package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.export.ExportConversationDialog
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.DirectionalIcon
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.chat.ConversationHit
import com.wangzan101.mika.chat.ConversationSearch
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.keepsKeyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.lazyListScrollbar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import com.wangzan101.mika.i18n.uiLocale

/** 侧栏里的一条对话。[id] 为 null 是还没存进对话列表的当前对话。 */
data class DrawerConversation(
    val id: String?,
    val title: String,
    val subtitle: String,
    val running: Boolean,
    val current: Boolean,
    val enabled: Boolean,
    /** 最后改动的时间(写在标题右边);还没存进对话列表的为空。 */
    val time: String = "",
    /** 置顶了:标题前一个小图钉,长按菜单里是「取消置顶」。 */
    val pinned: Boolean = false,
    /** 在浏览器页上对着一页开始的对话:那个网站(主机名),标题后面一个「网页」小标签;不是为 null。 */
    val site: String? = null,
    /** 从另一个对话分支出来的:标题前一个小分支图标。 */
    val branch: Boolean = false,
    /** 归档了:标题前一个小盒子图标,长按菜单里是「取消归档」。 */
    val archived: Boolean = false,
    /** 已经存进对话列表(有对话文件);还没说过话的草稿为 false,不能归档。 */
    val saved: Boolean = true,
    /** 归档的时间;归档视图里按它分组、算「归档于几天前」。 */
    val archivedAt: Long? = null,
    /** 搜索结果命中了正文:打开对话后跳到这条消息;只有标题命中时为 null。 */
    val target: ChatTarget? = null,
    /** 所属项目分组 id;未归属项目时为 null。 */
    val projectId: String? = null,
    /** 所属项目分组名称(用于在已归档、搜索和进行中列表里直接标明归属)。 */
    val projectName: String? = null,
)

/** 一次批量归档的结果:归档了几个、几个因为在跑任务或是当前对话没动。 */
data class ArchiveBatch(val archived: Int, val skipped: Int)

/** 刚归档的那一条:底部给几秒钟的「撤销」。 */
data class ArchiveUndo(val id: String, val title: String)

/** 归档视图里的时间段:本周归档的、本月早些时候的、更早的。 */
enum class ArchivePeriod { ThisWeek, ThisMonth, Earlier }

/** 归档视图里的一组。[rows] 保持传进来的顺序(按归档时间倒序)。 */
data class ArchiveGroup(val period: ArchivePeriod, val rows: List<DrawerConversation>)

/** 侧栏里的一个项目分组及其成员对话。 */
data class DrawerProjectGroup(val project: Project, val rows: List<DrawerConversation>)

/** 侧栏里的对话分段:进行中、项目分组、置顶、最近。 */
data class DrawerSections(
    val running: List<DrawerConversation>,
    val recent: List<DrawerConversation>,
    val pinned: List<DrawerConversation> = emptyList(),
    val projects: List<DrawerProjectGroup> = emptyList(),
)

/** 多选能勾的对话:普通侧栏是此刻列出来的;归档视图里是收起来的那些。没有编号的行勾不了。 */
fun drawerSelectableIds(
    sections: DrawerSections,
    archived: List<DrawerConversation>,
    archivedView: Boolean,
): Set<String> =
    if (archivedView) archived.mapNotNull { it.id }.toSet()
    else (sections.running + sections.pinned + sections.recent + sections.projects.flatMap { it.rows }).mapNotNull { it.id }.toSet()

/** 搜索候选:删掉的当场不搜;再按归档状态筛,全部时归档的也搜(结果行带归档标记)。 */
internal fun drawerSearchCandidates(
    all: List<ConversationSummary>,
    deleted: Set<String>,
    filter: SearchArchiveFilter,
): List<ConversationSummary> =
    all.asSequence()
        .filter { it.id !in deleted }
        .filter {
            when (filter) {
                SearchArchiveFilter.All -> true
                SearchArchiveFilter.Active -> it.archivedAt == null
                SearchArchiveFilter.Archived -> it.archivedAt != null
            }
        }
        .map { it.copy(title = ChatCopy.plainTitle(it.title)) }
        .toList()

/** 搜索拿到的原始命中;显示前再按此刻的置顶、运行、时间换成侧栏里的行。 */
private data class SearchOutcome(
    val query: String,
    val hits: List<ConversationHit>,
    val searching: Boolean,
    val truncated: Boolean = false,
)

/** 当前对话在侧栏里的样子:有没有任务在跑、标题、副标题(在做什么)。 */
data class CurrentConversation(val id: String?, val running: Boolean, val title: String, val subtitle: String)

/**
 * 侧栏的三段:「进行中」是当前对话(有任务时)和别的有任务在跑的对话;「置顶」是其余置顶了的对话,最近置顶的在前;
 * 「最近」是剩下的对话,最近改过的在前。每个对话只出现在一段里:置顶的对话有任务在跑时列在进行中(带图钉),跑完回到置顶。
 * 归档的对话不进置顶和最近,但仍然在跑任务时列在进行中;当前正在看的那个即使归档了也留着,方便知道自己在哪。
 * [canSwitch] 为假(任务进行中、没开并行)时只有当前对话可点。
 */
fun drawerSections(
    all: List<ConversationSummary>,
    current: CurrentConversation,
    others: List<String>,
    otherTitle: (String) -> String?,
    canSwitch: Boolean,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    projects: List<Project> = emptyList(),
): DrawerSections {
    val byId = all.associateBy { it.id }
    val projectNames = projects.associate { it.id to it.name }
    fun timeOf(id: String?) = byId[id]?.takeIf { it.updatedAt != 0L }?.let { conversationTime(it.updatedAt, now, zone) }.orEmpty()
    fun pinned(id: String?) = byId[id]?.pinnedAt != null
    fun archived(id: String?) = byId[id]?.archivedAt != null
    fun saved(id: String?) = byId[id]?.updatedAt?.let { it != 0L } ?: false
    fun projectOf(id: String?) = byId[id]?.projectId?.takeIf { it in projectNames }
    val running = buildList {
        if (current.running) {
            val pid = projectOf(current.id)
            add(DrawerConversation(current.id, current.title.ifBlank { tr(R.string.chat_new) }, current.subtitle, true, true, true, time = timeOf(current.id), pinned = pinned(current.id), site = byId[current.id]?.page?.host, branch = byId[current.id]?.branchOf != null, archived = archived(current.id), saved = saved(current.id), projectId = pid, projectName = pid?.let(projectNames::get)))
        }
        others.filter { it != current.id }.distinct().forEach { id ->
            val title = (byId[id]?.title ?: otherTitle(id))?.let(ChatCopy::plainTitle)?.ifBlank { null } ?: tr(R.string.drawer_running_task)
            val pid = projectOf(id)
            add(DrawerConversation(id, title, tr(R.string.drawer_running), running = true, current = false, enabled = canSwitch, time = timeOf(id), pinned = pinned(id), site = byId[id]?.page?.host, branch = byId[id]?.branchOf != null, archived = archived(id), saved = saved(id), projectId = pid, projectName = pid?.let(projectNames::get)))
        }
    }
    val runningIds = running.mapNotNull { it.id }.toSet()
    fun entry(s: ConversationSummary): DrawerConversation =
        drawerEntry(s, current.id, canSwitch, running = s.id in runningIds, time = timeOf(s.id), projectName = s.projectId?.let(projectNames::get))
    // 归档的对话不在主列表里;当前正在看的那个例外,不然打开归档对话后不知道自己在哪
    val rest = all.filter { it.id !in runningIds && (it.archivedAt == null || it.id == current.id) }
    val grouped = rest.filter { it.projectId != null && it.projectId in projectNames }.groupBy { it.projectId!! }
    val projectGroups = projects.map { project ->
        val rows = grouped[project.id].orEmpty()
            .sortedWith(compareByDescending<ConversationSummary> { it.pinnedAt != null }.thenByDescending { it.pinnedAt ?: 0L }.thenByDescending { it.updatedAt })
            .map(::entry)
        DrawerProjectGroup(project, rows)
    }
    val standalone = rest.filter { it.projectId == null || it.projectId !in projectNames }
    val pinnedRows = standalone.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }.map(::entry)
    val recent = standalone.asSequence().filter { it.pinnedAt == null }.take(MAX_RECENT).map(::entry).toList()
    return DrawerSections(running, recent, pinnedRows, projectGroups)
}

