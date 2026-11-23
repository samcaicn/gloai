package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.time.Instant

/** 一次调用是为哪个用途发的。存储里记 [key];认不出的 key(以后的版本写的)读成 [Other]。 */
enum class UsagePurpose(val key: String) {
    Main("main"), Vision("vision"), Light("light"), Other("other"), Proactive("proactive");

    companion object {
        fun of(role: ModelRole): UsagePurpose = when (role) {
            ModelRole.Main -> Main
            ModelRole.Vision -> Vision
            ModelRole.Light -> Light
        }

        fun of(key: String): UsagePurpose = entries.firstOrNull { it.key == key } ?: Other
    }
}

/** 一个模型是谁的、为哪个用途建的:记用量时原样带上。[providerName] 是建模型时服务商的名字(服务商删了也留着)。 */
data class UsageSource(
    val providerId: String, val providerName: String, val model: String, val purpose: UsagePurpose,
    val deepSeekDirect: Boolean = false,
)

/**
 * 一次逻辑调用的结果:[usage] 为 null 表示服务商没报用量(或调用失败),不估 token。
 * [httpAttempts] 是这次调用实际发出的推理请求数;两参数形式保留给不关心请求数的观察者。
 */
fun interface UsageSink {
    fun record(source: UsageSource, usage: Usage?)

    fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) = record(source, usage)
}

/**
 * 给模型套上计量:每次 [ChatModel.complete](及逐段文字的那一路)结束都记一笔,成功且带用量的记 token。
 * 适配器为兼容服务而重发的每个 HTTP 请求都计入次数,最终回复的 token 只记一次;没发请求的本地失败不算 HTTP 请求。
 * 对调用方透明:名字、能力照旧,异常原样抛出,逐段文字照常回调;原模型支持逐段文字,套上的也支持。
 * 记账本身出错不影响调用。
 */
fun ChatModel.metered(
    source: UsageSource, sink: UsageSink,
    /**
     * 适配器在同一次调用里再发一个请求(兼容重试、刷新令牌后重发)之前调,第二个参数是这次调用已经发出的请求数——
     * 它们要等这次调用结束才记进账本,按次数设上限的调用方得自己把它们加上。抛出就不再发,这次调用以这个错误结束。
     */
    beforeRetry: (UsageSource, Int) -> Unit = { _, _ -> },
    beforeCall: (UsageSource) -> Unit = {},
): ChatModel =
    if (this is StreamingChatModel) MeteredStreamingModel(this, source, sink, beforeCall, beforeRetry)
    else MeteredModel(this, source, sink, beforeCall, beforeRetry)

private open class MeteredModel(
    private val inner: ChatModel,
    protected val source: UsageSource,
    protected val sink: UsageSink,
    private val beforeCall: (UsageSource) -> Unit,
    private val beforeRetry: (UsageSource, Int) -> Unit,
) : ChatModel {
    override val capabilities get() = inner.capabilities
    override val name: String get() = inner.name

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        counted(system, messages, tools) { inner.complete(system, messages, tools) }

    protected suspend fun counted(system: String, messages: List<Message>, tools: List<ToolSpec>, call: suspend () -> ModelReply): ModelReply {
        // 在一次运行里调用的,也记到这次运行名下(见 RunUsageTally)
        val context = currentCoroutineContext()
        val tally = context[RunUsageTally]
        val conversation = context[ConversationMeter]
        // 拒绝时不建请求计数、不调用适配器，也不记一笔不存在的逻辑调用。
        tally?.checkBudget()
        beforeCall(source)
        val step = context[ConversationMeter.MainRequest]?.step
        if (step != null && conversation != null) {
            runCatching { conversation.request(RequestComposition.estimate(name, step, capabilities.contextTokens, system, tools, messages)) }
        }
        val attempts = ModelHttpAttempts { started -> beforeRetry(source, started) }
        val startedAt = tally?.deepSeekCost?.let { Instant.now() }
        val reply = try {
            withContext(attempts) { call() }
        } catch (e: Throwable) {
            runCatching { conversation?.record(null, attempts.started) }
            note(null, tally, attempts.started, startedAt)
            throw e
        }
        runCatching { conversation?.record(reply.usage, attempts.started) }
        note(reply.usage, tally, attempts.started, startedAt)
        return reply
    }

    private fun note(usage: Usage?, tally: RunUsageTally?, httpAttempts: Int, startedAt: Instant?) {
        runCatching { sink.record(source, usage, httpAttempts) }
        tally?.add(usage, httpAttempts)
        if (startedAt != null) tally?.deepSeekCost?.add(source.model.takeIf { source.deepSeekDirect }, startedAt, usage, httpAttempts)
    }
}

private class MeteredStreamingModel(
    private val inner: StreamingChatModel,
    source: UsageSource,
    sink: UsageSink,
    beforeCall: (UsageSource) -> Unit,
    beforeRetry: (UsageSource, Int) -> Unit,
) : MeteredModel(inner, source, sink, beforeCall, beforeRetry), StreamingChatModel {
    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = counted(system, messages, tools) { inner.completeStreaming(system, messages, tools, onText) }
}
