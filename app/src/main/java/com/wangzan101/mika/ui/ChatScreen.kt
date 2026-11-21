package com.wangzan101.mika.ui

import androidx.compose.foundation.gestures.scrollBy
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.GlassSurface
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.pageBackground
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.chat.ChecklistSource
import com.wangzan101.mika.chat.SavedChecklistStore
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.unconsumed
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ScrollToBottomButton
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.components.scrollListToBottom
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.viewer.FileViewer
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

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
    /** 小窗目标 App 的声音被用户恢复过:声音按钮显示「静音」,否则「恢复声音」。 */
    pipSoundOn: StateFlow<Boolean> = MutableStateFlow(false),
    /** 用户在设置里开了「允许我在小窗里直接操作」。 */
    pipOperateEnabled: Boolean = false,
    reduceMotion: Boolean,
    /** 左上角菜单:打开侧栏。 */
    onOpenDrawer: () -> Unit,
    /** 被拦下的提示里的「去设置」。 */
    openSettings: () -> Unit,
    openRun: (String) -> Unit = {},
    /** 任务要用手机而手机操作没准备好:在那轮问答下面放一行去设置(配不上具体轮次时挂在最后)。 */
    phoneSetupNeed: com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt.Need? = null,
    openPhoneSetup: () -> Unit = {},
    /** 按运行记录取依据的来处(「来源」胶囊和点开后的截图);读盘的活由调用方放到后台线程。 */
    loadSource: suspend (runId: String) -> EvidenceSource? = { null },
    openSource: (runId: String?, legacyText: String?) -> Unit = { _, _ -> },
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
    openReport: (String) -> Unit = {},
    openPlayback: (String) -> Unit = {},
    /** 人格选择器里的「管理人格」:打开设置里的人格页。 */
) {
    val checklistStore = checklists ?: (LocalContext.current.applicationContext as? MikaApp)?.savedChecklists
    val workflowRuntime = workflows ?: (LocalContext.current.applicationContext as? MikaApp)?.workflows
    val recommendationStore = recommendations
        ?: (LocalContext.current.applicationContext as? MikaApp)?.recommendedTasks
    val recommendedTasksFlow = remember(recommendationStore) {
        recommendationStore?.also { it.ensureLoaded { ChatCopy.recommendationSeeds() } }?.tasks
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
    // 注册文字的 Android 状态保存;平时由进程内的 ViewModel 管理完整会话草稿。
    rememberSaveable(vm, saver = androidx.compose.runtime.saveable.listSaver(
        save = { model: ChatViewModel -> model.saveDraftTexts() },
        restore = { saved -> vm.also { it.restoreDraftTexts(saved) } },
    )) { vm }
    val input = ui.input
    // 推荐任务可以直接填进空输入框;已有内容时先确认,不能因误触把草稿换掉。
    var pendingExample by rememberSaveable(ui.conversationId) { mutableStateOf<String?>(null) }
    var showUsage by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showChecklists by remember(ui.conversationId) { mutableStateOf(false) }
    var checklistIncoming by remember(ui.conversationId) { mutableStateOf<ChecklistSource?>(null) }
    var pendingChecklistDraft by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var showWorkflows by rememberSaveable { mutableStateOf(false) }
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
    val rowCount = chatItemCount(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null)
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    val showScrollToBottom = ui.exchanges.isNotEmpty() && !followingTail && canScrollForward
    val scrollMarker = "${ui.conversationId}:${ui.exchanges.size}:${last?.steps?.size}:${last?.steers?.size}:${last?.status?.hashCode()}:${ui.blocked?.hashCode()}"
    val jump = pendingJump?.takeIf { it.conversationId == ui.conversationId }
    // 目标行的高亮:只在目标还是当前对话时算。
    val highlightKey = jumpHighlight
        ?.takeIf { it.conversationId == ui.conversationId }
        ?.let { chatTargetKey(ui.exchanges, it.target) }
    LaunchedEffect(scrollMarker, jump) {
        // 从搜索或文件库过来:先滚到目标消息,期间不要跟末尾;目标不在了就退回原来的逻辑。
        if (jump != null) {
            val keys = chatItemKeys(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null)
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

    // 已结束各轮的运行小结:每轮结束那一行写这一轮的 token
    val endedRuns = ui.exchanges.filter { !it.status.isActive() }.mapNotNull { it.recordedRun }
    val runMetrics = rememberRunMetrics(endedRuns)
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
    PageBackground {
        CompositionLocalProvider(LocalMessageMenu provides menuHost) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("chat-list").behindMenu(menuOpen) { lift.value }.voiceDim(voiceHolding, reduceMotion).glassSource(backdrop),
                state = listState,
                contentPadding = with(density) {
                    PaddingValues(
                        start = 16.dp, end = 16.dp, top = topHeight.toDp() + 4.dp, bottom = composerHeight.toDp() + 12.dp,
                    )
                },
            ) {
                if (ui.exchanges.isEmpty() && ui.blocked == null) {
                    item("empty") {
                        EmptyState(ui.conversationId, recommendedTasks, Modifier.fillParentMaxHeight(), onPick = pickTask)
                    }
                }
                ui.exchanges.forEachIndexed { i, ex ->
                    val backgroundNote = i == ui.exchanges.lastIndex && ui.operatingPhone
                    exchange(
                        i, ex, ui.conversationId, ui.takeoverStage, vm, reduceMotion, backgroundNote, runMetrics, openRun, loadSource, openSource, kept,
                        highlightKey = highlightKey,
                        recover = { resume ->
                            val label = if (resume) R.string.chat_resume_request else R.string.chat_restart_request
                            vm.send(context.getString(label, ex.goal), check)
                        },
                        openReport = openReport, openPlayback = openPlayback,
                    )
                    // 「去设置」挂在发现手机没就绪的那一轮后面;配不上具体轮次时(下面)照旧挂在最后
                    if (setupRow == i) item("ps$i") { PhoneSetupLine(openPhoneSetup) }
                }
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
                    GlassSurface(modifier = Modifier.size(MikaSize.IconButton), backdrop = backdrop, shape = CircleShape) {
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
                            Text(ChatCopy.APP_TITLE, style = MikaType.Title, color = c.ink, maxLines = 1)
                        }
                    }
                    GlassSurface(modifier = Modifier.size(MikaSize.IconButton), backdrop = backdrop, shape = CircleShape) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape)
                                .clickable(enabled = vm.canSwitch(), onClickLabel = stringResource(R.string.chat_new), onClick = { vm.newConversation() })
                                .alpha(if (vm.canSwitch()) 1f else 0.35f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MikaIcons.New, stringResource(R.string.chat_new), Modifier.size(MikaSize.Icon), tint = c.ink)
                        }
                    }
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
            onTeach = if (ui.composer == ComposerMode.Idle) ({ onOpenTeach(input.trim()) }) else null,
            onStop = vm::cancel,
            queued = ui.queued,
            onGuideQueued = { id ->
                if (!vm.guideQueued(id, check)) Toast.makeText(context, context.getString(R.string.follow_up_already_started), Toast.LENGTH_SHORT).show()
            },
            onQueueActions = { queueActions = it },
            onCancelQueued = { id ->
                if (!vm.cancelQueued(id)) Toast.makeText(context, context.getString(R.string.follow_up_already_started), Toast.LENGTH_SHORT).show()
            },
            onEditQueued = { id -> vm.editQueued(id)?.let { editRequest++ } },
            onStartQueued = vm::startQueued,
            attachPanel = if (ui.attachEnabled) ({
                androidx.compose.runtime.key(ui.conversationId) {
                    AttachPanelHost(ui.attachments, onPicked = { vm.attach(it, ui.conversationId) }, onToggle = vm::toggleAttachment,
                        onDeviceFiles = if (com.wangzan101.mika.settings.FeatureFlags.get(context).deviceFiles()) ({
                            deviceBrowserConversation = vm.libraryTargetConversation()
                        }) else null)
                }
            }) else null,
            attachBlocked = ui.attachEnabled && !ui.importing && ui.composer != ComposerMode.Idle && ui.followUpMode == FollowUpMode.Steer,
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
            steerTarget = null,
            onVoiceHolding = { voiceHolding = it },
            editRequest = editRequest,
        )

        val pipOccupiesCorner = ui.pip.visible && ui.pip.mode != PipMode.Done
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

        if (showUsage) ConversationUsageSheet(ui.conversationId) { showUsage = false }

        AgentPip(
            pip = ui.pip,
            frame = frame,
            webContent = webContent,
            pipInput = pipInputLive,
            soundOn = pipSoundOn.collectAsState().value,
            operateEnabled = pipOperateEnabled,
            reduceMotion = reduceMotion,
            topInset = topHeight.toFloat(),
            bottomInset = composerHeight.toFloat(),
            onExpand = vm::expandPip,
            onCollapse = vm::collapsePip,
            // 关掉小画面:这个任务结束前只剩右下角的小按钮;提示一句还能去哪看
            onClose = {
                vm.closePip()
                Toast.makeText(context, context.getString(R.string.pip_closed_hint), Toast.LENGTH_SHORT).show()
            },
            onReopen = vm::reopenPip,
            onToggleSound = vm::togglePipSound,
            // 要你处理时点小窗:收起,滚到要你处理的那张卡片(在最后一问一答里,它后面只可能还有文件卡片和去设置那一行)
            onAttention = {
                vm.attendPip()
                val count = listState.layoutInfo.totalItemsCount
                val after = (if (last?.files?.isNotEmpty() == true) 1 else 0) + (if (setupRow == ui.exchanges.lastIndex || setupRow == -1) 1 else 0)
                val card = count - 1 - after
                if (card >= 0) scope.launch { if (reduceMotion) listState.scrollToItem(card) else listState.animateScrollToItem(card) }
            },
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

/** 空对话:居中问候 + 一句价值主张 + 自动换行的建议胶囊。点一条把完整提示词填进输入框,改好了再发;一条都没有时不显示。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(conversation: String?, tasks: List<RecommendedTask>, modifier: Modifier = Modifier, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    val hour = remember(conversation) { LocalTime.now().hour }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(ChatCopy.greeting(hour), style = MikaType.LargeTitle, color = c.ink, textAlign = TextAlign.Center)
        Text(ChatCopy.EMPTY_VALUE, Modifier.padding(top = 11.dp), style = EmptyLead, color = c.ink2, textAlign = TextAlign.Center)
        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tasks.forEach { task -> SuggestionChip(task.title) { onPick(task) } }
            }
        }
    }
}

/** 建议胶囊:白底 + 细描边 + 短标题,点它把完整提示词填进输入框。 */
@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Text(
        text,
        Modifier.clip(MikaShapes.Pill).background(c.surface, MikaShapes.Pill).border(0.5.dp, c.edge, MikaShapes.Pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = SuggestionText,
        color = c.ink2,
        maxLines = 1,
    )
}

// ---------- 一问一答 ----------

/** 跳转目标行的短暂高亮:一条全宽淡色底,约 1.8 秒后渐隐。 */
@Composable
private fun JumpHighlight(active: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    val alpha by animateFloatAsState(if (active) 1f else 0f, if (reduceMotion) snap() else tween(260), label = "jump-highlight")
    Box(
        Modifier.fillMaxWidth()
            .background(c.accent.copy(alpha = 0.12f * alpha), MikaShapes.Card),
    ) { content() }
}

private fun LazyListScope.exchange(
    index: Int, ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    runMetrics: Map<String, RunMetrics>,
    openRun: (String) -> Unit, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    kept: KeptPage? = null,
    /** 从搜索或文件库跳过来时要高亮的行 key。 */
    highlightKey: String? = null,
    /** 终态后的两个新运行入口:true 从摘要断点继续,false 按原目标重新开始。 */
    recover: (Boolean) -> Unit = {},
    openReport: (String) -> Unit = {}, openPlayback: (String) -> Unit = {},
) {
    if (ex.goal.isNotEmpty()) item("u$index") {
        JumpHighlight(highlightKey == "u$index", reduceMotion) { UserBubble(ex.goal, conversationId, key = "u$index") }
    }
    val paused = ex.status is ExchangeStatus.Paused || ex.status is ExchangeStatus.WaitingUnlock ||
        ex.status is ExchangeStatus.NeedsTakeover || ex.status is ExchangeStatus.NeedsConfirmation ||
        ex.status is ExchangeStatus.NeedsInput || ex.status is ExchangeStatus.Queued
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    // 插话:干活中作为右侧一步插进步骤里(见 [StepLine]);没在跑步骤(结束了、或在写总结)时收成紧跟
    // 用户消息下面的一条弱化小字,和前面的主对话区分主从。撤回的插话不显示。
    val steers = ex.steers.filterNot { it.state == SteerState.Withdrawn }
    if (active && !answering) {
        item("s$index") { WorkBlock(ex, steers.map { it.text }, reduceMotion, backgroundNote, openRun) }
    } else {
        steers.forEach { n ->
            item("m$index-${n.id}") {
                JumpHighlight(highlightKey == "m$index-${n.id}", reduceMotion) { SteerSupplement(n.text) }
            }
        }
    }
    ex.draft?.takeIf(String::isNotBlank)?.let { draft -> item("d$index") { DraftText(draft, ex.waitingForModel) } }
    item("r$index") {
        JumpHighlight(highlightKey == "r$index", reduceMotion) {
            StatusCard(ex.status, stage, vm, ex.recordedRun, ex.elapsedMs, ex.recordedRun?.let { runMetrics[it]?.tokens }, loadSource, openSource, openRun, "r$index",
                conversationId = conversationId,
                recover = recover, goal = ex.goal, openReport = openReport, openPlayback = openPlayback)
        }
    }
    // 这一轮结束后留在后台运行的那一页:还开着是绿色一行(能关),关了是一句原因
    keptPageRow(index, ex, kept, vm::closeKeptPage)
    if (ex.audios.isNotEmpty()) item("au$index") { GeneratedAudioCards(ex.audios, vm) }
    if (ex.photos.isNotEmpty()) item("p$index") {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { GeneratedPhotos(ex.photos, conversationId, 292.dp) }
    }
    if (ex.files.isNotEmpty()) item("f$index") {
        JumpHighlight(highlightKey == "f$index", reduceMotion) { FileCards(ex.files) }
    }
    learningNotes(index, ex, vm)
}

/**
 * 插话分成两段:第一句还没交给模型的([SteerState.unconsumed])之前的,和它连同之后的。
 * 正在长出来的回答排在两段中间。
 */
internal fun steersAroundDraft(steers: List<SteerNote>): Pair<List<SteerNote>, List<SteerNote>> {
    val visible = steers.filterNot { it.state == SteerState.Withdrawn }
    val first = visible.indexOfFirst { it.state.unconsumed }.let { if (it < 0) visible.size else it }
    return visible.take(first) to visible.drop(first)
}

/**
 * 长按的 [key] 是进行中那一问一答里、模型还没取走的手机插话时给出它,菜单据此提供撤回和编辑(编辑另看 [editableSteer])。
 * 电脑追加的([SteerNote.remote])不给:编辑会把只归当前任务的话变成手机插话、可能转成下一件事;撤不撤由电脑定。
 */
internal fun withdrawableSteer(exchanges: List<Exchange>, key: String): SteerNote? = exchanges.mapIndexedNotNull { i, ex ->
    if (ex.status.isActive()) ex.steers.firstOrNull { key == "m$i-${it.id}" && it.state.unconsumed && !it.remote } else null
}.firstOrNull()

internal fun ExchangeStatus.isActive() =
    this !is ExchangeStatus.Done && this !is ExchangeStatus.Failed && this !is ExchangeStatus.Cancelled

/**
 * 用户的话:淡蓝底气泡(深字),圆角 20、右下尾角 6,最宽八成。[note] 是插话的去向。
 * 带着用户此刻的屏幕时上面是截图缩略图和「你此刻的屏幕 · App · 时刻」,附言在下面的气泡里(见 [UserMessage])。
 * 带着附件时上面是照片格子和文件卡片(见 [SentAttachments]),点开在 App 里的查看页看;写的话在下面另起一个气泡,路径那几行不显示。
 * 引用了前面的消息时最上面是引的那条(见 [SentQuote])。长按气泡弹出复制 / 引用 / 选择文字([key] 认是哪一条,见 [MessageArea])。
 * 在浏览器页上对着一页问的,最上面是「网页 · 网站」和选中的字(见 [SentPage]),页面说明那几行不显示。
 */
@Composable
internal fun UserBubble(text: String, conversationId: String?, note: String? = null, key: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paged = remember(text) { AttachedPage.parse(text) }
    val quoted = remember(text) { QuoteMessage.parse(paged.words) }
    val attached = remember(text) { AttachmentMessage.parse(quoted.words) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        Column(horizontalAlignment = Alignment.End) {
            paged.page?.let { SentPage(it, max) }
            quoted.quote?.let { SentQuote(it, max) }
            if (attached.hasAttachments) {
                SentAttachments(attached, conversationId, max) { item ->
                    val id = conversationId ?: return@SentAttachments
                    scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                }
            }
            if (attached.hasAttachments && attached.words.isNotEmpty()) Spacer(Modifier.size(6.dp))
            if ((!attached.hasAttachments || attached.words.isNotEmpty()) && (paged.page == null || quoted.words.isNotEmpty())) UserMessage(conversationId, attached.words.takeIf { attached.hasAttachments } ?: quoted.words, ChatThumbWidth) { words ->
                // 用户气泡是淡蓝底 + 深字:选中的底色用稍浓一点的蓝,把手仍是强调色(画在气泡外面)
                val colors = MikaTheme.colors
                val bubble = colors.accent.copy(alpha = 0.14f)
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f))) {
                    MessageArea(
                        key, QuoteMessage.From.You, words, "selectable-user",
                        Modifier.widthIn(max = max).background(bubble, MikaShapes.UserBubble),
                        lifted = { LiftedMessage(MikaShapes.UserBubble, bubble) { UserBubbleText(words) } },
                    ) { UserBubbleText(words) }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 3.dp, end = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3) }
        }
    }
}

