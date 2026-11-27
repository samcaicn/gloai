package com.tuptup.speech.data

import android.content.Context
import java.security.MessageDigest

/** 本机配置：店铺/产品背景、文本 API、播讲与决策开关。
 *  全部存 SharedPreferences，不上传、不依赖账号。 */
class Settings(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tuptup_speech", Context.MODE_PRIVATE)

    var shopName: String
        get() = sp.getString("shopName", "") ?: ""
        set(v) = sp.edit().putString("shopName", v).apply()

    var shopLocation: String
        get() = sp.getString("shopLocation", "") ?: ""
        set(v) = sp.edit().putString("shopLocation", v).apply()

    var shopFeatures: String
        get() = sp.getString("shopFeatures", "") ?: ""
        set(v) = sp.edit().putString("shopFeatures", v).apply()

    var city: String
        get() = sp.getString("city", "郑州") ?: "郑州"
        set(v) = sp.edit().putString("city", v).apply()

    var apiBase: String
        get() = sp.getString("apiBase", "") ?: ""
        set(v) = sp.edit().putString("apiBase", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var apiModel: String
        get() = sp.getString("apiModel", "") ?: ""
        set(v) = sp.edit().putString("apiModel", v).apply()

    val apiConfigured: Boolean
        get() = apiBase.isNotBlank() && apiKey.isNotBlank() && apiModel.isNotBlank()

    var selectedVoiceId: String
        get() = sp.getString("selectedVoiceId", "") ?: ""
        set(v) = sp.edit().putString("selectedVoiceId", v).apply()

    var hasVoiceProfile: Boolean
        get() = sp.getBoolean("hasVoiceProfile", false)
        set(v) = sp.edit().putBoolean("hasVoiceProfile", v).apply()

    var voicePitch: Float
        get() = sp.getFloat("voicePitch", 1.0f)
        set(v) = sp.edit().putFloat("voicePitch", v).apply()

    var voiceVolume: Float
        get() = sp.getFloat("voiceVolume", 1.0f)
        set(v) = sp.edit().putFloat("voiceVolume", v).apply()

    var ttsSpeed: Float
        get() = sp.getFloat("ttsSpeed", 1.0f)
        set(v) = sp.edit().putFloat("ttsSpeed", v).apply()

    var jevEnabled: Boolean
        get() = sp.getBoolean("jevEnabled", true)
        set(v) = sp.edit().putBoolean("jevEnabled", v).apply()

    /** 音色模式：default=默认音色，mine=我的声音（本机复刻） */
    var voiceMode: String
        get() = sp.getString("voiceMode", "default") ?: "default"
        set(v) = sp.edit().putString("voiceMode", v).apply()

    /** 我的声音：参考音频对应的原文 */
    var localRefText: String
        get() = sp.getString("localRefText", "") ?: ""
        set(v) = sp.edit().putString("localRefText", v).apply()

    val useLocalVoice: Boolean
        get() = voiceMode == "mine"

    /** 主播音色包当前选中的音色 ID */
    var anchorVoiceSid: Int
        get() = sp.getInt("anchorVoiceSid", 3)
        set(v) = sp.edit().putInt("anchorVoiceSid", v).apply()

    /** 播讲风格：热情/专业/亲切/幽默 */
    var speakStyle: String
        get() = sp.getString("speakStyle", "热情") ?: "热情"
        set(v) = sp.edit().putString("speakStyle", v).apply()

    // ---- 评论联动 ----
    var commentSpeakEnabled: Boolean
        get() = sp.getBoolean("commentSpeakEnabled", true)
        set(v) = sp.edit().putBoolean("commentSpeakEnabled", v).apply()

    var commentAutoReplyEnabled: Boolean
        get() = sp.getBoolean("commentAutoReplyEnabled", true)
        set(v) = sp.edit().putBoolean("commentAutoReplyEnabled", v).apply()

    var commentSelector: String
        get() = sp.getString("commentSelector", "[class*=comment], [class*=danmu], .chat-item") ?: "[class*=comment], [class*=danmu], .chat-item"
        set(v) = sp.edit().putString("commentSelector", v).apply()

    var liveUrl: String
        get() = sp.getString("liveUrl", "") ?: ""
        set(v) = sp.edit().putString("liveUrl", v).apply()

    // ---- 评论采集登录（本机密码，不联网） ----
    /** 采集密码的 SHA-256（加固定盐），不存明文 */
    var collectPinHash: String
        get() = sp.getString("collectPinHash", "") ?: ""
        set(v) = sp.edit().putString("collectPinHash", v).apply()

    val hasCollectPin: Boolean get() = collectPinHash.isNotBlank()

    fun setCollectPin(pin: String) { collectPinHash = sha256("tuptup-collect:$pin") }

    fun checkCollectPin(pin: String): Boolean =
        hasCollectPin && collectPinHash == sha256("tuptup-collect:$pin")

    fun clearCollectPin() { sp.edit().remove("collectPinHash").apply() }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
