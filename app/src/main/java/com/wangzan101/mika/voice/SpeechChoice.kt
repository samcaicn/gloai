package com.wangzan101.mika.voice

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.voice.model.LegacySpeechSnapshot
import com.wangzan101.mika.voice.model.VoiceCredentials
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 用户为「语音转文字」做的选择,以及它在设置里的存取。具体用哪个引擎由 [SpeechResolver] 结合手机能力再定。 */
sealed interface SpeechChoice {
    /** 没选过:有手机自带识别就用它。 */
    data object Auto : SpeechChoice

    /** 明确选了手机自带识别。 */
    data object System : SpeechChoice

    /** 不用语音输入。 */
    data object Off : SpeechChoice

    /** 用某个服务商的某个模型转写,[method] 决定请求怎么发。 */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice

    /** Native streaming ASR, with credentials stored separately from this non-secret selection. */
    data class Doubao(val resourceId: String = "volc.seedasr.sauc.duration") : SpeechChoice

    /** 自建的 Fun-ASR 实时服务;[url] 是规范过的 ws(s) 地址(见 [FunAsrProtocol.normalize]),不含密钥。 */
    data class FunAsr(val url: String, val language: AsrLanguage = AsrLanguage.Auto) : SpeechChoice

    /**
     * 阿里云百炼实时语音识别;API Key 按地域另外加密存,这里只有模型、地域和业务空间 ID([workspace],
     * 规范过的,见 [BailianProtocol.workspace];空串用通用接入点)。
     */
    data class Bailian(
        val model: String = BailianProtocol.DEFAULT_MODEL,
        val region: BailianRegion = BailianRegion.Beijing,
        val workspace: String = "",
        val language: AsrLanguage = AsrLanguage.Auto,
    ) : SpeechChoice
}

/**
 * 服务商转写音频的接口形态。存的是名字,只能往后加、不能改名或删,旧设置才读得回来。
 * 带 ChatInputAudio 前缀的三种都是对话接口里的 input_audio,只是写法不同,选模型试转时依次都试:
 * [ChatInputAudio] 的 data 放裸 base64 并附一句转写指令;[ChatInputAudioUri] 的 data 换成
 * `data:audio/wav;base64,...` 的 data URI(有的服务只认这种);[ChatInputAudioOnly] 也用 data URI,
 * 但只发音频不带指令(专做转写的模型不收文字块)。
 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline, ChatInputAudioUri, ChatInputAudioOnly }

/**
 * 选择存在 [FeatureFlags] 那份设置里,一个键、一段 JSON;读不出来一律当 [SpeechChoice.Auto]。
 * 另记一个「手机自带识别不响应」([systemSilent]):按住说话时服务一直不回话,连着 [SILENT_STRIKES] 次才记上
 * (一次卡住多半是识别服务冷启动慢,不该从此关掉这条路);中间任何一次回了话就重新数。
 * 用户在设置里选「自动」或「手机自带」时记号和次数都清掉,好让换了识别服务、更新过系统的手机能再试。
 */
class SpeechSettings(private val prefs: SharedPreferences, private val cipher: KeyCipher = KeystoreKeyCipher("mika-speech-keys")) {
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()

    val hasDoubaoCredentials: Boolean get() = prefs.contains(DOUBAO_KEY)

    /** Save without making a billable network request. Both authentication generations are supported. */
    internal fun saveDoubao(credentials: DoubaoCredentials, resourceId: String) {
        require(credentials.valid && resourceId in DoubaoProtocol.resources)
        val json = buildJsonObject {
            put("apiKey", JsonPrimitive(credentials.apiKey))
            put("appId", JsonPrimitive(credentials.appId))
            put("accessKey", JsonPrimitive(credentials.accessKey))
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(json))
        val selected = SpeechChoice.Doubao(resourceId)
        prefs.edit().putString(DOUBAO_KEY, encrypted).putString(KEY, encode(selected).toString()).apply()
        _choice.value = selected
    }

