package com.jev.probe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.CrashLog
import com.jev.probe.core.Prefs
import com.jev.probe.core.SelfCheck
import kotlin.math.roundToInt

/**
 * Home / setup screen. Card-based layout with a live readiness summary, a
 * guided permission checklist (each row reflects its real granted state), a
 * prominent on/off switch, and a link to settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private val a11yComponent =
        "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    private val accent = Color.parseColor("#3A7AFE")
    private val green = Color.parseColor("#16A34A")
    private val red = Color.parseColor("#DC2626")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // 从悬浮球「诊断与自检」跳过来时，直接弹出诊断卡（仍会在背后建好主页）。
        if (intent?.getBooleanExtra("diag", false) == true) {
            intent?.removeExtra("diag")
            showDiagnostics()
        }
        build()
    }

    private fun build() {
        container.removeAllViews()

        container.addView(text("Jev 聊天助手", 24f, ink, bold = true))
        container.addView(text("在聊天 App 旁读对方消息（已支持 QQ、X、飞书、微信），给出判断和候选回复。微信开启后可在手机端无人值守收发。",
            13f, sub).apply { setPadding(0, dp(6), 0, dp(16)) })

        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val notify = isNotifListenerEnabled()
        val key = prefs.hasKey()   // judge route key: the one analysis cannot run without
        val ready = a11y && overlay && key

        // 已开启但无障碍被系统（或用户）悄悄关掉：这是「助手明明开了却没反应」的头号原因，
        // 给一条醒目的恢复横幅，点一下直接回无障碍设置页（关掉时下面的引导清单也会列出它，
        // 这条横幅只是让恢复更显眼）。
        if (prefs.enabled && !a11y) {
            container.addView(recoveryBanner())
        }

        // Readiness card
        container.addView(statusCard(ready, a11y, overlay, key))
        container.addView(privacyHint())

        // 引导式首启（P1-1）：一次只推一步，跳系统页回来后 onResume 重建，
        // 自动落到的下一个未完成项。用户不需要自己记「还差哪个」。
        val battery = isBatteryUnrestricted()
        val pending = ArrayList<String>().apply {
            if (!a11y) add(A11Y)
            if (!overlay) add(OVERLAY)
            if (!notify) add(NOTIFY)
            if (!battery) add(BATTERY)
        }
        val next = pending.firstOrNull()

        container.addView(sectionLabel(
            if (next == null) "权限设置（已全部开启）" else "还差 ${pending.size} 步 · 先开「${stepTitle(next)}」"))
        container.addView(permCard(A11Y, "读取当前聊天窗口的消息文字", a11y, next == A11Y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        container.addView(permCard(OVERLAY, "在聊天窗口上方显示分析卡片", overlay, next == OVERLAY) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        container.addView(permCard(NOTIFY, "监听微信新消息，触发自动收发", notify, next == NOTIFY) {
            startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        container.addView(permCard(BATTERY, "小米 / HyperOS 必做，否则后台被冻结、读不到消息", battery, next == BATTERY) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        // Actions
        container.addView(sectionLabel("其他"))
        container.addView(actionRow("设置", "自动收发 · 关系描述 · 订阅") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        container.addView(actionRow("自检与诊断", "一眼看清哪项没开、上次分析成没成、有没有崩过") {
            showDiagnostics()
        })

        // Master toggle — only meaningful once the prerequisites are in place,
        // otherwise flipping it just produces a silent no-op.
        if (next == null) {
            val toggle = bigToggle(prefs.enabled)
            toggle.setOnClickListener {
                prefs.enabled = !prefs.enabled
                build()
            }
            container.addView(toggle)
        } else {
            container.addView(text("把上面 ${pending.size} 项开完就能用了", 13f, sub).apply {
                setPadding(0, dp(18), 0, 0)
            })
        }
    }

    private fun stepTitle(id: String?) = when (id) {
        A11Y -> "无障碍权限"
        OVERLAY -> "悬浮窗权限"
        NOTIFY -> "通知读取"
        else -> "自启动 + 省电无限制"
    }

    /** 已开启但无障碍被系统关掉时的恢复横幅：点一下直接回无障碍设置页。 */
    private fun recoveryBanner(): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(dp(14), Color.parseColor("#FEF2F2"), stroke = true).apply {
                setStroke(dp(1), red)
            }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        c.addView(text("⚠ 助手已开启，但无障碍服务被系统关闭了", 14f, red, bold = true))
        c.addView(text("读不到聊天内容，助手不会工作。点此重新打开无障碍服务。", 12f, sub).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        val go = TextView(this).apply {
            text = "重新打开无障碍"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = roundBg(dp(10), red)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        go.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        c.addView(go)
        return c
    }

    /** 自检结果（P0-4）：纯文本，可一键复制发作者，不上传。 */
    private fun showDiagnostics() {
        // 探活要走网络（最长 12s 超时），绝不能在主线程做——这里只放一个"检查中"的
        // 提示框，拿到结果再弹真正的报告。系统不会在这期间卡住。
        val wait = android.app.ProgressDialog(this).apply {
            setMessage("正在检查服务接口…")
            setCancelable(false)
        }
        wait.show()
        Thread {
            val report = runCatching { SelfCheck.run(this) }
                .getOrElse {
                    SelfCheck.Report(listOf("自检执行失败：${it.message ?: it.javaClass.simpleName}"), false)
                }
            runOnUiThread {
                runCatching { wait.dismiss() }
                showDiagReport(report)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showDiagReport(report: SelfCheck.Report) {
        val tv = android.widget.TextView(this).apply {
            text = report.text
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle("自检与诊断")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_diag", report.text))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清除崩溃记录") { _, _ ->
                CrashLog.clear(this)
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun isBatteryUnrestricted(): Boolean = try {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- cards

    private fun statusCard(ready: Boolean, a11y: Boolean, overlay: Boolean, key: Boolean): View {
        val c = cardBox()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(dot(if (ready) green else red).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
        })
        head.addView(text(if (ready) "已就绪，可以用了" else "尚未就绪", 16f, if (ready) green else ink, bold = true))
        c.addView(head)
        c.addView(checkLine("无障碍", a11y))
        c.addView(checkLine("悬浮窗", overlay))
        c.addView(checkLine("密钥", key, okWord = "已设", noWord = "未设"))
        return c
    }

    /** One tappable line under the readiness card, opening the privacy policy page. */
    private fun privacyHint(): View = text("读取的聊天内容只发往你自己配置的接口 · 隐私政策", 11f, sub).apply {
        setPadding(dp(2), dp(8), 0, 0)
        setOnClickListener { openUrl(PRIVACY_URL) }
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLine(label: String, ok: Boolean, okWord: String = "已开", noWord: String = "未开"): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        row.addView(text(if (ok) "✓" else "✗", 14f, if (ok) green else red, bold = true).apply {
            (this as TextView).width = dp(22)
        })
        row.addView(text(label + (if (ok) okWord else noWord), 13f, sub))
        return row
    }

    /** One tappable permission row. [next] marks the step the guide is pointing at. */
    private fun permCard(title: String, desc: String, granted: Boolean, next: Boolean, onClick: () -> Unit): View {
        val c = cardBox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        when {
            granted -> left.addView(text("✓ 已开启", 12f, green, bold = true).apply { setPadding(0, dp(4), 0, 0) })
            next -> left.addView(text("下一步 →", 12f, accent, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        }
        row.addView(left)
        row.addView(btn(if (granted) "已开启" else "去开启", !granted, onClick))
        c.addView(row)
        return c
    }

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val c = cardBox()
        c.setOnClickListener { onClick() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(text("›", 22f, sub))
        c.addView(row)
        return c
    }

    private fun bigToggle(on: Boolean): View {
        return TextView(this).apply {
            text = if (on) "助手已开启 · 点击关闭" else "助手已关闭 · 点击开启"
            textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (on) Color.WHITE else accent)
            background = roundBg(dp(14), if (on) accent else Color.WHITE, stroke = !on)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
    }

    // ---------------------------------------------------------------- atoms

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(dp(14), Color.WHITE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun sectionLabel(t: String) = text(t, 12f, sub, bold = true).apply {
        setPadding(dp(2), dp(18), 0, dp(2))
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun btn(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (enabled) Color.WHITE else sub)
        background = roundBg(dp(10), if (enabled) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (enabled) setOnClickListener { onClick() }
    }

    private fun roundBg(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(a11yComponent)
    }

    private fun isNotifListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            "enabled_notification_listeners") ?: return false
        return enabled.split(":").any {
            it.endsWith("WxNotificationListener", ignoreCase = true)
        }
    }

    companion object {
        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"

        // 引导式首启的步骤 id（P1-1）
        private const val A11Y = "a11y"
        private const val OVERLAY = "overlay"
        private const val NOTIFY = "notify"
        private const val BATTERY = "battery"
    }
}