@Composable
private fun UserBubbleText(words: String) {
    Text(words, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = BubbleText, color = MikaTheme.colors.ink)
}

/** 长按抬起的那条消息:[color] 底、带阴影;太长时(最高半屏)只露开头,下面裁掉。 */
@Composable
private fun LiftedMessage(shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.floatShadow(MikaTheme.colors, shape).background(color, shape).clip(shape)
            .wrapContentHeight(Alignment.Top, unbounded = true),
    ) { content() }
}

/** 对话页里截图缩略图的宽。 */
private val ChatThumbWidth = 110.dp

/** 正在生成的回答:和完成后的回答一样放在淡灰底上。 */
@Composable
private fun DraftText(text: String, generating: Boolean) {
    MarkdownText(
        text, AnswerText.copy(color = MikaTheme.colors.ink),
        Modifier.fillMaxWidth().padding(bottom = 12.dp).answerSurface(), streaming = generating,
    )
}

/** 结束后插话收成的弱化小字:右对齐、比正文淡一点点,紧跟用户消息下面,和主回答区分主从。 */
@Composable
private fun SteerSupplement(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, Modifier.padding(horizontal = 4.dp), style = SupplementText, color = MikaTheme.colors.inkSoft)
    }
}

/** 干活中插话作为「右侧插入的一步」:淡蓝胶囊、右对齐,和左边的模型步骤区分开。 */
@Composable
private fun SteerStepPill(text: String) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier.background(c.accentSoft, SteerPillShape).clip(SteerPillShape).padding(horizontal = 12.dp, vertical = 6.dp),
            style = StepText, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Mika 的一段回答:长按弹出复制 / 引用 / 选择文字(见 [MessageArea]),复制、引用的是去掉 Markdown 符号的纯文字;
 * [modifier] 是回答的底,抬起的那张是同样的字放在白卡上。选择文字时选区的把手在单独的弹出窗口里,拖把手不会带出侧栏。
 */
