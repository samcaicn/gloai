package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.i18n.trPlural

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.chat.ConversationHit
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
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
internal data class SearchOutcome(
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
