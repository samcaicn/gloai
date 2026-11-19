package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DeepSeek 搜索:`POST https://api.deepseek.com/v1/tools/search`,`Authorization: Bearer`,
 * 请求体 `{"query","count","need_content":false}`。只要标题、网址和摘要,不把整页正文拉回来;
 * 结果在 `results[]`,摘要取 snippet,没有时取 summary。密钥和对话用的是同一个 DeepSeek API Key。
 */
class DeepSeekSearch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey.trim()

    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val body = buildJsonObject {
            put("query", query)
            put("count", n)
            // 正文按页计费,摘要够用
            put("need_content", false)
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").post(SearchHttp.json(body)).build()
        return SearchHttp.call(client, request) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The DeepSeek response has no results array.")
        return WebSearchResult.Success(results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(
                SearchHttp.text(o, "title"),
                SearchHttp.text(o, "url"),
                SearchHttp.text(o, "snippet").ifBlank { SearchHttp.text(o, "summary") },
            )
        }.take(count))
    }

    companion object {
        const val URL = "https://api.deepseek.com/v1/tools/search"
    }
}
