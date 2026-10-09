package com.jev.probe.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 一键自检（P0-4）。把「为什么没反应」这类问题变成一张用户自己能看懂、
 * 也能直接发给作者的卡片。
 *
 * 检查的是**结果**而不是设置项：权限有没有真的生效、上一轮分析到底成了没、
 * 近期 OCR 失败了多少次、有没有崩过。输出纯文本，不上传。
 */
object SelfCheck {

    private const val TAG = "JEVASSIST"

    /** 伪装服务组件名（微信节点要靠它才暴露）。 */
    private const val A11Y_COMPONENT =
        "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    data class Report(val lines: List<String>, val ok: Boolean) {
        val text: String get() = lines.joinToString("\n")
    }

    fun run(ctx: Context): Report {
        val prefs = Prefs(ctx)
        val l = ArrayList<String>()

        val a11y = isA11yOn(ctx)
        val overlay = Settings.canDrawOverlays(ctx)
        val notify = isNotifyOn(ctx)
        val battery = isBatteryUnrestricted(ctx)

        l.add(t(ctx, "【设备】", "[Device]") + "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        l.add("")
        l.add(t(ctx, "【必要条件】", "[Requirements]"))
        l.add("${mark(a11y)} " + t(ctx, "无障碍服务", "Accessibility service") +
            if (a11y) t(ctx, "已开启", ": on")
            else t(ctx, "未开启 —— 读不到聊天内容，助手不会工作", ": off — chat content can't be read, the assistant won't work"))
        l.add("${mark(overlay)} " + t(ctx, "悬浮窗", "Overlay") +
            if (overlay) t(ctx, "已允许", ": allowed")
            else t(ctx, "未允许 —— 分析结果没有地方显示", ": not allowed — results have nowhere to show"))
        l.add("${mark(notify)} " + t(ctx, "通知监听", "Notification access") +
            if (notify) t(ctx, "已启用", ": enabled")
            else t(ctx, "未启用 —— 微信来新消息不会自动触发", ": not enabled — new WeChat messages won't trigger analysis"))
        l.add("${mark(battery)} " + t(ctx, "省电无限制", "Battery unrestricted") +
            if (battery) t(ctx, "已放开", ": yes")
            else t(ctx, "未放开 —— 国产 ROM 会冻结后台，气泡会消失", ": no — Chinese ROMs freeze background apps and the bubble disappears"))

        // 服务端连通性：权限全绿但服务挂了时，用户看到的仍是"已就绪"却怎么都不出
        // 结果——这是最难自查的一类故障。主动打一次（GET，不消耗额度），
        // 把「站方挂了」和「你没配好」彻底分开。
        val live = probeEndpoint(ctx)
        l.add("")
        l.add(t(ctx, "【服务接口】", "[Cloud service]"))
        // 给用户看的只说「云端服务」，不露具体域名（safeopc.cn / tuptup.top 等内部信息）；
        // 真要做服务端排查时域名仍在 logcat（Live.host）里，作者自己看。
        when {
            live.code == 200 -> l.add("✓ " + t(ctx, "云端服务可达", "Cloud service reachable"))
            live.cfOrigin -> l.add("✗ " + t(ctx, "云端服务回源失败（HTTP ${live.code}）",
                "Cloud service origin unreachable (HTTP ${live.code})"))
            else -> l.add("! " + t(ctx, "云端服务返回 HTTP ${live.code}",
                "Cloud service returned HTTP ${live.code}") +
                if (live.note.isBlank()) "" else " · ${live.note}")
        }
        if (live.cfOrigin) {
            l.add("  " + t(ctx,
                "这是服务方源站的问题（证书/443/宕机），不是你的配置问题，重试无用。",
                "This is the provider's origin problem (certificate / port 443 / outage), not your configuration — retrying won't help."))
        } else if (live.code != 200 && live.code != 0) {
            l.add("  " + t(ctx,
                "先确认下面「上一次分析」的类别；若是 401 去设置里换密钥，若是 429 属共享额度排队。",
                "Check the \"Last analysis\" category below; 401 means set a new key in Settings, 429 means shared-quota queueing."))
        }

        l.add("")
        l.add(t(ctx, "【上一次分析】", "[Last analysis]"))
        val ms = prefs.lastAnalysisMs
        when {
            ms < 0 -> l.add("· " + t(ctx,
                "还没跑过（打开一个聊天窗口等对方发消息，或点悬浮球手动分析）",
                "Never run yet (open a chat and wait for a message, or tap the bubble to analyze manually)"))
            prefs.lastAnalysisOk -> l.add("✓ " + t(ctx, "成功，耗时 ${ms}ms", "OK, took ${ms}ms"))
            else -> {
                val kind = prefs.lastAnalysisErr
                l.add("✗ " + t(ctx, "失败，耗时 ${ms}ms，类别 ",
                    "Failed, took ${ms}ms, kind ") + kind.ifBlank { t(ctx, "未知", "unknown") })
                l.add("  ${ErrCatalog.classify(ctx, hintFor(kind)).message}")
            }
        }

        // 统计开着时才有近期的成功/失败分布；没开就提示可以开。
        if (Metrics.enabled) {
            val ev = Metrics.recent(200)
            val ends = ev.count { it.contains(" analysis_end ") }
            val oks = ev.count { it.contains(" analysis_end ") && it.contains(" ok=true") }
            val ocrFails = ev.count { it.contains(" ocr_end ") && it.contains(" ok=false") }
            l.add("")
            l.add(t(ctx, "【近期事件】共 ${ev.size} 条", "[Recent events] ${ev.size} total"))
            l.add("· " + t(ctx, "分析 $ends 次，成功 $oks 次",
                "Analyzed $ends times, $oks succeeded"))
            l.add("· " + t(ctx, "OCR 失败 $ocrFails 次（截屏被系统拒绝或限频时会走高）",
                "OCR failed $ocrFails times (rises when the system denies or rate-limits captures)"))
            if (ev.isNotEmpty()) l.add("· " + t(ctx, "最近一条：", "Latest: ") + ev.last().take(90))
        } else {
            l.add("")
            l.add(t(ctx, "【统计】未开启 —— 打开后可看到成功率与耗时（只存本机，不上传）",
                "[Stats] Off — turn on to see success rate and timing (stored on-device only, never uploaded)"))
        }

        val crash = CrashLog.text(ctx)
        l.add("")
        l.add(if (crash == null) t(ctx, "【崩溃 / ANR】无记录", "[Crash / ANR] No records")
        else t(ctx, "【崩溃 / ANR】有记录（${crash.lines().size} 行），可在设置里导出或清除",
            "[Crash / ANR] Records found (${crash.lines().size} lines) — export or clear in Settings"))

        val ready = a11y && overlay && notify
        l.add("")
        l.add(
            when {
                !ready -> t(ctx, "结论：上面带 ✗ 的项先补齐，否则助手不会工作。",
                    "Conclusion: fix the ✗ items above first, or the assistant won't work.")
                live.cfOrigin -> t(ctx,
                    "结论：你的权限都齐了，但服务方源站当前不可用（HTTP ${live.code}）。" +
                        "等站方修复后即可自动恢复，这不是你这边的问题。",
                    "Conclusion: all your permissions are granted, but the provider's origin is currently down (HTTP ${live.code}). " +
                        "It will recover once the provider fixes it — not an issue on your side.")
                else -> t(ctx, "结论：必要条件已满足，可以去聊天里试了。",
                    "Conclusion: requirements met — go try it in a chat.")
            })
        return Report(l, ready && live.code == 200)
    }

    /** 一次服务端探活结果。 */
    private data class Live(val code: Int, val host: String, val cfOrigin: Boolean, val note: String = "")

    /**
     * 轻量探活：对当前真实使用的接口发一个 **GET**（不 POST、不带 body、不消耗任何
     * 额度），只看状态码。
     *
     * 为什么用 GET 而不 POST：POST 决策接口会真的调用模型、扣额度，自检不该花钱；
     * GET 拿到的状态码对"源站通不通"这个判断是足够的（Cloudflare 的 52x 会在
     * CONNECT 阶段就返回，跟有没有 body 无关）。
     */
    private fun probeEndpoint(ctx: Context): Live {
        val prefs = Prefs(ctx)
        val url = try { prefs.jevDecisionsEndpoint() } catch (e: Exception) { return Live(0, "接口地址", false) }
        val host = runCatching { java.net.URI(url).host ?: "接口" }.getOrDefault("接口")
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "jev-assistant-android")
                if (prefs.isWorkerMode && prefs.accountToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer " + prefs.accountToken)
                }
            }
            val code = try { conn.responseCode } finally { conn.disconnect() }
            Log.d(TAG, "selfcheck probe host=$host code=$code")
            // 5xx 全当"不可用"：自检要回答的是"现在能不能用"，不是"确切几号"。
            // 4xx（401 无令牌 / 405 探测方法不被允许等）说明**服务器活着且端点存在**，
            // 只是 GET 探测本身被拒——这算"可达"，不该让用户看到红色感叹号。
            Live(if (code in 200..299 || code in 400..499) 200 else code, host, isCfOrigin(code))
        } catch (e: Exception) {
            Live(0, host, false, e.message?.take(40) ?: "连不上")
        }
    }

    private fun isCfOrigin(code: Int) =
        code == 521 || code == 522 || code == 523 || code == 524 || code == 525

    private fun hintFor(kindId: String): String = when (kindId) {
        ErrCatalog.Kind.BUSY.id -> "HTTP 429"
        ErrCatalog.Kind.AUTH.id -> "HTTP 401"
        ErrCatalog.Kind.NETWORK.id -> "网络超时"
        ErrCatalog.Kind.NO_TEXT.id -> "这一屏没认出文字"
        ErrCatalog.Kind.OCR_DENIED.id -> "截屏被拒绝 未开启"
        ErrCatalog.Kind.NO_KEY.id -> "未设置判断接口密钥"
        ErrCatalog.Kind.TIMEOUT.id -> "分析超时"
        else -> ""
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    /** 诊断文案双语取词（用户看得到的地方都跟随中/EN 设置）。 */
    private fun t(ctx: Context, zh: String, en: String): String = ctx.t(zh, en)

    private fun isA11yOn(ctx: Context): Boolean {
        val v = Settings.Secure.getString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return v.split(":").any { it.equals(A11Y_COMPONENT, ignoreCase = true) }
    }

    /** 无障碍服务是否真的开启（同进程内其它组件（如 KeepAliveService）用来做自愈轮询）。 */
    internal fun isA11yOnPublic(ctx: Context): Boolean = isA11yOn(ctx)

    private fun isNotifyOn(ctx: Context): Boolean {
        val v = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        return v.split(":").any { it.contains("com.jev.probe", ignoreCase = true) }
    }

    private fun isBatteryUnrestricted(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        Log.w(TAG, "battery check failed: ${e.message}")
        false
    }
}
