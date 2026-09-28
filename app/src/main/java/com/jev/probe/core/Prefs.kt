package com.jev.probe.core

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * App-private config store.
 *
 * v2 起（本文件）：三个 LLM 路由（judge / reply / vision）统一由 WeAuto 云端
 * `weauto.safeopc.cn` 提供，**默认即 worker 模式**，设置界面不再暴露 base url / key / model
 * （见 SettingsActivity「账户」卡片）。云端负责鉴权与 token 计费，客户端只持有一个
 * 加密保存的账户令牌（见 [accountToken] / [SecureStore]）。
 *
 * 旧的三方 provider（openrouter / bocha / typesafe / vercel / zen / custom）配置仍保留，
 * 仅作为 legacy 兜底，正常安装不会再走到。
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        if (prefsName == PREFS_MAIN) {
            migrateIfNeeded()
            unseedBochaDefaultIfUnconfigured()
        }
    }

    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    private fun unseedBochaDefaultIfUnconfigured() {
        if (sp.getBoolean(K_UNSEEDED_BOCHA, false)) return
        val e = sp.edit().putBoolean(K_UNSEEDED_BOCHA, true)
        val prov = sp.getString(K_JUDGE_PROVIDER, null)
        val key = sp.getString(K_JUDGE_KEY, "") ?: ""
        if (prov == PROVIDER_BOCHA && key.isBlank()) {
            e.remove(K_JUDGE_PROVIDER).remove(K_JUDGE_BASE).remove(K_JUDGE_MODEL)
            Log.i(TAG, "prefs: reverted auto-seeded bocha default to worker")
        }
        e.apply()
    }

    // ---------------------------------------------------------- worker 模式

    /** 是否走 WeAuto 云端（默认 true）。 */
    val isWorkerMode: Boolean
        get() = judgeProvider == PROVIDER_WORKER

    /**
     * 设备级稳定 ID，作为 X-WeAuto-Instance 发送，便于云端做并发/风控。
     * 用 Android ID（非硬件序列号），重装 App 会变，但足够做实例区分。
     */
    val instanceId: String
        get() = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    // ---------------------------------------------------------------- judge

    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_WORKER) ?: PROVIDER_WORKER
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER) ?: DEFAULT_JUDGE_BASE_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    /** Legacy 字段；worker 模式下不会用到，仍保留以免旧备份读不到。 */
    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    /**
     * Jev 模型。worker 模式下清空 → 云端用其自身默认模型（env.JEV_MODEL），
     * 不把客户端的旧 OpenRouter 模型名（如 typesafe/jev-1.13）误发给 Workers AI。
     */
    var judgeModel: String
        get() = if (isWorkerMode) "" else (sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER) ?: DEFAULT_JUDGE_MODEL_OPENROUTER)
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    var replyModel: String
        get() = if (isWorkerMode) "" else (sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL)
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    var visionModel: String
        get() = if (isWorkerMode) "" else (sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL)
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // ----------------------------------------------------- 账户 / 计费（加密）

    /**
     * WeAuto 云端账户令牌（bearer token），由 `/token/issue` 或购买后自动下发。
     * **加密存储**（AndroidKeyStore AES-GCM），内存/磁盘均无明文。
     * 设置界面「账户」卡片里只显示长度或「已激活」，绝不回显内容。
     */
    var accountToken: String
        get() = SecureStore.decrypt(appContext, sp.getString(K_ACCOUNT_TOKEN, "") ?: "")
        set(v) = sp.edit().putString(K_ACCOUNT_TOKEN, SecureStore.encrypt(appContext, v.trim())).apply()

    /** 最近一次云端返回的档位 id（basic/standard/pro），仅用于本地展示。 */
    var billingTier: String
        get() = sp.getString(K_BILLING_TIER, "") ?: ""
        set(v) = sp.edit().putString(K_BILLING_TIER, v).apply()

    /** 本月已用 token 数（本地缓存，真实以云端为准）。 */
    var billingUsed: Long
        get() = sp.getLong(K_BILLING_USED, 0L)
        set(v) = sp.edit().putLong(K_BILLING_USED, v).apply()

    /** 本月额度 token 数（本地缓存）。 */
    var billingQuota: Long
        get() = sp.getLong(K_BILLING_QUOTA, 0L)
        set(v) = sp.edit().putLong(K_BILLING_QUOTA, v).apply()

    /** 客户端用计费回传刷新本地缓存。 */
    fun recordBilling(s: BillingState?) {
        if (s == null) return
        if (s.tier.isNotBlank()) billingTier = s.tier
        if (s.quota > 0) billingQuota = s.quota
        billingUsed = s.used
    }

    // -------------------------------------------------------- context (D)

    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    // ------------------------------------------------------------- helpers

    fun effectiveReplyKey(): String = if (isWorkerMode) accountToken else replyKey.ifBlank { judgeKey }
    fun effectiveVisionKey(): String = if (isWorkerMode) accountToken else visionKey.ifBlank { effectiveReplyKey() }

    /** Full POST URL for the Jev decisions call. Worker 模式走云端 /ai/jev/decisions。 */
    fun judgeEndpoint(): String {
        if (isWorkerMode) return WORKER_BASE.trimEnd('/') + "/ai/jev/decisions"
        val base = judgeBaseUrl.trim().trimEnd('/')
        return when (judgeProvider) {
            PROVIDER_BOCHA -> "$base/v1/systemone"
            PROVIDER_TYPESAFE -> "$base/v1/systemone"
            PROVIDER_VERCEL -> "$base/v1/systemone"
            PROVIDER_ZEN -> "$base/v1/systemone"
            PROVIDER_CUSTOM -> judgeBaseUrl.trim()
            else -> "$base/alpha/decisions"
        }
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String =
        if (isWorkerMode) WORKER_BASE.trimEnd('/') + "/ai/v1/chat/completions"
        else "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"

    fun visionEndpoint(): String =
        if (isWorkerMode) WORKER_BASE.trimEnd('/') + "/ai/v1/chat/completions"
        else {
            val base = visionBaseUrl.trim().ifBlank { DEFAULT_VISION_BASE }
            "${base.trimEnd('/')}/chat/completions"
        }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: worker 模式下看账户令牌，否则看判断接口 key。 */
    fun hasKey(): Boolean = if (isWorkerMode) accountToken.isNotBlank() else judgeKey.isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_UNSEEDED_BOCHA = "unseeded_bocha_v141"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_ACCOUNT_TOKEN = "account_token_enc"
        private const val K_BILLING_TIER = "billing_tier"
        private const val K_BILLING_USED = "billing_used"
        private const val K_BILLING_QUOTA = "billing_quota"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        // ---- provider ----
        const val PROVIDER_WORKER = "worker"
        const val PROVIDER_BOCHA = "bocha"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_VERCEL = "vercel"
        const val PROVIDER_ZEN = "zen"
        const val PROVIDER_CUSTOM = "custom"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // ---- WeAuto 云端（唯一 LLM 出口）----
        const val WORKER_BASE = "https://weauto.safeopc.cn"

        // ---- legacy 预设（仅兜底）----
        const val DEFAULT_JUDGE_BASE_BOCHA = "https://jev.bocha.cn"
        const val DEFAULT_JUDGE_MODEL_BOCHA = "bocha-jev-v1"
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"
        const val DEFAULT_JUDGE_BASE_VERCEL = "https://ai-gateway.vercel.sh/typesafe"
        const val DEFAULT_JUDGE_MODEL_VERCEL = "typesafe-ai/jev"
        const val DEFAULT_JUDGE_BASE_ZEN = "https://opencode.ai/zen"
        const val DEFAULT_JUDGE_MODEL_ZEN = "jev-1.13"

        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        const val DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}

/**
 * Worker 回传的计费快照。客户端不自己算 token，只搬运云端通过
 * `X-Billing-*` 响应头给的数字，用于设置页展示。
 */
data class BillingState(
    var tier: String = "",
    var used: Long = 0L,
    var quota: Long = 0L,
    var remain: Long = 0L
)
