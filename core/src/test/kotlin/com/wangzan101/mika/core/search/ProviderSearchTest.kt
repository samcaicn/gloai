package com.wangzan101.mika.core.search

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SearXNG、Jina、Exa、DeepSeek 几家的请求形状、解析和出错时的说法。 */
class ProviderSearchTest {
    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun withServer(response: MockResponse, block: suspend (MockWebServer) -> Unit) = runTest {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try { block(server) } finally { server.shutdown() }
    }

    private val client = OkHttpClient()

    // ---------- SearXNG ----------

    @Test fun searxngIsAJsonGetOnTheInstance() = withServer(ok("""{"results":[
        {"title":"T1","url":"https://a.example","content":"C1","engine":"bing"},
        {"title":"T2","url":"https://b.example","content":"C2"},
        {"title":"T3","url":"https://c.example","content":"C3"}]}""")) { server ->
        val result = SearxngSearch(server.url("/searx/"), client).search("安卓", 2)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/searx/search?q=%E5%AE%89%E5%8D%93&format=json", request.path)
        assertEquals(listOf(WebSearchHit("T1", "https://a.example", "C1"), WebSearchHit("T2", "https://b.example", "C2")), (result as WebSearchResult.Success).hits)
    }

    @Test fun searxngAcceptsAnAddressThatAlreadyEndsInSearch() = withServer(ok("""{"results":[]}""")) { server ->
        SearxngSearch(server.url("/search"), client).search("q", 5)
        assertEquals("/search?q=q&format=json", server.takeRequest().path)
    }

    @Test fun searxngAcceptsASearchAddressWithATrailingSlash() = withServer(ok("""{"results":[]}""")) { server ->
        SearxngSearch(server.url("/search/"), client).search("q", 5)
        assertEquals("/search/?q=q&format=json", server.takeRequest().path)
    }

    @Test fun searxngAcceptsANestedSearchAddressWithATrailingSlash() = withServer(ok("""{"results":[]}""")) { server ->
        SearxngSearch(server.url("/foo/search/"), client).search("q", 5)
        assertEquals("/foo/search/?q=q&format=json", server.takeRequest().path)
    }

    @Test fun searxng403MeansJsonIsOffNotABadKey() = withServer(MockResponse().setResponseCode(403).setBody("Forbidden")) { server ->
        val failure = SearxngSearch(server.url("/"), client).search("q", 5) as WebSearchResult.Failure
        assertEquals("json_disabled", failure.code)
        assertTrue(failure.message.contains("search.formats"))
    }

    // ---------- Jina ----------