    internal fun doubaoCredentials(): DoubaoCredentials? = runCatching {
        val encrypted = prefs.getString(DOUBAO_KEY, null) ?: return null
        val obj = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)).jsonObject
        DoubaoCredentials(
            obj["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["appId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["accessKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ).takeIf { it.valid }
    }.getOrNull()

    fun clearDoubao() {
        prefs.edit().remove(DOUBAO_KEY).apply()
        if (_choice.value is SpeechChoice.Doubao) set(SpeechChoice.Off)
    }

    /** 上次用过的自建 Fun-ASR 服务地址,换去别的引擎后再选回来不用重填。 */
    val funAsrUrl: String? get() = prefs.getString(FUNASR_URL, null)?.let(FunAsrProtocol::normalize)

    /** 上次给自建服务选的识别语言。 */
    val funAsrLanguage: AsrLanguage get() = AsrLanguage.of(prefs.getString(FUNASR_LANGUAGE, null))

    /** 选用这个自建 Fun-ASR 服务(地址先经 [FunAsrProtocol.normalize])。 */
    fun saveFunAsr(url: String, language: AsrLanguage = AsrLanguage.Auto) {
        val choice = SpeechChoice.FunAsr(requireNotNull(FunAsrProtocol.normalize(url)), language)
        prefs.edit().putString(FUNASR_URL, choice.url).putString(FUNASR_LANGUAGE, language.code).putString(KEY, encode(choice).toString())
            .putString(LIVE_LAST, encode(choice).toString()).apply()
        _choice.value = choice
    }

    /** 这个地域存没存过百炼的 API Key(北京、新加坡的 Key 不通用,各存一份)。 */
    fun hasBailianKey(region: BailianRegion): Boolean = prefs.contains(bailianKeyName(region))

    /** 加密存下这个地域的百炼 API Key 并选用;[apiKey] 为空时沿用这个地域已存的(只换模型)。不联网。 */
    internal fun saveBailian(apiKey: String, choice: SpeechChoice.Bailian) {
        require(BailianProtocol.validModel(choice.model) && BailianProtocol.workspace(choice.workspace) == choice.workspace)
        val edit = prefs.edit()
        if (apiKey.isNotBlank()) {
            val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(apiKey.trim().toByteArray(Charsets.UTF_8)))
            edit.putString(bailianKeyName(choice.region), encrypted)
        } else require(hasBailianKey(choice.region))
        val json = encode(choice).toString()
        edit.putString(KEY, json).putString(LIVE_LAST, json).putString(bailianModelName(choice.region), choice.model)
            .putString(bailianWorkspaceName(choice.region), choice.workspace)
            .putString(bailianLanguageName(choice.region), choice.language.code).apply()
        _choice.value = choice
    }

    /** 这个地域上次用的百炼模型;没有时是默认的。 */
    fun bailianModel(region: BailianRegion): String =
        prefs.getString(bailianModelName(region), null)?.takeIf(BailianProtocol::validModel) ?: BailianProtocol.DEFAULT_MODEL

    /** 这个地域上次选的识别语言。 */
    fun bailianLanguage(region: BailianRegion): AsrLanguage = AsrLanguage.of(prefs.getString(bailianLanguageName(region), null))

    /** 这个地域上次填的业务空间 ID;没填过是空串。 */
    fun bailianWorkspace(region: BailianRegion): String =
        prefs.getString(bailianWorkspaceName(region), null)?.let(BailianProtocol::workspace).orEmpty()

    internal fun bailianKey(region: BailianRegion): String? = runCatching {
        val encrypted = prefs.getString(bailianKeyName(region), null) ?: return null
        String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8).takeIf(String::isNotBlank)
    }.getOrNull()

    /** 删掉这个地域的百炼 Key;正在用这个地域就关掉语音输入。 */
    fun clearBailian(region: BailianRegion) {
        prefs.edit().remove(bailianKeyName(region)).remove(bailianModelName(region)).remove(bailianWorkspaceName(region)).remove(bailianLanguageName(region)).apply()
        if ((_choice.value as? SpeechChoice.Bailian)?.region == region) set(SpeechChoice.Off)
    }

    /**
     * 「千问实时语音识别」一行上次选用的是哪种(自建或百炼的某个地域),点圆圈时沿用它;
     * 那种已经用不了(地址读不出、Key 删了)或从没选过时为 null,界面改去设置页。
     */
    val lastLive: SpeechChoice? get() = runCatching {
        // 没记过上次那种时,正在用的就是
        val stored = prefs.getString(LIVE_LAST, null)?.let { decode(Json.parseToJsonElement(it).jsonObject) } ?: _choice.value
        when (val c = stored) {
            is SpeechChoice.FunAsr -> c
            is SpeechChoice.Bailian -> c.takeIf { hasBailianKey(it.region) }
            else -> null
        }
    }.getOrNull()

    /** 这一行算不算设置过:有可以沿用的那种。 */
    val hasLive: Boolean get() = lastLive != null

    /** Reads all still-addressable legacy choices without changing the rollback store. */
    internal fun legacySnapshot(): LegacySpeechSnapshot {
        val selected = _choice.value
        val storedDoubao = doubaoCredentials()
        check(!hasDoubaoCredentials || storedDoubao != null) { "Legacy Doubao credentials are unreadable" }
        val doubao = storedDoubao?.let { VoiceCredentials(it.apiKey, it.appId, it.accessKey) }
        val bailianCredentials = mutableMapOf<BailianRegion, VoiceCredentials>()
        val bailianChoices = BailianRegion.entries.mapNotNull { region ->
            val active = (selected as? SpeechChoice.Bailian)?.takeIf { it.region == region }
            val hasKey = hasBailianKey(region)
            val key = if (hasKey) bailianKey(region) else null
            check(!hasKey || key != null) { "Legacy Bailian credentials are unreadable" }
            if (key != null) bailianCredentials[region] = VoiceCredentials(apiKey = key)
            if (active == null && !hasKey) return@mapNotNull null
            active ?: SpeechChoice.Bailian(
                model = bailianModel(region),
                region = region,
                workspace = bailianWorkspace(region),
                language = bailianLanguage(region),
            )
        }
        val savedFunAsr = funAsrUrl?.let { SpeechChoice.FunAsr(it, funAsrLanguage) }
        return LegacySpeechSnapshot(
            selection = selected,
            savedChoices = (listOfNotNull(savedFunAsr) + bailianChoices + listOfNotNull(lastLive)).distinct(),
            doubaoCredentials = doubao,
            bailianCredentials = bailianCredentials,
        )
    }

    private val _systemSilent = MutableStateFlow(prefs.getBoolean(SILENT, false))
    val systemSilent: StateFlow<Boolean> = _systemSilent.asStateFlow()

    fun set(choice: SpeechChoice) {
        // 这两种都会去用手机自带识别:用户主动选了,就再给它机会
        val retry = choice == SpeechChoice.System || choice == SpeechChoice.Auto
        val edit = prefs.edit().putString(KEY, encode(choice).toString())
        if (retry) edit.remove(SILENT).remove(STRIKES)
        edit.apply()
        _choice.value = choice
        if (retry) _systemSilent.value = false
    }

    /** 重试系统识别时只清健康记号;迁移后旧选择仍保持只读。 */
    fun retrySystem() {
        prefs.edit().remove(SILENT).remove(STRIKES).apply()
        _systemSilent.value = false
    }

    /** 手机自带识别这次一个回调都没给:连着第 [SILENT_STRIKES] 次才记成不响应。 */
    fun noteSystemSilent() {
        val strikes = prefs.getInt(STRIKES, 0) + 1
        val edit = prefs.edit().putInt(STRIKES, strikes)
        if (strikes >= SILENT_STRIKES) edit.putBoolean(SILENT, true)
        edit.apply()
        if (strikes >= SILENT_STRIKES) _systemSilent.value = true
    }

    /**
     * 点名试通过的识别服务(系统默认的不回话、它回了),下次先试它;null 是没有,或者系统默认的就回话。
     * 见 [SystemSpeech.attempts]。
     */
    val systemService: String? get() = prefs.getString(SERVICE, null)

    /** 手机自带识别这次回了话([service] 是回话的那个,null 是系统默认的):之前没响应的次数作废,记住回话的服务。 */
    fun noteSystemAnswered(service: String? = null) {
        val edit = prefs.edit()
        var changed = false
        if (prefs.getInt(STRIKES, 0) != 0) { edit.remove(STRIKES); changed = true }
        if (service != systemService) {
            if (service == null) edit.remove(SERVICE) else edit.putString(SERVICE, service)
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun read(): SpeechChoice {
        val raw = prefs.getString(KEY, null) ?: return SpeechChoice.Auto
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: SpeechChoice.Auto
    }

    private fun encode(choice: SpeechChoice): JsonObject = buildJsonObject {
        when (choice) {
            SpeechChoice.Auto -> put(TYPE, JsonPrimitive("auto"))
            SpeechChoice.System -> put(TYPE, JsonPrimitive("system"))
            SpeechChoice.Off -> put(TYPE, JsonPrimitive("off"))
            is SpeechChoice.Doubao -> {
                put(TYPE, JsonPrimitive("doubao"))
                put("resourceId", JsonPrimitive(choice.resourceId))
            }
            is SpeechChoice.FunAsr -> {
                put(TYPE, JsonPrimitive("funasr"))
                put("url", JsonPrimitive(choice.url))
                if (choice.language != AsrLanguage.Auto) put("language", JsonPrimitive(choice.language.code))
            }
            is SpeechChoice.Bailian -> {
                put(TYPE, JsonPrimitive("bailian"))
                put("model", JsonPrimitive(choice.model))
                put("region", JsonPrimitive(choice.region.name))
                if (choice.workspace.isNotEmpty()) put("workspace", JsonPrimitive(choice.workspace))
                if (choice.language != AsrLanguage.Auto) put("language", JsonPrimitive(choice.language.code))
            }
            is SpeechChoice.Provider -> {
                put(TYPE, JsonPrimitive("provider"))
                put("providerId", JsonPrimitive(choice.providerId))
                put("model", JsonPrimitive(choice.model))
                put("method", JsonPrimitive(choice.method.name))
            }
        }
    }

    /** 字段缺了、类型不认识、方法名不存在都返回 null,由调用处退回自动。 */
    private fun decode(obj: JsonObject): SpeechChoice? = when (obj[TYPE]?.jsonPrimitive?.contentOrNull) {
        "auto" -> SpeechChoice.Auto
        "system" -> SpeechChoice.System
        "off" -> SpeechChoice.Off
        "doubao" -> obj["resourceId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in DoubaoProtocol.resources }?.let { SpeechChoice.Doubao(it) }
        "funasr" -> obj["url"]?.jsonPrimitive?.contentOrNull?.let(FunAsrProtocol::normalize)
            ?.let { SpeechChoice.FunAsr(it, AsrLanguage.of(obj["language"]?.jsonPrimitive?.contentOrNull)) }
        "bailian" -> {
            val model = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf(BailianProtocol::validModel)
            val region = BailianRegion.entries.firstOrNull { it.name == obj["region"]?.jsonPrimitive?.contentOrNull }
            val workspace = BailianProtocol.workspace(obj["workspace"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val language = AsrLanguage.of(obj["language"]?.jsonPrimitive?.contentOrNull)
            if (model == null || region == null || workspace == null) null else SpeechChoice.Bailian(model, region, workspace, language)
        }
        "provider" -> {
            val id = obj["providerId"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val method = TranscribeMethod.entries.firstOrNull { it.name == obj["method"]?.jsonPrimitive?.contentOrNull }
            if (id == null || model == null || method == null) null else SpeechChoice.Provider(id, model, method)
        }
        else -> null
    }

    companion object {
        private const val KEY = "speech_choice"
        private const val DOUBAO_KEY = "speech_doubao_credentials_encrypted"
        private const val FUNASR_URL = "speech_funasr_url"
        private const val LIVE_LAST = "speech_live_last"

        private fun bailianKeyName(region: BailianRegion) = "speech_bailian_key_encrypted_${region.name.lowercase()}"
        private fun bailianModelName(region: BailianRegion) = "speech_bailian_model_${region.name.lowercase()}"
        private fun bailianWorkspaceName(region: BailianRegion) = "speech_bailian_workspace_${region.name.lowercase()}"
        private fun bailianLanguageName(region: BailianRegion) = "speech_bailian_language_${region.name.lowercase()}"
        private const val FUNASR_LANGUAGE = "speech_funasr_language"
        private const val TYPE = "type"
        private const val SILENT = "speech_system_silent"
        private const val STRIKES = "speech_system_silent_strikes"
        private const val SERVICE = "speech_system_service"

        /** 连着这么多次不响应才记下。 */
        const val SILENT_STRIKES = 2

        fun get(context: Context) = SpeechSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
