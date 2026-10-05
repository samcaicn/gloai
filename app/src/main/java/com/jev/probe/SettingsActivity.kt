package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.AutoReply
import com.jev.probe.core.CloudSync
import com.jev.probe.core.DeviceId
import com.jev.probe.core.LicenseClient
import com.jev.probe.core.Prefs
import com.jev.probe.core.SyncClient
import com.jev.probe.core.kb.KbStore
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 设置页。设计原则：**只摆用户能用自己的话复述的东西**。
 *
 * 2026-10-05 精简前这里有 9 个 section、二十多个开关，每个下面还挂一段解释，
 * 混着「树读不到正文时用 OCR 兜底」「注入最近历史条数」这类只有实现者看得懂的措辞。
 * 现在收敛成 5 段：
 *
 *  1. 自动收发  —— 唯一需要用户操心的开关组，全部用「会发生什么」措辞；
 *  2. 我和ta的关系 —— 一个文本框，AI 判断质量唯一的输入；
 *  3. 外观      —— 透明度滑块；
 *  4. 订阅      —— 429 时唯一的出路，必须留在设置里；
 *  5. 关于      —— 版本 + 数据清除 + 诊断入口。
 *
 * 被移除的开关不是「删了不管」：凡是从界面上消失但仍影响运行的，都由
 * [Prefs.normalizeHiddenSettings] 在升级时统一拨回产品默认值（详见那里的说明），
 * 否则就会出现「用户关掉了某项、界面里再没有地方改回来」的隐形行为。
 *
 * 所有开关**点一下即生效**，不再有需要滚到底部按的「保存设置」——那是最容易
 * 让人以为改了没生效的地方。仍需输入的文字项（关系描述、发送延迟）改为
 * 失焦/文本变化时落盘。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** 订阅卡片的状态行，收银台返回后要就地刷新。 */
    private lateinit var licStatus: TextView
    private var pendingTier: String = ""

    /** 云端备份卡片的状态行，备份/恢复结束后要就地刷新。 */
    private lateinit var syncStatus: TextView

    /** 自动收发开关旁边那行状态说明，随开关变化重画。 */
    private var autoStatusLine: TextView? = null
    private var autoSwitch: TextView? = null

    /** 发送延迟输入框：离开页面时兜底落盘（页面上已没有「保存设置」按钮）。 */
    private var pendingDelayEdit: EditText? = null

    /** 收银台在 App 内打开（见 [CheckoutActivity]）；返回后若还没激活就继续后台确认。 */
    private val checkoutLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val activated = prefs.licenseKey.isNotBlank() || prefs.accountToken.isNotBlank()
        if (activated) {
            licStatus.text = licenseStatusText()
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                Toast.makeText(this, "✓ 激活成功，判断接口已切换到云端", Toast.LENGTH_LONG).show()
            }
        } else {
            // 提前退出收银台（或付款还在银行侧处理）：继续后台确认
            startLicensePoll(pendingTier, licStatus)
        }
    }

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val danger = Color.parseColor("#DC2626")

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

        root.addView(text("设置", 22f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) })

        buildAutoReplyCard(root)
        buildRelationshipCard(root)
        buildAppearanceCard(root)
        buildCloudSyncCard(root)

        val licCard = buildLicenseCard(root)
        buildAboutCard(root)

        setContentView(scroll)

        // 从「共享额度正忙（429）」的错误卡跳进来时，直接落到订阅卡上。
        if (intent?.getStringExtra("focus") == "plan") {
            scroll.post { scroll.smoothScrollTo(0, licCard.top - dp(8)) }
        }
    }

    override fun onPause() {
        // 所有改动都是即时的，只有这个数字框需要在离开时兜底存一次。
        flushPendingEdits()
        super.onPause()
    }

    // ------------------------------------------------------- 1. 自动收发

    /**
     * 整个设置页最该显眼的一组。原来「自动发送」「自动填入」「自动打开会话」是三个
     * 散在两节的独立开关，用户根本不知道要一起开；这里合并成一个总开关，语义与
     * [AutoReply] 保持一致（三个入口：这里 / 悬浮球菜单 / 通知栏动作）。
     */
    private fun buildAutoReplyCard(root: LinearLayout) {
        root.addView(section("自动收发"))
        val c = card()

        val status = text("", 12.5f, sub).apply { setPadding(0, dp(2), 0, dp(8)) }
        autoStatusLine = status
        c.addView(status)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        row.addView(text("收到消息后自动回复并发送", 15f, ink, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = switchView(AutoReply.isOn(this))
        autoSwitch = sw
        sw.setOnClickListener { toggleAutoReply() }
        row.addView(sw)
        c.addView(row)

        // 唯一的红线提示，保留但压成两行：这不是可以顺手点开的开关。
        c.addView(text(
            "这是本应用唯一会替你发消息的设置。发出去的一定是候选回复原文，转账、红包、收款一律不碰。",
            11f, danger).apply { setPadding(0, dp(6), 0, dp(2)) })

        // 通知读取权限：没开就自动收发不了，所以放在这一组里而不是另起一节。
        val notifyOn = isNotifListenerEnabled()
        c.addView(permRow("通知读取权限", "用来知道微信来了新消息", notifyOn) {
            startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })

        // 发送延迟：一个数字，但它直接决定「自动发送能不能成功」——太快会点空。
        c.addView(label("发出前等几秒"))
        val delayEdit = edit(
            if (prefs.sendDelayMs % 1000 == 0) (prefs.sendDelayMs / 1000).toString()
            else String.format("%.1f", prefs.sendDelayMs / 1000f),
            "2"
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        c.addView(delayEdit)
        c.addView(text("太快的话微信还没准备好发送，点下去消息就丢了。", 11f, sub)
            .apply { setPadding(0, dp(4), 0, 0) })
        // 离开设置页时兜底保存。只挂失焦是不够的：软键盘收起、系统返回手势都可能
        // 不经过 onBlur，用户改完直接退出就会丢掉这个值（而页面上已经不再有「保存」按钮）。
        pendingDelayEdit = delayEdit

        root.addView(c)
        refreshAutoReplyUi()
    }

    private fun flushPendingEdits() {
        val v = pendingDelayEdit?.text?.toString()?.trim()?.toFloatOrNull()
        if (v != null) prefs.sendDelayMs = (v * 1000f).toInt()
        pendingDelayEdit = null
    }

    /** 与 [AutoReply.setOn] 走同一个事实源；开启需要二次确认。 */
    private fun toggleAutoReply() {
        val on = !AutoReply.isOn(this)
        if (on) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("确定开启自动收发？")
                .setMessage(
                    "对方发来消息后，应用会自动读、判断、填好回复并直接发送出去。\n\n" +
                        "发出去的一定是候选回复的原文，不会改写；转账、红包、收款一律不碰。\n\n" +
                        "随时想停：在悬浮球菜单、通知栏或这里点一下就能关。"
                )
                .setPositiveButton("开启") { _, _ -> applyAutoReply(true) }
                .setNegativeButton("再想想", null)
                .show()
        } else {
            applyAutoReply(false)
        }
    }

    private fun applyAutoReply(on: Boolean) {
        val msg = AutoReply.setOn(this, on)
        // 保活通知上挂着「暂停/开启自动收发」动作 + 一行状态文案，状态变了必须立刻重画。
        com.jev.probe.capture.KeepAliveService.refresh(this)
        refreshAutoReplyUi()
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshAutoReplyUi() {
        val on = AutoReply.isOn(this)
        autoSwitch?.let { applySwitchLook(it, on) }
        autoStatusLine?.text = if (on)
            "对方发消息 → 我读 → 自动填好并发出"
        else
            "对方发消息 → 我读 → 给出建议，发送由你点"
    }

    // ------------------------------------------------------- 2. 关系描述

    /**
     * 关系描述是 AI 判断质量唯一的用户输入，也是这页第二有价值的设置。
     * 原来旁边那句「（给 Jev 判断用）」是实现细节，改成用户能理解的理由。
     */
    private fun buildRelationshipCard(root: LinearLayout) {
        root.addView(section("我和对方的关系"))
        val c = card()
        c.addView(text("写清你们是什么关系、现在怎么样，AI 判断时会更准。", 12f, sub))
        c.addView(label("关系描述"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        // 逐字落盘：点「保存」才生效在这里是不可接受的（用户会以为没生效然后丢下）。
        relEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.relationship = s?.toString().orEmpty()
            }
        })
        c.addView(relEdit)

        c.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        c.addView(text("记下对方的重要信息，判断和回复会更贴合。", 11f, sub))
        root.addView(c)
    }

    // ---------------------------------------------------------- 3. 外观

    private fun buildAppearanceCard(root: LinearLayout) {
        root.addView(section("外观"))
        val c = card()
        val opacityLabel = label("悬浮窗透明度")
        c.addView(opacityLabel)
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗透明度：${100 - p - 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    // 拖完就存：滑块没有「需要点保存」这种交互。
                    prefs.overlayOpacity = sb!!.progress + 60
                }
            })
        }
        c.addView(seek)
        c.addView(text("越透明越能看清下面的聊天内容。", 11f, sub))
        root.addView(c)
    }

    // ------------------------------------------------------- 3.5 换手机不丢

    /**
     * 云端备份卡片。放回设置页的原因不是「备份功能重要」，而是它同时扛着两件
     * 卸载重装会丢的事：用户配置，以及**已付款的购买凭证**（见卡片底部说明）。
     * 去掉它，用户会以为自己没丢东西，实际重装后要重新配一遍。
     */
    private fun buildCloudSyncCard(root: LinearLayout) {
        root.addView(section("换手机 / 重装不丢"))
        val syncCard = card()
        val syncStatusView = text(syncStatusText(), 12.5f, ink, bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
        }
        syncStatus = syncStatusView
        syncCard.addView(syncStatusView)
        syncCard.addView(text(
            "把你的设置存一份到网上。换个手机、重装一次，登录回来就能接着用。聊天记录不会上传。",
            11f, sub))
        syncCard.addView(toggleRow("开启备份", prefs.syncEnabled) { on ->
            prefs.syncEnabled = on
            syncStatusView.text = syncStatusText()
        })
        syncCard.addView(cardBtn("立即备份") { doSyncBackup(syncStatusView) })
        syncCard.addView(cardBtn("从云端恢复") { doSyncRestore(syncStatusView) })
        syncCard.addView(text(
            "已经买过的也不怕：只要这台手机没换，付款记录会自己找回来，不用重复买。",
            11f, sub))
        root.addView(syncCard)
    }

    // ---------------------------------------------------------- 4. 订阅

    /**
     * 订阅卡保留完整（这是 429 时唯一的出路，浮窗的错误卡会直接跳过来），
     * 但把三档卡片顶上的解释性长文砍掉——「卖点要说真话」不等于「要写一屏文案」。
     */
    private fun buildLicenseCard(root: LinearLayout): LinearLayout {
        root.addView(section("订阅"))
        val licCard = card()
        licCard.addView(text("判断与回复由 WeAuto 云端提供，繁忙时可能提示 429。", 12f, sub))

        val licStatusView = text(licenseStatusText(), 12.5f, ink, bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
        }
        licStatus = licStatusView
        licCard.addView(licStatusView)
        licCard.addView(quotaBar())

        for (tier in LicenseClient.TIERS) {
            licCard.addView(tierCard(tier) { startCheckout(tier, licStatus) })
        }
        licCard.addView(text("付款在本应用内完成，确认后自动激活。", 11f, sub))
        root.addView(licCard)
        return licCard
    }

    // ---------------------------------------------------------- 5. 关于

    private fun buildAboutCard(root: LinearLayout) {
        root.addView(section("关于"))
        val c = card()

        c.addView(cardBtn("自检与诊断") {
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .putExtra("diag", true))
        })
        c.addView(text("助手没反应、上次分析成没成、有没有崩过，都在这里。", 11f, sub))

        val kbResult = resultText()
        c.addView(cardBtn("清空知识库与聊天历史") {
            val n = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${n.notes} 条笔记、${n.contacts} 个联系人、" +
                    "${n.logLines} 条聊天历史。其他设置不受影响，不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        c.addView(kbResult)

        c.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(14), 0, dp(2)) })
        root.addView(c)
    }

    // ---------------------------------------------------------------- atoms

    private fun applySwitchLook(sw: TextView, on: Boolean) {
        sw.text = if (on) "开" else "关"
        sw.setTextColor(if (on) Color.WHITE else sub)
        sw.background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
    }

    private fun switchView(on: Boolean) = TextView(this).apply {
        text = if (on) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else sub)
        background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(18), dp(6), dp(18), dp(6))
        // 放进 LinearLayout 前必须显式给尺寸，否则会退回默认的 wrap_content/wrap_content，
        // 在 weight 行里表现和 [permRow] 的按钮不一致。
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun section(t: String) = text(t, 12f, sub, bold = true).apply {
        setPadding(dp(2), dp(18), 0, dp(6))
    }

    private fun label(t: String) = text(t, 13f, ink, bold = true).apply {
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply {
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    /**
     * 一行「标签 + 右端开关」。与精简前的老实现不同，这里的点击会**真正回写**
     * [onChange]（老版只改颜色、值永远不落盘，是个隐形 bug）。
     */
    private fun toggleRow(labelText: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2))
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
            val now = (sw.text == "关")
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
            onChange(now)
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    /** 权限行：标题 + 一句用途 + 右侧按钮。已开启时按钮变灰不可点。 */
    private fun permRow(title: String, desc: String, granted: Boolean, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 14f, ink, bold = true))
        left.addView(text(desc, 11.5f, sub).apply { setPadding(0, dp(2), 0, 0) })
        c.addView(left)
        val btn = TextView(this).apply {
            text = if (granted) "已开启" else "去开启"
            textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (granted) sub else Color.WHITE)
            background = round(dp(10), if (granted) Color.parseColor("#E5E7EB") else accent)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (!granted) btn.setOnClickListener { onClick() }
        c.addView(btn)
        return c
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    // ------------------------------------------------------------ 订阅卡

    /**
     * 三档对比卡。卖点必须说真话：免费档走的是**共享**网关，高峰会排队并返回 429；
     * 付费买的是专属额度，不是「更多功能」。但这句话一行就够，不必写成一段。
     */
    private fun tierCard(tier: String, onClick: () -> Unit): View {
        val current = prefs.licenseTier == tier
        val (name, pitch) = when (tier) {
            LicenseClient.TIER_NORMAL -> "标准版 · 月租" to "专属额度，不用和免费用户挤"
            LicenseClient.TIER_PREMIUM -> "高级版 · 月租" to "更大额度，消息密集时也不卡"
            else -> "终身版 · 一次性" to "一次买断，永久专属额度"
        }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(dp(12), if (current) Color.parseColor("#EAF1FF") else Color.parseColor("#F7F8FA"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(name, 14f, ink, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (current) head.addView(text("当前档位", 11f, accent, bold = true))
        c.addView(head)
        c.addView(text(pitch, 12f, sub).apply { setPadding(0, dp(3), 0, dp(8)) })
        c.addView(pillBtn(if (current) "续费 / 换档" else "选择这个") { onClick() })
        return c
    }

    private fun pillBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        background = round(dp(10), accent)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /** 本月额度进度条（数字来自接口 `X-Billing-*` 响应头）。 */
    private fun quotaBar(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val q = prefs.billingQuota
        if (q <= 0L) {
            box.addView(text("额度：跑一次分析后显示", 12f, sub))
            return box
        }
        val used = prefs.billingUsed.coerceIn(0, q)
        val pct = (used * 100 / q).toInt().coerceIn(0, 100)
        box.addView(text("本月额度 $used / $q（$pct%）", 12.5f, ink, bold = true))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(dp(4), Color.parseColor("#E5E7EB"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(5) }
        }
        bar.addView(View(this).apply {
            background = round(dp(4), if (pct >= 90) danger else accent)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, (pct.coerceAtLeast(2) / 100f))
        })
        box.addView(bar)
        if (pct >= 90) box.addView(text(
            "额度快用完了，换个档位就不用和免费用户挤了。",
            11f, danger).apply { setPadding(0, dp(5), 0, 0) })
        return box
    }

    private fun licenseStatusText(): String = when {
        prefs.licenseKey.isNotBlank() ->
            "✓ 已激活 · ${tierLabel(prefs.licenseTier)} · 判断已走云端"
        prefs.accountToken.isNotBlank() -> "✓ 已有账户令牌 · 判断已走云端"
        else -> "未激活 — 判断走共享网关，高峰期可能提示 429"
    }

    private fun tierLabel(tier: String) = when (tier) {
        LicenseClient.TIER_NORMAL -> "标准版月租"
        LicenseClient.TIER_PREMIUM -> "高级版月租"
        LicenseClient.TIER_LIFETIME -> "终身版"
        else -> if (tier.isBlank()) "未知档位" else tier
    }

    /**
     * 打开收银台并开始自动确认：Worker /buy 动态建 checkout（302 到 Creem 的 pay.jukuai.net，
     * metadata 带本机 mid）→ **在 App 内 [CheckoutActivity] 的 WebView 里付款**（不跳外部浏览器）
     * → webhook 写 lic:<mid> → 每 3s 轮询 /license?mid= → 拿到卡密即写 accountToken + 切 worker 模式。
     */
    private fun startCheckout(tier: String, statusView: TextView) {
        val url = LicenseClient.buyUrl(prefs, tier)
        pendingTier = tier
        try {
            checkoutLauncher.launch(
                android.content.Intent(this, CheckoutActivity::class.java)
                    .putExtra(CheckoutActivity.EXTRA_URL, url)
                    .putExtra(CheckoutActivity.EXTRA_TIER, tier))
            statusView.text = "等待付款确认…（${tierLabel(tier)}）"
        } catch (e: Exception) {
            // 极端情况（ROM 无 WebView）：退回浏览器，但确认逻辑不变
            Log.w(TAG, "in-app checkout unavailable: ${e.message}")
            try {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开收银台：${e2.message}", Toast.LENGTH_LONG).show()
                return
            }
            startLicensePoll(tier, statusView)
        }
    }

    /** 每 3s 轮询一次卡密（最长 30 分钟），拿到即落盘激活并刷新状态行。 */
    private fun startLicensePoll(tier: String, statusView: TextView) {
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

    // ------------------------------------------------------------ 云端备份

    private fun syncStatusText(): String {
        if (!prefs.syncEnabled) return "云端备份：已关闭"
        val last = prefs.lastSyncAt
        val ago = if (last <= 0) "尚未备份" else {
            val mins = (System.currentTimeMillis() - last) / 60000
            when {
                mins < 1 -> "刚刚备份"
                mins < 60 -> "$mins 分钟前备份"
                else -> "${mins / 60} 小时前备份"
            }
        }
        return "云端备份：$ago · 设备 ${DeviceId.shortHash(this).take(8)}"
    }

    private fun doSyncBackup(statusView: TextView) {
        statusView.text = "正在备份…"
        worker.execute {
            val res = CloudSync.uploadNow(this, prefs)
            main.post {
                if (isFinishing || isDestroyed) return@post
                statusView.text = when (res) {
                    is SyncClient.Result.Ok -> syncStatusText()
                    SyncClient.Result.NotDeployed -> "暂时连不上服务器，稍后再试"
                    SyncClient.Result.Empty -> "云端备份：已关闭"
                    is SyncClient.Result.Failure -> "备份失败：${res.message}"
                }
            }
        }
    }

    private fun doSyncRestore(statusView: TextView) {
        statusView.text = "正在恢复…"
        worker.execute {
            val msg = CloudSync.restoreNow(this, prefs)
            main.post {
                if (isFinishing || isDestroyed) return@post
                statusView.text = msg ?: if (prefs.syncRestored) {
                    "云端没有更早的数据"
                } else {
                    "暂时连不上服务器，稍后再试"
                }
            }
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"
    }
}
