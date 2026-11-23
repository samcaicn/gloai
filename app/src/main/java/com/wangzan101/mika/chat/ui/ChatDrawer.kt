package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.Project
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.chat.ConversationSearch
import com.wangzan101.mika.settings.Prerequisites
import androidx.compose.ui.geometry.Rect
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.lazyListScrollbar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.WordmarkWeight

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
    onPersonal: () -> Unit = {},
    onPlans: () -> Unit = {},
    onMemory: () -> Unit = {},
    onBrowser: () -> Unit = {},
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
    permanent: Boolean = false,
    onShowScreen: (() -> Unit)? = null,
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
        modifier.fillMaxHeight().then(if (permanent) Modifier else Modifier.widthIn(max = DrawerWidth)).fillMaxWidth()
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
                Text("Mika", style = MikaType.Title.copy(fontWeight = WordmarkWeight), color = c.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (ok) c.ok else c.warnSolid, CircleShape))
                    Text(state, style = MikaType.Hint, fontWeight = FontWeight.Medium, color = c.ink3)
                }
            }
            // 多选从长按菜单进;进去以后这里换成「取消」,返回键同样能退出
            if (selecting) HeaderAction(stringResource(R.string.cancel), "drawer-select-cancel", c.ink2, onExitSelect)
        }
        if (!searchActive && !selecting && !archivedView && permanent) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                onShowScreen?.let { NavEntry(MikaIcons.Screens, stringResource(R.string.screens_preview), onClick = it) }
                NavEntry(MikaIcons.Message, stringResource(R.string.drawer_personal), onClick = onPersonal)
                NavEntry(MikaIcons.Clock, stringResource(R.string.drawer_plans), onClick = onPlans)
                NavEntry(MikaIcons.Heart, stringResource(R.string.memory_and_skills), onClick = onMemory)
                NavEntry(MikaIcons.Globe, stringResource(R.string.drawer_browser), onClick = onBrowser)
            }
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
        LaunchedEffect(first) { list.requestScrollToItem(0) }
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
