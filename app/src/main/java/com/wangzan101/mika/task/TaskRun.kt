package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.uistate.PlacePace
import kotlin.random.Random
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.usage.DeepSeekPriceCache
import com.wangzan101.mika.core.usage.DeepSeekRunCost
import com.wangzan101.mika.core.usage.RunUsageTally
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.ui.StatusCopy
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.SharedFile
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import com.wangzan101.mika.cap.phone.setup.RootShell
import com.wangzan101.mika.cap.root.RootCommands
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.ModelRouter
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.TaskMemoryRecorder
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.ui.ToolLabels
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.child.ChildAgentRunner
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.takeover.HandoverActivityCounter
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverPort
import com.wangzan101.mika.takeover.stoppedAt
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import com.wangzan101.mika.core.knowledge.ChannelFacts
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.cap.web.KeptOrigin
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.browser.PageLoans
import com.wangzan101.mika.browser.UserBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.WEB

/**
 * 浮层恢复声音的路由:只有真正把 App 交到用户手上的接管才处理;接管对象从任务开跑起就一直存在,
 * 不能仅凭非空提前返回。等待接管、网页接管或没有接管目标时都落到普通手机音频路径。
 */
internal fun restorePipSound(takeover: Takeover?, restorePhone: () -> Unit) {
    if (takeover?.unmute() != true) restorePhone()
}

/** 浮层再静上的路由,条件与 [restorePipSound] 相同。 */
internal fun mutePipSound(takeover: Takeover?, mutePhone: () -> Unit) {
    if (takeover?.muteBack() != true) mutePhone()
}
private val deepSeekPrices by lazy { DeepSeekPriceCache(ProviderStore.modelHttp) }

/**
 * 一个任务的全部状态:虚拟屏、循环、关卡、接管、静音、监视。[TaskService] 只管服务生命周期、意图分发与通知。
 * 本任务自己的流只在它是「显示中」的任务([service.taskState.shownRun])时同步写到服务对外的流;
 * 别的任务(并行时)、或被新任务换下去的旧任务的写入只落在自己这份,不会盖掉显示中的那个。
 * [scope] 是服务的作用域:旁路协程挂在它下面,不随任务协程一起取消,由 [execute] 收尾时取消。
 * [slot] 决定它的通知编号与按钮请求码;[parallel] 为 true 时(开关 parallelTasks)打开 App 前查别的任务是否在用。
 */
