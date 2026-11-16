package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import android.graphics.Rect
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import java.util.UUID
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.OverlayDrag
import com.wangzan101.mika.cap.phone.a11y.OverlayHold
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.DeviceStateWatcher
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.takeover.TakeoverQueue
import com.wangzan101.mika.ui.ChatCopy
import com.wangzan101.mika.ui.FollowUpMode
import com.wangzan101.mika.ui.VoiceCopy
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.screenThumbnail
import com.wangzan101.mika.ui.ToolLabels
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 悬浮胶囊的接线:把各任务的状态([TaskCenterState.runs] 和每个 [TaskRun] 的状态、接管阶段、活动)、开关、
 * Mika 界面在不在前台([AppForeground])、屏幕亮没亮([DeviceStateWatcher])、无障碍服务在不在交给 [CapsuleModel],
 * 按它的结果经 [com.wangzan101.mika.cap.phone.a11y.OverlayHost] 加上、挪动或拆掉浮层窗口。
 * 点一颗或胶囊展开卡片([CapsuleCards] 定内容,只说一件事),点卡片外面、收起按钮或返回收回成一颗(看过的不再展开,模型记着);卡片和胶囊上的按钮都转成任务服务现有的动作。
 * 一颗和卡片各在一个窗口里、各待各的位置(见 [window]):展开时一颗那个窗口显式隐藏,卡片在自己的窗口里从那一颗的方位缩放、淡入,
 * 收起时缩回去,一颗再在边上冒出来([OverlayMotion]:只动图层,不挪窗口,也不逐帧改窗口大小)。
 * 双击一颗或胶囊(见 [DoubleTap]:单击要等双击的时限过去才展开,两下还要同来源、落点相近)把用户此刻的屏幕截进那张卡片的输入框预览,
 * 不直接发出;用户看过预览、按发送后才和在卡片里发一句话走同一条路。卡片输入框左边的截屏按钮也挂在输入框上面,可以补一句再发。
 * 截的时候浮层先藏起来,截完再出来,一颗上闪一下。
 * 卡片底部的输入框点开时浮层才能接收键盘,发出、收起、点外面、按返回时马上还回去;输入法弹出时胶囊和卡片挪到它上面。
 * 长按一颗或胶囊说话(见 [CapsuleTalk]、[CapsuleVoicePanel]):按着时整屏压暗、胶囊旁边浮出面板和「取消」「改一改」两个落点,
 * 松开直接发给单击会展开的那张卡片(和双击发屏幕同一套找目标),拖到「改一改」打开卡片、字在输入框里,拖到「取消」丢掉;
 * 发出、没听清、用不了时胶囊上闪一句([note])。面板在一颗那个窗口下面的另一个窗口里,不接触摸。
 * 卡片展开时才收那件事的画面(每秒至多 [PREVIEW_FPS] 张,没变不推)。平时是一颗图标,有事要说时展开成胶囊(模型定);
 * 可以拖,松手贴到最近的左边或右边;停的位置按边和高度比例记在 [FeatureFlags],转屏后按比例重新摆。
 * 卡片左右居中(对话卡片固定高,任务卡片按内容高、最多一样高),按着标题栏上下拖,上边的比例另记一份,换卡片时上边不动;
 * 对话卡片的来回从 [ConversationStore] 读(和对话页同一份),
 * 操作过的 App 从那一轮的运行记录([RunStore])里读。
 * 浮层画不出来(服务刚断开、系统不让)时这个服务实例上不再重试,胶囊不出现,通知照旧。全部在主线程。
 */