@Composable
private fun AnswerArea(key: String, answer: String, modifier: Modifier, content: @Composable () -> Unit) {
    val plain = remember(answer) { MarkdownModel.toPlainText(answer) }
    MessageArea(
        key, QuoteMessage.From.Mika, plain, "selectable-answer", modifier,
        lifted = {
            LiftedMessage(MikaShapes.AnswerBubble, MikaTheme.colors.surface) {
                MarkdownText(answer, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), streaming = false)
            }
        },
    ) { content() }
}

/** Mika 回答的底:很淡的灰,左下尾角。 */
@Composable
private fun Modifier.answerSurface(): Modifier =
    background(MikaTheme.colors.answer, MikaShapes.AnswerBubble).padding(horizontal = 14.dp, vertical = 12.dp)

// ---------- 进度 ----------

/**
 * 进行中:一行状态(呼吸点 + 此刻的进展 + 用时),下面一条竖线步骤(做完的灰点、当前的蓝点;停下来等你时当前步是橙点)。
 * 在后台操作手机时最下面一行小字。
 */
@Composable
internal fun WorkBlock(ex: Exchange, steers: List<String>, reduceMotion: Boolean, backgroundNote: Boolean, openRun: (String) -> Unit) {
    val c = MikaTheme.colors
    val paused = ChatCopy.paused(ex.status)
    val elapsed = rememberElapsed(ex.startedAt)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        TurnMeta(elapsed, tokens = null)
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BreathingDot(paused, reduceMotion)
            Text(
                ChatCopy.progressLine(ex), Modifier.weight(1f), style = NowText, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        ex.goals?.let { GoalChecklistRow(it, ex.runId) }
        if (ex.steps.isNotEmpty() || steers.isNotEmpty()) StepLine(ex.steps, ex.stepActions, steers, live = true, paused = paused)
        // 跑的时候就能进运行过程(页面按秒接着长);结束后收进「做了 N 步 · 查看过程」那一行
        ex.recordedRun?.let { id ->
            Row(
                Modifier.padding(top = 2.dp).clip(MikaShapes.Pill).clickable(role = Role.Button) { openRun(id) }
                    .padding(horizontal = 4.dp, vertical = 4.dp).testTag("work-view-process"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.chat_view_process), style = DoneText, color = c.ink3)
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = c.ink3)
            }
        }
        if (backgroundNote) {
            Row(
                Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Eye, null, Modifier.size(15.dp), tint = c.ink3)
                Text(ChatCopy.BACKGROUND_NOTE, style = NoteText, color = c.ink3)
            }
        }
    }
}

