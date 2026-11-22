package com.wangzan101.mika.calls

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credentials are encrypted together; never put decrypted fields in a state flow or an error. */
class CallSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-volc-realtime-calls"),
) {
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    internal val credentialsRevision = MutableStateFlow(0L)

    @Synchronized
    fun save(config: CallConfig): CallConfig {
        val defaults = CallConfig()
        val previous = mutable.value
        require(config.defaultOutgoingSimId == null || CallPolicy.isRememberedSimId(config.defaultOutgoingSimId)) {
            "保存的拨号 SIM 无效，请重新选择"
        }
        require(config.allowedIncomingNumbers.size <= CallPolicy.MAX_ALLOWED_NUMBERS) { "最多可设置 100 个来电号码" }
        val numbers = config.allowedIncomingNumbers.map {
            requireNotNull(CallPolicy.incomingNumber(it)) { "自动接听名单中包含无效号码" }
        }.distinctBy(CallPolicy::incomingMatchKey).toSet()
        require(config.bypassIncomingNumbers.size <= CallPolicy.MAX_ALLOWED_NUMBERS) { "最多可设置 100 个本人接听号码" }
        val bypass = config.bypassIncomingNumbers.map {
            requireNotNull(CallPolicy.incomingNumber(it)) { "本人接听名单中包含无效号码" }
        }.distinctBy(CallPolicy::incomingMatchKey).toSet()
        require(config.greeting.length <= CallPolicy.MAX_GREETING_CHARS) { "开场白过长" }
        require(config.systemPrompt.length <= CallPolicy.MAX_PROMPT_CHARS) { "通话说明过长" }
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(config.activeVoiceProfileId)) { "实时语音配置无效" }
        val speaker = config.speaker.trim().ifBlank { defaults.speaker }
        require(speaker.length <= CallPolicy.MAX_SPEAKER_CHARS && speaker.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-" }) {
            "无效的火山音色编号"
        }
        val audioVerified = config.audioVerified && config.audioMode == previous.audioMode
        val next = config.copy(
            allowedIncomingNumbers = numbers,
            bypassIncomingNumbers = bypass,
            autoAnswerPausedUntilMs = config.autoAnswerPausedUntilMs?.takeIf { it > System.currentTimeMillis() },
            audioVerified = audioVerified,
            autoAnswer = config.enabled && audioVerified && config.autoAnswer,
            backgroundStandbyAuthorized = previous.backgroundStandbyAuthorized && config.enabled && audioVerified && config.autoAnswer,
            autoAnswerDelaySeconds = config.autoAnswerDelaySeconds.coerceIn(
                CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS, CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS,
            ),
            maxCallSeconds = CallPolicy.clampCallSeconds(config.maxCallSeconds),
            greeting = config.greeting.trim(), // An explicitly empty greeting is different from a missing preference.
            systemPrompt = config.systemPrompt.trim(),
            speaker = speaker,
            credentialsConfigured = config.credentialsConfigured,
        )
        val committed = prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putBoolean("summary_enabled", next.summaryEnabled)
            .putBoolean("auto_answer", next.autoAnswer)
            .putBoolean(BACKGROUND_AUTHORIZED, next.backgroundStandbyAuthorized)
            .putInt("auto_answer_delay_seconds", next.autoAnswerDelaySeconds)
            .putString("default_outgoing_sim_id", next.defaultOutgoingSimId)
            .putBoolean("allow_all", next.incomingScope == IncomingScope.ALL)
            .putString("incoming_scope", next.incomingScope.name)
            .putStringSet("incoming_types", next.allowedCallTypes.map { it.name }.toSet())
            .putStringSet("allowlist", numbers)
            .putStringSet("bypass_incoming", bypass)
            .putLong("auto_answer_paused_until_ms", next.autoAnswerPausedUntilMs ?: 0L)
            .putInt("max_seconds", next.maxCallSeconds)
            .putString("audio_mode", next.audioMode.name)
            .putString("audio_verified_build", Build.FINGERPRINT.takeIf { next.audioVerified })
            .putString("greeting", next.greeting)
            .putString("prompt", next.systemPrompt)
            .putString("speaker", next.speaker)
            .putString("active_voice_profile_id", next.activeVoiceProfileId)
            .commit()
        // A failed write may not leave auto-answer running with a policy the user tried to change.
        mutable.value = if (committed) next else previous.copy(enabled = next.enabled && previous.enabled, autoAnswer = false, backgroundStandbyAuthorized = false)
        check(committed) { "无法保存电话设置" }
        return next
    }

    /** After migration the catalog owns realtime verification state; mirrors only those two mirrors. */
    @Synchronized
    internal fun mirrorRealtimeState(credentialsConfigured: Boolean, audioVerified: Boolean) {
        val previous = mutable.value
        if (previous.credentialsConfigured == credentialsConfigured && previous.audioVerified == audioVerified) return
        save(previous.copy(
            credentialsConfigured = credentialsConfigured,
            audioVerified = audioVerified,
            autoAnswer = previous.enabled && audioVerified && previous.autoAnswer,
        ))
    }

    /** Called only by the foreground standby action. A live service is checked separately. */
    @Synchronized
    internal fun authorizeBackgroundStandby(): Boolean {
        val config = mutable.value
        if (!config.enabled || !config.autoAnswer || !config.audioVerified || !config.credentialsConfigured) return false
        if (config.backgroundStandbyAuthorized) return true
        if (!prefs.edit().putBoolean(BACKGROUND_AUTHORIZED, true).commit()) return false
        mutable.value = config.copy(backgroundStandbyAuthorized = true)
        return true
    }

    @Synchronized
    fun saveCredentials(appId: String, accessKey: String, apiKey: String) {
        val legacyId = appId.trim()
        val legacyKey = accessKey.trim()
        val modern = apiKey.trim()
        require(listOf(legacyId, legacyKey, modern).all { value ->
            value.length <= 1_024 && value.all { it.code in 33..126 }
        }) { "火山凭据格式无效" }
        val clear = legacyId.isEmpty() && legacyKey.isEmpty() && modern.isEmpty()
        require(clear || (modern.isNotEmpty() && legacyId.isEmpty() && legacyKey.isEmpty()) ||
            (modern.isEmpty() && legacyId.isNotEmpty() && legacyKey.isNotEmpty())) {
            "请仅填写 API Key，或同时填写旧版 App ID 和 Access Key"
        }
        val edit = prefs.edit()
        if (clear) {
            edit.remove(CREDENTIALS).putBoolean("auto_answer", false).putBoolean(BACKGROUND_AUTHORIZED, false)
        } else {
            val bytes = "${modern}\n${legacyId}\n${legacyKey}".toByteArray()
            try {
                val encrypted = runCatching { cipher.encrypt(bytes) }.getOrElse { error("无法加密火山凭据") }
                edit.putString(CREDENTIALS, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            } finally {
                bytes.fill(0)
            }
        }
        val committed = edit.commit()
        if (committed || clear) {
            mutable.value = mutable.value.copy(credentialsConfigured = !clear,
                autoAnswer = !clear && mutable.value.autoAnswer,
                backgroundStandbyAuthorized = !clear && mutable.value.backgroundStandbyAuthorized)
            credentialsRevision.value++
        }
        check(committed) { "无法保存火山凭据" }
    }

    @Synchronized
    internal fun readLegacyCredentials(): VolcRealtimeCredentials? {
        val stored = prefs.getString(CREDENTIALS, null) ?: return null
        val bytes = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
        return try {
            val fields = bytes.toString(Charsets.UTF_8).split('\n')
            if (fields.size != 3) return null
            VolcRealtimeCredentials(apiKey = fields[0], appId = fields[1], accessKey = fields[2]).takeIf { it.valid }
        } finally {
            bytes.fill(0)
        }
    }

    private fun read(): CallConfig = runCatching {
        val defaults = CallConfig()
        val enabled = prefs.getBoolean("enabled", false)
        val verified = prefs.getString("audio_verified_build", null)?.let { it == Build.FINGERPRINT } == true
        CallConfig(
            enabled = enabled,
            autoAnswer = enabled && verified && prefs.getBoolean("auto_answer", false),
            backgroundStandbyAuthorized = enabled && verified && prefs.getBoolean("auto_answer", false) &&
                prefs.getBoolean(BACKGROUND_AUTHORIZED, false),
            autoAnswerDelaySeconds = prefs.getInt("auto_answer_delay_seconds", defaults.autoAnswerDelaySeconds)
                .coerceIn(CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS, CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS),
            defaultOutgoingSimId = prefs.getString("default_outgoing_sim_id", null),
            incomingScope = prefs.getString("incoming_scope", null)?.let { name ->
                IncomingScope.entries.find { it.name == name } ?: IncomingScope.LIST
            } ?: if (prefs.getBoolean("allow_all", false)) IncomingScope.ALL else IncomingScope.LIST,
            allowedCallTypes = prefs.getStringSet("incoming_types", emptySet()).orEmpty().mapNotNull { name ->
                IncomingCallType.entries.find { it.name == name }
            }.toSet(),
            allowedIncomingNumbers = prefs.getStringSet("allowlist", emptySet()).orEmpty()
                .mapNotNull(CallPolicy::incomingNumber).distinctBy(CallPolicy::incomingMatchKey)
                .take(CallPolicy.MAX_ALLOWED_NUMBERS).toSet(),
            bypassIncomingNumbers = prefs.getStringSet("bypass_incoming", emptySet()).orEmpty()
                .mapNotNull(CallPolicy::incomingNumber).distinctBy(CallPolicy::incomingMatchKey)
                .take(CallPolicy.MAX_ALLOWED_NUMBERS).toSet(),
            autoAnswerPausedUntilMs = prefs.getLong("auto_answer_paused_until_ms", 0L)
                .takeIf { it > System.currentTimeMillis() },
            maxCallSeconds = CallPolicy.clampCallSeconds(prefs.getInt("max_seconds", CallPolicy.DEFAULT_SECONDS)),
            audioMode = prefs.getString("audio_mode", null)?.let(CallAudioMode::valueOf) ?: defaults.audioMode,
            audioVerified = verified,
            credentialsConfigured = runCatching { readLegacyCredentials() }.getOrNull() != null,
            summaryEnabled = prefs.getBoolean("summary_enabled", false),
            greeting = CallPolicy.migrateDefaultGreeting(prefs.getString("greeting", defaults.greeting).orEmpty())
                .take(CallPolicy.MAX_GREETING_CHARS),
            systemPrompt = prefs.getString("prompt", "").orEmpty().take(CallPolicy.MAX_PROMPT_CHARS),
            speaker = prefs.getString("speaker", defaults.speaker).orEmpty().take(CallPolicy.MAX_SPEAKER_CHARS),
            activeVoiceProfileId = prefs.getString("active_voice_profile_id", defaults.activeVoiceProfileId)
                .takeIf { it?.matches(Regex("[A-Za-z0-9_-]{1,100}")) == true } ?: defaults.activeVoiceProfileId,
        )
    }.getOrDefault(CallConfig())

    companion object {
        private const val CREDENTIALS = "volc_credentials_v1"
        internal const val BACKGROUND_AUTHORIZED = "background_standby_authorized"

        fun create(context: Context): CallSettings =
            CallSettings(context.getSharedPreferences("mika_call_settings", Context.MODE_PRIVATE))
    }
}
