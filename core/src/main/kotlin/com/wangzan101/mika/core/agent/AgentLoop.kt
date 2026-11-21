package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.observation.tag
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.PromptVersion
import com.wangzan101.mika.core.tool.parseArgs
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.FollowUp
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Presented
import com.wangzan101.mika.core.action.Refusals
import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.knowledge.ChannelAdvisor
import com.wangzan101.mika.core.knowledge.ChannelFacts
import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.MemoryContext
import com.wangzan101.mika.core.memory.ProfileContext
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.knowledge.toolsNote
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.LocalInteraction
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.usage.RunUsageTally
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.AskPort
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteAnchors
import com.wangzan101.mika.core.screenmap.ScreenMapRender
import com.wangzan101.mika.core.screenmap.ScreenMapStore
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.verify.Verifier
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId
import com.wangzan101.mika.core.userscreen.UserScreenSource
import com.wangzan101.mika.core.general.HISTORY_READ
import com.wangzan101.mika.core.general.VIEW_USER_SCREEN

/** 批做完后另看的一眼:内容和画面同一时刻。 */
private fun batchViewNote(content: String) = Feedback.format("batch_view_note", "screen" to content)

/** 进度块取出后，其余文字保持一条助手消息；调用及其私有数据原样保留。 */
private fun cleanReplyParts(parts: List<Part>, text: String): List<Part> {
    var textWritten = false
    return parts.mapNotNull { part ->
        if (part !is Part.Text) part else if (textWritten) null else {
            textWritten = true
            Part.Text(text).takeIf { text.isNotEmpty() }
        }
    }
}

/**
 * 写、改 Mika 自己的记忆不先问「是讨论还是要操作」。那个判断管的是要不要去读、去动 App 和网站;这两个工具只动 Mika 自己的记忆,
 * 各有校验(引文必须出自用户这一轮的话,改删还要话里明说了要改、要删,见 MemoryTools),改动在结果卡上可以撤销。
 * 交给它判的话,「总结一下写进记忆」「把那几条改得简洁一点」这样的话在真机上都被判成了讨论:调用被丢掉、改成不带工具重问,
 * 模型只好把调用写成文字,任务以「工具调用没被识别」结束。
 * 「如果我让你把那条删了,你会怎么做」这种只是问问的,由 memory_edit 自己拒(见 MemoryEdit 里的 onlyAsking)。
 */
private val MEMORY_WRITE_VERBS = setOf(Verb.MemorySave, Verb.MemoryEdit)
private val MEMORY_VERBS = setOf(Verb.MemorySearch, Verb.MemorySave, Verb.MemoryEdit)
/** 讨论历史时仍可查本地记忆与技能资料、之前几轮的记录；这些调用不会打开 App 或网站。 */
private val DISCUSSION_LOOKUP_VERBS = setOf(Verb.MemorySearch, Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.HistoryRead, Verb.NoticesRead)

/**
 * 驱动“模型 ↔ 工具”的循环,只做调度:问模型([ModelCaller])→ 交执行入口([ActionExecutor])→
 * 按策略([TurnPolicy]、[StopRules]、[StuckAdvice]、[NoticePolicy])决定提醒、建议与结局 → 写进历史。
 * 结局:不带工具调用的回复就是给用户的最终回答(核对见 [AnswerReview],来源由代码从证据账本里找);
 * 随调用写的文字是进度说明([TaskState.Running.narration])。做不成、要问用户、要用户亲手做,各走 end_not_done、
 * ask_user、hand_to_user。只有空回复(或被输出上限截断的回复)会被提醒,而且只提醒一次。
 * 每轮只执行第一个工具调用,写操作前过 [gate](能力的前置条件不满足时挂起,如手机锁屏),守卫结论放进工具结果的 guard 字段。
 * gate 挂起前用 setWaiting 报告原因(能力给的 [TaskState.Held]),放行时传 null;
 * 等待期间对外显示该状态,结束后恢复 Running。
 * 运行过程以 [RunEvent] 交给 [sink](已脱敏、大字段截短),存下的画面经 [RunSink.media] 交出。
 */
