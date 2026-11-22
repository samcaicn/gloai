package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.settings.ProviderStore

/** Process-owned catalog, account resolver and adapter registry shared by every voice capability and page. */
class VoiceModelEnvironment(
    val catalog: VoiceCatalogStore,
    val accountResolver: VoiceAccountResolver,
    val registry: VoiceAdapterRegistry,
    val credentials: VoiceOwnedAccountSource,
) {
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
        }
    }

    val stt: SttSelectionController by lazy { SttSelectionController(this) }

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
            }
            val credentials = VoiceCredentialStore.create(context)
            return VoiceModelEnvironment(
                catalog = VoiceCatalogStore(backend),
                accountResolver = VoiceAccountResolver(ProviderStore.get(context), credentials),
                registry = VoiceAdapterRegistry(),
                credentials = credentials,
            )
        }
    }
}
