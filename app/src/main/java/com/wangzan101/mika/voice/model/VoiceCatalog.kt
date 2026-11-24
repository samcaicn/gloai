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
    /** ElevenLabs speech-to-text: multipart upload, one utterance per request. */
    ElevenLabsUpload,
    /** Deepgram pre-recorded listen: raw WAV body. */
    DeepgramUpload,
    /** Azure Speech short-audio REST recognition. */
    AzureShortAudio,
    /** Tencent Cloud one-sentence recognition (TC3 signed). */
    TencentSentence,
    /** iFlytek dictation WebSocket (signed URL). */
    XfyunDictation,
}

@Serializable
enum class TtsProtocol {
    AndroidSystem,
    OpenAiSpeech,
    GeminiTts,
    /** Volcano Engine speech synthesis v3, HTTP chunked lines of base64 audio. */
    VolcSpeech,
    /** Bailian Qwen TTS over the multimodal generation endpoint. */
    BailianQwenTts,
    /** MiniMax T2A v2, hex audio in JSON. */
    MiniMaxT2a,
    ElevenLabsSpeech,
    DeepgramSpeak,
    AzureSpeech,
    /** Tencent Cloud TextToVoice (TC3 signed). */
    TencentVoice,
    /** iFlytek online synthesis WebSocket (signed URL). */
    XfyunSynthesis,
}

/** How the two call features turn speech into a reply. */
@Serializable
enum class CallEngine {
    /** Listen (speech to text) → think (chat model) → speak (text to speech). */
    Standard,
    /** One model that listens and speaks at once. */
    Realtime,
}

/** Which chat model the front desk thinks with, in chat and in calls. */
@Serializable
enum class ThinkModel { Light, Main }

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
    /** Service region or resource where the protocol needs one (Azure region, Volc resource id). */
    val region: String = "",
    /** Voice used when the user has not picked one. */
    val voice: String = "",
    /** Voices this model offers; empty falls back to the protocol's list. */
    val voices: List<String> = emptyList(),
    /** Readable names for opaque voice ids (fetched from the vendor), keyed by id. */
    val voiceNames: Map<String, String> = emptyMap(),
)

@Serializable
data class RealtimeBinding(
    val protocol: RealtimeProtocol,
    val endpoint: String = "",
    val speaker: String = "",
)

@Serializable
enum class VoiceModelOrigin {
    /** Shipped with the vendor definition. */
    Builtin,
    /** Fetched from the vendor's model list. */
    Listed,
    /** A model id the user typed in. */
    Manual,
}

@Serializable
data class VoiceModel(
    val serviceId: String,
    val id: String,
    val displayName: String,
    val stt: SttBinding? = null,
    val tts: TtsBinding? = null,
    val realtime: RealtimeBinding? = null,
    /** Where this entry came from; a list refresh replaces only [VoiceModelOrigin.Listed] entries. */
    val origin: VoiceModelOrigin = VoiceModelOrigin.Builtin,
    /** The vendor's list did not say what this model does, so it is offered for listening and speaking both. */
    val guessed: Boolean = false,
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
    /** AI phone calls' own Realtime model; null follows [realtime]. */
    val callRealtime: VoiceModelRef? = null,
    /** Voice for [callRealtime]; blank uses that model's own voice. Ignored while calls follow [realtime]. */
    val callSpeaker: String = "",
    /** Null means never chosen: an existing Realtime choice keeps Realtime, otherwise Standard. */
    val engine: CallEngine? = null,
    val think: ThinkModel = ThinkModel.Light,
    /** Silence that ends a caller's turn in a Standard call. */
    val silenceMs: Int = DEFAULT_SILENCE_MS,
    /** Stop speaking when the other side starts talking. */
    val bargeIn: Boolean = true,
    /** In-app voice calls hang up after this many minutes with nothing new; 0 never hangs up. AI phone calls ignore it. */
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES,
    /** AI phone calls' own engine; null follows [engine]. */
    val callEngine: CallEngine? = null,
    /** AI phone calls' own listen model; null follows [stt] (device recognition excluded). */
    val callStt: VoiceModelRef? = null,
    /** AI phone calls' own speak model; null follows [tts]. */
    val callTts: VoiceModelRef? = null,
    /** Voice for [callTts]; blank uses the model's default. */
    val callTtsVoice: String = "",
) {
    /** The engine voice calls use. */
    val resolvedEngine: CallEngine get() = engine ?: if (realtime != null) CallEngine.Realtime else CallEngine.Standard

    /** The engine AI phone calls use. */
    val resolvedCallEngine: CallEngine get() = callEngine ?: if (callRealtime != null) CallEngine.Realtime else resolvedEngine

    companion object {
        const val DEFAULT_SILENCE_MS = 1500
        const val MIN_SILENCE_MS = 500
        const val MAX_SILENCE_MS = 4000
        const val DEFAULT_IDLE_MINUTES = 5
        const val NEVER_IDLE = 0
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 120
        /** The idle choices offered as ready-made rows; anything else in range is a custom value. */
        val IDLE_PRESETS = listOf(2, 5, 10)
    }
}

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
