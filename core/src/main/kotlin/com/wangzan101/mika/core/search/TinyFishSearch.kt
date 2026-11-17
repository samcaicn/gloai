package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TinyFish 搜索:`GET https://api.search.tinyfish.ai?query=`,请求头 `X-API-Key`。
 * 返回 `results[]`,每条有 title、url、snippet;接口按页给结果,没有条数参数,这里只取前 count 条。
 */
class TinyFishSearch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey.trim()

    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val request = Request.Builder().url(url.newBuilder().addQueryParameter("query", query).build())
            .header("X-API-Key", key).get().build()
        return SearchHttp.call(client, request) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The TinyFish response has no results array.")
        return WebSearchResult.Success(results.take(count).mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), SearchHttp.text(o, "snippet"))
        })
    }

    companion object {
        const val URL = "https://api.search.tinyfish.ai"
    }
}