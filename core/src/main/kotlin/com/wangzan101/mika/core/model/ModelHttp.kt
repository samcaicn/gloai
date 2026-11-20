package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 对话适配器以外也要照同一套说法报错的地方(比如语音转文字)用:发一个请求,把 HTTP 状态和报错内容转成 [ModelError]。
 * 映射本身从各适配器原样搬来,适配器改成调这里,避免两份各改各的。
 */
object ModelHttp {
    class Reply(val status: Int, val retryAfter: String?, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** 发出请求并读完回复;网络失败抛 [ModelError.Network],取消协程会取消请求。 */
    suspend fun send(http: OkHttpClient, request: Request): Reply =
        HttpSupport.execute(http, request).let { Reply(it.status, it.retryAfter, it.body) }

    /**
     * OpenAI 兼容接口(对话、转写同一套)的错误映射。[model] 是请求的模型名。
     * 没有更具体说法的 HTTP 错误(>= 400)一律归 [ModelError.Server](MK-1 第 2 批:分类依状态码,不依 body 能否解析,
     * 备援链接得住);只有 HTTP 200 的内容解析不了才是 [ModelError.BadResponse]。
     */
    fun openAiError(status: Int, retryAfter: String?, body: String, model: String): ModelError =
        ModelAccessErrors.of(status, body, model) ?: reasoningEffortRejected(status, body) ?: when {
            status == 401 -> ModelError.Auth()
            status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
            status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter, body))
            contextTooLong(status, body) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            status == 403 -> ModelError.Auth()
            else -> if (status >= 400) ModelError.Server(status, ModelAccessErrors.detail(body)) else ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    /** HTTP 200 SSE 中的错误帧也按 HTTP 错误的分类交给重试和备援。 */
    fun streamError(root: JsonObject): ModelError? {
        val error = root["error"] ?: return null
        val errorObject = error as? JsonObject
        val type = (errorObject?.get("type") as? JsonPrimitive)?.contentOrNull
            ?: (root["type"] as? JsonPrimitive)?.contentOrNull
        val detail = (errorObject?.get("message") as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "rate_limit_error" -> ModelError.RateLimited(null)
            "overloaded_error" -> ModelError.Server(529, detail)
            else -> if (saysContextTooLong(error.toString())) ModelError.ContextTooLong()
            else ModelError.BadResponse(error.toString().take(200))
        }
    }

    /** HTTP 400 的报错说请求超出了模型的上下文(见 [saysContextTooLong])。OpenAI 兼容、Responses、Anthropic 三种接口共用;本机跑的服务三种接口都可能开。 */
    fun contextTooLong(status: Int, body: String): Boolean = status == 400 && saysContextTooLong(body)

    /** HTTP 400 明确指出思考强度参数无效。只认参数名或完整的协议字段,不从任意“unsupported”文字猜。 */
    fun reasoningEffortRejected(status: Int, body: String): ModelError.ReasoningEffortRejected? {
        if (status != 400) return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val param = (error?.get("param") as? JsonPrimitive)?.contentOrNull ?: (root?.get("param") as? JsonPrimitive)?.contentOrNull
        val lower = body.lowercase()
        val namesParameter = param in setOf("reasoning.effort", "reasoning_effort") ||
            (param == null && ("reasoning.effort" in lower || "reasoning_effort" in lower))
        val rejected = listOf("invalid", "unsupported", "not supported", "unknown").any { it in lower }
        return if (namesParameter && rejected) ModelError.ReasoningEffortRejected(ModelAccessErrors.detail(body)) else null
    }

    /**
     * 报错内容说请求超出了模型的上下文。各家原话不同(`context_length_exceeded`、「maximum context length」、
     * 「exceeds the available context size」、「is longer than the model's context length」、「exceeds the context window」、
     * 「is greater than the context length」、带图时的「is longer than the maximum model length」…):
     * 认「超出 / 长于 / 大于 / max」后面不远处(同一句里)跟着上下文的长度、大小、上限、窗口或模型长度,不分大小写;
     * 原来认的 `context_length`、「maximum context」照旧;另认一个本机服务旧版的「loaded with context length of only」。
     * 只提到「context window」这类词不算:报错里可能回显了请求里的话。
     */
    internal fun saysContextTooLong(text: String): Boolean = CONTEXT_TOO_LONG.containsMatchIn(text)

    private val CONTEXT_TOO_LONG = Regex(
        """(?:exceed|longer than|larger than|greater than|max)[^.\n]{0,40}?(?:context[ _](?:length|size|limit|window)|model[ _]length)|context_length|maximum context|context length of only""",
        RegexOption.IGNORE_CASE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Gemini 原生接口的错误映射。 */
    fun geminiError(status: Int, retryAfter: String?, body: String, model: String): ModelError {
        val err = try { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject } catch (e: Exception) { null }
        val st = (err?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
        val lower = msg.lowercase()
        ModelAccessErrors.of(status, body, model)?.let { return it }
        return when {
            status == 401 || status == 403 -> ModelError.Auth()
            // 这个模型额度为零的 429 上面已认成用不了;其余 429 按可重试限流处理(其限流与额度文案混用,无法可靠区分)
            status == 429 || st == "RESOURCE_EXHAUSTED" -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter, msg))
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status == 413 || (status == 400 && lower.contains("token") &&
                (lower.contains("exceed") || lower.contains("too long") || lower.contains("maximum"))) -> ModelError.ContextTooLong()
            status in 500..599 -> ModelError.Server(status)
            else -> if (status >= 400) ModelError.Server(status, ModelAccessErrors.detail(body)) else ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }
    }
}
