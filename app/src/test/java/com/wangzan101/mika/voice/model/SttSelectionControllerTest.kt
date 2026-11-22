package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.model.adapters.AndroidSystemSttAdapter
import com.wangzan101.mika.voice.model.adapters.BailianSttAdapter
import com.wangzan101.mika.voice.model.adapters.DoubaoSttAdapter
import com.wangzan101.mika.voice.model.adapters.FunAsrSttAdapter
import com.wangzan101.mika.voice.model.adapters.ProviderSttAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

internal class MemoryVoiceCatalogBackend(initial: String? = null) : VoiceCatalogBackend {
    var value: String? = initial
    var writes: Int = 0
        private set
    var clears: Int = 0
        private set

    override fun read(): String? = value

    override fun write(value: String): Boolean {
        writes++
        this.value = value
        return true
    }

    override fun clear() {
        clears++
        value = null
    }
}

internal class FakeVoiceProviderAccountSource(
    vararg values: Provider,
) : VoiceProviderAccountSource {
    private val providers = values.associateBy { it.id }
    var resolveCount: Int = 0
        private set

    override fun provider(id: String): Provider? {
        resolveCount++
        return providers[id]
    }

    override fun activeApiKey(id: String): String? = "provider-test-key"
    override fun hasKey(id: String): Boolean = true
    override fun keyRevision(id: String): Long = 0L
    override fun headers(provider: Provider): Map<String, String> = mapOf("User-Agent" to "Mika/test")
}

internal class FakeVoiceOwnedAccountSource : VoiceOwnedAccountSource {
    override fun read(secretId: String): VoiceCredentials = VoiceCredentials(apiKey = "owned-test-key")
    override fun credentialsConfigured(secretId: String): Boolean = true
    override fun configurationToken(secretId: String): String = "test-ciphertext-revision"
}

internal fun voiceTestEnvironment(
    catalog: VoiceCatalogStore,
    providers: VoiceProviderAccountSource = FakeVoiceProviderAccountSource(),
    owned: VoiceOwnedAccountSource = FakeVoiceOwnedAccountSource(),
    registry: VoiceAdapterRegistry = VoiceAdapterRegistry(),
) = VoiceModelEnvironment(
    catalog = catalog,
    accountResolver = VoiceAccountResolver(providers, owned),
    registry = registry,
    credentials = owned,
)

internal fun voiceTestProvider(
    id: String = "p1",
    baseUrl: String = "https://provider.example/v1",
    kind: ProviderConfig.Kind = ProviderConfig.Kind.OpenAiCompat,
) = Provider(id = id, name = "Provider", kind = kind, baseUrl = baseUrl)

class SttSelectionControllerTest {
    @Test fun `auto off and system selections use modes and the built in model without resolving accounts`() {
        val backend = MemoryVoiceCatalogBackend()
        val catalog = VoiceCatalogStore(backend, migrated = true)
        val providers = FakeVoiceProviderAccountSource()
        val controller = voiceTestEnvironment(catalog, providers).stt
        val selectionWrites = backend.writes
        assertEquals(SttSelectionController.SYSTEM_STT_SERVICE, catalog.state.value.services.single { it.id == "android-system" })
        assertEquals(SttSelectionController.SYSTEM_STT_MODEL, catalog.state.value.models.single { it.id == "system-stt" })

        controller.select(SpeechChoice.Auto)
        assertEquals(SttSelection.Auto, controller.current())
        controller.select(SpeechChoice.Off)
        assertEquals(SttSelection.Off, controller.current())
        controller.select(SpeechChoice.System)

        val systemRef = SttSelectionController.SYSTEM_STT_REF
        assertEquals(SttSelection.Model(systemRef), controller.current())
        val resolved = controller.requireModel(systemRef)
        assertEquals("android-system", resolved.service.id)
        assertEquals("system-stt", resolved.model.id)
        assertEquals(SttProtocol.AndroidSystem, resolved.model.stt?.protocol)
        assertSame(ResolvedAccount.None, resolved.account)
        assertEquals(0, providers.resolveCount)
        assertEquals(selectionWrites + 3, backend.writes)
    }

    @Test fun `selecting a model only persists its explicit ref`() {
        val provider = voiceTestProvider()
        val source = FakeVoiceProviderAccountSource(provider)
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "whisper-1", "Whisper", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        val ref = VoiceModelRef("provider:p1", "whisper-1", VoiceCapability.STT)
        val controller = voiceTestEnvironment(catalog, source).stt

        controller.select(ref)

        assertEquals(SttSelection.Model(ref), controller.current())
        assertEquals(0, source.resolveCount)
    }

    @Test fun `only batch protocols expose a file transcription model`() {
        SttProtocol.entries.forEach { protocol ->
            val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
            val provider = voiceTestProvider(kind = if (protocol == SttProtocol.GeminiInline) ProviderConfig.Kind.Gemini else ProviderConfig.Kind.OpenAiCompat)
            val account = when (protocol) {
                SttProtocol.AndroidSystem, SttProtocol.FunAsrStreaming -> VoiceAccount.None
                SttProtocol.DoubaoStreaming, SttProtocol.BailianStreaming -> VoiceAccount.VoiceOwned("owned-$protocol")
                else -> VoiceAccount.ExistingProvider("p1")
            }
            val serviceId = if (protocol == SttProtocol.AndroidSystem) "android-system" else "service-$protocol"
            val modelId = if (protocol == SttProtocol.AndroidSystem) "system-stt" else "model-$protocol"
            if (protocol != SttProtocol.AndroidSystem) {
                catalog.upsertService(VoiceService(serviceId, serviceId, account))
                val binding = when (protocol) {
                    SttProtocol.FunAsrStreaming -> SttBinding(protocol, endpoint = "ws://localhost:10095")
                    SttProtocol.BailianStreaming -> SttBinding(protocol, region = "Beijing")
                    else -> SttBinding(protocol)
                }
                catalog.upsertModel(VoiceModel(serviceId, modelId, modelId, stt = binding))
            }
            val ref = VoiceModelRef(serviceId, modelId, VoiceCapability.STT)
            val controller = voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider)).stt
            controller.select(ref)

            val batch = controller.batchModel()
            if (protocol in setOf(SttProtocol.AudioTranscriptions, SttProtocol.ChatInputAudio, SttProtocol.GeminiInline)) {
                assertEquals(protocol.name, ref.modelId, batch?.model?.id)
            } else {
                assertNull(protocol.name, batch)
            }
        }
    }

    @Test fun `environment shares its catalog and registry with the STT controller`() {
        val registry = VoiceAdapterRegistry(
            sttAdapters = listOf(
                AndroidSystemSttAdapter(),
                ProviderSttAdapter(SttProtocol.AudioTranscriptions),
                ProviderSttAdapter(SttProtocol.ChatInputAudio),
                ProviderSttAdapter(SttProtocol.GeminiInline),
                DoubaoSttAdapter(),
                FunAsrSttAdapter(),
                BailianSttAdapter(),
            ),
        )
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val environment = voiceTestEnvironment(catalog, registry = registry)

        assertSame(registry, environment.registry)
        assertSame(catalog, environment.catalog)
        assertSame(catalog, environment.stt.catalog)
    }
}
