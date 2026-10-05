package com.jev.probe.core

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
     * [inWeChat] 只影响 [Kind.NO_TEXT] 一类：微信内整屏截屏会触发风控，所以
     * `ocrCaptureManual` 在那里直接拒绝执行（见 ChatCaptureService）。若不告诉
     * classify 这件事，「截屏识别一次」这个按钮在微信里就是一个点了必然没反应的
     * 死路——比不给按钮更伤。
     */
    fun classify(e: Throwable?, inWeChat: Boolean = false): View = classify(e?.message, e, inWeChat)

    fun classify(msg: String?, e: Throwable? = null, inWeChat: Boolean = false): View {
        val raw = msg?.trim().orEmpty()
        val low = raw.lowercase()
        val status = (e as? ApiException)?.status
            ?: Regex("""HTTP (\d{3})""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

        if (status == 401 || status == 403) return View(
            Kind.AUTH, "密钥不可用",
            "接口返回 $status：密钥无效、过期或没有这个模型的权限。去设置里换一把，或换一个服务商。",
            Action.SETTINGS, "去设置")

        if (status == 429 || status == 529) return View(
            Kind.BUSY, "共享额度正忙",
            "现在用的是共享额度（免费档），高峰期会排队、返回 $status。换成专属额度就不再排队，也不用自己配密钥。",
            Action.PLAN, "看看专属额度")

        // Cloudflare 回源失败（521/522/523/524/525）：源站证书或 443 挂了。
        // 必须在 5xx 之前单独判——否则会掉进下面的 SERVER 通用文案，用户看不出
        // "等一会儿再试" 这次真的没用（源站一直不通，重试多少次都一样），
        // 也会误以为是自己配置的问题。动作是重试（万一站方刚好修好了），
        // 但文案必须说清这是站方的锅。
        if (status == 521 || status == 522 || status == 523 || status == 524 || status == 525) {
            return View(
                Kind.SERVER, "服务方源站出问题了",
                "接口返回 $status：Cloudflare 连不上源站（源站证书过期、443 没开或服务已宕）。" +
                    "这不是你的配置问题，重试要等站方修好。重试一次看看是否已恢复。",
                Action.RETRY, "重试")
        }

        if (status != null && status >= 500) return View(
            Kind.SERVER, "接口那边出错了",
            "接口返回 $status。不是你的配置问题，等一会儿再试通常就好。",
            Action.RETRY, "重试")

        if (status != null && status in 400..499) return View(
            Kind.SERVER, "请求被接口拒绝",
            "接口返回 $status：${raw.take(60)}。多半是地址或模型名填错了，去设置里核对一下。",
            Action.SETTINGS, "去设置")

        if (low.contains("timed out") || low.contains("timeout") || low.contains("超时") ||
            low.contains("unable to resolve") || low.contains("failed to connect") ||
            low.contains("econnerfused") || low.contains("econnrefused")
        ) return View(
            Kind.NETWORK, "网络不通",
            "请求没到接口就断了。检查一下网络（或代理 / VPN），再试一次。",
            Action.RETRY, "重试")

        if (low.contains("certpath") || low.contains("ssl") || low.contains("handshake")) return View(
            Kind.NETWORK, "HTTPS 没连上",
            "证书校验失败，常见于代理、抓包工具或系统时间不对。",
            Action.RETRY, "重试")

        if (low.contains("密钥") || low.contains("api key") || low.contains("未设置判断接口")) return View(
            Kind.NO_KEY, "还没配接口",
            "判断接口没有可用密钥。填一把自己的 key，或者直接选一个套餐走云端额度。",
            Action.SETTINGS, "去设置")

        if (low.contains("没认出") || low.contains("没有文字") || low.contains("没读到")) {
            return if (inWeChat) View(
                Kind.NO_TEXT, "这一屏没读到文字",
                "微信里的消息文字读不到了。可以先点「重新分析」再试一次（微信版本更新后" +
                    "节点结构常变）；仍然读不到的话，等下次微信更新或长按气泡做一次自检。",
                Action.RETRY, "重新分析"
            ) else View(
                Kind.NO_TEXT, "这一屏没读到文字",
                "界面里没有能读到的正文（自绘控件很常见）。可以点悬浮球菜单的「截屏识别一次」用本机 OCR 试一下。",
                Action.RETRY, "截屏识别一次"
            )
        }

        if (low.contains("截屏") && (low.contains("拒绝") || low.contains("不允许") ||
                low.contains("未开启") || low.contains("重开") || low.contains("errorcode"))
        ) return View(
            Kind.OCR_DENIED, "系统不允许截屏",
            "无障碍截屏被系统拒绝了。把设置里的无障碍开关关掉再打开一次，截屏能力才会生效。",
            Action.A11Y, "去无障碍设置")

        if (low.contains("超时") && low.contains("分析")) return View(
            Kind.TIMEOUT, "分析超时",
            "一轮分析超过看门狗时限还没结果，已自动中止。通常是网络慢，重试即可。",
            Action.RETRY, "重试")

        if (raw.isBlank()) return View(
            Kind.UNKNOWN, "出错了", "（没有错误信息）", Action.RETRY, "重试")

        return View(Kind.UNKNOWN, "出错了", raw.take(120), Action.RETRY, "重试")
    }
}