class TaskRun(
    val conversationId: String,
    val goal: String,
    private val service: TaskService,
    private val scope: CoroutineScope,
    val slot: Int = 0,
    val parallel: Boolean = false,
) {
    internal var provenance = RunProvenance()
    internal var triggerWindowKey: String? = null
    internal var triggerActionGoal: String? = null
    /** 条件检查运行时用户写的条件原文;对话和通知显示它,不显示生成的检查目标 [goal]。 */
    internal var triggerCondition: String? = null
    internal var triggerThreadId: String? = null
    internal var mcpExternalId: String? = null
    internal var channelPeer: ChannelPeer? = null
    internal var channelMessageId: String? = null
    /** 主循环已经给出结局的时间；与结果实际发出之间的耗时用于定位渠道回传延迟。 */
    @Volatile internal var resultReadyAtMs = 0L
        private set
    internal val conditionCheck get() = triggerActionGoal != null
    @Volatile private var children: ChildAgentRunner? = null
    /** 这次任务连上的外部 MCP server;结束时关闭会话。 */
    @Volatile private var mcpConnections: com.wangzan101.mika.settings.McpConnections? = null

    /**
     * 这次任务接上的全部外部 MCP 端点,交给 toolDispatcher 的就这一份。别的来源(以后的扩展等)也提供这类端点时在这里拼上,
     * 不给 toolDispatcher 另加参数;各来源连上的都要归任务的 ResourceScope,收尾时一起关。
     */
    private fun mcpEndpoints(): List<com.wangzan101.mika.core.cap.mcp.McpConnection> = buildList {
        mcpConnections?.let { addAll(it.connections) }
    }
    /** 自己的一份;显示中时同步写到服务对外的那份。[show] 时整份抄过去。 */
    /** App 给的模型来源、评测钩子、屏幕画面的开关。 */
    private val env = TaskEnvironment.of(service)
    /** 这段对话对外的会话号:这次运行建的模型(主、轻量、看图,及核查、意图判断、停止交代等旁路)都用它。 */
    private val session = ModelSession.of(conversationId)
    /** App 给的手机服务(共用的 shell 连接、「去设置」提示)、记忆、经验路线、异常退出后的恢复。 */
    private val phoneServices = PhoneServices.of(service)
    private val memoryRuntime = MemoryRuntime.of(service)
    private val routeStore = FileRouteStore.shared(service)
    private val recovery = TaskRecovery.of(service)

    private inner class Mirrored<T>(initial: T, private val shown: MutableStateFlow<T>) {
        private val own = MutableStateFlow(initial)
        val flow: StateFlow<T> = own.asStateFlow()
        var value: T
            get() = own.value
            set(v) {
                synchronized(service.taskState.shownLock) {
                    own.value = v
                    if (service.taskState.shownRun === this@TaskRun) shown.value = v
                }
            }
        fun copyOut() { shown.value = own.value }
    }

    /** 静音等恢复记录按它分开,几个任务各记各的。 */
    val key: String = UUID.randomUUID().toString()
    private val inbox = RunInbox()

    private val _runId = Mirrored<String?>(null, service.taskState._runId)
    private val _sharedFiles = Mirrored<List<SharedFile>>(emptyList(), service.taskState._sharedFiles)
    private val _state = Mirrored<TaskState>(TaskState.Running(0, TaskService.PREPARING), service.taskState._state)
    /** 同一状态带上对话编号,和 [_state] 一起写:界面据此只收屏上那个对话的状态。 */
    private val _tagged = Mirrored<Pair<String, TaskState>?>(conversationId to _state.value, service.taskState._shownTask)
    private val _takeoverStage = Mirrored(Takeover.Stage.None, service.taskState._takeoverStage)
    private val _frames = Mirrored<Bitmap?>(null, service.taskState._frames)
    private val _phoneFrames = MutableStateFlow<Bitmap?>(null)
    private val _activities = Mirrored<List<RunEvent.Activity>>(emptyList(), service.taskState._activities)
    /** 活动只追加;发布的是前 n 条的只读视图,每来一条不复制已有的。 */
    private val activityLog = ArrayList<RunEvent.Activity>()
    private fun logView(n: Int): List<RunEvent.Activity> = object : AbstractList<RunEvent.Activity>() {
        override val size = n
        override fun get(index: Int): RunEvent.Activity = synchronized(activityLog) { activityLog[index] }
    }
    private val _steers = Mirrored<List<SteerNote>>(emptyList(), service.taskState._steers)
    private val _displayId = Mirrored(-1, env.agentDisplayId)
    /** 小窗直接操作的口子:屏建好才有,释放时清空。 */
    private val _pipInput = Mirrored<PipInput?>(null, service.taskState._pipInput)
    /** 小窗目标 App 的声音被用户恢复过(按钮转向「静音」);跟着真实音频记录走,手机侧清掉时回到 false。 */
    private val _pipSoundOn = Mirrored(false, service.taskState._pipSoundOn)
    /** 虚拟屏上打开过 App:画中画据此才出来(只建了空屏、没打开过 App 时没有画面)。 */
    private val _appShown = Mirrored(false, env.agentAppShown)
    /** 正在用内置浏览器:画中画改显示网页画面。 */
    private val _webOpen = Mirrored(false, env.webScreenOpen)

    val state: StateFlow<TaskState> = _state.flow
    val runId: StateFlow<String?> = _runId.flow
    /** 本任务导出的文件;结束时随这一轮存进对话。 */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.flow
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.flow
    val frames: StateFlow<Bitmap?> = _frames.flow
    /** 个人助手的缩略图只收本任务获准展示的手机画面。 */
    val phoneFrames: StateFlow<Bitmap?> = _phoneFrames.asStateFlow()
    /** 本任务到此刻的活动(实际做过的事);悬浮胶囊的卡片按任务取,不只看显示中的那个。 */
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.flow
    /** 本任务的虚拟屏编号;屏到用到系统命令、事件工具或第一次打开 App 时才建,之前和释放之后为 -1。 */
    val displayId: StateFlow<Int> = _displayId.flow
    /** 本任务小窗的直接操作口;屏建好才有,释放后回到 null。 */
    val pipInput: StateFlow<PipInput?> = _pipInput.flow
    /** 本任务小窗目标的声音是否被用户恢复过;界面据此把按钮显示成「恢复声音」或「静音」。 */
    val pipSoundOn: StateFlow<Boolean> = _pipSoundOn.flow
    /** 此刻在用内置浏览器(工具的界面是网页);没用、任务结束后为 false。 */
    val webOpen: StateFlow<Boolean> = _webOpen.flow
    /** 本任务进行中插的话和它们的去向;悬浮胶囊的对话卡片按任务取。 */
    val steers: StateFlow<List<SteerNote>> = _steers.flow
    // 带对话编号的那份先写:收不带编号的状态时读到的编号就已经是这个任务的(界面据此不把别的对话的状态接到屏上)
    private val mirrored = listOf(_runId, _sharedFiles, _tagged, _state, _takeoverStage, _frames, _displayId, _pipInput, _pipSoundOn, _appShown, _webOpen, _activities, _steers)

    private fun setState(s: TaskState) = synchronized(service.taskState.shownLock) {
        _tagged.value = conversationId to s
        _state.value = s
    }

    /** 让界面显示这个任务:成为显示中的任务,并把自己的各项抄到服务对外的流。 */
    fun show() = synchronized(service.taskState.shownLock) {
        service.taskState.shownRun = this
        mirrored.forEach { it.copyOut() }
    }

    /** 跑 [execute] 的协程,由服务启动后填上。 */
    @Volatile var job: Job? = null
        internal set(v) {
            field = v
            if (cancelRequested) v?.cancel()
        }
    @Volatile private var cancelRequested = false
    /** 用户点了停止:还没交给模型的插话一起作废,不当成下一件事。 */
    @Volatile private var stopPressed = false
    @Volatile private var loop: AgentLoop? = null
    val activeAsk: Delivery.Ask? get() = loop?.activeAsk
    @Volatile private var gate: TaskGate? = null
    @Volatile var takeover: Takeover? = null
        private set
    @Volatile var muteFailedFor: String? = null
        private set
    @Volatile private var awake: KeepAwake? = null
    /**
     * 收尾时还有任务排着:先替接班的拿一份屏幕锁再放开自己的,服务收尾时交给放出来的那个,它开跑拿上自己那份后放开;
     * 没人接班、或没跑起来就结束了,由服务放开。否则这里一放开,屏幕若已过了系统的熄屏时间就当场熄掉,
     * 接班的拿锁点不亮屏幕,一开跑就停在等解锁。重复放开不会多算(见 [SharedWakeLock])。
     */
    @Volatile internal var wakeHandoff: WakeLockPort? = null
    /** shell 进程没了,虚拟屏和上面的 App 一起没了;任务没法接着做。 */
    @Volatile var shellDied = false
        internal set
    /** 已接上的手机侧;浮层恢复声音据此找到当前目标 App,收尾时清空。 */
    @Volatile private var phone: PhoneSide? = null
    /** 发布与收尾互斥;[ended] 之后不再写状态,迟到的发布不能盖掉终态或重新拿锁。 */
    private val publishLock = Any()
    private var ended = false

    fun cancel() {
        stopPressed = true
        children?.cancelAll(RunId(key))
        val running = loop
        val t = takeover
        when {
            t != null -> scope.launch { t.cancel() }
            running != null -> inbox.cancel()
            else -> {
                // 刚从队里放出来、协程还没挂上时点的取消:挂上时立即取消
                cancelRequested = true
                job?.cancel()
            }
        }
    }

    fun resume() {
        val g = gate ?: return
        val wasOnPage = g.userOnPagePending
        g.resume()
        // 用户在页面上操作的那种暂停:循环可能还没走到关卡(还在想),按它此刻的状态重新显示,不再写着暂停
        if (wasOnPage) loop?.state?.value?.takeIf { it is TaskState.Running }?.let(::publish)
    }
    fun takeOver(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handOver(waitKey) } } }
    fun handBack(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handBack(waitKey) } } }
    fun finishedMyself(waitKey: Int?) { takeover?.let { t -> scope.launch { t.finishedMyself(waitKey) } } }
    fun skip(waitKey: Int?) { takeover?.let { t -> scope.launch { t.skip(waitKey) } } }
    fun putAside(waitKey: Int?) { takeover?.let { t -> scope.launch { t.putAside(waitKey) } } }

    /** 浮层上的声音开关:恢复过声音的再按一次静回去,没恢复的按一次恢复(重复点击重试真实动作);没有手机侧或目标 App 时不动。 */
    fun togglePipSound() {
        scope.launch(Dispatchers.IO) {
            val restored = pipSoundRestored()
            val targetPkg = phone?.screen?.target?.value?.packageName
            val handed = takeover?.handedOverPackage
            Log.i(TAG, "togglePipSound restored=$restored target=$targetPkg handed=$handed unmuted=${phone?.audio?.userUnmuted?.value}")
            if (restored) mutePipSound(takeover) {
                val p = phone ?: return@mutePipSound
                val target = p.screen.target.value ?: return@mutePipSound
                if (!p.audio.muteAgain(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not mute again ${target.packageName} from pip")
                }
            } else restorePipSound(takeover) {
                val p = phone ?: return@restorePipSound
                val target = p.screen.target.value ?: return@restorePipSound
                if (!p.audio.unmute(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not restore sound of ${target.packageName} from pip")
                }
            }
        }
    }

    /** 小窗当前目标的声音是否已被用户恢复过:接管中看交出去的包,否则看虚拟屏目标;键的取法与 unmute/muteAgain 一致。 */
    private fun pipSoundRestored(): Boolean {
        val p = phone ?: return false
        val target = p.screen.target.value
        val pkg = takeover?.handedOverPackage ?: target?.packageName ?: return false
        val user = target?.takeIf { it.packageName == pkg }?.let { copyUser(it.userId) }
        return AppAudioKey.of(pkg, user) in p.audio.userUnmuted.value
    }

    /** 回答提问;已经不在等(超时后才答、已答过)时调 [tooLate] 告诉用户这条回答没用上。 */
    fun answer(text: String, tooLate: () -> Unit) {
        if (loop == null) return
        if (_state.value is TaskState.WaitingInput) scope.launch {
            if (inbox.send(RunInput.Answer(text)) != InputReceipt.Accepted) tooLate()
        }
        else tooLate()
    }

    /** 渠道已经核对过提问编号；仍由同一个收件箱回答，避免绕过运行生命周期。 */
    suspend fun answerFromChannel(text: String, askId: String): Boolean =
        inbox.send(RunInput.Answer(text, askId)) == InputReceipt.Accepted

    /** 新指令取代尚未回答的普通提问；旧任务按取消收尾，排队的新指令随后执行。 */
    fun cancelAsk(askId: String): Boolean = loop?.cancelInput(askId) == true

    /**
     * 同一对话在任务进行中的新原话，交给下一次模型步；准备阶段也会先排在 Inbox。
     * [messageId] 是界面给这句话的编号,界面据此标出它的去向(见 [SteerNote])。
     */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) {
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value + SteerNote(messageId, text, SteerState.Pending)
            // 收件箱收下了就马上告诉用户:模型此刻的请求已撤掉,下一步就带着这句话想(交给模型时再改成已加入)
            if (inbox.steer(messageId, text) == InputReceipt.Accepted) markSteers(setOf(messageId), SteerState.Received, from = SteerState.Pending)
            else {
                if (stopPressed) markSteers(setOf(messageId), SteerState.Cancelled)
                else {
                    markSteers(setOf(messageId), SteerState.Next)
                    service.deferMessage(conversationId, text, messageId)
                }
            }
        }
    }

    /** 远程追加只归当前任务;与状态发布互斥,收件箱再原子核对是否已经结束/取消。 */
    fun steerRemote(text: String): String? = synchronized(publishLock) delivery@{
        if (ended || stopPressed || mcpExternalId == null || _state.value !is TaskState.Running) return@delivery null
        if (loop?.state?.value?.let { it !is TaskState.Running } == true) return@delivery null
        val id = UUID.randomUUID().toString()
        synchronized(service.taskState.shownLock) {
            // 先记消息,再唤醒模型;消费事件可能紧跟着发生,不能把 Joined 改回 Received。
            _steers.value = _steers.value + SteerNote(id, text, SteerState.Received, remote = true)
            if (inbox.steerCurrent(id, text) != InputReceipt.Accepted) {
                _steers.value = _steers.value.filterNot { it.id == id }
                return@delivery null
            }
            id
        }
    }

    /** 撤回手机上插的、模型还没取走的那句;已取走、已收尾或是电脑追加的(收件箱拒绝)返回 false,去向不变。 */
    fun withdrawSteer(messageId: String): Boolean = synchronized(service.taskState.shownLock) {
        if (!inbox.withdrawSteer(messageId)) return@synchronized false
        markSteers(setOf(messageId), SteerState.Withdrawn)
        true
    }

    /** 手机插话可转下一任务;远程插话只能用于当前任务,未消费的一律作废,不改变来源和权限。 */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed) {
        val left = inbox.handoffUnconsumed()
        val (discard, next) = left.partition { cancelled || it.currentTaskOnly }
        markSteers(discard.map { it.messageId }.toSet(), SteerState.Cancelled)
        markSteers(next.map { it.messageId }.toSet(), SteerState.Next)
        next.forEach { service.deferMessage(conversationId, it.text, it.messageId) }
    }

    /** 已交给这个任务的插话,随这一轮存进对话。 */
    /** 这次任务真的用过的能力组(手机、网页、沙箱);记进这一轮,下一轮开局预先展开。 */
    @Volatile var usedGroups: List<String> = emptyList()
        private set

    fun joinedSteers(): List<String> = joined().map { it.text }

    /** 和 [joinedSteers] 一一对应:每句插话打断的那段回复,没打断的是空串。 */
    fun joinedInterrupted(): List<String> = joined().map { it.interrupted.orEmpty() }

    /** 和 [joinedSteers] 一一对应:每句插话交给模型之前已经说完的那段回复,没有的是空串。 */
    fun joinedFinished(): List<String> = joined().map { it.finished.orEmpty() }

    private fun joined() = _steers.value.filter { it.state == SteerState.Joined }

    /** 插话撤掉的那段回复,等这一批插话里第一句交给模型时挂到它上面。 */
    private val steerJoin = SteerJoin()

    private fun joinSteer(id: String) {
        synchronized(service.taskState.shownLock) { _steers.value = steerJoin.joined(_steers.value, id) }
    }

    /** 把 [ids] 这几句改成 [to];给了 [from] 时只改还在那个状态的(收下和交给模型可能先后颠倒,不往回改)。 */
    private fun markSteers(ids: Set<String>, to: SteerState, from: SteerState? = null) {
        if (ids.isEmpty()) return
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value.map { if (it.id in ids && (from == null || it.state == from)) it.copy(state = to) else it }
        }
    }

    /** 运行事件里界面要跟的两样:实际做过的事,和插话已交给模型。 */
    private val liveEvents = object : RunSink {
        override fun event(e: RunEvent) {
            when {
                e is RunEvent.Activity -> synchronized(service.taskState.shownLock) {
                    val n = synchronized(activityLog) { activityLog += e; activityLog.size }
                    _activities.value = logView(n)
                }
                e is RunEvent.State && e.name == "reply_interrupted" -> steerJoin.interrupted(e.detail)
                e is RunEvent.State && e.name == "reply_finished_before_steer" -> steerJoin.finished(e.detail)
                e is RunEvent.State && e.name == "steer_received" -> joinSteer(e.detail)
            }
        }
    }

    /** 通知和卡片的按钮都带着它所属的申报 [key];点在已经换掉的旧通知、旧卡片上不算数。 */
    fun confirm(choice: ConfirmChoice, key: Int?) {
        val running = loop
        val waiting = _state.value as? TaskState.WaitingConfirmation
        if (running != null && waiting != null && key == ConfirmationNotifier.requestKey(waiting)) {
            scope.launch { inbox.send(RunInput.Confirm(choice)) }
        }
    }

    /**
     * 任务的终态:[execute] 收尾之后才写,不经 [publish]。不论显示的是不是自己,都先把本次的运行编号记到
     * 自己对话名下([TaskService.endedRunIds]),界面收到结束状态时按对话取;显示中时也写 [TaskService.endedRunId]。
     */
    fun end(end: TaskState) = synchronized(service.taskState.shownLock) {
        service.taskState._endedRunIds.value = service.taskState._endedRunIds.value + (conversationId to _runId.value)
        if (service.taskState.shownRun === this) service.taskState._endedRunId.value = _runId.value
        setState(end)
    }

    /** 超出并行上限在排队;放出来开跑时 [dequeue]。 */
    fun queue(position: Int) { setState(TaskState.Held(GateReason.queued(trPlural(R.plurals.queue_ahead, position)))) }
    fun dequeue() { setState(TaskState.Running(0, TaskService.PREPARING)) }

    /** 本任务的沙箱:一个任务一份(导出额度按任务算),工作区是本对话的。 */
    private val sandbox by lazy { AndroidSandbox(service, SandboxState.get(service), onShared = ::addShared) }

    /** 沙箱导出了一个文件(share_file 或 `mika share`)。 */
    fun addShared(f: SharedFile) = synchronized(service.taskState.shownLock) { _sharedFiles.value = _sharedFiles.value + f }

    fun clearTakeoverStage() { _takeoverStage.value = Takeover.Stage.None }

    /** 此刻要显示的状态;已收尾时为空,不再重画通知。 */
    fun shownState(): TaskState? = synchronized(publishLock) { if (ended) null else _state.value }

    /** 因模型失败、主模型在它那家还没测试通过时,提示去那家「重新测试」(见 [RetestHint])。测试换了主模型时不看设置里的配置。 */
    private fun withRetestHint(end: TaskState): TaskState {
        if (end !is TaskState.Failed || env.modelFactory !== TaskEnvironment.defaultModelFactory) return end
        return RetestHint.apply(end, ProviderStore.get(service).untestedMainProvider()) { tr(R.string.fail_model_untested, it) }
    }

    /** execute 收尾用的本地状态;把 finally 拆成独立函数以缩小 execute 的 suspend 状态机(超大 suspend 函数会被 ART 校验器拒绝)。 */
    private class RunCleanup(
        val side: CoroutineScope,
        val resources: ResourceScope,
        val tally: RunUsageTally,
        val approvalControl: com.wangzan101.mika.settings.ApprovalControl,
        val connection: com.wangzan101.mika.cap.phone.shell.ShellBinding?,
        val smsLogin: com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin?,
        val watcher: DeviceStateWatcher?,
        val mute: AudioMute?,
        val afterTask: com.wangzan101.mika.core.uistate.AfterTaskMarks?,
        val shellDeath: IBinder.DeathRecipient?,
        val shellBinder: IBinder?,
        val record: RunHandle?,
        val runStore: RunStore?,
        val mcpEvents: AutoCloseable?,
        val liveFeed: AutoCloseable?,
        val approval: RunApproval?,
    )

    private suspend fun cleanup(c: RunCleanup) {
        if (resultReadyAtMs == 0L) resultReadyAtMs = SystemClock.elapsedRealtime()
        handoffUnconsumed()
        c.side.cancel()
        _frames.value = null
        _phoneFrames.value = null
        _webOpen.value = false
        synchronized(publishLock) { ended = true }
        if (awake != null && service.hasQueuedRuns()) wakeHandoff = service.wakeLock.lease().also { it.acquire() }
        awake?.close()
        awake = null
        c.shellDeath?.let { d -> runCatching { c.shellBinder?.unlinkToDeath(d, 0) } }
        loop = null
        children = null
        // 早点关:后面收尾要等屏和网页;resources 关时还会再关一次(只关一次,不重复)
        mcpConnections?.close()
        mcpConnections = null
        c.mcpEvents?.close()
        c.liveFeed?.close()
        c.approval?.let { c.approvalControl.end(it) }
        gate = null
        takeover = null
        handoverRef = null
        sideScope = null
        // 借来的页面还回浏览器页(在关掉网页这一套之前)
        withContext(Dispatchers.Main + NonCancellable) { runCatching { UserBrowser.existing()?.loans?.end(key) } }
        withContext(Dispatchers.IO + NonCancellable) {
            // 前面哪步抛错,屏和网页显示也照放(按建的反序)
            try {
                // 先定 Mika 屏上 App 的去留(留下的挪到主屏后面、正在出声的关掉)并释放屏,再还原静音:留下的不会突然出声
                val apps = phone?.takeIf { !shellDied }?.let { p ->
                    p.display.current?.let { d -> AppsAfterTaskEnd.finish(p.shell, d.displayId, c.afterTask) }
                }
                c.record?.let { r ->
                    apps?.let { r.event(RunEvent.State(System.currentTimeMillis(), "apps_after_task", it.detail)) }
                    val cost = c.tally.deepSeekCost?.let { usage ->
                        val table = if (usage.hasUsage()) {
                            withTimeoutOrNull(2_000L) { deepSeekPrices.prices() }.also {
                                if (it == null) Log.d(TAG, "DeepSeek 价目表暂不可用")
                            }
                        } else null
                        usage.estimate(table)
                    }
                    r.event(c.tally.event(System.currentTimeMillis(), cost))
                    r.close()
                    if (BuildConfig.DEBUG) c.runStore?.mirrorToExternal(r.runId)
                }
                // 恢复抛错也要撤掉「在跑」的登记,否则这份记录永远不归恢复处理
                try { c.mute?.restoreAll() } finally { recovery.endRun(key) }
                c.watcher?.close()
            } finally {
                try { c.resources.close() } finally { c.smsLogin?.close() }
            }
        }
        phone = null
        // 屏释放后、对外报结束前清掉:界面看到任务结束时已经没有屏了
        _displayId.value = -1
        _pipInput.value = null
        _pipSoundOn.value = false
        _appShown.value = false
        if (c.connection != null) phoneServices.sharedShell.release()
    }

    suspend fun execute(priorTurns: List<Turn>): TaskState {
        noticeTainted = NoticeTurn.tainted(priorTurns)
        // 手机一侧(内置助手、无障碍、虚拟屏)第一次 launch_app 时才接上:只用网页、沙箱的任务不需要它们
        var connection: com.wangzan101.mika.cap.phone.shell.ShellBinding? = null
        var smsLogin: com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin? = null
        phone = null
        // 虚拟屏、网页显示等:建好当场归它,收尾时一起放掉;不经「建完再记到变量」,取消落在中间也不会漏
        val resources = ResourceScope()
        var watcher: DeviceStateWatcher? = null
        var mute: AudioMute? = null
        // 模型标下的任务结束时各 App 的去留(在手机端口上);收尾时交给内置助手
        var afterTask: com.wangzan101.mika.core.uistate.AfterTaskMarks? = null
        var shellDeath: IBinder.DeathRecipient? = null
        var shellBinder: IBinder? = null
        val traces = (service.application as MikaApp).detailedTraces
        val traceTicket = traces.ticket()
        var record: RunHandle? = null
        var runStore: RunStore? = null
        // 整个运行共用一份已报告 Token 预算；子任务沿用协程上下文里的这份，不另开额度。
        val tokenBudget = if (conditionCheck) null else FeatureFlags.get(service).taskTokenBudget()
        // 这次运行里经计量的模型请求(含旁路调用),收尾时记进运行记录
        val tally = RunUsageTally(tokenBudget, if (FeatureFlags.get(service).showTaskCost()) DeepSeekRunCost() else null)
        // 第一次打开网址时才建;没建过就不用关
        var webStack: WebStack? = null
        // 当前界面(手机/网页)要看分发器;画中画的画面来源随它切换
        var dispatcher: ToolDispatcher? = null
        var mcpEvents: AutoCloseable? = null
        var liveFeed: AutoCloseable? = null
        // 询问 / 自主:开工时取一份,开发者选项里换开关时跟着变;收尾时交还
        val approvalControl = (service.application as MikaApp).approvalControl
        var approval: RunApproval? = null
        // 任务期间的旁路协程(助手连接状态、静音与挪走监视、状态发布),任务结束一并取消
        val side = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "task side job failed", e) },
        )
        sideScope = side
        try {
            // 用户在「干活时」关掉了常亮就不拿唤醒锁;开跑就按此刻的状态(准备中)拿上:
            // 开模型、读知识、选路线、判断意图这些第一步之前的准备和推理期间屏幕也不熄
            awake = if (FeatureFlags.get(service).keepScreenOn()) KeepAwake(service.wakeLock.lease(), side).also { it.onState(_state.value) } else null
            // 前一个任务替这个拿着的那份,自己的拿上了就放开
            wakeHandoff?.release()
            wakeHandoff = null
            val model = env.modelFactory(service, session) ?: return TaskState.Failed(tr(R.string.fail_no_main_model), FailKind.Environment)
            // 用户在浏览器页上对着一页问的:那一页借给这个任务当当前网页标签(见 PageLoans);开跑时页面还开着才借得到
            val pageLoan = if (provenance.origin == RunOrigin.Ui) withContext(Dispatchers.Main) { UserBrowser.existing()?.claim(conversationId, key) } else null
            val light = env.lightModelFactory(service, session)
            val decision = env.decisionModel(service)
            // 同一模型分成两个实例并不会更便宜或更快;禁止它给批量/收尾工具反而只会增加改派和逐步操作。
            // 这只跳过逐步分工,轻量角色仍可用于意图判断、标题和收尾等辅助调用。
            val routedLight = light?.takeIf {
                !ProviderStore.get(service).roles.value.sameModel(ModelRole.Main, ModelRole.Light)
            }
            val vision = env.visionModelFactory(service, session)

            val (knowledge, skills) = withContext(Dispatchers.IO) {
                // 用户对某个 App/站点的补充放在私有目录 personal/apps、personal/sites 下
                val userNotes = KnowledgeStore.loadNotes(File(service.filesDir, "personal"))
                // 自学的 App 知识里改名前写下的工具名换成现在的
                val learnedNotes = legacyNames().let { legacy -> memoryRuntime.appKnowledge().notes().mapValues { it.value.copy(text = legacy.rewrite(it.value.text)) } }
                KnowledgeStore(KnowledgeStore.loadBundled(), userNotes, learnedNotes) to GuideCatalog.loadBundled()
            }
            val appAccess = (service.application as MikaApp).appAccess
            // 应用访问边界:工具和预览画面共用一份(下面接手机端口时建),窗口没变时核对结果直接沿用,预览不必每一帧都问窗口
            var accessGuard: com.wangzan101.mika.core.uistate.AppAccessScreenPort? = null
            var smsPrivacy: com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen? = null
            suspend fun previewAllowed(): Boolean {
                if (smsPrivacy?.imagesPrivate == true) return false
                val guard = accessGuard ?: return false
                return try {
                    guard.checkAppAccess()
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: com.wangzan101.mika.core.access.AppAccessDenied) { false }
            }
            val metrics = mainScreenMetrics()
            // 在 App 里操作的节奏(设置 › 任务运行);评测、真机验收按钩子覆盖
            val pace = env.evalHooks.operatingPace ?: FeatureFlags.get(service).operatingPace()
            // 助手是否连着;没接上手机之前当作连着(网页、沙箱的步骤不看它)
            val shellUp = MutableStateFlow(true)
            val states = DeviceStateWatcher(service) { id -> phone?.shell?.topTaskId(id) ?: -1 }.also { watcher = it }

            /** 接上手机:连内置助手、等无障碍、备好(到第一次 launch_app 才建的)虚拟屏;缺一样就报 phone_unavailable。 */
            suspend fun connectPhone(): AndroidScreen {
                // 版本要求只属于手机操作;旧系统仍可聊天、使用网页和沙箱,也不应被引导去配对不支持的助手。
                if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) throw PhoneUnavailable(TaskService.TOO_OLD)
                val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
                val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
                    ?: run {
                        phoneServices.setupPrompt.request()
                        // 只用 USB 命令起过的助手停了:照实说要再运行命令,不让人去找无线调试
                        throw PhoneUnavailable(
                            if (phoneServices.shellControl.restart == HelperRestart.UsbCommand) NO_PHONE_HELPER_USB else NO_PHONE_HELPER,
                        )
                    }
                // 服务卡在「开着却没运行」、正在自动重启时多等一会儿,修好了接着做,放弃了照常报错
                val a11y = AccessibilityRepair.awaitService(ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
                    ?: throw PhoneUnavailable(StatusCopy.accessibilityUnavailable(phoneServices.recovery.accessibility.value.enabled))
                recovery.beginRun(shell, key)
                // 只有用过手机的任务才跟着 shell 的死活:shell 没了,虚拟屏和上面的 App 一起没了
                shellDeath = IBinder.DeathRecipient {
                    shellDied = true
                    val running = loop
                    if (running != null) running.cancel() else job?.cancel()
                }.also { shell.asBinder().linkToDeath(it, 0) }
                shellBinder = shell.asBinder()
                side.launch { conn.shell.map { it != null }.collect { shellUp.value = it } }
                val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                    suspend fun create() = resources.acquire(Dispatchers.IO, AgentDisplay::release) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, service.getSystemService(DisplayManager::class.java)) }
                    try {
                        create()
                    } catch (e: Exception) {
                        if (e is CancellationException || e !is IllegalStateException && e !is RemoteException) throw e
                        if (!parallel || !service.otherRunHasDisplay(this@TaskRun)) throw e
                        // 外壳建不出第二块屏:这次运行期间不再并行;本任务排在后面,等别的任务的屏都放掉再建
                        service.taskState.markSecondDisplayFailed()
                        publish(TaskState.Held(GateReason.queued(WAITING_DISPLAY)))
                        while (service.otherRunHasDisplay(this@TaskRun)) delay(DISPLAY_RETRY_MS)
                        create()
                    }.also { d ->
                        _displayId.value = d.displayId
                        _pipInput.value = PipInputImpl(shell, d)
                        side.launch {
                            combine(d.frames, _appShown.flow) { frame, shown -> frame to shown }.collect { (frame, shown) ->
                                if (dispatcher?.surface != WEB) {
                                    val allowed = frame.takeIf { previewAllowed() }
                                    if (dispatcher?.surface != WEB) {
                                        _frames.value = allowed
                                        _phoneFrames.value = allowed.takeIf { shown }
                                    }
                                }
                            }
                        }
                        side.launch {
                            appAccess.state.collect {
                                if (dispatcher?.surface != WEB && !previewAllowed()) {
                                    _frames.value = null
                                    _phoneFrames.value = null
                                }
                            }
                        }
                    }
                }
                val audio = recovery.audioMute(shell, key).also { mute = it }
                val muter = appMuter(shell, audio)
                // 占用登记里的名字:另一份用户里的那份单独占,和本来那份互不挡;占着的任务还在不在用它,问内置助手
                fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
                fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
                    runCatching { if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user) }.getOrDefault(-1) >= 0
                }
                val screen = AndroidScreen(
                    a11y, shell, agentDisplay, { recovery.markClipHeld(it) },
                    clipLock = service.clipLock, checkOtherTasks = parallel,
                    reserve = { pkg -> service.reservePackage(pkg, key, stillUsed(pkg, null)) },
                    reserveCopy = { pkg, user -> service.reservePackage(claimKey(pkg, user), key, stillUsed(pkg, user)) },
                    appFree = { pkg, user -> service.packageFree(claimKey(pkg, user), key, stillUsed(pkg, user)) },
                    // 模型要了等:等的时候界面写「等另一个任务用完某 App」(仍是在干活,不算要你处理),等完放回原来那句
                    awaitApp = { pkg, user, timeoutMs, free ->
                        waitingForApp(ConfirmationNotifier.appLabel(service, pkg)) { service.awaitPackage(claimKey(pkg, user), key, timeoutMs, free) }
                    },
                    clipSaved = { original, ours -> recovery.saveClip(original, ours) },
                    handedOver = { handoverRef?.handedOver == true },
                    // 打字一段一段出来;App 知识说限制浏览快慢的 App 打得更慢
                    typing = { pkg -> pace.typing(PlacePace.of(pkg?.let(skills::appPace))) },
                    // 启动前就静上,开头几秒也没声;启动被拒就还原
                    beforeOpen = muter::beforeOpen,
                    notOpened = muter::notOpened,
                    appAccess = appAccess,
                )
                val side0 = PhoneSide(shell, a11y, screen, agentDisplay, muter)
                phone = side0
                watchTarget(side0, states)
                return screen
            }
            val phoneScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) } },
                // 另一份用户里的 App 要问内置助手:助手已经连着才问,不为它去接
                appCopies = {
                    phoneServices.hub.shell.value?.let { s ->
                        withContext(Dispatchers.IO) { runCatching { AppCopyReader.read(s, launchableApps(service.packageManager, service.packageName)) }.getOrNull() }
                    }.orEmpty()
                }) { connectPhone() }
            val accessedPhone = com.wangzan101.mika.core.uistate.AppAccessScreenPort(
                phoneScreen, appAccess,
                packages = { phone?.screen?.accessPackages().orEmpty() },
                protectedUi = { appAccess.sensitiveUi }, ownPackage = service.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() },
            ).also { accessGuard = it }
            val privatePhone = com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen(accessedPhone).also { smsPrivacy = it }
            afterTask = phoneScreen.afterTask
            // 挪回虚拟屏后等窗口出现,Agent 接下来读屏才读得到
            suspend fun moveTask(taskId: Int, to: Int): Boolean {
                val p = phone ?: return false
                val moved = withContext(Dispatchers.IO) { runCatching { p.shell.moveTask(taskId, to) }.getOrDefault(false) }
                if (moved && to == p.display.current?.displayId) p.screen.awaitContent()
                return moved
            }
            // 挪回去的 App 原本就在虚拟屏上,屏一定已经建好;没有屏就没有可挪回的
            suspend fun moveBackToAgent(taskId: Int): Boolean {
                val id = phone?.display?.current?.displayId ?: return false
                return moveTask(taskId, id)
            }
            val app = service.application as MikaApp
            // 微信只使用当前绑定会话的独立授权；仍保留 Channel 来源及原有确认/接管规则。
            val unlockAuthorized = taskUnlockAuthorization(provenance.origin, channelPeer) { peer ->
                app.wechat.store.canUnlock(peer)
            }
            val taskGate = TaskGate(states.locked, shellUp,
                refreshLocked = states::refresh,
                unattendedUnlock = unlockAuthorized?.let { authorized ->
                    {
                        val result = app.deviceAccess.access.unlock(authorized)
                        states.refresh()
                        Log.i(TAG, "${provenance.origin} unlock finished: ${result.status}")
                        result.status
                    }
                },
                unlockFailureMessage = {
                    tr(if (provenance.origin == RunOrigin.Channel) R.string.wechat_unlock_failed else R.string.trigger_unlock_failed)
                },
            ) {
                val t = phone?.screen?.target?.value
                t != null && t.taskId >= 0 && moveBackToAgent(t.taskId)
            }
            gate = taskGate
            // 记录开不起来就不记,任务照跑
            val sink: RunSink = withContext(Dispatchers.IO) {
                runCatching {
                    val runs = RunStore(service).also { runStore = it }
                    runs.open(goal).also { opened ->
                        record = opened
                        _runId.value = opened.runId
                        service.rememberRecord(this@TaskRun, opened.runId)
                        mcpExternalId?.let { ext ->
                            (service.application as? com.wangzan101.mika.MikaApp)?.remoteTasks?.rememberRun(ext, opened.runId)
                        }
                    }
                }.onFailure { Log.w(TAG, "run record unavailable, running without it", it) }.getOrNull()
            } ?: RunSink.NONE
            val trace = record?.takeIf { traceTicket != null }?.let { run ->
                resources.acquire(Dispatchers.IO, traces.store::finish) { traces.start(traceTicket, run.runId) }
            }
            // 装了哪些 App:每个任务读一次,读不到就当没有;参考资料用
            val apps = try { phoneScreen.listApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val speed = FeatureFlags.get(service).speedFlags()
            // 记忆与个人技能:进程内同一份;总开关关着就不给记忆(不检索、不注入、不提取、没有记忆工具)
            val switches = memoryRuntime.settings().switches.value.let { sw ->
                sw.copy(learnSkills = env.evalHooks.learnSkills ?: sw.learnSkills, autoRemember = env.evalHooks.rememberMemory ?: sw.autoRemember)
            }
            val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
            val taskMemory = if (switches.taskMemoryEnabled) withContext(Dispatchers.IO) { memoryRuntime.taskMemory() } else null
            val profileSnapshot = if (switches.useMemory) withContext(Dispatchers.IO) {
                runCatching { memoryRuntime.profile().snapshot() }.onFailure { Log.w(TAG, "profile unavailable", it) }.getOrDefault(emptyList())
            } else emptyList()
            // 用户本次说的话:记忆的引文、按用户要求写下技能的引文都据此核对(只在内存里,任务开始清空)
            val utterances = UserUtterances()
            // 改名前沉淀的个人技能:里面的旧工具名载入时换成现在的
            // 插件技能取任务开始时的快照:任务中途启停插件,下一次任务才生效
            val catalog = GuideCatalog.withPersonal(
                skills, memoryRuntime.skills(), legacyNames()::rewrite,
                plugins = (service.application as MikaApp).plugins.skillsSnapshot(),
            )
            // 内置浏览器:每个任务自己的后台网页显示、标签池和脚本引擎,第一次打开网址时才建(只用手机的任务不建);
            // 系统里没有 WebView 时不提供网页工具
            val webInstalled = runCatching { WebViewCompat.getCurrentWebViewPackage(service) != null }.getOrDefault(false)
            val web = if (!webInstalled) null else LazyWebPort {
                val stack = resources.acquire(Dispatchers.Main, WebStack::close) { WebStack.create(
                        service, key, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                        // 下载、上传走本对话的工作区:只是 App 私有目录里的文件夹,不要求沙箱已装好
                        workspace = SandboxDirs(service).let { d -> WebStack.Workspace(d.workspace(conversationId), d.gate, d.tree) },
                    ) }
                webStack = stack
                // 模型用 web_keep_open 标过的那一页,任务结束时留在后台,记在这一轮名下(对话里那一轮下面显示、关了补一句)
                stack.keepOrigin = { KeptOrigin(conversationId, _runId.value, goal) }
                side.launch {
                    // 网页界面时画中画显示网页显示的画面;换界面时先清掉上一边的画面;怎么显示见 WebPreview
                    var last: String? = null
                    while (true) {
                        delay(WEB_FRAME_MS)
                        val surface = dispatcher?.surface
                        if (surface == WEB) _phoneFrames.value = null
                        if (surface != last) {
                            _frames.value = null
                            last = surface
                        }
                        // 当前标签是借来的那一页时它就在用户眼前,和接管中一样不放画面
                        val show = WebPreview.of(onWeb = surface == WEB, hasTab = stack.hasVisibleTab, handedOver = stack.handedOver || stack.onBorrowedTab)
                        when (show) {
                            WebPreview.Frames -> withContext(Dispatchers.IO) { stack.host.latestFrame() }?.let { _frames.value = it }
                            WebPreview.Hidden, WebPreview.NoPage -> _frames.value = null
                            WebPreview.Off -> Unit
                        }
                        _webOpen.value = show.open
                    }
                }
                stack.screen
            }
            if (conditionCheck && !webInstalled) return TaskState.Failed(tr(R.string.fail_no_web), FailKind.Environment)
            // 借来的页面:开局就建好网页这一套,把它当当前标签借进去;用户摸了它就暂停(此刻正在手机屏上操作时不算)
            if (pageLoan != null && web != null && web.prepare()) webStack?.let { stack ->
                withContext(Dispatchers.Main) {
                    stack.borrowedTouches = { pageLoan.touches }
                    UserBrowser.existing()?.loans?.bind(pageLoan, object : PageLoans.Borrower<WebTab> {
                        override fun take(tab: WebTab) = stack.borrow(tab)
                        override fun giveBack(tab: WebTab) = stack.giveBack(tab)
                        override fun swap(prev: WebTab, next: WebTab) = stack.swapBorrowed(prev, next)
                        override fun adopt(tab: WebTab) = stack.adopt(tab)
                        override fun touched() {
                            if (stack.onBorrowedTab && !(phoneScreen.acquired && dispatcher?.surface != WEB)) userOnPage()
                        }
                    })
                }
            }
            // 这个对话里用户发来的屏幕(跟着对话存在工作区里)
            val userScreens = (service.application as MikaApp).userScreens.source(conversationId)
            val stepLimit = if (conditionCheck) null else FeatureFlags.get(service).taskStepLimit()
            val runSpec = RunSpec(RunId(key), goal, conversationId,
                priorTurns,
                provenance, if (conditionCheck) setOf("web", "sandbox") else null, readOnly = conditionCheck,
                stepBudget = stepLimit, tokenBudget = tokenBudget)
            // 外部 MCP server 只在主任务接;子任务/条件检查/评测不接,避免绕过父任务审批、影响评测可复现
            if (!conditionCheck) {
                // 连上的当场归 resources:收尾(含取消、失败、连到一半被停)关作用域时一起关,stdio server 的进程和沙箱租约跟着放掉
                mcpConnections = (service.application as MikaApp).mcpServers.connectionsFor(
                    resources,
                    com.wangzan101.mika.mcp.ConversationMcpResultStore(SandboxDirs(service), conversationId),
                    conversationId,
                )
                // 连接失败、超过总时限没连上的只在任务卡片上提示一句,不打断任务;详情在设置页的健康状态里
                mcpConnections?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                    val store = (service.application as MikaApp).mcpServers
                    val names = store.servers.value.associate { it.id to it.name }
                    (_state.value as? TaskState.Running)?.let {
                        publish(it.copy(note = tr(R.string.mcp_servers_run_failed, failures.joinToString(", ") { f -> names[f.first] ?: f.first })))
                    }
                }
            }
            // 对话里有通知提醒(第三方文字)时和电脑交来的任务一样:不管「自主模式」开没开,付款照样先问
            val runApproval = approvalControl.start(alwaysAsk = provenance.origin == RunOrigin.Mcp || noticeTainted).also { approval = it }
            val childRunner = if (provenance.origin == RunOrigin.Ui && web != null) ChildAgentRunner(
                create = { childSpec ->
                    val childTools = toolDispatcher(privatePhone, model.capabilities.vision,
                        web = web, webPacing = { PagePacing.human() }, webPlacePace = { PlacePace.of(catalog.sitePace(it)) },
                        webIdleScroll = { IdleScroll(Random.Default) },
                        appPacing = { pace.appPacing() }, appPace = { PlacePace.of(catalog.appPace(it)) }, appIdleScroll = { IdleScroll(Random.Default) },
                        sandbox = sandbox, workspace = { conversationId }, runSpec = childSpec,
                        conversationId = conversationId, inlineScreen = speed.inlineScreen,
                        adaptiveVision = speed.adaptiveVision)
                    AgentLoop(model, childTools, SystemPrompt.identity(), maxSteps = childSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                        // 子任务跟着询问 / 自主;询问时它也过关卡,子任务里花钱的一步一律不做
                        confirmations = runApproval.gate(), approvals = runApproval, autonomous = runApproval.startedAutonomous,
                        reviewFinalAnswer = false, flags = speed, outwardPacing = OutwardPacing.human())
                },
                store = (service.application as MikaApp).childRuns,
                ownerAlive = { job?.isActive == true },
            ).also { children = it } else null
            // 自身端口:只给手机上、有用户的 UI 主任务;子任务、条件检查、定时、渠道、MCP 都没有。
            // 状态只读工具一直给;操作类工具等用户打开「允许 Mika 操作自己的界面」。
            val selfPort = if (provenance.origin == RunOrigin.Ui && !conditionCheck) {
                (service.application as? MikaApp)?.let { app -> com.wangzan101.mika.self.AndroidSelfPort(app, conversationId, key) }
            } else null
            val tools = toolDispatcher(privatePhone, model.capabilities.vision, catalog, recordFrames = true, vision = vision?.let { VisionService(it, BitmapImageOps) },
                smsLogin = if (provenance.origin == RunOrigin.Ui && !conditionCheck && !runSpec.readOnly) {
                    com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin(service, phoneServices.smsLogin, appAccess,
                        accessedPhone, privatePhone) { phone?.screen }.also { smsLogin = it }
                } else null,
                phoneCalls = (service.application as MikaApp).callPort.takeIf {
                    !conditionCheck && (service.application as MikaApp).calls.settings.state.value.enabled
                },
                // 设置里允许 Mika 改自己时,才把 settings_list、settings_set 发给模型
                selfConfig = if (FeatureFlags.get(service).selfConfig()) {
                    com.wangzan101.mika.settings.SelfConfigImpl(FeatureFlags.get(service))
                } else null,
                stableRefs = speed.inlineScreen, inlineScreen = speed.inlineScreen,
                adaptiveVision = speed.adaptiveVision,
                deepLinks = speed.deepLinks, memory = memory, profile = if (memory != null) memoryRuntime.profile() else null,
                taskMemory = taskMemory,
                utterances = utterances, conversationId = conversationId,
                web = web,
                // 同一个网站一页一页看:换页前补足停留,不连着秒开
                webPacing = { PagePacing.human() },
                // 停留时像人一样把页面轻轻滚几下
                webIdleScroll = { IdleScroll(Random.Default) },
                // 在 App 里一步一步操作:动作之间停一会儿,限制浏览快慢的 App 停得更久、屏数有上限(按设置里的操作节奏)
                appPacing = { pace.appPacing() },
                appIdleScroll = { IdleScroll(Random.Default) },
                // 上传只认本对话工作区里的普通文件(与沙箱同一套路径规则);技能目录、工作区外一律越界
                workspacePath = web?.let { { p: String -> sandbox.uploadCheck(conversationId, p) } },
                // 工作区 = 对话:同一对话的追问接着用上一轮的文件,不同对话互不可见
                sandbox = sandbox, workspace = { conversationId },
                deviceFiles = if (provenance.origin == RunOrigin.Ui && !conditionCheck && FeatureFlags.get(service).deviceFiles()) {
                    AndroidDeviceFiles(phoneServices.hub, com.wangzan101.mika.cap.sandbox.SandboxDirs(service), sandbox,
                        com.wangzan101.mika.settings.DeviceDirectoryStore.get(service), DeviceImportStore.get(service))
                } else null,
                onSandboxCommand = { sandboxNote(StatusCopy.sandboxCommand(it, sandboxOnline())) },
                onSandboxProgress = { sandboxNote(StatusCopy.sandboxProgress(it, sandboxOnline())) },
                onSandboxPreparing = { sandboxNote(StatusCopy.RUNTIME_PREPARING) },
                onSandboxScript = { sandboxNote(StatusCopy.sandboxScript(sandboxOnline())) },
                imageOps = BitmapImageOps, runSpec = runSpec,
                userScreens = userScreens,
                scheduleTrigger = if (provenance.origin == RunOrigin.Ui) { trigger -> TriggerRuntime.schedule(service, trigger) } else null,
                scheduleAdmin = if (provenance.origin == RunOrigin.Ui) LocalScheduleAdmin(service) else null,
                childHost = childRunner,
                // 用户要求时写下或改写个人技能(和自学收下的放在同一处,设置里立刻能看到)
                personalSkills = memoryRuntime.skills(),
                // 这个对话之前几轮的运行记录(只读;history_read 只认 runSpec 里这个对话的轮次)
                runRecords = RunStore(service).reader(),
                // 这两天的通知原文和存着的各天、各周、各月的归纳(主动能力攒下的):只给用户在手机上发起的任务;主动能力没开时不去碰它
                notices = if (provenance.origin == RunOrigin.Ui && !conditionCheck && FeatureFlags.get(service).proactive()) {
                    (service.application as MikaApp).proactive.noticeSource()
                } else null,
                mcpServers = mcpEndpoints(),
                // 仅用户明确配置并启用时提供通用搜索;否则继续用浏览器搜索。
                webSearch = com.wangzan101.mika.settings.WebSearchSettings.get(service).service(),
                // 已 root 的手机上用户打开了「允许 Mika 执行 root 命令」才登记 root 工具(子任务、条件检查不给)
                root = phoneServices.shellManager.root.takeIf { !conditionCheck && it.commandsAllowed() }
                    ?.let { r -> RootCommands(RootShell::su, r::commandsAllowed) { a -> if (a == null) r.suGone() else r.commandsDenied(a) } },
                // 看/操作 Mika 自己;开关关着时只给只读状态
                self = selfPort,
                selfOperate = selfPort?.takeIf { FeatureFlags.get(service).selfOperate() })
            dispatcher = tools
            // 主动能力从通知里攒下、够格带进任务的那一段:只给用户在手机上发起的任务(不给定时、渠道、电脑交来的,也不给条件检查和子任务);
            // 主动能力没开时不去碰它(连对象都不建)
            val observed = if (provenance.origin == RunOrigin.Ui && !conditionCheck && FeatureFlags.get(service).proactive()) {
                try {
                    (service.application as MikaApp).proactive.observedForTasks()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "could not read what was gathered from notifications: ${e.javaClass.simpleName}")
                    null
                }
            } else null

            // 主模型备援链(项目 3):评测换掉模型来源时不接设置里的备援,免得测到真服务商
            val chainStore = ProviderStore.get(service)
            val (chainModels, chainFbTags) = if (env.modelFactory === TaskEnvironment.defaultModelFactory)
                chainStore.mainFallbackChain(session) else (emptyList<ChatModel>() to emptyList<String?>())
            val chainTags = listOf(chainStore.mainProviderTag()) + chainFbTags
            val agent = AgentLoop(
                model, tools,
                SystemPrompt.identity(),
                // 助手断开、被挪走只在这一步用的是手机时才查
                gate = { w ->
                    if (phoneScreen.acquired && dispatcher?.surface != WEB) phone?.screen?.let { awaitUserPick(it, taskGate, w) }
                    taskGate.pass({ phoneScreen.acquired && dispatcher?.surface != WEB }, w)
                }, knowledge = knowledge, sink = sink, trace = trace, installedApps = { apps },
                // 选渠道用的事实:装了哪些 App、在 Mika 里登录过哪些网站、有没有内置浏览器
                channelFacts = {
                    val registry = WebSites.registry(service)
                    ChannelFacts(
                        installed = apps.map { it.first }.toSet(),
                        loggedInSites = withContext(Dispatchers.IO) { registry.list().filter { it.loggedInByUser }.map { it.host }.toSet() },
                        webAvailable = web != null,
                        challengedSites = withContext(Dispatchers.IO) { registry.challengedSites() },
                    )
                },
                maxSteps = env.evalHooks.maxSteps ?: runSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                reviewFinalAnswer = false,
                inputTimeoutMs = TaskService.INPUT_TIMEOUT_MS,
                // 询问 / 自主:询问时只有花钱的一步先问(有确认关卡和 request_confirmation);自主时连付款也不问。
                // 开工时询问、中途换成自主的,下一步起不再问;开工时自主的,换成询问留给下一个任务
                confirmations = runApproval.gate(),
                approvals = runApproval,
                autonomous = runApproval.startedAutonomous,
                observed = observed,
                confirmTimeoutMs = TaskService.CONFIRM_TIMEOUT_MS,
                selfPackage = service.packageName,
                flags = speed,
                // 只在 lightSteps 打开、轻量模型能调工具且与主模型不是同一份配置时生效
                modelFallbacks = chainModels, modelChainTags = chainTags,
                router = routedLight?.let { ModelRouter(model, it) },
                intentJudge = if (provenance.origin == RunOrigin.Ui) light ?: model else null,
                intentDecision = if (provenance.origin == RunOrigin.Ui) decision else null,
                // 选路线是辅助判断:用轻量模型,没配用主模型
                routes = if (speed.replay) RouteMatcher(light ?: model, routeStore, knowledge, decision) { detail ->
                    sink.event(RunEvent.State(System.currentTimeMillis(), "jev_route", detail))
                } else null,
                skills = catalog,
                memory = memory,
                interaction = service.interaction(conversationId, channelPeer),
                profileSnapshot = profileSnapshot,
                streamReplies = true,
                userScreens = userScreens,
                // 同一网站、App 里连着联系别人、提交:两次之间隔一段随机的时间
                outwardPacing = OutwardPacing.human(),
            )
            loop = agent
            liveFeed = agent.events.subscribe(liveEvents)
            mcpExternalId?.let { mcpEvents = (service.application as MikaApp).remoteTasks.attach(it, agent.events) }
            kit = LearnKit(switches, memory, utterances, tools, agent, catalog, knowledge, model, light, runStore, agent.flags.replay, priorTurns)
            suspend fun <T> io(fallback: T, call: () -> T): T =
                withContext(Dispatchers.IO) { runCatching(call).getOrDefault(fallback) }
            // 接管期间才订阅主屏的无障碍事件:平时没人订阅,服务不复制事件
            var counter: HandoverActivityCounter? = null
            var counting: Job? = null
            val handover = Takeover(object : TakeoverPort {
                override suspend fun agentTopTask() = phone?.let { p -> p.display.current?.let { d -> io(-1) { p.shell.topTaskId(d.displayId) } } } ?: -1
                override suspend fun targetTask() = phone?.screen?.target?.value?.taskId ?: -1
                override fun targetPackage() = phone?.screen?.target?.value?.packageName
                override suspend fun packageOf(taskId: Int) = phone?.let { p -> io(null) { p.shell.packageOfTask(taskId) } }
                override suspend fun displayOf(taskId: Int) = phone?.let { p -> io(-1) { p.shell.displayOfTask(taskId) } } ?: -1
                override suspend fun moveToMain(taskId: Int) = moveTask(taskId, Display.DEFAULT_DISPLAY)
                override suspend fun moveBack(taskId: Int) = moveBackToAgent(taskId)
                override fun setSound(packageName: String, on: Boolean) {
                    val p = phone ?: return
                    // 交出去的若是目标 App,按目标所属的那份算;别的按本来那份
                    val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { copyUser(it.userId) }
                    if (on) {
                        if (!p.audio.release(packageName, user)) Log.w(TAG, "could not restore sound of $packageName for takeover")
                    } else {
                        p.audio.opened(packageName, user)
                    }
                }
                override fun unmute(packageName: String) {
                    val p = phone ?: return
                    val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { copyUser(it.userId) }
                    if (!p.audio.unmute(packageName, user)) Log.w(TAG, "could not restore sound of $packageName from pip takeover")
                }
                override fun muteAgain(packageName: String) {
                    val p = phone ?: return
                    val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { copyUser(it.userId) }
                    if (!p.audio.muteAgain(packageName, user)) Log.w(TAG, "could not mute again $packageName from pip takeover")
                }
                override fun watchInput(packageName: String) {
                    counting?.cancel()
                    val c = HandoverActivityCounter(packageName).also { counter = it }
                    // 立即订阅:接着发生的第一下操作不能漏
                    val a11y = phone?.a11y ?: return
                    counting = side.launch(start = CoroutineStart.UNDISPATCHED) {
                        a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                            c.onEvent(Display.DEFAULT_DISPLAY, e.packageName, e.eventType, e.className, e.eventTime, e.windowId to e.source?.hashCode())
                        }
                    }
                }
                override suspend fun handoverActivity(): HandoverActivity? {
                    val c = counter ?: return null
                    val pm = service.packageManager
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull()
                    return HandoverActivity(c.inputCount, stoppedAt(phone?.a11y?.let { mainWindowTitle(it, c.packageName) }, label, c.lastActivity))
                }
                override fun stopWatching() {
                    counting?.cancel()
                    counting = null
                    counter = null
                }
                override suspend fun resume(result: HandBack) { inbox.send(RunInput.HandBack(result)) }
                override fun cancel() { inbox.cancel() }

                // 网页界面:把当前标签(同一个页面)拿到主屏的接管界面;手机界面返回 null,走挪任务
                override suspend fun showWeb(reason: String, key: Int): Boolean? {
                    // 没打开过网页就不会在网页界面上
                    val w = webStack ?: return null
                    if (tools.surface != WEB) return null
                    val message = takeoverMessage(_state.value)
                    return withContext(Dispatchers.Main) {
                        w.showForTakeover(reason, message, key, WebHandover.taskActions(service, key, this@TaskRun.key)) { takeover?.waitKey }
                    }
                }

                override fun webBusy() = WebHandover.loginOpen

                override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? =
                    webStack?.let { w -> withContext(Dispatchers.Main) { w.returnFromTakeover(loggedIn) } }
            }, side, queue = service.takeoverQueue, remind = { service.refreshNotifications(this@TaskRun) })
            takeover = handover
            handoverRef = handover

            side.launch {
                agent.state.collect {
                    if (it == TaskState.Idle) return@collect
                    if (it is TaskState.Running && it.preview != null && (gate?.movedAwayPending == true || gate?.userOnPagePending == true)) return@collect
                    val previous = _state.value as? TaskState.Running
                    if (it is TaskState.Running && previous != null && previous.preview != it.preview &&
                        previous.copy(preview = it.preview) == it) {
                        // 文字增量只刷新聊天；通知、接管和设备唤醒不需要每个 token 都重跑。
                        synchronized(publishLock) { if (!ended) setState(it) }
                        return@collect
                    }
                    handover.onState(it)
                    publish(it)
                    env.evalHooks.answerFor(it)?.let { answer -> agent.answerInput(answer) }
                    env.evalHooks.decisionFor(it)?.let { approved -> agent.answerConfirmation(approved) }
                }
            }
            // 别的任务的接管进出队时,本任务的接管通知要跟着换(排到最前、或后面多了一个)
            if (parallel) side.launch { service.takeoverQueue.changes.collect { service.refreshNotifications(this@TaskRun) } }
            side.launch {
                combine(handover.stage, handover.problem) { stage, _ -> stage }.collect {
                    _takeoverStage.value = it
                    service.refreshNotifications(this@TaskRun)
                }
            }
            val end = withContext(tally) { agent.run(runSpec, inbox) }
            resultReadyAtMs = SystemClock.elapsedRealtime()
            return end.also { usedGroups = agent.usedGroups.toList() }.let(::localizeTaskFailure).let(::withRetestHint)
        } finally {
            cleanup(RunCleanup(side, resources, tally, approvalControl, connection, smsLogin, watcher, mute, afterTask, shellDeath, shellBinder, record, runStore, mcpEvents, liveFeed, approval))
        }
    }

    /** 手机一侧:用过手机的任务才有。 */
    private class PhoneSide(
        val shell: com.wangzan101.mika.shell.IMikaShell, val a11y: ScreenAccessibility, val screen: AndroidScreen,
        val display: LazyAgentDisplay, val audio: AppMuter,
    )

    /**
     * 小窗直接操作的口子:把预览画面上的归一化坐标换算成虚拟屏逻辑坐标,交给内置助手注入。
     * 注入走后台线程:injectStroke 会按笔迹时长逐点 sleep,不能占着界面线程。
     */
    private class PipInputImpl(
        private val shell: com.wangzan101.mika.shell.IMikaShell,
        private val display: AgentDisplay,
    ) : PipInput {
        override suspend fun tap(fx: Float, fy: Float) {
            withContext(Dispatchers.IO) {
                runCatching { shell.injectTap(display.displayId, (fx * display.width).toInt(), (fy * display.height).toInt()) }
            }
        }

        override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
            if (points.size < 2) {
                if (points.size == 1) tap(points[0].first, points[0].second)
                return
            }
            withContext(Dispatchers.IO) {
                val n = points.size
                runCatching {
                    shell.injectStroke(
                        display.displayId,
                        IntArray(n) { (points[it].first * display.width).toInt() },
                        IntArray(n) { (points[it].second * display.height).toInt() },
                        LongArray(n) { durationMs * it / (n - 1) },
                    )
                }
            }
        }

        override suspend fun back() {
            withContext(Dispatchers.IO) {
                runCatching { shell.injectKey(display.displayId, android.view.KeyEvent.KEYCODE_BACK) }
            }
        }
    }

    /**
     * 这次任务的静音规则(见 [AppMuter]):Mika 自己不静;没有桌面入口的系统包不静(系统界面、选择框一类);
     * 用户在主屏上看得见的不静;没静成的记下,任务进度上提示可能出声。
     */
    private fun appMuter(shell: com.wangzan101.mika.shell.IMikaShell, audio: AudioMute): AppMuter {
        val pm = service.packageManager
        val own = service.packageName
        fun system(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(service).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !system(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = { pkg ->
                muteFailedFor = pkg
                publish(_state.value)
            },
        )
    }

    /** 任务所属的用户 → 静音按哪一份:本来那份(或读不到)为 null,另一份用户里的为那个用户编号。 */
    private fun copyUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != AppCopyReader.myUser }

    @Volatile private var handoverRef: Takeover? = null
    @Volatile private var sideScope: CoroutineScope? = null

    /**
     * 系统在用户主屏上问打开哪一个(见 [AndroidScreen.userPick]):停在这里,胶囊上请用户在屏幕上选一下,选好自动接着做;
     * 选出来的 App 要用户亲手处理的,留在主屏,请用户处理好了点继续。不在等时直接返回。
     */
    private suspend fun awaitUserPick(screen: AndroidScreen, gate: TaskGate, setWaiting: (TaskState?) -> Unit) {
        val pick = screen.userPick.value ?: return
        setWaiting(TaskState.Held(TaskGate.userStep(TaskGate.pickCopy(pick.app))))
        val r = screen.awaitUserPick() as? AndroidScreen.PickResult.NeedsUser ?: return
        gate.pauseForUser(TaskGate.finishCopy(r.app), setWaiting)
        screen.afterUserStep(r)
    }

    /**
     * 目标 App 换了就静音(启动前多半已静上,这里补上没静成的),并盯着它有没有被用户挪到主屏。
     * Mika 屏上冒出别的 App(从目标 App 里点开的、另一份顶上来的)也是第一次看到就静;静音按「包 + 用户」,另一份单独静。
     * 用户接管期间屏上的事归用户,不去静。
     */
    private fun watchTarget(p: PhoneSide, states: DeviceStateWatcher) {
        val side = sideScope ?: return
        side.launch(Dispatchers.IO) {
            p.screen.foregroundApps().collect { t ->
                if (handoverRef?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                // 窗口变得很密:两次之间至少隔一会儿,中间的变化合成一次
                delay(FOREGROUND_GAP_MS)
            }
        }
        // 小窗声音按钮的方向:跟着目标、用户恢复记录和接管状态重算,不另存一份可能过期的开关态
        side.launch(Dispatchers.IO) {
            val stage = takeover?.stage ?: MutableStateFlow(Takeover.Stage.None)
            combine(p.screen.target, p.audio.userUnmuted, stage) { _, _, _ -> }.collect {
                _pipSoundOn.value = pipSoundRestored()
            }
        }
        side.launch(Dispatchers.IO) {
            p.screen.target.filterNotNull().collectLatest { t ->
                _appShown.value = true
                if (handoverRef?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                // 有目标 App 就说明 launch_app 已经建好了屏
                val displayId = p.display.current?.displayId
                if (t.taskId >= 0 && displayId != null) {
                    states.taskMovedAway(displayId, t.taskId).collect {
                        // 用户点「我来处理」后 App 本来就该在主屏
                        if (handoverRef?.handedOver == true) return@collect
                        gate?.onTaskMovedAway()
                        publish(_state.value) // 立即显示暂停,不等下一个改动屏幕的工具
                    }
                }
            }
        }
    }

    /**
     * 用户在浏览器页上亲手摸了借给这个任务的页面:正在干活时马上显示暂停,下一个会改动屏幕的工具之前停下,
     * 用户点「继续」再接着做(见 [TaskGate.onUserOnPage])。等用户回答、接管中等别的状态不动。主线程。
     */
    private fun userOnPage() {
        if (_state.value !is TaskState.Running) return
        val g = gate ?: return
        g.onUserOnPage()
        publish(_state.value)
    }

    /** 任务收尾后判结局、学东西要用的;[execute] 里填上。 */
    private class LearnKit(
        val switches: MemorySwitches, val memory: MemoryStore?, val utterances: UserUtterances?, val dispatcher: ToolDispatcher,
        val agent: AgentLoop, val catalog: GuideCatalog, val knowledge: KnowledgeStore,
        val model: ChatModel, val light: ChatModel?, val runStore: RunStore?,
        val routesOn: Boolean, val priorTurns: List<Turn>,
    )
    @Volatile private var kit: LearnKit? = null

    /** 停下时正在写的那段字(模型请求已流出的可见文字);收尾前取,收尾后状态就换成终态了。 */
    fun draftText(): String? = (_state.value as? TaskState.Running)?.preview?.takeIf(String::isNotBlank)

    /** 停止后有没有可交代的:做过至少一件事,或停下时已经写出了字。 */
    fun hasStopMaterial(draft: String?): Boolean = synchronized(activityLog) { activityLog.isNotEmpty() } || !draft.isNullOrBlank()

    /** 真正失败后的断点交代:只按记录拼,不再请模型,也不把短失败原因改写成成功。 */
    suspend fun checkpointSummary(): String? {
        val k = kit
        val runId = _runId.value
        val recorded = withContext(Dispatchers.IO) {
            try {
                runId?.let { id -> k?.runStore?.events(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val events = recorded?.takeIf { StopSummary.worthSaying(it, null) } ?: synchronized(activityLog) { activityLog.toList() }
        if (!StopSummary.worthSaying(events, null)) return null
        val labels = ToolLabels(knownTools())
        return StopSummary.plain(k?.agent?.taskText ?: goal, events, null, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * 用户停止之后:按这次已经做了的事写几行交代(见 [StopSummary]),只读运行记录,不再操作任何东西。
     * 轻量模型优先,没配用主模型;没模型、出错、超时就按记录直接拼。没什么可交代的为 null。
     */
    suspend fun stopSummary(draft: String?): String? {
        val k = kit
        val runId = _runId.value
        val recorded = withContext(Dispatchers.IO) { runId?.let { id -> runCatching { k?.runStore?.events(id) }.getOrNull() } }
        val events = recorded?.takeIf { e -> e.any { it is RunEvent.Activity } } ?: synchronized(activityLog) { activityLog.toList() }
        if (!StopSummary.worthSaying(events, draft)) return null
        // 还没开出模型就停下的(准备阶段):按设置现取
        val (main, light) = if (k != null) k.model to k.light else withContext(Dispatchers.IO) {
            runCatching { env.modelFactory(service, session) }.getOrNull() to runCatching { env.lightModelFactory(service, session) }.getOrNull()
        }
        val labels = ToolLabels(knownTools())
        return StopSummary(light ?: main, main).summarize(k?.agent?.taskText ?: goal, events, draft, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * 结果交给用户之后调用(不拖慢、不改交出的回答):回答按用户的要求判结局([OutcomeJudge],轻量模型优先),
     * 记进运行记录和这一轮对话;只有办成的([LearnGate])才记路线、给回放的路线记成功。
     * 放弃、失败、取消的不判(结局已经清楚),返回 null;判不成是 [RunOutcome.Unknown]。
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? {
        val k = kit ?: return null
        if (end !is TaskState.Succeeded) return null
        val judged = OutcomeJudge(k.light ?: k.model, k.model).judge(k.agent.taskText ?: goal, end.answer, k.priorTurns)
        val runId = _runId.value
        if (runId != null) withContext(Dispatchers.IO) {
            k.runStore?.append(runId, judged.event(System.currentTimeMillis()))
            if (BuildConfig.DEBUG) k.runStore?.mirrorToExternal(runId)
            runCatching { conversations.updateTurn(conversationId, runId) { it.copy(result = judged.outcome.wire) } }
                .onFailure { Log.w(TAG, "could not save run outcome", it) }
        }
        k.agent.noteOutcome(judged.outcome)
        // 只有办成的才记成路线;从落盘的运行事件里取
        if (runId != null && LearnGate.shouldSaveRoute(k.routesOn, end, judged.outcome, runId, goal)) k.runStore?.let { s ->
            saveRoute(goal, withContext(Dispatchers.IO) { s.events(runId) }, k.dispatcher.index)
        }
        return judged
    }

    /**
     * 结果通知发出之后调用:记下用到的个人技能的结果、提取记忆、起草技能,提示挂到这一轮的结果卡片上并存进对话。
     * 在进程级的作用域里跑,服务退出也不中断。
     */
    /** 开工时这段对话里有没有通知提醒(见 [NoticeTurn]);[execute] 一开始按存着的全部轮次定。 */
    @Volatile private var noticeTainted = false

    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) {
        rememberTask()
        val k = kit ?: return
        val runId = _runId.value
        val learning = RunLearning(
            k.switches, k.memory, memoryRuntime.skills(), k.catalog,
            extractor = k.memory?.let { MemoryExtractor(k.light ?: k.model) },
            drafter = SkillDrafter(k.model),
            appDrafter = AppKnowledgeDrafter(k.light ?: k.model),
            appDrafts = memoryRuntime.appKnowledge(),
            ids = { UUID.randomUUID().toString() },
            autoApprove = env.evalHooks.autoApproveSkillDrafts,
            yieldToForeground = { while (service.hasActiveRuns()) delay(500) },
        )
        val notes = learning.learn(RunAftermath(
            conversationId, runId, k.agent.taskText ?: goal, end, k.utterances.takeIf { k.memory != null }?.all.orEmpty(), k.dispatcher.savedChanges, k.agent.usedMemoryIds,
            k.dispatcher.definitions().map { it.name }.toSet(), k.dispatcher.index,
            events = { withContext(Dispatchers.IO) { runId?.let { k.runStore?.events(it) }.orEmpty() } },
            origin = provenance.origin,
            knowledge = k.knowledge,
            outcome = outcome ?: RunOutcome.Unknown,
            // 对话里有通知提醒,或这次开工时带了从通知里得来的那一段:都不自动提取记忆
            noticeTainted = noticeTainted || k.agent.carriedObserved,
        ))
        if (runId == null || notes.memoryNotes.isEmpty() && notes.usedMemory.isEmpty() && notes.skillNotes.isEmpty()) return
        memoryRuntime.publish(notes)
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.updateTurn(conversationId, runId) { it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes) }
            }.onFailure { Log.w(TAG, "could not save memory notes", it) }
        }
    }

    /**
     * 任务结束之后:按这次运行的事件记一条跨会话摘要(不拖慢交付,失败不影响任务结局)。
     * 读过通知的任务照样记,但结论里不留回答的文字(见 [com.wangzan101.mika.core.proactive.NoticeRuns])。
     * 开关关着、没有运行编号、没读到事件、没什么可记的都不记。
     */
    private suspend fun rememberTask() {
        val runId = _runId.value ?: return
        val settings = memoryRuntime.settings()
        if (!settings.switches.value.taskMemoryEnabled) return
        val events = withContext(Dispatchers.IO) { runCatching { kit?.runStore?.events(runId) }.getOrNull() } ?: return
        val labels = ToolLabels(knownTools())
        val recorded = TaskMemoryRecorder.record(goal, events, System.currentTimeMillis(), UUID.randomUUID().toString(), labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        } ?: return
        // 读过通知的(调过 notices_read,或这段对话里有通知提醒):结论只留结局,不留回答的字
        val task = com.wangzan101.mika.core.proactive.NoticeRuns.summary(recorded, events, noticeTurnInContext = noticeTainted)
        withContext(Dispatchers.IO) {
            if (!settings.switches.value.taskMemoryEnabled) return@withContext
            runCatching { memoryRuntime.taskMemory().add(task) }
                .onFailure { Log.w(TAG, "task memory not saved", it) }
        }
    }

    /** 办成的运行记成路线;记不成(不该记、写盘失败)不影响任务结局。 */
    private suspend fun saveRoute(goal: String, events: List<RunEvent>, tools: ToolIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val route = RouteRecorder.record(goal, events, System.currentTimeMillis(), tools) ?: return@runCatching
            val versions = route.packages.mapNotNull { p ->
                runCatching { service.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()?.let { p to it }
            }.toMap()
            routeStore.putReplacing(route.copy(appVersions = versions))
        }.onFailure { Log.w(TAG, "route not saved", it) }
    }

    /** 主屏上该包最上面那个窗口的标题;窗口按从上到下排列。 */
    private fun mainWindowTitle(a11y: ScreenAccessibility, packageName: String): String? = runCatching {
        a11y.windowsOn(Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { w ->
            w.title?.toString()?.takeIf { it.isNotBlank() && w.root?.packageName?.toString() == packageName }
        }
    }.getOrNull()

    /** 沙箱这一步的说明写到任务卡片上(当前这一步的 note)。 */
    private fun sandboxNote(text: String) {
        val s = _state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    private fun sandboxOnline() = (sandbox.status() as? SandboxStatus.Ready)?.network == true

    /**
     * 等另一个任务放开 [app] 的这段时间:对外的进展写「等另一个任务用完 [app]」,仍是 Running,不是暂停、也不要你处理;
     * 等完(放开了、等不到、被停掉)把等之前那一句放回去。等之前不在干活(比如已经停下来要你处理)就不改。
     */
    private suspend fun <T> waitingForApp(app: String, wait: suspend () -> T): T {
        val before = _state.value as? TaskState.Running ?: return wait()
        val waiting = before.copy(progress = RunProgress.WaitingForApp(app))
        publish(waiting)
        try {
            return wait()
        } finally {
            if (_state.value == waiting) publish(before)
        }
    }

    private fun publish(state: TaskState) {
        // 被挪走后到 gate 拦下之前,循环可能还在推理:对外先显示暂停
        // 循环自己报的终态不往外发:终态只由 [end] 在记好运行编号之后写,否则界面先收到结束、拿不到编号
        if (!TaskService.loopMayPublish(state)) return
        val shown = when {
            state is TaskState.Running && gate?.movedAwayPending == true -> TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY))
            // 用户在借出的页面上操作:到关卡之前也先显示暂停
            state is TaskState.Running && gate?.userOnPagePending == true -> TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))
            else -> state
        }
        synchronized(publishLock) {
            if (ended) return
            setState(shown)
            awake?.onState(state)
        }
        service.refreshNotifications(this)
    }

    @Suppress("DEPRECATION")
    private fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** 网页接管界面顶部的说明:接管原因的话,付款时再带上商品、金额、地址。 */
    private fun takeoverMessage(state: TaskState): String? {
        val w = state as? TaskState.WaitingTakeover ?: return null
        return listOfNotNull(
            w.message.takeIf { it.isNotBlank() },
            w.item?.let { tr(R.string.payment_item, it) },
            w.amount?.let { tr(R.string.payment_amount, it) },
            w.address?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }
    }

    private companion object {
        /** 网页界面时画中画取画面的间隔。 */
        const val WEB_FRAME_MS = 500L
        const val SHELL_WAIT_MS = 15_000L
        const val DISPLAY_RETRY_MS = 1_000L
        /** Mika 屏上的前台 App 两次重读之间至少隔这么久。 */
        const val FOREGROUND_GAP_MS = 300L
        val WAITING_DISPLAY: String get() = tr(R.string.task_waiting_display)
        const val A11Y_WAIT_MS = 5_000L
        val NO_PHONE_HELPER: String get() = tr(R.string.task_no_phone_helper)
        val NO_PHONE_HELPER_USB: String get() = tr(R.string.task_no_phone_helper_usb)
        const val TAG = "MikaTask"
    }
}