/** 一条对话在侧栏里的样子:侧栏列表和搜索结果共用，免得两处规则走岔。 */
internal fun drawerEntry(
    s: ConversationSummary,
    currentId: String?,
    canSwitch: Boolean,
    running: Boolean,
    time: String,
    subtitle: String = "",
    target: ChatTarget? = null,
    projectName: String? = null,
): DrawerConversation {
    val isCurrent = s.id == currentId
    return DrawerConversation(
        s.id, ChatCopy.plainTitle(s.title), subtitle, running, isCurrent, canSwitch || isCurrent, time,
        pinned = s.pinnedAt != null, site = s.page?.host, branch = s.branchOf != null,
        archived = s.archivedAt != null, saved = s.updatedAt != 0L, archivedAt = s.archivedAt, target = target,
        projectId = s.projectId, projectName = projectName,
    )
}

/** 对话的时间:今天写时刻,昨天写「昨天」,一周内写星期几,今年写几月几日,更早带年份。 */
fun conversationTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = t.toLocalDate()
    val days = today.toEpochDay() - day.toEpochDay()
    return when {
        days <= 0L -> "%d:%02d".format(t.hour, t.minute)
        days == 1L -> tr(R.string.drawer_yesterday)
        days < 7L -> t.dayOfWeek.getDisplayName(DateTextStyle.SHORT, uiLocale())
        day.year == today.year -> tr(R.string.date_month_day, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
        else -> tr(R.string.date_year_month_day, day.year, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
    }
}

/** 归档的时间落在哪一段:本周(周一起算)、本月早些时候、更早。 */
fun archivePeriod(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): ArchivePeriod {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return when {
        !day.isBefore(weekStart) -> ArchivePeriod.ThisWeek
        day.year == today.year && day.month == today.month -> ArchivePeriod.ThisMonth
        else -> ArchivePeriod.Earlier
    }
}

/** 归档视图按时间段分组;没记归档时间的归到「更早」,不会漏掉。空组不出现。 */
fun archiveGroups(
    rows: List<DrawerConversation>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ArchiveGroup> =
    ArchivePeriod.values().mapNotNull { period ->
        val group = rows.filter {
            (it.archivedAt?.let { at -> archivePeriod(at, now, zone) } ?: ArchivePeriod.Earlier) == period
        }
        if (group.isEmpty()) null else ArchiveGroup(period, group)
    }

/** 分组标题:本周 / 本月 / 更早。 */
fun archivePeriodLabel(period: ArchivePeriod): String = tr(
    when (period) {
        ArchivePeriod.ThisWeek -> R.string.drawer_archive_this_week
        ArchivePeriod.ThisMonth -> R.string.drawer_archive_this_month
        ArchivePeriod.Earlier -> R.string.drawer_archive_earlier
    }
)

/** 「归档于 3 天前」;当天归档的说「今天归档」。 */
fun archivedAgo(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = (today.toEpochDay() - day.toEpochDay()).coerceAtLeast(0L).toInt()
    return if (days == 0) tr(R.string.drawer_archived_today)
    else trPlural(R.plurals.drawer_archived_days_ago, days, days)
}

/** 侧栏顶上 Mika 名字下面那行状态,和它是不是好的(绿点)还是要处理(橙点)。 */
fun readiness(check: Prerequisites.Check): Pair<String, Boolean> = when {
    !check.canStartTask -> tr(R.string.chat_cannot_start) to false
    !check.phoneReady -> tr(R.string.drawer_phone_not_ready) to false
    else -> tr(R.string.all_ready) to true
}

private const val MAX_RECENT = 50

/**
 * 侧栏:从起始边随聊天卡片一起滑出。顶部是标志和状态;导航入口;中间是进行中(段标题右边进「后台屏」页)和最近的对话;最底下设置和关于并排。
 * 打开和关上由外面的抽屉管,这里只摆内容,点了什么交给回调。
 */
@Composable
fun ChatDrawer(
    check: Prerequisites.Check,
    sections: DrawerSections,
    canSwitch: Boolean,
    /** 搜索框里的字;非空时侧栏进入搜索结果,入口区让位给结果。 */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    /** 当前查询的结果;还没搜完时 [DrawerSearch.searching] 为真。 */
    search: DrawerSearch? = null,
    /** 有对话可搜时才显示搜索框。 */
    canSearch: Boolean = false,
    /** 搜索结果的归档筛选:全部 / 只看未归档 / 只看已归档。 */
    searchFilter: SearchArchiveFilter = SearchArchiveFilter.All,
    onSearchFilterChange: (SearchArchiveFilter) -> Unit = {},
    onNew: () -> Unit,
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onOpen: (DrawerConversation) -> Unit,
    onAbout: () -> Unit = {},
    /** 有新版本:「关于 Mika」带小红点。 */
    aboutDot: Boolean = false,
    /** 长按一条存过的对话:在它原地弹出操作菜单(置顶、改名、删除);带上那一行在窗口里的位置和大小。 */
    onActions: (DrawerConversation, Rect) -> Unit = { _, _ -> },
    /** 多选模式:每行左边出现勾选框,点一行是勾选;底部换成「已选 N 条 / 全选 / 删除」。 */
    selecting: Boolean = false,
    selected: Set<String> = emptySet(),
    onExitSelect: () -> Unit = {},
    onToggleSelect: (DrawerConversation) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    /** 归档视图里多选后「取消归档」;普通侧栏里用不到。 */
    onUnarchiveSelected: () -> Unit = {},
    /** 普通侧栏里多选后「归档」;归档视图里用不到。 */
    onArchiveSelected: () -> Unit = {},
    /** 批量删除的进度和结果;在底部显示,删完过几秒由外面清掉。 */
    deleteBatch: DeleteBatch? = null,
    /** 批量归档的结果;在底部显示,过几秒由外面清掉。 */
    archiveBatch: ArchiveBatch? = null,
    /** 刚归档的那一条;底部显示「撤销」,过几秒由外面清掉。 */
    archiveUndo: ArchiveUndo? = null,
    onUndoArchive: () -> Unit = {},
    /** 归档视图里列出的对话;按归档时间倒序。 */
    archived: List<DrawerConversation> = emptyList(),
    /** 正在看归档视图:列表换成归档对话,搜索框和导航入口收起。 */
    archivedView: Boolean = false,
    onOpenArchive: () -> Unit = {},
    onExitArchive: () -> Unit = {},
    showProjectsSection: Boolean = false,
    collapsedProjects: Set<String> = emptySet(),
    onToggleProject: (String) -> Unit = {},
    onNewProject: () -> Unit = {},
    onNewInProject: (String) -> Unit = {},
    onProjectActions: (Project, Rect) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    containerColor: Color = MikaTheme.colors.bg,
) {
    val c = MikaTheme.colors
    // 能勾的就是此刻列在侧栏里、已经存过的对话(还没存过的当前草稿没有编号,删不了);归档视图里是归档的那些
    val selectableIds = remember(sections, archived, archivedView) {
        drawerSelectableIds(sections, archived, archivedView)
    }
    val selectedCount = selected.count { it in selectableIds }
    val allSelected = selectableIds.isNotEmpty() && selectedCount == selectableIds.size
    // 搜索时把几个入口收起来，把高度让给结果;清空后原样回来。搜索框在入口上面，位置不跳
    val searchActive = canSearch && !selecting && !archivedView && query.isNotBlank()
    Column(
        modifier.fillMaxHeight().widthIn(max = DrawerWidth).fillMaxWidth()
            .background(containerColor)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
            .testTag("drawer"),
    ) {
        val (state, ok) = readiness(check)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MikaLogo(36.dp, corner = 10.dp)
            Column(Modifier.weight(1f)) {
                Text("Mika", style = MikaType.Title, color = c.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (ok) c.ok else c.warnSolid, CircleShape))
                    Text(state, style = MikaType.Hint, fontWeight = FontWeight.Medium, color = c.ink3)
                }
            }
            // 多选从长按菜单进;进去以后这里换成「取消」,返回键同样能退出
            if (selecting) HeaderAction(stringResource(R.string.cancel), "drawer-select-cancel", c.ink2, onExitSelect)
        }
        // 搜索框和新对话并成一行:搜索框占满,右边一个中性圆加号(没在搜时才有);没有可搜的对话时也留那个加号
        val showNew = !searchActive && !selecting && !archivedView
        if (canSearch && !selecting && !archivedView) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DrawerSearchField(query, onQueryChange, Modifier.weight(1f))
                if (showNew) NewConversationButton(canSwitch, onNew)
            }
        } else if (showNew) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                NewConversationButton(canSwitch, onNew)
            }
        }
        // 搜索时搜索框下面按归档状态筛;归档视图里没有搜索,自然也没有这一行
        if (searchActive) SearchArchiveFilterRow(searchFilter, onSearchFilterChange)
        // 侧栏关着时列表只剩末尾那一项;打开时对话插在它前面,列表会按原来那一项停在最底下。最上面一项变了就回到顶上
        val list = rememberLazyListState()
        val first = when {
            archivedView -> "h-archive"
            searchActive -> "h-search"
            sections.running.isNotEmpty() -> "h-running"
            showProjectsSection -> "h-projects"
            sections.pinned.isNotEmpty() -> "h-pinned"
            sections.recent.isNotEmpty() -> "h-recent"
            else -> "end"
        }
        LaunchedEffect(first) { list.scrollToItem(0) }
        val terms = remember(query) { ConversationSearch.termsOf(query) }
        // 归档视图按本周 / 本月 / 更早分组;分组也跟着列表走
        val archiveGrouping = remember(archived) { archiveGroups(archived, System.currentTimeMillis()) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).lazyListScrollbar(list), state = list) {
            if (searchActive) {
                item("h-search") { SectionLabel(stringResource(R.string.drawer_search_results)) }
                if (!canSwitch) {
                    item("search-busy") {
                        Text(
                            stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                            style = MikaType.Hint, color = c.ink3,
                        )
                    }
                }
                // 扫正文期间一直提示;标题命中已经列出来时也提示,表示结果还会补全
                if (search?.searching != false) item("searching") { SearchHint(stringResource(R.string.drawer_searching)) }
                items(search?.hits.orEmpty(), key = { "s-${it.id}" }) {
                    // 「只看已归档」时每一行都是归档的,不用再标;其余筛选用归档小图标把收起来的对话标出来
                    ConversationEntry(it, onOpen, onActions, terms, showArchivedMark = searchFilter != SearchArchiveFilter.Archived, showProjectMark = true)
                }
                if (search != null && !search.searching && search.hits.isEmpty()) {
                    item("search-empty") { SearchEmpty(query) }
                }
                if (search?.truncated == true) {
                    item("search-more") { SearchHint(stringResource(R.string.drawer_search_truncated, ConversationSearch.MAX_HITS)) }
                }
            } else if (archivedView) {
                item("h-archive") { ArchiveHeader(archived.size, onExitArchive) }
                if (archived.isEmpty()) item("archive-empty") { ArchiveEmpty() }
                archiveGrouping.forEach { group ->
                    item("h-archive-${group.period.name}") { SectionLabel(archivePeriodLabel(group.period)) }
                    items(group.rows, key = { "a-${it.id}" }) {
                        ConversationEntry(
                            it, onOpen, onActions, showArchivedMark = false, showProjectMark = true,
                            selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect,
                        )
                    }
                }
            } else {
                if (sections.running.isNotEmpty()) {
                    // 「进行中」这一段的右边进「后台屏」页:看每个在跑的任务正在用哪个 App、实时画面
                    item("h-running") { RunningLabel(onScreens) }
                    items(sections.running, key = { "r-${it.id}" }) {
                        ConversationEntry(it, onOpen, onActions, selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect, showProjectMark = true)
                    }
                }
                if (showProjectsSection) {
                    item("h-projects") { ProjectsHeader(enabled = canSwitch, onNewProject = onNewProject) }
                    sections.projects.forEach { group ->
                        val collapsed = group.project.id in collapsedProjects
                        item("proj-h-${group.project.id}") {
                            ProjectGroupHeader(
                                project = group.project,
                                count = group.rows.size,
                                collapsed = collapsed,
                                canCreate = canSwitch,
                                onToggle = { onToggleProject(group.project.id) },
                                onNewChat = { onNewInProject(group.project.id) },
                                onActions = { bounds -> onProjectActions(group.project, bounds) },
                            )
                        }
                        if (!collapsed) {
                            if (group.rows.isEmpty()) {
                                item("proj-empty-${group.project.id}") {
                                    Text(
                                        stringResource(R.string.drawer_project_empty),
                                        Modifier.padding(start = 36.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
                                        style = MikaType.Hint, color = c.ink3,
                                    )
                                }
                            } else {
                                items(group.rows, key = { "proj-${group.project.id}-${it.id}" }) {
                                    ConversationEntry(
                                        it, onOpen, onActions, selecting = selecting, selected = it.id in selected,
                                        onToggleSelect = onToggleSelect,
                                    )
                                }
                            }
                        }
                    }
                }
                // 换不了对话时的说明放在第一段存着的对话(置顶或最近)上面
                val busyNote: LazyListScope.() -> Unit = {
                    if (!canSwitch) {
                        item("busy") {
                            Text(
                                stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                                style = MikaType.Hint, color = c.ink3,
                            )
                        }
                    }
                }
                if (sections.pinned.isNotEmpty()) {
                    item("h-pinned") { SectionLabel(stringResource(R.string.drawer_pinned)) }
                    busyNote()
                    items(sections.pinned, key = { "p-${it.id}" }) {
                        ConversationEntry(
                            it, onOpen, onActions, selecting = selecting, selected = it.id in selected,
                            onToggleSelect = onToggleSelect,
                        )
                    }
                }
                if (sections.recent.isNotEmpty()) {
                    item("h-recent") { SectionLabel(stringResource(R.string.drawer_recent)) }
                    if (sections.pinned.isEmpty()) busyNote()
                    items(sections.recent, key = { "c-${it.id}" }) {
                        ConversationEntry(
                            it, onOpen, onActions, selecting = selecting, selected = it.id in selected,
                            onToggleSelect = onToggleSelect,
                        )
                    }
                }
            }
            if (!searchActive && !selecting && !archivedView && archived.isNotEmpty()) {
                item("archive-entry") { ArchiveEntry(archived.size, onOpenArchive) }
            }
            item("end") { Box(Modifier.height(12.dp)) }
        }
        // 批量删除的进度 / 结果在底部停留几秒
        deleteBatch?.let { DeleteStatusBar(it) }
        archiveBatch?.let { ArchiveStatusBar(it) }
        if (!selecting) archiveUndo?.let { ArchiveUndoBar(it, onUndoArchive) }
        if (selecting) {
            SelectBar(selectedCount, allSelected, onSelectAll, archivedView, onArchiveSelected, onUnarchiveSelected, onDeleteSelected)
        } else {
            // 设置固定在侧栏最底下靠左;有新版时右边才出现「更新」带红点,点它进「关于 Mika」
            Row(
                Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("drawer-footer"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NavEntry(MikaIcons.Gear, stringResource(R.string.settings_title), Modifier, onClick = onSettings)
                if (aboutDot) NavEntry(MikaIcons.Refresh, stringResource(R.string.drawer_update), Modifier, dot = true, onClick = onAbout)
            }
        }
    }
}

