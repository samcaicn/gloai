package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestView
import com.wangzan101.mika.core.proactive.DigestViewLine
import com.wangzan101.mika.core.proactive.NoticeSource
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val NOTICES_READ = "notices_read"

/**
 * 读从各个 App 收到的通知(这两天的原文,和存着的各天、各周、各月的归纳):用户问「今天都收到了什么」「某某说了什么」「上周有什么事」时用,不用去一个个打开 App 看。
 * - `view=summary`(默认):这一天各 App 收到几段,加上已经写好的分段归纳(每句是模型照着一批通知写的;多了分页)。
 * - `view=messages`:通知原文,由旧到新,分页;可以按 App、按字样筛。
 * - `view=days` / `weeks` / `months`:存着的各天、各周、各月的归纳(比原文留得久),新的在前,分页;问起前几天、上周、上个月时用。
 *
 * 给出来的不是全部时都明说:记录从几点起才全(`recordedSince`、`incomplete`)、后面还有没给的(`nextOffset`)、
 * 有几个 App 因为来源档位不够格没给(`appsWithheld`)。读不出来是报错,不是「什么都没收到」。
 *
 * 只读,不碰屏幕。数据由 App 给([com.wangzan101.mika.core.proactive.NoticeSource]);没给(功能没开、不是用户在手机上发起的任务)时不出现。
 * 读到的都是别的 App、别人写的字:每一样都套不可信标记(里面自带的标记先弄坏)。不作为观察记进核查的账本;
 * 进运行记录前文字遮掉(见 [com.wangzan101.mika.core.run.record.Redactor.result]):通知原文只在本机留两天,不能借运行记录多留。
 * 读过通知的任务,结束后不自动提取记忆(见 RunLearning)。
 */
internal object NoticesRead : AgentTool {
    /** 一页里各段原文加起来大约多少字(至少给一段)。 */
    const val PAGE_CHARS = 6_000
    /** 一段原文最多留多少字。 */
    private const val TEXT_CHARS = 300
    private const val NAME_CHARS = 80
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    override val verb = Verb.NoticesRead
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(NOTICES_READ,
        Prompts.tool(NOTICES_READ, "page" to PAGE_CHARS),
        mapOf(
            "day" to str("summary and messages views only. Which day: today (default) or yesterday.", listOf("today", "yesterday")),
            "view" to str("summary (default): counts per app and the notes already written about that day. messages: the notification text itself, oldest first. " +
                "days, weeks, months: the daily, weekly or monthly digests Mika has stored, newest first.", listOf("summary", "messages", "days", "weeks", "months")),
            "app" to str("Only this app: its name or package as listed by a summary call. Leave out for all apps."),
            "query" to str("Not for summary. Keep only items whose chat name, sender or text contains this exact piece of text (one name or phrase, not a list of keywords)."),
            "offset" to int("Item to start from, taken from nextOffset of the previous call with the same day and view (default 0).", 0),
        ), emptyList())