/** 回答/干活头部那行:「Mika · 0:42 · 1.2 万 Token」;[elapsed]/[tokens] 为 null 时略去对应段。 */
@Composable
private fun TurnMeta(elapsed: Long?, tokens: Long?) {
    val c = MikaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(ChatCopy.APP_TITLE, style = TurnNameText, color = c.ink)
        if (elapsed != null) {
            Text("·", style = ClockText, color = c.ink3)
            Text(ChatCopy.clock(elapsed), style = ClockText, color = c.ink3)
        }
        if (tokens != null && tokens > 0) {
            Text("·", style = ClockText, color = c.ink3)
            Text(ChatTokensCopy.amount(tokens), style = ClockText, color = c.ink3)
        }
    }
}

@Composable
private fun GoalChecklistRow(goals: GoalChecklist, runId: String?) {
    val c = MikaTheme.colors
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(StatusCopy.goals(goals), style = NoteText, color = c.ink2)
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = c.ink3)
        }
        if (expanded) goals.items.forEachIndexed { i, goal ->
            Text("${if (i + 1 in goals.confirmed) "✓" else "○"}  $goal", style = NoteText, color = c.ink2)
        }
    }
}

/** 从 [startedAt] 起的用时,每秒更新;不知道开始时刻(为 0)时为 null。 */
@Composable
private fun rememberElapsed(startedAt: Long): Long? {
    if (startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return (now - startedAt).coerceAtLeast(0L)
}

private enum class Dot { Done, Current, Paused, Dim }

/**
 * 竖线步骤。[live] 时最后一步按任务此刻的状态标蓝点或橙点;在跑时只列最近 [ChatCopy.VISIBLE_STEPS] 步,
 * 前面的收成一行,点开看全部。
 */
@Composable
private fun StepLine(steps: List<StepRow>, actions: Map<Int, List<StepAction>>, steers: List<String>, live: Boolean, paused: Boolean) {
    val c = MikaTheme.colors
    var all by rememberSaveable { mutableStateOf(!live) }
    // 哪几步点开了:按这一屏里的序号记,列表变长也不乱
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // 步数多到值得收起来(超过这一屏列出的最近几步)时,才给「展开/收起」的开关
    val collapsible = steps.size > ChatCopy.VISIBLE_STEPS
    val hidden = if (all) 0 else (steps.size - ChatCopy.VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().drawBehind {
            val x = StepRail.toPx()
            drawLine(c.hair, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        },
    ) {
        // 顶部开关:折叠时「前面还有 N 步」,展开时「收起」,点一下来回切
        if (collapsible) {
            val label = if (all) stringResource(R.string.collapse) else ChatCopy.earlierSteps(hidden)
            StepItem(label, "", Dot.Dim, current = false, link = true, onClick = { all = !all })
        }
        val visible = steps.drop(hidden)
        visible.forEachIndexed { i, row ->
            val key = hidden + i
            val isLast = key == steps.lastIndex
            // 用户插话:右侧一步,插在完成的步骤和当前步之间
            if (isLast) steers.forEach { SteerStepPill(it) }
            val dot = when {
                live && isLast && paused -> Dot.Paused
                live && isLast && row.state == StepState.Run -> Dot.Current
                row.state == StepState.Dim -> Dot.Dim
                else -> Dot.Done
            }
            val open = expanded[key] == true
            val detail = actions[row.step].orEmpty()
            StepItem(
                row.text, row.subtitle, dot, current = dot == Dot.Current || dot == Dot.Paused,
                actions = detail, open = open,
                // 有具体动作才点得开:点开看这一步实际做了什么、对什么、结果如何
                onClick = if (detail.isEmpty()) null else ({ expanded[key] = !open }),
            )
        }
        // 还没有任何步骤时(刚把插话交给任务)只显示插话这一步
        if (visible.isEmpty()) steers.forEach { SteerStepPill(it) }
        // 底部「收起」:展开后步数一多,滑到最下面不用再爬回去也能收
        if (all && collapsible) {
            StepItem(stringResource(R.string.collapse), "", Dot.Dim, current = false, link = true, onClick = { all = false })
        }
    }
}

@Composable
private fun StepItem(
    text: String, subtitle: String, dot: Dot, current: Boolean, link: Boolean = false, onClick: (() -> Unit)? = null,
    actions: List<StepAction> = emptyList(), open: Boolean = false,
) {
    val c = MikaTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(if (open) R.string.collapse else R.string.chat_expand_steps), onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 28.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StepRail * 2), contentAlignment = Alignment.Center) { StepDot(dot) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = if (current) StepCurrentText else StepText,
                color = when {
                    link -> c.accent
                    current -> c.ink
                    else -> c.ink2
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // 点开:这一步实际做的几件事,一件一行「做了什么 · 对什么 · 结果」,说法和运行过程页一样
            if (open) actions.forEach { a ->
                Text(
                    ChatCopy.actionLine(a), Modifier.padding(top = 2.dp),
                    style = MikaType.Hint, color = if (a.failed) c.bad else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 能点开的只有有动作的那些:右边一个小箭头,点开时朝下
        if (actions.isNotEmpty()) DirectionalIcon(
            MikaIcons.Chevron, null,
            Modifier.padding(start = 6.dp).size(12.dp).graphicsLayer { rotationZ = if (open) if (rtl) -90f else 90f else 0f },
            tint = c.ink3,
        )
    }
}

/** 步骤点:6dp 实心;当前步外面一圈 3dp 的淡色光环。 */
@Composable
private fun StepDot(dot: Dot) {
    val c = MikaTheme.colors
    val (fill, ring) = when (dot) {
        Dot.Done -> c.ink3 to null
        Dot.Dim -> c.hair to null
        Dot.Current -> c.accent to c.accentSoft
        Dot.Paused -> c.warnSolid to c.warnSoft
    }
    // 底色先盖住竖线,点才是干净的
    val bg = c.bg
    Box(
        Modifier.size(12.dp).drawBehind {
            val center = Offset(size.width / 2, size.height / 2)
            if (ring != null) {
                drawCircle(bg, 6.dp.toPx(), center)
                drawCircle(ring, 6.dp.toPx(), center)
            }
            drawCircle(fill, 3.dp.toPx(), center)
        },
    )
}

/** 转圈:淡色圆环,顶上一段实色。[spinning] 为假(关了动画、停下来等你)时停在原处。 */
@Composable
internal fun Spinner(modifier: Modifier, spinning: Boolean, track: Color, head: Color) {
    val turn = if (!spinning) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    ).value
    Box(
        modifier.graphicsLayer { rotationZ = turn }
            .border(2.dp, track, CircleShape)
            .border(
                2.dp,
                Brush.sweepGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 0.63f to head, 0.88f to head, 0.89f to Color.Transparent),
                CircleShape,
            ),
    )
}

/** 干活那行「活着」的信号:一颗呼吸的小点(淡→实、微微放大);停下来等你时变橙、静止。 */
@Composable
private fun BreathingDot(paused: Boolean, reduceMotion: Boolean) {
    val c = MikaTheme.colors
    val active = !paused && !reduceMotion
    val breathe = if (active) rememberInfiniteTransition(label = "breathe").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    ).value else 1f
    val scale = if (active) 0.8f + 0.38f * breathe else 1f
    val alpha = if (active) 0.28f + 0.72f * breathe else 1f
    Box(
        Modifier.padding(top = 5.dp).size(9.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .background(if (paused) c.warnSolid else c.accent, CircleShape),
    )
}

/**
 * 结束后过程收成一行:「做了 N 步 · 用时 · 1.2 万 Token」(点了在下面展开步骤)和「查看过程」(进时间线)。
 * 没做步骤的(只聊了几句)只写 token;[tokens] 为 null(还没读到小结、服务商没报用量)时不写。
 */
@Composable
// ---------- 状态与结果 ----------

@StringRes
fun takeoverReasonLabel(reason: String): Int? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    "decision" -> R.string.takeover_reason_decision
    else -> null
}