/** 侧栏顶上的小按钮:平时的「多选」和多选中的「取消」。 */
@Composable
private fun HeaderAction(text: String, tag: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, style = MikaType.Label, color = color)
    }
}

/** 归档视图顶上的一行:返回普通侧栏,右边写着收起来了几条。 */
@Composable
private fun ArchiveHeader(count: Int, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(NavShape).clickable(role = Role.Button, onClick = onBack)
            .padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
            .testTag("drawer-archive-back"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(MikaIcons.Back, null, Modifier.size(16.dp), tint = c.ink2)
        Text(stringResource(R.string.drawer_archived), Modifier.weight(1f), style = SectionText, color = c.ink3)
        if (count > 0) {
            Text(stringResource(R.string.drawer_archived_count, count), style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        }
    }
}

/** 归档视图里一条都没有。 */
@Composable
private fun ArchiveEmpty() {
    Text(
        stringResource(R.string.drawer_archive_empty),
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
        style = MikaType.Body, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}

/** 侧栏底部的「已归档」入口:进去看收起来的对话。 */
@Composable
private fun ArchiveEntry(count: Int, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .clip(NavShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("drawer-archive"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(16.dp), tint = c.ink3)
        Text(stringResource(R.string.drawer_archived), Modifier.weight(1f), style = MikaType.Body, color = c.ink2, maxLines = 1)
        Text("$count", Modifier.testTag("drawer-archive-count"), style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
    }
}

/** 多选时的底部操作条:已选几条、全选 / 取消全选、删除;普通侧栏多一个「归档」,归档视图里换成「取消归档」。 */
@Composable
private fun SelectBar(
    selectedCount: Int, allSelected: Boolean, onSelectAll: () -> Unit,
    archived: Boolean, onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-select-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            tr(R.string.drawer_selected_count, selectedCount), Modifier.weight(1f),
            style = MikaType.Label, color = c.ink2, maxLines = 1, softWrap = false,
        )
        TextButton(onClick = onSelectAll, modifier = Modifier.testTag("drawer-select-all")) {
            Text(stringResource(if (allSelected) R.string.drawer_select_none else R.string.drawer_select_all), style = MikaType.Label, color = c.accent)
        }
        if (archived) {
            TextButton(onClick = onUnarchive, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-unarchive-selected")) {
                Text(stringResource(R.string.drawer_unarchive), style = MikaType.Label, color = if (selectedCount > 0) c.accent else c.ink3)
            }
        } else {
            TextButton(onClick = onArchive, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-archive-selected")) {
                Text(stringResource(R.string.drawer_archive), style = MikaType.Label, color = if (selectedCount > 0) c.accent else c.ink3)
            }
        }
        TextButton(onClick = onDelete, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-delete-selected")) {
            Text(stringResource(R.string.confirm_delete), style = MikaType.Label, color = if (selectedCount > 0) c.bad else c.ink3)
        }
    }
}

/** 批量归档的结果:归档了几个;有因为在跑任务或是当前对话没动的,照实说几个没归档。 */
@Composable
private fun ArchiveStatusBar(batch: ArchiveBatch) {
    val c = MikaTheme.colors
    val text = when {
        batch.archived <= 0 -> trPlural(R.plurals.drawer_archive_many_skipped, batch.skipped, batch.skipped)
        batch.skipped <= 0 -> trPlural(R.plurals.drawer_archive_many_done, batch.archived, batch.archived)
        else -> trPlural(R.plurals.drawer_archive_many_done, batch.archived, batch.archived) + " · " +
            trPlural(R.plurals.drawer_archive_many_skipped, batch.skipped, batch.skipped)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-archive-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(14.dp), tint = if (batch.archived > 0) c.ok else c.ink3)
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

/** 刚归档一条:底部写清收起了哪个对话,几秒内可以撤销。 */
@Composable
private fun ArchiveUndoBar(undo: ArchiveUndo, onUndo: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-archive-undo"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(14.dp), tint = c.ink3)
        Text(
            tr(R.string.drawer_archive_done, undo.title), Modifier.weight(1f),
            style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onUndo, modifier = Modifier.testTag("drawer-archive-undo-action")) {
            Text(stringResource(R.string.undo), style = MikaType.Label, color = c.accent)
        }
    }
}

/** 批量删除的进度和结果:删的时候是「正在删除 N 个对话…」,删完写成功几条、失败几条。 */
@Composable
private fun DeleteStatusBar(batch: DeleteBatch) {
    val c = MikaTheme.colors
    val deleted = batch.done - batch.failed
    val text = when {
        !batch.finished -> trPlural(R.plurals.drawer_delete_progress, batch.total, batch.total)
        batch.failed == 0 -> trPlural(R.plurals.drawer_delete_done, deleted, deleted)
        else -> trPlural(R.plurals.drawer_delete_partial, deleted, deleted, batch.failed)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-delete-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (batch.finished) {
            Icon(
                if (batch.failed == 0) MikaIcons.Check else MikaIcons.Exclaim, null, Modifier.size(14.dp),
                tint = if (batch.failed == 0) c.ok else c.bad,
            )
        }
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

@Composable
private fun NavEntry(
    icon: ImageVector, text: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        modifier.height(46.dp).clip(NavShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MikaType.Body, color = c.ink, maxLines = 1, softWrap = false)
            if (dot) UpdateDot()
        }
    }
}

/** 侧栏搜索框右边的新对话按钮:中性圆底 + 深色加号,和顶栏 ✚ 一个样子。 */
@Composable
private fun NewConversationButton(enabled: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.chat_new), onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.New, stringResource(R.string.chat_new), Modifier.size(18.dp), tint = c.ink)
    }
}

