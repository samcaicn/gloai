package com.wangzan101.mika.voice.model

import android.content.Context
import android.content.ContextWrapper
import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.OpenAiSpeechModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.TranscribeMethod
import com.wangzan101.mika.voice.Transcriber
import com.wangzan101.mika.voice.model.adapters.AndroidSystemSttAdapter
import com.wangzan101.mika.voice.model.adapters.BailianSttAdapter
import com.wangzan101.mika.voice.model.adapters.DoubaoSttAdapter
import com.wangzan101.mika.voice.model.adapters.FunAsrSttAdapter
import com.wangzan101.mika.voice.model.adapters.GeminiTtsAdapter
import com.wangzan101.mika.voice.model.adapters.OpenAiRealtimeAdapter
import com.wangzan101.mika.voice.model.adapters.OpenAiSpeechTtsAdapter
import com.wangzan101.mika.voice.model.adapters.ProviderSttAdapter
import com.wangzan101.mika.voice.model.adapters.SttHooks
import com.wangzan101.mika.voice.model.adapters.VolcDuplexRealtimeAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

class VoiceAdapterRegistryTest {
    private class FakeSpeechSession : SpeechSession {
        override val events: Flow<SpeechEvent> = emptyFlow()
        override val live: Boolean = false
        override fun release() {}
        override fun cancel() {}
    }

    private class FakeRealtimeSession : CallRealtimeSession {
        override val events: Flow<VolcRealtimeEvent> = emptyFlow()
        override fun sendPcm16(bytes: ByteArray, byteCount: Int): Boolean = true
        override fun sendGreeting(text: String): Boolean = true
        override fun setMuted(muted: Boolean): Boolean = true
        override fun sendToolResult(callId: String, result: JsonObject): Boolean = true
        override fun close() {}
    }

    private class RecordingRealtimeDelegate : CallRealtimeProvider {
        var profile: CallVoiceProfile? = null
        var credentials: VolcRealtimeCredentials? = null
        var prompt: String? = null

        override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
            this.profile = profile
            this.prompt = systemPrompt
        }