/** 一问一答此刻的状态:要你做的事是一张白卡([NeedCard]),结果直接排在背景上。 */
@Composable
private fun StatusCard(
    status: ExchangeStatus, stage: Takeover.Stage, vm: ChatViewModel, runId: String?,
    elapsedMs: Long?, tokens: Long?,
    loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    openRun: (String) -> Unit,
    /** 回答的长按菜单认这一条用的(见 [MessageArea])。 */
    key: String,
    /** 回答里的 /workspace 引用卡片点开要走这个对话的工作区。 */
    conversationId: String?,
    recover: (Boolean) -> Unit = {},
    goal: String, openReport: (String) -> Unit, openPlayback: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareRequest by remember { mutableStateOf<ShareRequest?>(null) }
    ShareCardHost(shareRequest, onDone = { shareRequest = null })
    when (status) {
        ExchangeStatus.Working -> Unit
        ExchangeStatus.WaitingUnlock -> NeedCard(stringResource(R.string.chat_wait_unlock), stringResource(R.string.chat_unlock_body))
        is ExchangeStatus.NeedsTakeover -> {
            val label = takeoverReasonLabel(status.reason)
            val title = if (label != null) stringResource(R.string.takeover_title_reason, stringResource(label)) else stringResource(R.string.takeover_title)
            val handedOver = stage == Takeover.Stage.HandedOver
            val text = if (handedOver) stringResource(R.string.takeover_handed_over_hint) else status.message
            // 付款的商品、金额、地址单独成行:这张卡片就是付款前的确认
            val body = listOfNotNull(text, StatusCopy.paymentLines(status.item, status.amount, status.address)).joinToString("\n")
            NeedCard(title, body, footer = { TextLink(stringResource(R.string.action_cancel_task), vm::cancel) }) {
                if (handedOver) {
                    CardButton(stringResource(R.string.action_finished_myself), onClick = vm::finishedMyself)
                    CardButton(stringResource(R.string.action_hand_back), primary = true, onClick = vm::handBack)
                } else {
                    CardButton(stringResource(R.string.action_skip), onClick = vm::skip)
                    CardButton(stringResource(R.string.action_take_over), primary = true, onClick = vm::takeOver)
                }
            }
        }
        is ExchangeStatus.NeedsInput -> {
            val options = status.options.orEmpty()
            val note = stringResource(R.string.no_answer_note, TaskService.INPUT_TIMEOUT_MINUTES)
            NeedCard(
                stringResource(R.string.input_title), stringResource(R.string.chat_input_body, status.prompt, note),
                options = if (options.isEmpty()) null else ({
                    options.forEach { o -> MikaButton(o, { vm.answer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
                }),
            ) {
                if (options.isEmpty()) CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            }
        }
        // 只有花钱的操作要你同意,每次只同意这一次
        is ExchangeStatus.NeedsConfirmation -> NeedCard(status.title, status.body + "\n" + ConfirmationCopy.NOTE) {
            CardButton(stringResource(R.string.chat_refuse), onClick = { vm.decline(status.key) })
            CardButton(stringResource(R.string.chat_allow_once), primary = true, onClick = { vm.approve(status.key) })
        }
        is ExchangeStatus.Paused -> NeedCard(stringResource(if (status.resumable) R.string.chat_paused else R.string.chat_waiting_for_you), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            if (status.resumable) CardButton(stringResource(R.string.chat_continue), primary = true, onClick = vm::resume)
        }
        // 排队等开始:轮到时自动开始,点继续不能插队,只给取消
        is ExchangeStatus.Queued -> NeedCard(stringResource(R.string.chat_queued), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
        }
        // 长按整块回答弹菜单;复制、引用的只是回答的字,不带上面的提示和下面的来源
        is ExchangeStatus.Done -> Box(Modifier.padding(bottom = 12.dp)) {
            AnswerArea(key, status.answer, Modifier.fillMaxWidth().answerSurface()) {
                Column {
                    TurnMeta(elapsedMs, tokens)
                    status.unverifiedLine?.let { line ->
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(MikaTheme.colors.warnSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Exclaim, null, Modifier.size(11.dp), tint = MikaTheme.colors.warn)
                            }
                            Text(line, style = MikaType.Caption, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ink2)
                        }
                    }
                    MarkdownText(status.answer, AnswerText.copy(color = MikaTheme.colors.ink))
                    OutputRefs(status.answer, conversationId) { item ->
                        val id = conversationId ?: return@OutputRefs
                        scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                    }
                    ReplyActions(runId, status.answer, goal, vm, { shareRequest = ShareRequest(goal, status.answer) }, openRun, loadSource, openSource, status.evidence, openReport, openPlayback)
                }
            }
        }
        is ExchangeStatus.Failed -> Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(MikaTheme.colors.bad, CircleShape))
                Text(stringResource(R.string.chat_not_done), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
            }
            MarkdownText(status.reason, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.fillMaxWidth().padding(top = 6.dp).answerSurface())
            RecoveryActions(runId, recover)
            ReplyActions(runId, null, goal, vm, {}, openRun, loadSource, openSource, null, openReport, openPlayback)
        }
        // 停止后补上的交代当作这一轮的回答,排在「已停止」下面;还在写时一行小字
        is ExchangeStatus.Cancelled -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(stringResource(R.string.chat_stopped), Modifier.padding(start = 2.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
            if (status.summarizing) Text(
                StatusCopy.STOP_SUMMARIZING, Modifier.padding(start = 2.dp, top = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3,
            )
            status.summary?.let { summary ->
                Box(Modifier.padding(top = 8.dp)) {
                    AnswerArea(key, summary, Modifier.fillMaxWidth().answerSurface()) {
                        Column {
                            MarkdownText(summary, AnswerText.copy(color = MikaTheme.colors.ink))
                            OutputRefs(summary, conversationId) { item ->
                                val id = conversationId ?: return@OutputRefs
                                scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
            }
            RecoveryActions(runId, recover)
            ReplyActions(runId, null, goal, vm, {}, openRun, loadSource, openSource, null, openReport, openPlayback)
        }
    }
}

/** 操作区里的「朗读/停止」图标;没有配置声音模型时不显示。 */
@Composable
private fun ReadAloudIcon(answer: String, vm: ChatViewModel) {
    val ready by vm.canSpeak.collectAsState()
    if (!ready) return
    val playback by vm.voice.collectAsState()
    val playing = playback is VoicePlayback.Playing || playback is VoicePlayback.Loading
    val c = MikaTheme.colors
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { if (playing) vm.stopSpeaking() else vm.speak(answer) }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(MikaIcons.Sound, null, Modifier.size(14.dp), tint = if (playing) c.accent else c.ink3)
            Text(
                stringResource(if (playing) R.string.chat_stop_speaking else R.string.chat_read_aloud),
                style = MikaType.Caption, color = if (playing) c.accent else c.ink3,
            )
        }
        (playback as? VoicePlayback.Error)?.let {
            Text(it.message, Modifier.padding(start = 8.dp), style = MikaType.Caption, color = c.bad)
        }
    }
}

/** 这一轮生成的音频:名字/稿子开头、时长、播放/停止;点整张卡播放,再点停止。 */
@Composable
private fun GeneratedAudioCards(audios: List<com.wangzan101.mika.core.chat.GeneratedAudio>, vm: ChatViewModel) {
    val playback by vm.voice.collectAsState()
    val playing = playback is VoicePlayback.Playing || playback is VoicePlayback.Loading
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        audios.forEach { a ->
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(role = Role.Button) { if (playing) vm.stopSpeaking() else vm.playAudio(a.path) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Sound, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        a.text?.lineSequence()?.firstOrNull()?.take(80)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_audio),
                        style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            a.durationMs?.let { secondsLabel(it) },
                            stringResource(if (playing) R.string.chat_audio_stop else R.string.chat_audio_play),
                        ).joinToString(" · "),
                        style = FileMeta, color = if (playing) c.accent else c.ink3, maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun secondsLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** 终态后旧现场已经释放:两个按钮都开新运行,区别只是是否沿用摘要里的已确认进度。 */
@Composable
private fun RecoveryActions(runId: String?, recover: (Boolean) -> Unit) {
    if (runId == null) return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardButton(stringResource(R.string.chat_restart_task), onClick = { recover(false) })
        CardButton(stringResource(R.string.chat_resume_checkpoint), primary = true, onClick = { recover(true) })
    }
}

/** 回答正文下方的操作区:一条淡分隔线 + 一排纯图标按钮(朗读/复制/分享 | 查看过程/来源/任务报告/操作回放)。 */
@Composable
private fun ReplyActions(
    runId: String?,
    answer: String?,
    goal: String,
    vm: ChatViewModel,
    onShare: () -> Unit,
    openRun: (String) -> Unit,
    loadSource: suspend (String) -> EvidenceSource?,
    openSource: (String?, String?) -> Unit,
    evidence: String?,
    openReport: (String) -> Unit,
    openPlayback: (String) -> Unit,
) {
    if (runId == null && answer == null) return
    val source by produceState<EvidenceSource?>(null, runId) { value = if (runId == null) null else loadSource(runId) }
    val hasSource = evidence != null || !source?.items.isNullOrEmpty() || !source?.screenshots.isNullOrEmpty()
    val c = MikaTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (answer != null) {
                ReadAloudIcon(answer, vm)
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Copy, stringResource(R.string.chat_copy)) { copyAnswer(context, answer) }
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Share, stringResource(R.string.share)) { onShare() }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(0.5.dp).height(18.dp).background(c.hair))
                Spacer(Modifier.width(8.dp))
            }
            if (runId != null) {
                ReplyIcon(MikaIcons.Clock, stringResource(R.string.chat_view_process)) { openRun(runId) }
                if (hasSource) {
                    Spacer(Modifier.width(4.dp))
                    ReplyIcon(MikaIcons.Link, sourceLabel(source)) { openSource(runId, evidence) }
                }
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Doc, stringResource(R.string.report_title)) { openReport(runId) }
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Play, stringResource(R.string.playback_title)) { openPlayback(runId) }
            }
        }
    }
}

