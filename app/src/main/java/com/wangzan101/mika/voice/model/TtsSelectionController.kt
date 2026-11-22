package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel

/**
 * Maps a catalog TTS protocol to the settings protocol family used for capability hints. Vendor protocols
 * deliver WAV/PCM only, the same output family as the device engine.
 */
internal fun TtsProtocol.settingsProtocol(): com.wangzan101.mika.settings.TtsProtocol = when (this) {
    TtsProtocol.OpenAiSpeech -> com.wangzan101.mika.settings.TtsProtocol.OpenAiSpeech
    TtsProtocol.GeminiTts -> com.wangzan101.mika.settings.TtsProtocol.GeminiTts
    else -> com.wangzan101.mika.settings.TtsProtocol.AndroidSystem
}

/** Reads and writes only TTS selection; resolution and protocol work happen when speech is actually used. */
class TtsSelectionController(private val environment: VoiceModelEnvironment) {
    internal val catalog: VoiceCatalogStore get() = environment.catalog
    val migrated: Boolean get() = environment.catalog.state.value.legacyMigrationComplete

    fun current(): VoiceModelRef? = environment.catalog.state.value.selections.tts

    fun select(ref: VoiceModelRef?) {
        environment.catalog.setTtsSelection(ref)
    }

    /**
     * 全新安装的默认选择:目录还没迁移、且没有朗读选择时,落在内置系统 TTS 上。
     * 迁移产生的选择(含云端)从不被改写;迁移后的目录交给迁移结果。
     */
    internal fun ensureDefault() {
        val snapshot = environment.catalog.state.value
        if (!snapshot.legacyMigrationComplete && snapshot.selections.tts == null) {
            environment.catalog.setTtsSelection(SYSTEM_TTS_REF)
        }
    }

    fun requireModel(ref: VoiceModelRef): ResolvedVoiceModel {
        val snapshot = environment.catalog.state.value
        if (snapshot.error != null) throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        if (ref.capability != VoiceCapability.TTS) throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
            ?: throw VoiceModelError.IncompleteConfig("Selected TTS model is not registered")
        if (model.tts == null) throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        val service = snapshot.services.firstOrNull { it.id == model.serviceId }
            ?: throw VoiceModelError.IncompleteConfig("Selected TTS service is not registered")
        return environment.accountResolver.resolve(service, model)
    }

    fun createTts(context: Context, ref: VoiceModelRef, capabilities: TtsCapabilities): TtsModel {
        val model = requireModel(ref)
        return environment.registry.tts(model).create(context, model, capabilities)
    }

    companion object {
        val SYSTEM_TTS_REF = VoiceModelRef("android-system", "system-tts", VoiceCapability.TTS)

        /** 和内置系统识别共用同一个 android-system 服务,不另建服务。 */
        val SYSTEM_TTS_SERVICE = SttSelectionController.SYSTEM_STT_SERVICE

        val SYSTEM_TTS_MODEL = VoiceModel(
            serviceId = "android-system",
            id = "system-tts",
            displayName = "Android system speech synthesis",
            tts = TtsBinding(TtsProtocol.AndroidSystem),
        )
    }
}