/** 「进行中」的段标题,右边一个「后台屏 ›」进后台屏页(只有有任务在跑时才有这一段)。 */
@Composable
private fun RunningLabel(onScreens: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_running)) }
        Row(
            Modifier.clip(NavShape).clickable(role = Role.Button, onClick = onScreens)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-screens"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_screens), style = MikaType.Hint, color = c.ink2)
            DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        style = SectionText, color = MikaTheme.colors.ink3,
    )
}

/**
 * 一条对话:点开它;长按(带一下振动)在原地弹出它的操作菜单(置顶、改名、删除)。换不了对话时(任务进行中、没开并行)点不开,长按照样能用。
 * 还没存进对话列表的(没有编号)没东西可改,不响应长按。置顶了的标题前有个小图钉。
 * 多选模式([selecting])下点一行是勾选/取消,左边出现勾选框;没存过的草稿不参与。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationEntry(
    item: DrawerConversation,
    onOpen: (DrawerConversation) -> Unit,
    onActions: (DrawerConversation, Rect) -> Unit,
    /** 搜索结果里高亮的关键词;普通列表为空。 */
    terms: List<String> = emptyList(),
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (DrawerConversation) -> Unit = {},
    /** 标题前显不显示归档小图标;归档视图里每一行都是归档的,不用再标。 */
    showArchivedMark: Boolean = true,
    /** 非项目分组列表(进行中/搜索/归档)里把所属项目用小文件夹标出来。 */
    showProjectMark: Boolean = false,
) {
    val c = MikaTheme.colors
    val deletable = item.id != null
    val selectable = selecting && deletable
    val haptic = LocalHapticFeedback.current
    // 这一行在窗口里的位置和大小(不裁掉滚出列表的部分),菜单照它摆出抬起的那一行;只在回调里读,不引起重组
    val bounds = remember { mutableStateOf(Rect.Zero) }
    val row: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
                // 侧滑时前景必须遮住归档底层，不能让按钮透过标题和时间。
                .background(c.bg, NavShape)
                .then(if (item.current) Modifier.cardSurface(c, NavShape) else Modifier)
                .clip(NavShape)
                .then(
                    when {
                        selectable -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggleSelect(item) })
                        // 多选模式里没存过的草稿不响应,免得点一下退出侧栏
                        selecting -> Modifier
                        deletable -> Modifier.combinedClickable(
                            onClickLabel = null,
                            onLongClickLabel = stringResource(R.string.drawer_actions),
                            // 和系统里长按一样振一下
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(item, bounds.value) },
                            onClick = { if (item.enabled) onOpen(item) },
                        )
                        else -> Modifier.clickable(enabled = item.enabled, onClick = { onOpen(item) })
                    },
                )
                .testTag("conversation-row")
                .alpha(if (item.enabled || selectable) 1f else 0.45f)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectable) SelectMark(selected)
                Box(Modifier.weight(1f)) { ConversationRowContent(item, terms, showArchivedMark, showProjectMark) }
            }
        }
    }
    row()
}

