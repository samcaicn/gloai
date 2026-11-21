package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.net.RateLimitGovernor
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.core.usage.ConversationMeter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次模型调用:历史只追加,估算越过触发点才压缩一次([ContextCompressor],完整历史不动)、主模型一时的故障按 [retry] 重试、上下文超长时压得更狠再发一次,
 * lightSteps 打开时按 [router] 在大小模型之间分工(轻量模型出错不重试,这一步直接改问主模型)。每个任务开始时 [reset]。
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
    /** 主模型的备援(项目 3):主模型这一步失败且 [Failover] 说换,就按序接手;默认没有,行为和原来一样。 */
    private val fallbacks: List<ChatModel> = emptyList(),
    /** 与主模型链平行的服务商 ID，用来判断每日预算是否适用于备援。 */
    private val chainProviderIds: List<String?> = emptyList(),
) {
    /** 每个模型一份压缩状态:各自的上下文、各自发出的那份历史,轻量模型那次压缩不连累主模型的请求前缀。 */
    private val compressors = java.util.IdentityHashMap<ChatModel, ContextCompressor>()
    private fun compressorFor(m: ChatModel) =
        compressors.getOrPut(m) { ContextCompressor(tools.index, structured = flags.structuredCompaction,
            diffScreens = tools.inlineScreen, triggerOverride = m.capabilities.compactionTriggerTokens,
            foldEvery = m.capabilities.compactionEveryRounds) }
    private val compactions = java.util.IdentityHashMap<ChatModel, CompactionState>()
    /** 本任务里已为重试等过的时间(对着 [RetryPolicy.taskWaitBudgetMs])。 */
    private var taskRetryWaitMs = 0L

    /** 主模型链:[model] 加上备援。切过去之后本任务余下都用它(和轻量模型停用一个道理),[reset] 回到主模型。 */
    private val chain: List<ChatModel> = listOf(model) + fallbacks
    private var chainIndex = 0

    /** 本任务里主模型最初的错误、和每个链上模型先犯的错误(整条链失败时的说法用,MK-1 第 2 批);[reset] 清空。 */
    private var headError: ModelError? = null
    private val trialLog = ArrayList<Pair<Int, ModelError>>()

    /** 最近一次主模型调用实际用的模型:切到备援后就是备援;[routed] 按它记账。 */
    var lastMainUsed: ChatModel = model
        private set

    /** 本任务的系统提示词:开工时按发给模型的同一份工具列表拼([SystemPrompt.assemble]),任务内不变。 */
    var systemPrompt: String = ""

    /** 插件提示词钩子:拼好的系统提示词发模型前过它;为 null 时原样发。 */
    var promptTransform: (suspend (String) -> String)? = null

    /** 最近一次调用在成功那次请求之前花掉的时间(失败的请求、退避);从第一次真正发出请求起算。 */
    var lastRetryWaitMs = 0L
        private set

    /** 每条已进历史的回复是哪个模型给的(按消息身份);换模型时按它去掉别家模型的私有数据。没记到的(任务开始前就在历史里的)视同主模型给的。 */
    private val replyOrigin = java.util.IdentityHashMap<Message, ChatModel>()

    /** 带真实用量的回复(按消息身份)和给出它的模型,是量请求大小的锚点([UsageMeter]);压缩改写前缀后清空。 */
    private val anchors = java.util.IdentityHashMap<Message, Pair<ChatModel, UsageMeter.Anchor>>()
    /** 每个模型最近一次请求的固定开销估算;回复进历史时和用量一起记进锚点。 */
    private val lastOverhead = java.util.IdentityHashMap<ChatModel, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        taskRetryWaitMs = 0
        chainIndex = 0
        headError = null
        trialLog.clear()
        lastMainUsed = model
        compressors.values.forEach { it.forget() }
        replyOrigin.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** 回复进了历史:记下是哪个模型给的;有真实 usage 的记成上下文大小的锚点。 */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        replyOrigin[m] = used
        val overhead = lastOverhead[used]
        if (usage != null && overhead != null) anchors[m] = used to UsageMeter.Anchor(usage, overhead)
    }

    /** 发给 [using] 的整份请求有多大:最近一条它给的、带真实用量的回复作锚点,没有就全量估算(见 [UsageMeter])。 */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int =
        UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first === using }?.second }

    /** 本任务里分工是否在起作用:开关打开、轻量模型能接手机步、没因不可恢复的错误停用。 */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /** 开了分工就在运行记录里记每次调用的模型和角色(停用之后也记,便于对比)。 */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /**
     * 按 [router] 选模型。轻量模型出错、没调工具(不带调用的回复是最终回答,只由主模型给),或要放弃、确认、接管、追问、做不可逆的事,
     * 它的回复不执行(作为 [ROLE_LIGHT_REJECTED] 记下用量),这一步改问主模型,改问前用 [restarted] 报新的起问时刻。
     * 返回回复和实际用的模型。
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val r = router?.takeIf { routing } ?: return ask(history, specs, step, onText = onText) to lastMainUsed
        val picked = r.pick(track.context(step, history.any { m -> m.parts.any { it is Part.Image } }))
        if (!r.isLight(picked)) return ask(history, specs, step, onText = onText) to lastMainUsed
        // 轻量模型的上下文可能更小:它报超长、它那次折叠多少,都不连累主模型(压缩状态按模型分开记)
        val asked = now()
        val light = try {
            complete(viewFor(history, picked), specs, picked, RetryPolicy.NONE, step, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError.TaskBudgetExceeded) {
            // 整个运行共用一份预算；轻量失败后不能再改问主模型。
            throw e
        } catch (e: Exception) {
            // Key、额度、模型名、请求本身不对,重试也没用:本任务余下的步都用主模型
            if (e is ModelError.Auth || e is ModelError.IllegalHeader || e is ModelError.QuotaExceeded || e is ModelError.DailyBudgetExceeded || e is ModelError.ModelNotFound ||
                e is ModelError.ModelNotAvailable || e is ModelError.ToolsUnsupported ||
                e is ModelError.ChatGptLimitReached || e is ModelError.ChatGptNotEligible || e is ModelError.SignInExpired || e is ModelError.AccountPlanNotAllowed ||
                (e is ModelError.Server && e.status in 400..499)) r.disable()
            null
        }
        val first = light?.parts?.firstActionCall()
        if (first != null && !needsMain(first)) return light to picked
        r.noteTrouble()
        val text = light?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), first?.name, first?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            light?.usage?.inputTokens, light?.usage?.outputTokens, light?.usage?.cachedInputTokens, now() - asked,
            picked.name, ROLE_LIGHT_REJECTED,
        ))
        emit(RunEvent.State(now(), "light_to_main", first?.name ?: if (light == null) "light_error" else "no_tool"))
        onText?.invoke("")
        restarted(now())
        return ask(history, specs, step, onText = onText) to lastMainUsed
    }

    private fun needsMain(call: Part.ToolCall): Boolean =
        ModelRouter.mainOnly(call.name, tools.verb(call.name)) ||
            (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, parseArgs(call.argsJson)))

    /**
     * 问 [using](默认主模型):发出的是给它看的那份历史([viewFor])压缩后的副本。
     * 问主模型时走主模型链:[complete] 放弃(重试耗尽或不可重试)且 [Failover] 说换,就换链上下一个再问;
     * 链用完、或 [Failover] 说不换,原样抛出,失败说法和没有备援时一样。问别的模型照旧单发。
     */
    suspend fun ask(history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null): ModelReply {
        if (using !== model) return complete(viewFor(history, using), specs, using, retry, step, onText)
        // 整条链都失败时的说法(MK-1 第 2 批):以主模型最初的错误为主、附每个备援错在哪(见 [terminalError]);
        // 只试过一个模型就原样交出,和没有备援时一致。每步先把错误记进 [trialLog]:切过去之后,
        // 后面的步直接由备援起问,主模型的错不在手边,靠这份记录才拼得回整条链的说法。
        while (true) {
            val current = chain[chainIndex]
            try {
                val reply = complete(viewFor(history, current), specs, current, retry, step, onText)
                lastMainUsed = current
                return reply
            } catch (e: ModelError) {
                if (trialLog.none { it.first == chainIndex }) trialLog += chainIndex to e
                if (chainIndex == 0 && headError == null) headError = e
                val next = Failover.nextIndex(e, chainIndex, chainProviderIds, chain.size) ?: throw terminalError(e)
                onText?.invoke("")
                emit(RunEvent.State(now(), "model_switch", "${current.name} -> ${chain[next].name} ${RetryPolicy.label(e)}"))
                chainIndex = next
            }
        }
    }

    /** 链上不再换了,决定交出哪个错误:预算错误原样;只试过一个模型原样;试过整条链包成 [ModelError.ChainFailed]。 */
    private fun terminalError(e: ModelError): ModelError {
        // 当前错误就是预算错误时以它为准;否则链上较早的预算错误优先于包装(审 T-1112-74a8f2 D1):预算错误不包 ChainFailed,原样交出
        if (e is ModelError.TaskBudgetExceeded || e is ModelError.DailyBudgetExceeded) return e
        trialLog.map { it.second }.firstOrNull {
            it is ModelError.TaskBudgetExceeded || it is ModelError.DailyBudgetExceeded
        }?.let { return it }
        return if (trialLog.size < 2) e
        else ModelError.ChainFailed(headError ?: e, trialLog.map { (index, err) -> chain[index].name to err })
    }

    /**
     * 发给 [to] 的历史:不是 [to] 给出的回复去掉思考内容和调用上的私有数据(这些只能原样回给产生它的那个模型)。
     * 没记到来源的回复视同主模型给的:发给主模型时留着,发给轻量模型或备援时都剥。只看字段在不在,不分服务商。
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        if (replyOrigin.isEmpty() && to === model) return history
        return history.map { m ->
            val origin = replyOrigin[m]
            val foreign = m.role == Role.Assistant && (origin?.let { it !== to } ?: (to !== model))
            if (!foreign || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
            else m.copy(parts = m.parts.mapNotNull { p ->
                when {
                    p is Part.Thinking -> null
                    p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                    else -> p
                }
            })
        }
    }

    private suspend fun complete(history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null): ModelReply {
        var retries = 0
        lastRetryWaitMs = 0
        // 第一次真正发出请求的时刻;之前的准备(压缩、等冷却窗口)不算重试等待
        var firstAttemptAt: Long? = null
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val compressor = compressorFor(using)
        var compaction = compactions[using] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            val raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            val restored = ContextRecovery.ensure(history, raw.messages)
            val sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            val (next, report) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            if (report) emit(RunEvent.Compressed(now(), sent.foldedSteps))
            compaction = next
            compactions[using] = compaction
            try {
                // 差异的 base 被裁掉或折叠时,下一次写操作要发全量
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(sent.messages))
                // 撞过 429 的话等全局冷却窗口过去再发(不在冷却立即返回,零开销)
                RateLimitGovernor.awaitClear(using.name)
                // 从第一次发出请求起算:失败的请求和退避才算重试等待,第一次发出之前的准备不算
                val sentAt = now()
                lastRetryWaitMs = firstAttemptAt?.let { sentAt - it } ?: 0L
                if (firstAttemptAt == null) firstAttemptAt = sentAt
                suspend fun invokeModel(): ModelReply {
                    val prompt = promptTransform?.invoke(systemPrompt) ?: systemPrompt
                    return if (onText == null) using.complete(prompt, sent.messages, specs)
                    else if (using is StreamingChatModel) using.completeStreaming(prompt, sent.messages, specs, onText)
                    else using.complete(prompt, sent.messages, specs).also { result ->
                        result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                            .takeIf(String::isNotEmpty)?.let(onText)
                    }
                }
                suspend fun send(): ModelReply =
                    if (currentCoroutineContext()[ConversationMeter] != null && roleOf(using) == ROLE_MAIN)
                        withContext(ConversationMeter.MainRequest(step)) { invokeModel() }
                    else invokeModel()
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + raw.foldedSteps
                        raw.rewritten && current != sent.messages -> "history_rewritten folded=" + raw.foldedSteps
                        else -> null
                    }
                    overflowRewrote = false
                    trace.modelCall(step, using, tools::verb, change, sent.messages) { send() }
                }
                return reply.also { r ->
                    RateLimitGovernor.reportSuccess(using.name)
                    lastOverhead[using] = overhead
                    r.usage?.let { compressor.calibrate(sent.estimate + overhead, it.inputTokens) }
                }
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[using] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                if (e is ModelError.RateLimited) {
                    // 429：记进全局冷却窗口，别的任务/子代理也一起等；没有重试额度时立刻交给链路切换。
                    val wait = RateLimitGovernor.reportRateLimited(using.name, e.retryAfterMs)
                    val hasFallback = chainIndex + 1 < chain.size && chain[chainIndex] === using
                    val withinRetryLimit = retries < policy.maxRetries &&
                        (!hasFallback || retries < RATE_LIMIT_FAILOVER_RETRIES)
                    val withinWaitLimit = !hasFallback || wait <= RATE_LIMIT_FAILOVER_WAIT_BUDGET_MS
                    if (!withinRetryLimit || !withinWaitLimit || taskRetryWaitMs + wait > policy.taskWaitBudgetMs) throw e
                    retries += 1
                    taskRetryWaitMs += wait
                    emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} rate_limited #$retries ${wait}ms"))
                    continue
                }
                val wait = policy.delayFor(e, retries, taskRetryWaitMs) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // 等待中取消任务就此结束
                delay(wait)
            }
        }
    }

    companion object {
        /** [RunEvent.ModelReply.role] 的取值;分工没起作用时为 null。 */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** 轻量模型的回复没执行,这一步改问了主模型;仍是一次模型调用,用量照记。 */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
        /** 有备援时只给当前主模型一次 429 重试机会，避免在单模型冷却里耗尽任务预算。 */
        private const val RATE_LIMIT_FAILOVER_RETRIES = 1
        /** 有备援时，超过这段 Retry-After 就直接切换备援。 */
        private const val RATE_LIMIT_FAILOVER_WAIT_BUDGET_MS = 5_000L
    }
}

/** 发出的历史里还完整可见的读取(按编号):带元素列表的读取结果与带 screen 对象的动作结果。 */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf(ScreenElementTable::hasRows) ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}
