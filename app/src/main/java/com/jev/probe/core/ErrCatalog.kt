package com.jev.probe.core

import android.content.Context
import com.jev.probe.jev.ApiException

/**
 * 错误归一（P0-3）：把「判断接口 HTTP 429：服务繁忙」这类原始串翻译成一句人话 +
 * 一个能立刻做的动作。
 *
 * 为什么要有这一层：用户看到 `HTTP 429` 只会觉得「坏了」，而 429 的真实含义是
 * 「你现在走的是共享额度，高峰期在排队」——这既是解释，也是最合适的付费引导时机
 * （P1-3）。所以 [Kind.BUSY] 的动作指向 [Action.PLAN]，不是「重试」。
 */
object ErrCatalog {

    /** 错误类别。同时用于埋点与自检卡的 `lastAnalysisErr`。 */
    enum class Kind(val id: String) {
        /** 401/403：密钥无效、过期、没权限。 */
        AUTH("auth"),

        /** 429/529：共享网关限流或过载。免费档最常见。 */
        BUSY("busy"),

        /**
         * Cloudflare Workers AI 免费额度耗尽（4006）。
         * 与 [BUSY] 的本质区别：BUSY 等一会儿就好，这个要等约 24 小时——免费额度
         * 文档写"00:00 UTC 重置"，实测是**滚动 24h 窗口**（耗尽那次调用起算，
         * 见 cloudflare-docs issue #34137：23:36 耗尽 → 次日 23:51 才恢复）。
         * 所以文案绝不能再说"等一会儿再试"，那是让用户白等一整天。
         */
        QUOTA("quota"),

        /** 超时 / 解析失败 / 连不上：纯网络问题。 */
        NETWORK("network"),

        /** 界面里读不到正文（自绘控件、空列表）。 */
        NO_TEXT("no_text"),

        /** 系统拒绝无障碍截屏（errorCode 1/2）。 */
        OCR_DENIED("ocr_denied"),

        /** 压根没配密钥。 */
        NO_KEY("no_key"),

        /** 一轮跑超时（看门狗兜底）。 */
        TIMEOUT("timeout"),

        /** 服务端 5xx 或其它非 4xx 状态。 */
        SERVER("server"),

        /** 归类不出来，原样展示。 */
        UNKNOWN("unknown")
    }

    /** 错误卡上那个按钮该干什么。 */
    enum class Action { RETRY, SETTINGS, PLAN, A11Y }

    data class View(
        val kind: Kind,
        val title: String,
        val message: String,
        val action: Action = Action.RETRY,
        val actionLabel: String = "重试"
    )

    /**
     * 从异常归类。优先看 HTTP 状态码，其次看文案关键词。
     *
     * [ctx] 用于界面双语取词（用户看到的地方都双语）；为 null 时回退中文，
     * 兼容无界面的调用（自检文本等）。[inWeChat] 只影响 [Kind.NO_TEXT] 一类：
     * 微信内整屏截屏会触发风控，所以 `ocrCaptureManual` 在那里直接拒绝执行
     * （见 ChatCaptureService）。若不告诉 classify 这件事，「截屏识别一次」
     * 这个按钮在微信里就是一个点了必然没反应的死路——比不给按钮更伤。
     */
    fun classify(e: Throwable?, inWeChat: Boolean = false): View = classify(null, e?.message, e, inWeChat)

    fun classify(msg: String?, e: Throwable? = null, inWeChat: Boolean = false): View =
        classify(null, msg, e, inWeChat)

    fun classify(ctx: Context?, e: Throwable?, inWeChat: Boolean = false): View =
        classify(ctx, e?.message, e, inWeChat)

