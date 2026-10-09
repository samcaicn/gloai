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
import com.jev.probe.core.t
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

        container.addView(text(t("Jev 聊天助手", "Jev Chat Assistant"), 24f, ink, bold = true))
        container.addView(text(t(
            "在聊天 App 旁读对方消息（已支持 QQ、X、飞书、微信），给出判断和候选回复。微信开启后可在手机端无人值守收发。",
            "Reads messages next to your chat apps (QQ, X, Feishu, WeChat) and suggests judgments plus candidate replies. With WeChat it can run fully hands-free."), 13f, sub).apply { setPadding(0, dp(6), 0, dp(16)) })

        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val notify = isNotifListenerEnabled()
        val battery = isBatteryUnrestricted()
        val key = prefs.hasKey()   // judge route key: the one analysis cannot run without
        // 引导式首启：一次只推一步，跳系统页回来后 onResume 重建，自动落到下一个未完成项。
        val pending = ArrayList<String>().apply {
            if (!a11y) add(A11Y)
            if (!overlay) add(OVERLAY)
            if (!notify) add(NOTIFY)
            if (!battery) add(BATTERY)
        }
        // 就绪口径必须和下面的清单用**同一组**判断。原来这里只查 3 项（无障碍 /
        // 悬浮窗 / 密钥）而清单查 4 项（含通知、省电），于是卡片写着「已就绪，可以用了」
        // 紧接着下面又冒出一句「还差 2 步」——用户完全不知道该信哪个。
        // 密钥单独算：它是「能不能跑」的另一条轴（没密钥能装能开，就是不出结果）。
        val permsOk = pending.isEmpty()
        val ready = permsOk && key
        val next = pending.firstOrNull()

        // 已开启但无障碍被系统（或用户）悄悄关掉：这是「助手明明开了却没反应」的头号原因，
        // 给一条醒目的恢复横幅，点一下直接回无障碍设置页（关掉时下面的引导清单也会列出它，
        // 这条横幅只是让恢复更显眼）。
        if (prefs.enabled && !a11y) {
            container.addView(recoveryBanner())
        }

        // Readiness card
        container.addView(statusCard(ready, permsOk, a11y, overlay, notify, battery, key))
        container.addView(privacyHint())

        container.addView(sectionLabel(
            when {
                pending.isEmpty() -> t("权限设置（已全部开启）", "Permissions (all granted)")
                else -> t(
                    "还差 ${pending.size} / ${TOTAL_STEPS} 步 · 先开「${stepTitle(next)}」",
                    "${pending.size} of ${TOTAL_STEPS} steps left · Start with \"${stepTitle(next)}\"")
            }))
        // 进度条：把「4 步」画成一条，用户一眼看到自己走到哪，而不是去数下面几张卡。
        if (pending.isNotEmpty()) container.addView(progressBar(TOTAL_STEPS - pending.size, TOTAL_STEPS))

        // 卡标题必须走 stepTitle()（本地化文案）。曾经把步骤 ID 常量（"a11y" 等）
        // 直接当标题传进来，用户在「还差 N / 4 步」页看到四张卡顶着英文单词。
        container.addView(permCard(stepTitle(A11Y), t("读取当前聊天窗口的消息文字", "Read the text of the current chat window"), a11y, next == A11Y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        container.addView(permCard(stepTitle(OVERLAY), t("在聊天窗口上方显示分析卡片", "Show the analysis card above chat windows"), overlay, next == OVERLAY) {
            startActivity(Settings.ACTION_MANAGE_OVERLAY_PERMISSION.let { Intent(it, Uri.parse("package:$packageName")) })
        })
        container.addView(permCard(stepTitle(NOTIFY), t("监听微信新消息，触发自动收发", "Listen for new WeChat messages to trigger auto send/receive"), notify, next == NOTIFY) {
            startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        container.addView(permCard(stepTitle(BATTERY), t("小米 / HyperOS 必做，否则后台被冻结、读不到消息", "Required on Xiaomi / HyperOS, otherwise the background process is frozen and messages can't be read"), battery, next == BATTERY) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        // 密钥缺失单独提示，且给一个直达设置页的按钮——它不在上面 4 步里，
        // 混在权限清单中会让人以为「开完 4 项就一定出结果」，然后卡在无反应上。
        if (!key) {
            container.addView(keyMissingCard())
        }

        // Actions
        container.addView(sectionLabel(t("其他", "More")))
        container.addView(actionRow(t("设置", "Settings"), t("自动收发 · 关系描述 · 订阅", "Auto send/receive · Relationship · Subscription")) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        container.addView(actionRow(t("自检与诊断", "Diagnostics"), t("一眼看清哪项没开、上次分析成没成、有没有崩过", "See at a glance what's off, whether the last analysis succeeded, and any past crashes")) {
            showDiagnostics()
        })

        // 总开关**始终**可见。原来只在 4 项全开后才显示，于是「权限还没开完」的用户
        // 想先把助手关掉都找不到开关——而 prefs.enabled 默认就是 true，助手处于
        // 开启状态。开关不可见 + 状态已开 = 用户在不知情下被采集。
        val toggle = bigToggle(prefs.enabled)
        toggle.setOnClickListener {
            if (prefs.enabled && !permsOk) {
                // 关掉不需要任何前置条件，这正是要立刻能关的原因。
                prefs.enabled = false
                build()
            } else {
                prefs.enabled = !prefs.enabled
                build()
            }
        }
        container.addView(toggle)
        if (!permsOk) {
            container.addView(text(
                if (prefs.enabled) t("上面 ${pending.size} 项开完就能用了；不想现在开，可以先点上面关掉助手",
                    "Finish the ${pending.size} item(s) above and it's ready; to hold off, switch the assistant off above")
                else t("助手已关闭。权限开完后再打开上面的总开关",
                    "Assistant is off. Turn the main switch back on once permissions are granted"),
                12.5f, sub).apply { setPadding(dp(2), dp(10), 0, 0) })
        }
    }

    /**
     * 4 步权限的进度条。纯装饰但有效：把「还差几步」从需要心算的句子变成一眼可见的量。
     */
    private fun progressBar(done: Int, total: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        for (i in 0 until total) {
            row.addView(View(this).apply {
                background = roundBg(dp(2), if (i < done) accent else Color.parseColor("#E5E7EB"))
                layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                    if (i > 0) leftMargin = dp(4)
                }
            })
        }
        return row
    }

    /**
     * 「没配接口」提示卡。worker 模式下 [Prefs.hasKey] 看的是账户令牌，所以对绝大多数
     * 用户来说这一项靠订阅激活自动满足——这张卡只在真的没激活时出现，文案要说清
     * 「不激活也能用，只是走共享额度会排队」，免得用户以为不付钱就完全不能用。
     */
    private fun keyMissingCard(): View {
        val c = cardBox()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(t("判断接口未激活", "Service not activated"), 15f, ink, bold = true))
        left.addView(text(
            t("现在还能用，走共享额度、高峰期可能提示繁忙。激活后有专属额度、不用排队。",
                "Still usable on the shared quota; it may report busy at peak times. Activate for dedicated quota with no queueing."),
            12f, sub).apply { setPadding(0, dp(3), 0, dp(4)) })
        val go = TextView(this).apply {
            text = t("去看看", "Activate"); textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = roundBg(dp(10), accent)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        go.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("focus", "plan"))
        }
        row.addView(left)
        row.addView(go)
        c.addView(row)
        return c
    }

    private fun stepTitle(id: String?) = when (id) {
        A11Y -> t("无障碍权限", "Accessibility")
        OVERLAY -> t("悬浮窗权限", "Display over other apps")
        NOTIFY -> t("通知读取", "Notification access")
        else -> t("自启动 + 省电无限制", "Auto-start & unrestricted battery")
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
        c.addView(text(t("⚠ 助手已开启，但无障碍服务被系统关闭了",
            "⚠ Assistant is on, but the accessibility service was turned off by the system"), 14f, red, bold = true))
        c.addView(text(t("读不到聊天内容，助手不会工作。点此重新打开无障碍服务。",
            "It can't read chat content and won't work. Tap to reopen the accessibility service."), 12f, sub).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        val go = TextView(this).apply {
            text = t("重新打开无障碍", "Reopen accessibility"); textSize = 13f; gravity = Gravity.CENTER
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
        //
        // 原来用的是 android.app.ProgressDialog：已废弃，而且在 HyperOS 这类深度
        // 定制系统上样式常常跑偏。换成自建的 Dialog——顺带把「可以取消」做出来：
        // 12 秒干等一个自己没触发的检查，用户第一反应是想退出去干别的。
        val wait = android.app.Dialog(this).apply {
            setCancelable(true)
            setContentView(TextView(this@MainActivity).apply {
                text = t("正在检查服务接口…", "Checking the service endpoint…")
                textSize = 14f
                setTextColor(ink)
                setPadding(dp(24), dp(26), dp(24), dp(26))
            })
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        wait.show()
        Thread {
            val report = runCatching { SelfCheck.run(this) }
                .getOrElse {
                    SelfCheck.Report(listOf(t("自检执行失败：", "Self-check failed: ") + (it.message ?: it.javaClass.simpleName)), false)
                }
            runOnUiThread {
                // 用户可能已经取消了这个等待框：别再在他背后弹报告。
                if (wait.isShowing) {
                    runCatching { wait.dismiss() }
                    showDiagReport(report)
                }
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
            .setTitle(t("自检与诊断", "Diagnostics"))
            .setView(scroll)
            .setPositiveButton(t("复制", "Copy")) { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_diag", report.text))
                Toast.makeText(this, t("已复制", "Copied"), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(t("清除崩溃记录", "Clear crash log")) { _, _ ->
                CrashLog.clear(this)
                Toast.makeText(this, t("已清除", "Cleared"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(t("关闭", "Close"), null)
            .show()
    }

    private fun isBatteryUnrestricted(): Boolean = try {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- cards

    /**
     * 就绪卡。4 项权限 + 密钥，共 5 行——与下面的清单一一对应。
     *
     * 措辞按「缺什么就说什么」：权限全开但没激活接口时，标题给「可以用了，接口未激活」
     * 而不是笼统的「尚未就绪」——因为这两种状态下助手**都能读能显示**，区别只在
     * 判断请求走共享额度还是专属额度。混成一句「未就绪」会让用户以为坏了。
     */
    private fun statusCard(
        ready: Boolean, permsOk: Boolean,
        a11y: Boolean, overlay: Boolean, notify: Boolean, battery: Boolean,
        key: Boolean
    ): View {
        val c = cardBox()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val (title, color) = when {
            ready -> t("已就绪，可以用了", "Ready to go") to green
            permsOk -> t("可以用了，接口未激活", "Ready — service not activated") to Color.parseColor("#D97706")
            else -> t("尚未就绪，还差 ${4 - listOf(a11y, overlay, notify, battery).count { it }} 项",
                "Not ready — ${4 - listOf(a11y, overlay, notify, battery).count { it }} item(s) left") to ink
        }
        head.addView(dot(if (ready) green else if (permsOk) Color.parseColor("#D97706") else red).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
        })
        head.addView(text(title, 16f, color, bold = true))
        c.addView(head)
        c.addView(checkLine(t("无障碍", "Accessibility"), a11y))
        c.addView(checkLine(t("悬浮窗", "Overlay"), overlay))
        c.addView(checkLine(t("通知读取", "Notifications"), notify))
        c.addView(checkLine(t("省电无限制", "Battery"), battery, okWord = t("已放开", "allowed"), noWord = t("未放开", "restricted")))
        c.addView(checkLine(t("判断接口", "Service"), key, okWord = t("已激活", "active"), noWord = t("未激活", "inactive")))
        return c
    }

    /** One tappable line under the readiness card, opening the privacy policy page. */
    private fun privacyHint(): View = text(t("读取的聊天内容只发往你自己配置的接口 · 隐私政策",
        "Chat content is only sent to the endpoint you configured · Privacy policy"), 11f, sub).apply {
        setPadding(dp(2), dp(8), 0, 0)
        setOnClickListener { openUrl(PRIVACY_URL) }
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, t("打不开浏览器", "Can't open the browser"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLine(label: String, ok: Boolean, okWord: String = t("已开", "on"), noWord: String = t("未开", "off")): View {
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
            granted -> left.addView(text(t("✓ 已开启", "✓ on"), 12f, green, bold = true).apply { setPadding(0, dp(4), 0, 0) })
            next -> left.addView(text(t("下一步 →", "Next →"), 12f, accent, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        }
        row.addView(left)
        row.addView(btn(if (granted) t("已开启", "On") else t("去开启", "Enable"), !granted, onClick))
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
            text = if (on) t("助手已开启 · 点击关闭", "Assistant is on · Tap to turn off")
                   else t("助手已关闭 · 点击开启", "Assistant is off · Tap to turn on")
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

        /** 引导共 4 步，进度条与「还差 N 步」都以此为分母。 */
        private const val TOTAL_STEPS = 4
    }
}
