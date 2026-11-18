package com.wangzan101.mika.core.fetch

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TinyFish 抓取的请求形状、解析和出错时的说法。 */
class TinyFishFetchTest {
    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun withServer(response: MockResponse, block: suspend (MockWebServer) -> Unit) = runTest {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try { block(server) } finally { server.shutdown() }
    }

    private val client = OkHttpClient()

    @Test fun isAnApiKeyPostAskingForMarkdown() = withServer(ok("""{"results":[{
        "url":"https://example.com/a?x=1","final_url":"https://example.com/a","title":"Example","text":"# Heading\n\nBody"}],"errors":[]}""")) { server ->
        val result = TinyFishFetch(" tinyfish-key ", client, server.url("/")).fetch("https://example.com/a?x=1")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        assertEquals("tinyfish-key", request.getHeader("X-API-Key"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("https://example.com/a?x=1", sent["urls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("markdown", sent["format"]!!.jsonPrimitive.content)
        assertEquals(0, sent["ttl"]!!.jsonPrimitive.int)
        assertEquals(45_000, sent["per_url_timeout_ms"]!!.jsonPrimitive.int)
        assertEquals(
            WebFetchResult.Success(WebPage("https://example.com/a?x=1", "https://example.com/a", "Example", "# Heading\n\nBody")),
            result,
        )
    }

    @Test fun aMissingFinalUrlFallsBackToTheRequestedOne() = withServer(ok("""{"results":[{"url":"https://example.com/a","title":null,"final_url":null,"text":"Body"}],"errors":[]}""")) { server ->
        val page = (TinyFishFetch("k", client, server.url("/")).fetch("https://example.com/a") as WebFetchResult.Success).page
        assertEquals(WebPage("https://example.com/a", "https://example.com/a", "", "Body"), page)
    }

    @Test fun aPerUrlErrorIsReportedWithItsCode() = withServer(ok("""{"results":[],"errors":[{"url":"https://example.com/missing","error":"page_not_found","status":404}]}""")) { server ->
        val failure = TinyFishFetch("k", client, server.url("/")).fetch("https://example.com/missing") as WebFetchResult.Failure
        assertEquals("page_not_found", failure.code)
        assertTrue(failure.message.contains("404"))
    }

    @Test fun aBotBlockAsksForTheBrowser() = withServer(ok("""{"results":[],"errors":[{"url":"https://example.com","error":"bot_blocked"}]}""")) { server ->
        val failure = TinyFishFetch("k", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure
        assertEquals("bot_blocked", failure.code)
        assertTrue(failure.message.contains("browser", ignoreCase = true))
    }

    @Test fun anUnknownPerUrlErrorKeepsTheCode() = withServer(ok("""{"results":[],"errors":[{"url":"https://example.com","error":"proxy_error"}]}""")) { server ->
        assertEquals("proxy_error", (TinyFishFetch("k", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure).code)
    }

    @Test fun anEmptyTextIsAFailure() = withServer(ok("""{"results":[{"url":"https://example.com","final_url":"https://example.com","title":"T","text":""}],"errors":[]}""")) { server ->
        assertEquals("empty_content", (TinyFishFetch("k", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure).code)
    }

    @Test fun withoutResultsOrErrorsIsInvalidResponse() = withServer(ok("""{"ok":true}""")) { server ->
        assertEquals("invalid_response", (TinyFishFetch("k", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure).code)
    }

    @Test fun rejectedKeyIsAuthenticationFailure() = withServer(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid API key"}""")) { server ->
        assertEquals("authentication_failed", (TinyFishFetch("bad", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure).code)
    }

    @Test fun paymentRequiredIsQuota() = withServer(MockResponse().setResponseCode(402).setBody("{}")) { server ->
        assertEquals("quota_exhausted", (TinyFishFetch("k", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure).code)
    }

    @Test fun rateLimitCarriesRetryAfter() = withServer(MockResponse().setResponseCode(429).setHeader("Retry-After", "9").setBody("{}")) { server ->
        assertEquals(9L, (TinyFishFetch("k", client, server.url("/")).fetch("https://example.com") as WebFetchResult.Failure).retryAfterSeconds)
    }

    @Test fun theProviderNeedsAKey() {
        assertEquals(WebFetchProvider.Problem.Key, WebFetchProvider.TinyFish.problem(" "))
        assertEquals(null, WebFetchProvider.TinyFish.problem("k"))
    }

    @Test fun onlyPlainHttpAddressesAreAccepted() {
        assertEquals(null, WebFetch.parseUrl(""))
        assertEquals(null, WebFetch.parseUrl("ftp://example.com/file"))
        assertEquals(null, WebFetch.parseUrl("https://user@example.com/"))
        assertTrue(WebFetch.parseUrl("https://example.com/a?x=1") != null)
        assertTrue(WebFetch.parseUrl("http://127.0.0.1:1234/") != null)
    }

    @Test fun aNonHttpAddressNeverReachesTheService() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val port = server.port
            assertEquals("invalid_url", (TinyFishFetch("k", client, server.url("/")).fetch("ftp://example.com/file") as WebFetchResult.Failure).code)
            assertEquals(0, server.requestCount)
            assertEquals("invalid_url", (TinyFishFetch("k", client, server.url("/")).fetch("http://user@127.0.0.1:$port/") as WebFetchResult.Failure).code)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}