/** 多选模式下每行左边的勾选框:没选是空心圆,选了是实心圆加一个白勾。 */
@Composable
private fun SelectMark(selected: Boolean) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier)
            .border(1.5.dp, if (selected) c.accent else c.edge, CircleShape)
            .testTag("conversation-check"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
    }
}

/** 一条对话里面的东西:进行中的小圆点、图钉、标题、时间,有副标题时下面一行。侧栏里那一行和长按抬起的那一张都用它。 */
@Composable
internal fun ConversationRowContent(
    item: DrawerConversation,
    terms: List<String> = emptyList(),
    showArchivedMark: Boolean = true,
    showProjectMark: Boolean = false,
) {
    val c = MikaTheme.colors
    val title = remember(item.title, terms, c.accent) { highlighted(item.title, terms, c.accent) }
    val showProjectName = showProjectMark && !item.projectName.isNullOrBlank()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (item.running) Box(Modifier.size(7.dp).background(c.accent, CircleShape))
            if (item.pinned) Icon(MikaIcons.Pin, stringResource(R.string.drawer_pinned_mark), Modifier.size(14.dp).testTag("conversation-pin"), tint = c.ink3)
            if (showProjectMark && item.projectId != null && !showProjectName) Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(14.dp).testTag("conversation-project"), tint = c.ink3)
            if (item.branch) Icon(MikaIcons.Route, stringResource(R.string.chat_branch_origin), Modifier.size(14.dp).testTag("conversation-branch"), tint = c.ink3)
            if (item.archived && showArchivedMark) Icon(MikaIcons.Archive, stringResource(R.string.drawer_archived), Modifier.size(14.dp).testTag("conversation-archived"), tint = c.ink3)
            Text(title, Modifier.weight(1f, fill = item.site == null), style = ConversationTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.site != null) {
                Text(
                    PageAskCopy.WEB, Modifier.background(c.accentSoft, RoundedCornerShape(MikaRadius.BubbleTail)).padding(horizontal = 6.dp).testTag("conversation-web"),
                    style = ConversationTag, color = c.accent, maxLines = 1,
                )
                Box(Modifier.weight(1f))
            }
            if (item.time.isNotEmpty()) {
                Text(item.time, Modifier.padding(start = 5.dp).testTag("conversation-time"), style = ConversationTime, color = c.ink3, maxLines = 1, softWrap = false)
            }
        }
        val sub = item.subtitle.ifEmpty { item.site.orEmpty() }
        if (showProjectName || sub.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showProjectName) {
                    Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(12.dp).testTag("conversation-project"), tint = c.ink2)
                    Text(
                        item.projectName!!,
                        Modifier.weight(1f, fill = false),
                        style = ConversationTime, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (sub.isNotEmpty()) Text("·", style = ConversationTime, color = c.ink3)
                }
                if (sub.isNotEmpty()) {
                    val styled = remember(sub, terms, c.accent) { highlighted(sub, terms, c.accent) }
                    Text(styled, Modifier.weight(1f, fill = !showProjectName), style = ConversationTime, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 给对话改名:输入框里是现在的标题(整段选中,直接打字就替换);空的存不了,两头空白去掉。 */
@Composable
internal fun RenameConversationDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("rename-conversation-field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag("confirm-rename-conversation"), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 删对话前的确认:写明删的是哪个对话;有任务在跑会停掉,绑着定时任务则写明计划会保留。
 * 和别处删东西的确认一样:右边红字「删除」,左边「取消」。
 */
@Composable
internal fun DeleteConversationDialog(title: String, impact: DeleteImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_delete_body, title),
        if (impact.runningTask) stringResource(R.string.drawer_delete_running) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_schedules, impact.schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 批量删对话前的确认:写明将删除几个对话;其中有任务在跑的会停掉,绑着定时任务的写明计划会保留。
 * 和单条删除的确认一样:右边红字「删除」,左边「取消」。
 */
@Composable
internal fun DeleteManyConversationsDialog(
    count: Int, runningTasks: Int, schedules: Int,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val body = listOfNotNull(
        trPlural(R.plurals.drawer_delete_many_body, count, count),
        if (runningTasks > 0) trPlural(R.plurals.drawer_delete_many_running, runningTasks, runningTasks) else null,
        if (schedules > 0) trPlural(R.plurals.drawer_delete_many_schedules, schedules, schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_many_title)) },
        text = { Text(body, Modifier.testTag("delete-many-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-many-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, Modifier.testTag("dismiss-delete-many-conversation")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 侧栏「项目」段头与「新建项目」入口。 */
@Composable
private fun ProjectsHeader(enabled: Boolean, onNewProject: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_projects)) }
        Row(
            Modifier.clip(NavShape).clickable(enabled = enabled, role = Role.Button, onClick = onNewProject)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-new-project"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Plus, null, Modifier.size(13.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_new_project), style = MikaType.Hint, color = c.ink2)
        }
    }
}

/** 侧栏里一个项目的可折叠分组头:点按展开/收起，右侧加号新建项目内对话，长按打开项目操作菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectGroupHeader(
    project: Project,
    count: Int,
    collapsed: Boolean,
    canCreate: Boolean,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
    onActions: (Rect) -> Unit,
) {
    val c = MikaTheme.colors
    val haptic = LocalHapticFeedback.current
    val bounds = remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .clip(NavShape)
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.drawer_project_actions),
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(bounds.value) },
                onClick = onToggle,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("project-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            MikaIcons.Chevron, null,
            Modifier.size(12.dp).rotate(if (collapsed) 0f else 90f),
            tint = c.ink3,
        )
        Icon(MikaIcons.Folder, null, Modifier.size(15.dp), tint = c.ink2)
        Text(project.name, Modifier.weight(1f), style = ConversationTitle, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count > 0) Text("$count", style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .clickable(enabled = canCreate, role = Role.Button, onClickLabel = stringResource(R.string.drawer_project_new_chat), onClick = onNewChat)
                .alpha(if (canCreate) 1f else 0.4f)
                .testTag("project-new-chat"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Plus, stringResource(R.string.drawer_project_new_chat), Modifier.size(14.dp), tint = c.ink2)
        }
    }
}

/** 新建 / 重命名项目的输入弹窗。 */
@Composable
private fun ProjectNameDialog(
    title: String,
    initial: String,
    confirmTag: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it },
                    Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("project-name-field"),
                    placeholder = { Text(stringResource(R.string.drawer_project_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag(confirmTag), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 删除项目的确认弹窗:写明会删除项目内全部对话与共享工作区文件。 */
@Composable
private fun DeleteProjectDialog(
    project: Project,
    impact: ProjectDeleteImpact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_project_delete_body, project.name),
        if (impact.runningTasks > 0) trPlural(R.plurals.drawer_delete_many_running, impact.runningTasks, impact.runningTasks) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_many_schedules, impact.schedules, impact.schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_project_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-project-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-project")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal const val MENU_IN_MS = 200
internal const val MENU_OUT_MS = 140
private val DrawerWidth = 360.dp

/** 卡片先纵向缩短,轮廓在屏幕上仍保持圆弧,不把上下角压成椭圆。 */
private class DrawerCardShape(private val progress: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 32.dp.toPx() } * progress
        return Outline.Rounded(RoundRect(
            rect = Rect(Offset.Zero, size),
            cornerRadius = CornerRadius(radius, radius / (1f - 0.04f * progress)),
        ))
    }
}
internal val NavShape = MikaShapes.Chip
private val SectionText = MikaType.Hint.copy(fontWeight = FontWeight.Medium)
private val ConversationTitle = MikaType.Body.copy(fontWeight = FontWeight.Medium)
private val ConversationTime = MikaType.Hint
private val ConversationTag = MikaType.Micro.copy(fontWeight = FontWeight.Medium)

/**
 * 对话页和它的侧栏:左上角菜单从左边滑出侧栏,外面一层遮罩;点遮罩、往左滑、按返回都关上。
 * 对话列表在后台预热和刷新。进了别的页面再回来,按 [reopen] 当场恢复侧栏。[content] 拿到「打开侧栏」放到菜单按钮上。
 */
@Composable
fun ChatWithDrawer(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    reduceMotion: Boolean,
    conversations: () -> List<ConversationSummary>,
    /** 存储里改过的对话(见 `ConversationStore.changes`);侧栏开着时别处存进来的对话也当场列出。 */
    conversationChanges: Flow<String> = emptyFlow(),
    /** 对话起好的标题;当前对话还没存进列表时(任务刚开始)也能叫出名字。 */
    conversationTitle: (String) -> String? = { null },
    /** 侧栏搜索用的索引;没有时侧栏不显示搜索框。 */
    search: ConversationSearch? = null,
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit = {},
    /** 有新版本:侧栏「关于 Mika」带小红点。 */
    updateAvailable: Boolean = false,
    /** 从侧栏进去的页面刚退回来:当场打开侧栏,回到进去之前的样子。 */
    reopen: Boolean = false,
    onReopened: () -> Unit = {},
    onRegularChat: () -> Unit = {},
    /** 外部跳回对话页时变化:若之前因返回键留在展开的侧栏,当场收起侧栏。 */
    resetKey: Int = 0,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    // 从侧栏页面返回时直接以展开状态创建,保留上游修复的首帧行为。
    val drawer = rememberChatDrawerState(if (reopen) DrawerValue.Open else DrawerValue.Closed)
    val backProgress = remember { Animatable(0f) }
    var drawerWidthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reopen) {
        if (reopen) onReopened()
    }
    val ui by vm.ui.collectAsState()
    val others by vm.otherRunning.collectAsState()
    fun close(then: () -> Unit = {}) {
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
        then()
    }
    /** 去别的页面:先当场关上侧栏,回来时就是关着的。 */
    fun leave(go: () -> Unit) {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    // 从手指刚拉动就准备列表,而不是过了半程才从空白跳成历史记录;完全收回才清理搜索等状态。
    val open by remember(drawer) {
        derivedStateOf {
            drawer.targetValue == DrawerValue.Open || drawer.isOpen ||
                (drawerWidthPx > 0 && drawer.currentOffset.isFinite() && drawer.currentOffset > -drawerWidthPx + 0.5f)
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    /** 搜索框里的字和已经拿到的结果;侧栏关掉就清空。 */
    var query by remember { mutableStateOf("") }
    var searchOutcome by remember { mutableStateOf<SearchOutcome?>(null) }
    /** 搜索结果的归档筛选;侧栏关掉回到「全部」。 */
    var searchFilter by remember { mutableStateOf(SearchArchiveFilter.All) }
    /** 多选模式:进去以后每行可勾选,底部换成批量删除的操作条;侧栏关上就退出。 */
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 归档视图:列表换成收起来的对话;侧栏关上就退出。 */
    var archivedView by remember { mutableStateOf(false) }
    /** 等用户确认的批量删除(对话编号)。 */
    var confirmingMany by remember { mutableStateOf<List<String>?>(null) }
    val deleteBatch by vm.deleteBatch.collectAsState()
    /** 批量归档的结果;在底部停留几秒。 */
    var archiveBatch by remember { mutableStateOf<ArchiveBatch?>(null) }
    LaunchedEffect(archiveBatch) {
        if (archiveBatch != null) {
            delay(3_000)
            archiveBatch = null
        }
    }
    /** 刚归档的那一条;底部给几秒钟的「撤销」。 */
    var archiveUndo by remember { mutableStateOf<ArchiveUndo?>(null) }
    LaunchedEffect(archiveUndo) {
        if (archiveUndo != null) {
            delay(4_000)
            archiveUndo = null
        }
    }
    /** 归档一条并给一次「撤销」;没归档成功(有任务在跑)就不提示。 */
    fun archiveWithUndo(id: String, title: String) {
        if (vm.archiveConversation(id)) archiveUndo = ArchiveUndo(id, title)
    }
    // 删除结果在底部停留几秒;下一批开始前不动它
    LaunchedEffect(deleteBatch) {
        val batch = deleteBatch ?: return@LaunchedEffect
        if (batch.finished) {
            delay(if (batch.failed == 0) 2_500L else 5_000L)
            vm.clearDeleteBatch()
        }
    }
    LaunchedEffect(open, search) {
        if (!open) {
            query = ""
            searchOutcome = null
            searchFilter = SearchArchiveFilter.All
            selecting = false
            selected = emptySet()
            archivedView = false
            archiveBatch = null
            archiveUndo = null
            // 正文索引只在侧栏开着时有用,关掉就放掉,长时间使用不会一直占着全部对话的正文
            search?.clear()
        }
    }
    // 返回先退出多选/归档/搜索,再收起侧栏。关着时交回系统,不把返回当作打开菜单。
    // 对话页里面板、画中画等自己的返回在内层,先于这里处理。
    val activity = LocalContext.current.findActivity()
    val context = LocalContext.current
    val nestedDrawerView = selecting || archivedView || query.isNotEmpty()
    BackHandler(enabled = open && nestedDrawerView) {
        when {
            selecting -> {
                selecting = false
                selected = emptySet()
            }
            archivedView -> archivedView = false
            query.isNotEmpty() -> query = ""
            else -> close()
        }
    }
    // 从右侧露出的聊天页贴边左划时,系统可能先接管返回。直接跟随返回进度,
    // 而不是等手指松开才突然启动另一段收起动画;取消返回则平滑回到展开位置。
    PredictiveBackHandler(enabled = open && !nestedDrawerView) { progress ->
        try {
            progress.collect { backProgress.snapTo(it.progress) }
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
            backProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            scope.launch {
                if (reduceMotion) backProgress.snapTo(0f)
                else backProgress.animateTo(0f, tween(180, easing = MikaMotion.Decelerate))
            }
            throw cancelled
        }
    }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(conversationChanges) { conversationChanges.collect { revision++ } }
    val deleted by vm.deleted.collectAsState()
    /** 长按了的那条和它在窗口里的位置;菜单开着(收起的动画放完才清掉)。 */
    var acting by remember { mutableStateOf<Pair<DrawerConversation, Rect>?>(null) }
    var actingProject by remember { mutableStateOf<Pair<Project, Rect>?>(null) }
    var creatingProject by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<Project?>(null) }
    var confirmingProject by remember { mutableStateOf<Pair<Project, ProjectDeleteImpact>?>(null) }
    /** 菜单出现的进度:0 是没有,1 是完全出来;关了动画时直接到位。 */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(acting, actingProject) {
        if (acting != null || actingProject != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    /** 收起菜单:倒着放一遍出现的动画,放完再清掉;[then] 当场做(改名、删除的弹窗不用等菜单收完)。 */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            acting = null
            actingProject = null
        }
    }
    /** 正在改名的那条。 */
    var renaming by remember { mutableStateOf<DrawerConversation?>(null) }
    var exporting by remember { mutableStateOf<DrawerConversation?>(null) }
    /** 要删的那条和删它会连带什么;确认弹窗开着。 */
    var confirming by remember { mutableStateOf<Pair<DrawerConversation, DeleteImpact>?>(null) }
    var lastReset by remember { mutableIntStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != lastReset) {
            lastReset = resetKey
            acting = null
            actingProject = null
            creatingProject = false
            renamingProject = null
            confirmingProject = null
            renaming = null
            confirming = null
            selecting = false
            selected = emptySet()
            archivedView = false
            confirmingMany = null
            archiveBatch = null
            archiveUndo = null
            query = ""
            searchOutcome = null
            searchFilter = SearchArchiveFilter.All
            drawer.snapTo(DrawerValue.Closed)
        }
    }
    val menuOpen = acting != null || actingProject != null
    val projects by vm.projects.collectAsState()
    val collapsedProjects by vm.collapsedProjects.collectAsState()
    val drawerColors = MikaTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(MikaTheme.colors.bg)) {
        // 与参照一致占手机宽度的 85%;窄窗口也至少留 48dp 供点击/左滑收回。
        val drawerWidth = minOf(DrawerWidth, maxWidth * 0.85f, (maxWidth - 48.dp).coerceAtLeast(0.dp))
        ChatNavigationDrawer(
            // 菜单开着时后面整屏(对话页和侧栏)模糊(安卓 12 起;更早的只变暗),无障碍也只读菜单
            modifier = if (!menuOpen) Modifier else Modifier
                .then(
                    if (GlassBlurSupported) Modifier.graphicsLayer {
                        val r = MenuBlur.toPx() * lift.value
                        renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                    } else Modifier,
                )
                .clearAndSetSemantics {},
            drawerContent = {
                // 提前在 IO 线程预热列表,之后保留最近快照;拖动开始的首帧不再同步遍历会话文件。
                val readHistory by rememberUpdatedState(conversations)
                val history by produceState(emptyList<ConversationSummary>(), open, revision) {
                    value = withContext(Dispatchers.IO) { readHistory() }
                }
                val drafts by vm.draftConversations.collectAsState()
                val cached by produceState(emptyList<ConversationSummary>(), history, drafts, revision) {
                    // 草稿元数据也在后台合并;输入草稿时只合并快照,不为每个字重新遍历会话目录。
                    value = withContext(Dispatchers.IO) { vm.withDrafts(history) }
                }
                val all = remember(open, cached) { if (open) cached else emptyList() }
                val readTitle by rememberUpdatedState(conversationTitle)
                val stored by produceState<String?>(null, open, revision, ui.conversationId) {
                    val id = ui.conversationId
                    value = if (open && id != null) withContext(Dispatchers.IO) { readTitle(id) } else null
                }
                val last = ui.exchanges.lastOrNull()
                val running = vm.busy()
                val current = CurrentConversation(
                    ui.conversationId, running,
                    stored ?: ChatCopy.title(ui.exchanges),
                    if (running) ChatCopy.subtitle(last, last?.let(vm::livePlace))?.first.orEmpty() else "",
                )
                val canSwitch = vm.canSwitch()
                // 搜索候选:标题换成侧栏里显示的样子(去掉附件行、网页说明),和行上看到的一致;再按归档状态筛。
                // 有没有可搜的对话看全部,不看当前筛选——筛到空的时候搜索框和筛选条还得留着,不然退不回来
                val searchable = remember(all, deleted) { all.any { it.id !in deleted } }
                val candidates = remember(all, deleted, searchFilter) { drawerSearchCandidates(all, deleted, searchFilter) }
                val engine = search
                LaunchedEffect(open, query, revision, candidates, engine) {
                    val q = query.trim()
                    if (engine == null || !open || q.isEmpty()) {
                        searchOutcome = null
                        return@LaunchedEffect
                    }
                    // 这个筛选下没有可搜的对话:直接给空结果,别让界面一直停在「搜索中」
                    if (candidates.isEmpty()) {
                        searchOutcome = SearchOutcome(q, emptyList(), searching = false)
                        return@LaunchedEffect
                    }
                    // 标题命中不用等正文,先列出来;正文扫完再整体换掉
                    searchOutcome = SearchOutcome(q, engine.titleMatches(q, candidates), searching = true)
                    delay(180)
                    val result = withContext(Dispatchers.IO) { engine.search(q, candidates) }
                    searchOutcome = SearchOutcome(q, result.hits, searching = false, truncated = result.truncated)
                }
                val byId = remember(candidates) { candidates.associateBy { it.id } }
                val now = System.currentTimeMillis()
                val runningIds = remember(others, ui.conversationId, running) {
                    (others + listOfNotNull(ui.conversationId?.takeIf { running })).toSet()
                }
                val projectNames = remember(projects) { projects.associate { it.id to it.name } }
                val searchView = searchOutcome?.takeIf { it.query == query.trim() }?.let { outcome ->
                    DrawerSearch(
                        outcome.hits.mapNotNull { hit ->
                            val s = byId[hit.id] ?: return@mapNotNull null
                            val time = if (s.updatedAt != 0L) conversationTime(s.updatedAt, now) else ""
                            drawerEntry(
                                s, ui.conversationId, canSwitch, running = hit.id in runningIds,
                                time = time, subtitle = hit.snippet.orEmpty(), target = hit.target,
                                projectName = s.projectId?.let(projectNames::get),
                            )
                        },
                        outcome.searching,
                        outcome.truncated,
                    )
                }
                // 删掉的对话当场不列,不等后台删完
                val sections = remember(all, current, others, canSwitch, deleted, projects) {
                    drawerSections(all.filter { it.id !in deleted }, current, others.filter { it !in deleted }, vm::runningGoal, canSwitch, System.currentTimeMillis(), projects = projects)
                }
                // 归档视图里的行:按归档时间倒序,最近收起来的在前;副标题写清所属项目和归档了多久
                val archivedRows = remember(all, current, canSwitch, deleted, runningIds, projectNames) {
                    val now = System.currentTimeMillis()
                    all.filter { it.id !in deleted && it.archivedAt != null }
                        .sortedByDescending { it.archivedAt }
                        .map { s ->
                            drawerEntry(
                                s, current.id, canSwitch, running = s.id in runningIds,
                                time = if (s.updatedAt != 0L) conversationTime(s.updatedAt, now) else "",
                                subtitle = s.archivedAt?.let { archivedAgo(it, now) }.orEmpty(),
                                projectName = s.projectId?.let(projectNames::get),
                            )
                        }
                }
                // 能勾的就是此刻列在侧栏里的对话;别处删了、列表变了,勾选里已经不存在的当场去掉。归档视图里换成归档的对话
                val selectableIds = remember(sections, archivedRows, archivedView) {
                    drawerSelectableIds(sections, archivedRows, archivedView)
                }
                LaunchedEffect(selectableIds) { selected = selected intersect selectableIds }
                // 普通侧栏和归档视图之间换,勾选不带过去
                LaunchedEffect(archivedView) {
                    selecting = false
                    selected = emptySet()
                }
                ChatDrawer(
                    check, sections, canSwitch,
                    modifier = Modifier.width(drawerWidth).onSizeChanged { drawerWidthPx = it.width }.offset {
                        val offset = drawer.currentOffset
                        val reveal = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                        IntOffset(-(reveal * backProgress.value).roundToInt(), 0)
                    },
                    // 与外层共用背景,让聊天卡片上下角的外侧阴影完整显示。
                    containerColor = Color.Transparent,
                    query = query,
                    onQueryChange = { query = it },
                    search = searchView,
                    canSearch = searchable,
                    searchFilter = searchFilter,
                    onSearchFilterChange = { searchFilter = it },
                    onNew = { close { vm.newConversation(); onRegularChat() } },
                    onSettings = { leave(onSettings) },
                    onScreens = { leave(onScreens) },
                    onAbout = { leave(onAbout) },
                    aboutDot = updateAvailable,
                    onActions = { item, bounds -> if (item.id != null) acting = item to bounds },
                    onOpen = { item ->
                        close {
                            onRegularChat()
                            val id = item.id
                            when {
                                id == null -> Unit
                                item.target != null -> vm.openConversationAt(id, item.target)
                                id == ui.conversationId -> Unit
                                item.running -> vm.switchTo(id)
                                else -> vm.openConversation(id)
                            }
                        }
                    },
                    selecting = selecting,
                    selected = selected,
                    onExitSelect = {
                        selecting = false
                        selected = emptySet()
                    },
                    onToggleSelect = { item ->
                        item.id?.let { id -> selected = if (id in selected) selected - id else selected + id }
                    },
                    onSelectAll = {
                        selected = if (selectableIds.isNotEmpty() && selectableIds.all { it in selected }) emptySet() else selectableIds
                    },
                    onDeleteSelected = {
                        val ids = selectableIds.filter { it in selected }
                        if (ids.isNotEmpty()) confirmingMany = ids
                    },
                    onUnarchiveSelected = {
                        val ids = selectableIds.filter { it in selected }
                        if (ids.isNotEmpty()) {
                            selecting = false
                            selected = emptySet()
                            vm.unarchiveConversations(ids)
                        }
                    },
                    onArchiveSelected = {
                        val ids = selectableIds.filter { it in selected }
                        if (ids.isNotEmpty()) {
                            selecting = false
                            selected = emptySet()
                            val archived = vm.archiveConversations(ids)
                            archiveBatch = ArchiveBatch(archived, ids.size - archived)
                        }
                    },
                    deleteBatch = deleteBatch,
                    archiveBatch = archiveBatch,
                    archiveUndo = archiveUndo,
                    onUndoArchive = {
                        val undo = archiveUndo
                        archiveUndo = null
                        undo?.let { vm.unarchiveConversation(it.id) }
                    },
                    archived = archivedRows,
                    archivedView = archivedView,
                    onOpenArchive = { archivedView = true },
                    onExitArchive = {
                        archivedView = false
                        selecting = false
                        selected = emptySet()
                    },
                    showProjectsSection = true,
                    collapsedProjects = collapsedProjects,
                    onToggleProject = vm::toggleProjectCollapsed,
                    onNewProject = { creatingProject = true },
                    onNewInProject = { pid -> close { vm.newConversation(pid); onRegularChat() } },
                    onProjectActions = { project, bounds -> actingProject = project to bounds },
                )
            },
            drawerState = drawer,
            contentOffset = {
                val offset = drawer.currentOffset
                val reveal = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                reveal * (1f - backProgress.value)
            },
            onDismiss = { close() },
            // 对话页上从左往右划拉出侧栏(拖着跟手);能左右滚的内容(宽表格、代码块等)先自己滚。
            // 屏幕边缘保留给系统返回;侧栏开着时返回收起侧栏,关着时交回系统。
            gesturesEnabled = !menuOpen,
        ) {
            // 使用抽屉同一份实时位移,聊天页宽度不变,和侧栏一起跟手平移;不能只在松手后播动画。
            // offset 会自动镜像 RTL,和 Material 抽屉的起始边一致。
            Box(
                Modifier.offset {
                    val offset = drawer.currentOffset
                    val push = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                    IntOffset((push * (1f - backProgress.value)).roundToInt(), 0)
                }.fillMaxSize().graphicsLayer {
                    val offset = drawer.currentOffset
                    val progress = if (drawerWidthPx > 0 && offset.isFinite())
                        ((drawerWidthPx + offset) / drawerWidthPx).coerceIn(0f, 1f) * (1f - backProgress.value) else 0f
                    // 现版 Kimi 的聊天页退成一张浮起的卡片:仅纵向略缩短,文字不被横向压窄。
                    scaleY = 1f - 0.04f * progress
                    shape = DrawerCardShape(progress)
                    clip = progress > 0f
                    shadowElevation = 16.dp.toPx() * progress
                    // 参考图是柔和的四周阴影,减弱 Android 默认向下投射的重阴影。
                    ambientShadowColor = drawerColors.floatShadow
                    spotShadowColor = drawerColors.floatShadow.copy(alpha = 0.18f)
                }.background(MikaTheme.colors.bg).drawWithContent {
                    drawContent()
                    val offset = drawer.currentOffset
                    val progress = if (drawerWidthPx > 0 && offset.isFinite())
                        ((drawerWidthPx + offset) / drawerWidthPx).coerceIn(0f, 1f) * (1f - backProgress.value) else 0f
                    // 深色主题里阴影不明显,用主题的细描边保留卡片边界。
                    if (drawerColors.isDark && progress > 0f) drawRoundRect(
                        color = drawerColors.edge.copy(alpha = drawerColors.edge.alpha * progress),
                        cornerRadius = CornerRadius(32.dp.toPx() * progress, 32.dp.toPx() * progress / (1f - 0.04f * progress)),
                        style = Stroke(1.dp.toPx()),
                    )
                }
                    .then(if (open) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("drawer-chat"),
            ) {
                content { scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() } }
                // 跟手淡化聊天内容,视觉焦点留在侧栏;绘制阶段读取位移,不让整页每帧重组。
                Box(Modifier.fillMaxSize().drawBehind {
                    val offset = drawer.currentOffset
                    val progress = if (drawerWidthPx > 0 && offset.isFinite())
                        ((drawerWidthPx + offset) / drawerWidthPx).coerceIn(0f, 1f) * (1f - backProgress.value) else 0f
                    drawRect(color = drawerColors.bg, alpha = 0.45f * progress)
                })
            }
        }
        acting?.let { (item, bounds) ->
            val id = item.id ?: return@let
            ConversationMenu(
                item, bounds, progress = { lift.value },
                onPin = { closeMenu { vm.setPinned(id, !item.pinned) } },
                onArchive = { closeMenu { archiveWithUndo(id, item.title) } },
                onUnarchive = { closeMenu { vm.unarchiveConversation(id) } },
                onRename = { closeMenu { renaming = item } },
                onTerminal = { closeMenu { activity?.let { a -> com.wangzan101.mika.ui.terminal.TerminalLauncher.open(a, id, item.title) } } },
                onExport = { closeMenu { exporting = item } },
                onDelete = { closeMenu { confirming = item to vm.deleteImpact(id) } },
                onSelect = { closeMenu { archiveUndo = null; selecting = true; selected = setOf(id) } },
                onDismiss = { closeMenu() },
            )
        }
        actingProject?.let { (project, bounds) ->
            ProjectMenu(
                project, bounds, progress = { lift.value },
                onNewChat = { closeMenu { close { vm.newConversation(project.id); onRegularChat() } } },
                onRename = { closeMenu { renamingProject = project } },
                onTerminal = {
                    closeMenu {
                        activity?.let { a ->
                            com.wangzan101.mika.ui.terminal.TerminalLauncher.openWorkspace(a, WorkspaceKey.Project(project.id), project.name)
                        }
                    }
                },
                onDelete = { closeMenu { confirmingProject = project to vm.projectDeleteImpact(project.id) } },
                onDismiss = { closeMenu() },
            )
        }
    }
    if (creatingProject) {
        ProjectNameDialog(
            title = stringResource(R.string.drawer_project_new_title),
            initial = "",
            confirmTag = "confirm-create-project",
            onSave = { name ->
                creatingProject = false
                vm.createProject(name)?.getOrNull()?.let { p ->
                    close { vm.newConversation(p.id); onRegularChat() }
                }
            },
            onDismiss = { creatingProject = false },
        )
    }
    renamingProject?.let { project ->
        ProjectNameDialog(
            title = stringResource(R.string.drawer_project_rename_title),
            initial = project.name,
            confirmTag = "confirm-rename-project",
            onSave = { name ->
                renamingProject = null
                vm.renameProject(project.id, name)
            },
            onDismiss = { renamingProject = null },
        )
    }
    confirmingProject?.let { (project, impact) ->
        DeleteProjectDialog(
            project = project,
            impact = impact,
            onConfirm = {
                confirmingProject = null
                vm.deleteProject(project.id)
            },
            onDismiss = { confirmingProject = null },
        )
    }
    renaming?.let { item ->
        RenameConversationDialog(
            item.title,
            onSave = { t ->
                renaming = null
                item.id?.let { vm.renameConversation(it, t) }
            },
            onDismiss = { renaming = null },
        )
    }
    confirming?.let { (item, impact) ->
        DeleteConversationDialog(
            item.title, impact,
            onConfirm = {
                confirming = null
                item.id?.let(vm::deleteConversation)
            },
            onDismiss = { confirming = null },
        )
    }
    confirmingMany?.let { ids ->
        // 弹窗里要写几条任务会停、几条定时任务保留;打开时算一次就够
        val impacts = remember(ids) { ids.map(vm::deleteImpact) }
        DeleteManyConversationsDialog(
            count = ids.size,
            runningTasks = impacts.count { it.runningTask },
            schedules = impacts.sumOf { it.schedules },
            onConfirm = {
                confirmingMany = null
                selecting = false
                selected = emptySet()
                vm.deleteConversations(ids)
            },
            onDismiss = { confirmingMany = null },
        )
    }
    exporting?.let { item ->
        item.id?.let { id -> ExportConversationDialog(id, item.title, onDismiss = { exporting = null }) }
    }
}