class AgentLoop(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val basePrompt: String,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val gate: suspend (setWaiting: (TaskState?) -> Unit) -> Unit = {},
    private val knowledge: KnowledgeStore? = null,
    /** 各 App 的屏图(页面结构);为 null 时不注入。进入有图的 App 时随 APP.md 一起给。 */
    private val screenMaps: ScreenMapStore? = null,
    sink: RunSink = RunSink.NONE,
    /** 已安装 App(包名, 显示名),只用于按任务文字挑参考资料;取不到时按空列表处理。 */
    private val installedApps: suspend () -> List<Pair<String, String>> = { emptyList() },
    /** 最终回答交出前、按风险才请的独立核查(见 [AnswerReview]);为 null 时只做字面核对(产品路径必须给)。 */
    private val verifier: Verifier? = null,
    /** 付款的确认关卡；自主执行路径传 null，也不向模型提供 request_confirmation。 */
    private val confirmations: ConfirmationGate? = ConfirmationGate(),
    /** 询问 / 自主(中途换成自主时从下一步起照它);默认询问。 */
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    /** 等用户回答确认的时限,到时按不同意;为 null 时一直等(测试)。 */
    confirmTimeoutMs: Long? = null,
    /** Mika 自己的包名:作用在它上面的写操作不做。 */
    private val selfPackage: String? = null,
    /** 追问(ask_user 与开工前的歧义问题)等用户回答的时限;计时走协程调度器的时钟,测试里用虚拟时间。 */
    inputTimeoutMs: Long = DEFAULT_INPUT_TIMEOUT_MS,
    /** 选渠道用的事实;给了就在任务开头注入一行「可用渠道」。 */
    private val channelFacts: (suspend () -> ChannelFacts)? = null,
    /** 提速开关;默认全关。 */
    val flags: SpeedFlags = SpeedFlags.OFF,
    /** 事件时间与耗时用的钟;测试里换成假时钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 大小模型分工;只在 flags.lightSteps 打开时用。 */
    router: ModelRouter? = null,
    /** UI 对话第一次要用工具时的独立意图判断；null 保留评测与其他入口的原行为。 */
    private val intentJudge: ChatModel? = null,
    /** 可选的类型化意图判断;不确定或失败时仍问 intentJudge,不替代主模型与执行关卡。 */
    intentDecision: com.wangzan101.mika.core.decision.DecisionModel? = null,
    /** 经验回放的路线匹配;只在 flags.replay 打开时用。 */
    routes: RouteMatcher? = null,
    /** 技能目录(含个人技能);任务开始时列出全部技能,并按目标给几条 App/站点知识的索引。为 null 或 [recommendSkills] 关着时不给索引。 */
    private val skills: GuideCatalog? = null,
    private val recommendSkills: Boolean = true,
    /** 记忆;为 null(或总开关关)时不检索、不注入、不提供 memory_* 工具。 */
    private val memory: MemoryStore? = null,
    /** 运行时状态里的本地时间按这个时区写。 */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 模型调用遇到一时的故障(限流、5xx、网络)时的重试。 */
    retry: RetryPolicy = RetryPolicy(),
    /** 不带调用的回复收下当最终回答之前问一次(见 [BeforeAccept]);默认照收。步数用完后的最后一轮不问。 */
    private val beforeAccept: suspend (answer: String) -> BeforeAccept = { BeforeAccept.Accept },
    private val interaction: Interaction = LocalInteraction,
    private val profileSnapshot: List<ProfileEntry> = emptyList(),
    private val autonomous: Boolean = false,
    /** 主动能力从通知里攒下、够格带进这次任务的那一段(见 [com.wangzan101.mika.core.proactive.ObservedContext]);没有为 null。 */
    private val observed: String? = null,
    /** 交互界面开启可见文字流；评测和后台调用保持原请求格式。 */
    private val streamReplies: Boolean = false,
    /** 这个对话里用户发来的屏幕(见 [UserScreenTurns]);没有时带屏幕的那一行原样给模型。 */
    private val userScreens: UserScreenSource? = null,
    /** 评测可保留旧收尾核查；交互产品由执行过程观察结果，收尾只整理来源。 */
    private val reviewFinalAnswer: Boolean = true,
    /** 同一网站、App 里连着做对外动作(发送、提交)时两次之间的间隔;默认不等。 */
    private val outwardPacing: OutwardPacing = OutwardPacing.Off,
    /** 主模型的备援链；[modelChainProviderIds] 与主模型和备援模型平行。 */
    private val modelFallbacks: List<ChatModel> = emptyList(),
    private val modelChainProviderIds: List<String?> = emptyList(),
    private val trace: RunTrace? = null,
    /** 插件提示词钩子:拼好的系统提示词发模型前过它;为 null 时原样发。 */
    private val promptTransform: (suspend (String) -> String)? = null,
    /** 这一轮用的人格(名字 · 修订号);只写进运行记录,不参与提示词拼装。没有人格为 null。 */
    private val personaLabel: String? = null,
    private val stages: TaskStages? = null,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        // 只有开着回放才在写操作前取目标元素与锚点
        tools.captureTargets = flags.replay
        tools.observeMcpCalls(::showMcpCall)
    }

    /** MCP 调用进行中把 Running 标上连接器名,结束(含失败、取消)时收回;返回的闭包由调用方在 finally 里调。 */
    private fun showMcpCall(connector: String): () -> Unit {
        val before = _state.value
        if (before !is TaskState.Running) return {}
        val calling = before.copy(mcpConnector = connector)
        _state.compareAndSet(before, calling)
        return { _state.compareAndSet(calling, before) }
    }

    /** 运行记录:按因果先后写(见 [RecordOrder])。 */
    val events = RunEventBus().also { bus ->
        bus.subscribe(sink)
        trace?.let { bus.subscribe(it) }
    }
    private val sink = RecordOrder(events).also { order ->
        // 记进证据账本的每条观察原样进运行记录(跟在读到它的那次调用的结果后面)
        tools.observer = { record { order.observed(RunEvent.Observed.of(it)) } }
    }

    private val caller = ModelCaller(model, tools, router, flags, retry, ::emit, ::now, autonomous, trace, modelFallbacks, modelChainProviderIds)
    private val intentGate = intentJudge?.let {
        IntentGate(it, model.takeUnless { main -> main === it }, intentDecision) { detail ->
            emit(RunEvent.State(now(), "jev_intent", detail))
        }
    }
    private val routeMatcher = routes
    /** 本任务的上下文生成器(外壳、预算、已给过的参考资料)。 */
    private var ctx = ContextBuilder()
    /** 本任务里用户发来的屏幕(开局时建)。 */
    private var screens: UserScreenTurns? = null
    /** 这次的原话给模型时的样子(带着屏幕时换成了说明,见 [UserScreenTurns]);还没开始为 null。判结局、提取记忆按它读。 */
    @Volatile var taskText: String? = null
        private set
    /**
     * 用户最近一句话(开局原话或插话)里他这次打的字,见 [ownWords];插话里没打字(只发了屏幕、只选了字)时沿用上一句的。
     * 语言提示和回答的语言检查按它;记忆、技能的引文收的是 [UserWords.said](还认他引的自己说过的话)。
     */
    private var latestWords = ""

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    @Volatile private var usedMemory: List<String> = emptyList()
    /** 本次任务记忆块里列出的条目编号;任务结束后读取。 */
    val usedMemoryIds: List<String> get() = usedMemory

    /** 这次任务开工时带没带「从通知里得来的」那一段;任务结束后读取(带了就不自动提取记忆,不靠读运行记录也知道)。 */
    val carriedObserved: Boolean get() = observed != null

    /** 本次任务真的用过的能力组(记进这一轮,下一轮开局预先展开);任务结束后读取。 */
    val usedGroups: Set<String> get() = tools.used

    /** 用户本次说的话(memory_save、skill_save 的引文校验用);给了就收集,只在内存里、每个任务开始清空。 */
    private val utterances get() = tools.utterances

    private val user = UserExchange(tools, confirmations, confirmTimeoutMs, inputTimeoutMs, { utterances }, { _state.value = it }, ::emit, ::now)
    /** 自身工具的当次同意:复用等用户回答的那条路,同一次工具调用里等,不分成两步。 */
    private val askPort = object : AskPort {
        override suspend fun awaitAnswer(prompt: String, options: List<String>?) = user.awaitAnswer(prompt, options)
    }
    init { tools.ctx.ask = askPort }
    private val replay = routes?.let { RouteReplay(it, tools, ::awaitGate, { r -> gateState = r; _state.value = r }, ::emit, clock) }

    @Volatile private var job: Job? = null
    /** 锁屏/被拿走关卡等完之后恢复的界面状态(当前这一步、批或回放)。 */
    @Volatile private var gateState: TaskState = TaskState.Idle
    @Volatile private var cancelRequested = false
    /** 被插话撤掉请求的那一步;之后迟到的流式文字不再显示。 */
    @Volatile private var interruptedStep = 0
    /** 本对话之前几轮用户说的话,只给核查器理解追问;不含干活模型的任何东西。 */
    private var earlierGoals: List<String> = emptyList()
    /** 本次运行的结局;判过结局后路线计数要用([noteOutcome])。 */
    @Volatile private var lastEnd: TaskState? = null
    private val review = AnswerReview(tools, verifier, ::emit, ::now)
    /** 本任务每一步存下的画面编号(步 → 编号),回答的来源按它附上画面。 */
    private val media = mutableMapOf<Int, MutableList<Int>>()
    /** 本任务的回答已因核对没对上退回过一次;再没对上就标成未确认交出,不再退回。 */
    private var gapsNudged = false
    private var provenance = RunProvenance()
    val activeAsk: Delivery.Ask? get() = user.activeAsk

    /** 新入口:同一对话里的插话和控制经 [inbox] 进入;旧入口继续使用相同的执行路径。 */
    suspend fun run(spec: RunSpec, inbox: RunInbox): TaskState {
        suspend fun start() = runInternal(spec.goal, spec.priorTurns, inbox, spec.provenance, spec.threadId, spec.tokenBudget)
        // App 在整个运行外层装好共享 tally,子任务也沿用;直接走核心入口时由这里保证 RunSpec 的预算生效。
        if (spec.tokenBudget == null || currentCoroutineContext()[RunUsageTally] != null) return start()
        return withContext(RunUsageTally(spec.tokenBudget)) { start() }
    }

    suspend fun run(task: String, priorTurns: List<Turn> = emptyList()): TaskState =
        runInternal(task, priorTurns, null, RunProvenance(), "local")

    private suspend fun runInternal(
        task: String, priorTurns: List<Turn>, inbox: RunInbox?, runProvenance: RunProvenance, threadId: String,
        tokenBudget: Long? = null,
    ): TaskState {
        cancelRequested = false
        provenance = runProvenance
        user.begin(threadId, runProvenance, interaction)
        inbox?.bind(object : RunInbox.Handler {
            override suspend fun answer(text: String, askId: String?) = user.answerInput(text, askId)
            override suspend fun confirm(choice: ConfirmChoice, askId: String?) = user.answerConfirmation(choice, askId)
            override suspend fun handBack(result: HandBack, askId: String?) = user.resumeAfterTakeover(result, askId)
            override fun cancel() = this@AgentLoop.cancel()
        })
        caller.reset(basePrompt)
        caller.promptTransform = promptTransform
        // 同意只在一次任务内有效
        confirmations?.reset()
        utterances?.clear()
        emit(RunEvent.TaskStarted(now(), Redactor.text(task), model.name,
            origin = runProvenance.origin.takeIf { it != RunOrigin.Ui }?.name?.lowercase(),
            stepLimit = maxSteps.takeIf { it != Int.MAX_VALUE }, tokenBudget = tokenBudget))
        // 人格在 TaskStarted 之后记,时间线按「开始时用了谁」读;没有人格不写
        personaLabel?.let { emit(RunEvent.State(now(), "persona", it)) }
        var end: TaskState? = null
        replay?.reset()
        lastEnd = null
        try {
            end = runLoop(task, priorTurns, inbox)
            lastEnd = end
            return end
        } catch (e: CancellationException) {
            end = TaskState.Cancelled
            throw e
        } finally {
            emit(ended(end))
            inbox?.close()
        }
    }

    private suspend fun runLoop(task: String, priorTurns: List<Turn>, inbox: RunInbox?): TaskState {
        val end = try {
            coroutineScope {
                // 插话的信号装在整个运行的上下文里:撤掉也不丢东西的等待据此提前结束(见 waitOrSteer)
                val body = async(inbox?.signal ?: EmptyCoroutineContext) { loop(task, priorTurns, inbox) }
                job = body
                if (cancelRequested) body.cancel()
                body.await()
            }
        } catch (e: CancellationException) {
            if (!cancelRequested) throw e
            TaskState.Cancelled
        } finally {
            job = null
            user.clear()
            confirmations?.reset()
        }
        _state.value = end
        return end
    }

    /** 写操作前过 [gate]:挂起期间对外显示等待原因,结束后恢复 [gateState]。 */
    private suspend fun awaitGate() {
        try {
            gate { waiting ->
                _state.value = waiting ?: gateState
                if (waiting != null) emit(waitingEvent(waiting))
            }
        } finally {
            _state.value = gateState
        }
    }

    /**
     * 交出的回答判过结局([OutcomeJudge])之后调用:回放走完的路线只在办成([LearnGate])时记一次成功。
     * 失败、取消的任务不判也不用调用;不调用就不记成功。
     */
    suspend fun noteOutcome(outcome: RunOutcome) {
        val end = lastEnd ?: return
        replay?.noteResult(end, outcome)
    }

    /** 交回接管的结局;结局作为 hand_to_user 的工具结果交给模型。 */
    suspend fun resumeAfterTakeover(result: HandBack) = user.resumeAfterTakeover(result)

    /** 旧的交回方式:一律算 complete;[note] 为 [USER_FINISHED_NOTE] 时表示用户说已自己办完。 */
    suspend fun resumeAfterTakeover(note: String) =
        resumeAfterTakeover(HandBack(HandBackOutcome.complete, userFinished = note == USER_FINISHED_NOTE))

    /** 回答追问;没有在等的问题(已超时、已答过)时返回 false,由界面告诉用户回答来晚了。 */
    suspend fun answerInput(text: String): Boolean = user.answerInput(text)

    fun cancelInput(askId: String): Boolean = user.cancelInput(askId)

    suspend fun answerConfirmation(approved: Boolean) = answerConfirmation(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny)

    suspend fun answerConfirmation(choice: ConfirmChoice) = user.answerConfirmation(choice)

    fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    private suspend fun prepareOpening(raw: String, rawPrior: List<Turn>): LoopOpening {
        // 用户发来的屏幕:这一句给全(那句话、截图、详情),之前几轮里的收成一行;下面都用换好的这句
        val shots = UserScreenTurns(userScreens, zone, tools.seesImages, canAskVision = !tools.seesImages && tools.vision != null && tools.definitions().any { it.name == VIEW_USER_SCREEN },
            image = { tools.modelImage(it, null) }, seen = tools::noteUserScreen).also { screens = it }
        user.answerText = shots::asText
        val opening = shots.render(raw)
        val task = opening?.said ?: raw
        taskText = task
        latestWords = ownWords(raw, opening)
        val priorTurns = shots.collapse(rawPrior)
        // 通知提醒那一轮没有用户的话,不算进「之前几轮用户说的话」
        earlierGoals = priorTurns.filterNot(NoticeTurn::isNotice).map { it.userText }
        val context = Conversation.context(priorTurns)
        // 仅「上一轮用户明确确认 + 再上一轮助手建议/提问」可供 memory_save 跨轮引用;更早轮次与助手原文不收。
        val confirmation = rawPrior.lastOrNull()
        val proposal = rawPrior.dropLast(1).lastOrNull()
        // 对话里有通知提醒(第三方文字)时不开这个例外:提醒那一轮就是以问句收尾的,用户回一句「好的」不能成为引用通知内容的依据。
        // 看的是存着的全部轮次:提醒隔了几轮之后,助手的回答里照样可能转述着通知。
        // 这次任务带着「从通知里得来的」那一段时也不开:上一轮的建议可能就是照着那一段说的,用户回一句「好的」
        // 不能让里面的事变成「用户说的」记忆。用户明说的(本轮原话里有的)照常能记。
        if (confirmation != null && proposal != null && !NoticeTurn.tainted(rawPrior) && observed == null) {
            utterances?.addPreviousConfirmation(UserWords.said(confirmation.userText, tools), proposal.outcome, proposal.status)
        }
        utterances?.add(UserWords.said(opening?.words ?: raw, tools))
        // 记忆块:对话上文之后、任务原文之前;用到的条目由代码记下
        usedMemory = emptyList()
        // 上次没读出记忆文件(密钥库一时不可用)就再读一次;还读不出就这次不带记忆
        // 产品路径用 Auto:记忆总量装得下就全量注入(不漏检索),装不下才退回检索
        val block = memory?.takeIf { it.reloadIfNeeded() }?.let {
            MemoryContext.build(it.items.value, task, priorTurns.lastOrNull()?.userText, tools.legacy::rewrite,
                mode = MemoryContext.Mode.Auto)
        }
        if (block?.message != null) {
            usedMemory = block.usedIds
            memory?.markUsed(block.usedIds)
            emit(RunEvent.MemoryUsed(now(), block.usedIds))
        }
        return LoopOpening(task, priorTurns, opening, context, block?.message)
    }

    private data class LoopOpening(
        val task: String,
        val priorTurns: List<Turn>,
        val screen: UserScreenTurns.Rendered?,
        val context: Message?,
        val memoryMessage: String?,
    )

    private data class LoopHistory(val messages: MutableList<Message>, val labels: Map<String, String>)

    private suspend fun prepareHistory(opening: LoopOpening): LoopHistory {
        val installed = try { installedApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val labels = installed.toMap()
        val matched = knowledge?.matchTask(opening.task, Int.MAX_VALUE, installed).orEmpty()
        stages?.prepare(latestWords, installed, matched)
        val index = if (skills != null && recommendSkills) GuideRecommender.index(skills, opening.task) else null
        val start = ctx.start(
            opening.task, priorTurns = opening.context?.let { (it.parts.single() as Part.Text).text }?.let { prior ->
                // 之前几轮读到的东西查得到时说一句(只在有记过运行的轮次、提供了 history_read 时)
                if (tools.definitions().any { it.name == HISTORY_READ }) Feedback.format("history_hint", "turns" to prior) else prior
            },
            memory = listOfNotNull(ProfileContext.render(profileSnapshot), opening.memoryMessage).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            observed = observed,
            channel = if (knowledge != null) channelLine(matched) else null,
            knowledge = matched, knowledgeNote = toolsNote(tools.scriptsOffered()), withLinks = flags.deepLinks,
            skillsIndex = index,
            taskImages = opening.screen?.images.orEmpty(), screens = opening.screen?.details.orEmpty(),
        )
        val history = start.messages.toMutableList()
        // 带了「从通知里得来的」那一段:记一笔(只记长度)。给了就算,不看最后放没放得下
        observed?.let { emit(RunEvent.State(now(), ObservedContext.RUN_STATE, it.length.toString())) }
        start.knowledge.filter { it.trial }.forEach { emit(RunEvent.State(now(), "knowledge_trial_used", it.name)) }
        // 链接只在 open_link 可用时给,且只放行真的给了模型的那几份
        if (flags.deepLinks) start.knowledge.forEach { if (it.links.isNotEmpty()) tools.allowLinks(it.links) }
        return LoopHistory(history, labels)
    }

    /** 当前给模型的工具单和它的系统提示词:展开新组时一起换,没展开就一字不变。 */
    private inner class ToolOffer {
        private var openGroups = tools.expanded
        private val extra = listOfNotNull(BatchRunner.spec(tools.batchable()).takeIf { flags.batchSteps })
        var current = emptyList<ToolSpec>()
            private set

        private fun keep(spec: ToolSpec): Boolean {
            val verb = tools.verb(spec.name)
            return (confirmations != null || verb != Verb.RequestConfirmation) && (memory != null || verb !in MEMORY_VERBS)
        }

        fun recordPromptVersion(): List<String> {
            // 提示词版本按每组都展开的样子算:同一版提示词不因这次展开了哪几组而不同
            val everything = tools.offeredNow(extra, all = true).filter(::keep)
            emit(RunEvent.PromptVersion(now(), PromptVersion.of(SystemPrompt.assemble(basePrompt, tools.promptSections(everything, all = true)), everything)))
            return everything.map { it.name }
        }

        // 每次问模型之前:展开了新的组时,工具和系统提示词跟着换;没展开就一字不变
        fun refresh() {
            val offer = tools.offeredNow(extra).filter(::keep)
            if (offer == current) return
            current = offer
            caller.systemPrompt = SystemPrompt.assemble(basePrompt, tools.promptSections(current))
            (tools.expanded - openGroups).forEach { emit(RunEvent.State(now(), "tools_opened", it)) }
            openGroups = tools.expanded
        }
    }

    private suspend fun loop(raw: String, rawPrior: List<Turn>, inbox: RunInbox?): TaskState {
        val opening = prepareOpening(raw, rawPrior)
        val task = opening.task
        // 开局预先展开:上一轮用过的组,和这次的话一定用得上的(见 ToolDispatcher.preload)
        tools.preload(task, opening.priorTurns.lastOrNull()?.groups.orEmpty()).forEach { emit(RunEvent.State(now(), "tools_preloaded", it)) }
        val offer = ToolOffer()
        val allToolNames = offer.recordPromptVersion()
        offer.refresh()
        val guards = OperationGuards(maxSteps)
        val recorder = ActionRecorder(sink, clock, saveScreen = { id, png -> sink.media(id, png) })
        val executor = ActionExecutor(tools, guards, confirmations, gate = ::awaitGate, recorder = recorder, offScreen = tools.offScreenGuard(), approvals = approvals,
            provenance = provenance,
            selfPackage = selfPackage, autonomous = autonomous, pacing = outwardPacing, onScreenAction = stages?.let { it::action })
        media.clear()
        gapsNudged = false
        interruptedStep = 0
        // 除用户原话以外的上下文都经它生成:统一外壳、统一预算
        ctx = ContextBuilder(zone = zone)
        val (history, labels) = prepareHistory(opening)
        return LoopSession(opening, offer, allToolNames, guards, recorder, executor, history, labels, inbox).run()
    }

    /** 一轮运行里模型轮次和工具执行共享的状态;每个挂起阶段自管自己的临时量。 */
    private inner class LoopSession(
        private val opening: LoopOpening,
        private val offer: ToolOffer,
        private val allToolNames: List<String>,
        private val guards: OperationGuards,
        private val recorder: ActionRecorder,
        private val executor: ActionExecutor,
        private val history: MutableList<Message>,
        private val labels: Map<String, String>,
        private val inbox: RunInbox?,
    ) {
        private val task = opening.task
        private var note = ""
        private var emptyNudged = false
        private var progressOnlyTurns = 0
        // 调过工具才有可核对的材料;交给用户付款和放行过对外动作一样,回答要另请核查
        private var acted = false
        private var paymentHandedOver = false
        // 用户回答了追问、还没做别的事:这时就要交回答,先提醒一次照做(见 [owedFollowUp])
        private var answered: String? = null
        private var followUpNudged = false
        private var resultReadNudged = false
        private var languageNudged = false
        // 工具调用漏成文字的回复刚退回过一次(见 [LeakedToolCall]);正常调了工具就重新算
        private var leakNudged = false
        private fun facts() = ReviewFacts(acted, paymentHandedOver || recorder.outwardActs > 0, recorder.lastChangeMark?.let { !tools.readSince(it) } == true)
        // 网页上每个站点只在第一次进入时按域名找一次知识
        private val visitedSites = mutableSetOf<String>()

        // 任务此刻在哪(App、站点……);由能力一侧按每次调用的结果给出
        private var place: Place? = null
        private var stuck = StuckState()
        private var lastProgressVersion = guards.progressVersion
        private fun stuckAdvice(verdicts: List<GuardVerdict>, typed: String?): String? {
            val version = guards.progressVersion
            val (next, advice) = StuckAdvice.next(stuck, StuckInput(verdicts, typed, place?.name, version != lastProgressVersion))
            lastProgressVersion = version
            stuck = next
            return advice
        }
        private val track = StepTrack()
        private val eachLoop = EachLoopDetector()
        private val progress = TaskProgressTracker(::now, ::emit)
        private var firstToolStep: Int? = null
        private val newData = NewDataTracker()
        private var intent: IntentGate.Intent? = null
        private var intentAssessed = false
        // 每次运行只判断一次;判断不出(调用失败、回复不是三个词之一)时为 null,照主模型自己的决定走
        private suspend fun assessIntent(): IntentGate.Intent? {
            intentAssessed = true
            // 只发来屏幕、没说话:没有话可判,按屏幕猜意图的规矩交给主模型
            val result = if (opening.screen != null && opening.screen.words.isEmpty()) null else intentGate?.assess(task, opening.priorTurns)
            emit(RunEvent.State(now(), "intent_gate", result?.name ?: "unavailable"))
            return result
        }
        private fun acceptProgress(parsed: CompletionPlanBlock.Extracted, step: Int) = progress.acceptParsed(parsed, step)

        suspend fun run(): TaskState {
            replayOpening()
            for (step in 1..maxSteps) {
                val end = runStep(step)
                if (end != null) return end
            }
            appendSteers(history, inbox)
            offer.refresh()
            return finalTurn(task, history, offer.current, maxSteps + 1, labels, place, recorder, facts(), inbox, progress)
        }

        private suspend fun replayOpening() {
            // 第一次模型调用之前:有合适的路线就先照着做,不调模型;做完或对不上都交还模型
            // 路线只记、只放手机上的做法:点名用网页的任务不回放
            if (flags.replay && replay != null && !task.trimStart().startsWith(WEB_CHANNEL_PREFIX)) {
                val reviewRoute = provenance.origin == RunOrigin.Ui && intentGate != null && routeMatcher?.candidates(task)?.isNotEmpty() == true
                if (reviewRoute) intent = assessIntent()
                if (!reviewRoute || intent == IntentGate.Intent.Read || intent == IntentGate.Intent.Act) {
                    replay.run(task, history, executor, ctx, track)?.let { place = it; acted = true }
                }
            }
        }

        private suspend fun runStep(step: Int): TaskState? {
            appendSteers(history, inbox)
            if (maxSteps != Int.MAX_VALUE) TurnPolicy.beforeStep(TurnSnapshot(step, maxSteps))?.let { history += ctx.notice(it.source, it.text) }
            appendRuntimeState(history, place, labels, step, progress.pending(step, afterFirstTool = firstToolStep == step - 1))
            _state.value = TaskState.Running(step, note, progress = progress.state(step))
            offer.refresh()
            var asked = now()
            val routed = try {
                routedOrRedirect(inbox, history, offer.current, track, step, { asked = it }, previewFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return StopRules.modelFailed(e)
            }
            if (routed == null) {
                keepInterrupted(history, inbox, step)
                return null
            }
            var (reply, used) = routed
            val proposed = reply.parts.firstActionCall()
            if (provenance.origin == RunOrigin.Ui && intentGate != null && proposed != null &&
                tools.verb(proposed.name).let { it !in DISCUSSION_LOOKUP_VERBS && it !in MEMORY_WRITE_VERBS }) {
                if (!intentAssessed) intent = assessIntent()
                if (intent == IntentGate.Intent.Discuss) {
                    // 提议的调用尚未写进历史、也没有执行；重问一次，只给直接回答的机会。
                    val discussion = history + ctx.notice("conversation_intent",
                        "The user is discussing or reviewing this time. Answer this question directly, without reading or operating apps, websites or other outside content.")
                    reply = try { caller.ask(discussion, emptyList(), step, using = model, onText = previewFor(step)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { return StopRules.modelFailed(e) }
                    used = caller.lastMainUsed
                }
            }
            val rawText = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            val parsed = CompletionPlanBlock.extract(rawText)
            parsed.stage?.let { stages?.reported(it) }
            acceptProgress(parsed, step)
            val text = parsed.text
            val cleanParts = cleanReplyParts(reply.parts, text)
            history += Message(Role.Assistant, cleanParts, Author.Mika).also { caller.noteReply(it, used, reply.usage) }
            val calls = ReplyCalls(reply.parts) { acceptProgress(it, step) }
            note = text.ifEmpty { calls.action?.name ?: note }
            val tool = calls.action?.name
            val reason = calls.action?.let { tools.callReason(it.name, parseArgs(it.argsJson)) }
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            // 随调用写的字是进度说明,界面上的实时那一行先用它
            val narration = text.takeIf { !calls.isEmpty && it.isNotEmpty() }
            _state.value = TaskState.Running(step, note, tool, progress = progress.state(step), reason = reason, narration = narration,
                preview = text.takeIf { streamReplies && calls.isEmpty && it.isNotEmpty() })
            // 带调用的回复等执行完再记:要看端口是否报告了密码框
            fun recordReply(argsJson: String?) = emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(text)), tool, argsJson,
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs,
                if (caller.recordRoles) used.name else null, if (caller.recordRoles) caller.roleOf(used) else null, retryWaitMs = retryWaitMs,
                repliedAtMs = repliedAtMs,
            ))

            val first = calls.action
            return if (first == null) {
                handleAnswer(step, calls, text, reply.stopReason, ::recordReply)
            } else {
                handleTool(step, calls, first, reason, narration, ::recordReply)
            }
        }

        /** 不带调用的回复:进度说明、最终回答,和交出去之前的各道核对。 */
        private suspend fun handleAnswer(
            step: Int,
            calls: ReplyCalls,
            text: String,
            stopReason: StopReason,
            recordReply: (String?) -> Unit,
        ): TaskState? {
            if (!calls.isEmpty) {
                recordReply(null)
                history += calls.results()
                if (++progressOnlyTurns >= 3) return StopRules.noAnswer()
                return null
            }
            track.after(null, ok = false, guard = false, changed = null, recovery = false, app = null)
            recordReply(null)
            // 模型给出最终回答时,先收下在途的用户插话,再决定是否交出回答。
            if (keepFinished(history, inbox)) return null
            // 空回复、被输出上限截断的半截话不是回答:提醒一次,再这样就结束
            if (text.isEmpty() || stopReason == StopReason.MaxTokens) {
                val n = TurnPolicy.afterEmpty(emptyNudged, truncated = text.isNotEmpty()) ?: return StopRules.noAnswer()
                emptyNudged = true
                history += ctx.notice(n.source, n.text)
                return null
            }
            emptyNudged = false
            // 工具调用原样漏成了文字(服务端没解析成调用):不是回答。退回一次,经工具接口重新调用;
            // 紧接着又漏就结束,不把原样的标记交给用户
            val leaked = LeakedToolCall.find(text, allToolNames)
            if (leaked != null) {
                if (leakNudged) return StopRules.toolCallAsText()
                leakNudged = true
                emit(RunEvent.State(now(), "tool_call_as_text", RunEvent.clip(leaked)))
                TurnPolicy.leakedToolCall(leaked).let { history += ctx.notice(it.source, it.text) }
                return null
            }
            // 用户刚同意或回答了什么、之后却什么也没做:先提醒一次照做或说明为什么不做;之后照常核对
            val owed = owedFollowUp(answered).takeIf { !followUpNudged }
            if (owed != null) {
                followUpNudged = true
                TurnPolicy.followUp(owed).let { history += ctx.notice(it.source, it.text) }
                return null
            }
            val before = beforeAccept(text)
            if (before is BeforeAccept.Continue) {
                history += ctx.notice(Feedback["before_accept_source"], before.note)
                return null
            }
            val currentFacts = facts()
            if (!reviewFinalAnswer && currentFacts.unreadChange && !resultReadNudged) {
                resultReadNudged = true
                history += ctx.notice("operation_result", "The result of the last action has not been read yet. Look at the current screen first to confirm the actual state, then answer; if it cannot be read, say so plainly.")
                return null
            }
            // 回答的正文不是用户最近那句的语言:退回一次整段改写;改写后还不对照样交出
            if (!languageNudged) {
                val mismatch = ReplyLanguage.mismatch(latestWords, text)
                if (mismatch != null) {
                    languageNudged = true
                    TurnPolicy.language(mismatch).let { history += ctx.notice(it.source, it.text) }
                    return null
                }
            }
            // 不带调用的回复就是最终回答；产品路径只整理来源。
            val result = conclude(task, text, step, currentFacts, history)
            if (result != null) {
                // 交出之前还有插话(核对、整理来源时到的):不收尾,带着它接着做;没有就关上收件箱,之后的话才是下一件事
                if (inbox?.closeUnlessSteered() == false) { keepFinished(history, inbox); return null }
                return result
            }
            return null
        }

        /** 带调用的回复:批量步、确认关卡、单步执行和执行后的收尾判断。 */
        private suspend fun handleTool(
            step: Int,
            calls: ReplyCalls,
            first: Part.ToolCall,
            reason: String?,
            narration: String?,
            recordReply: (String?) -> Unit,
        ): TaskState? {
            emptyNudged = false
            leakNudged = false
            progressOnlyTurns = 0
            acted = true
            if (firstToolStep == null) firstToolStep = step
            // 回答之后又做了别的:那个回答算有了下文
            answered = null

            if (stages != null && tools.verb(first.name) !in setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)) {
                stages.planIfNeeded(latestWords, intentJudge ?: model)
            }
            trace?.toolStarted(step, first.name, first.id, now())
            val args = parseArgs(first.argsJson)

            // 用户刚同意的那一步要单独做:批里的步不带同意,混进去会被关卡拦下,同意也可能被别的步用掉或作废
            if (((flags.batchSteps && first.name == DO_STEPS_TOOL) || tools.composite(first.name)) && confirmations?.hasGrant == true) {
                val content = buildJsonObject {
                    put("ok", false); put("error", "approved_step_first"); put("message", APPROVED_STEP_FIRST); put("recoverable", true)
                }
                history += calls.results(Part.ToolResult(first.id, content.toString(), isError = true))
                recordReply(if (first.name == DO_STEPS_TOOL) BatchRunner.redactArgs(first.argsJson) else Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                recorder.result(step, first.name, tools.verb(first.name), content, JsonObject(emptyMap()), null, null, 0, "model", first.id)
                track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                return null
            }
            if (flags.batchSteps && first.name == DO_STEPS_TOOL) {
                val end = batch(first, calls, history, offer.current, guards, executor, track, eachLoop, step, note, first.name, reason, narration, recordReply = recordReply,
                    app = { place = it }, steerWaiting = { inbox?.hasSteers() == true },
                    noteVerdicts = { s, sub, v ->
                        // 和单步一样计卡住提醒与同一段字的重复输入
                        val typed = StuckAdvice.typedText(tools.verb(s.tool), parseArgs(s.argsJson), sub?.content)
                        stuckAdvice(v, typed)
                    })
                if (end != null) return end
                if (progress.plan.complete) return finalTurn(task, history, offer.current, step + 1, labels, place, recorder, facts(), inbox, progress, Closeout.Completed)
                return null
            }

            val verb = tools.verb(first.name)
            val offScreen = tools.offScreen(first.name)
            val running = TaskState.Running(step, note, first.name, progress = progress.state(step), reason = reason, narration = narration)
            gateState = running
            var advice: String? = null
            var waitsForUser = false
            val action = executor.run(first, Origin.Model(step), object : FollowUp {
                override suspend fun resolve(exec: ToolExecution): ToolExecution? {
                    var e = exec
                    (e.outcome as? Outcome.Confirm)?.let { e = user.confirm(it, step, running) }
                    // 要等用户的(追问、接管):等之前先记下这次回复,等待中取消任务,运行记录里也留得住
                    waitsForUser = e.outcome is Outcome.Input || e.outcome is Outcome.Takeover
                    if (waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                    // 追问的回答(或超时)就是这次调用的工具结果,调用与结果配对
                    (e.outcome as? Outcome.Input)?.let { o ->
                        e = user.input(o, running)
                        if (e.content["ok"] == JsonPrimitive(true)) answered = o.prompt
                    }
                    // 等到交回,交回的结局就是这次调用的工具结果;取消任务就此结束
                    (e.outcome as? Outcome.Takeover)?.let { e = user.takeover(it, running) ?: return null }
                    return e
                }

                override suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented {
                    val e = exec
                    val a = stuckAdvice(verdicts, StuckAdvice.typedText(verb, args, e.content))
                    advice = a
                    val content = if (verdicts.isEmpty() && advice == null) e.content
                    else JsonObject(e.content +
                        (if (verdicts.isEmpty()) emptyMap() else mapOf("guard" to JsonArray(verdicts.map { it.toJson() }))) +
                        (advice?.let { mapOf("advice" to JsonPrimitive(it)) } ?: emptyMap()))
                    val isError = (e.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
                    history += calls.results(Part.ToolResult(first.id, ScreenElementTable.text(content, flags.tablesAtBirth && !isError), isError, e.observations.map { it.tag }))
                    // 只有端口明确说「不是密码框」才记原文
                    val secret = (e.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
                    if (!waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secret))
                    return Presented(e, content)
                }

                override fun refused(content: JsonObject) {
                    history += calls.results(Part.ToolResult(first.id, content.toString(), isError = true))
                    recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                }

                // 不在屏上、要问的一步(会改动东西的 root 命令):用和付款同一张确认卡片当场问,申报就是这一步本身
                override suspend fun approve(request: ConfirmationRequest): ToolExecution =
                    user.confirm(Outcome.Confirm(request, request.app), step, running)
            })
            val ran = when (action) {
                is ActionOutcome.Cancelled -> return TaskState.Cancelled
                is ActionOutcome.Refused -> {
                    guards.stop?.let {
                        recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                        return StopRules.guard(it)
                    }
                    // 应用权限拒绝已经由执行入口作为工具结果交给模型:不结束任务,让模型换路线或向用户说明
                    if (AppAccessDenied.isAppAccessCode(action.code)) {
                        track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                        return null
                    }
                    // 模型单步只会被守卫叫停、被确认关卡拦下或因作用在 Mika 自己身上不做;别的拒绝说明执行入口和这里对不上
                    check(action.hit != null || action.code == Refusals.OWN_APP) { "unexpected refusal ${action.code} for ${first.name}" }
                    // 被确认关卡拦下:不计入失败熔断,交给模型走确认流程
                    track.after(tools.verb(first.name), ok = false, guard = action.before.isNotEmpty(), changed = null, recovery = false, app = null)
                    return null
                }
                is ActionOutcome.Ran -> action
            }
            val exec = ran.exec
            val noNewData = newData.after(verb, exec)
            if (noNewData) emit(RunEvent.State(now(), "no_new_data", NewDataTracker.LIMIT.toString()))
            if (verb == Verb.HandToUser && (args["reason"] as? JsonPrimitive)?.contentOrNull == PAYMENT) paymentHandedOver = true
            val isError = (exec.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
            noteResultEvents(verb, exec.content)
            exec.place?.let { place = it }
            track.after(tools.verb(first.name), !isError, ran.verdicts.isNotEmpty() || advice != null, exec.changed, false, exec.place?.name)
            if (eachLoop.feed(verb, !isError)) {
                history += ctx.notice(EACH_LOOP_SOURCE, Feedback.format("loop.repeating_items", "count" to EachLoopDetector.REPEATS))
                track.markRecovery()
            }
            val picture = ran.picture
            val shotId = ran.shotId
            if (picture != null && shotId != null) {
                record { sink.media(shotId, picture) }
                media.getOrPut(step) { mutableListOf() } += shotId
                emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(place)))
            }

            val stopNow = guards.stop ?: executor.offScreenStop
            val end = StopRules.afterAction(exec.outcome, stopNow)
            if (end != null) {
                // 模型自己说做不成(end_not_done)时用户刚补了话:不收尾,带着这句话接着做;守卫叫停照样结束
                if (stopNow == null && exec.outcome is Outcome.Failure && inbox?.closeUnlessSteered() == false) {
                    appendSteers(history, inbox)
                    return null
                }
                return end
            }

            if (exec.images.isNotEmpty()) {
                // 读到的图片文件不是屏幕画面,不带 screenshotId
                history += Message(Role.User, listOf(Part.Text(NoticePolicy.imageNote(verb, offScreen))) +
                    exec.images.mapIndexed { i, img -> tools.modelImage(img, if (offScreen) null else exec.imageIds.getOrNull(i)) }, Author.Mika)
            }
            for (lookup in NoticePolicy.knowledgeAfter(verb, exec.place, !isError)) when (lookup) {
                is KnowledgeLookup.App -> injectKnowledge(history, knowledge?.byPackage(lookup.pkg), lookup.pkg)
                // 同一份知识不会重复注入
                is KnowledgeLookup.Site -> if (visitedSites.add(lookup.host)) injectKnowledge(history, knowledge?.byDomain(lookup.host), null)
            }
            if (progress.plan.complete) return finalTurn(task, history, offer.current, step + 1, labels, place, recorder, facts(), inbox, progress, Closeout.Completed)
            if (noNewData) return finalTurn(task, history, offer.current, step + 1, labels, place, recorder, facts(), inbox, progress, Closeout.NoNewData)
            return null
        }
    }

    /** 运行记录里按结果另记的事:报告的障碍(供评测按 barrier 类归因;参数不对被拒的不算)、看过的个人技能、存下的记忆。 */
    private fun noteResultEvents(verb: Verb?, content: JsonObject) {
        if (verb == Verb.HandleObstacle && content["ok"] == JsonPrimitive(true)) {
            (content["kind"] as? JsonPrimitive)?.contentOrNull?.let { emit(RunEvent.Barrier(now(), it)) }
        }
        GuideRecommender.viewedPersonal(verb, content)?.let { emit(RunEvent.SkillUsed(now(), it, "viewed")) }
        if (verb == Verb.MemorySave && content["ok"] == JsonPrimitive(true)) emit(RunEvent.MemorySaved(now(),
            content["id"]?.jsonPrimitive?.content ?: "", content["kind"]?.jsonPrimitive?.content ?: "", content["status"]?.jsonPrimitive?.content ?: ""))
    }

    /** 前台 App/站点的参考资料补进历史(确定性,不靠关键词猜);技能开局已全部列出,这里不再补。有屏图的 App 再随 APP.md 接一段页面结构。 */
    private suspend fun injectKnowledge(history: MutableList<Message>, e: KnowledgeEntry?, pkg: String?) {
        ctx.knowledge(e, toolsNote(tools.scriptsOffered()), flags.deepLinks)?.let { m ->
            if (flags.deepLinks && e!!.links.isNotEmpty()) tools.allowLinks(e.links)
            history += m
            if (e?.trial == true) emit(RunEvent.State(now(), "knowledge_trial_used", e.name))
        }
        // 屏图只试一次:去重在前,没接线、试过或没图都不再读屏读盘
        if (pkg == null || screenMaps == null || !ctx.firstScreenMapAttempt(pkg)) return
        val graph = screenMaps.load(pkg)
        if (graph == null || graph.nodes.isEmpty()) return
        val skeleton = tools.interfaces.peek()?.let { s -> RouteAnchors.texts(s).toSet() }
        ScreenMapRender.renderMapFor(graph, skeleton)?.let { map ->
            history += ctx.screenMap(pkg, map)
        }
    }

    /**
     * 这一步的运行时状态接在本步最后一条消息的末尾(还没发过的那条),和这一步一起被裁剪、折叠;
     * 发过的消息一概不改,请求前缀逐步只增不变。最后一条是助手消息时(不会发生在正常流程里)单独追加一条。
     * 网页上的版本从浏览器当前标签实际用的版本读。
     */
    private suspend fun appendRuntimeState(history: MutableList<Message>, place: Place?, labels: Map<String, String>, step: Int, pending: String?) {
        val mode = if (place?.kind == PlaceKind.Site) tools.placeMode() else null
        val state = ctx.runtimeState(place, place?.name?.let(labels::get), mode, step, maxSteps, now(), latestWords,
            listOfNotNull(pending, stages?.hint()).takeIf { it.isNotEmpty() }?.joinToString("\n"))
        val last = history.lastOrNull()
        if (last == null || last.role == Role.Assistant) history += Message(Role.User, listOf(state), Author.Mika)
        else history[history.lastIndex] = last.copy(parts = last.parts + state)
    }

    /**
     * 用户这句话里他这次打的字:屏幕的说明、浏览器页的页面说明、引的那一段和附件行先后去掉(见 [UserWords.typed];几样都带着时都去掉),
     * 都没有时是原话;只发了屏幕、只引了一段、只选了字、只带了附件时为空。
     * 给模型的那句带着固定的英文说明、插话标记,后面还可能接着运行时状态,不能拿来判断用户用的语言。
     */
    private fun ownWords(raw: String, shown: UserScreenTurns.Rendered?): String = UserWords.typed(shown?.words ?: raw, tools)

    private suspend fun channelLine(matched: List<KnowledgeEntry>): String? {
        val facts = channelFacts ?: return null
        val f = try { facts() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        return ChannelAdvisor.line(matched, tools.skillCatalog, f)
    }

    /**
     * 插话是独立的真实用户行;只在两步之间追加,绝不改写已经发给模型的消息。
     * 插话带着用户此刻的屏幕时和开局原话一样换好:那句话连同截图,后面跟着屏幕的详情。
     */
    private suspend fun appendSteers(history: MutableList<Message>, inbox: RunInbox?): Boolean =
        appendSteers(history, inbox?.takeSteers().orEmpty())

    private suspend fun appendSteers(history: MutableList<Message>, updates: List<RunInbox.UserSteer>): Boolean {
        updates.forEach { update ->
            val shown = screens?.render(update.text)
            val words = ownWords(update.text, shown)
            if (words.isNotBlank()) latestWords = words
            history += Message(Role.User, listOf(Part.Text("$STEER_MARKER\n${shown?.said ?: update.text}")) + shown?.images.orEmpty(), Author.User)
            shown?.details?.forEach { (source, body) -> history += ctx.userScreen(source, body) }
            utterances?.add(UserWords.said(shown?.words ?: update.text, tools))
            emit(RunEvent.State(now(), "steer_received", RunEvent.clip(Redactor.text(update.messageId))))
        }
        return updates.isNotEmpty()
    }

    /**
     * 不带调用的回复已经记进历史、也显示出来了,这时有插话:接上插话接着做(返回 true)。
     * 屏幕上那段回复要留着:下一步一开始界面上的实时文字就清掉了,所以发 `reply_finished_before_steer` 把它挂到插话上方。
     * 没有插话返回 false,什么也不动。
     */
    private suspend fun keepFinished(history: MutableList<Message>, inbox: RunInbox?): Boolean {
        val steers = inbox?.takeSteers().orEmpty()
        if (steers.isEmpty()) return false
        val current = _state.value as? TaskState.Running
        val shown = current?.preview?.trim()
        if (current?.preview != null) _state.value = current.copy(preview = null)
        // 不截短:这是一段完整的回答,界面要和没插话时一样显示全文(长度本来就受模型输出上限约束)
        if (!shown.isNullOrEmpty()) emit(RunEvent.State(now(), "reply_finished_before_steer", Redactor.text(shown)))
        appendSteers(history, steers)
        return true
    }

    /** 插话只取消正在等待的模型请求(本步不写进历史,下一步带着插话重问);工具调用进入执行入口后由它自己做完。 */
    private suspend fun routedOrRedirect(
        inbox: RunInbox?, history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)?,
    ): Pair<ModelReply, ChatModel>? {
        if (inbox == null) return caller.routed(history, specs, track, step, restarted, onText)
        return coroutineScope {
            val pending = async { caller.routed(history, specs, track, step, restarted, onText) }
            select<Pair<ModelReply, ChatModel>?> {
                pending.onAwait { it }
                inbox.redirects.onReceive {
                    pending.cancelAndJoin()
                    null
                }
            }
        }
    }

    /**
     * 插话撤掉了第 [step] 步的请求:用户已经看到的那段字(界面上的 preview)作为一条只有文字的回复记进历史,
     * 接着才是插话,模型下一次看到的和用户看到的一样。没看到字、或没有插话时什么也不记。
     * 思考内容和半截工具调用本来就不在 preview 里。界面上的那段字在这里清掉,改由 `reply_interrupted` 事件挂到插话上方。
     */
    private suspend fun keepInterrupted(history: MutableList<Message>, inbox: RunInbox?, step: Int) {
        val steers = inbox?.takeSteers().orEmpty()
        interruptedStep = step
        val current = _state.value as? TaskState.Running
        val partial = current?.takeIf { it.step == step }?.preview?.trim()
        if (current?.preview != null) _state.value = current.copy(preview = null)
        if (!partial.isNullOrEmpty() && steers.isNotEmpty()) {
            history += Message(Role.Assistant, listOf(Part.Text(partial)), Author.Mika)
            emit(RunEvent.State(now(), "reply_interrupted", RunEvent.clip(Redactor.text(partial))))
        }
        appendSteers(history, steers)
    }

    private fun previewFor(step: Int): ((String) -> Unit)? = if (!streamReplies) null else { raw ->
        val preview = CompletionPlanBlock.visible(raw)
        val current = _state.value as? TaskState.Running
        // 撤掉之后网络线程可能还送来一段:不再显示,免得屏幕上比历史里多
        if (current?.step == step && interruptedStep != step) _state.value = current.copy(preview = preview.takeIf(String::isNotEmpty))
    }

    /**
     * 执行一次 do_steps:整批对步数上限算一步,每个子步对守卫和运行记录各算一个动作(由 [BatchRunner] 记)。
     * 批停下时让下一步回到主模型。返回非 null 表示任务就此结束。
     */
    private suspend fun batch(
        first: Part.ToolCall, calls: ReplyCalls, history: MutableList<Message>, specs: List<ToolSpec>, guards: OperationGuards,
        executor: ActionExecutor, track: StepTrack, eachLoop: EachLoopDetector, step: Int, note: String, tool: String?, reason: String?, narration: String?,
        recordReply: (String?) -> Unit, app: (Place) -> Unit, steerWaiting: () -> Boolean,
        noteVerdicts: (BatchStep, ToolExecution?, List<GuardVerdict>) -> String?,
    ): TaskState? {
        // 一批打断了逐条干活的连击(这正是提醒想要的方向):连着次数清零重算
        eachLoop.reset()
        // 收着的组里的工具也能放进批里(和单步一样照常执行、展开它那一组)
        val (parsed, problem) = BatchRunner.parse(first.argsJson, tools.definitions().map { it.name }.toSet(), tools.batchable())
        // 一批只在一块屏上做:混了两块屏的整批不做
        val mixed = parsed?.let { BatchRunner.mixedSurface(it, tools::surfaceOf) }
        val steps = parsed.takeIf { mixed == null }
        val running = TaskState.Running(step, note, tool, steps?.let { RunProgress.Batch(it.size) }, reason, narration)
        if (steps != null) _state.value = running
        val started = now()
        var runner: BatchRunner? = null
        val exec = if (steps == null) ToolExecution(buildJsonObject {
            put("ok", false); put("error", if (mixed != null) "mixed_surface" else "invalid_arguments"); put("message", mixed ?: problem); put("recoverable", true)
        }) else {
            gateState = running
            runner = BatchRunner(tools, guards, executor, noteVerdicts = noteVerdicts, steerWaiting = steerWaiting)
            runner.run(steps, first.id, step)
        }
        val durationMs = now() - started
        val isError = exec.content["ok"] != JsonPrimitive(true)
        history += calls.results(Part.ToolResult(first.id, ScreenElementTable.text(exec.content, flags.tablesAtBirth && !isError), isError, exec.observations.map { it.tag }))
        // 批做完(或停下)后再给模型一份当前元素表；只有元素信息不足才附画面。
        if (steps != null && ranAnyStep(exec.content)) {
            tools.lookNow()?.let { v ->
                history += Message(Role.User, listOfNotNull(Part.Text(batchViewNote(ScreenElementTable.text(v.content, flags.tablesAtBirth))), v.image?.let { tools.modelImage(it, v.imageId) }), Author.Mika)
            }
        }
        recordReply(BatchRunner.redactArgs(first.argsJson))
        val picture = exec.frame
        val shotId = picture?.let { executor.recorder.nextShot() }
        // 子步之后按条件等界面的时间另记,小结里算等待
        emit(RunEvent.ToolResult(now(), step, first.name, RunEvent.clipResult(Redactor.text(exec.content.toString())), exec.changed, shotId, durationMs,
            waitMs = runner?.waitedMs ?: 0, callId = first.id))
        val currentPlace = exec.place
        currentPlace?.let(app)
        // 要确认不算失败(和单步被拦一样不进失败熔断),但同样交给主模型
        // 为新插话停下的批同样不算失败
        val confirm = exec.content["error"] == JsonPrimitive("confirmation_required") || exec.content["reason"] == JsonPrimitive(BatchRunner.NEW_MESSAGE)
        track.after(tools.verb(first.name), !isError || confirm, runner?.verdicts?.isNotEmpty() == true || exec.content["advice"] != null, exec.changed, false, currentPlace?.name)
        // 批停下(核对没过、要确认):下一步交给主模型
        if (isError) track.markRecovery()
        if (picture != null && shotId != null) {
            record { sink.media(shotId, picture) }
            media.getOrPut(step) { mutableListOf() } += shotId
            emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(currentPlace)))
        }
        return guards.stop?.let(StopRules::guard)
    }

    /** do_steps 的结果里至少有一个子步真的执行了(没执行的停止项标着 executed=false)。 */
    private fun ranAnyStep(content: JsonObject): Boolean =
        (content["steps"] as? JsonArray)?.any { (it as? JsonObject)?.get("executed") != JsonPrimitive(false) } == true

    /**
     * 用户同意了、还没用掉的那次操作,或回答了的追问([answered] 是那个问题,之后又做了别的就是 null):回答交出前还欠着的下文;
     * 都没有为 null。
     */
    private fun owedFollowUp(answered: String?): String? {
        confirmations?.granted?.let { r ->
            val what = listOfNotNull(r.action.name, r.target.takeIf(String::isNotBlank), r.content.takeIf(String::isNotBlank)?.let { "「$it」" }).joinToString(" ")
            return Feedback.format("followup_approved", "action" to what)
        }
        return answered?.let { Feedback.format("followup_answered", "prompt" to it.take(200)) }
    }

    /**
     * 第 [step] 步的回复没调工具,文字 [answer] 就是最终回答:按风险核对([AnswerReview]),来源由代码找。
     * 核对没对上:能退回([history] 不为 null,而且本任务还没退回过)就提醒模型一次、返回 null 接着做;
     * 否则照样交出,没对上的几处标成未确认。[facts] 是核对要看的任务情况(见 [ReviewFacts])。
     */
    private suspend fun conclude(task: String, answer: String, step: Int, facts: ReviewFacts, history: MutableList<Message>?): TaskState? {
        if (!reviewFinalAnswer) {
            val sources = review.sourcesOnly(task, answer, media)
            if (sources.isNotEmpty()) emit(RunEvent.Sources(now(), sources))
            return TaskState.Succeeded(answer, verified = false, sources = sources, reviewed = false)
        }
        val r = review.review(task, answer, step, facts, earlierGoals, media) {
            _state.value = TaskState.Running(step, VERIFYING_NOTE, reason = VERIFYING_NOTE,
                preview = (_state.value as? TaskState.Running)?.preview)
        }
        val missing = r.unconfirmed
        if (missing.isNotEmpty() && history != null && !gapsNudged) {
            gapsNudged = true
            TurnPolicy.unconfirmed(r.gaps, r.objections).let { history += ctx.notice(it.source, it.text) }
            return null
        }
        if (missing.isNotEmpty()) emit(RunEvent.Unconfirmed(now(), step, missing.map(::detail)))
        if (r.sources.isNotEmpty()) emit(RunEvent.Sources(now(), r.sources))
        return if (missing.isEmpty()) TaskState.Succeeded(answer, r.verified, r.note, r.sources, reviewed = r.reviewed)
        else TaskState.Succeeded(answer, verified = false, note = UNCONFIRMED_NOTE + missing.joinToString("、"), sources = r.sources, unconfirmed = missing)
    }

    /**
     * 步数用完后最后给一轮,工具只剩 end_not_done:做完了就用文字回答(照样核对,但不再退回,没对上的标成未确认),
     * 做不成就如实说查过什么。这一轮什么也没给(或被截断)仍算步数用完。
     */
    private suspend fun finalTurn(
        task: String, history: MutableList<Message>, specs: List<ToolSpec>, step: Int, labels: Map<String, String>, place: Place?, recorder: ActionRecorder,
        facts: ReviewFacts, inbox: RunInbox?, progress: TaskProgressTracker, closeout: Closeout = Closeout.StepLimit,
    ): TaskState {
        fun acceptProgress(parsed: CompletionPlanBlock.Extracted) = progress.acceptParsed(parsed, step)
        TurnPolicy.closeout(closeout, progress.state(step)).let { history += ctx.notice(it.source, it.text) }
        repeat(3) { attempt ->
            appendSteers(history, inbox)
            appendRuntimeState(history, place, labels, step, progress.pending(step))
            _state.value = TaskState.Running(step, "", progress = progress.state(step))
            val asked = now()
            val reply = try {
                caller.ask(history, TurnPolicy.finalTools(specs, tools::verb), step, onText = previewFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError.TaskBudgetExceeded) {
                return StopRules.modelFailed(e)
            } catch (e: ModelError.DailyBudgetExceeded) {
                return StopRules.modelFailed(e)
            } catch (e: Exception) {
                return StopRules.closeoutFailed(closeout, maxSteps)
            }
            val rawText = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            val parsed = CompletionPlanBlock.extract(rawText)
            parsed.stage?.let { stages?.reported(it) }
            acceptProgress(parsed)
            val text = parsed.text
            history += Message(Role.Assistant, cleanReplyParts(reply.parts, text), Author.Mika)
                .also { caller.noteReply(it, caller.lastMainUsed, reply.usage) }
            val calls = ReplyCalls(reply.parts, ::acceptProgress)
            val call = calls.actions.firstOrNull { tools.verb(it.name) in TurnPolicy.FINAL_VERBS }
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(text)), call?.name, call?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = false) },
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs, retryWaitMs = retryWaitMs,
                repliedAtMs = repliedAtMs,
            ))
            // 收尾模型也可能正在生成时收到插话。最多重答两次，其余交给下一任务。
            if (attempt < 2 && inbox?.hasSteers() == true) {
                if (!calls.isEmpty) history += calls.results()
                if (keepFinished(history, inbox)) return@repeat
            }
            if (call == null) {
                if (calls.action == null && !calls.isEmpty && text.isEmpty()) {
                    history += calls.results()
                    return@repeat
                }
                if (calls.action != null || text.isEmpty() || reply.stopReason == StopReason.MaxTokens) return StopRules.closeoutFailed(closeout, maxSteps)
                return conclude(task, text, step, facts, history = null)!!
            }
            val started = now()
            // 只剩放弃,不改变手机
            val exec = tools.executeRead(call)
            recorder.result(step, call.name, tools.verb(call.name), exec.content, exec.record, null, null, now() - started, "model", call.id)
            return StopRules.afterFinalTurn(exec.outcome, maxSteps)
        }
        return StopRules.closeoutFailed(closeout, maxSteps)
    }

    companion object {
        const val STEER_MARKER = "<user_interjection>"
        const val DEFAULT_MAX_STEPS = 60
        /** 正常任务没有固定步数截止；显式步数预算只用于评测和指定上限的子任务。 */
        const val UNLIMITED_STEPS = Int.MAX_VALUE
        /** 核查期间界面上这一步的说明。 */
        const val VERIFYING_NOTE = "正在核实结果"
        val APPROVED_STEP_FIRST: String get() = Feedback["approved_step_first"]
        /** 回答里退回一次后仍没对上的几处,结局说明里这样开头(界面另按 [TaskState.Succeeded.unconfirmed] 逐条标出)。 */
        const val UNCONFIRMED_NOTE = "未确认:"
        /** hand_to_user 交给用户付款时的原因;付款是不可逆的事,回答要另请核查。 */
        private const val PAYMENT = "payment"
        /** 逐条干活重复提醒的来源标签。 */
        private const val EACH_LOOP_SOURCE = "each loop"
        const val DEFAULT_INPUT_TIMEOUT_MS = 15 * 60_000L
        /** 交回接管后工具结果里的提醒(界面测试按它认)。 */
        val HANDBACK_NOTE: String get() = UserExchange.HANDBACK_NOTE

        /** 用户点了「我自己做完了」。 */
        val USER_FINISHED_NOTE: String get() = Feedback["user_finished_note"]
    }

    private fun now() = clock()

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private inline fun record(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 记录失败不影响任务
        }
    }

    private fun emit(e: RunEvent) = record { sink.event(e) }

    private fun ended(end: TaskState?): RunEvent.TaskEnded = when (end) {
        // 核对过却没能核实(含标成未确认的):记成 unverified,和评测、对话记录同一个说法
        is TaskState.Succeeded -> RunEvent.TaskEnded(now(), if (end.doubtful) "unverified" else "succeeded", Redactor.text(end.answer))
        is TaskState.Failed -> RunEvent.TaskEnded(now(), "failed", Redactor.text(end.reason), end.kind.name.lowercase())
        TaskState.Cancelled -> RunEvent.TaskEnded(now(), "cancelled", "已取消")
        else -> RunEvent.TaskEnded(now(), "error", "任务异常中断")
    }

    private fun waitingEvent(s: TaskState): RunEvent.State = when (s) {
        is TaskState.Held -> RunEvent.State(now(), s.reason.code, detail(s.reason.why))
        else -> RunEvent.State(now(), s::class.simpleName.orEmpty(), "")
    }
}

/** 用户点名走网页的说法(评测强制网页渠道时也加在目标前)。 */
private const val WEB_CHANNEL_PREFIX = "用网页"
