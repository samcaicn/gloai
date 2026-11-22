package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.model.adapters.SttHooks

/** Reads and writes only STT selection; resolution and protocol work happen when recognition is actually used. */
class SttSelectionController(private val environment: VoiceModelEnvironment) {
    internal val catalog: VoiceCatalogStore get() = environment.catalog
    val migrated: Boolean get() = environment.catalog.state.value.legacyMigrationComplete

    fun current(): SttSelection = environment.catalog.state.value.selections.stt

    fun select(choice: SpeechChoice) {
        val selection = when (choice) {
            SpeechChoice.Auto -> SttSelection.Auto
            SpeechChoice.Off -> SttSelection.Off
            SpeechChoice.System -> SttSelection.Model(SYSTEM_STT_REF)
            else -> throw IllegalArgumentException("Select a catalog model ref for this STT choice")
        }
        environment.catalog.setSttSelection(selection)
    }

    fun select(ref: VoiceModelRef) {
        environment.catalog.setSttSelection(SttSelection.Model(ref))
    }

    fun requireModel(ref: VoiceModelRef): ResolvedVoiceModel {
        if (ref == SYSTEM_STT_REF) {
            return environment.accountResolver.resolve(SYSTEM_STT_SERVICE, SYSTEM_STT_MODEL)
        }
        val snapshot = environment.catalog.state.value
        if (snapshot.error != null) throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        if (ref.capability != VoiceCapability.STT) throw VoiceModelError.Unsupported(VoiceCapability.STT)
        val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
            ?: throw VoiceModelError.IncompleteConfig("Selected STT model is not registered")
        if (model.stt == null) throw VoiceModelError.Unsupported(VoiceCapability.STT)
        val service = snapshot.services.firstOrNull { it.id == model.serviceId }
            ?: throw VoiceModelError.IncompleteConfig("Selected STT service is not registered")
        return environment.accountResolver.resolve(service, model)
    }

    fun spec(ref: VoiceModelRef): SpeechEngineSpec = spec(requireModel(ref))

    internal fun spec(model: ResolvedVoiceModel): SpeechEngineSpec = environment.registry.stt(model).spec(model)

    fun start(context: Context, ref: VoiceModelRef, language: String?, hooks: SttHooks): SpeechSession {
        val model = requireModel(ref)
        val binding = model.model.stt ?: throw VoiceModelError.Unsupported(VoiceCapability.STT)
        val configuredLanguage = binding.language.takeIf {
            it.isNotBlank() && binding.protocol in EXPLICIT_ASR_LANGUAGE_PROTOCOLS
        }
        return environment.registry.stt(model).start(context, model, configuredLanguage ?: language, hooks)
    }

    /** Only batch protocols can transcribe an existing file; system and streaming selections return null. */
    fun batchModel(): ResolvedVoiceModel? {
        if (!migrated) return null
        val ref = (current() as? SttSelection.Model)?.ref ?: return null
        val model = requireModel(ref)
        return model.takeIf { it.model.stt?.protocol in BATCH_PROTOCOLS }
    }

    companion object {
        val SYSTEM_STT_REF = VoiceModelRef("android-system", "system-stt", VoiceCapability.STT)
        val SYSTEM_STT_SERVICE = VoiceService("android-system", "Android", VoiceAccount.None)
        val SYSTEM_STT_MODEL = VoiceModel(
            serviceId = "android-system",
            id = "system-stt",
            displayName = "Android system speech recognition",
            stt = SttBinding(SttProtocol.AndroidSystem),
        )

        private val BATCH_PROTOCOLS = setOf(
            SttProtocol.AudioTranscriptions,
            SttProtocol.ChatInputAudio,
            SttProtocol.GeminiInline,
        )
        private val EXPLICIT_ASR_LANGUAGE_PROTOCOLS = setOf(
            SttProtocol.FunAsrStreaming,
            SttProtocol.BailianStreaming,
        )
    }
}
