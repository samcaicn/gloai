package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GeminiTtsModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps() = TtsCapabilities(voices = setOf("Kore", "Puck"), maxChars = 100)
    private fun model() = GeminiTtsModel(server.url("/").toString().trimEnd('/'), "gk", "gemini-2.5-flash-preview-tts", caps(), OkHttpClient())

    private fun response(pcm: ByteArray, mime: String = "audio/L16;codec=pcm;rate=24000") =
        """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"$mime","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":5}}"""

    @Test fun parsesInlinePcmAndSendsVoiceConfig() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response(byteArrayOf(1, 2, 3, 4))))
        val r = model().synthesize(TtsRequest("hello", "Kore"))
        assertEquals("audio/wav", r.mime)
        assertEquals(24_000, r.sampleRate)
        assertEquals("RIFF", String(r.bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals(4, r.bytes.size - 44)

        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash-preview-tts:generateContent", request.path)
        assertEquals("gk", request.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(listOf("AUDIO"), config["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Kore", config["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertEquals("hello", body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun honorsRateInMime() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response(byteArrayOf(1, 2), "audio/L16;rate=16000")))
        val r = model().synthesize(TtsRequest("hello", "Puck"))
        assertEquals(16_000, r.sampleRate)
        val bytes = r.bytes
        val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
        assertEquals(16_000, rate)
    }

    @Test fun refusesBlockedPrompt() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try { model().synthesize(TtsRequest("hello", "Kore")); fail("expected refused") } catch (e: ModelError.ContentRefused) { Unit }
    }

    @Test fun noAudioIsBadResponse() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}"""))
        try { model().synthesize(TtsRequest("hello", "Kore")); fail("expected bad response") } catch (e: ModelError.BadResponse) { Unit }
    }

    @Test fun safetyFinishWithoutAudioIsRefused() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        try { model().synthesize(TtsRequest("hello", "Kore")); fail("expected refused") } catch (e: ModelError.ContentRefused) { Unit }
    }
}
