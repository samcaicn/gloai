package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import android.graphics.Rect
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.chat.ConversationStore
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.OverlayDrag
import com.wangzan101.mika.cap.phone.a11y.OverlayHold
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.DeviceStateWatcher
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.takeover.TakeoverQueue
import com.wangzan101.mika.chat.ui.FollowUpMode
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.Exchange
import com.wangzan101.mika.chat.ui.ExchangeStatus
import com.wangzan101.mika.ui.ToolLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
    internal val context: Context,
    internal val tasks: TaskCenterState,
    internal val foreground: AppForeground,
    /** 几个任务共用的接管队列:卡片和那一条上的先后从这里读。 */
    internal val takeoverQueue: TakeoverQueue,
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val model = CapsuleModel(::reasonLabel)
    internal val flags = FeatureFlags.get(context)
    /** 任务结束后留在后台运行的那一页(没有任务时胶囊说它,卡片里能看画面、关掉)。 */
    internal val keeper get() = (context.applicationContext as MikaApp).pageKeeper
    internal val toolLabels by lazy { ToolLabels(knownTools()) }
    internal val appLabels = HashMap<String, String>()
    /** 各任务这一问一答此刻的样子,和对话页同一套推法(步骤、要你做的事、结果)。 */
    internal val exchanges = HashMap<String, Exchange>()

    internal var view by mutableStateOf<CapsuleView.Shown?>(null)
    internal var card by mutableStateOf<CardView?>(null)
    /** 同进程的排队队列;卡片按自己的对话数明确排队的项。 */
    internal var followUps by mutableStateOf<List<com.wangzan101.mika.task.QueuedFollowUp>>(emptyList())
    internal var preview by mutableStateOf<Bitmap?>(null)
    internal var zoomed by mutableStateOf(false)
    internal var dock by mutableStateOf(flags.capsuleDock())
    internal var edgeHidden by mutableStateOf(false)
    internal var inputUi by mutableStateOf(CardInputUi())
    /** 截到一屏时加一:一颗上闪一下。 */
    internal var flash by mutableIntStateOf(0)
    internal val taps = DoubleTap(
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    internal data class Touch(val at: Long, val x: Float, val y: Float, val source: Int)
    /** 最近一次胶囊窗口的 ACTION_DOWN;Compose 的 onClick 不带触摸信息,在这里补上。 */
    internal var capsuleTouch: Touch? = null
    internal var tapJob: Job? = null

    /** 任何不是普通第二下的交互都从这里打断待定点击,避免跨操作拼出双击。 */
    internal fun resetTaps() {
        tapJob?.cancel()
        taps.reset()
        capsuleTouch = null
    }

    /** 正在截屏(浮层藏着):这时的点按、再截都不算。 */
    internal var capturing = false
    /** 在卡片里发了话的那个对话:展开时跟着它的任务,好看到回答。 */
    internal var follow: String? = null
    /** 空闲卡片接着的最近那段对话(点开空闲图标时读一次)。 */
    internal var idle: CardView.Chat? = null
    /** 卡片标题栏的电源按钮点过:卡片里换成「完全退出」的确认,不马上退。 */
    internal var quitConfirm by mutableStateOf(false)

    /** 长按说话;[talkIdle] 是空闲图标上开始说时去读的最近那段对话(说完发给它)。 */
    internal val talk = CapsuleTalk(context, { (context.applicationContext as MikaApp).voice }, scope, ::spoken)
    internal var talkIdle: Deferred<CardView.Chat>? = null
    /** 胶囊上临时闪的一句(已发出、没听清、用不了);到时自己消失。 */
    internal var note by mutableStateOf<CapsuleNote?>(null)
    internal var noteJob: Job? = null
    internal var noteCount = 0

    /**
     * 对话卡片的来回:[threadOf] 那个对话记下的全部轮次([past],后台从 [ConversationStore] 读,和对话页同一份;还没读到为 null),
     * 排最后 [shownTurns] 轮,接上还没记进去的任务;[thread] 是此刻给界面的。[threadMark] 是上次读时这个对话里有哪些任务、结束没有,
     * 变了(任务结束、拿掉,那一轮这时才记进对话)就重读。[placesOf] 按运行记录编号记着那一轮操作过的 App 和网站。
     */
    internal var thread by mutableStateOf<ChatThread?>(null)
    internal var threadOf: String? = null
    internal var past: List<PastTurn>? = null
    /** 这个对话起好的标题(没有时卡片用第一句话)。 */
    internal var pastTitle: String? = null
    internal var shownTurns = CapsuleThreads.PAGE
    internal var threadMark: Any? = null
    internal var threadJob: Job? = null
    internal val placesOf = HashMap<String, List<String>>()
    internal val runStore by lazy { RunStore(context) }

    /** 卡片看得见的最高高度(像素,按屏幕和输入法定);0 是还没摆过。[cardFraction] 是卡片上边的比例,记在 [FeatureFlags]。 */
    internal var cardHeight by mutableIntStateOf(0)
    internal var cardFraction = flags.capsuleCardFraction()
    /** 卡片标题栏在窗口里的位置:只有按着它能拖卡片。 */
    internal var cardHandle: Rect? = null
    /** 用户屏幕上输入法的上边;没弹出为 null。胶囊和卡片都让开它。 */
    internal var imeTop: Int? = null
    /**
     * 这一轮输入法弹出期间摆卡片用的上边:键盘变高时马上跟,变矮时忽略候选行、工具栏那种一行的高度
     * (见 [CapsuleDocking.tallestImeTop])。中文输入法打拼音时输入法多一行少一行,卡片不跟着反复变高矮、上下挪。
     * 输入法收起时清掉,下次弹出重新记。
     */
    internal var cardImeTop: Int? = null
    internal var imeJob: Job? = null

    /** 胶囊此刻的透明度(不碰它几秒后淡下去);[lastActive] 是上次碰到或状态变了的时刻。 */
    internal var alpha by mutableStateOf(1f)
    internal var lastActive = 0L
    internal var look: Any? = null
    internal var fadeJob: Job? = null

    /** 卡片展开着;[closing] 是正在缩回胶囊那边(动效做完才真的收起)。 */
    internal var expanded = false
    internal var closing by mutableStateOf(false)
    /** 卡片真的开始出来了:这时一颗才让开(卡片排版、出来前一颗还在原处,不空着);卡片开始往回缩时一颗就回来。 */
    internal var cardOut by mutableStateOf(false)
    /** 展开前那一颗的中心(屏幕纵坐标)和卡片窗口的上边:卡片从那一颗的方位长出来、缩回去。 */
    internal var anchorY = 0
    internal var cardTop by mutableIntStateOf(0)
    internal var previewKey: String? = null
    internal var previewJob: Job? = null

    /** 「你来处理」那一条的高度比例(只在这次运行里记着)。 */
    internal var barFraction = 0f
    internal var dragging = false
    internal var dragStartX = 0
    internal var dragStartY = 0
    internal var placedY = 0
    /** 卡片窗口的上边。 */
    internal var cardY = 0
    internal var snap: ValueAnimator? = null

    /**
     * 两个浮层窗口,各自待在自己的位置上,展开、收起时谁都不挪:[window] 放一颗、胶囊和那一条,停在边上;[cardWindow] 放卡片,
     * 左右居中(收起时显式设为不可见,里面随后空出来,不挡下面的 App)。一个窗口在一颗和卡片的位置间来回挪,系统会把挪动做成滑过去的动画
     * (安卓原版的窗口移动动画),看上去是一颗从中间滑回边上、卡片从边上滑到中间。
     */
    internal var window: OverlayWindow? = null
    internal var cardWindow: OverlayWindow? = null
    /** 长按说话时的面板,在一颗那个窗口下面(压暗不盖住胶囊);平时空着没有大小。加不上时不能长按说话。 */
    internal var panelWindow: OverlayWindow? = null
    /** 窗口加在哪个服务实例上;服务换了,旧窗口已随旧实例拆掉。 */
    internal var windowOn: ScreenAccessibility? = null
    /** 在这个服务实例上加窗口失败过:不再试,等服务重连。 */
    internal var failedOn: ScreenAccessibility? = null
    internal var tick: Job? = null
    internal val ticks = MutableStateFlow(0)
    internal var last: Snapshot? = null

    internal val _showing = MutableStateFlow(false)
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

    internal data class RenderSettings(
        val mode: CapsuleMode,
        val opacity: CapsuleIdleOpacity,
        val hideAtEdge: Boolean,
        val cardScale: CapsuleCardScale,
    )

    internal data class Snapshot(
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
    internal fun runs(): Flow<List<CapsuleRun>> {
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

    internal fun exchange(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchanges[r.key] ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchanges[r.key] = it }
    }

    /** 示范开始后收起卡片，免得盖住用户要指的地方。 */
    fun yieldToTeaching() {
        if (expanded) collapse()
    }

    /** 按着标题栏上下拖卡片(左右居中不动);松手记下上边的比例,下次展开、换卡片和转屏后按它摆。 */
    internal val cardDrag = object : OverlayDrag {
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
    internal val drag = object : OverlayDrag {
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

    /** 一颗那个窗口交来的长按:开始说、手指移动算落点、松开按落点收尾。 */
    internal val talkHold = object : OverlayHold {
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

    internal val actions = object : CardActions {
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
            com.wangzan101.mika.MikaQuit.quit(context)
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
        override fun voiceChat() {
            val intent = com.wangzan101.mika.task.TaskService.openAppIntent(context)
                .putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_OPEN_VOICE_CALL, true)
            runCatching { context.startActivity(intent) }
            collapse()
        }
        override fun hideCapsule() {
            // 和设置 › 悬浮胶囊里选「关闭」同一处:开关一变,render 按 CapsuleModel 收掉胶囊和卡片(hide),任务和通知不受影响
            flags.setCapsuleMode(CapsuleMode.Off)
            android.widget.Toast.makeText(context, CardCopy.hiddenHint(), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    internal fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    internal companion object {
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
internal data class CapsuleNote(val text: String, val kind: CapsuleKind, val opensMika: Boolean, val id: Int)