    @Test fun jinaIsAPostWithBearerAndNoContent() = withServer(ok("""{"code":200,"status":20000,"data":[
        {"title":"J1","url":"https://j.example/1","description":"D1","usage":{"tokens":1}},
        {"title":"J2","url":"https://j.example/2","description":"","content":"only content"}]}""")) { server ->
        val result = JinaSearch(" jina-key ", client, server.url("/")).search("mika", 3)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer jina-key", request.getHeader("Authorization"))
        assertEquals("no-content", request.getHeader("X-Respond-With"))
        assertEquals("application/json", request.getHeader("Accept"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("mika", sent["q"]!!.jsonPrimitive.content)
        assertEquals(3, sent["num"]!!.jsonPrimitive.int)
        assertEquals(
            listOf(WebSearchHit("J1", "https://j.example/1", "D1"), WebSearchHit("J2", "https://j.example/2", "only content")),
            (result as WebSearchResult.Success).hits,
        )
    }

    @Test fun jinaErrorEnvelopeIsNotParsedAsResults() = withServer(ok("""{"data":null,"code":401,"name":"AuthenticationFailedError"}""")) { server ->
        assertEquals("invalid_response", (JinaSearch("k", client, server.url("/")).search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun jinaNoResults422IsAnEmptySuccess() = withServer(
        MockResponse().setResponseCode(422).setBody(
            // 真机实测的零命中返回
            """{"data":null,"code":422,"name":"AssertionFailureError","status":42206,"message":"No search results available for query zq site:example.invalid","readableMessage":"AssertionFailureError: No search results available for query zq site:example.invalid"}""",
        ),
    ) { server ->
        assertEquals(WebSearchResult.Success(emptyList()), JinaSearch("k", client, server.url("/")).search("zq site:example.invalid", 3))
    }

    @Test fun otherJina422IsStillAFailure() = withServer(
        MockResponse().setResponseCode(422).setBody("""{"data":null,"code":422,"name":"ParamValidationError","status":42200,"message":"Invalid num"}"""),
    ) { server ->
        assertEquals("service_unavailable", (JinaSearch("k", client, server.url("/")).search("q", 3) as WebSearchResult.Failure).code)
    }

    @Test fun jinaRejectedKeyIsAuthenticationFailure() = withServer(MockResponse().setResponseCode(401).setBody("""{"data":null,"code":401}""")) { server ->
        assertEquals("authentication_failed", (JinaSearch("bad", client, server.url("/")).search("q", 5) as WebSearchResult.Failure).code)
    }

    // ---------- Exa ----------

    @Test fun exaIsAPostAskingForHighlights() = withServer(ok("""{"requestId":"r","results":[
        {"id":"1","title":"E1","url":"https://e.example/1","highlights":["first part","second part"]},
        {"id":"2","title":"E2","url":"https://e.example/2","summary":"a summary"}]}""")) { server ->
        val result = ExaSearch("exa-key", client, server.url("/search")).search("mika", 2)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/search", request.path)
        assertEquals("Bearer exa-key", request.getHeader("Authorization"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("mika", sent["query"]!!.jsonPrimitive.content)
        assertEquals(2, sent["numResults"]!!.jsonPrimitive.int)
        assertTrue(sent["contents"]!!.jsonObject["highlights"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf(WebSearchHit("E1", "https://e.example/1", "first part … second part"), WebSearchHit("E2", "https://e.example/2", "a summary")),
            (result as WebSearchResult.Success).hits,
        )
    }

    @Test fun exaPaymentRequiredIsQuota() = withServer(MockResponse().setResponseCode(402).setBody("""{"tag":"X402_PAYMENT_REQUIRED"}""")) { server ->
        assertEquals("quota_exhausted", (ExaSearch("k", client, server.url("/search")).search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun exaRateLimitCarriesRetryAfter() = withServer(MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("{}")) { server ->
        assertEquals(5L, (ExaSearch("k", client, server.url("/search")).search("q", 5) as WebSearchResult.Failure).retryAfterSeconds)
    }

    // ---------- DeepSeek ----------

    @Test fun deepSeekIsAPostWithBearerAndCount() = withServer(ok("""{"results":[
        {"rerank_score":2.4,"title":"D1","url":"https://d.example/1","snippet":"S1","summary":"sum1"},
        {"title":"D2","url":"https://d.example/2","snippet":"","summary":"sum2"},
        {"title":"no url","url":"","snippet":"dropped"}]}""")) { server ->
        val result = DeepSeekSearch(" deepseek-key ", client, server.url("/v1/tools/search")).search("mika", 3)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/tools/search", request.path)
        assertEquals("Bearer deepseek-key", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("mika", sent["query"]!!.jsonPrimitive.content)
        assertEquals(3, sent["count"]!!.jsonPrimitive.int)
        assertEquals(false, sent["need_content"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf(WebSearchHit("D1", "https://d.example/1", "S1"), WebSearchHit("D2", "https://d.example/2", "sum2")),
            (result as WebSearchResult.Success).hits,
        )
    }

    @Test fun deepSeekWithoutResultsArrayIsInvalidResponse() = withServer(ok("""{"error":"x"}""")) { server ->
        assertEquals("invalid_response", (DeepSeekSearch("k", client, server.url("/")).search("q", 5) as WebSearchResult.Failure).code)
    }

    @Test fun deepSeekRejectedKeyIsAuthenticationFailure() = withServer(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}""")) { server ->
        assertEquals("authentication_failed", (DeepSeekSearch("bad", client, server.url("/")).search("q", 5) as WebSearchResult.Failure).code)
    }

    // ---------- 共用 ----------

    @Test fun providersNeedTheRightFields() {
        assertEquals(WebSearchProvider.Google, WebSearchProvider.entries.first())
        assertEquals(false, WebSearchProvider.Google.takesKey)
        assertEquals(false, WebSearchProvider.Google.needsEndpoint)
        assertEquals(false, WebSearchProvider.Google.keyRequired)
        assertEquals(null, WebSearchProvider.Google.problem("", "", groundingAvailable = true))
        assertEquals(WebSearchProvider.Problem.UnsupportedProvider, WebSearchProvider.Google.problem("", "", groundingAvailable = false))
        assertEquals(null, WebSearchProvider.You.problem("", ""))
        assertEquals(WebSearchProvider.Problem.Key, WebSearchProvider.Jina.problem("", " "))
        assertEquals(WebSearchProvider.Problem.Key, WebSearchProvider.Exa.problem("", ""))
        assertEquals(null, WebSearchProvider.Exa.problem("", "k"))
        assertEquals(WebSearchProvider.Problem.Key, WebSearchProvider.DeepSeek.problem("", ""))
        assertEquals(null, WebSearchProvider.DeepSeek.problem("", "k"))
        assertEquals(WebSearchProvider.Problem.Endpoint, WebSearchProvider.SearXNG.problem("not a url", ""))
        assertEquals(null, WebSearchProvider.SearXNG.problem("https://searx.example.org", ""))
    }

    @Test fun onlyPlainHttpAddressesAreAccepted() {
        assertEquals(null, WebSearch.parseEndpoint(""))
        assertEquals(null, WebSearch.parseEndpoint("ftp://example.com/search"))
        assertEquals(null, WebSearch.parseEndpoint("https://user:pass@example.com/search"))
        assertEquals(null, WebSearch.parseEndpoint("https://:pass@example.com/search"))
        assertEquals(null, WebSearch.parseEndpoint("https://example.com/search?key=value"))
        assertTrue(WebSearch.parseEndpoint("http://127.0.0.1:1234/") != null)
    }
}