class CapsuleController(
    private val context: Context,
    private val tasks: TaskCenterState,
    private val foreground: AppForeground,
    /** 几个任务共用的接管队列:卡片和那一条上的先后从这里读。 */
    private val takeoverQueue: TakeoverQueue,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = CapsuleModel(::reasonLabel)
    private val flags = FeatureFlags.get(context)
    /** 任务结束后留在后台运行的那一页(没有任务时胶囊说它,卡片里能看画面、关掉)。 */
    private val keeper get() = (context.applicationContext as MikaApp).pageKeeper
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()
    /** 各任务这一问一答此刻的样子,和对话页同一套推法(步骤、要你做的事、结果)。 */
    private val exchanges = HashMap<String, Exchange>()

    private var view by mutableStateOf<CapsuleView.Shown?>(null)
    private var card by mutableStateOf<CardView?>(null)
    /** 同进程的排队队列;卡片按自己的对话数明确排队的项。 */
    private var followUps by mutableStateOf<List<com.wangzan101.mika.task.QueuedFollowUp>>(emptyList())
    private var preview by mutableStateOf<Bitmap?>(null)
    private var zoomed by mutableStateOf(false)
    private var dock by mutableStateOf(flags.capsuleDock())
    private var edgeHidden by mutableStateOf(false)
    private var inputUi by mutableStateOf(CardInputUi())
    /** 截到一屏时加一:一颗上闪一下。 */
    private var flash by mutableIntStateOf(0)
    private val taps = DoubleTap(
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    private data class Touch(val at: Long, val x: Float, val y: Float, val source: Int)
    /** 最近一次胶囊窗口的 ACTION_DOWN;Compose 的 onClick 不带触摸信息,在这里补上。 */
    private var capsuleTouch: Touch? = null
    private var tapJob: Job? = null

    /** 任何不是普通第二下的交互都从这里打断待定点击,避免跨操作拼出双击。 */
    private fun resetTaps() {
        tapJob?.cancel()
        taps.reset()
        capsuleTouch = null
    }

    /** 正在截屏(浮层藏着):这时的点按、再截都不算。 */
    private var capturing = false
    /** 在卡片里发了话的那个对话:展开时跟着它的任务,好看到回答。 */
    private var follow: String? = null
    /** 空闲卡片接着的最近那段对话(点开空闲图标时读一次)。 */
    private var idle: CardView.Chat? = null
    /** 卡片标题栏的电源按钮点过:卡片里换成「完全退出」的确认,不马上退。 */
    private var quitConfirm by mutableStateOf(false)

    /** 长按说话;[talkIdle] 是空闲图标上开始说时去读的最近那段对话(说完发给它)。 */
    private val talk = CapsuleTalk(context, { (context.applicationContext as MikaApp).voice }, scope, ::spoken)
    private var talkIdle: Deferred<CardView.Chat>? = null
    /** 胶囊上临时闪的一句(已发出、没听清、用不了);到时自己消失。 */
    private var note by mutableStateOf<CapsuleNote?>(null)
    private var noteJob: Job? = null
    private var noteCount = 0

    /**
     * 对话卡片的来回:[threadOf] 那个对话记下的全部轮次([past],后台从 [ConversationStore] 读,和对话页同一份;还没读到为 null),
     * 排最后 [shownTurns] 轮,接上还没记进去的任务;[thread] 是此刻给界面的。[threadMark] 是上次读时这个对话里有哪些任务、结束没有,
     * 变了(任务结束、拿掉,那一轮这时才记进对话)就重读。[placesOf] 按运行记录编号记着那一轮操作过的 App 和网站。
     */
    private var thread by mutableStateOf<ChatThread?>(null)
    private var threadOf: String? = null
    private var past: List<PastTurn>? = null
    /** 这个对话起好的标题(没有时卡片用第一句话)。 */
    private var pastTitle: String? = null
    private var shownTurns = CapsuleThreads.PAGE
    private var threadMark: Any? = null
    private var threadJob: Job? = null
    private val placesOf = HashMap<String, List<String>>()
    private val runStore by lazy { RunStore(context) }

    /** 卡片看得见的最高高度(像素,按屏幕和输入法定);0 是还没摆过。[cardFraction] 是卡片上边的比例,记在 [FeatureFlags]。 */
    private var cardHeight by mutableIntStateOf(0)
    private var cardFraction = flags.capsuleCardFraction()
    /** 卡片标题栏在窗口里的位置:只有按着它能拖卡片。 */
    private var cardHandle: Rect? = null
    /** 用户屏幕上输入法的上边;没弹出为 null。胶囊和卡片都让开它。 */
    private var imeTop: Int? = null
    /**
     * 这一轮输入法弹出期间摆卡片用的上边:键盘变高时马上跟,变矮时忽略候选行、工具栏那种一行的高度
     * (见 [CapsuleDocking.tallestImeTop])。中文输入法打拼音时输入法多一行少一行,卡片不跟着反复变高矮、上下挪。
     * 输入法收起时清掉,下次弹出重新记。
     */
    private var cardImeTop: Int? = null
    private var imeJob: Job? = null

    /** 胶囊此刻的透明度(不碰它几秒后淡下去);[lastActive] 是上次碰到或状态变了的时刻。 */
    private var alpha by mutableStateOf(1f)
    private var lastActive = 0L
    private var look: Any? = null
    private var fadeJob: Job? = null

    /** 卡片展开着;[closing] 是正在缩回胶囊那边(动效做完才真的收起)。 */
    private var expanded = false
    private var closing by mutableStateOf(false)
    /** 卡片真的开始出来了:这时一颗才让开(卡片排版、出来前一颗还在原处,不空着);卡片开始往回缩时一颗就回来。 */
    private var cardOut by mutableStateOf(false)
    /** 展开前那一颗的中心(屏幕纵坐标)和卡片窗口的上边:卡片从那一颗的方位长出来、缩回去。 */
    private var anchorY = 0
    private var cardTop by mutableIntStateOf(0)
    private var previewKey: String? = null
    private var previewJob: Job? = null

    /** 「你来处理」那一条的高度比例(只在这次运行里记着)。 */
    private var barFraction = 0f
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var placedY = 0
    /** 卡片窗口的上边。 */
    private var cardY = 0
    private var snap: ValueAnimator? = null

    /**
     * 两个浮层窗口,各自待在自己的位置上,展开、收起时谁都不挪:[window] 放一颗、胶囊和那一条,停在边上;[cardWindow] 放卡片,
     * 左右居中(收起时显式设为不可见,里面随后空出来,不挡下面的 App)。一个窗口在一颗和卡片的位置间来回挪,系统会把挪动做成滑过去的动画
     * (安卓原版的窗口移动动画),看上去是一颗从中间滑回边上、卡片从边上滑到中间。
     */
    private var window: OverlayWindow? = null
    private var cardWindow: OverlayWindow? = null
    /** 长按说话时的面板,在一颗那个窗口下面(压暗不盖住胶囊);平时空着没有大小。加不上时不能长按说话。 */
    private var panelWindow: OverlayWindow? = null
    /** 窗口加在哪个服务实例上;服务换了,旧窗口已随旧实例拆掉。 */
    private var windowOn: ScreenAccessibility? = null
    /** 在这个服务实例上加窗口失败过:不再试,等服务重连。 */
    private var failedOn: ScreenAccessibility? = null
    private var tick: Job? = null
    private val ticks = MutableStateFlow(0)
    private var last: Snapshot? = null

    private val _showing = MutableStateFlow(false)
    /** 胶囊此刻真的显示在亮着的屏幕上;任务服务据此把「需要你」通知发得安静些。 */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        val screen = DeviceStateWatcher(context) { -1 }
        // 用户点了「完全退出」:按开关关着算,直到用户重新打开 Mika([resume] 清掉标记后再亮出来)
        val mode = combine(flags.capsuleModeChanges(), flags.quitChanges()) { m, quit -> if (quit) CapsuleMode.Off else m }
        val fullscreen = combine(flags.capsuleHideFullscreenChanges(), ScreenAccessibility.instance, mode) { enabled, service, m ->
            service.takeIf { enabled && m != CapsuleMode.Off }
        }.flatMapLatest { service -> service?.overlays()?.fullscreenChanges() ?: flowOf(false) }
        scope.launch {
            combine(
                combine(
                    runs(),
                    combine(
                        mode,
                        flags.capsuleIdleOpacityChanges(),
                        flags.capsuleHideAtEdgeChanges(),
                        flags.capsuleCardScaleChanges(),
                    ) { m, opacity, hideAtEdge, cardScale -> RenderSettings(m, opacity, hideAtEdge, cardScale) },
                    foreground.visible, screen.locked, ScreenAccessibility.instance,
                ) { r, settings, f, l, a ->
                    Snapshot(r, settings.mode, f, l, a,
                        opacity = settings.opacity, hideAtEdge = settings.hideAtEdge, cardScale = settings.cardScale)
                }.combine(fullscreen) { s, full -> s.copy(fullscreen = full) },
                ticks,
                keeper.page,
                // 主动能力判为值得提醒、还没看的条数(只要个数)
                com.wangzan101.mika.proactive.NoticeHintList.unseenCount(context),
            ) { s, _, kept, notices -> s.copy(kept = kept, notices = notices) }.collect(::render)
        }
        // 说话面板出来时铺满整屏(压暗背后),收起时显式收掉(见 [syncPanelWindow])
        scope.launch { snapshotFlow { talk.active }.collect { syncPanelWindow() } }
        // 排队条:卡片按自己的对话显示还有几件
        scope.launch { (context.applicationContext as MikaApp).followUpQueue.changes.collect { followUps = it } }
        // 停止后的交代写好(或写不成)时那一轮已改进对话:重读记下的轮次
        scope.launch { tasks.summarizing.collect { threadOf?.let(::loadPast) } }
        // 对话删掉了:见 [forget]
        scope.launch { ConversationStore.shared(context).deletions.collect(::forget) }
        // 卡片上这个对话的标题变了(用户在侧栏改了名、后台起好了标题):卡片顶上「接着「…」聊」跟着换
        scope.launch {
            val store = ConversationStore.shared(context)
            store.changes.collect { id ->
                if (id != threadOf) return@collect
                val title = withContext(Dispatchers.IO) { store.title(id) }
                if (id == threadOf && title != pastTitle) {
                    pastTitle = title
                    rebuildThread()
                }
            }
        }
        // 转屏:按记下的边和比例重新摆;系统给新尺寸可能稍晚一点,过一会儿再摆一次
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                resetTaps()
                // 屏幕尺寸换了,上一轮记的输入法位置不再作数;等输入法窗口的下一次变化重新记
                cardImeTop = null
                place()
                scope.launch {
                    delay(ROTATE_SETTLE_MS)
                    place()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    /** 用户点了「完全退出」:马上拆掉浮层。进程可能被无障碍服务重新拉起,届时按退出标记不再画(见 [start])。 */
    fun stopNow() {
        quitConfirm = false
        hide()
    }

    /** 用户又打开了 Mika:退出标记已清掉,按此刻的状态重新画(进程没被杀、又回到前台时用得上)。 */
    fun resume() {
        quitConfirm = false
        refresh()
    }

    private data class RenderSettings(
        val mode: CapsuleMode,
        val opacity: CapsuleIdleOpacity,
        val hideAtEdge: Boolean,
        val cardScale: CapsuleCardScale,
    )

    private data class Snapshot(
        val runs: List<CapsuleRun>, val mode: CapsuleMode, val mikaInForeground: Boolean, val locked: Boolean,
        val service: ScreenAccessibility?,
        /** 任务结束后留在后台运行的那一页。 */
        val kept: KeptPage? = null,
        val opacity: CapsuleIdleOpacity = CapsuleIdleOpacity.DEFAULT,
        val notices: Int = 0,
        /** 展开卡片的缩放档位,改了当场生效(见 [CardOverlay])。 */
        val cardScale: CapsuleCardScale = CapsuleCardScale.DEFAULT,
        val hideAtEdge: Boolean = false,
        val fullscreen: Boolean = false,
    )

    /**
     * 各任务此刻的样子。任务收尾后会从 [TaskCenterState.runs] 里拿掉;拿掉时它的状态已是终态,
     * 还带着它(到任务列表再变为止),模型才能记下「办好了」并停一会儿,停完以后再列着它模型也不算。
     * 没到终态就被拿掉的(服务被销毁)直接不算。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runs(): Flow<List<CapsuleRun>> {
        var previous = emptyList<TaskRun>()
        return tasks.runs.flatMapLatest { current ->
            val gone = previous.filter { r -> current.none { it === r } && !r.state.value.isActive() }
            previous = current
            val all = current + gone
            exchanges.keys.retainAll(all.map { it.key }.toSet())
            if (all.isEmpty()) flowOf(emptyList())
            else combine(all.map { r ->
                // 此刻在操作画面:有自己的虚拟屏,或正在用内置浏览器(卡片据此选任务卡片还是对话卡片)
                combine(r.state, r.takeoverStage, r.activities, r.displayId, r.webOpen) { s, st, acts, display, web ->
                    CapsuleRun(
                        r.key, r.conversationId, s, st, r.goal, exchange(r, s, acts),
                        runId = r.runId.value, onScreen = display >= 0 || web,
                    )
                }.combine(r.steers) { c, steers -> c.copy(steers = steers) }
            }) { it.toList() }.combine(takeoverQueue.changes) { list, _ ->
                // 接管队里的先后:排队变了(前面那件交还了)就重读,下一件马上成为「需要你」
                list.map { c ->
                    val owner = all.firstOrNull { it.key == c.key }?.takeover ?: return@map c
                    val ahead = takeoverQueue.ahead(owner)
                    if (ahead < 0) c else c.copy(queueAhead = ahead, queueBehind = takeoverQueue.behind(owner))
                }
            }
        }
    }

    private fun exchange(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchanges[r.key] ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchanges[r.key] = it }
    }

    private fun render(s: Snapshot) {
        last = s
        val service = s.service
        if (windowOn != null && windowOn !== service) {
            talk.abort()
            window = null
            cardWindow = null
            panelWindow = null
            windowOn = null
        }
        val inputs = CapsuleInputs(
            s.runs, s.mode, s.mikaInForeground, screenInteractive = !s.locked,
            serviceAvailable = service != null && service !== failedOn, cardOpen = expanded, kept = s.kept, fullscreen = s.fullscreen,
            notices = s.notices,
        )
        val shown = model.update(inputs, System.currentTimeMillis()) as? CapsuleView.Shown
        schedule(model.nextChangeAt)
        if (shown == null || service == null) {
            hide()
            return
        }
        // App 已交到你手上:顶部那一条代替卡片
        if (shown.kind == CapsuleKind.HandedOver) resetCard()
        view = shown
        card = if (expanded) CapsuleCards.of(model.cardRuns(), follow, idle, s.kept) else null
        if (expanded && card == null) resetCard()
        updateThread(card as? CardView.Chat, s.runs)
        // 换成了没有输入框的内容(例如变成要你同意):输入框收起,焦点还给下面的 App
        if (inputUi.open && card?.let(CapsuleCards::input) == null) closeInput()
        val detail = card as? CardView.Detail
        val keptCard = card as? CardView.Kept
        if (keptCard != null) watchKept(keptCard.page.id)
        else watchPreview(detail?.takeIf { CapsuleCards.showsScreen(it.content) }?.run?.run?.key)
        val w = window ?: openWindows(service)
        if (w == null) {
            view = null
            card = null
            _showing.value = false
            return
        }
        applyDrag()
        place()
        // 换了状态、件数,展开或缩回:马上恢复,重新计时;只换了一句不算(一颗上看不到那句)
        val nowLook = listOf(shown.kind, shown.pill, shown.count, shown.needCount)
        if (nowLook != look) {
            look = nowLook
            resetTaps()
            wake()
        } else fade()
        _showing.value = true
    }

    /** 加上两个窗口(先一颗的,再卡片的,卡片在上面);有一个加不上就都不要,退回通知。 */
    private fun openWindows(service: ScreenAccessibility): OverlayWindow? {
        val overlays = service.overlays()
        // 面板先加,在一颗下面:按着说话时压暗的那层不盖住胶囊
        val panel = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) { VoicePanelOverlay() }
        val ball = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT)) { CapsuleOverlay() }
        val cardW = ball?.let { overlays.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL), ::outside) { CardOverlay() } }
        if (ball == null || cardW == null) {
            ball?.close()
            panel?.close()
            failedOn = service
            Log.w(TAG, "capsule overlay unavailable, falling back to notifications")
            return null
        }
        window = ball
        cardWindow = cardW
        panelWindow = panel
        windowOn = service
        syncWindowVisibility()
        // 新加的面板窗口先藏起来:没在说话时它不该在屏幕上留任何东西
        syncPanelWindow()
        for (it in listOf(ball, cardW)) it.onResize(::place)
        ball.onTouch { e ->
            // Replacing the half-circle on ACTION_DOWN would turn the same touch into a full-ball click.
            if (!edgeHidden) wake()
            capsuleTouch = Touch(e.eventTime, e.rawX, e.rawY, e.source)
        }
        cardW.onTouch { wake() }
        cardW.onBack(::back)
        watchIme(service)
        return ball
    }

    private fun refresh() {
        last?.let(::render)
    }

    /**
     * 对话 [id] 删掉了:它刚结束的任务马上从胶囊和卡片上拿掉,卡片不再接着它(刚在卡片里发了话的;空闲卡片接着的,
     * 展开着时换成开新对话的那张),展开着的卡片按 [CapsuleCards.of] 重挑(什么都没有就收起)。删对话先停掉它的任务、
     * 等它收尾(最多 15 秒)再删记录,所以这时它的任务一般已经结束;等不到收尾的,或者收尾以后、删记录之前又在卡片里接着说的
     * (会在这个对话里开一件新任务),之后存进的那一轮会把对话重新建起来,胶囊照常显示它。
     */
    private fun forget(id: String) {
        model.forget(id)
        if (follow == id) follow = null
        if (idle?.conversationId == id) idle = if (expanded) CardView.Chat(null) else null
        refresh()
    }

    private fun hide() {
        resetTaps()
        snap?.cancel()
        fadeJob?.cancel()
        look = null
        imeJob?.cancel()
        imeJob = null
        imeTop = null
        cardImeTop = null
        dragging = false
        edgeHidden = false
        talk.abort()
        talkIdle = null
        noteJob?.cancel()
        note = null
        window?.close()
        window = null
        cardWindow?.close()
        cardWindow = null
        panelWindow?.close()
        panelWindow = null
        windowOn = null
        view = null
        resetCard()
        card = null
        _showing.value = false
    }

    private fun resetCard() {
        expanded = false
        closing = false
        cardOut = false
        syncWindowVisibility()
        follow = null
        zoomed = false
        quitConfirm = false
        cardHandle = null
        closeInput()
        inputUi = inputUi.collapsed()
        watchPreview(null)
        updateThread(null, emptyList())
    }

    /** 两个窗口交替时显式显隐;空内容只负责排版,不承担把系统窗口旧帧撤下屏幕的职责。 */
    private fun syncWindowVisibility() {
        val visible = capsuleWindowVisibility(expanded, cardOut, closing)
        window?.setVisible(visible.capsule)
        cardWindow?.setVisible(visible.card)
    }

    /**
     * 说话面板窗口:在说时铺满整屏(压暗背后)、才显出来;收起时先显式设为不可见再缩回没有大小。
     * 和一颗、卡片一样,不能拿空内容和零尺寸当作已经不可见:Compose 内容清空后可能不再出新帧,
     * 系统画布上会留着最后一帧「转文字…」,面板看上去就卡在屏幕上了。
     */
    private fun syncPanelWindow() {
        val w = panelWindow ?: return
        if (talk.active) {
            w.setFullScreen(true)
            w.setVisible(true)
        } else {
            w.setVisible(false)
            w.setFullScreen(false)
        }
    }

    // ---------- 输入 ----------

    /** 点了输入框:这时才让浮层能接收键盘,输入法对着它弹出。 */
    private fun openInput() {
        if (inputUi.open) return
        cardWindow?.setFocusable(true)
        inputUi = inputUi.copy(open = true)
    }

    /** 收起输入框,立刻把焦点还给下面的 App。 */
    private fun closeInput() {
        if (!inputUi.open) return
        inputUi = inputUi.copy(open = false)
        cardWindow?.setFocusable(false)
    }

    /** 返回键:先收起输入框,再关放大的画面,最后收起卡片。 */
    private fun back() {
        when {
            inputUi.open -> closeInput()
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    /** 放大或关掉画面;放大时卡片不能拖(没有标题栏)。 */
    private fun zoom(on: Boolean) {
        zoomed = on
        applyDrag()
    }

    /**
     * 发出输入框里的话,和在对话页里发的走同一条路(都记进那个对话):在跑、等条件时插话给它,
     * Mika 问你时作为回答,办完后在同一个对话里接着说,空闲时接着最近的对话或开新对话;后两种之后卡片跟着那个对话的任务看回答。
     */
    private fun send() {
        val app = context.applicationContext as? com.wangzan101.mika.MikaApp
        if (app != null && app.teachReady && app.teach.blocksTasks()) {
            android.widget.Toast.makeText(context, com.wangzan101.mika.i18n.tr(com.wangzan101.mika.R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val text = inputUi.draft.trim()
        val shot = inputUi.screen?.shot
        val c = card ?: return
        val kind = CapsuleCards.input(c)?.kind ?: return
        if (text.isEmpty() && shot == null) return
        val mode = inputUi.followUpMode
        inputUi = CardInputUi()
        cardWindow?.setFocusable(false)
        if (shot != null) deliver(c, kind, shot, text, mode) else submit(c, kind, text, CapsuleCards.conversationOf(c), mode)
        refresh()
    }

    /** 按输入框此刻交给谁([kind])发出 [text];开始说时进 [conversationId](为 null 时新开一个)。 */
    private fun submit(c: CardView, kind: CardInputKind, text: String, conversationId: String?, mode: FollowUpMode = FollowUpMode.Steer) {
        val run = when (c) {
            is CardView.Detail -> c.run.run
            is CardView.Chat -> c.run?.run
            is CardView.Kept -> null
        }
        when (kind) {
            CardInputKind.Steer -> run?.let {
                if (mode == FollowUpMode.Queue) {
                    TaskService.enqueueFollowUp(context, it.conversationId, UUID.randomUUID().toString(), text)
                } else {
                    TaskService.start(context, text, it.conversationId, messageId = UUID.randomUUID().toString())
                }
            }
            CardInputKind.Answer -> run?.let { TaskService.answer(context, text, it.key) }
            CardInputKind.Continue -> run?.let { startIn(it.conversationId, text) }
            CardInputKind.Start -> startIn(conversationId ?: ConversationStore.shared(context).newConversation(), text)
        }
    }

    /**
     * 截下的屏幕连同附言 [words] 发出:先存进这句话要去的那个对话(新对话这时就定下编号),
     * 再写成带屏幕的一句话([UserScreenMessage])照常发出;存不下就不发。
     */
    private fun deliver(c: CardView, kind: CardInputKind, shot: CapturedScreen, words: String, mode: FollowUpMode = FollowUpMode.Steer) {
        val access = (context.applicationContext as MikaApp).appAccess.current()
        if (shot.sourcePackages.isEmpty() || shot.sourcePackages.any { !access.canShare(it) }) {
            inputUi = inputUi.copy(screen = null)
            android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val conversationId = CapsuleCards.conversationOf(c) ?: ConversationStore.shared(context).newConversation()
        scope.launch {
            val saved = runCatching { (context.applicationContext as MikaApp).userScreens.save(conversationId, shot) }
                .onFailure { Log.w(TAG, "could not save the screen", it) }.getOrNull() ?: return@launch
            val latest = (context.applicationContext as MikaApp).appAccess.current()
            if (shot.sourcePackages.any { !latest.canShare(it) }) {
                inputUi = inputUi.copy(screen = null)
                android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            submit(c, kind, UserScreenMessage.compose(saved.id, words), conversationId, mode)
            refresh()
        }
    }

    /**
     * 双击或读屏的截屏操作:展开单击会打开的那张卡片,把此刻的屏幕挂到现有输入框的预览上。
     * 这里只截屏和填 [PendingScreen],不保存进对话、不提交;用户检查预览后按现有发送键,才由 [send] 调 [deliver]。
     * 那张卡片没有输入框(要你接管、同意)时没有地方预览,就和单击一样只展开。
     */
    private fun previewScreen() {
        val v = view ?: return
        if (capturing) return
        scope.launch {
            if (v.kind == CapsuleKind.Idle || v.kind == CapsuleKind.Notice) idle = loadIdle()
            val target = CapsuleCards.of(model.cardRuns(), follow, idle)
            val kind = target?.let(CapsuleCards::input)?.kind
            if (target == null || kind == null) {
                expand()
                return@launch
            }
            CapsuleCards.conversationOf(target)?.let { follow = it }
            open()
            refresh()
            val shot = captureScreen() ?: return@launch
            val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
            if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
        }
    }

    /** 卡片里的截屏按钮:截下来挂在输入框上面,补不补一句都行。 */
    private fun captureForCard() {
        if (capturing) return
        scope.launch {
            val shot = captureScreen() ?: return@launch
            val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
            if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
        }
    }

    /**
     * 截下用户此刻的屏幕:输入框开着先收起(等输入法下去),浮层藏起来、真的从屏幕上下去了再截,截完马上出来、一颗上闪一下;
     * 再在后台整理屏上的字。前台是 Mika 自己、截不到时为 null。
     */
    private suspend fun captureScreen(): CapturedScreen? {
        val service = windowOn ?: return null
        val w = window ?: return null
        val c = cardWindow ?: return null
        capturing = true
        try {
            if (inputUi.open) {
                closeInput()
                withTimeoutOrNull(IME_GONE_MS) { while (imeTop != null) delay(IME_POLL_MS) }
            }
            // 空着的那个窗口(展开时一颗那个、收起时卡片那个)不画东西,不用等它
            suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
            val store = (context.applicationContext as MikaApp).appAccess
            val sources = MainScreenCapture.sourcePackages(service) ?: return null
            if (context.packageName in sources || !store.current().available) return null
            for (pkg in sources) {
                if (!store.current().canShare(pkg) && !com.wangzan101.mika.cap.phone.a11y.requestScreenShare(service, store, pkg, appLabel(pkg))) return null
            }
            val capture = w.hidden { c.hidden {
                if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) null
                else MainScreenCapture.shoot(service)
            } } ?: return null
            val (shot, read) = capture
            if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) {
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return null
            }
            flash++
            wake()
            val area = service.overlays().area()
            val r = MainScreenCapture.build(service, shot, read, area.insetTop, area.insetBottom) { ConfirmationNotifier.appLabel(context, it) }
            if (sources.any { !store.current().canShare(it) } || MainScreenCapture.sourcePackages(service) != sources) return null
            val captured = (r as? MainScreenCapture.Result.Captured)?.shot ?: return null
            return CapturedScreen(captured.jpeg, captured.screen, sources)
        } finally {
            capturing = false
        }
    }

    /**
     * 在对话 [conversationId] 里开始说 [text];别的任务在跑时排上,不丢。卡片接着跟这个对话;
     * 空闲卡片也改成接着这个对话(新开的对话标题就是这句话)。
     */
    private fun startIn(conversationId: String, text: String) {
        TaskService.start(context, text, conversationId)
        follow = conversationId
        idle = idle?.let { CardView.Chat(conversationId, title = if (it.target == conversationId) it.title else ScreenCopy.plain(text)) }
    }

    /** 示范开始后收起卡片，免得盖住用户要指的地方。 */
    fun yieldToTeaching() {
        if (expanded) collapse()
    }

    /** 点开空闲图标:先读用户此刻在哪个对话([loadIdle]),再展开(来回由 [updateThread] 读)。 */
    private fun openIdle() {
        scope.launch {
            idle = loadIdle()
            open()
            refresh()
        }
    }

    /**
     * 空闲卡片接着的那段对话:用户在 App 里所在的那个,没有记录时接着最近那个(见 [CapsuleIdle]);一个都没有时开新对话。
     * 不按对话文件的时间猜:新开的对话还没有文件,猜到的是上一个。
     */
    private suspend fun loadIdle(): CardView.Chat = withContext(Dispatchers.IO) {
        runCatching {
            val store = ConversationStore.shared(context)
            val recent = store.list().firstOrNull()?.let { CardView.Chat(it.id, title = it.title?.let(ScreenCopy::plain)) }
            CapsuleIdle.chat(store.currentChat(), recent) { id ->
                // 和侧栏一样:用户起的名字或第一句话
                (store.title(id) ?: store.turns(id).firstOrNull()?.userText)?.let(ScreenCopy::plain)
            }
        }.getOrElse { CardView.Chat(null) }
    }

    // ---------- 对话卡片的来回 ----------

    /**
     * 对话卡片要的来回:换了对话从头读(先读最近 [CapsuleThreads.PAGE] 轮);这个对话里的任务出现、结束、拿掉时重读记下的轮次
     * (任务结束后那一轮才记进对话);其余时候只按此刻的任务重排(在回答的字跟着长)。不是对话卡片时清掉。
     */
    private fun updateThread(chat: CardView.Chat?, runs: List<CapsuleRun>) {
        val id = chat?.target
        if (id == null) {
            threadJob?.cancel()
            threadOf = null
            past = null
            threadMark = null
            shownTurns = CapsuleThreads.PAGE
            // 空闲卡片的新对话:来回是空的
            thread = if (chat != null) ChatThread(emptyList()) else null
            return
        }
        val mark = runs.filter { it.conversationId == id }.map { it.key to it.state.isActive() }
        if (id != threadOf) {
            threadOf = id
            past = null
            shownTurns = CapsuleThreads.PAGE
            thread = null
        }
        if (mark != threadMark) {
            threadMark = mark
            loadPast(id)
        }
        rebuildThread()
    }

    /** 按记下的轮次和卡片里此刻的任务排出来回;记下的还没读到时先空着。 */
    private fun rebuildThread() {
        val id = threadOf ?: return
        val p = past ?: return
        val live = CapsuleThreads.liveTail(model.cardRuns(), id, p.mapNotNull { it.turn.runId }.toSet())
        val built = CapsuleThreads.build(p, shownTurns, live, tasks.summarizing.value) { CapsuleThreads.places(it.run.exchange?.activities.orEmpty(), ::appLabel) }
        thread = pastTitle?.let { built.copy(title = it) } ?: built
    }

    /** 后台读对话 [id] 记下的轮次,连同要排出来的那几轮操作过的 App 和网站(从运行记录里读,读过的记着),读到后重排。 */
    private fun loadPast(id: String) {
        threadJob?.cancel()
        val known = HashMap(placesOf)
        val n = shownTurns
        threadJob = scope.launch {
            val (turns, found) = withContext(Dispatchers.IO) {
                val turns = runCatching { ConversationStore.shared(context).turns(id) }.getOrDefault(emptyList())
                turns to turns.takeLast(n).mapNotNull { it.runId }.filter { it !in known }.associateWith(::placesIn)
            }
            val title = withContext(Dispatchers.IO) { ConversationStore.shared(context).title(id) }
            placesOf += found
            if (threadOf != id) return@launch
            pastTitle = title
            past = turns.map { PastTurn(it, it.runId?.let(placesOf::get).orEmpty()) }
            rebuildThread()
        }
    }

    /** 那次运行操作过的 App 和网站;记录已清掉或读不了时为空(当作纯聊天)。在后台线程调。 */
    private fun placesIn(runId: String): List<String> = runCatching {
        CapsuleThreads.places(runStore.events(runId).filterIsInstance<RunEvent.Activity>()) { ConfirmationNotifier.appLabel(context, it) }
    }.getOrDefault(emptyList())

    /** 滚到了顶:往前多排 [CapsuleThreads.PAGE] 轮(先读出它们操作过的 App)。 */
    private fun loadEarlier() {
        val id = threadOf ?: return
        val p = past ?: return
        if (p.size <= shownTurns) return
        shownTurns += CapsuleThreads.PAGE
        loadPast(id)
    }

    /** 跟着用户屏幕上的输入法:弹出、收起、变高时重新摆。卡片只按这一轮最高的样子摆,打字时不跟着候选行反复变(见 [cardImeTop])。 */
    private fun watchIme(service: ScreenAccessibility) {
        imeJob?.cancel()
        imeJob = scope.launch {
            service.overlays().imeTop().collect {
                imeTop = it
                cardImeTop = CapsuleDocking.tallestImeTop(cardImeTop, it, dp(IME_SHRINK_JUMP_DP))
                place()
            }
        }
    }

    /** 到了模型说的时刻再算一次(换句、办完后缩小、消失)。 */
    private fun schedule(at: Long?) {
        tick?.cancel()
        tick = at?.let { t ->
            scope.launch {
                delay((t - System.currentTimeMillis()).coerceAtLeast(0L))
                ticks.value++
            }
        }
    }

    // ---------- 半透明 ----------

    /** 碰到了:马上恢复,从现在起重新计时。 */
    private fun wake() {
        lastActive = System.currentTimeMillis()
        fade()
    }

    private fun fade() {
        fadeJob?.cancel()
        val v = view ?: return
        val idleFor = System.currentTimeMillis() - lastActive
        val before = edgeHidden
        edgeHidden = CapsuleEdge.hidden(last?.hideAtEdge == true, v.kind, v.pill, v.needCount,
            expanded || capturing, dragging, talk.active, note != null, idleFor)
        alpha = if (expanded || dragging) 1f else CapsuleFade.alpha(v.kind, v.needCount, v.pill, idleFor, last?.opacity?.alpha ?: CapsuleIdleOpacity.DEFAULT.alpha)
        if (before != edgeHidden) {
            applyDrag()
            place()
        }
        if (!expanded && idleFor < CapsuleFade.IDLE_MS) fadeJob = scope.launch {
            delay(CapsuleFade.IDLE_MS - idleFor)
            fade()
        }
    }

    // ---------- 小画面 ----------

    /** 只给展开的那件事收画面;换了一件或收起就停。 */
    private fun watchPreview(key: String?) {
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        previewJob = null
        preview = null
        zoomed = false
        val run = key?.let(::runFor) ?: return
        previewJob = scope.launch {
            launch { run.frames.collect { if (it == null) preview = null } }
            run.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect {
                preview = it.takeIf { run.frames.value != null }
            }
        }
    }

    /** 留在后台运行的那一页的卡片:收它那块显示的画面(和任务卡片的小画面同一个节流)。 */
    private fun watchKept(id: Long) {
        val key = "kept:$id"
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        preview = null
        zoomed = false
        previewJob = scope.launch {
            keeper.frames().filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { preview = it }
        }
    }

    // ---------- 位置与拖动 ----------

    /** 能摆的范围;[ime] 为 true 时让开输入法([at],默认是此刻的输入法上边)。 */
    private fun area(ime: Boolean = true, at: Int? = imeTop): DockArea? = windowOn?.overlays()?.area()?.let {
        val room = room()
        DockArea.of(it, margin = dp(EDGE_MARGIN_DP), gap = dp(TOP_GAP_DP), room = room)
            .let { a -> if (ime) a.above(at, dp(TOP_GAP_DP), room) else a }
    }

    /** 卡片用的范围:让开这一轮输入法最高的样子;拼音候选行出来、收回去时卡片不跟着变高矮、上下挪。 */
    private fun cardArea(): DockArea? = area(at = cardImeTop)

    private fun room() = dp(CapsuleShadowRoom.value.toInt())

    private fun isBar() = view?.kind == CapsuleKind.HandedOver

    /**
     * 按记下的位置摆窗口:胶囊贴在记下的那一边,「你来处理」那一条在顶部正中,卡片左右居中、上边按记下的比例摆;
     * 放不下或输入法盖到时往上挪(卡片最高高度同时变矮)。卡片换了高度(换卡片、内容变了)窗口跟着变,再摆一次,上边不动。
     */
    private fun place() {
        val w = window ?: return
        if (dragging || snap?.isRunning == true) return
        val a = area() ?: return
        placeCard()
        val h = w.height.coerceAtLeast(dp(PILL_DP))
        if (isBar()) {
            placedY = CapsuleDocking.fitY(CapsuleDocking.y(barFraction, a, dp(BAR_DP)), a, h)
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, placedY))
            return
        }
        // 卡片展开着时这个窗口是空的,照样停在边上:收起时一颗就在原处出来
        val d = dock
        // 输入法盖到原来的位置:胶囊和卡片都挪到它上面,收起后回原处
        val base = area(ime = false) ?: a
        val natural = CapsuleDocking.fitY(CapsuleDocking.y(d.fraction, base, dp(PILL_DP)), base, h)
        placedY = CapsuleDocking.fitY(natural, a, h)
        val side = if (d.side == DockSide.Left) Gravity.LEFT else Gravity.RIGHT
        val offset = if (edgeHidden) CapsuleEdge.offset(d.side, a, room()) else CapsuleDocking.edgeOffset(d.side, a)
        w.place(OverlayPlacement(Gravity.TOP or side, offset, placedY))
    }

    /**
     * 摆卡片窗口:左右居中、上边按记下的比例。卡片最高高度按屏幕和输入法定,变了内容跟着重排,窗口变了尺寸再摆一次。
     * 输入法按这一轮最高的样子算([cardArea]):打字时拼音候选行多出来一行、收回去,卡片不跟着变高矮也不上下挪。
     * 刚展开、窗口还空着时按卡片会有的高度先摆好(对话卡片固定高;任务卡片按内容高,先按最矮的算),内容出来时不用再挪。
     */
    private fun placeCard() {
        val w = cardWindow ?: return
        val c = card ?: return
        val a = cardArea() ?: return
        windowOn?.overlays()?.area()?.let { cardHeight = CardDocking.maxHeight(it.height, a, room()) }
        val base = area(ime = false) ?: a
        val expected = if (c is CardView.Chat) cardHeight + 2 * room() else dp(PILL_DP)
        cardY = CardDocking.y(cardFraction, base, a, cardFull(base), maxOf(w.height, expected))
        cardTop = cardY
        w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cardY))
    }

    /** 最高的卡片(对话卡片)在没让输入法的范围 [base] 里的窗口高(含阴影边):卡片上边的比例按它算。 */
    private fun cardFull(base: DockArea): Int =
        CardDocking.maxHeight(windowOn?.overlays()?.area()?.height ?: 0, base, room()) + 2 * room()

    /**
     * 能拖哪里:胶囊和那一条整个能拖;卡片只有按着标题栏能拖(中间上下滑是滚动),放大画面时不能拖。
     * 一颗和胶囊还能长按说话(那一条不能;语音输入设成不用时也不能,长按照旧是点按)。
     */
    private fun applyDrag() {
        window?.draggable(drag)
        val talkable = canTalk()
        // 说着说着不能说了(例如这时 App 交到你手上、胶囊换成那一条):这一段丢掉,不发——用户没机会松开选落点
        if (!talkable && talk.active) talk.abort()
        window?.holdable(talkHold.takeIf { talkable }, CapsuleTalk.HOLD_MS)
        cardWindow?.draggable(cardDrag, cardHandle.takeIf { !zoomed } ?: Rect())
    }

    /** 卡片报来标题栏的位置。 */
    private fun onHandle(r: Rect) {
        if (r == cardHandle) return
        cardHandle = r
        applyDrag()
    }

    /** 按着标题栏上下拖卡片(左右居中不动);松手记下上边的比例,下次展开、换卡片和转屏后按它摆。 */
    private val cardDrag = object : OverlayDrag {
        override fun start() {
            dragging = true
            dragStartY = cardY
        }

        override fun move(dx: Float, dy: Float) {
            val w = cardWindow ?: return
            val a = cardArea() ?: return
            if (!dragging) return
            val base = area(ime = false) ?: a
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, CardDocking.dragY(dragStartY, dy, base, a, cardFull(base), w.height)))
        }

        override fun end(dx: Float, dy: Float) {
            val w = cardWindow
            val a = cardArea()
            val was = dragging
            dragging = false
            if (!was || w == null || a == null) return
            val base = area(ime = false) ?: a
            val full = cardFull(base)
            cardFraction = CardDocking.release(CardDocking.dragY(dragStartY, dy, base, a, full, w.height), base, full)
            flags.setCapsuleCardFraction(cardFraction)
            place()
        }
    }

    /** 拖胶囊(左右上下)或「你来处理」那一条(只上下);卡片见 [cardDrag]。 */
    private val drag = object : OverlayDrag {
        override fun start() {
            val w = window ?: return
            val a = area() ?: return
            resetTaps()
            snap?.cancel()
            val wasHidden = edgeHidden
            wake()
            dragging = true
            // The revealed ball is 40dp plus its shadow room; its layout resize arrives next frame.
            dragStartX = CapsuleDocking.dockedX(dock.side, a, if (wasHidden) dp(PILL_DP) else w.width)
            dragStartY = placedY
        }

        override fun move(dx: Float, dy: Float) {
            val w = window ?: return
            val a = area() ?: return
            if (!dragging) return
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, y))
            else w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width), y))
        }

        override fun end(dx: Float, dy: Float) {
            val w = window
            val a = area()
            if (!dragging || w == null || a == null) {
                dragging = false
                return
            }
            dragging = false
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) {
                barFraction = CapsuleDocking.fraction(y, a, dp(BAR_DP))
                place()
                return
            }
            val x = CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width)
            val base = area(ime = false) ?: a
            dock = CapsuleDocking.release(x, y, w.width, w.height, a, base)
            flags.setCapsuleDock(dock)
            wake()
            snapTo(x, y, CapsuleDocking.dockedX(dock.side, a, w.width))
        }
    }

    /** 松手后滑到边上;关了动画时直接摆过去。 */
    private fun snapTo(fromX: Int, y: Int, toX: Int) {
        val w = window ?: return
        placedY = y
        if (animationsOff(context) || fromX == toX) {
            place()
            return
        }
        snap = ValueAnimator.ofInt(fromX, toX).setDuration(SNAP_MS).apply {
            addUpdateListener { w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, it.animatedValue as Int, y)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snap = null
                    place()
                }
            })
            start()
        }
    }

    /** 读屏时代替拖动:换到另一边。 */
    private fun swapSide() {
        resetTaps()
        dock = dock.copy(side = if (dock.side == DockSide.Left) DockSide.Right else DockSide.Left)
        flags.setCapsuleDock(dock)
        place()
    }

    // ---------- 长按说话 ----------

    /** 此刻能不能在胶囊上说话:有面板窗口、不是「你来处理」那一条、语音输入没设成不用。 */
    private fun canTalk(): Boolean = panelWindow != null && !isBar() && !edgeHidden &&
        (context.applicationContext as MikaApp).speech.choice.value != SpeechChoice.Off

    /** 一颗那个窗口交来的长按:开始说、手指移动算落点、松开按落点收尾。 */
    private val talkHold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) = beginTalk(byTouch = true)

        override fun move(rawX: Float, rawY: Float) {
            if (talk.byTouch) talk.move(rawX, rawY, dp(TARGET_SLOP_DP))
        }

        override fun end(rawX: Float, rawY: Float) {
            if (!talk.byTouch) return
            talk.finish(CapsuleVoice.drop(rawX, rawY, talk.cancelAt, talk.editAt, dp(TARGET_SLOP_DP)))
        }

        override fun cancel() {
            if (talk.byTouch) talk.abort()
        }
    }

    /** 读屏的「说话」:没在说就开始(没有手指,只能发出),在说就发出。 */
    private fun toggleTalk() {
        if (talk.active && !talk.byTouch && !talk.released) talk.finish(CapsuleVoice.Drop.Send)
        else beginTalk(byTouch = false)
    }

    /**
     * 开始说:先等着的单击、双击不算了。说给谁按单击会展开的那张卡片(空闲图标上先去读最近那段对话,读到后面板顶上那句跟着换)。
     * 没有录音权限(胶囊弹不了权限框)、语音输入用不了时不录,胶囊上说一句,点一下打开 Mika。
     */
    private fun beginTalk(byTouch: Boolean) {
        val v = view ?: return
        // 松开后还在转时可以重新按:上一段由 talk.begin 丢掉
        if (capturing || (talk.active && !talk.released) || !canTalk()) return
        resetTaps()
        wake()
        clearNote()
        val loading = if (v.kind == CapsuleKind.Idle || v.kind == CapsuleKind.Notice) scope.async { loadIdle() } else null
        when (val b = talk.begin(CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, idle)), byTouch)) {
            CapsuleTalk.Begin.Started -> {
                talkIdle?.cancel()
                talkIdle = loading
                loading?.let {
                    scope.launch {
                        val chat = it.await()
                        if (talk.active && talkIdle === it) talk.title = CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, chat))
                    }
                }
            }
            CapsuleTalk.Begin.NoPermission -> {
                loading?.cancel()
                showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
            }
            is CapsuleTalk.Begin.Unavailable -> {
                loading?.cancel()
                showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
            }
        }
    }

    /** 说完了:按松开时的落点发出或打开卡片;没听清、出错时胶囊上说一句(说法和输入栏一样)。 */
    private fun spoken(drop: CapsuleVoice.Drop, state: HoldToTalk.State) {
        val idleJob = talkIdle
        talkIdle = null
        when (state) {
            is HoldToTalk.State.Done -> scope.launch {
                idleJob?.let { idle = it.await() }
                if (drop == CapsuleVoice.Drop.Edit) editSpoken(state.text) else sendSpoken(state.text)
            }
            is HoldToTalk.State.Problem -> {
                idleJob?.cancel()
                when (val why = state.why) {
                    null -> showNote(VoiceCopy.NO_MATCH, CapsuleKind.Failed, opensMika = false, NOTE_SHORT_MS)
                    SpeechFailure.NoPermission -> showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                    // 缺权限的是识别服务:胶囊上说不清,带到 Mika 里(在那里按住说话会有说明和去它设置页的按钮)
                    is SpeechFailure.ServiceNoPermission ->
                        showNote(tr(R.string.capsule_voice_service_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                    is SpeechFailure.Unavailable -> showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                    else -> showNote(VoiceCopy.failure(why), CapsuleKind.Failed, opensMika = false, NOTE_LONG_MS)
                }
            }
            else -> idleJob?.cancel()
        }
    }

    /**
     * 松开直接发出:发给单击会展开的那张卡片里的对话(和双击发屏幕同一套),胶囊上闪「已插话 / 已回答 / 已发出:…」。
     * 那张卡片没有输入框(要你接管、同意)时不发,展开卡片,字先放进输入框。
     */
    private fun sendSpoken(text: String) {
        val target = CapsuleCards.of(model.cardRuns(), follow, idle)
        val kind = target?.let(CapsuleCards::input)?.kind
        if (target == null || kind == null) {
            open()
            inputUi = inputUi.withSpoken(text)
            refresh()
            return
        }
        submit(target, kind, text, CapsuleCards.conversationOf(target), inputUi.followUpMode)
        showNote(CapsuleVoice.sent(kind, text), CapsuleKind.Done, opensMika = false, NOTE_SENT_MS)
        refresh()
    }

    /** 拖到「改一改」松开:展开卡片,字在输入框里、键盘弹出,改完自己发。 */
    private fun editSpoken(text: String) {
        open()
        inputUi = inputUi.withSpoken(text)
        refresh()
        if (card?.let(CapsuleCards::input) != null) openInput()
    }

    private fun showNote(text: String, kind: CapsuleKind, opensMika: Boolean, ms: Long) {
        resetTaps()
        note = CapsuleNote(text, kind, opensMika, ++noteCount)
        wake()
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(ms)
            note = null
            fade()
        }
    }

    private fun clearNote() {
        noteJob?.cancel()
        note = null
    }

    /** 胶囊上闪着一句时点它:那句说要打开 Mika 就打开,否则只收掉那句;这一下不参与单击或双击计时。 */
    private fun tapCapsule() {
        val n = note
        clearNote()
        if (n != null) {
            resetTaps()
            if (n.opensMika) open(null)
            return
        }
        val touch = capsuleTouch
        capsuleTouch = null
        val age = touch?.let { SystemClock.uptimeMillis() - it.at }
        if (touch == null || age == null || age !in 0..taps.timeoutMs) {
            // 读屏直接执行 onClick,没有 MotionEvent:它是一次明确的单击,不和之前摸过胶囊拼成双击
            resetTaps()
            expand()
            return
        }
        tap(touch)
    }

    // ---------- 点按与卡片 ----------

    /** 真实触摸点一颗或胶囊:两下要在系统时限内、同一位置附近且来源相同;单击仍等双击时限过去再展开。 */
    private fun tap(touch: Touch) {
        if (capturing) return
        when (taps.tap(touch.at, touch.x, touch.y, touch.source)) {
            DoubleTap.Tap.Double -> {
                tapJob?.cancel()
                previewScreen()
            }
            DoubleTap.Tap.First -> {
                tapJob?.cancel()
                tapJob = scope.launch {
                    delay(taps.timeoutMs)
                    if (taps.settle(SystemClock.uptimeMillis())) expand()
                }
            }
        }
    }

    /** 展开卡片(空闲图标是空闲卡片)。每次展开都按用户此刻在哪个对话重读一次空闲卡片,不拿上次那份。 */
    private fun expand() {
        val v = view ?: return
        // 值得提醒:单击把挑出来的几条通知写进今天的提醒对话(Mika 的一条消息,不调模型),那个对话成为当前对话;
        // 再和空闲时一样展开卡片,卡片接着的就是它。没写成(出了错)也照常展开。双击发屏幕、长按说话和空闲时一样
        if (v.kind == CapsuleKind.Notice) {
            scope.launch {
                com.wangzan101.mika.proactive.NoticeHintList.openTalk(context)
                idle = loadIdle()
                open()
                refresh()
            }
            return
        }
        if (v.kind == CapsuleKind.Idle) {
            openIdle()
            return
        }
        open()
        refresh()
        scope.launch {
            val fresh = loadIdle()
            if (fresh != idle) {
                idle = fresh
                refresh()
            }
        }
    }

    /** 展开卡片:先记下此刻那一颗的方位,卡片从那边长出来。 */
    private fun open() {
        if (!expanded) anchorY = placedY + (window?.height ?: 0) / 2
        expanded = true
        closing = false
        syncWindowVisibility()
    }

    /** 点在窗口外面:先关掉放大的画面,再收起卡片。 */
    private fun outside() {
        when {
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    private fun freshChat(fresh: Boolean) {
        idle = idle?.copy(fresh = fresh)
        refresh()
    }

    /** 收起卡片:输入框马上收起(焦点还给下面的 App),卡片缩回那一颗的方位后([closed])才真的收起;关了动画时直接收起。 */
    private fun collapse() {
        if (!expanded) return
        closeInput()
        if (closing) return
        if (animationsOff(context) || card == null) {
            resetCard()
            refresh()
            return
        }
        closing = true
        syncWindowVisibility()
    }

    private fun closed() {
        if (!closing) return
        resetCard()
        refresh()
    }

    /** 卡片缩放的原点:那一颗停靠的那一边,高度是那一颗的中心在卡片上的位置(超出卡片时取上下边)。 */
    private fun cardOrigin(size: Size): TransformOrigin {
        val y = if (size.height > 0f) ((anchorY - cardTop) / size.height).coerceIn(0f, 1f) else 0f
        return TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, y)
    }

    private fun runFor(key: String): TaskRun? = tasks.runs.value.firstOrNull { it.key == key }

    private val actions = object : CardActions {
        override fun collapse() = this@CapsuleController.collapse()
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) {
            // 和通知上的「我来处理」一样带着这次等待的编号:点在已经换掉的旧等待上不算数
            TaskService.takeOver(context, waitKey = runFor(runKey)?.takeover?.waitKey, runKey = runKey)
            collapse()
        }
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMika(conversationId: String) {
            open(conversationId)
            collapse()
        }
        override fun zoom(on: Boolean) = this@CapsuleController.zoom(on)
        override fun openInput() = this@CapsuleController.openInput()
        override fun draft(text: String) {
            inputUi = inputUi.copy(draft = text)
        }
        override fun send() = this@CapsuleController.send()
        override fun freshChat(fresh: Boolean) = this@CapsuleController.freshChat(fresh)
        override fun loadEarlier() = this@CapsuleController.loadEarlier()
        override fun captureScreen() = captureForCard()
        override fun dropScreen() {
            inputUi = inputUi.copy(screen = null)
        }
        override fun setFollowUpMode(mode: FollowUpMode) {
            inputUi = inputUi.copy(followUpMode = mode)
        }
        override fun askQuit() {
            closeInput()
            // 确认卡片没有自己的标题栏可拖,别拿上一张卡片的位置当把手
            cardHandle = null
            applyDrag()
            quitConfirm = true
        }
        override fun cancelQuit() {
            quitConfirm = false
        }
        override fun quit() {
            com.wangzan101.mika.quit.MikaQuit.quit(context)
        }
        override fun closeKeptPage() {
            keeper.close(KeptPageClose.User)
            collapse()
        }
        override fun teach() {
            val hint = inputUi.draft.trim().takeIf { it.isNotEmpty() }
            val intent = com.wangzan101.mika.task.TaskService.openAppIntent(context)
                .putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_OPEN_TEACH, true)
            if (hint != null) intent.putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_TEACH_GOAL, hint)
            runCatching { context.startActivity(intent) }
            collapse()
        }
        override fun hideCapsule() {
            // 和设置 › 悬浮胶囊里选「关闭」同一处:开关一变,render 按 CapsuleModel 收掉胶囊和卡片(hide),任务和通知不受影响
            flags.setCapsuleMode(CapsuleMode.Off)
            android.widget.Toast.makeText(context, CardCopy.hiddenHint(), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 卡片窗口里:展开时是卡片,收起时窗口先显式隐藏、内容随后清空。标题栏的电源按钮点过时换成退出的确认。
     * 非标准档时整张卡换 [Density.zoomed] 的密度渲染:宽、字、间距等比缩放;高度上限仍按真实屏幕高换算(在 Provider 里 toDp)。 */
    @Composable
    private fun CardOverlay() {
        val c = card ?: return
        MikaTheme(reduceMotion = animationsOff(context)) {
            val scale = last?.cardScale ?: CapsuleCardScale.DEFAULT
            CompositionLocalProvider(
                LocalDensity provides LocalDensity.current.zoomed(scale.factor),
                LocalCapsuleCardScale provides scale,
            ) {
                val height = if (cardHeight > 0) with(LocalDensity.current) { cardHeight.toDp() } else Dp.Unspecified
                OverlayMotion(open = !closing, origin = ::cardOrigin, onClosed = ::closed, onOpened = {
                    cardOut = true
                    syncWindowVisibility()
                }) {
                    if (quitConfirm) QuitCard(actions, height)
                    else CapsuleCard(
                        c, preview, zoomed, actions, { elapsed(it) }, input = inputUi, thread = thread, height = height, onHandle = ::onHandle,
                        queuedCount = CapsuleCards.conversationOf(c)?.let { id ->
                            followUps.count { it.conversationId == id && !it.claimed && it.source == com.wangzan101.mika.task.QueuedSource.Explicit }
                        } ?: 0,
                        onQueued = { CapsuleCards.conversationOf(c)?.let(::open) },
                    )
                }
            }
        }
    }

    /** 面板窗口里:按着说话时整屏的那一层,贴着一颗此刻的位置;平时什么都没有。 */
    @Composable
    private fun VoicePanelOverlay() {
        if (!talk.active) return
        val a = windowOn?.overlays()?.area()
        val top = placedY + room()
        val bottom = placedY + (window?.height ?: 0) - room()
        MikaTheme(reduceMotion = animationsOff(context)) {
            CapsuleVoicePanel(talk, top, bottom.coerceAtLeast(top), a?.insetTop ?: 0, a?.insetBottom ?: 0)
        }
    }

    /** 边上那个窗口里:一颗、胶囊或那一条;卡片出来后是空的,卡片往回缩时一颗同时从边上冒出来。胶囊上闪着一句时显示那句。 */
    @Composable
    private fun CapsuleOverlay() {
        val shown = view ?: return
        if (card != null && cardOut && !closing) return
        val n = note
        val v = if (n == null || shown.kind == CapsuleKind.HandedOver) shown
            else shown.copy(kind = n.kind, text = n.text, detail = null, count = 0, needCount = 0, pill = true, widthEpoch = -n.id)
        if (edgeHidden) {
            MikaTheme(reduceMotion = animationsOff(context)) {
                CapsuleEdgeContent(dock.side, alpha) {
                    resetTaps()
                    wake()
                    place()
                }
            }
            return
        }
        val talking = talk.active && !talk.released
        MikaTheme(reduceMotion = animationsOff(context)) {
            OverlayMotion(open = true, origin = { TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, 0.5f) }, onClosed = {}) {
                CapsuleContent(
                    v,
                    onTap = ::tapCapsule,
                    onSendScreen = ::previewScreen,
                    flash = flash,
                    onStop = { v.runKey?.let { TaskService.cancel(context, it) } },
                    onHandBack = { v.runKey?.let { TaskService.handBack(context, runKey = it) } },
                    side = dock.side,
                    tapLabel = when {
                        n?.opensMika == true -> tr(R.string.capsule_voice_open)
                        v.kind == CapsuleKind.Idle -> CapsuleCopy.TALK
                        v.kind == CapsuleKind.Notice -> tr(R.string.notice_hint_open)
                        else -> CapsuleCopy.EXPAND
                    },
                    onSwapSide = if (v.kind == CapsuleKind.HandedOver) null else ::swapSide,
                    alpha = alpha,
                    holding = talking && talk.byTouch,
                    onTalk = if (canTalk()) ::toggleTalk else null,
                    talkLabel = tr(if (talking && !talk.byTouch) R.string.capsule_voice_send else R.string.capsule_voice_talk),
                )
            }
        }
    }

    /** 用时,每秒更新;不显示用时时为 null。 */
    @Composable
    private fun elapsed(startedAt: Long?): String? {
        if (startedAt == null) return null
        val now by produceState(System.currentTimeMillis(), startedAt) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000L - value % 1_000L)
            }
        }
        return ChatCopy.clock(now - startedAt)
    }

    /** 回到 Mika 的那个对话(和点通知同一个入口)。 */
    private fun open(conversationId: String?) {
        runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
            .onFailure { Log.w(TAG, "could not open Mika from capsule", it) }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    private fun reasonLabel(reason: String): String? = when (reason) {
        "captcha" -> R.string.takeover_reason_captcha
        "login" -> R.string.takeover_reason_login
        "access_blocked" -> R.string.takeover_reason_access_blocked
        "payment" -> R.string.takeover_reason_payment
        // 「需要你决定」接在「需要你：」后面重复了,用模型写的话
        else -> null
    }?.let(context::getString)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "MikaCapsule"
        /** 看得见的胶囊离屏幕左右边、离状态栏和底部。 */
        const val EDGE_MARGIN_DP = 12
        const val TOP_GAP_DP = 8
        /** 胶囊、那一条连同阴影边的窗口高度(按比例摆高度时用)。 */
        const val PILL_DP = 40 + 16
        const val BAR_DP = 48 + 16
        const val SNAP_MS = 220L

        const val ROTATE_SETTLE_MS = 300L
        /**
         * 输入法一下矮了这么多(dp)才当作换了面板(表情、手写这类),卡片跟着还原;拼音候选行、工具栏
         * 多一行少一行远不到这个高度,不跟,免得打字时卡片一直变大小(见 [CapsuleDocking.tallestImeTop])。
         */
        const val IME_SHRINK_JUMP_DP = 120
        /** 说话时两个落点各自外扩多少算拖到了。 */
        const val TARGET_SLOP_DP = 14
        /** 胶囊上闪的那句停多久:发出了、没听清、出错或用不了。 */
        const val NOTE_SENT_MS = 1_500L
        const val NOTE_SHORT_MS = 2_000L
        const val NOTE_LONG_MS = 3_500L
        /** 截屏前收起卡片里的输入法,最多等它这么久下去。 */
        const val IME_GONE_MS = 700L
        const val IME_POLL_MS = 30L
    }
}

/** 一颗和卡片两个窗口此刻该不该显示;卡片收尾完成后无论动画停在哪一帧都不能再显示。 */
internal data class CapsuleWindowVisibility(val capsule: Boolean, val card: Boolean)

internal fun capsuleWindowVisibility(expanded: Boolean, cardOut: Boolean, closing: Boolean): CapsuleWindowVisibility =
    CapsuleWindowVisibility(capsule = !expanded || !cardOut || closing, card = expanded)

/** 胶囊上临时闪的一句:[kind] 定前面的标记(办好了是勾、没办成是叹号);[opensMika] 时点它打开 Mika;[id] 每句不同(胶囊按它重新定宽)。 */
private data class CapsuleNote(val text: String, val kind: CapsuleKind, val opensMika: Boolean, val id: Int)