    override fun available(ctx: ToolContext): Boolean {
        val spec = ctx.config.runSpec
        // 只给用户在手机上发起的任务;条件检查这类只读运行、别处来的任务都不给
        return ctx.config.notices != null && (spec == null || (spec.provenance.origin == RunOrigin.Ui && !spec.readOnly))
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val source = ctx.config.notices!!
        val zone = source.zone
        val yesterday = a.str("day") == "yesterday"
        val viewName = a.str("view")
        val messages = viewName == "messages"
        val app = a.str("app", max = 200)?.trim()?.takeIf { it.isNotEmpty() }
        val query = a.str("query", max = 200)?.trim()?.takeIf { it.isNotEmpty() }
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        LEVELS[viewName]?.let { return digests(source, viewName!!, it, app, query, offset) }

        val day = Instant.ofEpochMilli(source.now()).atZone(zone).toLocalDate().let { if (yesterday) it.minusDays(1) else it }
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 读不出来不能装成「这一天什么都没有」
        val view = source.read(from, to) ?: return fail("notices_unavailable",
            "The notification record could not be read just now (it may have just been turned off, or the store is busy). This does not mean nothing came in: tell the user it could not be read.")
        // App 的名字是那个 App 自己起的:收一遍再给(见 NoticeFilter.appName)
        fun label(pkg: String) = NoticeFilter.appName(source.appLabel(pkg), pkg)
        fun wanted(pkg: String) = app == null || pkg.equals(app, ignoreCase = true) || source.appLabel(pkg).contains(app, ignoreCase = true) || NoticeFilter.appName(app).let { it.isNotEmpty() && label(pkg).contains(it, ignoreCase = true) }
        fun time(ms: Long) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
        val entries = view.entries.filter { wanted(it.packageName) }

        return ToolExecution(okObj {
            put("day", if (yesterday) "yesterday" else "today")
            // 记录是从什么时候开始的:更早的、锁屏期间没留在通知栏里的,这里没有
            put("recordedSince", STAMP.format(Instant.ofEpochMilli(view.sinceMs).atZone(zone)))
            if (view.sinceMs > from) put("incomplete", true)
            // 有通知、但来源档位不够格没给出来的 App:只说有几个
            if (view.withheldApps > 0) put("appsWithheld", view.withheldApps)
            if (!messages) {
                put("apps", JsonArray(entries.groupBy { it.packageName }.map { (pkg, list) ->
                    buildJsonObject { put("app", label(pkg)); put("package", pkg); put("notifications", list.size) }
                }))
                val notes = view.notes.filter { wanted(it.packageName) }
                val page = ArrayList<DigestNote>()
                var chars = 0
                for (n in notes.drop(offset)) {
                    val size = n.text.length.coerceAtMost(TEXT_CHARS) + (n.title?.length ?: 0).coerceAtMost(NAME_CHARS)
                    if (page.isNotEmpty() && chars + size > PAGE_CHARS) break
                    page += n
                    chars += size
                }
                // 分段归纳那份这会儿读不出来:下面是空的,但不是「没有」
                if (view.notesUnavailable) put("notesUnavailable", true)
                put("notesTotal", notes.size)
                put("notes", JsonArray(page.mapIndexed { i, n ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("time", time(n.fromMs).let { f -> time(n.toMs).let { t -> if (t == f) f else "$f-$t" } })
                        put("app", label(n.packageName))
                        n.title?.takeIf(String::isNotBlank)?.let { put("chat", untrusted(it, NAME_CHARS)) }
                        put("text", untrusted(n.text, TEXT_CHARS))
                    }
                }))
                val next = offset + page.size
                if (next < notes.size) put("nextOffset", next)
            } else {
                val hits = if (query == null) entries else entries.filter { e -> listOfNotNull(e.title, e.sender, e.text).any { it.contains(query, ignoreCase = true) } }
                val page = ArrayList<NoticeEntry>()
                var chars = 0
                for (e in hits.drop(offset)) {
                    val size = e.text.length.coerceAtMost(TEXT_CHARS) + e.title.length.coerceAtMost(NAME_CHARS)
                    if (page.isNotEmpty() && chars + size > PAGE_CHARS) break
                    page += e
                    chars += size
                }
                put("total", hits.size)
                put("items", JsonArray(page.mapIndexed { i, e ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("time", time(e.atMs))
                        put("app", label(e.packageName))
                        e.group?.let { put("kind", if (it) "group chat" else "private chat") }
                        if (e.title.isNotBlank()) put("chat", untrusted(e.title, NAME_CHARS))
                        e.sender?.takeIf(String::isNotBlank)?.let { put("sender", untrusted(it, NAME_CHARS)) }
                        put("text", untrusted(e.text, TEXT_CHARS))
                    }
                }))
                val next = offset + page.size
                if (next < hits.size) put("nextOffset", next)
            }
        })
    }

    private val LEVELS = mapOf("days" to DigestLevel.Day, "weeks" to DigestLevel.Week, "months" to DigestLevel.Month)
    private val DAY = DateTimeFormatter.ISO_LOCAL_DATE
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH)

    /** 存着的各天(各周、各月)的归纳:一句一项,新的那份在前,同一份里按原来的次序;分页、筛选和原文一样。 */
    private suspend fun digests(source: NoticeSource, viewName: String, level: DigestLevel, app: String?, query: String?, offset: Int): ToolExecution {
        val got = source.digests(level) ?: return fail("notices_unavailable",
            "The stored digests could not be read just now (they may have just been turned off, or the store is busy). This does not mean there are none: tell the user they could not be read.")
        val zone = source.zone
        // App 的名字是那个 App 自己起的:收一遍再给(见 NoticeFilter.appName)
        fun label(pkg: String) = NoticeFilter.appName(source.appLabel(pkg), pkg)
        fun wanted(pkg: String) = app == null || pkg.equals(app, ignoreCase = true) || source.appLabel(pkg).contains(app, ignoreCase = true) || NoticeFilter.appName(app).let { it.isNotEmpty() && label(pkg).contains(it, ignoreCase = true) }
        fun date(day: Long) = DAY.format(LocalDate.ofEpochDay(day))
        // 这一份管的是哪几天:日期是我们自己写的。月归纳管的是结束在这个月的那几周,和日历上的月份差几天,所以两样都写
        fun period(d: DigestView) = when (level) {
            DigestLevel.Day -> date(d.start)
            DigestLevel.Week -> "${date(d.start)} to ${date(d.end)}"
            DigestLevel.Month -> "${MONTH.format(LocalDate.ofEpochDay(d.end))} (${date(d.start)} to ${date(d.end)})"
        }
        val hits = got.digests.flatMap { d ->
            d.lines.filter { l -> (app == null || l.apps.any(::wanted)) && (query == null || l.text.contains(query, ignoreCase = true)) }.map { d to it }
        }
        val page = ArrayList<Pair<DigestView, DigestViewLine>>()
        var chars = 0
        for (h in hits.drop(offset)) {
            val size = h.second.text.length.coerceAtMost(TEXT_CHARS)
            if (page.isNotEmpty() && chars + size > PAGE_CHARS) break
            page += h
            chars += size
        }
        return ToolExecution(okObj {
            put("view", viewName)
            // 存着几份(不管筛没筛):一份都没有和「筛完没有」要分得清
            put("stored", got.digests.size)
            if (got.withheldLines > 0) put("linesWithheld", got.withheldLines)
            put("total", hits.size)
            put("items", JsonArray(page.mapIndexed { i, (d, l) ->
                buildJsonObject {
                    put("n", offset + i)
                    put("period", period(d))
                    // 那段时间还没过完时做的:只到做的那一刻为止
                    if (d.partial) put("soFarAsOf", STAMP.format(Instant.ofEpochMilli(d.madeAtMs).atZone(zone)))
                    // 做这一份时有几句放不下、没看到:这一份没有照着那段时间的全部写
                    if (d.leftOut > 0) put("inputsLeftOut", d.leftOut)
                    put("apps", JsonArray(l.apps.map { JsonPrimitive(label(it)) }))
                    put("text", untrusted(l.text, TEXT_CHARS))
                }
            }))
            val next = offset + page.size
            if (next < hits.size) put("nextOffset", next)
        })
    }

    private fun untrusted(s: String, max: Int): String = NoticeTurn.untrusted(if (s.length <= max) s else s.take(max - 1) + "…")
}
