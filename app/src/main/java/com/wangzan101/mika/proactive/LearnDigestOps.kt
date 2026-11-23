package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeDay
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestView
import com.wangzan101.mika.core.proactive.DigestViewLine
import com.wangzan101.mika.core.proactive.DigestsView
import com.wangzan101.mika.core.proactive.NoticeSpan
import com.wangzan101.mika.core.proactive.SpanKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_ALL
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_NOTE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_DAY
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_WEEK
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_MONTH
import com.wangzan101.mika.proactive.LearnRunner.Companion.AUTO_SPANS_MAX

// 学习:日/周/月摘要的读写、删除与触发
/**
 * 任务读存着的归纳用([NoticeSource.digests]):各天(各周、各月)的归纳,新的在前;每份只给依据的 App 档位都够格的句子,
 * 一句都给不出来的那份不列。条件和 [notices] 一样;存着的那份读不出来时为 null。在串行协程上调。
 */
internal fun LearnRunner.digests(which: DigestLevel, level: LearnLevel, tier: (String) -> SourceTier): DigestsView? {
    if (!on() || suspended() || !digest() || level.intoTasks.isEmpty()) return null
    val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
    val pool = freshDigest() ?: return null
    var withheld = 0
    fun view(start: Long, end: Long, madeAtMs: Long, partial: Boolean, lines: List<DigestLine>, leftOut: Int): DigestView? {
        val (given, rest) = lines.filter { it.apps.isNotEmpty() }.partition { it.apps.all(ok) }
        withheld += rest.size
        return if (given.isEmpty()) null else DigestView(start, end, madeAtMs, partial, given.map { DigestViewLine(it.text, it.apps) }, leftOut)
    }
    val out = when (which) {
        DigestLevel.Day -> pool.days().mapNotNull { view(it.day, it.day, it.madeAtMs, it.partial, it.lines, it.leftOut) }
        DigestLevel.Week -> pool.spans(SpanKind.Week).mapNotNull { view(it.start, it.start + 6, it.madeAtMs, it.partial, it.lines, it.leftOut) }
        DigestLevel.Month -> pool.spans(SpanKind.Month).mapNotNull {
            val cover = DigestPool.monthCover(it.start)
            view(cover.first, cover.last, it.madeAtMs, it.partial, it.lines, it.leftOut)
        }
    }
    return DigestsView(out, withheld)
}

/**
 * 要拿归纳去用(给任务、挑该做哪一份)之前:过了留存期的先去掉,有去掉的就存。
 * 到期不靠定时器(息屏不干活),谁来读谁先清——不然不开「Mika 知道了什么」那一页,过期的归纳照样会被带进任务。
 */
internal fun LearnRunner.freshDigest(): DigestPool? = loadedDigest()?.also { if (it.expire(dayOf(clock()))) saveDigest(it) }

