package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.JsonHttp
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/**
 * 各家搜索服务商共用的那一段:发请求、把常见的 HTTP 状态换成给模型的失败、读 JSON。
 * 各家特殊的状态由 [special] 先认:可以是一种失败(比如 SearXNG 的 403),也可以是结果(比如 Jina 零命中回的 422)。
 */
internal object SearchHttp {
    /** 一条摘要最多这么长:端点不可信,不能把整页正文塞进模型上下文。 */
    private const val MAX_SNIPPET_CHARS = 600

    fun json(body: JsonObject): RequestBody = JsonHttp.json(body)

    suspend fun call(
        client: OkHttpClient,
        request: Request,
        special: (Response) -> WebSearchResult? = { null },
        parse: (JsonObject) -> WebSearchResult,
    ): WebSearchResult = JsonHttp.call(
        client, request, "search", special = special,
        failure = { WebSearchResult.Failure(it.code, it.message, it.retryAfterSeconds) },
        parse = parse,
    )

    fun text(o: JsonObject, name: String): String = JsonHttp.text(o, name)

    /** 没有网址的结果不要;摘要截短。 */
    fun hit(title: String, url: String, snippet: String): WebSearchHit? =
        if (url.isBlank()) null else WebSearchHit(title, url, snippet.trim().let(::clip))

    /** 按码点截短:摘要来自不可信端点,emoji 等代理对不能被切成半个字符。 */
    private fun clip(s: String): String =
        if (s.codePointCount(0, s.length) <= MAX_SNIPPET_CHARS) s
        else s.substring(0, s.offsetByCodePoints(0, MAX_SNIPPET_CHARS)) + "…"
}