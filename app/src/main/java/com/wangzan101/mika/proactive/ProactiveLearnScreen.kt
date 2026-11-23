package com.wangzan101.mika.proactive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.ContactType
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.TopicKind
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「Mika 知道了什么」里的各级页面。最上面是索引(栈是空的):每天、每周、每月各一行,话题、联系人、清除各一个入口。
 * 往下:某一天([Day])、某一周或某个月([Span])的句子;再往下是一句的依据([DayLine]、[SpanLine])和一句分段归纳
 * 那段时间的通知原文([Note])。
 *
 * 用稳定的东西认(哪一天、哪一周、哪一句、句子的 id),不用列表里的位置:下面的数据随时会变(重做、删除往上传、到期)。
 * 指的东西不在了就退回上一级([KnowData.valid]),删除也就不会落到「现在排在那个位置上的另一条」头上。
 */
internal sealed interface KnowPage {
    data object Topics : KnowPage
    data object Contacts : KnowPage
    data object Clear : KnowPage
    data class Day(val day: Long) : KnowPage
    data class Span(val kind: SpanKind, val start: Long) : KnowPage
    /** 当日归纳里的一句:看它依据的分段归纳。 */
    data class DayLine(val day: Long, val line: DigestLine) : KnowPage
    /** 周、月归纳里的一句:看它出自哪几天、哪几周。 */
    data class SpanLine(val kind: SpanKind, val start: Long, val line: DigestLine) : KnowPage
    /** 一句分段归纳:看它那段时间的通知原文。 */
    data class Note(val id: String) : KnowPage
}

/** 这一页存着的归纳(运行时那几条流此刻的值),和由它算出来的:索引上列哪些、某一页指的东西还在不在。 */
internal class KnowData(
    val today: Long,
    /** 现在能做新的归纳(总开关、「当日消息归纳」都开着,模型确认过):今天、本周、本月即使还没有东西也各占一行。 */
    val canMake: Boolean,
    val notes: List<DigestNote>,
    val days: List<DayDigest>,
    val weeks: List<SpanDigest>,
    val months: List<SpanDigest>,
) {
    fun spans(kind: SpanKind): List<SpanDigest> = if (kind == SpanKind.Week) weeks else months

    fun current(kind: SpanKind): Long = if (kind == SpanKind.Week) DigestPool.weekOf(today) else DigestPool.monthOf(today)

    fun day(day: Long): DayDigest? = days.firstOrNull { it.day == day }

    fun span(kind: SpanKind, start: Long): SpanDigest? = spans(kind).firstOrNull { it.start == start }

    /** 索引上「每天」列的日子,新的在前:有当日归纳的、还有分段归纳的(归纳还没做出来、或被删掉了,句子还在),能做时加上今天。 */
    fun dayKeys(): List<Long> =
        (days.map { it.day } + notes.map { it.day } + if (canMake) listOf(today) else emptyList()).distinct().sortedDescending()

    /** 索引上「每周」「每月」列的,新的在前:存着的,能做时加上本周(本月)。 */
    fun spanKeys(kind: SpanKind): List<Long> =
        (spans(kind).map { it.start } + if (canMake) listOf(current(kind)) else emptyList()).distinct().sortedDescending()

    fun valid(page: KnowPage): Boolean = when (page) {
        KnowPage.Topics, KnowPage.Contacts, KnowPage.Clear -> true
        is KnowPage.Day -> page.day in dayKeys()
        is KnowPage.Span -> page.start in spanKeys(page.kind)
        is KnowPage.DayLine -> day(page.day)?.lines?.contains(page.line) == true
        is KnowPage.SpanLine -> span(page.kind, page.start)?.lines?.contains(page.line) == true
        is KnowPage.Note -> notes.any { it.id == page.id }
    }
}

/** 各级页面都要用的那些:数据、怎么起名字、怎么写日期、往下一级和回上一级。 */
private class KnowUi(
    val runtime: ProactiveRuntime,
    val data: KnowData,
    val removedDays: Set<Long>,
    val removedSpans: Set<Pair<SpanKind, Long>>,
    val label: (String) -> String,
    val dayText: (Long) -> String,
    val monthText: (Long) -> String,
    val dateFormat: java.text.DateFormat,
    val timeFormat: java.text.DateFormat,
    /** 「归纳到现在」这会儿点了也做不了的原因;做得了为 null。 */
    val blockedNote: String?,
    val listState: (KnowPage) -> LazyListState,
    val open: (KnowPage) -> Unit,
    val up: () -> Unit,
) {
    /** 一个月的归纳管的是结束在这个月的那几周,和日历上的月份差几天:把实际管到的日子写出来。 */
    fun cover(month: Long): String = DigestPool.monthCover(month).let { "${dayText(it.first)} – ${dayText(it.last)}" }

    fun spanName(kind: SpanKind, start: Long): String =
        if (kind == SpanKind.Week) "${dayText(start)} – ${dayText(start + 6)}" else "${monthText(start)} (${cover(start)})"

    fun time(fromMs: Long, toMs: Long): String {
        val from = timeFormat.format(java.util.Date(fromMs))
        val to = timeFormat.format(java.util.Date(toMs))
        return if (to == from) from else "$from–$to"
    }

    fun noteSub(n: DigestNote): String = listOfNotNull(label(n.packageName), n.title, time(n.fromMs, n.toMs)).joinToString(" · ")

    fun apps(line: DigestLine): String = line.apps.joinToString(" · ") { label(it) }
}

/**
 * 主动能力 ›「Mika 知道了什么」:归纳出的内容,和它们的删除。分几级([KnowPage]):先是一页索引,点进去才列句子,
 * 再点一句看它是照着什么写的。设置都在上一页([ProactiveScreen]);这里不看开关也照样显示存着的东西(关着时东西还留着),
 * 只有「归纳到现在」那几行要开着、模型确认过才给。
 * 句子、概述、话题名、名字、通知原文都来自别处(模型照着通知写的、通知里的),一律当普通文字显示。
 *
 * 存着的东西能有几百行(一天几十上百句分段归纳,话题、联系人各几百个):索引页只有十几行,长的列表都在下一级,
 * 一行一项按需渲染([LazyPage]、[card])。
 */