/** 操作区里的一枚纯图标按钮:30dp 触达区、17dp 图标,[label] 只作无障碍描述。 */
@Composable
private fun ReplyIcon(icon: ImageVector, label: String, tint: Color = MikaTheme.colors.ink2, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(MikaShapes.Tile).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(17.dp), tint = tint)
    }
}

/** 复制回答正文的纯文字到剪贴板。 */
private fun copyAnswer(context: Context, answer: String) {
    val clip = ClipData.newPlainText("Mika", MarkdownModel.toPlainText(answer))
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
}

/**
 * 「需要你」卡片(要你处理、提问、确认、暂停、锁屏、被拦下):白卡,标题前一个橙色圆点,下面说明;
 * [options] 是一列可点的答案;按钮并排,次要的在左(浅色)、主要的在右(蓝色);[footer] 在最下面。
 */
@Composable
private fun NeedCard(
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warnSolid, CircleShape))
                Text(title, style = NeedTitle, color = c.ink)
            }
            Text(body, Modifier.padding(top = 6.dp), style = CardBody, color = c.ink2)
            if (options != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
            }
            if (buttons != null) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
            footer?.let { Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
        }
    }
}

@Composable
private fun RowScope.CardButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.weight(1f),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Medium,
    )
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(MikaShapes.Pill).clickable(onClick = onClick).padding(4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}

/** 导出到「下载/Mika」的文件:小卡片,名字、大小和存放处;点了能看的在 App 里打开([FileViewer]),其余交给系统里能打开它的 App。 */
@Composable
private fun FileCards(files: List<SharedFile>) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button) { openShared(context, f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Doc, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ChatViewModel.sizeLabel(f.size)} · ${f.savedAs.substringBeforeLast('/')}",
                        style = FileMeta, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = c.ink3)
            }
        }
    }
}

private fun openShared(context: Context, f: SharedFile) = FileViewer.open(context, Uri.parse(f.uri), f.name)

// ---------- 输入栏 ----------

/** 「＋」面板:关着、开着、正让给键盘(点了输入框:键盘升起来时面板跟着矮下去,键盘到位再关)。 */
private enum class Panel { Closed, Open, Yielding }

/**
 * 玻璃胶囊输入栏:左边「＋」,右边发送(没内容时灰);任务在跑时多一个停止,补充消息先排在输入栏上方。
 * 「＋」点开的附件面板([attachPanel])在输入栏下面、占键盘那块位置,和键盘互换:开面板收键盘,
 * 再点「＋」(这时是 ×)或点输入框就收起面板、换成键盘;返回先收面板。待发的附件在输入栏上方一排([PendingTray])。
 */
