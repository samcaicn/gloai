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
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.Route
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
        Log.i(TAG, "settings opened workerMode=${prefs.isWorkerMode} accountToken.len=${prefs.accountToken.length}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 账户（WeAuto 云端，统一管理） ===================
        root.addView(section("账户 · WeAuto 云端"))

        val accountCard = card()
        accountCard.addView(cardTitle("WeAuto 云端账户"))
        accountCard.addView(text(
            "判断 / 回复 / 视觉三个模型接口统一由 WeAuto 云端（weauto.safeopc.cn）提供，无需配置地址与密钥。" +
                "填入账户令牌即可使用，令牌在本地加密保存。用量按 token 实时计费，详见档位套餐。",
            12f, sub))

        // 当前状态
        val statusText = resultText()
        fun refreshStatus() {
            if (prefs.accountToken.isBlank()) {
                statusText.text = "未激活：请粘贴账户令牌后点「激活 / 校验」"
            } else {
                val name = tierName(prefs.billingTier)
                val quota = prefs.billingQuota
                val used = prefs.billingUsed
                statusText.text = if (quota > 0)
                    "已激活 · $name · 本月已用 ${fmt(used)} / ${fmt(quota)} · 剩余 ${fmt((quota - used).coerceAtLeast(0))}"
                else "已激活 · $name（额度信息待下次请求刷新）"
            }
        }
        refreshStatus()
        accountCard.addView(statusText)

        val tokenEdit = edit("", "粘贴账户令牌（形如 xxxx.yyyy）", password = true)
        accountCard.addView(label("账户令牌"))
        accountCard.addView(tokenEdit)
        accountCard.addView(text("令牌只保存在本机（加密），不会上传、不会显示在界面明文。", 11f, sub))

        accountCard.addView(cardBtn("激活 / 校验") {
            val tok = tokenEdit.text.toString().trim()
            if (tok.isBlank()) { statusText.text = "请先粘贴账户令牌"; return@cardBtn }
            statusText.text = "校验中…"
            worker.execute {
                val r = checkToken(tok)
                main.post {
                    if (r.ok) {
                        prefs.accountToken = tok
                        prefs.billingTier = r.tier
                        prefs.billingUsed = r.used
                        prefs.billingQuota = r.quota
                        statusText.text = "已激活 · ${tierName(r.tier)} · 已用 ${fmt(r.used)} / ${fmt(r.quota)} · 剩余 ${fmt(r.remain)}"
                        Toast.makeText(this@SettingsActivity, "激活成功", Toast.LENGTH_SHORT).show()
                    } else {
                        statusText.text = "校验失败：${r.err}"
                    }
                }
            }
        })

        val testResult = resultText()

        accountCard.addView(cardBtn("测试判断（连通性）") {
            if (prefs.accountToken.isBlank()) { testResult.text = "请先激活账户"; return@cardBtn }
            testResult.text = "测试中…"
            worker.execute {
                val demo = ChatSnapshot("连通测试", listOf(Msg("other", "在吗？"), Msg("me", "在")))
                val a = JudgeClient(prefs).judge(demo, prefs.relationship)
                main.post {
                    testResult.text = if (a.error != null) "失败（${a.latencyMs}ms）：${a.error}"
                    else "成功 ${a.latencyMs}ms · 意图=${a.trueIntent?.choice ?: "?"}（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        accountCard.addView(testResult)

        accountCard.addView(cardBtn("退出登录") {
            prefs.accountToken = ""
            prefs.billingTier = ""
            prefs.billingUsed = 0
            prefs.billingQuota = 0
            tokenEdit.setText("")
            refreshStatus()
            statusText.text = "已退出，请粘贴新的账户令牌"
        })
        root.addView(accountCard)

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
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))
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
                    "账户令牌、白名单等设置不受影响。不可恢复。")
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

        // =================== 关于与隐私 ===================
        root.addView(section("关于与隐私"))
        val aboutCard = card()
        aboutCard.addView(text(
            "本应用的模型接口由 WeAuto 云端（weauto.safeopc.cn）统一提供并按 token 计费。" +
                "聊天内容仅在本地做判断与起草，密钥与账户令牌仅保存在本机（加密）。",
            12f, sub))
        aboutCard.addView(cardBtn("隐私政策") { openUrl(PRIVACY_URL) })
        aboutCard.addView(cardBtn("开源仓库") { openUrl(REPO_URL) })
        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(10), 0, dp(2)) })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存设置") {
            prefs.relationship = relEdit.text.toString()
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    /** 校验账户令牌：调 /token/status，成功返回档位与额度。 */
    private fun checkToken(tok: String): TokenResult {
        return try {
            val u = Prefs.WORKER_BASE.trimEnd('/') + "/token/status?token=" +
                URLEncoder.encode(tok, "UTF-8")
            val j = com.jev.probe.jev.HttpJson.getJson(u, tok, Route.ACCOUNT)
            if (j.optBoolean("ok")) {
                TokenResult(
                    true,
                    j.optString("tier", ""),
                    j.optLong("used", 0),
                    j.optLong("quota", 0),
                    j.optLong("remain", 0)
                )
            } else {
                TokenResult(false, err = j.optString("error", "校验失败"))
            }
        } catch (e: Exception) {
            TokenResult(false, err = e.message ?: "网络错误")
        }
    }

    private fun tierName(tier: String): String = when (tier) {
        "basic" -> "基础版"
        "standard" -> "标准版"
        "pro" -> "旗舰版"
        else -> if (tier.isBlank()) "未激活" else tier
    }

    /** token 数 -> 人类可读（万 / 亿）。 */
    private fun fmt(n: Long): String {
        val wan = n / 10000.0
        return if (wan >= 10000) "%.2f亿".format(wan / 10000.0)
        else if (wan >= 1) "%.1f万".format(wan)
        else "$n"
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

    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    companion object {
        private const val TAG = "JEVASSIST"
        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"
        private const val REPO_URL = "https://github.com/jev-chat/jev-chat-jarvis"
    }
}

/** /token/status 校验结果。 */
private data class TokenResult(
    val ok: Boolean,
    val tier: String = "",
    val used: Long = 0,
    val quota: Long = 0,
    val remain: Long = 0,
    val err: String = ""
)
