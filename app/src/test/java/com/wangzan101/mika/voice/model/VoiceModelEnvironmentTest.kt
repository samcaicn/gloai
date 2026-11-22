package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class RecordingMigrationMarker : MigrationMarker {
    var marked = false
        private set
    override fun markComplete() { marked = true }
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
}
