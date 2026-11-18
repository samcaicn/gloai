package com.wangzan101.mika.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 各家外部 JSON 接口(网页搜索、网页抓取)共用的那一段:发请求、把常见的 HTTP 状态换成给模型的失败、读 JSON。
 * 各家特殊的状态由 [call] 的 special 先认:可以是一种失败,也可以是结果(比如 Jina 零命中回的 422)。
 * [service] 只进给模型的说法("search service"、"fetch service")。
 */
internal object JsonHttp {
    private const val MAX_RESPONSE_CHARS = 1_000_000
    private val JSON_TYPE = "application/json".toMediaType()
    private const val USER_AGENT = "Mika (+https://github.com/wangzan101/Mika)"

    /** 一次调用没成的原因;由调用方换成自己的失败类型。 */
    data class Failure(val code: String, val message: String, val retryAfterSeconds: Long? = null)

    fun json(body: JsonObject): RequestBody = body.toString().toRequestBody(JSON_TYPE)

    /**
     * 返回 [T]:special 先认响应(返回结果就照它的),再按 HTTP 状态换成失败,否则读 JSON 交给 parse。
     * 网络不通时也走 [failure]。
     */
    suspend fun <T> call(
        client: OkHttpClient,
        request: Request,
        service: String,
        timeoutSec: Int? = null,
        special: (Response) -> T? = { null },
        failure: (Failure) -> T,
        parse: (JsonObject) -> T,
    ): T {
        val r = request.newBuilder().header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        return try {
            client.newCall(r).awaitBody(timeoutSec) { response ->
                special(response)?.let { return@awaitBody it }
                failureOf(response, service)?.let { return@awaitBody failure(it) }
                val body = response.body?.charStream()?.use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        // 多读一个字符判定超限，避免等待超大正文全部下载完成。
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - text.length + 1))
                        if (count == -1) break
                        if (text.length + count > MAX_RESPONSE_CHARS) {
                            return@awaitBody failure(Failure("invalid_response", "The $service service response is too large."))
                        }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }.orEmpty()
                val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (e: IllegalArgumentException) { null }
                    ?: return@awaitBody failure(Failure("invalid_response", "The $service service did not return a JSON object."))
                parse(root)
            }
        } catch (e: IOException) {
            failure(Failure("offline", "The $service service could not be reached; check the network and proxy."))
        }
    }

    private fun failureOf(response: Response, service: String): Failure? = when (response.code) {
        in 200..299 -> null
        401, 403 -> Failure("authentication_failed", "The $service service rejected the configured API key.")
        402 -> Failure("quota_exhausted", "The $service service reports that its quota or credits are exhausted.")
        429 -> Failure(
            "rate_limited", "The $service service is rate limiting requests; retry later or use the browser.",
            response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0),
        )
        else -> Failure("service_unavailable", "The $service service returned HTTP ${response.code}.")
    }

    /** 读一个字符串字段;没有、不是字符串、全是空白都按空串。 */
    fun text(o: JsonObject, name: String): String = (o[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}