        override suspend fun connect(
            profile: CallVoiceProfile,
            credentials: VolcRealtimeCredentials,
            systemPrompt: String,
            enablePhoneDtmf: Boolean,
        ): CallRealtimeSession {
            this.profile = profile
            this.credentials = credentials
            this.prompt = systemPrompt
            return FakeRealtimeSession()
        }
    }

    private val context = ContextWrapper(null)
    private val provider = Provider(
        id = "p1",
        name = "Provider",
        kind = ProviderConfig.Kind.OpenAiCompat,
        baseUrl = "https://provider.example/v1",
    )
    private val existingAccount = ResolvedAccount.ExistingProvider(
        provider = provider,
        credentials = VoiceCredentials(apiKey = "provider-key"),
        headers = mapOf("User-Agent" to "Mika/test", "X-Session" to "voice-session"),
        keyRevision = 0L,
    )
    private val ownedAccount = ResolvedAccount.VoiceOwned("owned-secret", VoiceCredentials(apiKey = "owned-key"))

    private fun resolved(
        model: VoiceModel,
        account: ResolvedAccount = existingAccount,
    ): ResolvedVoiceModel = ResolvedVoiceModel(
        service = VoiceService(model.serviceId, "Service", VoiceAccount.None),
        model = model,
        account = account,
        configurationFingerprint = "test-fingerprint",
    )

    private fun sttModel(protocol: SttProtocol, binding: SttBinding = SttBinding(protocol)) = VoiceModel(
        serviceId = "stt-service",
        id = "stt-model",
        displayName = "STT",
        stt = binding,
    )

    @Test fun `registry resolves every currently supported protocol`() {
        val registry = VoiceAdapterRegistry()

        SttProtocol.entries.forEach { protocol ->
            val account = when (protocol) {
                SttProtocol.AndroidSystem, SttProtocol.FunAsrStreaming -> ResolvedAccount.None
                SttProtocol.DoubaoStreaming, SttProtocol.BailianStreaming -> ownedAccount
                else -> existingAccount
            }
            val binding = when (protocol) {
                SttProtocol.FunAsrStreaming -> SttBinding(protocol, endpoint = "ws://localhost:10095")
                SttProtocol.BailianStreaming -> SttBinding(protocol, region = "Beijing")
                else -> SttBinding(protocol)
            }
            assertEquals(protocol, registry.stt(resolved(sttModel(protocol, binding), account)).protocol)
        }

        TtsProtocol.entries.filterNot { it == TtsProtocol.AndroidSystem }.forEach { protocol ->
            val model = VoiceModel("tts-service", "tts-model", "TTS", tts = TtsBinding(protocol))
            assertEquals(protocol, registry.tts(resolved(model)).protocol)
        }

        RealtimeProtocol.entries.forEach { protocol ->
            val model = VoiceModel("rt-service", "rt-model", "Realtime", realtime = RealtimeBinding(protocol))
            assertEquals(protocol, registry.realtime(resolved(model, ownedAccount)).protocol)
        }
    }

    @Test fun `registry never returns an adapter for another capability`() {
        val registry = VoiceAdapterRegistry()
        val ttsOnlyResolvedModel = resolved(VoiceModel("s", "tts", "TTS", tts = TtsBinding(TtsProtocol.OpenAiSpeech)))

        val error = assertThrows(VoiceModelError.Unsupported::class.java) { registry.stt(ttsOnlyResolvedModel) }
        assertEquals(VoiceCapability.STT, error.requested)
    }

    @Test fun `android system TTS remains unregistered in this task`() {
        val registry = VoiceAdapterRegistry()
        val model = resolved(VoiceModel("android-system", "system-tts", "System TTS", tts = TtsBinding(TtsProtocol.AndroidSystem)), ResolvedAccount.None)

        val error = assertThrows(VoiceModelError.Unsupported::class.java) { registry.tts(model) }
        assertEquals(VoiceCapability.TTS, error.requested)
    }

    @Test fun `provider STT bindings preserve each existing transcribe method`() {
        val cases = listOf(
            SttBinding(SttProtocol.AudioTranscriptions) to TranscribeMethod.AudioTranscriptions,
            SttBinding(SttProtocol.ChatInputAudio) to TranscribeMethod.ChatInputAudio,
            SttBinding(SttProtocol.ChatInputAudio, chatInputData = ChatInputAudioData.DataUri) to TranscribeMethod.ChatInputAudioUri,
            SttBinding(
                SttProtocol.ChatInputAudio,
                chatInputData = ChatInputAudioData.DataUri,
                includeTranscriptionInstruction = false,
            ) to TranscribeMethod.ChatInputAudioOnly,
            SttBinding(SttProtocol.GeminiInline) to TranscribeMethod.GeminiInline,
        )

        cases.forEach { (binding, method) ->
            val model = resolved(sttModel(binding.protocol, binding))
            val spec = ProviderSttAdapter(binding.protocol).spec(model) as SpeechEngineSpec.Provider
            assertEquals(method, spec.method)
            assertEquals("stt-model", spec.model)
            assertEquals(provider.baseUrl, spec.provider.baseUrl)
        }
    }

    @Test fun `base64 chat audio cannot silently drop its required transcription instruction`() {
        val binding = SttBinding(
            SttProtocol.ChatInputAudio,
            chatInputData = ChatInputAudioData.Base64,
            includeTranscriptionInstruction = false,
        )
        val adapter = ProviderSttAdapter(SttProtocol.ChatInputAudio)

        val error = assertThrows(VoiceModelError.IncompleteConfig::class.java) {
            adapter.spec(resolved(sttModel(binding.protocol, binding)))
        }
        assertEquals(VoiceErrorCategory.INCOMPLETE_CONFIG, error.category)
    }

    @Test fun `every STT adapter starts the existing session shape without Android audio in tests`() {
        val adapters = listOf(
            AndroidSystemSttAdapter(),
            ProviderSttAdapter(SttProtocol.AudioTranscriptions),
            ProviderSttAdapter(SttProtocol.ChatInputAudio),
            ProviderSttAdapter(SttProtocol.GeminiInline),
            DoubaoSttAdapter(),
            FunAsrSttAdapter(),
            BailianSttAdapter(),
        )

        adapters.forEach { adapter ->
            val session = FakeSpeechSession()
            var receivedTag: String? = null
            val factory: (Context, ResolvedVoiceModel, String, SttHooks) -> SpeechSession = { _, _, tag, _ ->
                receivedTag = tag
                session
            }
            val injected = when (adapter) {
                is AndroidSystemSttAdapter -> AndroidSystemSttAdapter(factory)
                is ProviderSttAdapter -> ProviderSttAdapter(adapter.protocol, factory)
                is DoubaoSttAdapter -> DoubaoSttAdapter(factory)
                is FunAsrSttAdapter -> FunAsrSttAdapter(factory)
                is BailianSttAdapter -> BailianSttAdapter(factory)
                else -> error("unexpected adapter")
            }
            val model = when (adapter.protocol) {
                SttProtocol.AndroidSystem -> resolved(sttModel(adapter.protocol), ResolvedAccount.None)
                SttProtocol.DoubaoStreaming -> resolved(sttModel(adapter.protocol, SttBinding(adapter.protocol, resourceId = "volc.seedasr.sauc.duration")), ownedAccount)
                SttProtocol.FunAsrStreaming -> resolved(sttModel(adapter.protocol, SttBinding(adapter.protocol, endpoint = "ws://localhost:10095")), ResolvedAccount.None)
                SttProtocol.BailianStreaming -> resolved(sttModel(adapter.protocol, SttBinding(adapter.protocol, region = "Beijing")), ownedAccount)
                else -> resolved(sttModel(adapter.protocol))
            }

            val hooks = SttHooks(systemCapability = SystemCapability(available = true, onDevice = false))
            assertSame(session, injected.start(context, model, "zh-CN", hooks))
            assertEquals("zh-CN", receivedTag)
        }
    }

    @Test fun `streaming STT specs retain endpoint region and resource configuration`() {
        val doubao = DoubaoSttAdapter().spec(resolved(
            sttModel(SttProtocol.DoubaoStreaming, SttBinding(SttProtocol.DoubaoStreaming, resourceId = "volc.seedasr.sauc.concurrent")),
            ownedAccount,
        )) as SpeechEngineSpec.Doubao
        assertEquals("volc.seedasr.sauc.concurrent", doubao.resourceId)

        val funAsr = FunAsrSttAdapter().spec(resolved(
            sttModel(SttProtocol.FunAsrStreaming, SttBinding(SttProtocol.FunAsrStreaming, endpoint = "ws://localhost:10095")),
            ResolvedAccount.None,
        )) as SpeechEngineSpec.FunAsr
        assertEquals("ws://localhost:10095", funAsr.url)

        val bailian = BailianSttAdapter().spec(resolved(
            sttModel(SttProtocol.BailianStreaming, SttBinding(SttProtocol.BailianStreaming, region = "Singapore", businessSpace = "llm-workspace")),
            ownedAccount,
        )) as SpeechEngineSpec.Bailian
        assertEquals(BailianRegion.Singapore, bailian.region)
        assertEquals("llm-workspace", bailian.workspace)
    }

    @Test fun `missing streaming credentials fail before a session factory or network runs`() {
        var called = false
        val factory: (Context, ResolvedVoiceModel, String, SttHooks) -> SpeechSession = { _, _, _, _ ->
            called = true
            FakeSpeechSession()
        }
        val missing = ResolvedAccount.VoiceOwned("missing", VoiceCredentials())

        assertThrows(VoiceModelError.MissingCredentials::class.java) {
            DoubaoSttAdapter(factory).start(context, resolved(sttModel(SttProtocol.DoubaoStreaming), missing), "en-US", SttHooks())
        }
        assertThrows(VoiceModelError.MissingCredentials::class.java) {
            BailianSttAdapter(factory).start(context, resolved(sttModel(SttProtocol.BailianStreaming), missing), "en-US", SttHooks())
        }
        assertFalse(called)
    }

    @Test fun `openAI TTS reuses endpoint active key headers and speech protocol`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val wav = ByteArray(44).also {
                "RIFF".toByteArray().copyInto(it, 0)
                "WAVE".toByteArray().copyInto(it, 8)
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))
            val account = existingAccount.copy(provider = provider.copy(baseUrl = server.url("/v1").toString().trimEnd('/')))
            val model = resolved(VoiceModel("s", "voice-1", "Voice", tts = TtsBinding(TtsProtocol.OpenAiSpeech)), account)

            val tts = OpenAiSpeechTtsAdapter(http = OkHttpClient()).create(context, model, TtsCapabilities(formats = setOf("wav")))
            assertTrue(tts is OpenAiSpeechModel)
            val response = tts.synthesize(TtsRequest("hello", voice = "tester", format = "wav"))

            assertEquals("audio/wav", response.mime)
            val request = server.takeRequest()
            assertEquals("/v1/audio/speech", request.path)
            assertEquals("Bearer provider-key", request.getHeader("Authorization"))
            assertEquals("Mika/test", request.getHeader("User-Agent"))
            assertEquals("voice-session", request.getHeader("X-Session"))
            assertTrue(request.body.readUtf8().contains("\"model\":\"voice-1\""))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `gemini TTS reuses generateContent protocol and Google key header`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val wav = ByteArray(44).also {
                "RIFF".toByteArray().copyInto(it, 0)
                "WAVE".toByteArray().copyInto(it, 8)
            }
            val encoded = Base64.getEncoder().encodeToString(wav)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/wav","data":"$encoded"}}]}}]}""",
            ))
            val geminiProvider = provider.copy(kind = ProviderConfig.Kind.Gemini, baseUrl = server.url("").toString().trimEnd('/'))
            val account = existingAccount.copy(provider = geminiProvider)
            val model = resolved(VoiceModel("s", "gemini-voice", "Gemini", tts = TtsBinding(TtsProtocol.GeminiTts)), account)

            val tts = GeminiTtsAdapter(http = OkHttpClient()).create(context, model, TtsCapabilities(formats = setOf("wav")))
            assertTrue(tts is GeminiTtsModel)
            tts.synthesize(TtsRequest("hello", voice = "Kore", format = "wav"))

            val request = server.takeRequest()
            assertEquals("/v1beta/models/gemini-voice:generateContent", request.path)
            assertEquals("provider-key", request.getHeader("x-goog-api-key"))
            assertEquals("Mika/test", request.getHeader("User-Agent"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"responseModalities\":[\"AUDIO\"]"))
            assertTrue(body.contains("\"voiceName\":\"Kore\""))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `realtime adapters map exact profile endpoint model speaker and credentials`() = runBlocking {
        val cases = listOf(
            Triple(
                VolcDuplexRealtimeAdapter(),
                RealtimeBinding(RealtimeProtocol.VolcDuplex, endpoint = "wss://voice.example/ws", speaker = "speaker_1"),
                VolcRealtimeConfig.DEFAULT_SPEAKER,
            ),
            Triple(
                OpenAiRealtimeAdapter(),
                RealtimeBinding(RealtimeProtocol.OpenAiRealtime, speaker = ""),
                OpenAiCallProtocol.VOICE,
            ),
        )

        cases.forEach { (adapter, binding, defaultSpeaker) ->
            val delegate = RecordingRealtimeDelegate()
            val injected = when (adapter) {
                is VolcDuplexRealtimeAdapter -> VolcDuplexRealtimeAdapter(delegate)
                is OpenAiRealtimeAdapter -> OpenAiRealtimeAdapter(delegate)
                else -> error("unexpected adapter")
            }
            val model = resolved(VoiceModel("rt", "realtime-1", "Realtime", realtime = binding), ownedAccount)
            val realtime = injected.create(model)
            realtime.connect(CallVoiceProfile(), VolcRealtimeCredentials(), "system prompt")

            val expectedProtocol = if (binding.protocol == RealtimeProtocol.VolcDuplex) CallVoiceProtocol.VOLC_DUPLEX else CallVoiceProtocol.OPENAI_REALTIME
            val expectedEndpoint = binding.endpoint.ifBlank {
                if (binding.protocol == RealtimeProtocol.VolcDuplex) VolcRealtimeProtocol.ENDPOINT else OpenAiCallProtocol.ENDPOINT
            }
            assertEquals(expectedProtocol, delegate.profile?.protocol)
            assertEquals(expectedEndpoint, delegate.profile?.endpoint)
            assertEquals("realtime-1", delegate.profile?.model)
            assertEquals(binding.speaker.ifBlank { defaultSpeaker }, delegate.profile?.speaker)
            assertEquals("owned-key", delegate.credentials?.apiKey)
            assertEquals("system prompt", delegate.prompt)
        }
    }

    @Test fun `HTTP 401 maps to authentication failure`() {
        val error = VoiceModelError.from(ModelHttp.openAiError(401, null, "bad key", "model"))
        assertEquals(VoiceErrorCategory.AUTH_FAILED, error.category)
    }

    @Test fun `HTTP 429 maps to rate limiting with protocol detail`() {
        val source = ModelHttp.openAiError(429, "2", "slow down", "model")
        val error = VoiceModelError.from(source)
        assertEquals(VoiceErrorCategory.RATE_LIMITED, error.category)
        assertSame(source, error.cause)
    }

    @Test fun `unknown host maps to network failure`() {
        assertEquals(VoiceErrorCategory.NETWORK, VoiceModelError.from(UnknownHostException("missing host")).category)
        assertEquals(VoiceErrorCategory.NETWORK, VoiceModelError.from(ModelError.Network(UnknownHostException("missing host"))).category)
    }

    @Test fun `socket timeout maps to timeout`() {
        assertEquals(VoiceErrorCategory.TIMEOUT, VoiceModelError.from(SocketTimeoutException("read timed out")).category)
        assertEquals(VoiceErrorCategory.TIMEOUT, VoiceModelError.from(ModelError.Network(SocketTimeoutException("read timed out"))).category)
    }

    @Test fun `malformed successful transcription maps to protocol error with original detail`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val transcriber = Transcriber(OkHttpClient())
            val source = runCatching {
                transcriber.transcribe(
                    provider.copy(baseUrl = server.url("/v1").toString().trimEnd('/')),
                    "key",
                    "model",
                    TranscribeMethod.AudioTranscriptions,
                    ByteArray(4),
                    null,
                )
            }.exceptionOrNull()
            val error = VoiceModelError.from(source!!)
            assertEquals(VoiceErrorCategory.PROTOCOL_ERROR, error.category)
            assertTrue(error.detail.contains("no text field"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `cancellation is not presented as a failure`() {
        val error = VoiceModelError.from(CancellationException("user cancelled"))
        assertEquals(VoiceErrorCategory.CANCELLED, error.category)
        assertFalse(error.isFailure)
    }

    @Test fun `server protocol detail is retained for diagnostics`() {
        val error = VoiceModelError.from(ModelError.Server(503, "upstream unavailable"))
        assertEquals(VoiceErrorCategory.PROTOCOL_ERROR, error.category)
        assertTrue(error.detail.contains("HTTP 503"))
        assertTrue(error.detail.contains("upstream unavailable"))
    }
}
