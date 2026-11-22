package com.wangzan101.mika.voice.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class VoiceCapability { STT, TTS, REALTIME }

@Serializable
data class VoiceModelRef(
    val serviceId: String,
    val modelId: String,
    val capability: VoiceCapability,
)

@Serializable
data class VoiceService(
    val id: String,
    val name: String,
    val account: VoiceAccount,
)

@Serializable
sealed interface VoiceAccount {
    @Serializable
    @SerialName("ExistingProvider")
    data class ExistingProvider(val providerId: String) : VoiceAccount

    @Serializable
    @SerialName("VoiceOwned")
    data class VoiceOwned(val secretId: String) : VoiceAccount

    @Serializable
    @SerialName("None")
    data object None : VoiceAccount
}

@Serializable
enum class SttProtocol {
    AndroidSystem,
    AudioTranscriptions,
    ChatInputAudio,
    GeminiInline,
    DoubaoStreaming,
    FunAsrStreaming,
    BailianStreaming,
}

@Serializable
enum class TtsProtocol { AndroidSystem, OpenAiSpeech, GeminiTts }

@Serializable
enum class RealtimeProtocol { VolcDuplex, OpenAiRealtime }

@Serializable
enum class ChatInputAudioData { Base64, DataUri }

@Serializable
data class SttBinding(
    val protocol: SttProtocol,
    val endpoint: String = "",
    val region: String = "",
    val resourceId: String = "",
    val businessSpace: String = "",
    val chatInputData: ChatInputAudioData = ChatInputAudioData.Base64,
    val includeTranscriptionInstruction: Boolean = true,
    /** Empty means automatic; otherwise this is the explicit legacy ASR language code. */
    val language: String = "",
)

@Serializable
data class TtsBinding(
    val protocol: TtsProtocol,
    val endpoint: String = "",
)

@Serializable
data class RealtimeBinding(
    val protocol: RealtimeProtocol,
    val endpoint: String = "",
    val speaker: String = "",
)

@Serializable
data class VoiceModel(
    val serviceId: String,
    val id: String,
    val displayName: String,
    val stt: SttBinding? = null,
    val tts: TtsBinding? = null,
    val realtime: RealtimeBinding? = null,
) {
    val capabilities: Set<VoiceCapability>
        get() = buildSet {
            if (stt != null) add(VoiceCapability.STT)
            if (tts != null) add(VoiceCapability.TTS)
            if (realtime != null) add(VoiceCapability.REALTIME)
        }
}

@Serializable
sealed interface SttSelection {
    @Serializable
    @SerialName("Auto")
    data object Auto : SttSelection

    @Serializable
    @SerialName("Off")
    data object Off : SttSelection

    @Serializable
    @SerialName("Model")
    data class Model(val ref: VoiceModelRef) : SttSelection
}

@Serializable
data class VoiceSelections(
    val stt: SttSelection = SttSelection.Auto,
    val tts: VoiceModelRef? = null,
    val realtime: VoiceModelRef? = null,
)

@Serializable
enum class VoiceVerification { Unverified, Verified }

@Serializable
data class VoiceVerificationRecord(
    val ref: VoiceModelRef,
    val status: VoiceVerification,
    /** Empty is connection/capability verification; Realtime audio keys retain their original scope. */
    val scope: String = "",
)

sealed interface VoiceCatalogError {
    data object ReadFailed : VoiceCatalogError
    data object UnsupportedSchema : VoiceCatalogError
}

@Serializable
data class VoiceCatalogSnapshot(
    val schemaVersion: Int = 1,
    val services: List<VoiceService> = emptyList(),
    val models: List<VoiceModel> = emptyList(),
    val selections: VoiceSelections = VoiceSelections(),
    val verifications: List<VoiceVerificationRecord> = emptyList(),
    val legacyMigrationComplete: Boolean = false,
    @Transient val error: VoiceCatalogError? = null,
    @Transient val rawSnapshot: String? = null,
)