    fun classify(ctx: Context?, msg: String?, e: Throwable? = null, inWeChat: Boolean = false): View {
        val raw = msg?.trim().orEmpty()
        val low = raw.lowercase()
        val status = (e as? ApiException)?.status
            ?: Regex("""HTTP (\d{3})""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        fun w(zh: String, en: String) = ctx?.t(zh, en) ?: zh
        val retry = { w("重试", "Retry") }

        if (status == 401 || status == 403) return View(
            Kind.AUTH, w("密钥不可用", "Key unavailable"),
            w("接口返回 $status：密钥无效、过期或没有这个模型的权限。去设置里换一把，或换一个服务商。",
                "The endpoint returned $status: the key is invalid, expired, or lacks access to this model. Set a new one in Settings, or switch providers."),
            Action.SETTINGS, w("去设置", "Settings"))

        if (status == 429 || status == 529) return View(
            Kind.BUSY, w("共享额度正忙", "Shared quota busy"),
            w("现在用的是共享额度（免费档），高峰期会排队、返回 $status。换成专属额度就不再排队，也不用自己配密钥。",
                "You're on the shared free quota — it queues at peak times and returned $status. Switch to a dedicated plan to skip the queue; no key setup needed."),
            Action.PLAN, w("看看专属额度", "See plans"))

        // CF Workers AI 免费额度耗尽（4006）。注意它经 Worker 包装后是 502 回来的，
        // 必须排在下面 5xx 通用分支之前，否则会掉进"等一会儿再试"的错文案。
        // 关键词同时覆盖英文原文（4006/neurons/free allocation）与 Worker 的中文包装。
        if (low.contains("4006") || low.contains("neurons") || low.contains("free allocation") ||
            low.contains("免费额度") || low.contains("额度已") || low.contains("额度耗尽")
        ) return View(
            Kind.QUOTA, w("今天的免费额度用完了", "Daily free quota used up"),
            w("云端共享的免费额度每天 1 万 neurons，今天已经用完（4006）。它按滚动 24 小时恢复，" +
                "不是零点重置——重试要等恢复，急着用可以看专属额度。",
                "The shared free quota (10,000 neurons/day) is used up for today (4006). " +
                    "It recovers on a rolling 24-hour window, not at midnight UTC — retrying won't help until then. See plans if you need it now."),
            Action.PLAN, w("看看专属额度", "See plans"))

        // Cloudflare 回源失败（521/522/523/524/525）：源站证书或 443 挂了。
        // 必须在 5xx 之前单独判——否则会掉进下面的 SERVER 通用文案，用户看不出
        // "等一会儿再试" 这次真的没用（源站一直不通，重试多少次都一样），
        // 也会误以为是自己配置的问题。动作是重试（万一站方刚好修好了），
        // 但文案必须说清这是站方的锅。
        if (status == 521 || status == 522 || status == 523 || status == 524 || status == 525) {
            return View(
                Kind.SERVER, w("服务方源站出问题了", "Provider origin is down"),
                w("接口返回 $status：Cloudflare 连不上源站（源站证书过期、443 没开或服务已宕）。" +
                    "这不是你的配置问题，重试要等站方修好。重试一次看看是否已恢复。",
                    "The endpoint returned $status: Cloudflare can't reach the origin (expired certificate, port 443 closed, or the service is down). " +
                        "This isn't your configuration — retrying only helps once the provider fixes it. Try once to see if it recovered."),
                Action.RETRY, retry())
        }

        if (status != null && status >= 500) return View(
            Kind.SERVER, w("接口那边出错了", "Endpoint error"),
            w("接口返回 $status。不是你的配置问题，等一会儿再试通常就好。",
                "The endpoint returned $status. Not your configuration — waiting a moment and retrying usually fixes it."),
            Action.RETRY, retry())

        if (status != null && status in 400..499) return View(
            Kind.SERVER, w("请求被接口拒绝", "Request rejected"),
            w("接口返回 $status：${raw.take(60)}。多半是地址或模型名填错了，去设置里核对一下。",
                "The endpoint returned $status: ${raw.take(60)}. The URL or model name is probably wrong — check them in Settings."),
            Action.SETTINGS, w("去设置", "Settings"))

        if (low.contains("timed out") || low.contains("timeout") || low.contains("超时") ||
            low.contains("unable to resolve") || low.contains("failed to connect") ||
            low.contains("econnerfused") || low.contains("econnrefused")
        ) return View(
            Kind.NETWORK, w("网络不通", "Network unreachable"),
            w("请求没到接口就断了。检查一下网络（或代理 / VPN），再试一次。",
                "The request never reached the endpoint. Check your network (or proxy / VPN) and try again."),
            Action.RETRY, retry())

        if (low.contains("certpath") || low.contains("ssl") || low.contains("handshake")) return View(
            Kind.NETWORK, w("HTTPS 没连上", "HTTPS failed"),
            w("证书校验失败，常见于代理、抓包工具或系统时间不对。",
                "Certificate validation failed — common with proxies, packet captures, or a wrong system clock."),
            Action.RETRY, retry())

        if (low.contains("密钥") || low.contains("api key") || low.contains("未设置判断接口")) return View(
            Kind.NO_KEY, w("还没配接口", "Endpoint not configured"),
            w("判断接口没有可用密钥。填一把自己的 key，或者直接选一个套餐走云端额度。",
                "No usable key for the judgment endpoint. Add your own key, or pick a plan to use cloud quota."),
            Action.SETTINGS, w("去设置", "Settings"))

        if (low.contains("没认出") || low.contains("没有文字") || low.contains("没读到")) {
            return if (inWeChat) View(
                Kind.NO_TEXT, w("这一屏没读到文字", "No text read on this screen"),
                w("聊天里的消息文字读不到了。点「重新分析」再试一次，或稍后再看。",
                    "Message text can't be read right now. Tap \"Analyze again\" to retry, or try again later."),
                Action.RETRY, w("重新分析", "Analyze again")
            ) else View(
                Kind.NO_TEXT, w("这一屏没读到文字", "No text read on this screen"),
                w("界面里没有能读到的文字。可点悬浮球菜单的「截屏识别一次」再试。",
                    "No readable text in this view. Try \"Capture screen\" from the bubble menu."),
                Action.RETRY, w("截屏识别一次", "Capture screen")
            )
        }

        if (low.contains("截屏") && (low.contains("拒绝") || low.contains("不允许") ||
                low.contains("未开启") || low.contains("重开") || low.contains("errorcode"))
        ) return View(
            Kind.OCR_DENIED, w("系统不允许截屏", "System blocked screen capture"),
            w("无障碍截屏被系统拒绝了。把设置里的无障碍开关关掉再打开一次，截屏能力才会生效。",
                "Accessibility screen capture was denied. Toggle the accessibility switch in Settings off and back on to re-enable it."),
            Action.A11Y, w("去无障碍设置", "Accessibility settings"))

        if (low.contains("超时") && low.contains("分析")) return View(
            Kind.TIMEOUT, w("分析超时", "Analysis timed out"),
            w("一轮分析超过看门狗时限还没结果，已自动中止。通常是网络慢，重试即可。",
                "One analysis round exceeded the watchdog limit and was aborted. Usually a slow network — just retry."),
            Action.RETRY, retry())

        if (raw.isBlank()) return View(
            Kind.UNKNOWN, w("出错了", "Something went wrong"),
            w("（没有错误信息）", "(no error message)"), Action.RETRY, retry())

        return View(Kind.UNKNOWN, w("出错了", "Something went wrong"), raw.take(120), Action.RETRY, retry())
    }
}
