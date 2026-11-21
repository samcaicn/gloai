package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriberTest {
    private val server = MockWebServer().apply { start() }
    private val t = Transcriber(OkHttpClient())

    @After fun stop() = server.shutdown()

    private fun provider(kind: ProviderConfig.Kind, suffix: String) =
        Provider("p", "P", kind, server.url(suffix).toString().trimEnd('/'))

    private val wav = Wav.silence(100)

    @Test fun transcriptionAndProbeHonorOnlyExplicitTtsMarks() = runBlocking {
        val p = provider(ProviderConfig.Kind.OpenAiCompat, "/v1").copy(ttsModels = setOf("custom"))
        val probeError = runCatching { t.detect(p, null, "custom") }.exceptionOrNull()
        val transcribeError = runCatching {
            t.transcribe(p, null, "custom", TranscribeMethod.AudioTranscriptions, wav, null)
        }.exceptionOrNull()
        assertTrue(probeError is IllegalArgumentException)
        assertTrue(transcribeError is IllegalArgumentException)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"text":"识别成功"}"""))
        assertEquals(
            TranscribeMethod.AudioTranscriptions,
            t.detect(p, null, "fishaudio/drama-3-int4"),
        )
        assertTrue(server.takeRequest().body.readUtf8().contains("fishaudio/drama-3-int4"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun audioTranscriptionsSendsMultipart() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "whisper-1", TranscribeMethod.AudioTranscriptions, wav, "zh")
        val r = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", r.path)
        assertEquals("Bearer k", r.getHeader("Authorization"))
        val body = r.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("whisper-1"))
        assertTrue(body.contains("name=\"language\""))
        assertEquals("你好", out)
    }

    @Test
    fun chatInputAudioParsesChoice() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"今天下雨"}}]}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.ChatInputAudio, wav, null)
        val r = server.takeRequest()
        assertEquals("/v1/chat/completions", r.path)
        assertTrue(r.body.readUtf8().contains("input_audio"))
        assertEquals("今天下雨", out)
    }

    @Test
    fun geminiInlineParses() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.Gemini, ""), "k", "gemini-x", TranscribeMethod.GeminiInline, wav, null)
        val r = server.takeRequest()
        assertEquals("/v1beta/models/gemini-x:generateContent", r.path)
        assertEquals("k", r.getHeader("x-goog-api-key"))
        assertTrue(r.body.readUtf8().contains("inline_data"))
        assertEquals("hello", out)
    }

    @Test
    fun detectFallsBackToChatAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}"""))
        val m = t.detect(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m")
        assertEquals(TranscribeMethod.ChatInputAudio, m)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun chatInputAudioUriSendsDataUri() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"明天晴"}}]}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.ChatInputAudioUri, wav, null)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"data\":\"data:audio/wav;base64,"))
        assertTrue(body, body.contains("\"format\":\"wav\""))
        assertEquals("明天晴", out)
    }

    @Test
    fun chatInputAudioOnlySendsNoInstruction() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"明天晴"}}]}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.ChatInputAudioOnly, wav, null)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("data:audio/wav;base64,"))
        assertFalse(body, body.contains("\"type\":\"text\""))
        assertEquals("明天晴", out)
    }

    @Test
    fun detectTriesAudioOnlyLast() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"The provided URL does not appear to be valid."}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"this task does not support this input"}}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"嗯。"}}]}"""))
        val m = t.detect(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m")
        assertEquals(TranscribeMethod.ChatInputAudioOnly, m)
        assertEquals(4, server.requestCount)
        // 依次试过:转写接口(multipart)、对话接口带音频、对话接口带 data URI
        val first = server.takeRequest().body.readUtf8()
        assertTrue(first, "name=\"model\"" in first)
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second, "\"input_audio\"" in second && "data:audio" !in second)
        val third = server.takeRequest().body.readUtf8()
        assertTrue(third, "data:audio/wav;base64," in third)
        // 最后一种:只发音频、不带指令
        val last = server.takeRequest().body.readUtf8()
        assertFalse(last, "\"type\":\"text\"" in last)
        assertTrue(last, "\"input_audio\"" in last)
    }

    @Test
    fun chatInputAudioSendsRawBase64() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"x"}}]}"""))
        t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.ChatInputAudio, wav, null)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, "\"input_audio\"" in body)
        assertFalse(body, body.contains("data:audio"))
    }

    @Test
    fun detectTriesDataUriWhenRawBase64IsRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"format is empty","code":"UNSUPPORTED_FORMAT"}}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}"""))
        val m = t.detect(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m")
        assertEquals(TranscribeMethod.ChatInputAudioUri, m)
        assertEquals(3, server.requestCount)
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("data:audio/wav;base64,"))
    }

    @Test
    fun unauthorizedMapsToKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.AudioTranscriptions, wav, null)
            fail()
        } catch (e: ModelError) {
            assertTrue(e is ModelError.Auth)
        }
    }

    @Test
    fun detectStopsOnKeyError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"x"}}]}"""))
        try {
            t.detect(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m")
            fail()
        } catch (e: ModelError) {
            assertTrue(e is ModelError.Auth)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun looksLikeSpeech() {
        listOf("whisper-1", "qwen3-asr-flash", "gpt-4o-transcribe", "paraformer-v2").forEach {
            assertTrue(it, Transcriber.looksLikeSpeech(ListedModel(it)))
        }
        listOf("qwen3-max", "gpt-4o").forEach { assertFalse(it, Transcriber.looksLikeSpeech(ListedModel(it))) }
    }
}
