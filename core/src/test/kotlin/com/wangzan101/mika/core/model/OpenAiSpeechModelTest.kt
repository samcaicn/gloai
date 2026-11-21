package com.wangzan101.mika.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenAiSpeechModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps() = TtsCapabilities(voices = setOf("alloy", "nova"), maxChars = 100, formats = setOf("wav", "mp3", "pcm"))
    private fun model(http: OkHttpClient = OkHttpClient()) =
        OpenAiSpeechModel(server.url("/").toString().trimEnd('/'), "sk", "gpt-4o-mini-tts", caps(), http)

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

    @Test fun streamsBoundedPcmChunksAcrossFragmentedStereoFrames() = runTest {
        val cancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).build()
        val pcm = ByteArray(20_000) { it.toByte() }
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "audio/pcm")
            .setHeader("X-Audio-Sample-Rate", "44100")
            .setHeader("X-Audio-Channels", "2")
            .setChunkedBody(Buffer().write(pcm), 3_073))

        val chunks = model(http).streamAudio(TtsRequest(" hi ", "nova", 1f)).toList()
        assertFalse(cancelled.get())
        val joined = Buffer()
        for (part in chunks) {
            assertTrue(part is TtsAudioPart.Pcm)
            val chunk = part as TtsAudioPart.Pcm
            assertEquals(44_100, chunk.sampleRate)
            assertEquals(2, chunk.channels)
            assertTrue(chunk.bytes.size in 4..8192)
            assertEquals(0, chunk.bytes.size % 4)
            joined.write(chunk.bytes)
        }
        assertArrayEquals(pcm, joined.readByteArray())

        val request = server.takeRequest()
        assertEquals("/audio/speech", request.path)
        assertEquals("Bearer sk", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("hi", body["input"]!!.jsonPrimitive.content)
        assertEquals("nova", body["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("audio", body["stream_format"]!!.jsonPrimitive.content)
        assertEquals("1.0", body["speed"]!!.jsonPrimitive.content)
    }

    @Test fun usesHeaderlessPcmDefaultsOnlyForTheFinalOfficialOpenAiEndpoint() = runTest {
        val officialUrl = "https://api.openai.com/v1"
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = wrapPcm16Wav(pcm, 32_000)
        fun speechModel(finalUrl: String) = OpenAiSpeechModel(
            officialUrl, "sk", "gpt-4o-mini-tts", caps(),
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val json = Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8()
                val streaming = "stream_format" in Json.parseToJsonElement(json).jsonObject
                val body = if (streaming) pcm.toResponseBody("audio/pcm".toMediaType())
                    else wav.toResponseBody("audio/wav".toMediaType())
                Response.Builder()
                    .request(request.newBuilder().url(finalUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }.build(),
        )
        val request = TtsRequest("hello", "alloy")
        val chunk = speechModel("$officialUrl/audio/speech").streamAudio(request).toList().single() as TtsAudioPart.Pcm
        assertEquals(24_000, chunk.sampleRate)
        assertEquals(1, chunk.channels)
        assertArrayEquals(pcm, chunk.bytes)

        val part = speechModel("https://speech.example/v1/audio/speech").streamAudio(request).toList().single()
        assertTrue(part is TtsAudioPart.Complete)
        assertArrayEquals(wav, (part as TtsAudioPart.Complete).response.bytes)
    }

    @Test fun retriesExplicitStreamingRejectionsWithAnUnflaggedWavRequest() = runTest {
        val wav = wrapPcm16Wav(byteArrayOf(1, 2), 24_000)
        val rejections = listOf(
            MockResponse().setResponseCode(422).setBody(
                """{"detail":[{"loc":["body","stream_format"],"msg":"Extra inputs are not permitted"}]}""",
            ),
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Unknown parameter","param":"stream_format"}}""",
            ),
        )
        for ((index, rejection) in rejections.withIndex()) {
            server.enqueue(rejection)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))

            val part = model().streamAudio(TtsRequest("hello", "alloy")).toList().single()
            assertTrue(part is TtsAudioPart.Complete)
            assertArrayEquals(wav, (part as TtsAudioPart.Complete).response.bytes)
            assertEquals((index + 1) * 2, server.requestCount)
            assertPcmThenWavRequests()
        }
    }

    @Test fun reusesAnAlreadySynthesizedAudioFile() = runTest {
        val wav = wrapPcm16Wav(byteArrayOf(1, 2), 24_000)
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 1)
        val files = listOf(
            Triple("audio/wav", "audio/wav", wav),
            Triple("audio/mpeg", "audio/mpeg", mp3),
            Triple("application/octet-stream", "audio/wav", wav),
        )
        for ((index, file) in files.withIndex()) {
            val (mime, expectedMime, bytes) = file
            server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(bytes)))

            val part = model().streamAudio(TtsRequest("hello", "alloy")).toList().single()
            assertTrue(part is TtsAudioPart.Complete)
            val response = (part as TtsAudioPart.Complete).response
            assertEquals(expectedMime, response.mime)
            assertArrayEquals(bytes, response.bytes)
            assertEquals(index + 1, server.requestCount)
            assertEquals("audio", nextSpeechBody()["stream_format"]?.jsonPrimitive?.content)
        }
    }

    @Test fun retriesThirdPartyPcmWithoutFormatHeadersAsWav() = runTest {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = wrapPcm16Wav(pcm, 44_100)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm)))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))

        val part = model().streamAudio(TtsRequest("hello", "alloy")).toList().single()
        assertTrue(part is TtsAudioPart.Complete)
        assertArrayEquals(wav, (part as TtsAudioPart.Complete).response.bytes)
        assertEquals(2, server.requestCount)
        assertPcmThenWavRequests()
    }

    @Test fun preservesAuthAndRateLimitErrorsWithoutRetrying() = runTest {
        val request = TtsRequest("hello", "alloy")
        val tts = model()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))

        val auth = runCatching { tts.streamAudio(request).toList() }.exceptionOrNull()
        assertTrue(auth is ModelError.Auth)
        assertEquals(1, server.requestCount)

        val limit = runCatching { tts.streamAudio(request).toList() }.exceptionOrNull()
        assertTrue(limit is ModelError.RateLimited)
        assertEquals(2_000L, (limit as ModelError.RateLimited).retryAfterMs)
        assertEquals(2, server.requestCount)
    }

    @Test fun reportsAnIncompleteFrameAfterPcmWithoutRetrying() = runTest {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "audio/pcm")
            .setHeader("X-Audio-Sample-Rate", "44100")
            .setHeader("X-Audio-Channels", "1")
            .setChunkedBody(Buffer().write(byteArrayOf(1, 2, 3)), 2))

        val parts = mutableListOf<TtsAudioPart>()
        val result = runCatching { model().streamAudio(TtsRequest("hello", "alloy")).toList(parts) }
        assertTrue(result.exceptionOrNull() is TtsError.BadAudio)
        assertArrayEquals(byteArrayOf(1, 2), (parts.single() as TtsAudioPart.Pcm).bytes)
        assertEquals(1, server.requestCount)
    }

    private fun assertPcmThenWavRequests() {
        val streamed = nextSpeechBody()
        assertEquals("pcm", streamed["response_format"]?.jsonPrimitive?.content)
        assertEquals("audio", streamed["stream_format"]?.jsonPrimitive?.content)
        val buffered = nextSpeechBody()
        assertEquals("wav", buffered["response_format"]?.jsonPrimitive?.content)
        assertFalse("stream_format" in buffered)
        assertFalse("stream" in buffered)
    }

    private fun nextSpeechBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun cancellingStreamClosesTheHttpCallWhileWaitingForAudio() = assertCancelsRequest { tts, request ->
        tts.streamAudio(request).collect {}
    }

    @Test fun cancellingBufferedSynthesisClosesTheHttpCallWhileWaitingForAudio() = assertCancelsRequest { tts, request ->
        tts.synthesize(request)
    }

    private fun assertCancelsRequest(consume: suspend (OpenAiSpeechModel, TtsRequest) -> Unit) = runBlocking {
        val cancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.set(true)
                }
            })
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val synthesis = launch { consume(model(http), TtsRequest("hello", "alloy")) }
        val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertNotNull(request)
        withTimeout(5_000) { synthesis.cancelAndJoin() }
        assertTrue(cancelled.get())
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
