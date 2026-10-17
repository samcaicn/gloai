package com.jev.probe.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private config store.
 *
 * v1.1 免费模式（本文件现状）：不再有 Creem 卡密 / 云端 token 计费。使用方式三种——
 *  ① 免费额度：默认，走 WeAuto 云端，每日条数由 [com.jev.probe.core.license.PackLedger]
 *     本地账本 + 云端 `X-Quota-*` 响应头共同校准；
 *  ② 自带接口：用户自填 OpenAI 兼容 base_url + key（[textCustom] 系列），直连不过云端；
 *  ③ 话术包：商家签发的 .wpk 离线授权（reply_quota）。
 *
 * 仍保留一个加密账户令牌（[accountToken]）供 worker 模式 bearer 用；旧的三方 provider
 * （openrouter / bocha / typesafe / vercel / zen / custom）配置仍保留，仅作 legacy 兜底。
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        if (prefsName == PREFS_MAIN) {
            migrateIfNeeded()
            unseedBochaDefaultIfUnconfigured()
            normalizeHiddenSettings()
            normalizeAutoDefaults()
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

    /**
     * 2026-10-15：自动回复开箱即用。用户明确要求不要再去设置页手动开一堆开关，
     * 自动分析 / 自动填入 / 自动发送 / 自动打开会话全部默认开启。
     *
     * 关键：旧版装机把 `auto_analyze` / `auto_send` 残留成 false 并持久化，而 SharedPreferences
     * 只在 key 缺失时才用 getter 默认值——`install -r` 升级后已存的 false 不会被新默认覆盖，
     * 导致「代码默认 true 但设备仍 false」。这里一次性把这几个 key 显式置 true（仅执行一次），
     * 升级后立刻生效。之后再允许用户手动关（getter 尊重已存值），本归一化不再进入。
     */
    private fun normalizeAutoDefaults() {
        if (sp.getBoolean(K_AUTO_DEFAULTS_V1, false)) return
        val e = sp.edit().putBoolean(K_AUTO_DEFAULTS_V1, true)
        e.putBoolean(K_AUTO, true)
        e.putBoolean(K_AUTO_SEND, true)
        e.putBoolean(K_AUTO_FILL, true)
        e.putBoolean(K_AUTO_OPEN, true)
        e.putBoolean(K_OCR_AUTO, true)
        e.apply()
        Log.i(TAG, "prefs: normalized auto defaults -> analyze/fill/send/open all true")
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
     * Jev 模型。worker / tuptup 两种「云端 jev」模式都清空 → 云端用其自身默认模型
     * （env.JEV_MODEL = @cf/meta/llama-3.3-70b-instruct），不把客户端的 one-api 模型名
     * （如 doubao-seed-2.0-pro）误发给只跑 Cloudflare Workers AI 的 jev 决策接口。
     * 否则 env.AI.run 拒掉该模型 → 502 → 回落到饱和的 one-api 网关 → 全 429。
     */
    var judgeModel: String
        get() = when {
            isWorkerMode -> ""
            judgeProvider == PROVIDER_TUPTUP -> ""
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
            judgeProvider == PROVIDER_TUPTUP -> ""   // 同 judge：走 Worker /ai/v1/chat 时用 Cloudflare Workers AI，不转发 one-api 模型名
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

    // --------------------------------------------------------------- text

    /**
     * 文本链路（判断/回复）自定义开关（v1.1 免费模式，对齐 PC 版 guard.py
     * 「用户自填 base_url + key → 直连自家模型」）：开启后判断/回复改发用户
     * 自配的 OpenAI 兼容接口，**不经过 WeAuto 云端、不计免费条数、不耗
     * Workers AI 额度**。优先级高于 worker 模式；留空 key 时部分网关仍可匿名用。
     */
    var textCustom: Boolean
        get() = sp.getBoolean(K_TEXT_CUSTOM, false)
        set(v) = sp.edit().putBoolean(K_TEXT_CUSTOM, v).apply()

    var textBaseUrl: String
        get() = sp.getString(K_TEXT_BASE, "") ?: ""
        set(v) = sp.edit().putString(K_TEXT_BASE, v.trim()).apply()

    /** 自定义文本接口的 key（加密保存，设置页不回显）。 */
    var textKey: String
        get() = SecureStore.decrypt(appContext, sp.getString(K_TEXT_KEY, "") ?: "")
        set(v) = sp.edit().putString(K_TEXT_KEY, SecureStore.encrypt(appContext, v.trim())).apply()

    /** 自定义文本接口的模型名；留空 = 用 App 默认模型。 */
    var textModel: String
        get() = sp.getString(K_TEXT_MODEL, "") ?: ""
        set(v) = sp.edit().putString(K_TEXT_MODEL, v.trim()).apply()

    /** 文本是否实际走自定义接口（开关开着且 base url 非空）。 */
    fun isTextCustom(): Boolean = textCustom && textBaseUrl.isNotBlank()

    // ----------------------------------------------------- 账户 / 计费（加密）

    /**
     * WeAuto 云端账户令牌（bearer token），由 `/token/issue` 或购买后自动下发。
     * **加密存储**（AndroidKeyStore AES-GCM），内存/磁盘均无明文。
     * 设置界面「账户」卡片里只显示长度或「已激活」，绝不回显内容。
     */
    var accountToken: String
        get() = SecureStore.decrypt(appContext, sp.getString(K_ACCOUNT_TOKEN, "") ?: "")
        set(v) = sp.edit().putString(K_ACCOUNT_TOKEN, SecureStore.encrypt(appContext, v.trim())).apply()

    // 配额与授权由 PackLedger 统一管理（每日免费条数 + 话术包离线回复次数），
    // 不再有 Creem 卡密 / 云端计费字段。

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

    /**
     * 待送达的自愈提示（空=无）。
     *
     * 通知是唯一的提示通道时，一旦通知权限被关，提示发出去却被系统全丢，
     * 用户永远看不到「哪里坏了」。这里做一个兜底：发通知前先查
     * areNotificationsEnabled()，被关就落到 prefs，等用户下次打开 App
     * 时在首页以醒目横幅补上——App 不一定在前台，但用户迟早会打开它。
     */
    var pendingAlert: String
        get() = sp.getString(K_PENDING_ALERT, "") ?: ""
        set(v) = sp.edit().putString(K_PENDING_ALERT, v).apply()

    /** 待送达提示的类型：无障碍掉线 / 输入法被重置。 */
    var pendingAlertKind: String
        get() = sp.getString(K_PENDING_ALERT_KIND, "") ?: ""
        set(v) = sp.edit().putString(K_PENDING_ALERT_KIND, v).apply()

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

    // 「自动分析」开关已去掉（2026-10-15 用户要求）：只要软件在跑、有消息进来就自动分析，
    // 不再给关的机会。setter 保留只为兼容云端备份里的旧 key，写入无效。
    var ocrAutoAnalyze: Boolean
        get() = true
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

    /**
     * 「不回复名单」：标题命中的会话**完全不自动回复**（连分析都不跑），
     * 与 [whitelist] 的「非空时只分析命中会话」方向相反——这是排除，且互不冲突：
     * 一个会话可以同时命中两个名单（白名单收窄分析范围、不回复名单再排除掉它）。
     * 存的同样是关键词（子串匹配，忽略大小写），「移出」= 删掉能匹配上的关键词。
     */
    var noReplyList: Set<String>
        get() = sp.getStringSet(K_NO_REPLY, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_NO_REPLY, v).apply()

    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    // 「自动分析」开关已去掉（2026-10-15 用户要求）：恒开。setter 保留只为兼容旧 key。
    var autoAnalyze: Boolean
        get() = true
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** 是否已完成首次引导（悬浮球首次出现时的产品说明卡）。只展示一次。 */
    var onboarded: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDED, v).apply()

    /**
     * 主动发送令牌：给「主动给旧会话发消息」功能做一层轻量鉴权。
     *
     * 该入口（[com.jev.probe.capture.ProactiveSendReceiver]）是 exported 广播——任何
     * App 都能发。无令牌会有人借最近来过通知的会话（= 有锚点）冒发消息。令牌是首启时
     * 生成的 12 位随机串，显示在设置页，adb 调试时也用得上（见 proactive_token.txt 镜像）。
     * 校验失败一律静默丢弃，不弹 toast（避免被扫频刷提醒）。
     */
    var proactiveToken: String
        get() {
            val cur = sp.getString(K_PROACTIVE_TOKEN, "") ?: ""
            if (cur.isNotBlank()) return cur
            val gen = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            sp.edit().putString(K_PROACTIVE_TOKEN, gen).apply()
            return gen
        }
        set(v) = sp.edit().putString(K_PROACTIVE_TOKEN, v.trim()).apply()

    // --------------------------------------------------- 无人值守（微信）

    /**
     * 填入回复后是否自动点「发送」。仅作用于微信。
     *
     * **默认关**（2026-10-05 改）：对外承诺是「发送权永远在人手里」，而这是全应用
     * 唯一会替用户把消息发出去的开关，新装不该默认打开。已经开过的老用户不受影响
     * （值已存在），且设置页开启时会有二次确认、运行期悬浮窗会挂「自动发送已开」角标。
     */
    var autoSend: Boolean
        // 2026-10-15：用户要求自动回复开箱即用，默认开启自动发送（填完即发）。
        // 仍需探针输入法处于激活态才会真正落到输入框；未激活时回落剪贴板+toast，不强行切 IME。
        get() = sp.getBoolean(K_AUTO_SEND, true)
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

    /**
     * 自动发送前是否随机等待一段时间（而非固定 [sendDelayMs]），让回复发得更像人、
     * 同时给用户一段窗口直接进微信改草稿。默认开。仅作用于自动回复（force 主动发送
     * 仍走固定 [sendDelayMs]，用户刚亲手点了"发送"不该再被随机拖延）。
     */
    var randomSendDelay: Boolean
        get() = sp.getBoolean(K_RANDOM_SEND_DELAY, true)
        set(v) = sp.edit().putBoolean(K_RANDOM_SEND_DELAY, v).apply()

    /** 随机等待下限（毫秒），默认 2s。 */
    var randomSendDelayMinMs: Int
        get() = sp.getInt(K_RANDOM_SEND_DELAY_MIN, 2000)
        set(v) = sp.edit().putInt(K_RANDOM_SEND_DELAY_MIN, v.coerceAtLeast(0)).apply()

    /** 随机等待上限（毫秒），默认 300s。 */
    var randomSendDelayMaxMs: Int
        get() = sp.getInt(K_RANDOM_SEND_DELAY_MAX, 300_000)
        set(v) = sp.edit().putInt(K_RANDOM_SEND_DELAY_MAX, v.coerceAtLeast(0)).apply()

    // ------------------------------------------------------------- helpers

    fun effectiveReplyKey(): String = when {
        isTextCustom() -> textKey
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

    /**
     * OpenAI-compatible chat completions 地址。
     * 分流优先级（v1.1 免费模式）：
     *   1. isTextCustom() —— 用户自填接口，直连、不过云端、不计免费条数；
     *   2. worker / tuptup —— 打 Worker 的 /ai/v1/chat/completions
     *      （Cloudflare Workers AI，免费条数由 Worker 侧计数）；
     *   3. legacy 自建 —— replyBaseUrl。
     */
    fun replyEndpoint(): String = when {
        isTextCustom() -> {
            val base = textBaseUrl.trim().trimEnd('/')
            if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
        isWorkerMode -> WORKER_BASE.trimEnd('/') + "/ai/v1/chat/completions"
        judgeProvider == PROVIDER_TUPTUP -> WORKER_BASE.trimEnd('/') + "/ai/v1/chat/completions"
        else -> "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"
    }

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

    /** 不回复名单命中判定：关键词子串匹配（忽略大小写），空名单恒不命中。 */
    fun isNoReply(title: String?): Boolean {
        val nl = noReplyList
        if (nl.isEmpty() || title.isNullOrBlank()) return false
        return nl.any { title.contains(it, ignoreCase = true) }
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
     * 运行时完整性失败标记（[Integrity.verify] 写入）。
     *
     * 一旦被标记为篡改（被重签/马甲包/注入框架/调试器），核心功能（自动回复、AI 判断、
     * 云端授权）在客户端侧降级为不可用——即便攻击者清数据重装同证书包也不会自愈。
     * 真正的不可绕过在 L3 服务端：服务端只按官方签名指纹放行激活与 AI 调用，
     * 马甲包证书≠官方 → 403 → 永远拿不到卡密、用不了云 AI。
     */
    var tampered: Boolean
        get() = sp.getBoolean(K_TAMPERED, false)
        set(v) = sp.edit().putBoolean(K_TAMPERED, v).apply()

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
            if (k == K_ACCOUNT_TOKEN) continue
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
            if (k == K_ACCOUNT_TOKEN) continue
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
        private const val K_AUTO_DEFAULTS_V1 = "auto_defaults_normalized_v1"

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
        private const val K_TEXT_BASE = "text_base_url"
        private const val K_TEXT_KEY = "text_key"
        private const val K_TEXT_MODEL = "text_model"
        private const val K_TEXT_CUSTOM = "text_custom"
        private const val K_ACCOUNT_TOKEN = "account_token_enc"
        private const val K_METRICS = "metrics_enabled"
        private const val K_LAST_MS = "last_analysis_ms"
        private const val K_LAST_OK = "last_analysis_ok"
        private const val K_LAST_ERR = "last_analysis_err"
        private const val K_PENDING_ALERT = "pending_alert"
        private const val K_PENDING_ALERT_KIND = "pending_alert_kind"
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
        private const val K_NO_REPLY = "no_reply_list"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_AUTO_SEND = "auto_send"
        private const val K_AUTO_OPEN = "auto_open_chat"
        private const val K_OCR_IMAGES = "ocr_images"
        private const val K_AUTO_FILL = "auto_fill_best"
        private const val K_SEND_DELAY = "send_delay_ms"
        private const val K_RANDOM_SEND_DELAY = "random_send_delay"
        private const val K_RANDOM_SEND_DELAY_MIN = "random_send_delay_min_ms"
        private const val K_RANDOM_SEND_DELAY_MAX = "random_send_delay_max_ms"
        private const val K_SYNC_RESTORED = "sync_restored"
        private const val K_LAST_SYNC = "last_sync_at"
        private const val K_SYNC_ENABLED = "sync_enabled"
        private const val K_TAMPERED = "integrity_tampered_v1"
        private const val K_ONBOARDED = "onboarded_v1"
        private const val K_PROACTIVE_TOKEN = "proactive_token_v1"

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
        // 2026-10-15 起切到 weauto-license Worker 自定义域 wetech.jukuai.net：
        // Pages 版（weauto-license.pages.dev）代码停在 1 天前快照——没有 /ai/tuptup/v1
        // 转发路由，且带 KV daily 30 条硬限额（今日实测打满两次，整条回复链 429 死）。
        // Worker 版（今天部署，含 aiapi 转发供应商 + secret AIAPI_TOKEN）功能是超集，
        // LLM / 支付 / 激活 / 升级接口全部同源。
        const val WORKER_HOST = "wetech.jukuai.net"
        const val WORKER_BASE = "https://wetech.jukuai.net"

        // ---- tuptup.top OpenAI 兼容网关（用户自备 LLM）----
        // 2026-10-15 起客户端不再直连 aiapi：改打 Worker 的 /ai/tuptup/v1 转发路由
        // （Worker 注入 AIAPI_KEY secret 并强制 auto 模型），key 不出现在客户端。
        // 下面两个常量仅作非 worker 模式的 legacy 兜底与探针内引用。
        const val TUPTUP_BASE = "https://aiapi.tuptup.top/v1"
        const val TUPTUP_KEY = "sk-15.1_XFAL9BjiPERLLCDZMBvI9jqO86m6S5df0zjJVq1fdR0"
        // auto = one-api 网关的自动路由入口。2026-10-15 实测 default 组
        // doubao-seed-2.0-pro / qwen3.7-max / kimi-k2.6 / deepseek-v4-pro 四模型
        // 全部「无可用渠道」（火山配额耗尽），仅 auto 通（路由到 Qwen2.5-7B）。
        // Worker 的 tuptup 转发也会强制把 model 覆盖为 auto，客户端传什么无所谓。
        const val TUPTUP_MODEL = "auto"

        /**
         * 历史模型降级链（保留仅为兼容旧设置页展示，实际链路已不用）。
         *
         * 2026-10-15：aiapi 改走 Worker /ai/tuptup/v1 转发且 Worker 侧强制 model=auto，
         * 客户端多模型轮询失去意义（auto 是网关内唯一稳定入口，按模型挂是常态）。
         * 若将来 Worker 转发支持显式指定模型，可恢复此链。
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
