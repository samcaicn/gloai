package com.wangzan101.mika.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogStoreTest {
    @Test
    fun `same model keeps STT and TTS bindings independent`() {
        val store = VoiceCatalogStore(FakeBackend())
        store.upsertService(testService)
        store.upsertModel(testModel(stt = sttBinding, tts = ttsBinding))

        assertEquals(sttBinding, store.requireModel(sttRef).stt)
        assertEquals(ttsBinding, store.requireModel(ttsRef).tts)
        assertEquals(setOf(VoiceCapability.STT, VoiceCapability.TTS), store.requireModel(sttRef).capabilities)
    }

    @Test
    fun `selections survive reload without conflating capabilities`() {
        val backend = FakeBackend()
        val store = VoiceCatalogStore(backend)
        store.upsertService(testService)
        store.upsertModel(testModel(stt = sttBinding, tts = ttsBinding, realtime = realtimeBinding))
        store.setSttSelection(SttSelection.Model(sttRef))
        store.setTtsSelection(ttsRef)
        store.setRealtimeSelection(realtimeRef)

        val restored = VoiceCatalogStore(backend)
        assertEquals(SttSelection.Model(sttRef), restored.state.value.selections.stt)
        assertEquals(ttsRef, restored.state.value.selections.tts)
        assertEquals(realtimeRef, restored.state.value.selections.realtime)

        restored.setSttSelection(SttSelection.Off)
        assertEquals(SttSelection.Off, VoiceCatalogStore(backend).state.value.selections.stt)
        restored.setSttSelection(SttSelection.Auto)
        assertEquals(SttSelection.Auto, VoiceCatalogStore(backend).state.value.selections.stt)
        restored.setSttSelection(SttSelection.Model(systemSttRef))
        assertEquals(SttSelection.Model(systemSttRef), VoiceCatalogStore(backend).state.value.selections.stt)
        assertTrue(restored.state.value.services.none { it.id == "android-system" })
    }

    @Test
    fun `idle hang-up defaults to five minutes, keeps a custom value, clamps it and can be turned off`() {
        val backend = FakeBackend()
        val store = VoiceCatalogStore(backend)
        assertEquals(5, store.state.value.selections.idleMinutes)
        store.setCallStyle(idleMinutes = 37)
        assertEquals(37, VoiceCatalogStore(backend).state.value.selections.idleMinutes)
        store.setCallStyle(idleMinutes = 999)
        assertEquals(VoiceSelections.MAX_IDLE_MINUTES, store.state.value.selections.idleMinutes)
        store.setCallStyle(idleMinutes = VoiceSelections.NEVER_IDLE)
        assertEquals(VoiceSelections.NEVER_IDLE, VoiceCatalogStore(backend).state.value.selections.idleMinutes)
        store.setCallStyle(bargeIn = false)
        assertEquals("改别的不碰它", VoiceSelections.NEVER_IDLE, store.state.value.selections.idleMinutes)
    }

    @Test
    fun `changing one binding invalidates only that capability verification`() {
        val (store, backend) = populatedStore()
        store.markVerification(sttRef, VoiceVerification.Verified)
        store.markVerification(ttsRef, VoiceVerification.Verified)

        store.upsertModel(testModel(stt = sttBinding.copy(endpoint = "https://new.example"), tts = ttsBinding))

        assertEquals(VoiceVerification.Unverified, store.verification(sttRef))
        assertEquals(VoiceVerification.Verified, store.verification(ttsRef))
        assertEquals(VoiceVerification.Verified, VoiceCatalogStore(backend).verification(ttsRef))
    }

    @Test
    fun `corrupt migrated catalog fails instead of resetting choices`() {
        val backend = FakeBackend("{broken")
        val store = VoiceCatalogStore(backend, migrated = true)

        assertTrue(store.state.value.error is VoiceCatalogError.ReadFailed)
        assertTrue(store.state.value.legacyMigrationComplete)
        assertNull(store.state.value.selections.tts)
        assertEquals("{broken", store.state.value.rawSnapshot)
        assertEquals("{broken", backend.value)
        assertEquals(0, backend.writeAttempts)
    }

    @Test
    fun `corrupt pre-migration catalog leaves legacy mode active and raw data untouched`() {
        val backend = FakeBackend("{broken")
        val store = VoiceCatalogStore(backend)

        assertTrue(store.state.value.error is VoiceCatalogError.ReadFailed)
        assertFalse(store.state.value.legacyMigrationComplete)
        assertEquals("{broken", store.state.value.rawSnapshot)
        assertThrows(IllegalStateException::class.java) { store.setSttSelection(SttSelection.Off) }
        assertEquals("{broken", backend.value)
        assertEquals(0, backend.writeAttempts)
    }

    @Test
    fun `unknown protocol preserves raw catalog and exposes UnsupportedSchema`() {
        val (seed, seedBackend) = populatedStore()
        seed.setSttSelection(SttSelection.Model(sttRef))
        seed.setTtsSelection(ttsRef)
        val known = seedBackend.value!!
        val unknown = known.replace("\"OpenAiSpeech\"", "\"FutureTts\"")
        assertNotEquals(known, unknown)
        val backend = FakeBackend(unknown)

        val store = VoiceCatalogStore(backend, migrated = true)

        assertTrue(store.state.value.error is VoiceCatalogError.UnsupportedSchema)
        assertEquals(unknown, store.state.value.rawSnapshot)
        assertEquals(listOf(testService), store.state.value.services)
        assertEquals(1, store.state.value.models.size)
        assertEquals(SttSelection.Model(sttRef), store.state.value.selections.stt)
        assertEquals(ttsRef, store.state.value.selections.tts)
        assertNotEquals(SttSelection.Auto, store.state.value.selections.stt)
        assertThrows(IllegalStateException::class.java) { store.setTtsSelection(null) }
        assertEquals(unknown, backend.value)
        assertEquals(0, backend.writeAttempts)
    }

    @Test
    fun `failed write leaves disk and published state unchanged`() {
        val (store, backend) = populatedStore()
        val before = store.state.value
        val rawBefore = backend.value
        val attemptsBefore = backend.writeAttempts
        backend.failWrites = true

        assertThrows(IllegalStateException::class.java) { store.setTtsSelection(ttsRef) }

        assertSame(before, store.state.value)
        assertEquals(rawBefore, backend.value)
        assertEquals(attemptsBefore + 1, backend.writeAttempts)
    }

    @Test
    fun `deleting a model clears its selections and verification in one snapshot`() {
        val (store, backend) = populatedStore()
        store.setSttSelection(SttSelection.Model(sttRef))
        store.setTtsSelection(ttsRef)
        store.markVerification(sttRef, VoiceVerification.Verified)
        val attemptsBefore = backend.writeAttempts

        store.deleteModel(ttsRef)

        assertTrue(store.state.value.models.isEmpty())
        assertEquals(SttSelection.Off, store.state.value.selections.stt)
        assertNull(store.state.value.selections.tts)
        assertEquals(VoiceVerification.Unverified, store.verification(sttRef))
        assertEquals(attemptsBefore + 1, backend.writeAttempts)
    }

    @Test
    fun `replaceAfterMigration validates and persists the complete snapshot once`() {
        val backend = FakeBackend()
        val store = VoiceCatalogStore(backend)
        val snapshot = VoiceCatalogSnapshot(
            services = listOf(testService),
            models = listOf(testModel(stt = sttBinding, tts = ttsBinding)),
            selections = VoiceSelections(SttSelection.Model(sttRef), ttsRef, null),
            verifications = listOf(VoiceVerificationRecord(sttRef, VoiceVerification.Verified)),
            legacyMigrationComplete = true,
        )

        store.replaceAfterMigration(snapshot)

        assertEquals(1, backend.writeAttempts)
        assertTrue(backend.value!!.contains("\"schemaVersion\":1"))
        assertTrue(backend.value!!.contains("\"legacyMigrationComplete\":true"))
        val restored = VoiceCatalogStore(backend)
        assertTrue(restored.state.value.legacyMigrationComplete)
        assertEquals(snapshot.services, restored.state.value.services)
        assertEquals(snapshot.models, restored.state.value.models)
        assertEquals(snapshot.selections, restored.state.value.selections)
        assertEquals(VoiceVerification.Verified, restored.verification(sttRef))
        assertNull(restored.state.value.error)
    }

    @Test
    fun `invalid capability selection is rejected before writing`() {
        val (store, backend) = populatedStore()
        val before = store.state.value
        val attemptsBefore = backend.writeAttempts

        assertThrows(IllegalArgumentException::class.java) { store.setTtsSelection(sttRef) }

        assertSame(before, store.state.value)
        assertEquals(attemptsBefore, backend.writeAttempts)
    }

    private data class Fixture(val store: VoiceCatalogStore, val backend: FakeBackend)

    private fun populatedStore(): Fixture {
        val backend = FakeBackend()
        val store = VoiceCatalogStore(backend)
        store.upsertService(testService)
        store.upsertModel(testModel(stt = sttBinding, tts = ttsBinding))
        return Fixture(store, backend)
    }

    private class FakeBackend(initial: String? = null) : VoiceCatalogBackend {
        var value: String? = initial
        var failWrites = false
        var writeAttempts = 0

        override fun read(): String? = value

        override fun write(value: String): Boolean {
            writeAttempts++
            if (failWrites) return false
            this.value = value
            return true
        }
    }

    private companion object {
        val testService = VoiceService("svc", "Service", VoiceAccount.ExistingProvider("provider-1"))
        val sttBinding = SttBinding(SttProtocol.AudioTranscriptions, endpoint = "https://stt.example")
        val ttsBinding = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "https://tts.example")
        val realtimeBinding = RealtimeBinding(RealtimeProtocol.OpenAiRealtime, endpoint = "wss://realtime.example")
        val sttRef = VoiceModelRef("svc", "shared", VoiceCapability.STT)
        val ttsRef = VoiceModelRef("svc", "shared", VoiceCapability.TTS)
        val realtimeRef = VoiceModelRef("svc", "shared", VoiceCapability.REALTIME)
        val systemSttRef = VoiceModelRef("android-system", "system-stt", VoiceCapability.STT)

        fun testModel(stt: SttBinding? = null, tts: TtsBinding? = null, realtime: RealtimeBinding? = null) = VoiceModel(
            serviceId = "svc",
            id = "shared",
            displayName = "Shared model",
            stt = stt,
            tts = tts,
            realtime = realtime,
        )
    }
}