/** 没读出来时读一次;读不出来(密钥库一时不可用、IO)为 null,下次再试,绝不拿空的去盖文件。 */
internal fun LearnRunner.loadedDigest(): DigestPool? {
    digestPool?.let { return it }
    return try {
        DigestPool(digestStore.load()).also {
            digestPool = it
            notesFlow.value = it.all()
            daysFlow.value = it.days()
            weeksFlow.value = it.spans(SpanKind.Week)
            monthsFlow.value = it.spans(SpanKind.Month)
            removedFlow.value = removedOf(it)
            removedDaysFlow.value = it.removedDays()
            // 记着的还没落盘的删除(当时读不出来的、删了没存上的):现在补做。存上了才从记着的里面划掉(见 saveDigest)
            val pending = purges.digestPurges
            if (pending.isNotEmpty()) {
                applyDigestPurges(it, pending)
                digestPurgesApplied = digestPurgesApplied + pending
                saveDigest(it)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn digest unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** 存上了返回 true。 */
internal fun LearnRunner.saveDigest(pool: DigestPool): Boolean {
    notesFlow.value = pool.all()
    daysFlow.value = pool.days()
    weeksFlow.value = pool.spans(SpanKind.Week)
    monthsFlow.value = pool.spans(SpanKind.Month)
    removedFlow.value = removedOf(pool)
    removedDaysFlow.value = pool.removedDays()
    return try {
        digestStore.save(pool.data())
        digestUnsaved = false
        // 做过的删除存上了:不再记着,免得以后再做一遍、把这之后新攒的也删掉
        if (digestPurgesApplied.isNotEmpty()) {
            purges.digestPurges = purges.digestPurges - digestPurgesApplied
            digestPurgesApplied = emptySet()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn digest save failed: ${e.javaClass.simpleName}")
        digestUnsaved = true
        false
    }
}

/** 内存里的归纳退回到 [data] 那时的样子(页面跟着变)。记着的删除不受影响:它们已经做在 [data] 里了,存上之前一直记着。 */
internal fun LearnRunner.restoreDigest(data: DigestData) {
    val pool = DigestPool(data)
    digestPool = pool
    notesFlow.value = pool.all()
    daysFlow.value = pool.days()
    weeksFlow.value = pool.spans(SpanKind.Week)
    monthsFlow.value = pool.spans(SpanKind.Month)
    removedFlow.value = removedOf(pool)
    removedDaysFlow.value = pool.removedDays()
}

internal fun LearnRunner.removedOf(pool: DigestPool): Set<Pair<SpanKind, Long>> =
    SpanKind.entries.flatMapTo(HashSet()) { kind -> pool.removedSpans(kind).map { kind to it } }

/** 用户删掉一句。 */
internal fun LearnRunner.deleteNote(id: String) = digestRemove(setOf(MARK_NOTE + id))

/** 整份删掉:内存里的和文件(读不出来时也删)。 */
internal fun LearnRunner.dropDigest() {
    if (notesApp != null || dayInFlight != null || spanInFlight != null) stopRequest()
    digestPool = null
    digestUnsaved = false
    todayWanted = false
    weekWanted = false
    monthWanted = false
    dayTries.clear()
    partialTries.clear()
    spanTries.clear()
    if (notesFlow.value.isNotEmpty()) notesFlow.value = emptyList()
    if (daysFlow.value.isNotEmpty()) daysFlow.value = emptyList()
    if (weeksFlow.value.isNotEmpty()) weeksFlow.value = emptyList()
    if (monthsFlow.value.isNotEmpty()) monthsFlow.value = emptyList()
    if (removedFlow.value.isNotEmpty()) removedFlow.value = emptySet()
    if (removedDaysFlow.value.isNotEmpty()) removedDaysFlow.value = emptySet()
    // 同 dropJournal:删掉了就没有还要补做的;删不掉的记一个「全清」,读得出来时照它清空
    digestPurgesApplied = emptySet()
    purges.digestPurges = if (wipe("digest") { digestStore.wipe() }) emptySet() else setOf(MARK_ALL)
}

/** 话题没了:事件上、联系人上都不再指着它。 */
internal fun LearnRunner.topicsGone(ids: Set<String>) {
    if (ids.isEmpty()) return
    buffer.forgetTopics(ids)
    val c = contactPool ?: loadedContacts() ?: return
    if (c.forgetTopics(ids)) saveContacts(c)
}

/**
 * 现在该做哪一天的当日归纳(第 2 级):用户刚要了「今天到现在的」(今天有句子才做);否则是今天之前、有句子、还没有完整归纳、
 * 那天的通知也都做过分段归纳的最早的一天;都没有,再看今天「到现在为止」的要不要自动做一份([DigestPool.todayAutoDue])。
 * 在这个进程里试过 [LearnTuning.DAY_TRIES_MAX] 次还不成的不再试。没有为 null。
 */
internal fun LearnRunner.dayToDigest(now: Long): Pair<Long, Boolean>? {
    if (!digest()) {
        // 「当日消息归纳」关着:东西还留着,期限照算。已经读进来的那份照样清;不为清它专门去读文件
        // (这时没人拿得到这些归纳——任务不给,页面一打开就清)
        digestPool?.let { if (it.expire(dayOf(now))) saveDigest(it) }
        return null
    }
    // 每次看有没有归纳要做,都先把到期的去掉:存着的归纳按期删,不靠有人打开页面或任务来读
    val pool = freshDigest() ?: return null
    val today = dayOf(now)
    if (todayWanted) {
        if (pool.dayInput(today).isNotEmpty()) return today to true
        todayWanted = false
    }
    val j = journal
    pool.dueDay(today) { d ->
        (dayTries[d] ?: 0) < LearnTuning.DAY_TRIES_MAX && (j == null || !j.hasPending(startOf(d), startOf(d + 1), eligible))
    }?.let { return it to false }
    // 今天的不用等用户去点:句子攒够了、离上一份够久了就自动做一份「到现在为止」的
    if ((partialTries[today] ?: 0) < LearnTuning.DAY_TRIES_MAX && pool.todayAutoDue(today, now)) return today to true
    return null
}

internal fun LearnRunner.startOf(day: Long): Long = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone()).toInstant().toEpochMilli()

/** 用户点了「归纳今天到现在的」:今天有分段归纳就做一份(标着是到现在为止的),过完这一天会重做完整的。 */
internal fun LearnRunner.digestToday() {
    if (!on() || suspended() || !digest()) return
    todayWanted = true
    // 用户在看这一页:失败后停着的也不再停
    holdUntilMs = 0L
    if (host != null) evaluate()
}

/** 用户删掉某一天的当日归纳。 */
internal fun LearnRunner.deleteDay(day: Long) {
    loadedDigest() ?: return
    if (dayInFlight == day) stopRequest()
    if (day == dayOf(clock())) todayWanted = false
    digestRemove(setOf(MARK_DAY + day))
}

/**
 * 当日消息归纳的第 2 级:把 [day] 这一天各个 App 的分段归纳合成一份。放进请求的只有第 1 级的句子,没有通知原文。
 * 同意、建模型、失败后停一段都和别的批一样。
 */
internal fun LearnRunner.sendDay(scope: CoroutineScope, day: Long, partial: Boolean) {
    val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
    if (consent() == null) return publish(LearnStatus.NeedsConsent)
    val input = pool.dayInput(day)
    if (input.isEmpty()) return publish(LearnStatus.Idle)
    sendRollup(
        scope, "day digest", ProactiveCall.Day, pool,
        begin = {
            dayInFlight = day
            if (partial) todayWanted = false
        },
        request = { model, lang, z -> NoticeDay(model, lang).digest(input, z, appLabel) },
        apply = {
            // 途中被清掉的 App 的句子:依据过它的那几句由 setDay 整句丢掉
            pool.setDay(day, input, it.lines, clock(), partial, it.brief)
            "${input.size} notes, ${pool.day(day)?.lines?.size ?: 0} lines, partial=$partial, brief=${pool.day(day)?.brief?.isNotEmpty() == true}"
        },
        tried = { (if (partial) partialTries else dayTries).let { it[day] = (it[day] ?: 0) + 1 } },
    )
}

/**
 * 现在该做哪一周、哪个月的归纳(第 3、4 级):用户刚要了「本周(本月)到现在的」(有东西可归纳才做);否则是已经过完、
 * 下一级都做完了、还没有完整归纳的最早的一周,没有该做的周再看月。下一级一句话都没有的,不问模型,直接记成做过了。
 * 在这个进程里试过 [LearnTuning.SPAN_TRIES_MAX] 次还不成的不再试。没有为 null。
 */
internal fun LearnRunner.spanToDigest(now: Long): Triple<SpanKind, Long, Boolean>? {
    if (!digest()) return null
    val pool = loadedDigest() ?: return null
    val today = dayOf(now)
    val j = journal
    fun given(kind: SpanKind, start: Long) = (spanTries[kind to start] ?: 0) >= LearnTuning.SPAN_TRIES_MAX
    // 自动做的:只拿完整的下一级当输入;那段时间一句都没有的直接记下(上面一级不必等它)
    fun auto(kind: SpanKind, due: () -> Long?): Long? {
        repeat(AUTO_SPANS_MAX) {
            val start = due() ?: return null
            if (pool.spanInput(kind, start, withPartial = false).isNotEmpty()) return start
            pool.setSpan(kind, start, emptyList(), emptyList(), now, partial = false)
            saveDigest(pool)
            log("${kind.name.lowercase()} digest: nothing to merge, recorded empty")
        }
        return null
    }
    if (weekWanted) {
        val week = DigestPool.weekOf(today)
        if (pool.spanInput(SpanKind.Week, week, withPartial = true).isNotEmpty()) return Triple(SpanKind.Week, week, true)
        weekWanted = false
    }
    auto(SpanKind.Week) {
        pool.dueWeek(
            today,
            // 还欠着当日归纳的日子先等它(试了几次都不成的不等);还有通知没做过分段归纳的也等
            waitForDay = { d, owed ->
                (owed && (dayTries[d] ?: 0) < LearnTuning.DAY_TRIES_MAX) || (j != null && j.hasPending(startOf(d), startOf(d + 1), eligible))
            },
            skip = { given(SpanKind.Week, it) },
        )
    }?.let { return Triple(SpanKind.Week, it, false) }
    if (monthWanted) {
        val month = DigestPool.monthOf(today)
        if (pool.spanInput(SpanKind.Month, month, withPartial = true).isNotEmpty()) return Triple(SpanKind.Month, month, true)
        monthWanted = false
    }
    auto(SpanKind.Month) {
        pool.dueMonth(today, waitForWeek = { w, owed -> owed && !given(SpanKind.Week, w) }, skip = { given(SpanKind.Month, it) })
    }?.let { return Triple(SpanKind.Month, it, false) }
    return null
}

/**
 * 用户点了「归纳本周到现在的」:本周有当日归纳就做一份(标着是到现在为止的),过完这一周会重做完整的。
 * 今天又来了新句子的话,先把今天到现在的当日归纳重做一遍,再做这一周的。
 */
internal fun LearnRunner.digestWeek() {
    if (!on() || suspended() || !digest()) return
    if (loadedDigest()?.todayStale(dayOf(clock())) == true) todayWanted = true
    weekWanted = true
    holdUntilMs = 0L
    if (host != null) evaluate()
}

/**
 * 用户点了「归纳本月到现在的」:先把今天、本周(它算在这个月里的话)到现在的做了,再做这个月的。
 * 本周的归纳被用户删掉了的话不顺带做回来(要的是这个月的,不是它);今天的同理(见 [DigestPool.todayStale])。
 */
internal fun LearnRunner.digestMonth() {
    if (!on() || suspended() || !digest()) return
    val today = dayOf(clock())
    val pool = loadedDigest()
    if (pool?.todayStale(today) == true) todayWanted = true
    val week = DigestPool.weekOf(today)
    if (DigestPool.monthOfWeek(week) == DigestPool.monthOf(today) && pool?.removedSpans(SpanKind.Week)?.contains(week) != true) weekWanted = true
    monthWanted = true
    holdUntilMs = 0L
    if (host != null) evaluate()
}

/** 用户删掉某一周(某个月)的归纳。 */
internal fun LearnRunner.deleteSpan(kind: SpanKind, start: Long) {
    loadedDigest() ?: return
    if (spanInFlight == kind to start) stopRequest()
    // 刚点过「归纳到现在」还没做的:删了就不做了
    val today = dayOf(clock())
    if (kind == SpanKind.Week && start == DigestPool.weekOf(today)) weekWanted = false
    if (kind == SpanKind.Month && start == DigestPool.monthOf(today)) monthWanted = false
    digestRemove(setOf((if (kind == SpanKind.Week) MARK_WEEK else MARK_MONTH) + start))
}

/**
 * 当日消息归纳的第 3、4 级:把这一周各天的当日归纳(这个月各周的周归纳)合成一份。放进请求的只有下一级的句子。
 * [partial] 为 true 是用户要的「到现在为止」:下一级里还没过完的那份也放进去。
 */
internal fun LearnRunner.sendSpan(scope: CoroutineScope, kind: SpanKind, start: Long, partial: Boolean) {
    val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
    if (consent() == null) return publish(LearnStatus.NeedsConsent)
    val input = pool.spanInput(kind, start, withPartial = partial)
    if (input.isEmpty()) return publish(LearnStatus.Idle)
    val name = "${kind.name.lowercase()} digest"
    sendRollup(
        scope, name, ProactiveCall.Span, pool,
        begin = {
            spanInFlight = kind to start
            if (partial) { if (kind == SpanKind.Week) weekWanted = false else monthWanted = false }
        },
        request = { model, lang, _ -> NoticeSpan(model, lang).digest(kind, start, input, appLabel) },
        apply = {
            // 途中被删掉、被清掉 App 的句子:依据过它的那几句由 setSpan 整句丢掉
            pool.setSpan(kind, start, input, it.lines, clock(), partial, it.brief)
            "${input.size} lines in, ${pool.span(kind, start)?.lines?.size ?: 0} lines, partial=$partial, brief=${pool.span(kind, start)?.brief?.isNotEmpty() == true}"
        },
        tried = { spanTries[kind to start] = (spanTries[kind to start] ?: 0) + 1 },
    )
}