@Composable
fun ProactiveLearnScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val runtime = app.proactive
    val enabled by runtime.enabled.collectAsState()
    val state by runtime.learnState.collectAsState()
    val topics by runtime.learnTopics.collectAsState()
    val topicsBlocked by runtime.learnTopicsBlocked.collectAsState()
    val removedSpans by runtime.learnDigestRemoved.collectAsState()
    val removedDays by runtime.learnDigestRemovedDays.collectAsState()
    val consent by runtime.consent.collectAsState()
    val level by runtime.learnLevel.collectAsState()
    val limit by runtime.triageLimit.collectAsState()
    val digestOn by runtime.learnDigest.collectAsState()
    val notes by runtime.learnDigestNotes.collectAsState()
    val days by runtime.learnDigestDays.collectAsState()
    val weeks by runtime.learnDigestWeeks.collectAsState()
    val months by runtime.learnDigestMonths.collectAsState()
    val contactsView by runtime.learnContactsView.collectAsState()
    val contacts = contactsView.contacts
    var editRelation by remember { mutableStateOf<ObservedContact?>(null) }
    var merging by remember { mutableStateOf<ObservedTopic?>(null) }
    // 模型配置和用量账本只在主动能力开着时才碰(和主动能力页一样);话题和删除关着时也能用
    var usageTick by remember { mutableStateOf(0) }
    val budgetOut = remember(enabled, state, limit, usageTick) {
        if (!enabled) false else runtime.triageUsage().let { (tokens, calls) ->
            ProactiveBudget.exhausted(tokens, calls, limit) != null
        }
    }
    if (enabled) {
        val version by remember { runtime.usageVersion() }.collectAsState()
        LaunchedEffect(version) { usageTick++ }
    }

    var target by remember { mutableStateOf<TriageTarget?>(null) }
    var targetRead by remember { mutableStateOf(0) }
    LaunchedEffect(enabled, consent, targetRead) { target = if (enabled) runtime.triageTarget() else null }
    LifecycleResumeEffect(Unit) {
        targetRead++
        // 打开(回到)本页时读出话题池并整理一遍
        runtime.onLearn { open() }
        onPauseOrDispose {}
    }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmClearApp by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        labels = withContext(Dispatchers.IO) {
            runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList()).toMap()
        }
    }
    fun label(pkg: String) = labels[pkg] ?: pkg
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    // 「11月9日」「2026年11月」:按系统语言和历法的写法(系统自己的格式器,不拿它的写法去喂别的格式器)
    val dayFormat = remember { android.icu.text.DateFormat.getInstanceForSkeleton("MMMd", java.util.Locale.getDefault()) }
    val monthFormat = remember { android.icu.text.DateFormat.getInstanceForSkeleton("yMMMM", java.util.Locale.getDefault()) }
    fun dayText(epochDay: Long): String = dayFormat.format(java.util.Date(java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    fun monthText(epochDay: Long): String = monthFormat.format(java.util.Date(java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    val gate = TriageGate.of(enabled, consent, target)
    // 已经归纳出的东西关了开关也还留着(只是不再做新的)。「归纳到现在」那几行只在真能做的时候给
    val canMake = enabled && digestOn && gate == TriageGate.Ready
    val blockedNote = makeBlocked(budgetOut, state.status)?.let { stringResource(it) }
    val today = java.time.LocalDate.now().toEpochDay()
    val data = remember(today, canMake, notes, days, weeks, months) { KnowData(today, canMake, notes, days, weeks, months) }

    // 一级一级往下走的栈(空的是索引)。指的东西不在了的那几级不显示,并从栈里去掉
    var stack by remember { mutableStateOf(listOf<KnowPage>()) }
    val shown = stack.dropLastWhile { !data.valid(it) }
    LaunchedEffect(shown, stack) { if (shown != stack) stack = shown }
    // 各级页面滚到哪儿了:从下一级回来时还在原处;那一级从栈里出去了就不留
    val indexScroll = rememberScrollState()
    val listStates = remember { HashMap<KnowPage, LazyListState>() }
    LaunchedEffect(shown) { listStates.keys.retainAll(shown.toSet()) }
    val up = { if (shown.isEmpty()) onBack() else stack = shown.dropLast(1) }
    // 返回先退回上一级,到索引了才离开这一页
    BackHandler(onBack = up)
    var moreDays by remember { mutableStateOf(false) }
    var moreWeeks by remember { mutableStateOf(false) }
    var moreMonths by remember { mutableStateOf(false) }

    val ui = KnowUi(
        runtime, data, removedDays, removedSpans, ::label, ::dayText, ::monthText, dateFormat, timeFormat, blockedNote,
        listState = { listStates.getOrPut(it) { LazyListState() } },
        open = { stack = shown + it },
        up = up,
    )
    when (val page = shown.lastOrNull()) {
        null -> IndexPage(
            ui, indexScroll, topics.size, contacts.size,
            moreDays, { moreDays = true }, moreWeeks, { moreWeeks = true }, moreMonths, { moreMonths = true },
        )
        is KnowPage.Day -> DayPage(ui, page)
        is KnowPage.Span -> SpanPage(ui, page)
        is KnowPage.DayLine -> DayLinePage(ui, page)
        is KnowPage.SpanLine -> SpanLinePage(ui, page)
        is KnowPage.Note -> NotePage(ui, page)
        KnowPage.Topics -> TopicsPage(ui, topics, level, state.status == LearnStatus.StoreUnavailable) { merging = it }
        KnowPage.Contacts -> ContactsPage(ui, contacts, topics) { editRelation = it }
        KnowPage.Clear -> ClearPage(
            ui, remember(topics, contacts, notes, days, weeks, months, state.journalApps, labels) {
                clearableApps(topics, contacts, notes, days, weeks + months, state.journalApps).sortedBy { label(it).lowercase() }
            },
            topicsBlocked, contactsView.blocked, { confirmClearAll = true }, { confirmClearApp = it },
        )
    }

    if (confirmClearAll) {
        MikaDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.proactive_learn_clear_all)) },
            text = { Text(stringResource(R.string.proactive_learn_clear_all_body)) },
            confirmButton = {
                TextButton({
                    runtime.onLearn { clearAll() }
                    confirmClearAll = false
                }) { Text(stringResource(R.string.proactive_learn_delete)) }
            },
            dismissButton = { TextButton({ confirmClearAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    editRelation?.let { c ->
        // 关系由用户设:列出几种关系和「不确定」;「交回给 Mika 判断」是清掉用户设的
        MikaDialog(
            onDismissRequest = { editRelation = null },
            title = { Text(stringResource(R.string.proactive_learn_relation_title, c.name)) },
            text = {
                Column {
                    ContactRelation.entries.forEach { r ->
                        TextButton({
                            runtime.onLearn { setContactRelation(c.id, r) }
                            editRelation = null
                        }) { Text(stringResource(relationLabel(r)) + if (c.userRelation == r) " ✓" else "") }
                    }
                    if (c.userRelation != null) {
                        TextButton({
                            runtime.onLearn { setContactRelation(c.id, null) }
                            editRelation = null
                        }) { Text(stringResource(R.string.proactive_learn_relation_reset)) }
                    }
                }
            },
            // 点一项就设好并关掉,所以只有「取消」一个按钮。它要放在 confirmButton 上:MikaDialog 总会画出那一格,
            // 放在 dismissButton 上的话旁边会多出一个没有字的蓝色按钮(和「应用访问」里改分类的弹窗同一个写法)
            confirmButton = { TextButton({ editRelation = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    merging?.let { t ->
        // 把这个话题并进另一个:列出其他话题(名字像的排在前面),点一个就并好并关掉。并了分不开,弹窗里写明。
        // 话题可以有几百个:列表限高、能滚。
        // 先后只在弹窗打开时排一次:开着的时候话题池会变(来了一批归纳、有通知被点开),跟着重排的话,
        // 手指落下去的那一行可能已经换成了别的话题,而点一下就并了、分不开。中途没了的话题从列表里去掉
        val order = remember(t.id) { TopicPool.mergeCandidates(t, topics, System.currentTimeMillis()).map { it.id } }
        val others = remember(order, topics) { topics.associateBy { it.id }.let { now -> order.mapNotNull(now::get) } }
        MikaDialog(
            onDismissRequest = { merging = null },
            title = { Text(stringResource(R.string.proactive_learn_topic_merge_title, t.label)) },
            text = {
                Column {
                    Text(stringResource(R.string.proactive_learn_topic_merge_body))
                    LazyColumn(Modifier.heightIn(max = 320.dp).testTag("merge_targets")) {
                        items(others, key = { it.id }) { other ->
                            TextButton({
                                runtime.onLearn { mergeTopic(t.id, other.id) }
                                merging = null
                            }) { Text(other.label + " · " + stringResource(kindLabel(other.kind))) }
                        }
                    }
                }
            },
            // 只有「取消」一个按钮,放在 confirmButton 上(同上面改关系的弹窗)
            confirmButton = { TextButton({ merging = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmClearApp?.let { pkg ->
        MikaDialog(
            onDismissRequest = { confirmClearApp = null },
            title = { Text(stringResource(R.string.proactive_learn_clear_app, label(pkg))) },
            text = { Text(stringResource(R.string.proactive_learn_clear_app_body, label(pkg))) },
            confirmButton = {
                TextButton({
                    runtime.onLearn { clearApp(pkg) }
                    confirmClearApp = null
                }) { Text(stringResource(R.string.proactive_learn_delete)) }
            },
            dismissButton = { TextButton({ confirmClearApp = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---- 页面的骨架:长列表一行一项按需渲染 ----

/** 带返回的顶栏下面一个按需渲染的列表(和 [PageScaffold] 一样的留白,只是不一次把所有行都画出来)。 */
@Composable
private fun LazyPage(title: String, onBack: () -> Unit, state: LazyListState, content: LazyListScope.() -> Unit) {
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
                state = state,
                contentPadding = PaddingValues(start = MikaSpace.Page, end = MikaSpace.Page, top = MikaSpace.S4, bottom = MikaSpace.S24),
                content = content,
            )
        }
    }
}

/** 一张卡片里的各项:行([row],有分隔线)和说明([note],没有)。 */
private class CardRows {
    class Entry(val key: String?, val isRow: Boolean, val content: @Composable (first: Boolean) -> Unit)

    val entries = ArrayList<Entry>()

    /** [key]:这一行指的东西(句子的 id 之类),列表变了它也认得出是同一行;没有就按位置。[first]:它前面这张卡里还没有别的行。 */
    fun row(key: String? = null, content: @Composable (first: Boolean) -> Unit) {
        entries += Entry(key, true, content)
    }

    fun note(content: @Composable () -> Unit) {
        entries += Entry(null, false) { content() }
    }
}

/** 一张卡拆成懒加载的行时,每行的圆角:首行上圆、末行下圆。 */
private fun cardRowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(
        topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp,
    )
}

/**
 * 懒加载列表里的一张分组卡片([GroupCard] 的样子,拆成一行一项)。[id] 在这一页里不能重复。
 * 列表里的 key 重了会崩,所以行自己带的 key 万一重了(存着的数据里有两个一样的 id)就退回按位置。
 */
private fun LazyListScope.card(id: String, build: CardRows.() -> Unit) {
    val entries = CardRows().apply(build).entries
    if (entries.isEmpty()) return
    val firstRow = entries.indexOfFirst { it.isRow }
    val seen = HashSet<String>()
    val keys = entries.mapIndexed { i, e -> e.key?.let { "$id/k:$it" }?.takeIf(seen::add) ?: "$id/#$i" }
    itemsIndexed(entries, key = { i, _ -> keys[i] }, contentType = { _, e -> if (e.isRow) 1 else 0 }) { i, e ->
        Box(
            Modifier.fillMaxWidth().padding(bottom = if (i == entries.lastIndex) 12.dp else 0.dp)
                .clip(cardRowShape(i, entries.size)).background(MikaTheme.colors.surface),
        ) { e.content(i == firstRow) }
    }
}

private fun LazyListScope.label(id: String, text: @Composable () -> String) {
    item(key = "label:$id", contentType = 2) { GroupLabel(text()) }
}

// ---- 索引 ----

/** 一份归纳在索引上的那一行小字:有概述写概述;没有(老的、被清掉了)写「几句 · 哪些 App」;一句都没有的写 [empty]。 */
@Composable
private fun digestSub(ui: KnowUi, brief: String, lines: List<DigestLine>, empty: Int): String = when {
    lines.isEmpty() -> stringResource(empty)
    brief.isNotEmpty() -> brief
    else -> (listOf(stringResource(R.string.proactive_know_lines, lines.size)) + lines.flatMap { it.apps }.distinct().take(3).map(ui.label)).joinToString(" · ")
}

@Composable
private fun dayTitle(ui: KnowUi, day: Long): String = when (day) {
    ui.data.today -> stringResource(R.string.proactive_learn_day_today)
    ui.data.today - 1 -> stringResource(R.string.proactive_learn_day_yesterday)
    else -> ui.dayText(day)
}

@Composable
private fun daySub(ui: KnowUi, day: Long): String {
    val d = ui.data.day(day)
    val count = ui.data.notes.count { it.day == day }
    return when {
        d != null -> digestSub(ui, d.brief, d.lines, R.string.proactive_learn_day_nothing)
        day in ui.removedDays -> stringResource(R.string.proactive_know_removed)
        count > 0 -> stringResource(R.string.proactive_know_pending, count)
        else -> stringResource(R.string.proactive_learn_empty)
    }
}

@Composable
private fun spanTitle(ui: KnowUi, kind: SpanKind, start: Long): String = when {
    start != ui.data.current(kind) -> ui.spanName(kind, start)
    kind == SpanKind.Week -> stringResource(R.string.proactive_learn_week_this)
    else -> stringResource(R.string.proactive_learn_month_this)
}

@Composable
private fun spanSub(ui: KnowUi, kind: SpanKind, start: Long): String {
    val d = ui.data.span(kind, start)
    return when {
        d != null -> digestSub(ui, d.brief, d.lines, R.string.proactive_learn_span_nothing)
        (kind to start) in ui.removedSpans -> stringResource(R.string.proactive_know_removed)
        else -> stringResource(R.string.proactive_learn_empty)
    }
}

/** 索引上先列几行,更早的点一下再展开(每天最多三十多行、每周二十多行,不必懒加载,但也不必一上来全摆出来)。 */
private const val INDEX_DAYS = 7
private const val INDEX_WEEKS = 4
private const val INDEX_MONTHS = 3

@Composable
private fun IndexPage(
    ui: KnowUi, scroll: ScrollState, topics: Int, contacts: Int,
    moreDays: Boolean, onMoreDays: () -> Unit, moreWeeks: Boolean, onMoreWeeks: () -> Unit, moreMonths: Boolean, onMoreMonths: () -> Unit,
) {
    PageScaffold(stringResource(R.string.proactive_learn_title), ui.up, scrollState = scroll) {
        @Composable
        fun <T> section(title: Int, keys: List<T>, shown: Int, more: Boolean, onMore: () -> Unit, row: @Composable (T, Boolean) -> Unit) {
            if (keys.isEmpty()) return
            GroupLabel(stringResource(title))
            GroupCard {
                val visible = if (more) keys else keys.take(shown)
                visible.forEachIndexed { i, k -> row(k, i == 0) }
                if (visible.size < keys.size) {
                    ListRow(null, stringResource(R.string.proactive_know_older, keys.size - visible.size), onClick = onMore)
                }
            }
        }
        section(R.string.proactive_know_days, ui.data.dayKeys(), INDEX_DAYS, moreDays, onMoreDays) { day, first ->
            ListRow(null, dayTitle(ui, day), daySub(ui, day), first = first, onClick = { ui.open(KnowPage.Day(day)) }) { RowChevron() }
        }
        for (kind in SpanKind.entries) {
            val week = kind == SpanKind.Week
            section(
                if (week) R.string.proactive_know_weeks else R.string.proactive_know_months, ui.data.spanKeys(kind),
                if (week) INDEX_WEEKS else INDEX_MONTHS, if (week) moreWeeks else moreMonths, if (week) onMoreWeeks else onMoreMonths,
            ) { start, first ->
                ListRow(null, spanTitle(ui, kind, start), spanSub(ui, kind, start), first = first, onClick = { ui.open(KnowPage.Span(kind, start)) }) { RowChevron() }
            }
        }
        GroupLabel(stringResource(R.string.proactive_learn_topics))
        GroupCard {
            ListRow(
                null, stringResource(R.string.proactive_know_topics), stringResource(R.string.proactive_know_count, topics),
                first = true, onClick = { ui.open(KnowPage.Topics) },
            ) { RowChevron() }
            ListRow(
                null, stringResource(R.string.proactive_learn_contacts), stringResource(R.string.proactive_know_count, contacts),
                onClick = { ui.open(KnowPage.Contacts) },
            ) { RowChevron() }
        }
        GroupCard {
            ListRow(null, stringResource(R.string.proactive_know_clear), first = true, onClick = { ui.open(KnowPage.Clear) }) { RowChevron() }
        }
    }
}

// ---- 某一天:当日归纳的句子,和这一天的分段归纳 ----

@Composable
private fun DayPage(ui: KnowUi, page: KnowPage.Day) {
    val day = page.day
    val isToday = day == ui.data.today
    val d = ui.data.day(day)
    val mine = remember(ui.data.notes, day) { ui.data.notes.filter { it.day == day }.sortedByDescending { it.toMs } }
    val canMake = isToday && ui.data.canMake
    val title = if (isToday || day == ui.data.today - 1) dayTitle(ui, day) else stringResource(R.string.proactive_learn_day_of, ui.dayText(day))
    LazyPage(title, ui.up, ui.listState(page)) {
        card("digest") {
            dayNote(d, day in ui.removedDays, isToday, ui.data.canMake)?.let { text -> note { EmptyNote(stringResource(text)) } }
            d?.lines?.forEach { line ->
                row { first -> ListRow(null, line.text, ui.apps(line), first = first, onClick = { ui.open(KnowPage.DayLine(day, line)) }) { RowChevron() } }
            }
            // 这一天的句子太多、没能全放进去的:说出来,这一份不是照着全部写的
            if (d != null && d.leftOut > 0) note { EmptyNote(stringResource(R.string.proactive_learn_left_out, d.leftOut)) }
            if (canMake) row { first ->
                val made = d?.let { stringResource(R.string.proactive_learn_day_made, ui.timeFormat.format(java.util.Date(it.madeAtMs))) }
                // 预算用完了之类:点了也发不出去,写明原因,不给按钮(照样能点的话,点了没有任何反应)
                ListRow(
                    null, stringResource(R.string.proactive_learn_day_make), ui.blockedNote ?: made, first = first,
                    onClick = if (ui.blockedNote != null) null else ({ ui.runtime.onLearn { digestToday() } }),
                ) {
                    if (ui.blockedNote == null) TextButton({ ui.runtime.onLearn { digestToday() } }, Modifier.testTag("proactive-learn-day-make")) {
                        Text(stringResource(R.string.proactive_learn_day_make_action))
                    }
                }
            }
            if (d != null) row { first ->
                ListRow(null, stringResource(R.string.proactive_learn_delete), first = first, onClick = { ui.runtime.onLearn { deleteDay(day) } })
            }
        }
        label("notes") {
            if (isToday) stringResource(R.string.proactive_learn_digest_today) else stringResource(R.string.proactive_learn_digest_of, ui.dayText(day))
        }
        card("notes") {
            if (mine.isEmpty()) note {
                EmptyNote(stringResource(if (isToday) R.string.proactive_learn_digest_empty else R.string.proactive_know_notes_gone))
            }
            mine.forEach { n -> row(n.id) { first -> NoteRow(ui, n, first) } }
        }
    }
}

/** 一句分段归纳:点开看它那段时间的通知原文;右边删掉。 */
@Composable
private fun NoteRow(ui: KnowUi, n: DigestNote, first: Boolean) {
    ListRow(null, n.text, ui.noteSub(n), first = first, onClick = { ui.open(KnowPage.Note(n.id)) }) {
        TextButton({ ui.runtime.onLearn { deleteNote(n.id) } }) { Text(stringResource(R.string.proactive_learn_delete)) }
    }
}

// ---- 某一周、某个月 ----

@Composable
private fun SpanPage(ui: KnowUi, page: KnowPage.Span) {
    val kind = page.kind
    val start = page.start
    val isWeek = kind == SpanKind.Week
    val current = start == ui.data.current(kind)
    val d = ui.data.span(kind, start)
    val canMake = current && ui.data.canMake
    val title = when {
        current && isWeek -> stringResource(R.string.proactive_learn_week_this)
        current -> stringResource(R.string.proactive_learn_month_this) + " (${ui.cover(start)})"
        else -> stringResource(if (isWeek) R.string.proactive_learn_week_of else R.string.proactive_learn_month_of, ui.spanName(kind, start))
    }
    LazyPage(title, ui.up, ui.listState(page)) {
        card("digest") {
            spanNote(d, (kind to start) in ui.removedSpans, kind, canMake)?.let { text -> note { EmptyNote(stringResource(text)) } }
            d?.lines?.forEach { line ->
                // 一句下面写它是哪几天的(周归纳)、来自哪些 App
                val sub = ((if (isWeek) line.days.sorted().map(ui.dayText) else emptyList()) + line.apps.map(ui.label)).joinToString(" · ")
                row { first -> ListRow(null, line.text, sub, first = first, onClick = { ui.open(KnowPage.SpanLine(kind, start, line)) }) { RowChevron() } }
            }
            if (d != null && d.leftOut > 0) note { EmptyNote(stringResource(R.string.proactive_learn_left_out, d.leftOut)) }
            if (canMake) row { first ->
                val made = d?.let {
                    val at = java.util.Date(it.madeAtMs)
                    stringResource(R.string.proactive_learn_day_made, ui.dateFormat.format(at) + " " + ui.timeFormat.format(at))
                }
                val make = { ui.runtime.onLearn { if (isWeek) digestWeek() else digestMonth() } }
                ListRow(
                    null, stringResource(if (isWeek) R.string.proactive_learn_week_make else R.string.proactive_learn_month_make),
                    ui.blockedNote ?: made, first = first, onClick = if (ui.blockedNote != null) null else make,
                ) {
                    if (ui.blockedNote == null) TextButton(make, Modifier.testTag("proactive-learn-${kind.name.lowercase()}-make")) {
                        Text(stringResource(R.string.proactive_learn_day_make_action))
                    }
                }
            }
            if (d != null) row { first ->
                ListRow(null, stringResource(R.string.proactive_learn_delete), first = first, onClick = { ui.runtime.onLearn { deleteSpan(kind, start) } })
            }
        }
        // 这一周的各天(这个月的各周):这一份还没做出来的时候,从这里也看得到这段时间里已经有的东西
        if (isWeek) {
            val inWeek = ui.data.dayKeys().filter { DigestPool.weekOf(it) == start }
            if (inWeek.isNotEmpty()) {
                label("days") { stringResource(R.string.proactive_know_days) }
                card("days") {
                    inWeek.forEach { day ->
                        row(day.toString()) { first ->
                            ListRow(null, dayTitle(ui, day), daySub(ui, day), first = first, onClick = { ui.open(KnowPage.Day(day)) }) { RowChevron() }
                        }
                    }
                }
            }
        } else {
            val inMonth = ui.data.spanKeys(SpanKind.Week).filter { DigestPool.monthOfWeek(it) == start }
            if (inMonth.isNotEmpty()) {
                label("weeks") { stringResource(R.string.proactive_know_weeks) }
                card("weeks") {
                    inMonth.forEach { w ->
                        row(w.toString()) { first ->
                            ListRow(null, spanTitle(ui, SpanKind.Week, w), spanSub(ui, SpanKind.Week, w), first = first, onClick = { ui.open(KnowPage.Span(SpanKind.Week, w)) }) { RowChevron() }
                        }
                    }
                }
            }
        }
    }
}

// ---- 一句的依据 ----

/** 当日归纳里的一句是照着哪几句分段归纳写的(句子上记着它们的 id;分段归纳只留八天,过期的、删掉的说个数)。 */
@Composable
private fun DayLinePage(ui: KnowUi, page: KnowPage.DayLine) {
    val line = page.line
    val found = remember(ui.data.notes, line) { ui.data.notes.filter { it.id in line.notes }.sortedBy { it.fromMs } }
    val gone = line.notes.size - found.size
    LazyPage(stringResource(R.string.proactive_know_basis), ui.up, ui.listState(page)) {
        label("line") { stringResource(R.string.proactive_know_basis_line) }
        card("line") { row { first -> ListRow(null, line.text, ui.apps(line), first = first) } }
        label("notes") { stringResource(R.string.proactive_know_basis_notes) }
        card("notes") {
            if (found.isEmpty()) note { EmptyNote(stringResource(R.string.proactive_know_basis_none)) }
            found.forEach { n -> row(n.id) { first -> NoteRow(ui, n, first) } }
            if (found.isNotEmpty() && gone > 0) note { EmptyNote(stringResource(R.string.proactive_know_basis_gone, gone)) }
        }
    }
}

/**
 * 周、月归纳里的一句出自哪几天、哪几周:句子上只记到「哪几天(哪几周)」,没记是其中哪一句,所以点开是去那一天(那一周)的页面。
 * 那一份已经到期或删掉了的只写一句,不能点。
 */
@Composable
private fun SpanLinePage(ui: KnowUi, page: KnowPage.SpanLine) {
    val line = page.line
    val isWeek = page.kind == SpanKind.Week
    LazyPage(stringResource(R.string.proactive_know_basis), ui.up, ui.listState(page)) {
        label("line") { stringResource(R.string.proactive_know_basis_line) }
        card("line") { row { first -> ListRow(null, line.text, ui.apps(line), first = first) } }
        label("from") { stringResource(if (isWeek) R.string.proactive_know_basis_days else R.string.proactive_know_basis_weeks) }
        card("from") {
            val from = (if (isWeek) line.days else line.weeks).distinct().sorted()
            if (from.isEmpty()) note { EmptyNote(stringResource(R.string.proactive_know_basis_none)) }
            from.forEach { key ->
                row(key.toString()) { first ->
                    if (isWeek) {
                        val there = key in ui.data.dayKeys()
                        ListRow(
                            null, ui.dayText(key), if (there) daySub(ui, key) else stringResource(R.string.proactive_know_gone), first = first,
                            onClick = if (there) ({ ui.open(KnowPage.Day(key)) }) else null,
                        ) { if (there) RowChevron() }
                    } else {
                        val there = ui.data.span(SpanKind.Week, key) != null
                        ListRow(
                            null, ui.spanName(SpanKind.Week, key),
                            if (there) spanSub(ui, SpanKind.Week, key) else stringResource(R.string.proactive_know_gone), first = first,
                            onClick = if (there) ({ ui.open(KnowPage.Span(SpanKind.Week, key)) }) else null,
                        ) { if (there) RowChevron() }
                    }
                }
            }
        }
    }
}

/**
 * 一句分段归纳是照着哪些通知写的。句子只记了 App、会话名和起止时间,所以列的是那个会话(没记会话名的是那个 App)
 * 在那段时间里还存着的通知,可能比当时依据的多几段。原文只留两天:没有了的时候说清是到期了、开关关着被删了,还是读不出来。
 */
@Composable
private fun NotePage(ui: KnowUi, page: KnowPage.Note) {
    val n = ui.data.notes.firstOrNull { it.id == page.id } ?: return
    val raw by produceState<RawNotices?>(null, n) { value = ui.runtime.learnRaw(n) }
    LazyPage(stringResource(R.string.proactive_know_raw), ui.up, ui.listState(page)) {
        label("note") { stringResource(R.string.proactive_know_basis_line) }
        card("note") {
            row { first -> ListRow(null, n.text, ui.noteSub(n), first = first) }
            row { first ->
                ListRow(null, stringResource(R.string.proactive_know_delete_note), first = first, onClick = { ui.runtime.onLearn { deleteNote(n.id) } })
            }
        }
        label("raw") { stringResource(if (n.title != null) R.string.proactive_know_raw_label else R.string.proactive_know_raw_label_app) }
        card("raw") {
            when (val r = raw) {
                null -> Unit
                RawNotices.Off -> note { EmptyNote(stringResource(R.string.proactive_know_raw_off)) }
                RawNotices.Unavailable -> note { EmptyNote(stringResource(R.string.proactive_know_raw_unavailable)) }
                is RawNotices.Found -> {
                    if (r.entries.isEmpty()) note {
                        val expired = n.toMs < System.currentTimeMillis() - NoticeJournal.KEEP_MS
                        EmptyNote(stringResource(if (expired) R.string.proactive_know_raw_expired else R.string.proactive_know_raw_none))
                    }
                    r.entries.forEach { e ->
                        row { first ->
                            // 句子没记会话名的:这几段可能来自好几个会话,把各自的会话名写出来
                            val chat = e.title.takeIf { n.title == null && it.isNotBlank() }
                            ListRow(null, e.text, listOfNotNull(chat, e.sender, ui.timeFormat.format(java.util.Date(e.atMs))).joinToString(" · "), first = first)
                        }
                    }
                }
            }
        }
    }
}

// ---- 话题、联系人、清除 ----

@Composable
private fun TopicsPage(ui: KnowUi, topics: List<ObservedTopic>, level: LearnLevel, storeUnavailable: Boolean, onMerge: (ObservedTopic) -> Unit) {
    // 只有一个话题时没有可并的对象,不给「并入」
    val merge: ((ObservedTopic) -> Unit)? = if (topics.size > 1) onMerge else null
    // 几天算已确立跟着激进程度走。只在话题变了的时候重排,不是每次重组都排一遍
    val (sure, unsure) = remember(topics, level) {
        val now = System.currentTimeMillis()
        topics.sortedByDescending { it.score(now) }.partition { level.established(it) }
    }
    LazyPage(stringResource(R.string.proactive_know_topics), ui.up, ui.listState(KnowPage.Topics)) {
        card("sure") {
            if (sure.isEmpty()) note {
                EmptyNote(stringResource(if (storeUnavailable) R.string.proactive_learn_state_store else R.string.proactive_learn_empty))
            }
            sure.forEach { t -> row(t.id) { first -> TopicRow(t, first, ui.label, ui.dateFormat, merge?.let { { it(t) } }) { ui.runtime.onLearn { deleteTopic(t.id) } } } }
        }
        if (unsure.isNotEmpty()) {
            label("unsure") { stringResource(R.string.proactive_learn_unsure) }
            card("unsure") {
                unsure.forEach { t -> row(t.id) { first -> TopicRow(t, first, ui.label, ui.dateFormat, merge?.let { { it(t) } }) { ui.runtime.onLearn { deleteTopic(t.id) } } } }
            }
        }
    }
}

@Composable
private fun ContactsPage(ui: KnowUi, contacts: List<ObservedContact>, topics: List<ObservedTopic>, onRelation: (ObservedContact) -> Unit) {
    val (sure, unsure) = remember(contacts) {
        val now = System.currentTimeMillis()
        contacts.sortedByDescending { it.score(now) }.partition { it.established }
    }
    val topicLabels = remember(topics) { topics.associate { it.id to it.label } }
    LazyPage(stringResource(R.string.proactive_learn_contacts), ui.up, ui.listState(KnowPage.Contacts)) {
        card("sure") {
            if (sure.isEmpty()) note { EmptyNote(stringResource(R.string.proactive_learn_contacts_empty)) }
            sure.forEach { c ->
                row(c.id) { first -> ContactRow(c, first, ui.label, topicLabels, ui.dateFormat, { onRelation(c) }) { ui.runtime.onLearn { deleteContact(c.id) } } }
            }
        }
        if (unsure.isNotEmpty()) {
            label("unsure") { stringResource(R.string.proactive_learn_unsure) }
            card("unsure") {
                unsure.forEach { c ->
                    row(c.id) { first -> ContactRow(c, first, ui.label, topicLabels, ui.dateFormat, { onRelation(c) }) { ui.runtime.onLearn { deleteContact(c.id) } } }
                }
            }
        }
        item(key = "footer") { FooterNote(stringResource(R.string.proactive_learn_contacts_sub)) }
    }
}

@Composable
private fun ClearPage(
    ui: KnowUi, clearable: List<String>, topicsBlocked: Int, contactsBlocked: Int, onClearAll: () -> Unit, onClearApp: (String) -> Unit,
) {
    PageScaffold(stringResource(R.string.proactive_know_clear), ui.up) {
        GroupCard {
            // 「清空全部」一直在:页面上什么都没列出来时也可能有东西——哪一份存盘读不出来的时候,它只能靠清空删掉(见 StoreCodec)
            ListRow(null, stringResource(R.string.proactive_learn_clear_all), first = true, onClick = onClearAll)
            // 清掉某个 App 的:有话题证据、有联系人,或各级归纳里有它的句子的 App 都列出来
            clearable.forEach { pkg ->
                ListRow(null, stringResource(R.string.proactive_learn_clear_app, ui.label(pkg)), onClick = { onClearApp(pkg) })
            }
        }
        if (topicsBlocked > 0 || contactsBlocked > 0) GroupCard {
            if (topicsBlocked > 0) {
                ListRow(
                    null, stringResource(R.string.proactive_learn_topics_allow_again), stringResource(R.string.proactive_learn_topics_blocked, topicsBlocked),
                    first = true, onClick = { ui.runtime.onLearn { allowDeletedTopics() } },
                )
            }
            if (contactsBlocked > 0) {
                ListRow(
                    null, stringResource(R.string.proactive_learn_contacts_allow_again), stringResource(R.string.proactive_learn_contacts_blocked, contactsBlocked),
                    first = topicsBlocked == 0, onClick = { ui.runtime.onLearn { allowDeletedContacts() } },
                )
            }
        }
    }
}

/** 一个话题:名字(纯文字)、种类、哪些 App 在几天里印证过、上次出现、点开/划掉几次;右边删掉。 */
@Composable
private fun TopicRow(
    t: ObservedTopic, first: Boolean, label: (String) -> String, dateFormat: java.text.DateFormat, onMerge: (() -> Unit)?, onDelete: () -> Unit,
) {
    val apps = t.evidence.keys.joinToString(", ", transform = label)
    // 话题名按纯文字显示;敏感的在后面加一个纯文字的小标记
    ListRow(
        null, if (t.sensitive) t.label + " · " + stringResource(R.string.proactive_learn_sensitive_tag) else t.label,
        stringResource(R.string.proactive_learn_topic_detail, stringResource(kindLabel(t.kind)), apps, t.days, dateFormat.format(java.util.Date(t.lastSeenMs))),
        first = first,
        detail = stringResource(R.string.proactive_learn_topic_outcomes, t.apps, t.opened, t.dismissed),
    ) {
        Row {
            if (onMerge != null) TextButton(onMerge) { Text(stringResource(R.string.proactive_learn_topic_merge)) }
            TextButton(onDelete) { Text(stringResource(R.string.proactive_learn_delete)) }
        }
    }
}

/**
 * 一个联系人:名字(纯文字)、类型、哪个 App、关系(猜的标「猜测」,用户设的标「你设定的」)、出现过几天、上次出现;
 * 下一行是连着的话题。右边改关系、删掉。
 */
@Composable
private fun ContactRow(
    c: ObservedContact,
    first: Boolean,
    label: (String) -> String,
    topicLabels: Map<String, String>,
    dateFormat: java.text.DateFormat,
    onRelation: () -> Unit,
    onDelete: () -> Unit,
) {
    val rel = stringResource(relationLabel(c.relation))
    val relation = when {
        c.userSet -> stringResource(R.string.proactive_learn_rel_user, rel)
        c.relation == ContactRelation.Unknown -> rel
        else -> stringResource(R.string.proactive_learn_rel_guess, rel)
    }
    val type = stringResource(if (c.type == ContactType.Group) R.string.proactive_learn_type_group else R.string.proactive_learn_type_person)
    val linked = c.topics.mapNotNull(topicLabels::get)
    ListRow(
        null, c.name,
        stringResource(R.string.proactive_learn_contact_detail, type, label(c.packageName), relation, c.days.size, dateFormat.format(java.util.Date(c.lastSeenMs))),
        first = first,
        detail = if (linked.isEmpty()) null else stringResource(R.string.proactive_learn_contact_topics, linked.joinToString(", ")),
    ) {
        Row {
            TextButton(onRelation) { Text(stringResource(R.string.proactive_learn_relation)) }
            TextButton(onDelete) { Text(stringResource(R.string.proactive_learn_delete)) }
        }
    }
}

private fun relationLabel(r: ContactRelation): Int = when (r) {
    ContactRelation.Family -> R.string.proactive_learn_rel_family
    ContactRelation.Partner -> R.string.proactive_learn_rel_partner
    ContactRelation.Friend -> R.string.proactive_learn_rel_friend
    ContactRelation.Colleague -> R.string.proactive_learn_rel_colleague
    ContactRelation.Classmate -> R.string.proactive_learn_rel_classmate
    ContactRelation.Other -> R.string.proactive_learn_rel_other
    ContactRelation.Unknown -> R.string.proactive_learn_rel_unknown
}

private fun kindLabel(k: TopicKind): Int = when (k) {
    TopicKind.Interest -> R.string.proactive_learn_kind_interest
    TopicKind.Follow -> R.string.proactive_learn_kind_follow
    TopicKind.Shopping -> R.string.proactive_learn_kind_shopping
    TopicKind.Service -> R.string.proactive_learn_kind_service
    TopicKind.Routine -> R.string.proactive_learn_kind_routine
}

/**
 * 「清空来自它的」列哪些 App:话题上有它的证据、有它的联系人,分段归纳、当日(周、月)归纳里有依据过它的句子,
 * 某一份归纳的概述是照着它的句子写的(概述还在时才算;分段归纳到期之后,它可能只剩在概述里),
 * 或者还留着它的通知原文([raw])——刚来了几条通知、还没归纳出任何东西的 App 只有原文,不列它就只能全部清空。
 */
internal fun clearableApps(
    topics: List<com.wangzan101.mika.core.proactive.ObservedTopic>,
    contacts: List<com.wangzan101.mika.core.proactive.ObservedContact>,
    notes: List<com.wangzan101.mika.core.proactive.DigestNote>,
    days: List<com.wangzan101.mika.core.proactive.DayDigest>,
    spans: List<com.wangzan101.mika.core.proactive.SpanDigest>,
    raw: Set<String> = emptySet(),
): Set<String> = LinkedHashSet<String>().apply {
    topics.forEach { addAll(it.evidence.keys) }
    contacts.forEach { add(it.packageName) }
    notes.forEach { add(it.packageName) }
    // 概述清掉之后来源记录可能还留着:只在概述还有字时才算,不然列出来的 App 其实已经没东西可清
    days.forEach { d ->
        d.lines.forEach { addAll(it.apps) }
        if (d.brief.isNotEmpty()) addAll(d.briefApps)
    }
    spans.forEach { s ->
        s.lines.forEach { addAll(it.apps) }
        if (s.brief.isNotEmpty()) addAll(s.briefApps)
    }
    addAll(raw)
}

/**
 * 某一天的页面上,当日归纳那张卡片顶上的一句说明;不用说为 null(有句子)。页面上写的要是真的:
 * 说「会自动归纳」「点下面这一行」的那几句,只在现在真能做([canMake]:总开关、「当日消息归纳」都开着,模型确认过)时才用。
 */
internal fun dayNote(digest: DayDigest?, removed: Boolean, isToday: Boolean, canMake: Boolean): Int? = when {
    digest != null -> if (digest.lines.isEmpty()) R.string.proactive_learn_day_nothing else null
    // 被删掉的:不会再自动做了。今天的在能做的时候可以点下面那一行做回来
    removed -> if (isToday && canMake) R.string.proactive_learn_span_removed else R.string.proactive_know_removed
    !canMake -> R.string.proactive_learn_empty
    isToday -> R.string.proactive_learn_day_none
    else -> R.string.proactive_know_day_pending
}

/** 某一周(某个月)的页面上,那张卡片顶上的一句说明;[canMake] 是「这是本周(本月),而且现在能做」。 */
internal fun spanNote(digest: SpanDigest?, removed: Boolean, kind: SpanKind, canMake: Boolean): Int? = when {
    digest != null -> if (digest.lines.isEmpty()) R.string.proactive_learn_span_nothing else null
    removed -> if (canMake) R.string.proactive_learn_span_removed else R.string.proactive_know_removed
    !canMake -> R.string.proactive_learn_empty
    kind == SpanKind.Week -> R.string.proactive_learn_week_none
    else -> R.string.proactive_learn_month_none
}

/**
 * 手动「归纳到现在」这会儿为什么做不了(要显示的那句话);做得了为 null。
 * 预算用完了——按账本算出来的([budgetOut]),或者请求时被拒、执行器记着今天不再发([LearnStatus.BudgetUsedUp]);
 * 「应用访问」设置读不出来、整个停着。这些时候点了按钮也不会发请求,页面要说出来而不是没有反应。
 */
internal fun makeBlocked(budgetOut: Boolean, status: LearnStatus): Int? = when {
    status == LearnStatus.Suspended -> R.string.proactive_status_access
    budgetOut || status == LearnStatus.BudgetUsedUp -> R.string.proactive_learn_state_budget
    else -> null
}
