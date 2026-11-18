package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiSpeechModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps() = TtsCapabilities(voices = setOf("alloy", "nova"), maxChars = 100, formats = setOf("wav", "mp3", "pcm"))
    private fun model() = OpenAiSpeechModel(server.url("/").toString().trimEnd('/'), "sk", "gpt-4o-mini-tts", caps(), OkHttpClient())

    @Test fun sendsSpeechRequestAndReturnsAudio() = runTest {
        val wav = wrapPcm16Wav(byteArrayOf(0, 0, 0, 0), 24_000)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))
        val r = model().synthesize(TtsRequest("hello", "alloy", 1.1f, "wav"))
        assertEquals("audio/wav", r.mime)
        assertTrue(r.bytes.contentEquals(wav))
        val request = server.takeRequest()
        assertEquals("/audio/speech", request.path)
        assertEquals("Bearer sk", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("hello", body["input"]!!.jsonPrimitive.content)
        assertEquals("alloy", body["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("1.1", body["speed"]!!.jsonPrimitive.content)
    }

    @Test fun wrapsRawPcm() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
        val r = model().synthesize(TtsRequest("hi", "nova", format = "pcm"))
        assertEquals("audio/wav", r.mime)
        assertEquals("RIFF", String(r.bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals(24_000, r.sampleRate)
        assertEquals(4, r.bytes.size - 44)
    }

    @Test fun mapsAuthError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            model().synthesize(TtsRequest("hello", "alloy"))
            fail("expected auth error")
        } catch (e: ModelError.Auth) {
            Unit
        }
    }

    @Test fun rejectsBeforeSending() = runTest {
        try { model().synthesize(TtsRequest("hello", "unknown")); fail("expected voice error") } catch (e: TtsError.Unsupported) { Unit }
        try { model().synthesize(TtsRequest("x".repeat(101), "alloy")); fail("expected length error") } catch (e: TtsError.Unsupported) { Unit }
        try { model().synthesize(TtsRequest("hello", "alloy", format = "flac")); fail("expected format error") } catch (e: TtsError.Unsupported) { Unit }
        assertEquals(0, server.requestCount)
    }
}
