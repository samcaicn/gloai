package com.wangzan101.mika.core.fetch

import com.wangzan101.mika.core.net.JsonHttp
import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TinyFish 抓取:`POST https://api.fetch.tinyfish.ai`,请求头 `X-API-Key`,
 * 请求体 `{"urls":[网址],"format":"markdown","ttl":0,"per_url_timeout_ms":45000}`。
 * 返回 `results[]`(title、final_url、text)和 `errors[]`;per-URL 的失败在 200 的 `errors[]` 里,不是 HTTP 错误。
 * 单个网址的失败按它的 error 码说清楚;其它码原样报出。
 */
class TinyFishFetch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebFetch {
    private val key = apiKey.trim()

    override suspend fun fetch(url: String): WebFetchResult {
        val target = WebFetch.parseUrl(url)
            ?: return WebFetchResult.Failure("invalid_url", "Enter an HTTP or HTTPS address.")
        val body = buildJsonObject {
            putJsonArray("urls") { add(JsonPrimitive(target.toString())) }
            put("format", "markdown")
            // 0:要这一页此刻的内容,不吃缓存
            put("ttl", 0)
            put("per_url_timeout_ms", PER_URL_TIMEOUT_MS)
        }
        val request = Request.Builder().url(this.url).header("X-API-Key", key).post(JsonHttp.json(body)).build()
        return JsonHttp.call(
            client, request, "fetch", timeoutSec = TIMEOUT_SEC,
            failure = { WebFetchResult.Failure(it.code, it.message, it.retryAfterSeconds) },
            parse = ::parse,
        )
    }

    private fun parse(root: JsonObject): WebFetchResult {
        (root["errors"] as? JsonArray)?.firstOrNull()?.let { item ->
            val o = item as? JsonObject ?: return@let
            val code = JsonHttp.text(o, "error").ifBlank { return@let }
            return WebFetchResult.Failure(code, message(code, o))
        }
        val result = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return WebFetchResult.Failure("invalid_response", "The TinyFish fetch response has no results array.")
        val text = JsonHttp.text(result, "text")
        if (text.isEmpty()) return WebFetchResult.Failure("empty_content", "The fetched page has no readable text.")
        val requested = JsonHttp.text(result, "url")
        val final = JsonHttp.text(result, "final_url").ifBlank { requested }
        return WebFetchResult.Success(WebPage(url = requested, finalUrl = final, title = JsonHttp.text(result, "title"), text = text))
    }

    /** TinyFish 的 per-URL 错误码换成给模型的说法;状态码能带就带上。 */
    private fun message(code: String, o: JsonObject): String {
        val status = (o["status"] as? JsonPrimitive)?.intOrNull
        return when (code) {
            "target_http_error" -> "The page returned HTTP ${status ?: "error"}."
            "page_not_found" -> if (status == 404) "The page was not found (HTTP 404)." else "The page was not found."
            "target_unreachable" -> "The page could not be reached."
            "timeout" -> "The page did not finish loading in time; retry later or use the browser."
            "bot_blocked" -> "The site blocked the fetcher; use the browser instead."
            "empty_content" -> "The page has no readable text."
            "login_required" -> "The page needs a sign-in; use the browser and hand it to the user if needed."
            "content_too_large" -> "The page is too large to fetch."
            "invalid_url" -> "The service rejected the page address."
            "invalid_redirect_url" -> "The page redirected to an address the service rejects."
            "proxy_error" -> "The service could not reach the page through its proxy."
            else -> "The page could not be fetched ($code)."
        }
    }

    companion object {
        const val URL = "https://api.fetch.tinyfish.ai"
        /** 单页最长等 45 秒;客户端多等一点好收到超时错误。 */
        private const val PER_URL_TIMEOUT_MS = 45_000
        private const val TIMEOUT_SEC = 60
    }
}