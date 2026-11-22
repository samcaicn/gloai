package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.model.TtsCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 声音服务的接口形态。存名字，只能往后加、不能改名或删。 */
enum class TtsProtocol(val wire: String) {
    /** OpenAI Speech:POST /audio/speech。 */
    OpenAiSpeech("openai-speech"),

    /** Gemini TTS:generateContent + responseModalities AUDIO。 */
    GeminiTts("gemini-tts"),

    /** 内置 Android 系统语音合成;只用于目录能力提示,旧版选择从不落成它。 */
    AndroidSystem("android-system");

    companion object {
        fun of(wire: String?): TtsProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

internal fun normalizeVoiceSpeed(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.25f, 4f) else 1f

/** 用户选的声音、模型和接口；Key 仍由 [ProviderStore] 按服务商保存。 */
data class VoiceChoice(
    val providerId: String,
    val model: String,
    val protocol: TtsProtocol,
    val voice: String = "",
    val speed: Float = 1.0f,
    val streaming: Boolean = false,
)

/**
 * 声音设置。默认允许朗读（用户点按钮才会合成），但不自动朗读、不给 speak 工具：
 * [enabled] 是总开关；[autoRead] 打开后完成的回答自动读；[speakTool] 打开后模型可以主动说一句；
 * [maxCharsPerSpeak] 是单次朗读的字数上限，防止误读整篇长文烧钱。
 */
data class VoiceSettingsState(
    val enabled: Boolean = true,
    val choice: VoiceChoice? = null,
    /** 迁移后的音色/语速/流式使用偏好;模型选择在目录里,这里只留使用偏好。 */
    val voice: String = "",
    val speed: Float = 1.0f,
    val streaming: Boolean = false,
    val autoRead: Boolean = false,
    val speakTool: Boolean = false,
    /** 允许把生成的音频存进手机音乐库;默认关。 */
    val saveEnabled: Boolean = false,
    /** 每次保存前是否问用户;默认问。 */
    val askBeforeSave: Boolean = true,
    val maxCharsPerSpeak: Int = DEFAULT_MAX_CHARS,
) {
    companion object {
        const val DEFAULT_MAX_CHARS = 2000
        const val MAX_CHARS_LIMIT = 20_000
    }
}

class VoiceSettings(private val prefs: SharedPreferences) {
    interface Owner {
        val voiceSettings: VoiceSettings
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<VoiceSettingsState> = _state.asStateFlow()

    /** Usage preferences remain in this store; migration reads but never rewrites them. */
    internal fun legacySnapshot(): VoiceSettingsState = state.value

    /** 旧版在声音页直接填写模型。首次升级时保留这个选择及其音色和协议;选择经统一目录迁移,这里不再按标记过滤。 */
    internal fun reconcileTtsChoice(store: ProviderStore) {
        val choice = state.value.choice
        if (!prefs.getBoolean(KEY_TTS_ROLE_MIGRATED, false)) {
            if (choice != null && store.provider(choice.providerId) != null) {
                val model = choice.model.trim()
                if (model != choice.model) setChoice(choice.copy(model = model))
            }
            prefs.edit().putBoolean(KEY_TTS_ROLE_MIGRATED, true).apply()
        }
    }

    fun setEnabled(on: Boolean) = update { copy(enabled = on) }

    fun setChoice(choice: VoiceChoice?) = update {
        val normalized = choice?.copy(
            speed = normalizeVoiceSpeed(choice.speed),
            streaming = choice.streaming && choice.protocol == TtsProtocol.OpenAiSpeech,
        )
        copy(
            choice = normalized,
            voice = normalized?.voice.orEmpty(),
            speed = normalized?.speed ?: 1f,
            streaming = normalized?.streaming ?: false,
        )
    }

    fun setVoice(voice: String) = update { copy(voice = voice, choice = choice?.copy(voice = voice)) }

    fun setSpeed(speed: Float) = update {
        val normalized = normalizeVoiceSpeed(speed)
        copy(speed = normalized, choice = choice?.copy(speed = normalized))
    }

    fun setStreaming(on: Boolean) = update {
        copy(streaming = on, choice = choice?.copy(streaming = on && choice.protocol == TtsProtocol.OpenAiSpeech))
    }

    fun setAutoRead(on: Boolean) = update { copy(autoRead = on) }

    fun setSpeakTool(on: Boolean) = update { copy(speakTool = on) }

    fun setSaveEnabled(on: Boolean) = update { copy(saveEnabled = on) }

    fun setAskBeforeSave(on: Boolean) = update { copy(askBeforeSave = on) }

    fun setMaxChars(n: Int) = update { copy(maxCharsPerSpeak = n.coerceIn(1, VoiceSettingsState.MAX_CHARS_LIMIT)) }

    /** 按当前协议给的能力。音色不限制（由服务商校验）；OpenAI 支持多种容器，Gemini 这里统一回到 WAV。 */
    fun capabilities(): TtsCapabilities? {
        val choice = _state.value.choice ?: return null
        return capabilities(choice.protocol)
    }

    /** 迁移后按目录模型的协议给能力;字数上限仍取这里的使用偏好。 */
    fun capabilities(protocol: TtsProtocol): TtsCapabilities = when (protocol) {
        TtsProtocol.OpenAiSpeech -> TtsCapabilities(
            voices = null,
            maxChars = _state.value.maxCharsPerSpeak,
            formats = setOf("wav", "mp3", "opus", "aac", "flac", "pcm"),
        )
        TtsProtocol.GeminiTts -> TtsCapabilities(
            voices = null,
            maxChars = _state.value.maxCharsPerSpeak,
            formats = setOf("wav"),
        )
        TtsProtocol.AndroidSystem -> TtsCapabilities(
            voices = null,
            maxChars = _state.value.maxCharsPerSpeak,
            formats = setOf("wav"),
        )
    }

    private fun update(block: VoiceSettingsState.() -> VoiceSettingsState) {
        val next = _state.value.block()
        prefs.edit().putString(KEY, encode(next).toString()).apply()
        _state.value = next
    }

    private fun read(): VoiceSettingsState {
        val raw = prefs.getString(KEY, null) ?: return VoiceSettingsState()
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: VoiceSettingsState()
    }

    private fun encode(state: VoiceSettingsState): JsonObject = buildJsonObject {
        put("enabled", state.enabled)
        put("autoRead", state.autoRead)
        put("speakTool", state.speakTool)
        put("saveEnabled", state.saveEnabled)
        put("askBeforeSave", state.askBeforeSave)
        put("maxCharsPerSpeak", state.maxCharsPerSpeak)
        put("voice", state.voice)
        put("speed", state.speed)
        put("streaming", state.streaming)
        state.choice?.let { c ->
            put("providerId", c.providerId)
            put("model", c.model)
            put("protocol", c.protocol.wire)
        }
    }

    private fun decode(obj: JsonObject): VoiceSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        val protocol = TtsProtocol.of((obj["protocol"] as? JsonPrimitive)?.contentOrNull)
        val voice = (obj["voice"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val speed = normalizeVoiceSpeed((obj["speed"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 1f)
        val streaming = bool("streaming", false)
        val choice = if (providerId.isNullOrBlank() || model.isNullOrBlank() || protocol == null) null
        else VoiceChoice(
            providerId = providerId,
            model = model,
            protocol = protocol,
            voice = voice,
            speed = speed,
            streaming = protocol == TtsProtocol.OpenAiSpeech && streaming,
        )
        return VoiceSettingsState(
            enabled = bool("enabled", true),
            choice = choice,
            voice = voice,
            speed = speed,
            streaming = streaming,
            autoRead = bool("autoRead", false),
            speakTool = bool("speakTool", false),
            saveEnabled = bool("saveEnabled", false),
            askBeforeSave = bool("askBeforeSave", true),
            maxCharsPerSpeak = ((obj["maxCharsPerSpeak"] as? JsonPrimitive)?.intOrNull ?: VoiceSettingsState.DEFAULT_MAX_CHARS)
                .coerceIn(1, VoiceSettingsState.MAX_CHARS_LIMIT),
        )
    }

    companion object {
        private const val KEY = "voice_settings"
        private const val KEY_TTS_ROLE_MIGRATED = "voice_tts_role_migrated"

        fun get(context: Context): VoiceSettings =
            (context.applicationContext as? Owner)?.voiceSettings
                ?: error("the Application must implement VoiceSettings.Owner")
    }
}
