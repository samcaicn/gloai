package com.tuptup.speech.data

import android.content.Context

/** 本机配置：工作信息、AI 接口、播讲与音色。全部存 SharedPreferences，不上传、不依赖账号。 */
class Settings(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tuptup_speech", Context.MODE_PRIVATE)

    /** 基本工作信息 = 店铺名称 + 工作内容，AI 据此生成话术 */
    var shopName: String
        get() = sp.getString("shopName", "") ?: ""
        set(v) = sp.edit().putString("shopName", v).apply()

    var workInfo: String
        get() = sp.getString("workInfo", sp.getString("shopFeatures", "") ?: "") ?: ""
        set(v) = sp.edit().putString("workInfo", v).apply()

    /** 供天气补充信息使用；AI 生成不依赖此项 */
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

    /** 音色模式：default=主播音色，mine=我的声音（本机复刻） */
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

    // ---- 评论联动 ----
    var commentSpeakEnabled: Boolean
        get() = sp.getBoolean("commentSpeakEnabled", true)
        set(v) = sp.edit().putBoolean("commentSpeakEnabled", v).apply()

    var liveUrl: String
        get() = sp.getString("liveUrl", "") ?: ""
        set(v) = sp.edit().putString("liveUrl", v).apply()

    /** 评论采集节点选择器：默认通用，特殊平台可改 */
    var commentSelector: String
        get() = sp.getString("commentSelector", "[class*=comment], [class*=danmu], .chat-item")
            ?: "[class*=comment], [class*=danmu], .chat-item"
        set(v) = sp.edit().putString("commentSelector", v).apply()

    /** 补充工作/产品信息后是否立即重新生成话术 */
    var autoRegen: Boolean
        get() = sp.getBoolean("autoRegen", true)
        set(v) = sp.edit().putBoolean("autoRegen", v).apply()
}
