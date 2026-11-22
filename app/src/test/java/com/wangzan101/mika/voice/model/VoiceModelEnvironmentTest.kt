package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.SpeechChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class RecordingMigrationMarker : MigrationMarker {
    var marked = false
        private set
    var cleared = false
        private set
    override fun markComplete() { marked = true }
    override fun clear() { cleared = true }
}

private class EnvCredentialBackend : VoiceCredentialBackend {
    override fun read(secretId: String): String? = null
    override fun write(secretId: String, value: String): Boolean = true
    override fun delete(secretId: String) {}
}

private fun envStore(): VoiceCredentialStore = VoiceCredentialStore(
    EnvCredentialBackend(),
    object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
    },
)

class VoiceModelEnvironmentTest {
    private fun environment(
        catalog: VoiceCatalogStore,
        marker: RecordingMigrationMarker,
        migrated: Boolean,
    ) = VoiceModelEnvironment(
        catalog = catalog,
        accountResolver = VoiceAccountResolver(FakeVoiceProviderAccountSource(), FakeVoiceOwnedAccountSource()),
        registry = VoiceAdapterRegistry(),
        credentials = FakeVoiceOwnedAccountSource(),
        credentialStore = envStore(),
        migrationMarker = marker,
    )

    @Test fun `corrupt migrated catalog shows ReadFailed and is never reset`() {
        val backend = MemoryVoiceCatalogBackend("not-json {{{")
        val catalog = VoiceCatalogStore(backend, migrated = true)
        val marker = RecordingMigrationMarker()
        val env = environment(catalog, marker, migrated = true)

        assertEquals(VoiceCatalogError.ReadFailed, catalog.state.value.error)
        assertTrue(catalog.state.value.legacyMigrationComplete)

        env.migrateLegacy(LegacyVoiceSnapshotSource { LegacyVoiceSnapshot() })

        assertEquals(VoiceCatalogError.ReadFailed, catalog.state.value.error)
        assertEquals(0, backend.writes)
        assertFalse(marker.marked)
    }

    @Test fun `failed migration keeps legacy behavior and does not mark complete`() {
        val backend = MemoryVoiceCatalogBackend()
        val catalog = VoiceCatalogStore(backend, migrated = false)
        val marker = RecordingMigrationMarker()
        val env = environment(catalog, marker, migrated = false)

        env.migrateLegacy(LegacyVoiceSnapshotSource { throw IOException("read failed") })

        assertNotNull(env.migrationError)
        assertFalse(catalog.state.value.legacyMigrationComplete)
        assertFalse(marker.marked)
    }

    @Test fun `repair clears a corrupt migrated snapshot and re-migrates from retained legacy data`() {
        val backend = MemoryVoiceCatalogBackend("not-json {{{")
        val catalog = VoiceCatalogStore(backend, migrated = true)
        val marker = RecordingMigrationMarker()
        val env = environment(catalog, marker, migrated = true)

        val legacy = LegacyVoiceSnapshot(
            speech = LegacySpeechSnapshot(
                selection = SpeechChoice.Doubao(DoubaoProtocol.CONCURRENT),
                doubaoCredentials = VoiceCredentials(apiKey = "test-doubao"),
            ),
        )
        env.migrateLegacy(LegacyVoiceSnapshotSource { legacy })

        // The corrupt snapshot is surfaced, never silently reset.
        assertEquals(VoiceCatalogError.ReadFailed, catalog.state.value.error)
        assertFalse(marker.marked)

        val result = env.repairCorruptCatalog()

        assertEquals(MigrationResult.Migrated, result)
        assertNull(catalog.state.value.error)
        assertTrue(catalog.state.value.legacyMigrationComplete)
        assertEquals(
            SttSelection.Model(VoiceModelRef("stt-doubao", "doubao", VoiceCapability.STT)),
            catalog.state.value.selections.stt,
        )
        assertTrue(marker.cleared)
        assertTrue(marker.marked)
        assertEquals(1, backend.clears)
        assertTrue(backend.value != null)
    }

    @Test fun `repair refuses to reset a readable catalog`() {
        val backend = MemoryVoiceCatalogBackend()
        val catalog = VoiceCatalogStore(backend, migrated = true)
        val marker = RecordingMigrationMarker()
        val env = environment(catalog, marker, migrated = true)
        env.migrateLegacy(LegacyVoiceSnapshotSource { LegacyVoiceSnapshot() })

        val result = env.repairCorruptCatalog()

        assertTrue(result is MigrationResult.Failed)
        assertEquals(0, backend.clears)
        assertFalse(marker.cleared)
        assertFalse(marker.marked)
        assertNull(catalog.state.value.error)
    }
}
