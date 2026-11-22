package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.settings.ProviderStore

/**
 * Durable marker that the one-time legacy migration has completed. It lives outside the catalog JSON so a
 * corrupt already-migrated snapshot is surfaced as [VoiceCatalogError.ReadFailed] and never silently reset.
 */
interface MigrationMarker {
    fun markComplete()

    /** Removes the durable migration marker; used only by the confirm-guarded catalog repair. */
    fun clear() {}

    object None : MigrationMarker {
        override fun markComplete() {}
    }
}

/** Process-owned catalog, account resolver and adapter registry shared by every voice capability and page. */
class VoiceModelEnvironment(
    val catalog: VoiceCatalogStore,
    val accountResolver: VoiceAccountResolver,
    val registry: VoiceAdapterRegistry,
    val credentials: VoiceOwnedAccountSource,
    /** Writable voice-owned credential store; null in tests that never persist owned accounts. */
    val credentialStore: VoiceCredentialStore? = null,
    private val migrationMarker: MigrationMarker = MigrationMarker.None,
) {
    /** Set when the one-time legacy migration failed; the catalog page surfaces it while legacy behavior is retained. */
    @Volatile
    var migrationError: VoiceModelError? = null
        private set

    /** Retained read-only legacy source so a corrupt, already-migrated catalog can be repaired in place. */
    @Volatile
    private var legacySource: LegacyVoiceSnapshotSource? = null

    init {
        val initial = catalog.state.value
        if (initial.error == null) {
            if (initial.services.none { it.id == SttSelectionController.SYSTEM_STT_SERVICE.id }) {
                catalog.upsertService(SttSelectionController.SYSTEM_STT_SERVICE)
            }
            val current = catalog.state.value
            val existing = current.models.firstOrNull {
                it.serviceId == SttSelectionController.SYSTEM_STT_MODEL.serviceId && it.id == SttSelectionController.SYSTEM_STT_MODEL.id
            }
            if (existing == null) {
                catalog.upsertModel(SttSelectionController.SYSTEM_STT_MODEL)
            } else if (existing.stt != SttSelectionController.SYSTEM_STT_MODEL.stt) {
                catalog.upsertModel(existing.copy(stt = SttSelectionController.SYSTEM_STT_MODEL.stt))
            }
            val existingTts = catalog.state.value.models.firstOrNull {
                it.serviceId == TtsSelectionController.SYSTEM_TTS_MODEL.serviceId && it.id == TtsSelectionController.SYSTEM_TTS_MODEL.id
            }
            if (existingTts == null) {
                catalog.upsertModel(TtsSelectionController.SYSTEM_TTS_MODEL)
            } else if (existingTts.tts != TtsSelectionController.SYSTEM_TTS_MODEL.tts) {
                catalog.upsertModel(existingTts.copy(tts = TtsSelectionController.SYSTEM_TTS_MODEL.tts))
            }
            TtsSelectionController(this).ensureDefault()
        }
    }

    /** Runs the one-time legacy migration; a failed read is recorded and legacy behavior is retained. */
    fun migrateLegacy(source: LegacyVoiceSnapshotSource) {
        legacySource = source
        val store = credentialStore ?: return
        val result = LegacyVoiceMigration(source, catalog, store).migrateIfNeeded()
        when (result) {
            is MigrationResult.Migrated -> migrationMarker.markComplete()
            is MigrationResult.Failed -> migrationError = result.error
            else -> Unit
        }
    }

    /**
     * Repairs a corrupt, already-migrated catalog: clears the corrupt snapshot and the durable
     * migration marker, resets the catalog to a clean pre-migration state, then re-runs the
     * legacy migration from the retained rollback data. Refuses to touch a readable catalog.
     */
    fun repairCorruptCatalog(): MigrationResult {
        val source = legacySource
            ?: return MigrationResult.Failed(VoiceModelError.IncompleteConfig("Legacy voice data is not available for repair"))
        val store = credentialStore
            ?: return MigrationResult.Failed(VoiceModelError.IncompleteConfig("Voice credential store is unavailable"))
        return try {
            catalog.resetForRepair()
            migrationMarker.clear()
            val result = LegacyVoiceMigration(source, catalog, store).migrateIfNeeded()
            when (result) {
                is MigrationResult.Migrated -> {
                    migrationError = null
                    migrationMarker.markComplete()
                }
                is MigrationResult.Failed -> migrationError = result.error
                else -> Unit
            }
            result
        } catch (error: Exception) {
            MigrationResult.Failed(VoiceModelError.from(error))
        }
    }

    val stt: SttSelectionController by lazy { SttSelectionController(this) }

    val tts: TtsSelectionController by lazy { TtsSelectionController(this) }

    val realtime: RealtimeSelectionController by lazy { RealtimeSelectionController(this) }

    companion object {
        @Volatile
        private var shared: VoiceModelEnvironment? = null

        fun get(context: Context): VoiceModelEnvironment = shared ?: synchronized(this) {
            shared ?: create(context.applicationContext).also { shared = it }
        }

        private fun create(context: Context): VoiceModelEnvironment {
            val prefs = context.getSharedPreferences("mika_voice_model_catalog", Context.MODE_PRIVATE)
            val backend = object : VoiceCatalogBackend {
                override fun read(): String? = prefs.getString("snapshot", null)
                override fun write(value: String): Boolean = prefs.edit().putString("snapshot", value).commit()
                override fun clear() {
                    prefs.edit().remove("snapshot").apply()
                }
            }
            val credentials = VoiceCredentialStore.create(context)
            val marker = object : MigrationMarker {
                override fun markComplete() {
                    prefs.edit().putBoolean("migration_complete", true).commit()
                }

                override fun clear() {
                    prefs.edit().remove("migration_complete").apply()
                }
            }
            return VoiceModelEnvironment(
                catalog = VoiceCatalogStore(backend, migrated = prefs.getBoolean("migration_complete", false)),
                accountResolver = VoiceAccountResolver(ProviderStore.get(context), credentials),
                registry = VoiceAdapterRegistry(),
                credentials = credentials,
                credentialStore = credentials,
                migrationMarker = marker,
            )
        }
    }
}
