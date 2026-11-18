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
    GeminiTts("gemini-tts");

    companion object {
        fun of(wire: String?): TtsProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

/** 用户选的声音、模型和接口；Key 仍由 [ProviderStore] 按服务商保存。 */
data class VoiceChoice(
    val providerId: String,
    val model: String,
    val protocol: TtsProtocol,
    val voice: String = "",
    val speed: Float = 1.0f,
)

/**
 * 声音设置。默认允许朗读（用户点按钮才会合成），但不自动朗读、不给 speak 工具：
 * [enabled] 是总开关；[autoRead] 打开后完成的回答自动读；[speakTool] 打开后模型可以主动说一句；
 * [maxCharsPerSpeak] 是单次朗读的字数上限，防止误读整篇长文烧钱。
 */
data class VoiceSettingsState(
    val enabled: Boolean = true,
    val choice: VoiceChoice? = null,
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
    private val _state = MutableStateFlow(read())
    val state: StateFlow<VoiceSettingsState> = _state.asStateFlow()

    fun setEnabled(on: Boolean) = update { copy(enabled = on) }

    fun setChoice(choice: VoiceChoice?) = update {
        copy(choice = choice?.copy(speed = choice.speed.coerceIn(0.25f, 4.0f)))
    }

    fun setAutoRead(on: Boolean) = update { copy(autoRead = on) }

    fun setSpeakTool(on: Boolean) = update { copy(speakTool = on) }

    fun setSaveEnabled(on: Boolean) = update { copy(saveEnabled = on) }

    fun setAskBeforeSave(on: Boolean) = update { copy(askBeforeSave = on) }

    fun setMaxChars(n: Int) = update { copy(maxCharsPerSpeak = n.coerceIn(1, VoiceSettingsState.MAX_CHARS_LIMIT)) }

    /** 按当前协议给的能力。音色不限制（由服务商校验）；OpenAI 支持多种容器，Gemini 这里统一回到 WAV。 */
    fun capabilities(): TtsCapabilities? {
        val choice = _state.value.choice ?: return null
        return when (choice.protocol) {
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
        }
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
        state.choice?.let { c ->
            put("providerId", c.providerId)
            put("model", c.model)
            put("protocol", c.protocol.wire)
            put("voice", c.voice)
            put("speed", c.speed)
        }
    }

    private fun decode(obj: JsonObject): VoiceSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        val protocol = TtsProtocol.of((obj["protocol"] as? JsonPrimitive)?.contentOrNull)
        val choice = if (providerId.isNullOrBlank() || model.isNullOrBlank() || protocol == null) null
        else VoiceChoice(
            providerId = providerId,
            model = model,
            protocol = protocol,
            voice = (obj["voice"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            speed = ((obj["speed"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 1.0f).coerceIn(0.25f, 4.0f),
        )
        return VoiceSettingsState(
            enabled = bool("enabled", true),
            choice = choice,
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

        fun get(context: Context) = VoiceSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
