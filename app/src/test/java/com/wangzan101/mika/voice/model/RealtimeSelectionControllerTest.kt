package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.CallAudioMode
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSelectionControllerTest {
    private val volcRef = VoiceModelRef("realtime:volc-profile", "volc-model", VoiceCapability.REALTIME)
    private val openAiRef = VoiceModelRef("realtime:openai-profile", "gpt-realtime-test", VoiceCapability.REALTIME)

    private fun migratedCatalog(): VoiceCatalogStore {
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
                realtime = RealtimeBinding(
                    RealtimeProtocol.OpenAiRealtime,
                    endpoint = com.wangzan101.mika.calls.OpenAiCallProtocol.ENDPOINT,
                    speaker = "alloy",
                ),
            ),
        )
        return catalog
    }

    @Test
    fun `select and current round trip the active realtime ref`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        assertNull(controller.current())

        controller.select(volcRef)
        assertEquals(volcRef, controller.current())

        controller.select(openAiRef)
        assertEquals(openAiRef, controller.current())

        controller.select(null)
        assertNull(controller.current())
    }

    @Test
    fun `profile maps the exact endpoint model and speaker without touching secrets`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        val volc = controller.profile(volcRef)
        assertEquals("volc-model", volc.id)
        assertEquals("Volc profile", volc.name)
        assertEquals(com.wangzan101.mika.calls.CallVoiceProtocol.VOLC_DUPLEX, volc.protocol)
        assertEquals("wss://volc.example/ws", volc.endpoint)
        assertEquals("volc-model", volc.model)
        assertEquals("speaker_a", volc.speaker)
        assertTrue(volc.credentialsConfigured)

        val openAi = controller.profile(openAiRef)
        assertEquals(com.wangzan101.mika.calls.CallVoiceProtocol.OPENAI_REALTIME, openAi.protocol)
        assertEquals(com.wangzan101.mika.calls.OpenAiCallProtocol.ENDPOINT, openAi.endpoint)
        assertEquals("gpt-realtime-test", openAi.model)
        assertEquals("alloy", openAi.speaker)
    }

    @Test
    fun `blank endpoint and speaker fall back to protocol defaults`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("realtime:p", "P", VoiceAccount.VoiceOwned("owned-p")))
        catalog.upsertModel(
            VoiceModel("realtime:p", "m", "M", realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex)),
        )
        val controller = voiceTestEnvironment(catalog).realtime

        val profile = controller.profile(VoiceModelRef("realtime:p", "m", VoiceCapability.REALTIME))
        assertEquals(com.wangzan101.mika.calls.VolcRealtimeProtocol.ENDPOINT, profile.endpoint)
        assertEquals(com.wangzan101.mika.calls.VolcRealtimeConfig.DEFAULT_SPEAKER, profile.speaker)
    }

    @Test
    fun `credentials are resolved from the owned account without a profile copy`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        val credentials = controller.credentials(volcRef)
        assertTrue(credentials.valid)
        assertTrue(controller.credentialsConfigured(volcRef))
    }

    @Test
    fun `missing owned credentials read as not configured instead of throwing`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog, owned = object : VoiceOwnedAccountSource {
            override fun read(secretId: String): VoiceCredentials? = null
            override fun credentialsConfigured(secretId: String): Boolean = false
            override fun configurationToken(secretId: String): String = ""
        }).realtime

        assertFalse(controller.credentialsConfigured(volcRef))
    }

    @Test
    fun `connection tested read and write is scoped to the ref`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        assertFalse(controller.connectionTested(volcRef))
        assertFalse(controller.connectionTested(openAiRef))

        controller.markConnectionTested(volcRef, true)
        assertTrue(controller.connectionTested(volcRef))
        assertFalse(controller.connectionTested(openAiRef))

        controller.markConnectionTested(volcRef, false)
        assertFalse(controller.connectionTested(volcRef))
    }

    @Test
    fun `audio verification is read and written per device and mode scope`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        assertFalse(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-one"))
        assertFalse(controller.isAudioVerified(volcRef, CallAudioMode.REDIRECT, "device-two"))
        assertFalse(controller.isAudioVerified(openAiRef, CallAudioMode.DIRECT, "device-one"))

        controller.markAudioVerified(volcRef, CallAudioMode.DIRECT, true, "device-one")
        controller.markAudioVerified(volcRef, CallAudioMode.REDIRECT, true, "device-two")
        controller.markAudioVerified(openAiRef, CallAudioMode.DIRECT, true, "device-one")

        assertTrue(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-one"))
        assertTrue(controller.isAudioVerified(volcRef, CallAudioMode.REDIRECT, "device-two"))
        assertTrue(controller.isAudioVerified(openAiRef, CallAudioMode.DIRECT, "device-one"))

        // Every scope survives for exactly its own ref; a different device stays unverified.
        assertFalse(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-two"))
        assertFalse(controller.isAudioVerified(openAiRef, CallAudioMode.REDIRECT, "device-two"))

        // Removing one scope must not remove the others.
        controller.markAudioVerified(volcRef, CallAudioMode.DIRECT, false, "device-one")
        assertFalse(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-one"))
        assertTrue(controller.isAudioVerified(volcRef, CallAudioMode.REDIRECT, "device-two"))
        assertTrue(controller.isAudioVerified(openAiRef, CallAudioMode.DIRECT, "device-one"))
    }

    @Test
    fun `requireModel rejects a ref without a realtime binding`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("realtime:p", "P", VoiceAccount.VoiceOwned("owned-p")))
        catalog.upsertModel(VoiceModel("realtime:p", "stt-only", "STT", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        val controller = voiceTestEnvironment(catalog).realtime

        val error = assertThrows(VoiceModelError.Unsupported::class.java) {
            controller.profile(VoiceModelRef("realtime:p", "stt-only", VoiceCapability.REALTIME))
        }
        assertEquals(VoiceCapability.REALTIME, error.requested)
    }

    @Test
    fun `requireModel rejects a non realtime capability`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        val error = assertThrows(VoiceModelError.Unsupported::class.java) {
            controller.profile(volcRef.copy(capability = VoiceCapability.STT))
        }
        assertEquals(VoiceCapability.REALTIME, error.requested)
    }

    @Test
    fun `provider without a selection throws an incomplete configuration`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime

        assertThrows(VoiceModelError.IncompleteConfig::class.java) { controller.provider() }
    }

    @Test
    fun `profile and credentialsConfigured resolve display state without decrypting owned secrets`() {
        val catalog = migratedCatalog()
        val owned = DecryptionTrackingOwnedSource()
        val controller = voiceTestEnvironment(catalog, owned = owned).realtime

        val profile = controller.profile(volcRef)
        assertTrue(profile.credentialsConfigured)
        assertTrue(controller.credentialsConfigured(volcRef))
        assertEquals(0, owned.readCount)

        // Connection-time resolution still decrypts exactly once.
        assertTrue(controller.credentials(volcRef).valid)
        assertEquals(1, owned.readCount)
    }

    @Test
    fun `speaker-only edit preserves connection and audio verification records`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime
        controller.select(volcRef)
        controller.markConnectionTested(volcRef, true)
        controller.markAudioVerified(volcRef, CallAudioMode.DIRECT, true, "device-one")
        assertTrue(controller.connectionTested(volcRef))
        assertTrue(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-one"))

        val model = controller.model(volcRef)
        catalog.upsertModel(model.copy(realtime = model.realtime!!.copy(speaker = "speaker_b")))

        assertTrue(controller.connectionTested(volcRef))
        assertTrue(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-one"))
    }

    @Test
    fun `endpoint change invalidates connection and audio verification records`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime
        controller.select(volcRef)
        controller.markConnectionTested(volcRef, true)
        controller.markAudioVerified(volcRef, CallAudioMode.DIRECT, true, "device-one")

        val model = controller.model(volcRef)
        catalog.upsertModel(model.copy(realtime = model.realtime!!.copy(endpoint = "wss://volc.example/other")))

        assertFalse(controller.connectionTested(volcRef))
        assertFalse(controller.isAudioVerified(volcRef, CallAudioMode.DIRECT, "device-one"))
    }

    @Test
    fun `calls follow the shared selection until given their own model and voice`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime
        controller.select(volcRef)
        assertTrue(controller.callFollows)
        assertEquals(volcRef, controller.callCurrent())
        assertEquals("speaker_a", controller.callProfile().speaker)

        controller.selectForCalls(volcRef, "speaker_b")
        assertFalse(controller.callFollows)
        assertEquals("speaker_b", controller.callProfile().speaker)
        assertEquals("speaker_a", controller.profile(volcRef).speaker)

        controller.select(openAiRef)
        assertEquals(volcRef, controller.callCurrent())

        controller.selectForCalls(null, "ignored")
        assertTrue(controller.callFollows)
        assertEquals(openAiRef, controller.callCurrent())
        assertEquals("", catalog.state.value.selections.callSpeaker)
    }

    @Test
    fun `deleting the call-only model makes calls follow again`() {
        val catalog = migratedCatalog()
        val controller = voiceTestEnvironment(catalog).realtime
        controller.select(volcRef)
        controller.selectForCalls(openAiRef, "verse")
        catalog.deleteModel(openAiRef)
        assertTrue(controller.callFollows)
        assertEquals(volcRef, controller.callCurrent())
    }

    @Test
    fun `calls cannot pick an unknown model`() {
        val controller = voiceTestEnvironment(migratedCatalog()).realtime
        assertThrows(VoiceModelError::class.java) {
            controller.selectForCalls(VoiceModelRef("nope", "nope", VoiceCapability.REALTIME))
        }
    }
}

/** Counts secret reads so display paths can be pinned to never decrypt. */
private class DecryptionTrackingOwnedSource : VoiceOwnedAccountSource {
    var readCount = 0
        private set

    override fun read(secretId: String): VoiceCredentials {
        readCount++
        return VoiceCredentials(apiKey = "owned-test-key")
    }

    override fun credentialsConfigured(secretId: String): Boolean = true
    override fun configurationToken(secretId: String): String = "ciphertext-revision"

}
