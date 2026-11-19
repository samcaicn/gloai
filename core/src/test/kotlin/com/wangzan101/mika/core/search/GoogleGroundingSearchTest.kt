package com.wangzan101.mika.core.search

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleGroundingSearchTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')
    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun geminiGroundingSendsExpectedRequestAndParsesChunksAndSupports() = runTest {
        server.enqueue(
            ok(
                """{
                    "candidates": [{
                        "content": {
                            "role": "model",
                            "parts": [
                                {"text": "internal reasoning", "thought": true},
                                {"text": "Kotlin 2.1.0 is released. It adds guard conditions."}
                            ]
                        },
                        "finishReason": "STOP",
                        "groundingMetadata": {
                            "groundingChunks": [
                                {"web": {"uri": "https://kotlinlang.org/docs/releases.html", "title": "Kotlin releases"}},
                                {"web": {"uri": "https://github.com/JetBrains/kotlin", "title": "JetBrains Kotlin"}},
                                {"web": {"uri": "https://example.org/extra", "title": "Extra source"}}
                            ],
                            "groundingSupports": [
                                {"segment": {"text": "Kotlin 2.1.0 is released."}, "groundingChunkIndices": [0]},
                                {"segment": {"text": "It adds guard conditions."}, "groundingChunkIndices": [1]}
                            ]
                        }
                    }]
                }""",
            ),
        )

        val search = GoogleGroundingSearch.forGemini(
            baseUrl = baseUrl(),
            apiKey = " gk-test ",
            model = "models/gemini-2.5-flash",
            client = client,
            headers = mapOf("User-Agent" to "MikaTest/1.0"),
        )
        val result = search.search("latest kotlin release", count = 2) as WebSearchResult.Success

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", req.path)
        assertEquals("gk-test", req.getHeader("x-goog-api-key"))
        assertEquals("MikaTest/1.0", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Authorization"))

        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val tools = sent["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals(JsonObject(emptyMap()), tools[0].jsonObject["googleSearch"]!!.jsonObject)
        assertTrue(
            sent["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.isNotBlank(),
        )
        assertEquals(
            "latest kotlin release",
            sent["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        assertEquals(2, result.hits.size)
        assertEquals("Kotlin releases", result.hits[0].title)
        assertEquals("https://kotlinlang.org/docs/releases.html", result.hits[0].url)
        assertTrue(result.hits[0].snippet.contains("Kotlin 2.1.0 is released."))
        assertTrue(result.hits[0].snippet.contains("It adds guard conditions."))
        assertEquals("JetBrains Kotlin", result.hits[1].title)
        assertEquals("https://github.com/JetBrains/kotlin", result.hits[1].url)
        assertEquals("It adds guard conditions.", result.hits[1].snippet)
    }

    @Test
    fun parsesByteOffsetSegmentsWithoutExplicitSegmentText() = runTest {
        server.enqueue(
            ok(
                """{
                    "candidates": [{
                        "content": {
                            "role": "model",
                            "parts": [{"text": "Gemini grounded summary."}]
                        },
                        "finishReason": "STOP",
                        "groundingMetadata": {
                            "groundingChunks": [
                                {"web": {"uri": "https://ai.google.dev/gemini-api/docs/grounding", "title": "Google Search Grounding"}}
                            ],
                            "groundingSupports": [
                                {"segment": {"startIndex": 0, "endIndex": 24}, "groundingChunkIndices": [0]}
                            ]
                        }
                    }]
                }""",
            ),
        )

        val search = GoogleGroundingSearch.forGemini(
            baseUrl = baseUrl(),
            apiKey = "gk-test",
            model = "gemini-2.5-flash",
            client = client,
        )
        val result = search.search("gemini grounding", count = 5) as WebSearchResult.Success
        assertEquals(1, result.hits.size)
        assertEquals("Google Search Grounding", result.hits[0].title)
        assertEquals("https://ai.google.dev/gemini-api/docs/grounding", result.hits[0].url)
        assertEquals("Gemini grounded summary.", result.hits[0].snippet)
    }

    @Test
    fun emitsSingleGoogleSearchHitWhenChunksAbsentButAnswerPresent() = runTest {
        server.enqueue(
            ok(
                """{
                    "candidates": [{
                        "content": {
                            "role": "model",
                            "parts": [{"text": "Grounded answer without web chunk citations."}]
                        },
                        "finishReason": "STOP"
                    }]
                }""",
            ),
        )

        val search = GoogleGroundingSearch.forGemini(
            baseUrl = baseUrl(),
            apiKey = "gk-test",
            model = "gemini-2.5-flash",
            client = client,
        )
        val result = search.search("mika android", count = 5) as WebSearchResult.Success
        assertEquals(1, result.hits.size)
        val hit = result.hits[0]
        assertEquals("https://www.google.com/search?q=mika%20android", hit.url)
        assertEquals("Grounded answer without web chunk citations.", hit.snippet)
    }

    @Test
    fun mapsAuthRateLimitBlockedAndIllegalHeaderFailures() = runTest {
        val search = GoogleGroundingSearch.forGemini(
            baseUrl = baseUrl(),
            apiKey = "gk-test",
            model = "gemini-2.5-flash",
            client = client,
        )

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}"""))
        assertEquals("authentication_failed", (search.search("q", 5) as WebSearchResult.Failure).code)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403}}"""))
        assertEquals("authentication_failed", (search.search("q", 5) as WebSearchResult.Failure).code)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"API key not valid."}}"""))
        assertEquals("authentication_failed", (search.search("q", 5) as WebSearchResult.Failure).code)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12").setBody("{}"))
        val rateLimited = search.search("q", 5) as WebSearchResult.Failure
        assertEquals("rate_limited", rateLimited.code)
        assertEquals(12L, rateLimited.retryAfterSeconds)

        server.enqueue(ok("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals("content_refused", (search.search("q", 5) as WebSearchResult.Failure).code)

        val countBefore = server.requestCount
        val illegal = GoogleGroundingSearch.forGemini(
            baseUrl = baseUrl(),
            apiKey = "gk-\nbad",
            model = "gemini-2.5-flash",
            client = client,
        )
        assertEquals("authentication_failed", (illegal.search("q", 5) as WebSearchResult.Failure).code)
        assertEquals(countBefore, server.requestCount)
    }

    @Test
    fun providerMarksUnsupportedWhenGroundingUnavailable() {
        assertEquals(null, WebSearchProvider.Google.problem("", "", groundingAvailable = true))
        assertEquals(
            WebSearchProvider.Problem.UnsupportedProvider,
            WebSearchProvider.Google.problem("", "", groundingAvailable = false),
        )
    }
}
