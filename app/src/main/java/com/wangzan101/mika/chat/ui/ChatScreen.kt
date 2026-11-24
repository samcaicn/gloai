package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.gestures.scrollBy
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.GlassSurface
import com.wangzan101.mika.voicecall.ui.CallRecordsSheet
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.chat.ChecklistSource
import com.wangzan101.mika.chat.SavedChecklistStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ScrollToBottomButton
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.components.scrollListToBottom
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.WordmarkWeight
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.wangzan101.mika.files.ui.DeviceFileBrowser
import com.wangzan101.mika.settings.ui.PhoneSetupLine
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.task.ui.RecommendedTaskDialog
import com.wangzan101.mika.task.ui.SavedChecklistsScreen
import com.wangzan101.mika.task.ui.TaskFillDialog
import com.wangzan101.mika.ui.LocalAdaptiveLayout
import com.wangzan101.mika.voice.ui.voiceDim
import com.wangzan101.mika.workflow.ui.WorkflowsScreen

/**
 * 对话页:顶栏(左边打开侧栏、中间对话标题和此刻在做什么、右边新对话),对话在顶栏和玻璃输入栏之间滚动。
 * 进行中是一行状态加一条竖线步骤,不套卡片;要你处理时停在当前步,下面一张白卡;完成后过程收成一行,回答直接排在背景上。
 * Agent 的虚拟屏以悬浮小窗([AgentPip])盖在对话上,不占布局。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    frames: StateFlow<Bitmap?>,
    webOpen: StateFlow<Boolean>,
    /** 小窗直接操作的入口(点/滑/返回注入到虚拟屏);屏没建好或任务结束时为 null。 */
    pipInput: StateFlow<com.wangzan101.mika.cap.phone.display.PipInput?> = MutableStateFlow(null),
    /** 此刻有虚拟屏(或开着网页)的任务所在的会话:前台派出去的事有画面时小窗跟它。 */
    screens: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** 小窗目标 App 的声音被用户恢复过:声音按钮显示「静音」,否则「恢复声音」。 */
    pipSoundOn: StateFlow<Boolean> = MutableStateFlow(false),
    /** 用户在设置里开了「允许我在小窗里直接操作」。 */
    pipOperateEnabled: Boolean = false,
    reduceMotion: Boolean,
    /** 左上角菜单:打开侧栏。 */
    onOpenDrawer: () -> Unit,
    /** 被拦下的提示里的「去设置」。 */
    openSettings: () -> Unit,
    /** 「＋」面板里的「浏览器」:打开内置浏览器。 */
    onOpenBrowser: () -> Unit = {},
    openRun: (String) -> Unit = {},
    /** 任务要用手机而手机操作没准备好:在那轮问答下面放一行去设置(配不上具体轮次时挂在最后)。 */
    phoneSetupNeed: com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt.Need? = null,
    openPhoneSetup: () -> Unit = {},
    /** 输入栏按住说话从这里开会话;null 时只能打字。 */
    voice: VoiceSource? = null,
    /** 「还不能语音输入」里的「去选」。 */
    openSpeechModel: () -> Unit = {},
    /** 输入框里还没发出的字，交给「教 Mika」预填，不会自己开始。 */
    onOpenTeach: (String) -> Unit = {},
    /** 首页推荐任务。不传时用本机保存的那一份;测试传入单独的存储,避免改到用户的数据。 */
    recommendations: RecommendedTaskStore? = null,
    checklists: SavedChecklistStore? = null,
    workflows: com.wangzan101.mika.workflow.WorkflowRuntime? = null,
    /** 人格选择器里的「管理人格」:打开设置里的人格页。 */
    onOpenPersonas: () -> Unit = {},
    /** 输入栏的听筒按钮:进语音通话;为 null 时不显示(正常常驻,不可用时进门禁页引导)。 */
    onVoiceCall: (() -> Unit)? = null,
    /** 前台派出的一件的「查看详细过程」:打开它这次运行的过程,找不到运行时打开它的对话(阶段决定认哪次运行)。 */
    openDeskRun: (taskId: String, phase: com.wangzan101.mika.core.frontdesk.DeskTaskPhase) -> Unit = { _, _ -> },
    /** 右上角 ⋯ 里的「通话设置」。 */
    openCallSettings: () -> Unit = {},
) {
    val checklistStore = checklists ?: (LocalContext.current.applicationContext as? MikaApp)?.savedChecklists
    val workflowRuntime = workflows ?: (LocalContext.current.applicationContext as? MikaApp)?.workflows
    val recommendationStore = recommendations
        ?: (LocalContext.current.applicationContext as? MikaApp)?.recommendedTasks
    val recommendedTasksFlow = remember(recommendationStore) {
        recommendationStore?.also {
            it.ensureLoaded(seed = { ChatCopy.recommendationSeeds() }, retired = { ChatCopy.retiredRecommendationSeeds() })
        }?.tasks
            ?: MutableStateFlow<List<RecommendedTask>>(emptyList())
    }
    val recommendedTasks by recommendedTasksFlow.collectAsState()
    val ui by vm.ui.collectAsState()
    val projects by vm.projects.collectAsState()
    val currentProject = remember(projects, ui.projectId) { ui.projectId?.let { pid -> projects.firstOrNull { it.id == pid } } }
    var deviceBrowserConversation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.conversationId, deviceBrowserConversation) {
        if (deviceBrowserConversation != null && deviceBrowserConversation != vm.ui.value.conversationId) deviceBrowserConversation = null
    }
    deviceBrowserConversation?.takeIf { it == ui.conversationId }?.let { id ->
        DeviceFileBrowser(vm, id) { deviceBrowserConversation = null }
    }
    val pendingJump by vm.jump.collectAsState()
    val callLog by remember(ui.conversationId) { vm.calls(ui.conversationId) }.collectAsState(emptyList())
    val calls = remember(ui.exchanges, callLog) { callMarks(ui.exchanges, callLog) }
    val others by vm.otherRunning.collectAsState()
    val keptAny by vm.keptPage.collectAsState()
    // 只管这个对话留下的那一页
    val kept = keptAny?.takeIf { it.conversationId == ui.conversationId }
    val live by frames.collectAsState()
    val webLive by webOpen.collectAsState()
    val pipInputLive by pipInput.collectAsState()
    // 任务一结束虚拟屏就释放了;完成时小窗还要停留几秒打勾,继续显示最后一帧
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastFrameWasWeb by remember { mutableStateOf(false) }
    LaunchedEffect(live, webLive, ui.pip.mode) {
        live?.let {
            lastFrame = it
            if (webLive) lastFrameWasWeb = true
            else if (ui.pip.mode != PipMode.Done) lastFrameWasWeb = false
        }
    }
    val frame = live ?: lastFrame.takeIf { ui.pip.visible && ui.pip.mode == PipMode.Done }
    val webContent = if (ui.pip.mode == PipMode.Done) lastFrameWasWeb else webLive
    // 前台接的会话自己没有任务在跑时,小窗跟它派出去的事:在做或要你处理、有画面的几件里,要你处理的优先,再取最近动过的;
    // 让服务显示它,画面跟着换。放大、关掉记在页面里,按事情分开;不止一件在办时小窗上写是哪件
    val screenTasks by screens.collectAsState()
    val deskPip = if (ui.pip.visible) null else ui.deskTasks
        .filter { (it.state == DeskTaskPhase.Running || it.state == DeskTaskPhase.NeedsYou) && it.taskId in screenTasks }
        .sortedWith(compareBy<DeskTaskView> { if (it.state == DeskTaskPhase.NeedsYou) 0 else 1 }.thenByDescending { it.updatedAt })
        .firstOrNull()
    LaunchedEffect(deskPip?.taskId) { deskPip?.let { vm.showDeskScreen(it.taskId) } }
    var deskPipExpanded by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var deskPipClosed by remember(ui.conversationId) { mutableStateOf(emptySet<String>()) }
    val pip = deskPip?.let {
        PipUi(
            visible = true, mode = if (it.state == DeskTaskPhase.NeedsYou) PipMode.NeedsYou else PipMode.Running,
            expanded = deskPipExpanded == it.taskId && it.taskId !in deskPipClosed, closed = it.taskId in deskPipClosed,
        )
    } ?: ui.pip
    val pipLabel = deskPip?.title?.takeIf { trayOrder(ui.deskTasks).size > 1 }
    // 注册文字的 Android 状态保存;平时由进程内的 ViewModel 管理完整会话草稿。
    rememberSaveable(vm, saver = androidx.compose.runtime.saveable.listSaver(
        save = { model: ChatViewModel -> model.saveDraftTexts() },
        restore = { saved -> vm.also { it.restoreDraftTexts(saved) } },
    )) { vm }
    val input = ui.input
    // 推荐任务可以直接填进空输入框;已有内容时先确认,不能因误触把草稿换掉。
    var pendingExample by rememberSaveable(ui.conversationId) { mutableStateOf<String?>(null) }
    var showUsage by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showRecords by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showTasks by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showChecklists by remember(ui.conversationId) { mutableStateOf(false) }
    var checklistIncoming by remember(ui.conversationId) { mutableStateOf<ChecklistSource?>(null) }
    var pendingChecklistDraft by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var showWorkflows by rememberSaveable { mutableStateOf(false) }
    // 面板开着时选照片会把首页的草稿提升成对话(id 从 null 变成新值),所以不跟对话 id 绑;换对话时用户本来就会先关掉
    var showPlus by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var saveTaskPrompt by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var fillTaskPrompt by rememberSaveable(ui.conversationId) { mutableStateOf<String?>(null) }
    var fillTaskTitle by rememberSaveable(ui.conversationId) { mutableStateOf("") }
    val fillDraft: (String) -> Unit = { prompt ->
        if (vm.ui.value.input.isEmpty()) vm.input(prompt) else pendingExample = prompt
    }
    val pickTask: (RecommendedTask) -> Unit = { task ->
        if (task.fillIn && TaskTemplate.fields(task.prompt).isNotEmpty()) {
            fillTaskTitle = task.title
            fillTaskPrompt = task.prompt
        } else fillDraft(task.prompt)
    }
    var retryFailures by remember { mutableStateOf<Pair<String, String>?>(null) }
    var voiceHolding by remember { mutableStateOf(false) }
    var editRequest by remember { mutableIntStateOf(0) }
    var composerHeight by rememberSaveable { mutableIntStateOf(0) }
    var topHeight by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 从搜索或文件库跳过来时,短暂高亮目标行;新跳转带新 serial,会重新计时。
    var jumpHighlight by remember { mutableStateOf<ChatJump?>(null) }
    LaunchedEffect(jumpHighlight) {
        val active = jumpHighlight ?: return@LaunchedEffect
        delay(JUMP_HIGHLIGHT_MS)
        if (jumpHighlight == active) jumpHighlight = null
    }

    var cardFlash by remember { mutableStateOf<CardFlash?>(null) }
    LaunchedEffect(cardFlash) {
        val active = cardFlash ?: return@LaunchedEffect
        delay(JUMP_HIGHLIGHT_MS)
        if (cardFlash == active) cardFlash = null
    }

    // 只有原本跟着最新消息时才继续跟随。这个状态与列表位置一起跨页面保存。
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var scrollingToBottom by remember(ui.conversationId) { mutableStateOf(false) }
    var scrollToBottomJob by remember(ui.conversationId) { mutableStateOf<Job?>(null) }
    var previousScrollMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var previousConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val tailThresholdPx = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(listState, tailThresholdPx) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.nearTail(tailThresholdPx) }.collect { (scrolling, nearTail) ->
            if (!scrollingToBottom && (scrolling || wasScrolling)) followingTail = nearTail
            wasScrolling = scrolling
        }
    }

    // 返回聊天时同一条内容不重新滚动；新进展仅在跟随末尾时滚动。
    // 目标行直接由当前 UI 状态计算:layoutInfo 还是上一帧的测量结果,发送后立即读它会滚到旧的末尾。
    val last = ui.exchanges.lastOrNull()
    val setupRow = phoneSetupRow(phoneSetupNeed, ui.conversationId, ui.exchanges)
    // 前台派出去、此刻在等你的几件:对话末尾各一张问题卡
    val needs = ui.deskTasks.filter { it.need != null }.map { it.taskId }
    val rowCount = chatItemCount(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null, calls, needs)
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    val showScrollToBottom = ui.exchanges.isNotEmpty() && !followingTail && canScrollForward
    val scrollMarker = "${ui.conversationId}:${ui.exchanges.size}:${last?.steps?.size}:${last?.steers?.size}:${last?.status?.hashCode()}:${ui.blocked?.hashCode()}" +
        ":${callLog.size}:${callLog.lastOrNull()?.endMs}"
    val jump = pendingJump?.takeIf { it.conversationId == ui.conversationId }
    // 目标行的高亮:只在目标还是当前对话时算。
    val highlightKey = cardFlash?.takeIf { it.conversationId == ui.conversationId }?.key ?: jumpHighlight
        ?.takeIf { it.conversationId == ui.conversationId }
        ?.let { chatTargetKey(ui.exchanges, it.target) }
    val openDeskTask: (String) -> Unit = { id ->
        val view = ui.deskTasks.firstOrNull { it.taskId == id }
        openDeskRun(id, view?.state ?: DeskTaskPhase.Done)
    }
    // 滚到前台派出的一件任务的问题卡并短暂高亮;它此刻不在等你(没有问题卡)时返回 false
    val jumpToNeed: (String) -> Boolean = { taskId ->
        val key = needCardKey(taskId)
        val index = chatItemKeys(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null, calls, needs).indexOf(key)
        if (index < 0) false
        else {
            scrollToBottomJob?.cancel()
            followingTail = false
            scope.launch { if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index) }
            cardFlash = CardFlash(ui.conversationId, key, System.nanoTime())
            true
        }
    }
    LaunchedEffect(scrollMarker, jump) {
        // 从搜索或文件库过来:先滚到目标消息,期间不要跟末尾;目标不在了就退回原来的逻辑。
        if (jump != null) {
            val keys = chatItemKeys(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null, calls, needs)
            val index = chatTargetKey(ui.exchanges, jump.target)?.let(keys::indexOf) ?: -1
            if (index >= 0) {
                previousScrollMarker = scrollMarker
                previousConversation = ui.conversationId
                scrollToBottomJob?.cancel()
                followingTail = false
                listState.scrollToItem(index)
                jumpHighlight = jump
                vm.consumeJump(jump)
                return@LaunchedEffect
            }
            vm.consumeJump(jump)
        }
        if (previousScrollMarker == scrollMarker) return@LaunchedEffect
        val firstEntry = previousScrollMarker == null
        val changedConversation = previousConversation != ui.conversationId
        previousScrollMarker = scrollMarker
        previousConversation = ui.conversationId
        if (changedConversation) {
            scrollToBottomJob?.cancel()
            followingTail = true
        }
        if (!firstEntry && !changedConversation && !followingTail) return@LaunchedEffect
        if (rowCount > 0) {
            if (firstEntry || changedConversation || reduceMotion) listState.scrollToItem(rowCount - 1)
            else listState.animateScrollToItem(rowCount - 1)
        }
    }
    // 流式文字增长时跟住底部；用户主动向上翻看过程时不拉回去。
    LaunchedEffect(last?.draft?.length) {
        if (last?.draft.isNullOrEmpty() || !followingTail || listState.isScrollInProgress) return@LaunchedEffect
        val layout = listState.layoutInfo
        val end = layout.totalItemsCount - 1
        val draft = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return@LaunchedEffect
        val tailGap = draft.offset + draft.size - layout.viewportEndOffset
        if (tailGap > with(density) { 240.dp.roundToPx() }) return@LaunchedEffect
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val offset = (draft.size - viewport + with(density) { 12.dp.roundToPx() }).coerceAtLeast(0)
        listState.scrollToItem(end, offset)
    }

    // 输入栏变高(多半是输入法弹起,也可能是附件、多行输入):原本跟着最新消息时,对话流一起往上顶同样的距离,
    // 最后一条仍贴着输入栏;用户正往上翻看时不动。变矮时列表到底自己会收回来。
    var lastComposerHeight by remember { mutableIntStateOf(composerHeight) }
    LaunchedEffect(composerHeight) {
        val grown = composerHeight - lastComposerHeight
        lastComposerHeight = composerHeight
        if (grown > 0 && followingTail && !listState.isScrollInProgress) listState.scrollBy(grown.toFloat())
    }

    val context = LocalContext.current

    val c = MikaTheme.colors
    val backdrop = rememberGlassBackdrop()
    /** 长按了的那条消息;菜单开着(收起的动画放完才清掉)。 */
    var menu by remember { mutableStateOf<MessageTarget?>(null) }
    var queueActions by remember(ui.conversationId) { mutableStateOf<com.wangzan101.mika.task.QueuedFollowUp?>(null) }
    /** 选了「选择文字」的那条:它换回系统的长按选中;换对话时清掉。 */
    var selecting by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    /** 菜单出现的进度:0 是没有,1 是完全出来;关了动画时直接到位。 */
    val lift = remember { Animatable(0f) }
    val adaptive = LocalAdaptiveLayout.current
    val screenDocked = LocalOperationScreenDocked.current
    LaunchedEffect(adaptive) {
        menu = null
        lift.snapTo(0f)
    }
    LaunchedEffect(menu) {
        if (menu != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    LaunchedEffect(ui.pendingSharedDraft, ui.sharedDraft) {
        if (ui.pendingSharedDraft != null || ui.sharedDraft != null) {
            pendingExample = null
            fillTaskPrompt = null
            pendingChecklistDraft = null
            showChecklists = false
            showWorkflows = false
            if (menu != null) {
                lift.snapTo(0f)
                menu = null
            }
        }
    }
    /** 收起菜单:倒着放一遍出现的动画,放完再清掉;[then] 当场做。 */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            menu = null
        }
    }
    val menuHost = remember(selecting) { MessageMenuHost(selecting) { menu = it } }
    val menuOpen = menu != null
    /** 空对话:只有问候和推荐卡片,左右不套消息列表那圈留白,由卡片自己和输入栏对齐。 */
    val emptyConversation = ui.exchanges.isEmpty() && ui.blocked == null && calls.isEmpty()
    PageBackground {
        CompositionLocalProvider(LocalMessageMenu provides menuHost) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("chat-list").behindMenu(menuOpen) { lift.value }.voiceDim(voiceHolding, reduceMotion).glassSource(backdrop),
                state = listState,
                contentPadding = with(density) {
                    PaddingValues(
                        start = if (emptyConversation) 0.dp else 16.dp, end = if (emptyConversation) 0.dp else 16.dp,
                        top = topHeight.toDp() + 4.dp, bottom = composerHeight.toDp() + 12.dp,
                    )
                },
            ) {
                if (emptyConversation) {
                    item("empty") {
                        EmptyState(ui.conversationId, recommendedTasks, Modifier.fillParentMaxHeight(), onPick = pickTask)
                    }
                }
                ui.exchanges.forEachIndexed { i, ex ->
                    callMarkRows(calls.before(i), highlightKey, reduceMotion)
                    timeMarkAt(ui.exchanges, i, calls)?.let { at -> item(timeMarkKey(i)) { TimeMarkLine(at, timeMarkKey(i)) } }
                    val backgroundNote = i == ui.exchanges.lastIndex && ui.operatingPhone && !screenDocked
                    exchange(
                        i, ex, ui.conversationId, ui.takeoverStage, vm, reduceMotion, backgroundNote, openRun, kept,
                        highlightKey = highlightKey,
                        recover = { resume ->
                            val label = if (resume) R.string.chat_resume_request else R.string.chat_restart_request
                            vm.send(context.getString(label, ex.goal), check)
                        },
                    )
                    // 「去设置」挂在发现手机没就绪的那一轮后面;配不上具体轮次时(下面)照旧挂在最后
                    if (setupRow == i) item("ps$i") { PhoneSetupLine(openPhoneSetup) }
                }
                callMarkRows(calls.tail, highlightKey, reduceMotion)
                deskNeedRows(ui.deskTasks, highlightKey, reduceMotion, vm::answerDeskTask, vm::cancelDeskTask, vm)
                if (setupRow == -1) item("phone-setup") { PhoneSetupLine(openPhoneSetup) }
                ui.blocked?.let { problems ->
                    item("blocked") {
                        NeedCard(
                            stringResource(R.string.chat_cannot_start),
                            stringResource(R.string.chat_missing, problems.joinToString(stringResource(R.string.list_sep)) { StatusCopy.banner(it).title }),
                        ) {
                            CardButton(stringResource(R.string.ok_got_it), onClick = vm::dismissBlocked)
                            CardButton(stringResource(R.string.chat_go_settings), primary = true, onClick = { vm.dismissBlocked(); openSettings() })
                        }
                    }
                }
            }
        }

        Column(Modifier.behindMenu(menuOpen) { lift.value }.onSizeChanged { topHeight = it.height }) {
            // 悬浮玻璃三件套:菜单 / 标题 / 新建,各自一小块磨砂玻璃,不铺一整条
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!adaptive.hasNavigation || screenDocked) GlassSurface(modifier = Modifier.size(MikaSize.IconButton), backdrop = backdrop, shape = CircleShape) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape)
                                .clickable(onClickLabel = stringResource(R.string.chat_menu), onClick = onOpenDrawer),
                            contentAlignment = Alignment.Center,
                        ) {
                            DirectionalIcon(MikaIcons.Menu, stringResource(R.string.chat_menu), Modifier.size(MikaSize.Icon), tint = c.ink)
                            // 别的对话里还有任务在跑(并行时):右上角一个小圆点,侧栏「进行中」里能切过去
                            if (others.isNotEmpty()) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp).size(8.dp)
                                        .background(c.bg, CircleShape).padding(1.dp).background(c.accent, CircleShape),
                                )
                            }
                        }
                    }
                    GlassSurface(backdrop = backdrop) {
                        Row(Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_usage_title)) { showUsage = true }
                            .testTag("chat-usage-title").padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ChatCopy.APP_TITLE, style = MikaType.Title.copy(fontWeight = WordmarkWeight), color = c.ink, maxLines = 1)
                        }
                    }
                    ChatMoreMenu(
                        backdrop, canSwitch = vm.canSwitch(),
                        onNew = { vm.newConversation() },
                        onRecords = { showRecords = true },
                        onTasks = { showTasks = true },
                        onCallSettings = openCallSettings,
                    )
                }
                if (currentProject != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        GlassSurface(backdrop = backdrop, modifier = Modifier.testTag("chat-project-pill")) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(12.dp), tint = c.ink2)
                                Text(currentProject.name, style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        Composer(
            Modifier.align(Alignment.BottomCenter).behindMenu(menuOpen) { lift.value }.onSizeChanged { composerHeight = it.height },
            backdrop = backdrop,
            text = input,
            draftKey = ui.conversationId,
            onText = vm::input,
            mode = ui.composer,
            hasHistory = ui.exchanges.isNotEmpty(),
            onSend = { vm.send(input, check) },
            onStop = vm::cancel,
            queued = ui.queued,
            onQueueActions = { queueActions = it },
            onCancelQueued = { id ->
                if (!vm.cancelQueued(id)) Toast.makeText(context, context.getString(R.string.follow_up_already_started), Toast.LENGTH_SHORT).show()
            },
            onEditQueued = { id -> vm.editQueued(id)?.let { editRequest++ } },
            onStartQueued = vm::startQueued,
            onPlus = if (ui.attachEnabled) ({
                focusManager.clearFocus()
                keyboardController?.hide()
                showPlus = true
            }) else null,
            // 接了前台时随时能加附件;没接前台时附件跟着一件直接开跑的任务走,这条会话自己在跑任务时加不了(见 [ChatSession.canAttach])
            attachBlocked = ui.attachEnabled && !ui.importing && vm.deskFor(ui.conversationId) == null && ui.composer != ComposerMode.Idle,
            attachments = ui.attachments,
            importing = ui.importing,
            rejected = ui.rejectedAttachments,
            overLimit = ui.overLimit,
            reduceMotion = reduceMotion,
            onRemove = vm::removeAttachment,
            onDismissRejected = vm::dismissRejected,
            quote = ui.quote,
            onRemoveQuote = vm::clearQuote,
            voice = voice,
            // 说的话接在已有的字后面;任务在跑时先作为补充消息排队
            onVoiceText = { vm.input(HoldToTalk.join(vm.ui.value.input, it)) },
            // 松开在「在听」卡片里:接上已有的字直接发出;发不出去(比如还在导入附件)就留在输入框里
            onVoiceSend = {
                val text = HoldToTalk.join(vm.ui.value.input, it)
                vm.input(text)
                vm.send(text, check)
            },
            onPickSpeech = openSpeechModel,
            onVoiceCall = onVoiceCall,
            steerTarget = null,
            onVoiceHolding = { voiceHolding = it },
            editRequest = editRequest,
            deskTasks = ui.deskTasks,
            onStopTask = vm::cancelDeskTask,
            onTaskDetail = openDeskTask,
        )

        val pipOccupiesCorner = !screenDocked && pip.visible && pip.mode != PipMode.Done
        val composerBottomDp = with(density) { composerHeight.toDp() }
        ScrollToBottomButton(
            visible = showScrollToBottom,
            onClick = {
                if (rowCount <= 0) return@ScrollToBottomButton
                scrollToBottomJob?.cancel()
                followingTail = true
                scrollToBottomJob = scope.launch {
                    scrollingToBottom = true
                    try {
                        scrollListToBottom(listState, density, reduceMotion)
                        followingTail = listState.nearTail(tailThresholdPx)
                    } finally {
                        scrollingToBottom = false
                    }
                }
            },
            modifier = Modifier
                .align(if (pipOccupiesCorner) Alignment.BottomCenter else Alignment.BottomEnd)
                .behindMenu(menuOpen) { lift.value }
                .voiceDim(voiceHolding, reduceMotion)
                .padding(
                    end = if (pipOccupiesCorner) 0.dp else 16.dp,
                    bottom = composerBottomDp + 8.dp,
                ),
            backdrop = backdrop,
            reduceMotion = reduceMotion,
        )

        if (showPlus) {
            ChatPlusSheet(
                selected = ui.attachments,
                onDismiss = { showPlus = false },
                onPicked = { uris ->
                    vm.attach(uris, ui.conversationId)
                    showPlus = false
                },
                onToggle = vm::toggleAttachment,
                onFull = { Toast.makeText(context, AttachCopy.FULL, Toast.LENGTH_SHORT).show() },
                onDeviceFiles = if (FeatureFlags.get(context).deviceFiles()) ({
                    showPlus = false
                    deviceBrowserConversation = vm.libraryTargetConversation()
                }) else null,
                onPaste = {
                    val pasted = readClipboardText(context)
                    if (pasted.isNullOrBlank()) {
                        Toast.makeText(context, context.getString(R.string.plus_clipboard_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        showPlus = false
                        val now = vm.ui.value.input
                        vm.input(if (now.isEmpty()) pasted else now.trimEnd() + "\n" + pasted)
                        editRequest++
                    }
                },
                onTeach = if (ui.composer == ComposerMode.Idle) ({
                    showPlus = false
                    onOpenTeach(vm.ui.value.input.trim())
                }) else null,
                onChecklists = { showPlus = false; showChecklists = true },
                onWorkflows = { showPlus = false; showWorkflows = true },
                onBrowser = { showPlus = false; onOpenBrowser() },
                tasks = recommendedTasks,
                onPickTask = { showPlus = false; pickTask(it) },
                onPickSkill = { skill ->
                    showPlus = false
                    val prefix = context.getString(R.string.plus_skill_directive, skill.display)
                    vm.input(prefix + vm.ui.value.input)
                    editRequest++
                },
            )
        }

        if (showUsage) ConversationUsageSheet(ui.conversationId) { showUsage = false }
    if (showRecords) {
        CallRecordsSheet(
            callLog, current = callLog.lastOrNull { it.endMs == null }?.id.orEmpty(),
            onPick = { call -> showRecords = false; ui.conversationId?.let { vm.openConversationAt(it, ChatTarget.Call(call)) } },
            onDismiss = { showRecords = false },
        )
    }
    if (showTasks) {
        val items = remember(ui.exchanges, ui.deskTasks) { deskTaskItems(ui.exchanges, ui.deskTasks) }
        val titles by produceState(emptyMap<String, String>(), items) { value = vm.deskTaskTitles(items.map { it.taskId }) }
        DeskTasksSheet(items, titles, onPick = { showTasks = false; openDeskTask(it.taskId) }, onDismiss = { showTasks = false })
    }

        if (!screenDocked) AgentPip(
            pip = pip,
            frame = frame,
            webContent = webContent,
            pipInput = pipInputLive,
            soundOn = pipSoundOn.collectAsState().value,
            operateEnabled = pipOperateEnabled,
            reduceMotion = reduceMotion,
            topInset = topHeight.toFloat(),
            bottomInset = composerHeight.toFloat(),
            onExpand = { if (deskPip != null) deskPipExpanded = deskPip.taskId else vm.expandPip() },
            onCollapse = { if (deskPip != null) deskPipExpanded = null else vm.collapsePip() },
            // 关掉小画面:这个任务结束前只剩右下角的小按钮;提示一句还能去哪看
            onClose = {
                if (deskPip != null) {
                    deskPipClosed = deskPipClosed + deskPip.taskId
                    deskPipExpanded = null
                } else vm.closePip()
                Toast.makeText(context, context.getString(R.string.pip_closed_hint), Toast.LENGTH_SHORT).show()
            },
            onReopen = { if (deskPip != null) deskPipClosed = deskPipClosed - deskPip.taskId else vm.reopenPip() },
            onToggleSound = { if (deskPip != null) vm.togglePipSoundIn(deskPip.taskId) else vm.togglePipSound() },
            // 要你处理时点小窗:收起,滚到要你处理的那张卡片。Mika 在办的事滚到它的问题卡(没有就打开它的过程记录);
            // 自己的任务的卡片在最后一问一答里,它后面只可能还有文件卡片和去设置那一行
            onAttention = {
                if (deskPip != null) {
                    deskPipExpanded = null
                    if (!jumpToNeed(deskPip.taskId)) openDeskTask(deskPip.taskId)
                } else {
                    vm.attendPip()
                    val count = listState.layoutInfo.totalItemsCount
                    val after = (if (last?.files?.isNotEmpty() == true) 1 else 0) + (if (setupRow == ui.exchanges.lastIndex || setupRow == -1) 1 else 0)
                    val card = count - 1 - after
                    if (card >= 0) scope.launch { if (reduceMotion) listState.scrollToItem(card) else listState.animateScrollToItem(card) }
                }
            },
            label = pipLabel,
        )

        queueActions?.let { item ->
            ActionSheet(
                ChatCopy.plainTitle(item.text),
                buildList {
                    if (editableSteer(item.text)) add(SheetAction(stringResource(R.string.follow_up_edit)) {
                        vm.editQueued(item.id)?.let { editRequest++ }
                    })
                    add(SheetAction(stringResource(R.string.follow_up_cancel), destructive = true) {
                        if (!vm.cancelQueued(item.id)) Toast.makeText(context, context.getString(R.string.follow_up_already_started), Toast.LENGTH_SHORT).show()
                    })
                },
                onDismiss = { queueActions = null },
            )
        }

        // 长按消息的菜单盖在最上面:复制纯文字;引用放到输入框上方;选择文字让那条换回系统的长按选中;
        // 已经答完的一轮还能从这里分支
        menu?.let { m ->
            val pending = withdrawableSteer(ui.exchanges, m.key)
            val canEdit = input.isBlank() && ui.quote == null && ui.attachments.isEmpty() && !ui.importing
            val branchTurn = vm.branchTurn(m.key)
            val retryTurn = vm.retryTurn(m.key)
            MessageMenu(
                m, progress = { lift.value },
                onSaveTask = if (recommendationStore != null && m.from == QuoteMessage.From.You && m.text.isNotBlank()) ({
                    closeMenu { saveTaskPrompt = m.text }
                }) else null,
                onSaveChecklist = if (checklistStore != null && m.from == QuoteMessage.From.Mika && m.text.isNotBlank() && ui.conversationId != null) ({
                    closeMenu {
                        checklistIncoming = ChecklistSource(ui.conversationId!!, m.key, m.text)
                        showChecklists = true
                    }
                }) else null,
                onCopy = { closeMenu { copyMessage(context, m.text) } },
                onQuote = { closeMenu { vm.quote(m.from, m.text) } },
                onSelect = {
                    closeMenu {
                        selecting = m.key
                        Toast.makeText(context, context.getString(R.string.chat_select_hint), Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { closeMenu() },
                onBranch = branchTurn?.let { turn -> {
                    closeMenu {
                        scope.launch {
                            val id = vm.branchFrom(turn)
                            val copy = vm.branchCopy.value
                            val message = when {
                                id == null -> R.string.chat_branch_failed
                                copy?.truncated == true -> R.string.chat_branch_attachments_skipped
                                else -> R.string.chat_branch_done
                            }
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                            vm.clearBranchCopy()
                        }
                    }
                } },
                onBranchRetry = retryTurn?.let { turn -> {
                    closeMenu {
                        scope.launch {
                            val result = vm.branchBefore(turn)
                            val message = when {
                                result == null -> R.string.chat_branch_failed
                                result.needsReattach -> R.string.chat_branch_attachments_readd
                                vm.branchCopy.value?.truncated == true -> R.string.chat_branch_attachments_skipped
                                else -> R.string.chat_branch_done
                            }
                            if (result != null && result.failures.isNotEmpty() && vm.ui.value.conversationId == result.conversationId) {
                                val details = result.failures.joinToString("\n") { failure ->
                                    val reason = when (failure.status) {
                                        com.wangzan101.mika.chat.RetryAttachmentStatus.MISSING -> R.string.chat_retry_missing
                                        com.wangzan101.mika.chat.RetryAttachmentStatus.OVER_LIMIT -> R.string.chat_retry_limit
                                        else -> R.string.chat_retry_failed
                                    }
                                    context.getString(reason, failure.path.substringAfterLast('/'))
                                }
                                val screenHint = if (result.screenNeedsReattach) "\n" + context.getString(R.string.chat_branch_attachments_readd) else ""
                                retryFailures = result.conversationId to (details + screenHint)
                            } else if (result == null || vm.ui.value.conversationId == result.conversationId) {
                                Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                            }
                            vm.clearBranchCopy()
                        }
                    }
                } },
                onEdit = if (pending != null && canEdit && editableSteer(pending.text)) ({ closeMenu {
                    vm.editSteer(pending.id)?.let { editRequest++ }
                } }) else null,
                onWithdraw = pending?.let { n -> ({ closeMenu { vm.withdrawSteer(n.id) } }) },
            )
        }
    }

    retryFailures?.takeIf { it.first == ui.conversationId }?.let { (_, details) ->
        MikaDialog(
            onDismissRequest = { retryFailures = null },
            title = { Text(stringResource(R.string.chat_branch_attachments_skipped)) },
            text = { Text(details) },
            confirmButton = { TextButton({ retryFailures = null }) { Text(stringResource(R.string.ok_got_it)) } },
        )
    }

    if (showChecklists && checklistStore != null) androidx.compose.ui.window.Dialog(
        onDismissRequest = { showChecklists = false; checklistIncoming = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            SavedChecklistsScreen(checklistStore, incoming = checklistIncoming,
                onBack = { showChecklists = false; checklistIncoming = null },
                onUseDraft = { text ->
                    showChecklists = false; checklistIncoming = null
                    if (vm.ui.value.input.isEmpty()) vm.input(text) else pendingChecklistDraft = text
                })
        }
    }
    pendingChecklistDraft?.let { text ->
        MikaDialog(
            onDismissRequest = { pendingChecklistDraft = null },
            title = { Text(stringResource(R.string.chat_example_replace_title)) },
            text = { Text(stringResource(R.string.share_draft_replace_body)) },
            confirmButton = { TextButton({ vm.input(text); pendingChecklistDraft = null }, Modifier.testTag("checklist-replace-draft")) {
                Text(stringResource(R.string.chat_example_replace_action))
            } },
            dismissButton = { TextButton({ pendingChecklistDraft = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showWorkflows && workflowRuntime != null) androidx.compose.ui.window.Dialog(
        onDismissRequest = { showWorkflows = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            WorkflowsScreen(workflowRuntime, onBack = { showWorkflows = false },
                onOpenConversation = { id -> showWorkflows = false; vm.openWorkflowConversation(id) },
                onUseResult = { text ->
                    showWorkflows = false
                    if (vm.ui.value.input.isEmpty()) vm.input(text) else pendingExample = text
                })
        }
    }
    saveTaskPrompt?.let { prompt ->
        recommendationStore?.let { store ->
            RecommendedTaskDialog(store, initial = null, onDismiss = { saveTaskPrompt = null }, seedPrompt = prompt)
        }
    }

    fillTaskPrompt?.let { prompt ->
        TaskFillDialog(fillTaskTitle, prompt, onDismiss = { fillTaskPrompt = null }, onFill = { filled ->
            fillTaskPrompt = null
            fillDraft(filled)
        })
    }

    pendingExample?.let { example ->
        MikaDialog(
            onDismissRequest = { pendingExample = null },
            title = { Text(stringResource(R.string.chat_example_replace_title)) },
            text = { Text(stringResource(R.string.chat_example_replace_body)) },
            confirmButton = {
                TextButton(
                    onClick = { vm.input(example); pendingExample = null },
                    Modifier.testTag("confirm-replace-draft"),
                ) { Text(stringResource(R.string.chat_example_replace_action)) }
            },
            dismissButton = { TextButton({ pendingExample = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (ui.pendingSharedDraft != null) {
        MikaDialog(
            onDismissRequest = vm::cancelPendingSharedDraft,
            title = { Text(stringResource(R.string.chat_example_replace_title)) },
            text = { Text(stringResource(R.string.share_draft_replace_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!vm.confirmPendingSharedDraft()) {
                            Toast.makeText(context, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    Modifier.testTag("confirm-replace-shared-draft"),
                ) { Text(stringResource(R.string.chat_example_replace_action)) }
            },
            dismissButton = {
                TextButton(
                    onClick = vm::cancelPendingSharedDraft,
                    Modifier.testTag("cancel-replace-shared-draft"),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** 最后一项可能是一段很长的答复，仅仅看到它的开头不代表用户正在看末尾。 */
private fun LazyListState.nearTail(thresholdPx: Int): Boolean {
    val layout = layoutInfo
    val end = layout.totalItemsCount - 1
    val tail = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return false
    return tail.offset + tail.size - layout.viewportEndOffset <= thresholdPx
}

// ---------- 空对话 ----------

/** 跳到一张任务卡片后的短暂高亮:哪个对话、哪一行;[at] 让连点同一张也重新计时。 */
private data class CardFlash(val conversationId: String?, val key: String, val at: Long)
