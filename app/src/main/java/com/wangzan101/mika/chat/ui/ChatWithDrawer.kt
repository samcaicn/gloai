package com.wangzan101.mika.chat.ui

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable

import com.wangzan101.mika.R
import com.wangzan101.mika.export.ExportConversationDialog
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.chat.ConversationSearch
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.components.GlassBlurSupported
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.ui.LocalAdaptiveLayout

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
    onPersonal: () -> Unit = {},
    onPlans: () -> Unit = {},
    onMemory: () -> Unit = {},
    onBrowser: () -> Unit = {},
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit = {},
    /** 有新版本:侧栏「关于 Mika」带小红点。 */
    updateAvailable: Boolean = false,
    /** 从侧栏进去的页面刚退回来:当场打开侧栏,回到进去之前的样子。 */
    reopen: Boolean = false,
    onReopened: () -> Unit = {},
    frames: StateFlow<Bitmap?>? = null,
    operationRun: (String) -> com.wangzan101.mika.task.TaskRun? = { null },
    chatActive: Boolean = true,
    onRegularChat: () -> Unit = {},
    /** 外部跳回对话页时变化:若之前因返回键留在展开的侧栏,当场收起侧栏。 */
    resetKey: Int = 0,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val adaptive = LocalAdaptiveLayout.current
    val permanent = adaptive.hasNavigation
    val ui by vm.ui.collectAsState()
    val runId = ui.exchanges.lastOrNull()?.runId
    var preferConversations by rememberSaveable(ui.conversationId, runId) { mutableStateOf(false) }
    val screenAvailable = chatActive && permanent && frames != null && ui.pip.visible
    val screenDocked = screenAvailable && !ui.pip.closed && !preferConversations
    // 从侧栏页面返回时直接以展开状态创建,保留上游修复的首帧行为。
    val drawer = rememberChatDrawerState(if (reopen && !permanent) DrawerValue.Open else DrawerValue.Closed)
    val backProgress = remember { Animatable(0f) }
    var drawerWidthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reopen, permanent) {
        if (permanent) drawer.snapTo(DrawerValue.Closed)
        if (reopen) {
            preferConversations = true
            onReopened()
        }
    }
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
    val open by remember(drawer, permanent) {
        derivedStateOf {
            permanent || drawer.targetValue == DrawerValue.Open || drawer.isOpen ||
                (drawerWidthPx > 0 && drawer.currentOffset.isFinite() && drawer.currentOffset > -drawerWidthPx + 0.5f)
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open, permanent) {
        if (open && !permanent) {
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
    PredictiveBackHandler(enabled = !permanent && open && !nestedDrawerView) { progress ->
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
    LaunchedEffect(adaptive) {
        acting = null
        actingProject = null
        lift.snapTo(0f)
    }
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
        AdaptiveChatNavigation(
            layout = adaptive,
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
                    modifier = (if (permanent) Modifier else Modifier.width(drawerWidth)).onSizeChanged { drawerWidthPx = it.width }.offset {
                        val offset = drawer.currentOffset
                        val reveal = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                        IntOffset(-(reveal * backProgress.value).roundToInt(), 0)
                    },
                    // 与外层共用背景,让聊天卡片上下角的外侧阴影完整显示。
                    containerColor = Color.Transparent,
                    permanent = permanent,
                    onShowScreen = if (screenAvailable) ({ preferConversations = false; vm.expandPip() }) else null,
                    onPersonal = { leave(onPersonal) },
                    onPlans = { leave(onPlans) },
                    onMemory = { leave(onMemory) },
                    onBrowser = { leave(onBrowser) },
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
            sideContent = if (screenDocked) ({
                val run = ui.conversationId?.let(operationRun)
                val liveFrame by (run?.frames ?: frames!!).collectAsState()
                var lastFrame by remember(ui.conversationId, runId) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(liveFrame) { liveFrame?.let { lastFrame = it } }
                val previewFrame = liveFrame ?: lastFrame.takeIf { ui.pip.mode == PipMode.Done }
                val showConversations = { preferConversations = true }
                val closeScreen = { preferConversations = true; vm.closePip() }
                if (run != null) InteractiveOperationScreen(run, previewFrame, ui.pip.mode, showConversations, closeScreen)
                else OperationScreenPane(previewFrame, ui.pip.mode, showConversations, closeScreen)
            }) else null,
            drawerGesturesEnabled = chatActive,
            compactContent = { navigation, detail ->
                ChatNavigationDrawer(
                    drawerContent = navigation,
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
                ) { detail() }
            },
        ) {
            // 使用抽屉同一份实时位移,聊天页宽度不变,和侧栏一起跟手平移;不能只在松手后播动画。
            // offset 会自动镜像 RTL,和 Material 抽屉的起始边一致。
            Box(
                (if (permanent) Modifier else Modifier.offset {
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
                    .testTag("drawer-chat")).fillMaxSize(),
            ) {
                CompositionLocalProvider(LocalOperationScreenDocked provides screenDocked) {
                    content {
                        preferConversations = true
                        if (!permanent) scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() }
                    }
                }
                // 跟手淡化聊天内容,视觉焦点留在侧栏;绘制阶段读取位移,不让整页每帧重组。
                if (!permanent) Box(Modifier.fillMaxSize().drawBehind {
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
