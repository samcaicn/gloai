package com.wangzan101.mika.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.model.FakeVoiceOwnedAccountSource
import com.wangzan101.mika.voice.model.FakeVoiceProviderAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAdapterRegistry
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.adapters.AndroidSystemSttAdapter
import com.wangzan101.mika.voice.model.adapters.BailianSttAdapter
import com.wangzan101.mika.voice.model.adapters.VendorSttAdapter
import com.wangzan101.mika.voice.model.adapters.DoubaoSttAdapter
import com.wangzan101.mika.voice.model.adapters.FunAsrSttAdapter
import com.wangzan101.mika.voice.model.adapters.ProviderSttAdapter
import com.wangzan101.mika.voice.model.adapters.SttSessionFactory
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import com.wangzan101.mika.voice.model.voiceTestProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class VoiceInputUnifiedTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val context = GrantedContext()

    private class GrantedContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private class FakeSpeechSession(
        override val live: Boolean = false,
        override val unlimited: Boolean = false,
        override val events: Flow<SpeechEvent> = emptyFlow(),
    ) : SpeechSession {
        override fun release() {}
        override fun cancel() {}
    }

    private class FakeRecorder : Recorder {
        override val level: StateFlow<Float> = MutableStateFlow(0f)
        override fun start(): Boolean = true
        override fun stop(): ShortArray = ShortArray(Wav.RATE)
        override fun cancel() {}
    }

    private fun legacyStore() = ProviderStore(tmp.root, AesGcmKeyCipher { key })

    private fun input(
        catalog: VoiceCatalogStore,
        registry: VoiceAdapterRegistry,
        settings: SpeechSettings = SpeechSettings(MemoryPrefs()),
        capability: SystemCapability = SystemCapability(available = true, onDevice = true),
        providers: FakeVoiceProviderAccountSource = FakeVoiceProviderAccountSource(voiceTestProvider()),
    ) = VoiceInput(
        context = context,
        settings = settings,
        providers = legacyStore(),
        voiceModels = voiceTestEnvironment(catalog, providers, FakeVoiceOwnedAccountSource(), registry),
        systemCapability = { capability },
        systemCompatibility = { false },
    )

    @Test fun `legacy gate keeps the saved legacy system choice instead of the catalog choice`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = false)
        catalog.setSttSelection(SttSelection.Off)
        val settings = SpeechSettings(MemoryPrefs()).apply { set(SpeechChoice.System) }
        val voice = input(catalog, VoiceAdapterRegistry(), settings)

        assertEquals(SpeechEngineSpec.System(onDevice = true), voice.spec())
    }

    @Test fun `migrated auto system and off preserve system health and never substitute a cloud model`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "cloud", "Cloud", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        val controller = voiceTestEnvironment(catalog).stt
        val unavailable = SystemCapability(available = false, onDevice = false)

        controller.select(SpeechChoice.Auto)
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel),
            input(catalog, VoiceAdapterRegistry(), capability = unavailable).spec(),
        )
        controller.select(SpeechChoice.System)
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone),
            input(catalog, VoiceAdapterRegistry(), capability = unavailable).spec(),
        )
        controller.select(SpeechChoice.Off)
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off),
            input(catalog, VoiceAdapterRegistry(), capability = unavailable).spec(),
        )

        controller.select(SpeechChoice.System)
        val available = input(catalog, VoiceAdapterRegistry(), capability = SystemCapability(available = true, onDevice = false))
        assertEquals(SpeechEngineSpec.System(onDevice = false), available.spec())
    }

    @Test fun `late selections delegate every adapter family with the configured language`() {
        val started = mutableMapOf<SttProtocol, String>()
        val factory: SttSessionFactory = { _, model, tag, _ ->
            started[model.model.stt!!.protocol] = tag
            FakeSpeechSession(live = model.model.stt!!.protocol != SttProtocol.AudioTranscriptions)
        }
        val registry = VoiceAdapterRegistry(
            sttAdapters = listOf(
                AndroidSystemSttAdapter(factory),
                ProviderSttAdapter(SttProtocol.AudioTranscriptions, factory),
                ProviderSttAdapter(SttProtocol.ChatInputAudio, factory),
                ProviderSttAdapter(SttProtocol.GeminiInline, factory),
                DoubaoSttAdapter(factory),
                FunAsrSttAdapter(factory),
                BailianSttAdapter(factory),
            ) + VendorSttAdapter.PROTOCOLS.map { VendorSttAdapter(it, factory) },
        )
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertService(VoiceService("doubao", "Doubao", VoiceAccount.VoiceOwned("doubao")))
        catalog.upsertService(VoiceService("funasr", "Fun-ASR", VoiceAccount.None))
        catalog.upsertService(VoiceService("bailian", "Bailian", VoiceAccount.VoiceOwned("bailian")))
        catalog.upsertService(VoiceService("vendor", "Vendor", VoiceAccount.VoiceOwned("vendor")))
        val models = listOf(
            VoiceModel("provider:p1", "batch", "Batch", stt = SttBinding(SttProtocol.AudioTranscriptions)),
            VoiceModel("provider:p1", "chat", "Chat", stt = SttBinding(SttProtocol.ChatInputAudio)),
            VoiceModel("provider:p1", "gemini", "Gemini", stt = SttBinding(SttProtocol.GeminiInline)),
            VoiceModel("doubao", "doubao", "Doubao", stt = SttBinding(SttProtocol.DoubaoStreaming, resourceId = DoubaoProtocol.DURATION)),
            VoiceModel("funasr", "funasr", "Fun-ASR", stt = SttBinding(SttProtocol.FunAsrStreaming, endpoint = "ws://localhost:10095", language = "yue")),
            VoiceModel("bailian", "bailian", "Bailian", stt = SttBinding(SttProtocol.BailianStreaming, region = "Beijing", language = "yue")),
        ) + VendorSttAdapter.PROTOCOLS.map { VoiceModel("vendor", it.name, it.name, stt = SttBinding(it, region = "eastasia")) }
        models.forEach(catalog::upsertModel)
        val refs = models.map { VoiceModelRef(it.serviceId, it.id, VoiceCapability.STT) } + SttSelectionController.SYSTEM_STT_REF
        val voice = input(catalog, registry)
        val controller = voiceTestEnvironment(catalog, registry = registry).stt

        refs.forEach { ref ->
            controller.select(ref)
            val protocol = if (ref == SttSelectionController.SYSTEM_STT_REF) SttProtocol.AndroidSystem
            else models.single { it.serviceId == ref.serviceId && it.id == ref.modelId }.stt!!.protocol
            // Starting after each new selection proves the runtime did not retain the previous adapter.
            voice.start("en-US")
            val expectedLanguage = if (protocol == SttProtocol.FunAsrStreaming || protocol == SttProtocol.BailianStreaming) "yue" else "en-US"
            assertEquals(protocol.name, expectedLanguage, started[protocol])
        }
        assertEquals(SttProtocol.entries.toSet(), started.keys)
    }

    @Test fun `corrupt migrated catalog never falls back to auto system recognition`() = runBlocking {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend("{broken"), migrated = true)
        val voice = input(catalog, VoiceAdapterRegistry())

        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone), voice.spec())
        val event = voice.start("en-US").events.single() as SpeechEvent.Failed
        val error = (event.why as SpeechFailure.Provider).error
        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.INCOMPLETE_CONFIG, (error as VoiceModelError).category)
    }

    @Test fun `selecting STT model does not probe other protocols`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}"""))
            val provider = voiceTestProvider(baseUrl = server.url("/v1").toString().trimEnd('/'))
            val source = FakeVoiceProviderAccountSource(provider)
            val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
            catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
            catalog.upsertModel(VoiceModel("provider:p1", "whisper-1", "Whisper", stt = SttBinding(SttProtocol.AudioTranscriptions)))
            val ref = VoiceModelRef("provider:p1", "whisper-1", VoiceCapability.STT)
            lateinit var adapter: ProviderSttAdapter
            adapter = ProviderSttAdapter(SttProtocol.AudioTranscriptions) { _, model, tag, _ ->
                val spec = adapter.spec(model) as SpeechEngineSpec.Provider
                ProviderSpeech(FakeRecorder(), { wav ->
                    Transcriber(OkHttpClient()).transcribe(
                        spec.provider,
                        model.account.credentials.apiKey,
                        model.model.id,
                        spec.method,
                        wav,
                        SystemSpeech.isoCode(tag),
                    )
                })
            }
            val registry = VoiceAdapterRegistry(sttAdapters = listOf(adapter))
            val environment = voiceTestEnvironment(catalog, source, registry = registry)
            val voice = VoiceInput(
                context = context,
                settings = SpeechSettings(MemoryPrefs()),
                providers = legacyStore(),
                voiceModels = environment,
                systemCapability = { SystemCapability(available = true, onDevice = true) },
            )

            environment.stt.select(ref)
            assertEquals(0, server.requestCount)
            val session = voice.start("en-US")
            session.release()
            val events = runBlocking {
                withTimeout(5_000) { session.events.filterNot { it is SpeechEvent.Level }.toList() }
            }

            assertTrue(events.last() is SpeechEvent.Final)
            server.takeRequest().let { assertEquals("/v1/audio/transcriptions", it.path) }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
