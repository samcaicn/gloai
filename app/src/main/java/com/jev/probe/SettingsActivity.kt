package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.LicenseClient
import com.jev.probe.core.Prefs
import kotlin.math.roundToInt
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened workerMode=${prefs.isWorkerMode}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 视觉接口（可选自定义） ===================
        root.addView(section("视觉接口"))
        val visionCard = card()
        visionCard.addView(cardTitle("视觉模型接口"))
        visionCard.addView(text(
            "默认使用内置视觉模型接口。开启自定义后，视觉请求改发你填的 " +
                "OpenAI 兼容接口（需支持 image_url），判断与回复不受影响。",
            12f, sub))
        val visionCustomRow = toggleRow("自定义视觉接口（不走云端）", prefs.visionCustom)
        visionCard.addView(visionCustomRow)
        visionCard.addView(label("Base URL（OpenAI 兼容，如 https://api.xxx.com/v1）"))
        val visionBaseEdit = edit(prefs.visionBaseUrl, "留空则始终走云端")
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("API Key（自定义接口的密钥，留空则用内置）"))
        val visionKeyEdit = edit(prefs.visionKey, "sk-…", password = true)
        visionCard.addView(visionKeyEdit)
        visionCard.addView(label("模型名（如 qwen-vl-max / gpt-4o-mini）"))
        val visionModelEdit = edit(prefs.visionModelStored(), Prefs.DEFAULT_VISION_MODEL)
        visionCard.addView(visionModelEdit)
        root.addView(visionCard)

        // =================== 接口（当前） ===================
        root.addView(section("接口（当前）"))
        val ifaceCard = card()
        ifaceCard.addView(cardTitle("LLM 后端"))
        val providerLabel = when (prefs.judgeProvider) {
            Prefs.PROVIDER_TUPTUP -> "tuptup.top（OpenAI 兼容，内置密钥）"
            Prefs.PROVIDER_WORKER -> "WeAuto 云端（账户令牌）"
            else -> "自定义 / ${prefs.judgeProvider}"
        }
        ifaceCard.addView(text("判断 / 回复 / 视觉统一走：$providerLabel", 12f, sub))
        ifaceCard.addView(text(
            "本机读取（无障碍 + ML Kit 离线 OCR）优先；仅在需要 AI 判断/生成时才调用上述接口，" +
                "对方发图片也只在本机 OCR 读字、不上传。",
            11f, sub))
        root.addView(ifaceCard)

        // =================== 订阅与激活（Creem 支付，套餐与桌面端一致） ===================
        root.addView(section("订阅与激活"))
        val licCard = card()
        licCard.addView(cardTitle("WeAuto 云端 · 支付宝/银行卡"))
        licCard.addView(text(
            "购买后自动激活云端判断（不再依赖内置网关额度）。支付宝仅一次性买断可用" +
                "（平台限制），月租档支持卡/Apple Pay。", 12f, sub))
        val licStatus = text(licenseStatusText(), 12.5f, ink, bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
        }
        licCard.addView(licStatus)
        for (tier in LicenseClient.TIERS) {
            val label = when (tier) {
                LicenseClient.TIER_NORMAL -> "标准版 · 月租"
                LicenseClient.TIER_PREMIUM -> "高级版 · 月租"
                else -> "终身版 · 一次性（支持支付宝）"
            }
            licCard.addView(cardBtn(label) { startCheckout(tier, licStatus) })
        }
        licCard.addView(text(
            "付款完成返回本页即自动激活（每 3 秒自动确认，最长等 30 分钟）。", 11f, sub))
        root.addView(licCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        val ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书正文是画上去的，节点树里读不到，这时截一次屏本地识别（不上传）。", 11f, sub))
        val ocrPrimaryRow = toggleRow("微信内优先本地 OCR 逐气泡识别", prefs.ocrPrimary)
        card2.addView(ocrPrimaryRow)
        card2.addView(text("开则微信每个气泡都用本机 PaddleOCR 认字（不上传），收发方仍由树判定；关则退回纯树读。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("打开对话即自动分析并显示「分析中…」；关闭则只亮悬浮球不出结果。", 11f, sub))
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库与历史") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空知识库与历史"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        card2.addView(text("自检", 12f, sub).apply {
            setPadding(dp(2), dp(12), dp(8), dp(2))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { kbResult.text = out }
                }
            }
        })
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 无人值守（微信 · 脱离电脑） ===================
        root.addView(section("无人值守（微信 · 脱离电脑）"))
        root.addView(permCard("通知读取权限（微信）", "监听微信新消息，触发自动分析/回复", isNotifListenerEnabled()) {
            startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        val unattendedCard = card()
        unattendedCard.addView(cardTitle("微信自动收发"))
        unattendedCard.addView(text(
            "无障碍读取 + 通知监听驱动：收到消息自动分析并填入回复；开启自动发送后，由你从候选里选定的那一条会自动发出。" +
            "对方发图片时用 OCR 读图里文字（不上传）。",
            12f, sub))
        val autoSendRow = toggleRow("填入后自动发送回复", prefs.autoSend)
        unattendedCard.addView(autoSendRow)
        val ocrImgRow = toggleRow("对方发图片时按图 OCR 读字", prefs.ocrImages)
        unattendedCard.addView(ocrImgRow)
        val autoOpenRow = toggleRow("收到消息自动打开会话（真·无人值守）", prefs.autoOpenChat)
        unattendedCard.addView(autoOpenRow)
        unattendedCard.addView(text("自动打开会话会直接点开微信聊天界面，仅无人值守时用；默认开（自动监听）。", 11f, sub))
        val autoFillRow = toggleRow("自动填入最佳候选（配合上两项＝全自动）", prefs.autoFillBest)
        unattendedCard.addView(autoFillRow)
        unattendedCard.addView(text(
            "worker/jev 返回候选后直接填入打分最高的一条，延迟下述时间后自动发出。" +
                "只在微信生效，只填候选原文；输入框已有你手打的字时不会覆盖。",
            11f, sub))
        val delayLabel = label("发送前延迟（秒）")
        unattendedCard.addView(delayLabel)
        val delayEdit = edit(
            if (prefs.sendDelayMs % 1000 == 0) (prefs.sendDelayMs / 1000).toString()
            else String.format("%.1f", prefs.sendDelayMs / 1000f),
            "2"
        )
        delayEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        unattendedCard.addView(delayEdit)
        unattendedCard.addView(text(
            "填入后等这么久再点发送。微信需要一点时间把草稿落进编辑框、把发送按钮切回可点状态，" +
                "太短会点空导致消息没发出去。0 = 立刻发。",
            11f, sub))
        root.addView(unattendedCard)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 关于 ===================
        root.addView(section("关于"))
        val aboutCard = card()
        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(10), 0, dp(2)) })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存设置") {
            prefs.relationship = relEdit.text.toString()
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrPrimary = (ocrPrimaryRow.tag as? Boolean) ?: true
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.autoSend = (autoSendRow.tag as? Boolean) ?: true
            prefs.ocrImages = (ocrImgRow.tag as? Boolean) ?: true
            prefs.autoOpenChat = (autoOpenRow.tag as? Boolean) ?: false
            prefs.autoFillBest = (autoFillRow.tag as? Boolean) ?: true
            delayEdit.text?.toString()?.trim()?.toFloatOrNull()?.let { secs ->
                prefs.sendDelayMs = (secs * 1000f).toInt()
            }
            prefs.visionCustom = (visionCustomRow.tag as? Boolean) ?: false
            prefs.visionBaseUrl = visionBaseEdit.text.toString()
            prefs.visionKey = visionKeyEdit.text.toString()
            prefs.visionModel = visionModelEdit.text.toString()
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    // ---------------------------------------------------------------- atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    /** A permission card: title + description + a "去开启" button. Mirrors the
     *  same-named helper in MainActivity (kept separate because the two activities
     *  use different private UI atoms). */
    private fun permCard(title: String, desc: String, granted: Boolean?, onClick: () -> Unit): View {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        if (granted == true) left.addView(text("✓ 已开启", 12f, accent, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left)
        row.addView(cardBtn(if (granted == true) "已开启" else "去开启", onClick))
        c.addView(row)
        return c
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    // ------------------------------------------------------------ Creem 支付

    private fun licenseStatusText(): String = when {
        prefs.licenseKey.isNotBlank() ->
            "✓ 已激活 · ${tierLabel(prefs.licenseTier)} · 判断已走云端"
        prefs.accountToken.isNotBlank() -> "✓ 已有账户令牌 · 判断已走云端"
        else -> "未激活 — 当前判断走内置网关（额度有限，繁忙时提示 429）"
    }

    private fun tierLabel(tier: String) = when (tier) {
        LicenseClient.TIER_NORMAL -> "标准版月租"
        LicenseClient.TIER_PREMIUM -> "高级版月租"
        LicenseClient.TIER_LIFETIME -> "终身版"
        else -> if (tier.isBlank()) "未知档位" else tier
    }

    /**
     * 打开收银台并开始自动确认：Worker /buy 动态建 checkout（302 到 creem.io，
     * metadata 带本机 mid）→ 浏览器付款 → webhook 写 lic:<mid> → 本端每 3s 轮询
     * /license?mid= → 拿到卡密即写 accountToken + 切 worker 模式。
     */
    private fun startCheckout(tier: String, statusView: TextView) {
        try {
            val url = LicenseClient.buyUrl(prefs, tier)
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "已在浏览器打开收银台，付款后回到本页自动激活", Toast.LENGTH_LONG).show()
        statusView.text = "等待付款确认…（${tierLabel(tier)}）"
        Thread {
            val deadline = System.currentTimeMillis() + 30 * 60_000L
            var failures = 0
            while (System.currentTimeMillis() < deadline && !isFinishing && !isDestroyed) {
                try {
                    val key = LicenseClient.poll(prefs)
                    if (!key.isNullOrBlank()) {
                        LicenseClient.activate(prefs, key, tier)
                        Log.i(TAG, "license activated tier=$tier")
                        main.post {
                            if (!isFinishing && !isDestroyed) {
                                statusView.text = licenseStatusText()
                                Toast.makeText(this, "✓ 激活成功，判断接口已切换到云端", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@Thread
                    }
                    failures = 0
                } catch (e: Exception) {
                    // 单次网络抖动不放弃，连续 40 次失败（约 2 分钟）视为断网，继续等
                    failures++
                    Log.w(TAG, "license poll failed x$failures: ${e.message}")
                }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
            }
        }.start()
    }

    /** Whether our WeChat notification listener is enabled in system settings. */
    private fun isNotifListenerEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any {
            it.equals("$packageName/com.jev.probe.capture.WxNotificationListener", ignoreCase = true) ||
                it.endsWith("WxNotificationListener", ignoreCase = true)
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"
    }
}