@Composable
internal fun Composer(
    modifier: Modifier,
    text: String,
    onText: (String) -> Unit,
    mode: ComposerMode,
    hasHistory: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 这个对话明确排队、还没开始的下一件。 */
    queued: List<com.wangzan101.mika.task.QueuedFollowUp> = emptyList(),
    onGuideQueued: (String) -> Unit = {},
    onQueueActions: (com.wangzan101.mika.task.QueuedFollowUp) -> Unit = {},
    onCancelQueued: (String) -> Unit = {},
    onEditQueued: (String) -> Unit = {},
    onStartQueued: () -> Unit = {},
    /** 沙箱就绪时就有「＋」;点开是这块面板。 */
    attachPanel: (@Composable () -> Unit)? = null,
    /** 附件已启用但此刻不能加:「＋」还在,点了只给提示。 */
    attachBlocked: Boolean = false,
    /** 「＋」面板里的「教 Mika」。点了先收起面板。 */
    onTeach: (() -> Unit)? = null,
    attachments: List<PendingAttachment> = emptyList(),
    importing: Boolean = false,
    rejected: List<AttachmentFailure> = emptyList(),
    overLimit: Int = 0,
    onRemove: (String) -> Unit = {},
    onDismissRejected: () -> Unit = {},
    /** 要随下一句话发出去的引用;有时输入框上方一张小条,× 去掉。 */
    quote: QuoteMessage.Quote? = null,
    onRemoveQuote: () -> Unit = {},
    editRequest: Int = 0,
    /** 输入栏后面那层内容的画面;为 null 时输入栏不做模糊、用不透明底。 */
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = false,
    /** 按住说话从这里开会话;null 时输入框只能打字。 */
    voice: VoiceSource? = null,
    /** 说完的字;调用方把它接在已有的字后面,不自动发送。 */
    onVoiceText: (String) -> Unit = {},
    /** 松开在「在听」卡片里(「松开 发送」)说完的字:调用方接上已有的字直接发出。没给时没有「松开 发送」。 */
    onVoiceSend: ((String) -> Unit)? = null,
    /** 「还不能语音输入」里的「去选」:到设置里选转文字。 */
    onPickSpeech: () -> Unit = {},
    /** 任务在跑时说的话交给谁:卡片顶上写「插话给『…』」。 */
    steerTarget: String? = null,
    /** 按着说话时为 true:对话内容跟着虚化。 */
    onVoiceHolding: (Boolean) -> Unit = {},
    sendFromIme: Boolean = false,
    draftKey: String? = null,
) {
    val c = MikaTheme.colors
    var fieldFocused by remember { mutableStateOf(false) }
    // 底部从页面底色淡出到透明,输入栏是浮在上面的玻璃
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.25f to c.bg, 1f to c.bg) }
    val busy = mode == ComposerMode.Busy
    val context = LocalContext.current
    // 附件功能开着就保留「＋」;此刻不能加时只提示,不展开面板
    val showAttach = attachPanel != null || attachBlocked
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    var panel by rememberSaveable { mutableStateOf(Panel.Closed) }
    // 「松开 发送」和发送键一样先收起附件面板
    val vh = androidx.compose.runtime.key(draftKey) { rememberVoiceHold(voice, onVoiceText, onVoiceSend?.let { send -> { text -> panel = Panel.Closed; send(text) } }) }
    val vState = voiceState(vh)
    val voiceHolding = vh?.holding == true
    LaunchedEffect(voiceHolding) { onVoiceHolding(voiceHolding) }
    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            panel = Panel.Closed
            field.requestFocus()
            keyboard?.show()
        }
    }
    // 面板和键盘一样高:记下见过的键盘高度(在导航栏之上的那段),没见过时用默认高度
    var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ime, nav, density) {
        snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.collect { if (it > keyboardPx) keyboardPx = it }
    }
    // 输入框聚焦时长按留给系统的选字、粘贴(悬浮、分体、实体键盘也一样);没聚焦时输入框上盖一层按住说话的手势
    val voiceGesture = vh != null && (!fieldFocused || voiceHolding)
    // 键盘从弹着到收起(返回键、键盘自己的收起键):输入框跟着失焦,又能按住说话。只看键盘的变化,实体键盘(从不弹出)不受影响
    LaunchedEffect(ime, density, vh != null) {
        if (vh == null) return@LaunchedEffect
        var wasUp = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { up ->
            if (wasUp && !up && fieldFocused) focus.clearFocus()
            wasUp = up
        }
    }
    // 输入框外面的字一变(说完填进来、点了示例),光标放到末尾,接着说或接着打都在后面
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shownValue = if (fieldValue.text == text) fieldValue else TextFieldValue(text, TextRange(text.length))
    val panelPx = with(density) {
        if (keyboardPx > 0) keyboardPx.coerceIn(PanelMin.roundToPx(), PanelMax.roundToPx()) else PanelDefault.roundToPx()
    }
    LaunchedEffect(attachPanel == null) { if (attachPanel == null) panel = Panel.Closed }
    BackHandler(enabled = panel == Panel.Open) { panel = Panel.Closed }
    // 让给键盘:等键盘升到差不多面板高(没有键盘弹出来时最多等一会儿)再关
    LaunchedEffect(panel, panelPx) {
        if (panel != Panel.Yielding) return@LaunchedEffect
        withTimeoutOrNull(YIELD_MS) {
            snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.first { it >= panelPx * 9 / 10 }
        }
        panel = Panel.Closed
    }
    val togglePanel: () -> Unit = {
        if (panel == Panel.Open) {
            panel = Panel.Yielding
            field.requestFocus()
            keyboard?.show()
        } else {
            focus.clearFocus()
            keyboard?.hide()
            panel = Panel.Open
        }
    }
    val turn by animateFloatAsState(if (panel == Panel.Open) 45f else 0f, if (reduceMotion) snap() else tween(200), label = "plus")
    Box(
        modifier.fillMaxWidth()
            .pageBackground(c.bg, fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            FollowUpTray(
                queued, active = mode != ComposerMode.Idle, canGuide = mode == ComposerMode.Busy, onGuide = onGuideQueued,
                onEdit = onEditQueued, onCancel = onCancelQueued, onStartNow = onStartQueued, onActions = onQueueActions,
            )
            quote?.let { QuoteChip(it, onRemoveQuote, Modifier.padding(bottom = 6.dp)) }
            if (attachments.isNotEmpty()) PendingTray(attachments, onRemove, Modifier.padding(bottom = 2.dp))
            if (importing || rejected.isNotEmpty() || overLimit > 0) {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (importing) Text(AttachCopy.IMPORTING, style = MikaType.Caption, color = c.ink3)
                    if (rejected.isNotEmpty() || overLimit > 0) {
                        val why = listOfNotNull(
                            rejected.takeIf { it.isNotEmpty() }?.map { failure ->
                                val reason = when (failure.reason) {
                                    AttachmentFailureReason.TOO_LARGE -> R.string.chat_attach_too_large
                                    AttachmentFailureReason.LOW_DEVICE_SPACE -> R.string.chat_attach_low_device_space
                                    AttachmentFailureReason.WORKSPACE_LIMIT -> R.string.chat_attach_workspace_limit
                                    AttachmentFailureReason.IO_ERROR -> R.string.chat_attach_io_error
                                }
                                stringResource(reason, failure.name)
                            }?.joinToString("\n"),
                            overLimit.takeIf { it > 0 }?.let(AttachCopy::overLimit),
                        ).joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(why, Modifier.weight(1f), style = MikaType.Caption, color = c.warn)
                            TextLink(stringResource(R.string.ok_got_it), onDismissRejected)
                        }
                    }
                }
            }
            if (vh != null) {
                val title = if (busy && !steerTarget.isNullOrBlank()) stringResource(R.string.voice_steer_to, steerTarget)
                else stringResource(R.string.voice_listening)
                VoiceFloat(vh, vState, title, backdrop, reduceMotion)
            }
            Box(Modifier.fillMaxWidth()) {
            // 实底卡片:半透明玻璃在两行身后衬的底不一致会显出两个色,输入栏不走高斯模糊
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard()) {
                val canSend = AttachTray.canSend(text, attachments, importing)
                // 两行:第一行纯输入,第二行按钮;字再多按钮排布也不动
                Column(Modifier.weight(1f)) {
                    // start 14dp:文字左缘对齐＋图标可视左缘(40dp 按钮内 20dp 图标居中,字形自身再内缩约 4dp)
                    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp)) {
                        val converting = vState is HoldToTalk.State.Converting
                        if (text.isEmpty() && !converting) {
                            if (vh != null) VoicePlaceholder(mode)
                            else Text(ChatCopy.placeholder(mode, hasHistory), style = MikaType.Body, color = c.ink3, maxLines = 1)
                        }
                        BasicTextField(
                            shownValue,
                            { v -> fieldValue = v; if (v.text != text) onText(v.text) },
                            Modifier.fillMaxWidth().testTag("composer-field").focusRequester(field)
                                .alpha(if (converting) 0f else 1f)
                                // 面板开着时点输入框:面板让给键盘
                                .onFocusChanged {
                                    fieldFocused = it.isFocused
                                    if (it.isFocused && panel == Panel.Open) panel = Panel.Yielding
                                },
                            textStyle = MikaType.Body.copy(color = c.ink, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(c.accent),
                            maxLines = 5,
                            keyboardOptions = if (sendFromIme) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                            keyboardActions = if (sendFromIme) KeyboardActions(onSend = {
                                if (AttachTray.canSend(text, attachments, importing)) onSend()
                            }) else KeyboardActions.Default,
                        )
                        // 服务商松开后在转:输入框里转圈,转好的字填进来
                        if (converting) VoiceConverting(Modifier.align(Alignment.CenterStart))
                        if (voiceGesture && vh != null) {
                            Box(Modifier.matchParentSize().holdToTalk(vh) { field.requestFocus(); keyboard?.show() })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (showAttach) {
                            val label = if (panel == Panel.Open) AttachCopy.CLOSE else AttachCopy.ADD
                            val blockedHint = stringResource(R.string.attach_blocked_running)
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(enabled = !importing, onClickLabel = label, onClick = {
                                        if (attachBlocked) {
                                            Toast.makeText(context, blockedHint, Toast.LENGTH_SHORT).show()
                                        } else togglePanel()
                                    }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Plus, label, Modifier.size(20.dp).graphicsLayer { rotationZ = turn }, tint = c.ink)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 话筒常驻:按住才听,点一下提示按住(引擎不可用则直接弹说明页);键盘出来以后长按输入框留给系统选字
                        if (vh != null) {
                            val hint = stringResource(R.string.voice_hold_to_talk)
                            Box(
                                Modifier.size(MikaSize.IconButton).testTag("composer-mic").holdToTalk(vh) { if (vh.engineUsable) vh.toast = hint else vh.explainUnavailable() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Mic, hint, Modifier.size(MikaSize.Icon), tint = c.ink)
                            }
                        }
                        // 任务在跑时没字就停止;写了字停止让位给发送,发出去的话交给当前任务
                        if (busy && !canSend) {
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(onClickLabel = stringResource(R.string.chat_stop), onClick = onStop),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(26.dp), tint = c.ink)
                            }
                        }
                        if (!busy || canSend) {
                            // 可发时黑底白箭头(主操作);空输入灰底灰箭头——发送是主行动,空态也有圆底和＋/话筒拉开层级,但灰箭头表明还不可用。全用 token,深色主题自动反转
                            Box(
                                Modifier.size(MikaSize.IconButton)
                                    .clip(CircleShape)
                                    .background(if (canSend) c.ink else c.neutralSoft)
                                    .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { panel = Panel.Closed; onSend() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(20.dp), tint = if (canSend) c.bg else c.ink3)
                            }
                        }
                    }
                }
            }
            if (vh != null) VoiceBarCover(vh, vState, reduceMotion, Modifier.matchParentSize())
            }
            if (vh != null) VoiceSheetHost(vh, onPickSpeech)
            if (panel != Panel.Closed && attachPanel != null) {
                // 键盘还在(刚开面板时正在收、让给键盘时正在升)的那一段从面板里扣掉:输入栏不跟着上下跳
                Box(
                    Modifier.fillMaxWidth().clipToBounds().layout { m, cons ->
                        val keyboardNow = (ime.getBottom(this) - nav.getBottom(this)).coerceAtLeast(0)
                        val shown = (panelPx - keyboardNow).coerceAtLeast(0)
                        val p = m.measure(Constraints.fixed(cons.maxWidth, panelPx))
                        layout(cons.maxWidth, shown) { p.place(0, 0) }
                    },
                ) {
                    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                        if (onTeach != null && panel == Panel.Open) {
                            Text(
                                stringResource(R.string.teach_menu),
                                Modifier.fillMaxWidth().clickable { panel = Panel.Closed; onTeach() }
                                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("teach-mika"),
                                color = c.accent, style = MikaType.Body,
                            )
                        }
                        Box(Modifier.weight(1f)) { attachPanel() }
                    }
                }
            }
        }
    }
}

