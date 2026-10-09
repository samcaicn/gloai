package com.jev.probe.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
            normalizeHiddenSettings()
        }
    }

    /**
     * 设置页精简（2026-10-05）的一次性收口：处理那些**从界面上删掉、但仍影响运行**的项。
     *
     * 删 UI 最怕的不是少个开关，而是留下「隐形行为」——用户当初关掉了某项、现在界面上
     * 再没有地方改回来，而功能已经悄悄变了。逐项看过设置页删掉的东西，真正需要在这里
     * 兜底的只有一条：
     *
     *  - **云端备份**（syncEnabled，原本默认 **true**）：[App] 每次启动都会调
     *    [CloudSync.uploadNow]，唯一的闸门就是这个开关。界面上删掉它，就变成「三个 API
     *    密钥、知识库笔记、联系人每次启动自动上传，而用户永远找不到开关关掉」——与
     *    产品自己在设置页写下的隐私承诺（「读取的聊天内容只发往你自己配置的接口」）直接
     *    冲突。**改为默认关闭**。
     *
     * 其余删掉的项（本地 OCR 兜底 / 逐气泡识别 / OCR 自动分析 / 图片 OCR / 自动分析 /
     * 自动打开会话 / 自定义视觉接口 / 事件日志）**不需要在这里动手**：它们的 getter 默认值
     * 已经就是产品要的答案（OCR 与自动分析全 true、视觉自定义与事件日志全 false），用户
     * 没显式改过就等于默认值，改过的则按用户意愿保留。所以这里刻意不写它们——写一遍
     * 和默认值相同的值只会把「这是有意选的」和「这是继承来的」混为一谈。
     *
     * 只在用户**从未显式碰过** [K_SYNC_ENABLED] 时才写入；已经设过的（无论开还是关）
     * 尊重既有选择。执行一次后打标记，之后不再进入。
     */
    private fun normalizeHiddenSettings() {
        if (sp.getBoolean(K_HIDDEN_NORMALIZED, false)) return
        val e = sp.edit().putBoolean(K_HIDDEN_NORMALIZED, true)
        if (!sp.contains(K_SYNC_ENABLED)) e.putBoolean(K_SYNC_ENABLED, false)
        // 旧默认值里那句「from=me 的是我发的」是给模型看的字段说明，却原样显示在设置页，
        // 用户只会一脸茫然（prompt 两条路径都自己交代了说话人，那句话本就多余）。
        // 只清洗**恰好等于旧默认值**的那一串，不动用户自己写的描述。
        val rel = sp.getString(K_REL, "") ?: ""
        if (rel.startsWith(LEGACY_REL_PREFIX)) e.putString(K_REL, DEFAULT_REL)
        e.apply()
        Log.i(TAG, "prefs: cloud backup defaulted OFF after settings simplification")
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
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_TUPTUP) ?: PROVIDER_TUPTUP
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
        get() = when {
            isWorkerMode -> ""
            judgeProvider == PROVIDER_TUPTUP -> TUPTUP_MODEL
            else -> sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER) ?: DEFAULT_JUDGE_MODEL_OPENROUTER
        }
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
        get() = when {
            isWorkerMode -> ""
            judgeProvider == PROVIDER_TUPTUP -> TUPTUP_MODEL
            else -> sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        }
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    /**
     * 视觉接口自定义开关：开启后视觉请求改发用户自配的 OpenAI 兼容接口
     * （visionBaseUrl + visionKey + visionModel），不再经过 WeAuto 云端、不计量。
     * 优先级高于 worker 模式；判断/回复不受影响，仍走云端。
     */
    var visionCustom: Boolean
        get() = sp.getBoolean(K_VISION_CUSTOM, false)
        set(v) = sp.edit().putBoolean(K_VISION_CUSTOM, v).apply()

    /** 视觉是否实际走自定义接口（开关开着且 base url 非空）。 */
    fun isVisionCustom(): Boolean = visionCustom && visionBaseUrl.isNotBlank()

    /** 视觉模型名的原始存储值（设置页展示用，不受 worker/custom 分流影响）。 */
    fun visionModelStored(): String =
        sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL

    var visionModel: String
        get() = if (isVisionCustom())
            (sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL)
        else if (judgeProvider == PROVIDER_TUPTUP)
            TUPTUP_MODEL
        else if (!isWorkerMode)
            (sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL)
        else ""
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

    /**
     * 购买实例 ID（mid）：首次取值时生成高熵 UUID 并持久化。整个购买闭环
     * （checkout metadata.mid → webhook 写 lic:<mid> → /license?mid= 轮询）
     * 都靠它把付款关联到本机，重装前保持不变。
     */
    val licenseMid: String
        get() {
            val saved = sp.getString(K_LICENSE_MID, null)
            if (!saved.isNullOrBlank()) return saved
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "") + instanceId.hashCode().toUInt()
            sp.edit().putString(K_LICENSE_MID, fresh).apply()
            return fresh
        }

    /**
     * Put back a mid captured from a previous install, so an already-paid licence
     * stays reachable: the server keys Top-up records to this value, so generating
     * a fresh one after a reinstall would orphan the purchase forever. No-op once a
     * mid exists — a reinstall must not silently swap somebody's licence out.
     */
    fun adoptLicenseMid(v: String?) {
        val mid = v?.trim().orEmpty()
        if (mid.isBlank()) return
        if (!sp.getString(K_LICENSE_MID, null).isNullOrBlank()) return
        sp.edit().putString(K_LICENSE_MID, mid).apply()
        Log.i(TAG, "license mid adopted from backup")
    }

    /** 购买成功后由 /license?mid= 轮询拿到的卡密（加密存，同 accountToken）。 */
    var licenseKey: String
        get() = SecureStore.decrypt(appContext, sp.getString(K_LICENSE_KEY, "") ?: "")
        set(v) = sp.edit().putString(K_LICENSE_KEY, SecureStore.encrypt(appContext, v.trim())).apply()

    /** 本机已购买的 Creem 档位（normal/premium/lifetime），仅展示用。 */
    var licenseTier: String
        get() = sp.getString(K_LICENSE_TIER, "") ?: ""
        set(v) = sp.edit().putString(K_LICENSE_TIER, v).apply()

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

    // -------------------------------------------------------- 统计与自检（P0）

    /**
     * 本地事件日志开关（P0-1）。**默认关闭**——产品读的是聊天，任何统计都必须由用户
     * 显式同意。关闭时 [Metrics.log] 直接 no-op，不写任何文件。
     * 打开后只写本机 `filesDir/metrics/events.log`，不上传，且绝不记录聊天内容。
     */
    var metricsEnabled: Boolean
        get() = sp.getBoolean(K_METRICS, false)
        set(v) {
            sp.edit().putBoolean(K_METRICS, v).apply()
            Metrics.setEnabled(v)
        }

    /** 上一轮分析耗时（毫秒），自检卡展示用。与统计开关无关，只存数字。 */
    var lastAnalysisMs: Long
        get() = sp.getLong(K_LAST_MS, -1L)
        set(v) = sp.edit().putLong(K_LAST_MS, v).apply()

    /** 上一轮分析是否成功（true=出判断 / false=失败 / 未跑过=-1 由 [lastAnalysisMs] 表达）。 */
    var lastAnalysisOk: Boolean
        get() = sp.getBoolean(K_LAST_OK, false)
        set(v) = sp.edit().putBoolean(K_LAST_OK, v).apply()

    /** 上一轮分析的失败类别（见 ErrCatalog.Kind），成功时为空。 */
    var lastAnalysisErr: String
        get() = sp.getString(K_LAST_ERR, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_ERR, v).apply()

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

    /**
     * 微信内文字读取是否优先走本地 PaddleOCR（无障碍截屏 API，不触风控），
     * 逐气泡识别、收发方仍由节点树判定。关则退回纯树读。默认开——本地 OCR 优先。
     */
    var ocrPrimary: Boolean
        get() = sp.getBoolean(K_OCR_PRIMARY, true)
        set(v) = sp.edit().putBoolean(K_OCR_PRIMARY, v).apply()

    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, true)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * 「只分析这些会话」名单。
     *
     * 键名沿用历史的 `whitelist`（**不要改**：改了老用户的名单会凭空消失），但语义
     * 以这里为准：**非空时只分析标题命中的会话**，其余一律不分析——它是缩小范围，
     * 不是排除。界面文案已按这个语义写，见 ChatCaptureService.toggleWhitelist。
     */
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

    /** 是否已完成首次引导（悬浮球首次出现时的产品说明卡）。只展示一次。 */
    var onboarded: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDED, v).apply()

    // --------------------------------------------------- 无人值守（微信）

    /**
     * 填入回复后是否自动点「发送」。仅作用于微信。
     *
     * **默认关**（2026-10-05 改）：对外承诺是「发送权永远在人手里」，而这是全应用
     * 唯一会替用户把消息发出去的开关，新装不该默认打开。已经开过的老用户不受影响
     * （值已存在），且设置页开启时会有二次确认、运行期悬浮窗会挂「自动发送已开」角标。
     */
    var autoSend: Boolean
        get() = sp.getBoolean(K_AUTO_SEND, false)
        set(v) = sp.edit().putBoolean(K_AUTO_SEND, v).apply()

    /**
     * 收到微信消息时，若微信不在前台，是否自动点开该会话（通过通知的 contentIntent）。
     * 开启后才能做到真正"不在手机旁"也自动接管。默认开：自动监听的核心开关之一
     * （配合 autoAnalyze + 通知监听），关掉则只在用户已停留在聊天界面时才自动分析。
     */
    var autoOpenChat: Boolean
        get() = sp.getBoolean(K_AUTO_OPEN, true)
        set(v) = sp.edit().putBoolean(K_AUTO_OPEN, v).apply()

    /**
     * 对方发来图片消息（节点树读不到字）时，是否截一次屏用 OCR 读图里文字作为补充。
     * 仅这一步才在微信内截屏（避开其风控最严的"手动截图"路径，走无障碍截屏 API）。
     */
    var ocrImages: Boolean
        get() = sp.getBoolean(K_OCR_IMAGES, true)
        set(v) = sp.edit().putBoolean(K_OCR_IMAGES, v).apply()

    /**
     * 分析完成后是否直接按排名第一的候选填入（配合 [autoSend] 即全自动收发）。
     * 默认开：配合通知监听可做到「对方来信 → 自动分析 → 填入 → 延迟发送」全链路无人值守。
     * 仅作用于微信；开启后仍只会填/发候选里的原文，不会改写。
     */
    var autoFillBest: Boolean
        get() = sp.getBoolean(K_AUTO_FILL, true)
        set(v) = sp.edit().putBoolean(K_AUTO_FILL, v).apply()

    /**
     * 填入后延迟多久点发送（毫秒）。留一点间隔是为了让微信把草稿真正落进编辑框、
     * 发送按钮从「语音/表情」态切回「发送」态，间隔太短会点空。
     */
    var sendDelayMs: Int
        get() = sp.getInt(K_SEND_DELAY, 2000)
        set(v) = sp.edit().putInt(K_SEND_DELAY, v.coerceIn(0, 30_000)).apply()

    // ------------------------------------------------------------- helpers

    fun effectiveReplyKey(): String = when {
        isWorkerMode -> accountToken
        judgeProvider == PROVIDER_TUPTUP -> TUPTUP_KEY
        else -> replyKey.ifBlank { judgeKey }
    }

    /** 视觉鉴权：自定义接口用自配 key（留空则退回账户令牌）；云端模式用账户令牌。 */
    fun effectiveVisionKey(): String = when {
        isVisionCustom() -> visionKey.ifBlank { accountToken }
        isWorkerMode -> accountToken
        judgeProvider == PROVIDER_TUPTUP -> TUPTUP_KEY
        else -> visionKey.ifBlank { effectiveReplyKey() }
    }

    /** Full POST URL for the Jev decisions call. Worker 模式走云端 /ai/jev/decisions。 */
    fun judgeEndpoint(): String {
        if (isWorkerMode) return WORKER_BASE.trimEnd('/') + "/ai/jev/decisions"
        if (judgeProvider == PROVIDER_TUPTUP) return TUPTUP_BASE.trimEnd('/') + "/chat/completions"
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

    /**
     * Jev 决策服务地址（jev 优先路径专用，与 judgeProvider 无关）。
     * provider=tuptup 时用户没配自建 jev，就打云端 worker 的决策接口。
     */
    fun jevDecisionsEndpoint(): String = when {
        isWorkerMode -> WORKER_BASE.trimEnd('/') + "/ai/jev/decisions"
        judgeProvider == PROVIDER_TUPTUP -> WORKER_BASE.trimEnd('/') + "/ai/jev/decisions"
        else -> judgeEndpoint()
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String =
        if (isWorkerMode) WORKER_BASE.trimEnd('/') + "/ai/v1/chat/completions"
        else if (judgeProvider == PROVIDER_TUPTUP) TUPTUP_BASE.trimEnd('/') + "/chat/completions"
        else "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"

    fun visionEndpoint(): String {
        if (isVisionCustom()) {
            val base = visionBaseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
        if (isWorkerMode) return WORKER_BASE.trimEnd('/') + "/ai/v1/chat/completions"
        if (judgeProvider == PROVIDER_TUPTUP) return TUPTUP_BASE.trimEnd('/') + "/chat/completions"
        val base = visionBaseUrl.trim().ifBlank { DEFAULT_VISION_BASE }
        return "${base.trimEnd('/')}/chat/completions"
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: worker 模式下看账户令牌，tuptup 看内置密钥，否则看判断接口 key。 */
    fun hasKey(): Boolean = when {
        isWorkerMode -> accountToken.isNotBlank()
        judgeProvider == PROVIDER_TUPTUP -> TUPTUP_KEY.isNotBlank()
        else -> judgeKey.isNotBlank()
    }

    /**
     * 云端备份 marks whether this install has already tried to pull a snapshot
     * down. Without it every launch would attempt an import and clobber local
     * edits made in between.
     */
    var syncRestored: Boolean
        get() = sp.getBoolean(K_SYNC_RESTORED, false)
        set(v) = sp.edit().putBoolean(K_SYNC_RESTORED, v).apply()

    /** ms epoch of the last successful upload; shown in settings, 0 = never. */
    var lastSyncAt: Long
        get() = sp.getLong(K_LAST_SYNC, 0L)
        set(v) = sp.edit().putLong(K_LAST_SYNC, v).apply()

    /** Kill switch for cloud backup, for users who would rather not send anything. */
    var syncEnabled: Boolean
        get() = sp.getBoolean(K_SYNC_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_SYNC_ENABLED, v).apply()

    /**
     * Every preference as plain values, for the cloud snapshot.
     *
     * Two entries are deliberately left out: `account_token_enc` and
     * `license_key_enc`. Both are AES-GCM blobs sealed by the AndroidKeyStore key
     * `weauto_billing_aes`, and **that key is destroyed along with the app**.
     * Copying the ciphertext to a new install would restore a string that can
     * never decrypt again — worse, it would make [hasKey] report "activated"
     * while silently producing empty credentials. The recoverable credential is
     * `license_mid`: with the same mid the existing `/license?mid=` polling
     * hands back the purchased key from the server.
     */
    fun exportAll(): JSONObject = JSONObject().apply {
        for ((k, v) in sp.all) {
            if (k == K_ACCOUNT_TOKEN || k == K_LICENSE_KEY) continue
            when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Float -> put(k, v)
                is Set<*> -> put(k, JSONArray(v.filterIsInstance<String>()))
            }
        }
    }

    /**
     * Apply a previously exported map. Existing values win — a restore must never
     * run backwards over something the user changed since the snapshot was taken.
     *
     * @return number of keys actually written (0 means everything was already set)
     */
    fun importAll(obj: JSONObject): Int {
        val e = sp.edit()
        var n = 0
        for (k in obj.keys()) {
            if (k == K_ACCOUNT_TOKEN || k == K_LICENSE_KEY) continue
            if (sp.contains(k)) continue
            val v = obj.get(k)
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until v.length()) v.optString(i)?.let { set.add(it) }
                    e.putStringSet(k, set)
                }
                else -> continue
            }
            n++
        }
        e.apply()
        return n
    }

    companion object {
        private const val TAG = "JEVASSIST"

        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_UNSEEDED_BOCHA = "unseeded_bocha_v141"
        private const val K_HIDDEN_NORMALIZED = "hidden_settings_normalized_v145"

        /** 旧版 [DEFAULT_REL] 的开头，用来识别「这串是旧默认值、不是我写的」。 */
        private const val LEGACY_REL_PREFIX = "对方是我的伴侣；"
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
        private const val K_VISION_CUSTOM = "vision_custom"
        private const val K_ACCOUNT_TOKEN = "account_token_enc"
        private const val K_BILLING_TIER = "billing_tier"
        private const val K_BILLING_USED = "billing_used"
        private const val K_BILLING_QUOTA = "billing_quota"
        private const val K_LICENSE_MID = "license_mid"
        private const val K_METRICS = "metrics_enabled"
        private const val K_LAST_MS = "last_analysis_ms"
        private const val K_LAST_OK = "last_analysis_ok"
        private const val K_LAST_ERR = "last_analysis_err"
        private const val K_LICENSE_KEY = "license_key_enc"
        private const val K_LICENSE_TIER = "license_tier"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
    private const val K_OCR_FALLBACK = "ocr_fallback"
    private const val K_OCR_PRIMARY = "ocr_primary"
    private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_AUTO_SEND = "auto_send"
        private const val K_AUTO_OPEN = "auto_open_chat"
        private const val K_OCR_IMAGES = "ocr_images"
        private const val K_AUTO_FILL = "auto_fill_best"
        private const val K_SEND_DELAY = "send_delay_ms"
        private const val K_SYNC_RESTORED = "sync_restored"
        private const val K_LAST_SYNC = "last_sync_at"
        private const val K_SYNC_ENABLED = "sync_enabled"
        private const val K_ONBOARDED = "onboarded_v1"

        // ---- provider ----
        const val PROVIDER_WORKER = "worker"
        const val PROVIDER_BOCHA = "bocha"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_VERCEL = "vercel"
        const val PROVIDER_ZEN = "zen"
        const val PROVIDER_CUSTOM = "custom"
        const val PROVIDER_TUPTUP = "tuptup"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // ---- WeAuto 云端（唯一 LLM 出口）----
        const val WORKER_BASE = "https://weauto.safeopc.cn"

        /**
         * Creem 直连结账短链（三档对应 [LicenseClient.TIERS]）。
         *
         * 为什么直连：原 [WORKER_BASE]/buy 走 weauto.safeopc.cn 的 Cloudflare Worker
         * 动态建 checkout，但该源站证书/443 故障会整页 525（"SSL 握手失败、页面打不开"）。
         * 这里改成本地硬编码 Creem 结账短链（落在用户自有的 pay.jukuai.net 域，Vercel 托管，
         * 与 safeopc Worker 隔离），WebView 直接打开，**彻底绕过 525 的那一层**。
         *
         * 链接是 Creem API 建的会话短链（一次性），需轮换时在 Dev 侧重跑建链脚本即可：
         *   curl -X POST https://api.creem.io/v1/checkouts \
         *     -H "x-api-key: $CREEM_KEY" -H 'Content-Type: application/json' \
         *     -d '{"product_id":"<prod_xxx>"}'
         * 取返回的 checkout_url 填到这里。App 内绝不持有 Creem API key。
         * 界面对用户只显示「安全支付」，不露任何内部域名。
         */
        val CREEM_CHECKOUT_URLS = mapOf(
            LicenseClient.TIER_NORMAL to "https://pay.jukuai.net/checkout/prod_1xaKxzuVRluYDmFvaq2dQj/ch_3Wdf8t4uahOmNaqmVTLfZ3",
            LicenseClient.TIER_PREMIUM to "https://pay.jukuai.net/checkout/prod_1BrJoEUEGGuWZGRX0trS6h/ch_3F7SMPVmtEMVIxMwTnJJ6E",
            LicenseClient.TIER_LIFETIME to "https://pay.jukuai.net/checkout/prod_4QeZzI7YyAmySg3F0XfuZK/ch_2HdxkEOJGwSTedymbM4Pgz"
        )

        // ---- tuptup.top OpenAI 兼容网关（用户自备 LLM，判断/回复/视觉统一走这里）----
        // 密钥按用户要求硬编码为默认值；此 key 会出现在源码与公开镜像里，介意请改走设置页自填。
        const val TUPTUP_BASE = "https://aiapi.tuptup.top/v1"
        const val TUPTUP_KEY = "sk-15.1_XFAL9BjiPERLLCDZMBvI9jqO86m6S5df0zjJVq1fdR0"
        // doubao seed 系列在 tuptup.top 唯一有可用渠道的聊天模型（seed-1.6-flash 无渠道、seedance 未定价）。
        // 网关上游偶发限流(429)为瞬态，恢复后即可用。deepseek-v3.1 在该网关无渠道(503)，已弃用。
        const val TUPTUP_MODEL = "doubao-seed-2.0-pro"

        /**
         * 判断/回复的模型降级链（第一个是 [TUPTUP_MODEL]）。
         *
         * 为什么要降级：one-hub 网关的"上游负载已饱和"(429 insufficient_user_quota) 与
         * "无可用渠道"(503) 是**按模型**发生的——同一个网关上 13 个模型实测会同时全挂，
         * 但也常常只有一两个在饱和。此时换模型立刻就能出结果，比让用户干等一个死模型强得多。
         * 顺序按「稳定性优先」：pro 系列最稳，flash 系列饱和率明显更高。
         * 全部失败才把最后一个错误抛给上层（由 [com.jev.probe.core.ErrCatalog] 归类展示）。
         */
        val TUPTUP_FALLBACK_MODELS = listOf(
            TUPTUP_MODEL,
            "qwen3.7-max",
            "kimi-k2.6",
            "deepseek-v4-pro",
            "minimax-m3",
            "glm-5.2",
        )

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

        /**
         * 关系描述留空时喂给模型的兜底文本。
         *
         * 这里**不要**再写「from=me 的是我发的」这类字段说明：两条 prompt 路径都已经
         * 自己交代了说话人——[JevQuestions.buildState] 用结构化的 `from` 字段，
         * `LlmJudge` 直接渲染成「我：/ 对方：」。它过去被写进来只是因为同一份字符串
         * 还要显示在设置页给用户看，而用户看到 `from=me` 只会一脸茫然。
         */
        const val DEFAULT_REL = "对方是我的伴侣"
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
