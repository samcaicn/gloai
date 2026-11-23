package com.wangzan101.mika.task

import android.graphics.Bitmap
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.PhoneViewport
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.cap.phone.display.phoneViewport
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.agent.TaskStages
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.run.ChildAgentRunner
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.model.usage.DeepSeekPriceCache
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.knowledge.FileRouteStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.imageModel
import com.wangzan101.mika.settings.ttsModel
import com.wangzan101.mika.settings.untestedMainProvider
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.voice.model.settingsProtocol
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

/** Resolve file-transcription exposure when phone tools are assembled; a stale voice selection only removes this tool. */
internal fun taskTranscribePort(
    origin: RunOrigin,
    stt: com.wangzan101.mika.voice.model.SttSelectionController,
    legacyPort: () -> com.wangzan101.mika.core.cap.transcribe.TranscribePort?,
    migratedPort: () -> com.wangzan101.mika.core.cap.transcribe.TranscribePort,
): com.wangzan101.mika.core.cap.transcribe.TranscribePort? {
    if (origin != RunOrigin.Ui) return null
    if (!stt.migrated) return legacyPort()
    return try {
        if (stt.batchModel() == null) null else migratedPort()
    } catch (_: com.wangzan101.mika.voice.model.VoiceModelError) {
        null
    }
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
    internal val service: TaskService,
    private val scope: CoroutineScope,
    val slot: Int = 0,
    val parallel: Boolean = false,
) : ScreenControl {
    override val manualControl = com.wangzan101.mika.core.run.ManualControl()
    internal data class ManualTarget(val port: com.wangzan101.mika.core.screen.uistate.ScreenPort, val identity: String, val width: Int, val height: Int, val inputWidth: Int, val inputHeight: Int)
    private var manualTarget: ManualTarget? = null
    internal var manualTargetNow: (suspend () -> ManualTarget?)? = null

    /** 只给当前运行的实际画面接管，已有主屏接管或无画面时不开放输入。 */
    override suspend fun beginScreenControl(): Boolean {
        if (stopPressed || job?.isActive != true || !manualControl.begin()) return false
        return try {
            val target = manualTargetNow?.invoke()
            if (target == null) { manualControl.end(); false }
            else { manualTarget = target; loop?.state?.value?.let(::publish); true }
        } catch (e: CancellationException) { withContext(NonCancellable) { manualControl.end() }; throw e }
        catch (_: Exception) { manualControl.end(); false }
    }

    private suspend fun endScreenControl() {
        manualTarget = null
        manualControl.end()
        loop?.state?.value?.let(::publish)
    }

    override suspend fun continueAfterScreenControl() {
        val waitingTakeover = takeover?.stage?.value == Takeover.Stage.Waiting
        endScreenControl()
        if (waitingTakeover) takeover?.handBack()
    }

    private suspend fun manualInput(width: Int, height: Int, act: suspend (ManualTarget) -> com.wangzan101.mika.core.screen.uistate.ActionResult): Boolean {
        val version = manualControl.version
        return manualControl.userInput {
            if (manualControl.version != version) return@userInput false
            val current = manualTargetNow?.invoke() ?: return@userInput false
            val target = manualTarget ?: return@userInput false
            if (target.identity != current.identity || width != current.width || height != current.height ||
                target.width != width || target.height != height || target.inputWidth != current.inputWidth || target.inputHeight != current.inputHeight) return@userInput false
            try { target.port.checkAppAccess(); act(target) is com.wangzan101.mika.core.screen.uistate.ActionResult.Ok }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        } ?: false
    }

    @Volatile internal var activePhoneDisplay: AgentDisplay? = null
    private var appliedViewport: Pair<Int, PhoneViewport>? = null

    override suspend fun resizeScreen(width: Int, height: Int): Boolean {
        if (width < 64 || height < 64 || job?.isActive != true) return false
        // 网页的手机版/桌面版视口由浏览器自己管理；这里调整后台手机屏。
        if (runState._webOpen.value) return false
        val display = activePhoneDisplay ?: return false
        val viewport = phoneViewport(width, height, service.resources.displayMetrics.densityDpi)
        if (appliedViewport == (display.displayId to viewport)) return true
        return manualControl.reconfigure {
            withContext(NonCancellable) {
                if (runState._webOpen.value || job?.isActive != true || activePhoneDisplay !== display || takeover?.handedOver == true) return@withContext false
                if (!display.resize(viewport.width, viewport.height, viewport.dpi)) return@withContext false
                appliedViewport = display.displayId to viewport
                manualTarget = manualTarget?.takeIf { it.identity == "phone:${display.displayId}" }?.copy(
                    width = display.previewWidth, height = display.previewHeight, inputWidth = display.width, inputHeight = display.height)
                // 新 Surface 的首帧之前不接受旧画面输入；任务仍在同一个 displayId 上。
                withTimeoutOrNull(2_000) { display.frames.filterNotNull().first() }
                true
            }
        }
    }

    override suspend fun touchScreen(width: Int, height: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, swipe: Boolean): Boolean {
        if (width <= 0 || height <= 0 || x1 !in 0 until width || x2 !in 0 until width || y1 !in 0 until height || y2 !in 0 until height) return false
        return manualInput(width, height) { target ->
            val start = controlPoint(x1, y1, width, height, target.inputWidth, target.inputHeight)
            val end = controlPoint(x2, y2, width, height, target.inputWidth, target.inputHeight)
            if (swipe) target.port.swipe(start.first, start.second, end.first, end.second, durationMs.coerceIn(50, 2_000))
            else target.port.tapXY(start.first, start.second)
        }
    }

    override suspend fun screenBack(width: Int, height: Int) = manualInput(width, height) { it.port.back() }
    override suspend fun screenText(width: Int, height: Int, text: String) = manualInput(width, height) { it.port.typeText(null, null, text) }
    internal val chipStages = TaskStages {
        try { service.refreshNotifications(this) } catch (e: RuntimeException) {
            // The action already ran. A notification failure must never turn it into a failed tool call.
            Log.w(TAG, "could not refresh stage notification", e)
        }
    }
    internal var provenance = RunProvenance()
    internal var triggerWindowKey: String? = null
    internal var triggerActionGoal: String? = null
    /** 条件检查运行时用户写的条件原文;对话和通知显示它,不显示生成的检查目标 [goal]。 */
    internal var triggerCondition: String? = null
    internal var triggerThreadId: String? = null
    internal var mcpExternalId: String? = null
    internal var workflowAttemptId: String? = null
    /** 前台派活时附的补充说明;进任务上下文时是 Mika 的说明,不是用户原话。没有为 null。 */
    internal var brief: String? = null
    internal var channelPeer: ChannelPeer? = null
    internal var channelMessageId: String? = null
    /** 主循环已经给出结局的时间；与结果实际发出之间的耗时用于定位渠道回传延迟。 */
    @Volatile internal var resultReadyAtMs = 0L
        private set
    internal val conditionCheck get() = triggerActionGoal != null
    @Volatile internal var children: ChildAgentRunner? = null
    /** 这次任务连上的外部 MCP server;结束时关闭会话。 */
    @Volatile internal var mcpConnections: com.wangzan101.mika.settings.McpConnections? = null

    /**
     * 这次任务接上的全部外部 MCP 端点,交给 toolDispatcher 的就这一份。别的来源(以后的扩展等)也提供这类端点时在这里拼上,
     * 不给 toolDispatcher 另加参数;各来源连上的都要归任务的 ResourceScope,收尾时一起关。
     */
    internal fun mcpEndpoints(): List<com.wangzan101.mika.core.cap.mcp.McpConnection> = buildList {
        mcpConnections?.let { addAll(it.connections) }
    }
    /** 本次任务使用的沙箱工作区(同项目对话共用 `p:<projectId>`,其余沿用对话工作区)。 */
    internal val workspaceKey: WorkspaceKey by lazy { WorkspaceResolver.resolveFor(service, conversationId) }

    /** 自己的一份;显示中时同步写到服务对外的那份。[show] 时整份抄过去。 */
    /** App 给的模型来源、评测钩子、屏幕画面的开关。 */
    internal val env = TaskEnvironment.of(service)
    /** 这段对话对外的会话号:这次运行建的模型(主、轻量、看图,及核查、意图判断、停止交代等旁路)都用它。 */
    internal val session = ModelSession.of(conversationId)
    /** App 给的手机服务(共用的 shell 连接、「去设置」提示)、记忆、经验路线、异常退出后的恢复。 */
    internal val phoneServices = PhoneServices.of(service)
    internal val memoryRuntime = MemoryRuntime.of(service)
    internal val routeStore = FileRouteStore.shared(service)
    internal val recovery = TaskRecovery.of(service)

    /** 本任务的界面可见状态(14 个 Mirrored 状态流 + 发布锁 + 结束标志)。 */
    internal val runState = TaskRunState(service, conversationId, env) { service.taskState.shownRun === this }

    /** 静音等恢复记录按它分开,几个任务各记各的。 */
    override val key: String = UUID.randomUUID().toString()
    internal val inbox = RunInbox()
    /** 插话的收发、去向与撤回。 */
    private val steerCoordinator = SteerCoordinator(
        service, conversationId, runState, inbox,
        loop = { loop }, stopPressed = { stopPressed }, mcpExternalId = { mcpExternalId },
    )
    /** 生命周期控制与用户交互(取消、接管、问答、确认、收尾)。 */
    private val runControl = TaskRunControl(
        service, conversationId, scope, runState, inbox,
        loop = { loop }, gate = { gate }, takeover = { takeover },
        children = { children }, job = { job },
        cancelRequested = { cancelRequested }, setCancelRequested = { cancelRequested = it },
        shownRef = { service.taskState.shownRun === this },
        publish = ::publish,
        key = key,
        setStopPressed = { stopPressed = it },
    )
    /** 收尾后的交代与学习。 */
    private val runTeardown = RunTeardown(
        service, conversationId, goal, runState,
        kit = { kit }, env = env, session = session, memoryRuntime = memoryRuntime,
        routeStore = routeStore,
        workflowAttemptId = { workflowAttemptId }, noticeTainted = { noticeTainted }, provenance = { provenance },
    )
    /** 手机侧运行时(浮层声音、静音、目标监视、浏览器页触摸暂停)。 */
    internal val phoneRuntime = PhoneRuntime(
        service, conversationId, scope, runState,
        publish = ::publish,
        phone = { phone }, takeover = { takeover }, gate = { gate },
        handoverRef = { handoverRef }, sideScope = { sideScope }, sandbox = { sandbox },
        setMuteFailedFor = { muteFailedFor = it },
        workspaceKey = { workspaceKey },
    )

    val state: StateFlow<TaskState> get() = runState.state
    val runId: StateFlow<String?> get() = runState.runId
    /** 本任务导出的文件;结束时随这一轮存进对话。 */
    val sharedFiles: StateFlow<List<SharedFile>> get() = runState.sharedFiles
    /** 本任务生成并留在工作区里的音频;结束时随这一轮存进对话。 */
    val audios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> get() = runState.audios
    /** 本任务生成并留在工作区里的图;结束时随这一轮存进对话。 */
    val photos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> get() = runState.photos
    val takeoverStage: StateFlow<Takeover.Stage> get() = runState.takeoverStage
    val frames: StateFlow<Bitmap?> get() = runState.frames
    /** 个人助手的缩略图只收本任务获准展示的手机画面。 */
    val phoneFrames: StateFlow<Bitmap?> get() = runState.phoneFrames
    /** 本任务到此刻的活动(实际做过的事);悬浮胶囊的卡片按任务取,不只看显示中的那个。 */
    val activities: StateFlow<List<RunEvent.Activity>> get() = runState.activities
    /** 本任务的虚拟屏编号;屏到用到系统命令、事件工具或第一次打开 App 时才建,之前和释放之后为 -1。 */
    val displayId: StateFlow<Int> get() = runState.displayId
    /** 本任务小窗的直接操作口;屏建好才有,释放后回到 null。 */
    val pipInput: StateFlow<PipInput?> get() = runState.pipInput
    /** 本任务小窗目标的声音是否被用户恢复过;界面据此把按钮显示成「恢复声音」或「静音」。 */
    val pipSoundOn: StateFlow<Boolean> get() = runState.pipSoundOn
    /** 此刻在用内置浏览器(工具的界面是网页);没用、任务结束后为 false。 */
    val webOpen: StateFlow<Boolean> get() = runState.webOpen
    /** 本任务进行中插的话和它们的去向;悬浮胶囊的对话卡片按任务取。 */
    val steers: StateFlow<List<SteerNote>> get() = runState.steers

    /** 让界面显示这个任务:成为显示中的任务,并把自己的各项抄到服务对外的流。 */
    fun show() = synchronized(service.taskState.shownLock) {
        service.taskState.shownRun = this
        runState.copyOut()
    }

    /** 跑 [execute] 的协程,由服务启动后填上。 */
    @Volatile var job: Job? = null
        internal set(v) {
            field = v
            if (cancelRequested) v?.cancel()
        }
    @Volatile private var cancelRequested = false
    /** 用户点了停止:还没交给模型的插话一起作废,不当成下一件事。 */
    @Volatile internal var stopPressed = false
    @Volatile internal var loop: AgentLoop? = null
    val activeAsk: Delivery.Ask? get() = runControl.activeAsk
    @Volatile internal var gate: TaskGate? = null
    @Volatile var takeover: Takeover? = null
        internal set
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
    @Volatile internal var phone: PhoneSide? = null
    fun cancel() {
        runControl.cancel()
        if (manualControl.state.value != com.wangzan101.mika.core.run.ManualControl.State.Automatic) {
            scope.launch { manualControl.close() }
        }
    }

    fun resume() {
        if (manualControl.state.value == com.wangzan101.mika.core.run.ManualControl.State.Manual) {
            scope.launch { continueAfterScreenControl() }
            return
        }
        runControl.resume()
    }
    private fun takeoverAction(action: suspend (Takeover) -> Boolean) {
        val current = takeover ?: return
        scope.launch {
            manualControl.end {
                action(current).also { if (it) manualTarget = null }
            }
        }
    }

    fun takeOver(waitKey: Int?) = takeoverAction { it.handOver(waitKey) }
    fun handBack(waitKey: Int?) = takeoverAction { it.handBack(waitKey) }
    fun finishedMyself(waitKey: Int?) = takeoverAction { it.finishedMyself(waitKey) }
    fun skip(waitKey: Int?) = takeoverAction { it.skip(waitKey) }
    fun putAside(waitKey: Int?) = takeoverAction { it.putAside(waitKey) }

    /** 浮层上的声音开关:恢复过声音的再按一次静回去,没恢复的按一次恢复(重复点击重试真实动作);没有手机侧或目标 App 时不动。 */
    fun togglePipSound() = phoneRuntime.togglePipSound()

    /** 回答提问;已经不在等(超时后才答、已答过)时调 [tooLate] 告诉用户这条回答没用上。 */
    fun answer(text: String, tooLate: () -> Unit) = runControl.answer(text, tooLate)

    /** 渠道已经核对过提问编号；仍由同一个收件箱回答，避免绕过运行生命周期。 */
    suspend fun answerFromChannel(text: String, askId: String): Boolean = runControl.answerFromChannel(text, askId)

    /** 新指令取代尚未回答的普通提问；旧任务按取消收尾，排队的新指令随后执行。 */
    fun cancelAsk(askId: String): Boolean = runControl.cancelAsk(askId)

    /** 同一对话在任务进行中的新原话，交给下一次模型步；准备阶段也会先排在 Inbox。 */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) = steerCoordinator.steer(text, messageId)

    /** 远程追加只归当前任务;与状态发布互斥,收件箱再原子核对是否已经结束/取消。 */
    fun steerRemote(text: String): String? = steerCoordinator.steerRemote(text)

    /** 撤回手机上插的、模型还没取走的那句;已取走、已收尾或是电脑追加的(收件箱拒绝)返回 false,去向不变。 */
    fun withdrawSteer(messageId: String): Boolean = steerCoordinator.withdrawSteer(messageId)

    /** 手机插话可转下一任务;远程插话只能用于当前任务,未消费的一律作废,不改变来源和权限。 */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed) = steerCoordinator.handoffUnconsumed(cancelled)

    /** 这次任务真的用过的能力组(手机、网页、沙箱);记进这一轮,下一轮开局预先展开。 */
    @Volatile var usedGroups: List<String> = emptyList()
        private set

    fun joinedSteers(): List<String> = steerCoordinator.joinedSteers()

    /** 和 [joinedSteers] 一一对应:每句插话打断的那段回复,没打断的是空串。 */
    fun joinedInterrupted(): List<String> = steerCoordinator.joinedInterrupted()

    /** 和 [joinedSteers] 一一对应:每句插话交给模型之前已经说完的那段回复,没有的是空串。 */
    fun joinedFinished(): List<String> = steerCoordinator.joinedFinished()

    /** 运行事件里界面要跟的两样:实际做过的事,和插话已交给模型。 */
    internal val liveEvents = object : RunSink {
        override fun event(e: RunEvent) {
            when {
                e is RunEvent.Activity -> synchronized(service.taskState.shownLock) {
                    val n = synchronized(runState.activityLog) { runState.activityLog += e; runState.activityLog.size }
                    runState._activities.value = runState.logView(n)
                }
                e is RunEvent.State && e.name == "reply_interrupted" -> steerCoordinator.onInterrupted(e.detail)
                e is RunEvent.State && e.name == "reply_finished_before_steer" -> steerCoordinator.onFinished(e.detail)
                e is RunEvent.State && e.name == "steer_received" -> steerCoordinator.onSteerReceived(e.detail)
            }
        }
    }

    /** 通知和卡片的按钮都带着它所属的申报 [key];点在已经换掉的旧通知、旧卡片上不算数。 */
    fun confirm(choice: ConfirmChoice, key: Int?) = runControl.confirm(choice, key)

    /**
     * 任务的终态:[execute] 收尾之后才写,不经 [publish]。不论显示的是不是自己,都先把本次的运行编号记到
     * 自己对话名下([TaskService.endedRunIds]),界面收到结束状态时按对话取;显示中时也写 [TaskService.endedRunId]。
     */
    fun end(end: TaskState) = runControl.end(end)

    /** 超出并行上限在排队;放出来开跑时 [dequeue]。 */
    fun queue(position: Int) = runControl.queue(position)
    fun dequeue() = runControl.dequeue()

    /** 本任务的沙箱:一个任务一份(导出额度按任务算),工作区是本对话的。 */
    internal val sandbox by lazy { AndroidSandbox(service, SandboxState.get(service), onShared = ::addShared) }

    internal val audioSettings by lazy { com.wangzan101.mika.settings.VoiceSettings.get(service) }

    /** 未迁移的任务保持原有的一次性端口决定;只有迁移后的目录选择需要动态刷新。 */
    private val legacyTranscribePort by lazy {
        val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(service)
        val choice = com.wangzan101.mika.voice.SpeechSettings.get(service).choice.value as? com.wangzan101.mika.voice.SpeechChoice.Provider
            ?: return@lazy null
        ProviderStore.get(service).provider(choice.providerId) ?: return@lazy null
        com.wangzan101.mika.media.AndroidTranscribePort(service, conversationId, choice, voiceModels.stt) { workspaceKey }
    }

    /** 迁移后的转写端口:目录与执行都按当前批量 STT 决定,配置失效只让转写工具不可用。 */
    internal val transcribePort: com.wangzan101.mika.core.cap.transcribe.TranscribePort?
        get() {
            if (provenance.origin != RunOrigin.Ui) return null
            val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(service)
            return taskTranscribePort(
                provenance.origin,
                voiceModels.stt,
                legacyPort = { legacyTranscribePort },
                migratedPort = {
                    com.wangzan101.mika.media.AndroidTranscribePort(service, conversationId, null, voiceModels.stt) { workspaceKey }
                },
            )
        }

    /**
     * 本任务的音频端口:用户配置并打开了声音模型、且这是手机上开始的任务时才建。
     * 定时、远程、子任务、条件检查没有它,音频工具也就不会发给模型。
     */
    internal val imageSettings by lazy { com.wangzan101.mika.settings.ImageModelSettings.get(service) }

    /** 本任务的图片端口:用户配置并打开了图片模型、且这是手机上开始的任务时才建。 */
    internal val imagePort by lazy {
        val state = imageSettings.state.value
        val choice = state.choice
        if (!state.enabled || choice == null || provenance.origin != RunOrigin.Ui) null
        else imageSettings.capabilities()?.let { caps ->
            com.wangzan101.mika.settings.ProviderStore.get(service).imageModel(choice, caps)?.let { model ->
                com.wangzan101.mika.media.AndroidImagePort(service, conversationId, model, caps, onGenerated = runState::addPhoto, onSaved = runState::addSaved)
            }
        }
    }

    /** 把工作区里的一张图复制成别的 App 可读的 content URI(分享用);越界、不是图片、太大时为 null。 */
    internal suspend fun shareWorkspaceImage(path: String): com.wangzan101.mika.media.ShareableFile? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = path.trim()
        val prefix = com.wangzan101.mika.core.workspace.SandboxPaths.WORKSPACE + "/"
        if (!trimmed.startsWith(prefix)) return@withContext null
        val rel = trimmed.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return@withContext null
        val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(service)
        val bytes = dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(service, conversationId)), rel).use { g ->
                if (g.size > 20L * 1024 * 1024) null else g.read(0, g.size.toInt())
            }
        } ?: return@withContext null
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val mime = opts.outMimeType?.takeIf { it.startsWith("image/") } ?: return@withContext null
        com.wangzan101.mika.media.ShareFileExporter(service).save(bytes, mime, trimmed.substringAfterLast('/'))
    }

    internal val audioPort by lazy {
        val state = audioSettings.state.value
        if (!state.enabled || provenance.origin != RunOrigin.Ui) null
        else {
            val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(service)
            if (voiceModels.tts.migrated) migratedAudioPort(voiceModels, state) else legacyAudioPort(state)
        }
    }

    private fun legacyAudioPort(state: com.wangzan101.mika.settings.VoiceSettingsState): com.wangzan101.mika.media.AndroidAudioPort? {
        val choice = state.choice ?: return null
        return audioSettings.capabilities()?.let { caps ->
            com.wangzan101.mika.settings.ProviderStore.get(service).ttsModel(choice, caps)?.let { model ->
                buildAudioPort(model, caps, choice.voice, choice.speed)
            }
        }
    }

    private fun migratedAudioPort(
        voiceModels: com.wangzan101.mika.voice.model.VoiceModelEnvironment,
        state: com.wangzan101.mika.settings.VoiceSettingsState,
    ): com.wangzan101.mika.media.AndroidAudioPort? {
        val ref = voiceModels.tts.current() ?: return null
        return try {
            val protocol = voiceModels.tts.requireModel(ref).model.tts?.protocol
                ?.let { it.settingsProtocol() } ?: return null
            val caps = audioSettings.capabilities(protocol)
            val model = voiceModels.tts.createTts(service, ref, caps)
            buildAudioPort(model, caps, state.voice, state.speed)
        } catch (_: com.wangzan101.mika.voice.model.VoiceModelError) {
            null
        }
    }

    private fun buildAudioPort(
        model: com.wangzan101.mika.core.model.TtsModel,
        caps: com.wangzan101.mika.core.model.TtsCapabilities,
        defaultVoice: String,
        defaultSpeed: Float,
    ): com.wangzan101.mika.media.AndroidAudioPort =
        com.wangzan101.mika.media.AndroidAudioPort(
            service, conversationId, model, caps, onSaved = ::addSavedAudio,
            onGenerated = ::addAudio, defaultVoice = defaultVoice.ifBlank { null }, defaultSpeed = defaultSpeed,
            onSpeak = { response -> (service.application as com.wangzan101.mika.MikaApp).voicePlayer.play(response.bytes, response.mime) },
            resolveWorkspace = { workspaceKey },
        )

    /** 沙箱导出了一个文件(share_file 或 `mika share`)。 */
    fun addShared(f: SharedFile) = runState.addShared(f)

    /** 音频能力生成了一段音频(写进工作区时由端口回调)。 */
    fun addAudio(a: com.wangzan101.mika.core.chat.GeneratedAudio) = runState.addAudio(a)

    /** 音频能力把一段音频存进了音乐库:当作这一轮导出的文件,聊天里出现卡片。 */
    fun addSavedAudio(s: com.wangzan101.mika.core.model.SavedAudio) = runState.addSavedAudio(s)

    fun addSaved(s: com.wangzan101.mika.core.cap.image.SavedImage) = runState.addSaved(s)

    fun clearTakeoverStage() = runState.clearTakeoverStage()

    /** 此刻要显示的状态;已收尾时为空,不再重画通知。 */
    fun shownState(): TaskState? = runState.shownState()

    /** 因模型失败、主模型在它那家还没测试通过时,提示去那家「重新测试」(见 [RetestHint])。测试换了主模型时不看设置里的配置。 */
    private fun withRetestHint(end: TaskState): TaskState {
        if (end !is TaskState.Failed || env.modelFactory !== TaskEnvironment.defaultModelFactory) return end
        return RetestHint.apply(end, ProviderStore.get(service).untestedMainProvider()) { tr(R.string.fail_model_untested, it) }
    }

    /** execute 收尾;拆成独立函数以缩小 execute 的 suspend 状态机(超大 suspend 函数会被 ART 校验器拒绝)。 */
    private suspend fun cleanup(c: RunAssembly) {
        withContext(NonCancellable) { manualControl.close() }
        manualTarget = null
        manualTargetNow = null
        activePhoneDisplay = null
        if (resultReadyAtMs == 0L) resultReadyAtMs = SystemClock.elapsedRealtime()
        handoffUnconsumed()
        c.side.cancel()
        runState._frames.value = null
        runState._phoneFrames.value = null
        runState._webOpen.value = false
        synchronized(runState.publishLock) { runState.ended = true }
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
        runState._displayId.value = -1
        runState._pipInput.value = null
        runState._pipSoundOn.value = false
        runState._appShown.value = false
        if (c.connection != null) phoneServices.sharedShell.release()
        // 这次运行解锁过手机才挂着回锁义务;还有别的任务在跑就等,都结束了在这里锁回去(没有义务时立即返回)
        withContext(Dispatchers.Main + NonCancellable) { service.finishTaskRelock(this@TaskRun) }
    }

    suspend fun execute(priorTurns: List<Turn>): TaskState {
        noticeTainted = NoticeTurn.tainted(priorTurns)
        phone = null
        val a = RunAssembly(
            traces = (service.application as MikaApp).detailedTraces,
            // 整个运行共用一份已报告 Token 预算；子任务沿用协程上下文里的这份，不另开额度。
            tokenBudget = if (conditionCheck) null else FeatureFlags.get(service).taskTokenBudget(),
            showCost = FeatureFlags.get(service).showTaskCost(),
            approvalControl = (service.application as MikaApp).approvalControl,
            // 任务期间的旁路协程(助手连接状态、静音与挪走监视、状态发布),任务结束一并取消
            side = CoroutineScope(
                scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
                    CoroutineExceptionHandler { _, e -> Log.w(TAG, "task side job failed", e) },
            ),
        )
        sideScope = a.side
        try {
            // 用户在「干活时」关掉了常亮就不拿唤醒锁;开跑就按此刻的状态(准备中)拿上:
            // 开模型、读知识、选路线、判断意图这些第一步之前的准备和推理期间屏幕也不熄
            awake = if (FeatureFlags.get(service).keepScreenOn()) KeepAwake(service.wakeLock.lease(), a.side).also { it.onState(runState._state.value) } else null
            // 前一个任务替这个拿着的那份,自己的拿上了就放开
            wakeHandoff?.release()
            wakeHandoff = null
            val m = assembleModels() ?: return TaskState.Failed(tr(R.string.fail_no_main_model), FailKind.Environment)
            val ph = assemblePhone(a)
            val taskGate = assembleGate(ph)
            val rec = assembleRecord(a)
            val mem = assembleMemory(m, ph)
            // 系统里没有 WebView 时没有网页工具
            val web = assembleWeb(a, ph)
            if (conditionCheck && web == null) return TaskState.Failed(tr(R.string.fail_no_web), FailKind.Environment)
            lendPage(a, m, ph, web)
            val pr = assemblePrompt(a, priorTurns)
            val t = assembleTools(a, m, ph, mem, rec, pr, web)
            val agent = assembleAgent(a, m, ph, taskGate, mem, rec, pr, web, t, priorTurns)
            assembleTakeover(a, agent, t)
            val end = withContext(a.tally) { agent.run(pr.runSpec, inbox) }
            resultReadyAtMs = SystemClock.elapsedRealtime()
            return end.also { manualControl.awaitAutomatic(); usedGroups = agent.usedGroups.toList() }.let(::localizeTaskFailure).let(::withRetestHint)
        } finally {
            cleanup(a)
        }
    }

    @Volatile internal var handoverRef: Takeover? = null
    @Volatile private var sideScope: CoroutineScope? = null

    /** 任务收尾后判结局、学东西要用的;[execute] 里填上。 */
    @Volatile internal var kit: LearnKit? = null

    /** 停下时正在写的那段字(模型请求已流出的可见文字);收尾前取,收尾后状态就换成终态了。 */
    fun draftText(): String? = runState.draftText()

    /** 停止后有没有可交代的:做过至少一件事,或停下时已经写出了字。 */
    fun hasStopMaterial(draft: String?): Boolean = runState.hasStopMaterial(draft)

    /** 真正失败后的断点交代:只按记录拼,不再请模型,也不把短失败原因改写成成功。 */
    suspend fun checkpointSummary(): String? = runTeardown.checkpointSummary()

    /**
     * 用户停止之后:按这次已经做了的事写几行交代(见 [StopSummary]),只读运行记录,不再操作任何东西。
     * 轻量模型优先,没配用主模型;没模型、出错、超时就按记录直接拼。没什么可交代的为 null。
     */
    suspend fun stopSummary(draft: String?): String? = runTeardown.stopSummary(draft)

    /**
     * 结果交给用户之后调用(不拖慢、不改交出的回答):回答按用户的要求判结局([OutcomeJudge],轻量模型优先),
     * 记进运行记录和这一轮对话;只有办成的([LearnGate])才记路线、给回放的路线记成功。
     * 放弃、失败、取消的不判(结局已经清楚),返回 null;判不成是 [RunOutcome.Unknown]。
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? = runTeardown.settleOutcome(end, conversations)

    /**
     * 结果通知发出之后调用:记下用到的个人技能的结果、提取记忆、起草技能,提示挂到这一轮的结果卡片上并存进对话。
     * 在进程级的作用域里跑,服务退出也不中断。
     */
    /** 开工时这段对话里有没有通知提醒(见 [NoticeTurn]);[execute] 一开始按存着的全部轮次定。 */
    @Volatile internal var noticeTainted = false

    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) = runTeardown.learnAfter(end, outcome, conversations)

    internal fun publish(state: TaskState) {
        // 被挪走后到 gate 拦下之前,循环可能还在推理:对外先显示暂停
        // 循环自己报的终态不往外发:终态只由 [end] 在记好运行编号之后写,否则界面先收到结束、拿不到编号
        if (!TaskService.loopMayPublish(state)) return
        val shown = when {
            state is TaskState.Running && manualControl.state.value in setOf(com.wangzan101.mika.core.run.ManualControl.State.Requesting, com.wangzan101.mika.core.run.ManualControl.State.Manual) ->
                TaskState.Held(TaskGate.paused(tr(R.string.gate_user_on_page)))
            state is TaskState.Running && gate?.movedAwayPending == true -> TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY))
            // 用户在借出的页面上操作:到关卡之前也先显示暂停
            state is TaskState.Running && gate?.userOnPagePending == true -> TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))
            else -> state
        }
        synchronized(runState.publishLock) {
            if (runState.ended) return
            runState.setState(shown)
            awake?.onState(state)
        }
        service.refreshNotifications(this)
    }

    /** 网页接管界面顶部的说明:接管原因的话,付款时再带上商品、金额、地址。 */
    internal fun takeoverMessage(state: TaskState): String? = runControl.takeoverMessage(state)

    internal companion object {
        /** 网页界面时画中画取画面的间隔。 */
        const val WEB_FRAME_MS = 500L
        const val SHELL_WAIT_MS = 15_000L
        const val DISPLAY_RETRY_MS = 1_000L
        val WAITING_DISPLAY: String get() = tr(R.string.task_waiting_display)
        const val A11Y_WAIT_MS = 5_000L
        val NO_PHONE_HELPER: String get() = tr(R.string.task_no_phone_helper)
        val NO_PHONE_HELPER_USB: String get() = tr(R.string.task_no_phone_helper_usb)
        val NO_PHONE_HELPER_SHIZUKU: String get() = tr(R.string.task_no_phone_helper_shizuku)
        const val TAG = "MikaTask"
    }
}
