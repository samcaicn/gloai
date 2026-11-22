package com.wangzan101.mika.calls

import com.wangzan101.mika.voice.model.FakeVoiceOwnedAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAdapterRegistry
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.adapters.OpenAiRealtimeAdapter
import com.wangzan101.mika.voice.model.adapters.VolcDuplexRealtimeAdapter
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what a delegate provider received so the unified provider mapping can be pinned. */
private class RecordingRealtimeProvider : CallRealtimeProvider {
    val validated = mutableListOf<CallVoiceProfile>()
    val connected = mutableListOf<Pair<CallVoiceProfile, VolcRealtimeCredentials>>()

    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        validated += profile
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
    ): CallRealtimeSession {
        connected += profile to credentials
        return FakeSession
    }
}

private object FakeSession : CallRealtimeSession {
    override val events: Flow<VolcRealtimeEvent> = emptyFlow()
    override fun sendPcm16(bytes: ByteArray, byteCount: Int): Boolean = true
    override fun sendGreeting(text: String): Boolean = true
    override fun setMuted(muted: Boolean): Boolean = true
    override fun close() {}
}

class CallRealtimeUnifiedTest {
    private val volcRef = VoiceModelRef("realtime:volc-profile", "volc-model", VoiceCapability.REALTIME)
    private val openAiRef = VoiceModelRef("realtime:openai-profile", "gpt-realtime-test", VoiceCapability.REALTIME)

    private fun controller(recording: RecordingRealtimeProvider): Pair<com.wangzan101.mika.voice.model.RealtimeSelectionController, RecordingRealtimeProvider> {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("realtime:volc-profile", "Volc", VoiceAccount.VoiceOwned("owned-volc")))
        catalog.upsertModel(
            VoiceModel(
                "realtime:volc-profile",
                "volc-model",
                "Volc profile",
                realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex, endpoint = "wss://volc.example/ws", speaker = "speaker_a"),
            ),
        )
        catalog.upsertService(VoiceService("realtime:openai-profile", "OpenAI", VoiceAccount.VoiceOwned("owned-openai")))
        catalog.upsertModel(
            VoiceModel(
                "realtime:openai-profile",
                "gpt-realtime-test",
                "OpenAI profile",
                realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime, endpoint = OpenAiCallProtocol.ENDPOINT, speaker = "alloy"),
            ),
        )
        val registry = VoiceAdapterRegistry(
            realtimeAdapters = listOf(
                VolcDuplexRealtimeAdapter(recording),
                OpenAiRealtimeAdapter(recording),
            ),
        )
        val environment = voiceTestEnvironment(catalog, owned = FakeVoiceOwnedAccountSource(), registry = registry)
        return environment.realtime to recording
    }

    @Test
    fun `volc duplex maps the exact endpoint model and speaker to the delegate`() {
        val (controller, recording) = controller(RecordingRealtimeProvider())
        controller.select(volcRef)

        val provider = controller.provider()
        provider.validate(CallVoiceProfile(), "hello", enablePhoneDtmf = true)

        val seen = recording.validated.single()
        assertEquals(CallVoiceProtocol.VOLC_DUPLEX, seen.protocol)
        assertEquals("wss://volc.example/ws", seen.endpoint)
        assertEquals("volc-model", seen.model)
        assertEquals("speaker_a", seen.speaker)
    }

    @Test
    fun `openai realtime maps the exact endpoint model and speaker to the delegate`() {
        val (controller, recording) = controller(RecordingRealtimeProvider())
        controller.select(openAiRef)

        val provider = controller.provider()
        provider.validate(CallVoiceProfile(), "hello", enablePhoneDtmf = false)

        val seen = recording.validated.single()
        assertEquals(CallVoiceProtocol.OPENAI_REALTIME, seen.protocol)
        assertEquals(OpenAiCallProtocol.ENDPOINT, seen.endpoint)
        assertEquals("gpt-realtime-test", seen.model)
        assertEquals("alloy", seen.speaker)
    }

    @Test
    fun `multiple profiles keep independent selections`() {
        val (controller, _) = controller(RecordingRealtimeProvider())

        controller.select(volcRef)
        assertEquals(volcRef, controller.current())
        controller.select(openAiRef)
        assertEquals(openAiRef, controller.current())
        controller.select(null)
        assertEquals(null, controller.current())
    }

    @Test
    fun `carrier connect and in-app resolution agree on the active profile and credentials`() = kotlinx.coroutines.test.runTest {
        val (controller, recording) = controller(RecordingRealtimeProvider())
        controller.select(volcRef)

        // Carrier entry point (CallRuntime) connects through the catalog-backed provider.
        controller.provider().connect(CallVoiceProfile(), VolcRealtimeCredentials(), "carrier", enablePhoneDtmf = true)

        // In-app entry point (VoiceCallController) resolves profile + credentials through the same
        // controller: activeProfile() -> profile(), activeCredentials() -> credentials(). It keeps
        // VolcRealtimeClient for the connection, so the shared invariant is that both entry points
        // read identical endpoint/model/speaker and credentials for the active ref.
        val inAppProfile = controller.profile()
        val inAppCredentials = controller.credentials()

        val (carrierProfile, carrierCredentials) = recording.connected.single()
        assertEquals(inAppProfile.protocol, carrierProfile.protocol)
        assertEquals(inAppProfile.endpoint, carrierProfile.endpoint)
        assertEquals(inAppProfile.model, carrierProfile.model)
        assertEquals(inAppProfile.speaker, carrierProfile.speaker)
        assertEquals(inAppCredentials.apiKey, carrierCredentials.apiKey)
        assertEquals(inAppCredentials.appId, carrierCredentials.appId)
        assertEquals(inAppCredentials.accessKey, carrierCredentials.accessKey)
    }

    @Test
    fun `connect resolves credentials from the owned account through the provider`() = kotlinx.coroutines.test.runTest {
        val (controller, recording) = controller(RecordingRealtimeProvider())
        controller.select(volcRef)

        val provider = controller.provider()
        provider.connect(CallVoiceProfile(), VolcRealtimeCredentials(), "hello", enablePhoneDtmf = true)

        val (seenProfile, seenCredentials) = recording.connected.single()
        assertEquals("wss://volc.example/ws", seenProfile.endpoint)
        assertTrue(seenCredentials.valid)
    }
}
