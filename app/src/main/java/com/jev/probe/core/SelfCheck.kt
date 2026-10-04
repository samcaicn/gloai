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

        l.add("【设备】${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        l.add("")
        l.add("【必要条件】")
        l.add("${mark(a11y)} 无障碍服务${if (a11y) "已开启" else "未开启 —— 读不到聊天内容，助手不会工作"}")
        l.add("${mark(overlay)} 悬浮窗${if (overlay) "已允许" else "未允许 —— 分析结果没有地方显示"}")
        l.add("${mark(notify)} 通知监听${if (notify) "已启用" else "未启用 —— 微信来新消息不会自动触发"}")
        l.add("${mark(battery)} 省电无限制${if (battery) "已放开" else "未放开 —— 国产 ROM 会冻结后台，气泡会消失"}")

        l.add("")
        l.add("【上一次分析】")
        val ms = prefs.lastAnalysisMs
        when {
            ms < 0 -> l.add("· 还没跑过（打开一个聊天窗口等对方发消息，或点悬浮球手动分析）")
            prefs.lastAnalysisOk -> l.add("✓ 成功，耗时 ${ms}ms")
            else -> {
                val kind = prefs.lastAnalysisErr
                l.add("✗ 失败，耗时 ${ms}ms，类别 ${kind.ifBlank { "未知" }}")
                l.add("  ${ErrCatalog.classify(hintFor(kind)).message}")
            }
        }

        // 统计开着时才有近期的成功/失败分布；没开就提示可以开。
        if (Metrics.enabled) {
            val ev = Metrics.recent(200)
            val ends = ev.count { it.contains(" analysis_end ") }
            val oks = ev.count { it.contains(" analysis_end ") && it.contains(" ok=true") }
            val ocrFails = ev.count { it.contains(" ocr_end ") && it.contains(" ok=false") }
            l.add("")
            l.add("【近期事件】共 ${ev.size} 条")
            l.add("· 分析 $ends 次，成功 $oks 次")
            l.add("· OCR 失败 $ocrFails 次（截屏被系统拒绝或限频时会走高）")
            if (ev.isNotEmpty()) l.add("· 最近一条：${ev.last().take(90)}")
        } else {
            l.add("")
            l.add("【统计】未开启 —— 打开后可看到成功率与耗时（只存本机，不上传）")
        }

        val crash = CrashLog.text(ctx)
        l.add("")
        l.add(if (crash == null) "【崩溃 / ANR】无记录"
        else "【崩溃 / ANR】有记录（${crash.lines().size} 行），可在设置里导出或清除")

        val ready = a11y && overlay && notify
        l.add("")
        l.add(if (ready) "结论：必要条件已满足，可以去聊天里试了。"
        else "结论：上面带 ✗ 的项先补齐，否则助手不会工作。")
        return Report(l, ready)
    }

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

    private fun isA11yOn(ctx: Context): Boolean {
        val v = Settings.Secure.getString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return v.split(":").any { it.equals(A11Y_COMPONENT, ignoreCase = true) }
    }

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
