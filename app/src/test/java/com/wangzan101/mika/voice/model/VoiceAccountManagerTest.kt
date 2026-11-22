package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryCredentialBackend : VoiceCredentialBackend {
    val values = mutableMapOf<String, String>()
    override fun read(secretId: String): String? = values[secretId]
    override fun write(secretId: String, value: String): Boolean {
        values[secretId] = value
        return true
    }
    override fun delete(secretId: String) {
        values.remove(secretId)
    }
}

private fun testCredentialStore(): VoiceCredentialStore = VoiceCredentialStore(
    MemoryCredentialBackend(),
    object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
    },
)

class VoiceAccountManagerTest {
    @Test fun `voice account save is local only and never probes chat providers`() {
        val providers = FakeVoiceProviderAccountSource()
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val manager = VoiceAccountManager(catalog, testCredentialStore())

        // A provider that does not exist still saves: the account is validated locally, not resolved.
        val saved = manager.saveVoiceAccount(
            VoiceService("s1", "Speech", VoiceAccount.ExistingProvider("missing-provider")),
        )
        assertEquals("s1", saved.id)
        assertEquals(0, providers.resolveCount)

        manager.providerVoiceUsage("missing-provider")
        assertEquals(0, providers.resolveCount)
    }

    @Test fun `saving owned credentials preserves verification until the ciphertext token changes`() {
        val store = testCredentialStore()
        store.save("sec-1", VoiceCredentials(apiKey = "first-key"))
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("owned", "Owned", VoiceAccount.VoiceOwned("sec-1")))
        catalog.upsertModel(VoiceModel("owned", "m1", "M1", stt = SttBinding(SttProtocol.DoubaoStreaming)))
        val ref = VoiceModelRef("owned", "m1", VoiceCapability.STT)
        catalog.markVerification(ref, VoiceVerification.Verified)
        val manager = VoiceAccountManager(catalog, store)

        manager.saveVoiceAccount(
            VoiceService("owned", "Owned", VoiceAccount.VoiceOwned("sec-1")),
            VoiceCredentials(apiKey = "first-key"),
        )
        assertEquals(VoiceVerification.Verified, catalog.verification(ref))

        manager.saveVoiceAccount(
            VoiceService("owned", "Owned", VoiceAccount.VoiceOwned("sec-1")),
            VoiceCredentials(apiKey = "rotated-key"),
        )
        assertEquals(VoiceVerification.Unverified, catalog.verification(ref))
    }

    @Test fun `changing the account identity invalidates verification`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("s1", "Speech", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("s1", "m1", "M1", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        val ref = VoiceModelRef("s1", "m1", VoiceCapability.STT)
        catalog.markVerification(ref, VoiceVerification.Verified)
        val manager = VoiceAccountManager(catalog, testCredentialStore())

        manager.saveVoiceAccount(VoiceService("s1", "Speech", VoiceAccount.ExistingProvider("p2")))

        assertEquals(VoiceVerification.Unverified, catalog.verification(ref))
    }

    @Test fun `provider deletion previews usage then removes services models and selections in one snapshot`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("s1", "One", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertService(VoiceService("s2", "Two", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertService(VoiceService("s3", "Local", VoiceAccount.None))
        catalog.upsertModel(VoiceModel("s1", "m1", "M1", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        catalog.upsertModel(VoiceModel("s1", "m2", "M2", tts = TtsBinding(TtsProtocol.OpenAiSpeech), realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime)))
        catalog.upsertModel(VoiceModel("s2", "m3", "M3", stt = SttBinding(SttProtocol.ChatInputAudio)))
        catalog.upsertModel(VoiceModel("s3", "m4", "M4", stt = SttBinding(SttProtocol.FunAsrStreaming)))
        val m1Stt = VoiceModelRef("s1", "m1", VoiceCapability.STT)
        val m2Tts = VoiceModelRef("s1", "m2", VoiceCapability.TTS)
        val m2Rt = VoiceModelRef("s1", "m2", VoiceCapability.REALTIME)
        val m3Stt = VoiceModelRef("s2", "m3", VoiceCapability.STT)
        catalog.setSttSelection(SttSelection.Model(m3Stt))
        catalog.setTtsSelection(m2Tts)
        catalog.setRealtimeSelection(m2Rt)
        val manager = VoiceAccountManager(catalog, testCredentialStore())

        val usage = manager.providerVoiceUsage("p1")
        assertEquals(setOf(m1Stt, m2Tts, m2Rt, m3Stt), usage.toSet())

        val deleted = manager.deleteProviderWithVoiceUsage("p1")
        assertEquals(setOf(m1Stt, m2Tts, m2Rt, m3Stt), deleted.toSet())

        val snapshot = catalog.state.value
        assertEquals(listOf("s3"), snapshot.services.map { it.id })
        assertEquals(listOf("m4"), snapshot.models.map { it.id })
        assertEquals(SttSelection.Off, snapshot.selections.stt)
        assertEquals(null, snapshot.selections.tts)
        assertEquals(null, snapshot.selections.realtime)
        assertTrue(snapshot.verifications.isEmpty())
    }

    @Test fun `owned secret content never leaks through the manager`() {
        val backend = MemoryCredentialBackend()
        val store = VoiceCredentialStore(
            backend,
            object : KeyCipher {
                override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
                override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
            },
        )
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val manager = VoiceAccountManager(catalog, store)

        manager.saveVoiceAccount(
            VoiceService("owned", "Owned", VoiceAccount.VoiceOwned("sec-1")),
            VoiceCredentials(apiKey = "super-secret"),
        )

        val stored = backend.values.getValue("sec-1")
        assertFalse(stored.contains("super-secret"))
        val snapshot = catalog.state.value
        assertFalse(snapshot.toString().contains("super-secret"))
    }
}