/** 面板高度:没见过键盘时的默认值,以及跟着键盘时的上下限。 */
private val PanelDefault = 320.dp
private val PanelMin = 280.dp
private val PanelMax = 420.dp
/** 让给键盘时最多等这么久(毫秒)。 */
private const val YIELD_MS = 700L

/** 跳转目标行高亮停留多久(毫秒)。 */
private const val JUMP_HIGHLIGHT_MS = 1_800L

/** 竖线的位置(从左边算起);步骤点、转圈的中心都在这条线上。 */
private val StepRail = 8.dp

private val FileTileShape = MikaShapes.Tile
private val EmptyLead = MikaType.Value
private val ExampleText = MikaType.Value
private val SuggestionText = MikaType.Body.copy(fontWeight = FontWeight.Medium)
private val BubbleText = MikaType.Body
/** 回答:正文字号,行距放宽到 1.7。 */
private val AnswerText = MikaType.Body
private val NowText = MikaType.Value
private val TurnNameText = MikaType.Label.copy(fontWeight = FontWeight.SemiBold)
private val ClockText = MikaType.Hint.copy(fontFeatureSettings = "tnum")
private val StepText = MikaType.Caption
private val StepCurrentText = MikaType.Caption.copy(fontWeight = FontWeight.Medium)
/** 插话收成的小字:14sp,比正文淡一点点。 */
private val SupplementText = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
/** 干活中插话的右侧胶囊:比用户气泡小一圈,右下尾角。 */
private val SteerPillShape = RoundedCornerShape(14.dp, 14.dp, 5.dp, 14.dp)
private val NoteText = MikaType.Hint
private val DoneText = MikaType.Caption
private val NeedTitle = MikaType.Value.copy(fontWeight = FontWeight.SemiBold)
private val CardBody = MikaType.Body
private val PillText = MikaType.Caption
private val ChipText = MikaType.Hint.copy(fontWeight = FontWeight.Medium)
private val FileName = MikaType.Body.copy(fontWeight = FontWeight.Medium)
private val FileMeta = MikaType.Hint
