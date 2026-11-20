package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRotationModelsTest {
    private val caps = Capabilities(true, false, 32000, false)

    @Test fun thirdKeyWorksForBothProtocolsAndStreamingModes() = runTest {
        for (gemini in listOf(false, true)) for (streaming in listOf(false, true))
            for (sse in if (streaming) listOf(false, true) else listOf(false)) {
            val server = MockWebServer().also { it.start() }
            try {
                server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
                server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
                val json = if (gemini) """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
                    else """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
                val body = if (sse && !gemini)
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    else if (sse) "data: $json\n\n" else json
                server.enqueue(MockResponse().setHeader("Content-Type", if (sse) "text/event-stream" else "application/json").setBody(body))
                val keys = mutableListOf("keyA", "keyB", "keyC")
                val rejected = mutableListOf<String>()
                val next = { keys.firstOrNull() }
                val reject: (String, Long?, Int) -> Unit = { key, _, _ ->
                    rejected += key
                    keys.remove(key)
                }
                val model: StreamingChatModel = if (gemini)
                    GeminiModel(server.url("/").toString(), "", "m", caps, OkHttpClient(), keySupplier = next, onKeyRejected = reject)
                else OpenAiCompatModel(server.url("/v1").toString(), null, "m", caps, OkHttpClient(), keySupplier = next, onKeyRejected = reject)
                val chunks = mutableListOf<String>()
                val reply = if (streaming) model.completeStreaming("s", emptyList(), emptyList(), chunks::add)
                    else model.complete("s", emptyList(), emptyList())
                assertEquals(listOf(Part.Text("ok")), reply.parts)
                assertEquals(listOf("keyA", "keyB"), rejected)
                assertEquals(3, server.requestCount)
                val header = if (gemini) "x-goog-api-key" else "Authorization"
                val prefix = if (gemini) "" else "Bearer "
                assertEquals(listOf("keyA", "keyB", "keyC"), (1..3).map { server.takeRequest().getHeader(header)!!.removePrefix(prefix) })
                if (streaming) assertTrue(chunks.last() == "ok")
            } finally { server.shutdown() }
        }
    }

    @Test fun exhaustedPoolDoesNotRetryTheSameKey() = runTest {
        val attempted = mutableListOf<String?>()
        val rejected = mutableListOf<String>()
        var pick = 0
        val response = requestWithKeyRotation(
            nextKey = { listOf("A", "B", "A")[pick++] },
            rejected = { key, _, _ -> rejected += key },
        ) { key -> attempted += key; RawResponse(401, null, "{}") }
        assertEquals(401, response.status)
        assertEquals(listOf("A", "B"), attempted)
        assertEquals(listOf("A", "B"), rejected)
    }
}
