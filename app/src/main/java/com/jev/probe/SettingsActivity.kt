package com.jev.probe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.jev.probe.core.Lang
import com.jev.probe.core.Prefs
import com.jev.probe.core.SyncClient
import com.jev.probe.core.license.PackLedger
import com.jev.probe.core.scriptpack.PackError
import com.jev.probe.core.scriptpack.ScriptPackStore
import com.jev.probe.core.t
import com.jev.probe.core.ConversationHistory
import com.jev.probe.capture.ChatCaptureService
import com.jev.probe.core.kb.KbStore
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 设置页。设计原则：**只摆用户能用自己的话复述的东西**。
 *
 * 2026-10-05 精简前这里有 9 个 section、二十多个开关，每个下面还挂一段解释，
 * 混着「树读不到正文时用 OCR 兜底」「注入最近历史条数」这类只有实现者看得懂的措辞。
 * 现在收敛成 6 段：
 *
 *  1. 自动收发  —— 唯一需要用户操心的开关组，全部用「会发生什么」措辞；
 *  2. 我和ta的关系 —— 一个文本框，AI 判断质量唯一的输入；
 *  3. 外观      —— 透明度滑块；
 *  3.5 换手机不丢 —— 云端备份（设置 + 购买凭证）；
 *  3.7 话术包   —— 导入商家签发的 .wpk，AI 回复按包内人设/风格生成（见 docs/scriptpack-spec.md）；
 *  4. 授权中心  —— 免费额度 / 自带接口（BYO Key）/ 话术包三种用法，429 时在这里切自带接口；
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

    /** 云端备份卡片的状态行，备份/恢复结束后要就地刷新。 */
    private lateinit var syncStatus: TextView

    /** 自动收发开关旁边那行状态说明，随开关变化重画。 */
    private var autoStatusLine: TextView? = null
    private var autoSwitch: TextView? = null

    /** 发送延迟输入框：离开页面时兜底落盘（页面上已没有「保存设置」按钮）。 */
    private var pendingDelayEdit: EditText? = null

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val danger = Color.parseColor("#DC2626")

    /** 话术包导入（SAF OpenDocument）。导入/删除/启用后整卡重画。 */
    private val packImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importPackAsync(it) } }

    /** 话术包卡片容器：状态变化时清空重画（导入 / 启用 / 删除）。 */
    private var packCardHost: LinearLayout? = null

    /** 授权中心卡容器：重置今日免费计数后就地刷新额度进度条。 */
    private var authCardRef: LinearLayout? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // 确保最近会话候选桶已加载（主动发送卡片用）。幂等；load 在磁盘线程跑。
        ConversationHistory.attach(this)
        Log.i(TAG, "settings opened workerMode=${prefs.isWorkerMode}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()
        scroll.addView(root)

        root.addView(text(t("设置", "Settings"), 22f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) })

        root.addView(buildLangRow())

        buildAutoReplyCard(root)
        buildNoReplyCard(root)
        buildProactiveSendCard(root)
        buildRelationshipCard(root)
        buildSendLedgerCard(root)
        buildAppearanceCard(root)
        buildCloudSyncCard(root)
        buildScriptPackCard(root)

        val authCard = buildAuthCenterCard(root)
        buildAboutCard(root)

        setContentView(scroll)

        // 从「共享额度正忙（429）」的错误卡跳进来时，直接落到授权中心卡（自带接口入口）。
        if (intent?.getStringExtra("focus") == "plan") {
            scroll.post { scroll.smoothScrollTo(0, authCard.top - dp(8)) }
        }
    }

    override fun onPause() {
        // 所有改动都是即时的，只有这个数字框需要在离开时兜底存一次。
        flushPendingEdits()
        super.onPause()
    }

    /**
     * 中 / EN 界面语言切换。默认中文；点 EN 即全 App 界面切英文（本 Activity
     * recreate 立即生效，主页 onResume 重建、悬浮球下次重绘生效）。
     * 当前语言高亮，再点当前语言不做事。
     */
    private fun buildLangRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), 0)
        }
        row.addView(text(t("语言 / Language", "Language / 语言"), 14f, ink, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cur = Lang.code(this)
        fun langBtn(label: String, code: String): TextView {
            val active = cur == code
            return TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (active) Color.WHITE else ink)
                background = round(dp(10), if (active) accent else Color.WHITE, stroke = !active)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
                if (!active) setOnClickListener {
                    Lang.set(this@SettingsActivity, code)
                    recreate()
                }
            }
        }
        row.addView(langBtn("中文", Lang.ZH))
        row.addView(langBtn("EN", Lang.EN))
        return row
    }

    // ------------------------------------------------------- 1. 自动收发

    /**
     * 整个设置页最该显眼的一组。原来「自动发送」「自动填入」「自动打开会话」是三个
     * 散在两节的独立开关，用户根本不知道要一起开；这里合并成一个总开关，语义与
     * [AutoReply] 保持一致（三个入口：这里 / 悬浮球菜单 / 通知栏动作）。
     */
    private fun buildAutoReplyCard(root: LinearLayout) {
        root.addView(section(t("自动收发", "Auto send/receive")))
        val c = card()

        val status = text("", 12.5f, sub).apply { setPadding(0, dp(2), 0, dp(8)) }
        autoStatusLine = status
        c.addView(status)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        row.addView(text(t("收到消息后自动回复并发送", "Auto-reply and send when a message arrives"), 15f, ink, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = switchView(AutoReply.isOn(this))
        autoSwitch = sw
        sw.setOnClickListener { toggleAutoReply() }
        row.addView(sw)
        c.addView(row)

        // 唯一的红线提示，保留但压成两行：这不是可以顺手点开的开关。
        c.addView(text(
            t("这是本应用唯一会替你发消息的设置。发出去的一定是候选回复原文，转账、红包、收款一律不碰。",
              "This is the only setting that sends messages on your behalf. What gets sent is always the candidate reply verbatim; money transfers, red packets and payment requests are never touched."),
            11f, danger).apply { setPadding(0, dp(6), 0, dp(2)) })

        // 通知读取权限：没开就自动收发不了，所以放在这一组里而不是另起一节。
        val notifyOn = isNotifListenerEnabled()
        c.addView(permRow(t("通知读取权限", "Notification access"), t("用来知道聊天来了新消息", "So the app knows when a new chat message arrives"), notifyOn) {
            startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })

        // 写入输入法（2026-10-13）：微信 8.0.78 剥光 a11y 树后，普通 app 再也写不进输入栏，
        // 自动回复的文本只能靠探针自带的输入法服务 commitText 写入。没启用它，
        // 「自动发送」会退化成「已复制，长按输入框粘贴」。这里给一次性引导。
        val imeOn = com.jev.probe.core.SelfCheck.isProbeImeEnabledPublic(this)
        val imeActive = com.jev.probe.input.ProbeImeBus.imeActive
        val imeDesc = if (!imeOn) {
            t("未启用：自动回复只能复制到剪贴板。点此去 设置→语言和输入法 启用「AI聊天助手 · 写入」", "Not enabled: replies are only copied to the clipboard. Tap to open Settings → Languages & input and enable \"AI Assistant · Write\"")
        } else if (imeActive) {
            t("已设为当前输入法，自动回复直接写入并发送", "Active — replies are written and sent directly")
        } else {
            t("已启用，但还不是当前输入法。点下方按钮切换", "Enabled but not current. Tap the button below to switch")
        }
        c.addView(permRow(
            t("写入输入法", "Write input method"),
            imeDesc,
            imeOn
        ) {
            startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        // 写入输入法启用后，还须把它设为「当前输入法」自动发送才会真正落键——
        // 否则每次填完都弹系统选择器、需手动点一次。这里给一键切换入口（弹选择器选探针）。
        if (imeOn) {
            c.addView(cardBtn(t("切换为「AI聊天助手 · 写入」", "Switch to \"AI Assistant · Write\"")) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showInputMethodPicker()
                Toast.makeText(this, t("在弹出列表里选「AI聊天助手 · 写入」",
                    "Pick \"AI Assistant · Write\" in the list"), Toast.LENGTH_LONG).show()
            })
        }

        // 「自动分析」开关已去掉（2026-10-15 用户要求）：只要软件在跑就自动分析，
        // 设置页不再给这一项。上面保留的只有「自动收发」——发送权仍在人手里。

        // 随机等待：自动回复前随机等 2–300 秒，更像人、也留出窗口直接在微信里改草稿。
        c.addView(toggleRow(
            t("随机等待（更自然）", "Random wait (more human-like)"),
            prefs.randomSendDelay
        ) { prefs.randomSendDelay = it })
        c.addView(text(
            t("每条自动回复前随机等 2–300 秒再发出。等待时可改草稿，或点「本次不发送」拦下。",
              "Each auto-reply waits a random 2–300s. During the wait you can edit the draft or tap \"Skip this time\"."),
            11f, sub).apply { setPadding(dp(2), dp(2), 0, dp(2)) })

        // 发送延迟：固定等待（随机关闭、或主动发送时用的兜底值）。太快会点空。
        c.addView(label(t("发出前固定等待（秒）", "Fixed wait before sending (seconds)")))
        val delayEdit = edit(
            if (prefs.sendDelayMs % 1000 == 0) (prefs.sendDelayMs / 1000).toString()
            else String.format("%.1f", prefs.sendDelayMs / 1000f),
            "2"
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        c.addView(delayEdit)
        c.addView(text(
            t("「随机等待」关掉、或你主动发消息时使用。",
              "Used when random wait is off, or for messages you send on purpose."),
            11f, sub).apply { setPadding(0, dp(4), 0, 0) })
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
                .setTitle(t("确定开启自动收发？", "Turn on auto send/receive?"))
                .setMessage(
                    t(
                        "对方发来消息后，应用会自动读、判断、填好回复并直接发送出去。\n\n" +
                            "发出去的一定是候选回复的原文，不会改写；转账、红包、收款一律不碰。\n\n" +
                            "随时想停：在悬浮球菜单、通知栏或这里点一下就能关。",
                        "When a message arrives, the app will read it, judge it, fill in a reply and send it automatically.\n\n" +
                            "What gets sent is always the candidate reply verbatim, never rewritten; money transfers, red packets and payment requests are never touched.\n\n" +
                            "To stop anytime: tap in the bubble menu, the notification, or here."
                    )
                )
                .setPositiveButton(t("开启", "Turn on")) { _, _ -> applyAutoReply(true) }
                .setNegativeButton(t("再想想", "Not now"), null)
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
            t("对方发消息 → 我读 → 自动填好并发出",
              "They message → I read → auto-fill and send")
        else
            t("对方发消息 → 我读 → 给出建议，发送由你点",
              "They message → I read → suggestions, you tap send")
    }

    // ------------------------------------------------------- 2. 关系描述

    /**
     * 关系描述是 AI 判断质量唯一的用户输入，也是这页第二有价值的设置。
     * 原来旁边那句「（给 Jev 判断用）」是实现细节，改成用户能理解的理由。
     */
    /**
     * 不回复名单卡：命中关键词的会话（群/个人）完全不自动回复。
     * 与「只分析这些会话」白名单互不影响，这里存关键词、子串匹配。
     * 增删都在本卡内完成：输入框 + 添加按钮，名单行右侧「移除」。
     */
    private fun buildNoReplyCard(root: LinearLayout) {
        root.addView(section(t("不回复名单", "No-reply list")))
        val c = card()
        c.addView(text(t(
            "名单里的群/个人来了消息不分析、不回复。存关键词，匹配会话标题。",
            "Chats (groups or people) whose title matches a keyword below get no auto replies."
        ), 12f, sub).apply { setPadding(0, dp(2), 0, dp(6)) })

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(listHost)
        val input = edit("", t("输入群名或对方昵称的关键词", "Keyword from group/person title"))

        fun render() {
            listHost.removeAllViews()
            val nl = prefs.noReplyList
            if (nl.isEmpty()) {
                listHost.addView(text(t("（空）—— 所有会话都会正常自动回复",
                    "(empty) — every chat gets normal auto replies"), 12f, sub).apply {
                    setPadding(0, dp(4), 0, dp(4))
                })
            } else {
                for (kw in nl.sorted()) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = round(dp(8), Color.parseColor("#F7F8FA"))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                    }
                    row.addView(text(kw, 13.5f, ink).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(text(t("移除", "Remove"), 12.5f, danger, bold = true).apply {
                        setPadding(dp(10), dp(4), dp(2), dp(4))
                        setOnClickListener {
                            prefs.noReplyList = prefs.noReplyList - kw
                            render()
                        }
                    })
                    listHost.addView(row)
                }
            }
        }
        render()

        c.addView(input)
        c.addView(cardBtn(t("+ 添加到不回复名单", "+ Add to no-reply list")) {
            val kw = input.text.toString().trim()
            if (kw.isEmpty()) {
                Toast.makeText(this, t("先输入关键词", "Enter a keyword first"), Toast.LENGTH_SHORT).show()
                return@cardBtn
            }
            if (prefs.noReplyList.any { it.equals(kw, ignoreCase = true) }) {
                Toast.makeText(this, t("名单里已有这个关键词", "Already in the list"), Toast.LENGTH_SHORT).show()
                return@cardBtn
            }
            prefs.noReplyList = prefs.noReplyList + kw
            input.setText("")
            render()
        })
        root.addView(c)
    }

    // ------------------------------------------------------- 主动发送（方案 A/B）
    /**
     * 主动给旧会话发消息。硬约束（和自动回复一致，绕不开）：
     *  - 目标会话须在 30 分钟内来过一条通知（微信前台读不到标题，只能靠通知锚点定位）；
     *  - 发送瞬间屏幕要亮着 + 探针输入法激活 + 微信在前台（或能自动重开会话）。
     * 没有"枚举所有会话"的能力（FLAG_SECURE），会话名由用户自己填，最近来过的列成快捷标签。
     */
    private fun buildProactiveSendCard(root: LinearLayout) {
        root.addView(section(t("主动发送（给旧会话发消息）", "Proactive send (to an old chat)")))
        val c = card()
        c.addView(text(t(
            "填会话名（群名/对方昵称）+ 内容，点发送。只能发给 30 分钟内来过通知的会话；" +
            "发送时需屏幕亮着、探针输入法已激活、聊天在前台（或能自动重开）。",
            "Enter a chat name (group/contact) and text, then tap send. Only chats that got a " +
            "notification in the last 30 min can be located; the screen must be on with the probe keyboard active."
        ), 12f, sub).apply { setPadding(0, dp(2), 0, dp(6)) })

        val convEdit = edit("", t("会话名（群名 / 对方昵称）", "Chat name (group / contact)"))
        c.addView(convEdit)

        // 最近来过的会话：恰好就是有锚点、能定位的那些，点一下填进输入框。
        val recent = ConversationHistory.convsForPkg(ChatCaptureService.PKG_WECHAT)
        if (recent.isNotEmpty()) {
            c.addView(text(t("最近会话（点一下填入）", "Recent chats (tap to fill)"), 12f, sub).apply {
                setPadding(0, dp(6), 0, dp(2))
            })
            val chipHost = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // 横向滚动，标签多了不撑高页面。
            }
            val chipScroll = android.widget.HorizontalScrollView(this).apply {
                setPadding(0, 0, 0, dp(2))
            }
            for (r in recent) {
                val chip = TextView(this).apply {
                    text = r
                    textSize = 13f
                    setTextColor(ink)
                    background = round(dp(8), Color.parseColor("#EEF2FF"))
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.rightMargin = dp(8); lp.topMargin = dp(2); lp.bottomMargin = dp(2)
                    layoutParams = lp
                    setOnClickListener { convEdit.setText(r) }
                }
                chipHost.addView(chip)
            }
            chipScroll.addView(chipHost)
            c.addView(chipScroll)
        }

        c.addView(label(t("要发送的内容", "Message to send")))
        val msgEdit = edit("", t("在这里输入要发的内容", "Type the message here")).apply {
            minLines = 2; gravity = Gravity.START or Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        c.addView(msgEdit)

        c.addView(cardBtn(t("发送", "Send")) {
            val conv = convEdit.text.toString().trim()
            val text = msgEdit.text.toString().trim()
            if (conv.isBlank() || text.isBlank()) {
                Toast.makeText(this, t("会话名和内容都要填", "Both chat name and message are required"),
                    Toast.LENGTH_SHORT).show()
                return@cardBtn
            }
            // 走和 adb 同一条校验过的入口：令牌 + 持久化队列 + 服务补发。
            val token = prefs.proactiveToken
            runCatching {
                sendBroadcast(Intent(ChatCaptureService.ACTION_SEND_TO_CHAT).apply {
                    `package` = packageName
                    putExtra("conv", conv)
                    putExtra("text", text)
                    putExtra("token", token)
                })
                Toast.makeText(this, t("已加入待发队列：$conv", "Queued: $conv"), Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(c)
    }

    /**
     * 发送记录回放：把 send_ledger.csv 尾部若干条（时间/会话/结果/原因）展示出来。
     * 「我发了 你没发成功」这类争议以前只能靠 adb 抓文件，现在用户自己就能查——
     * 每行都有发送链路落下的真实证据（box empty after tap / no anchor / queue draft 等）。
     */
    private fun buildSendLedgerCard(root: LinearLayout) {
        root.addView(section(t("发送记录", "Send log")))
        val c = card()
        val logView = TextView(this).apply {
            textSize = 11f
            setTextColor(sub)
            setLineSpacing(dp(2).toFloat(), 1f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        fun refresh() {
            val lines = try {
                val f = java.io.File(getExternalFilesDir(null), "send_ledger.csv")
                if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(12) else emptyList()
            } catch (_: Exception) { emptyList() }
            if (lines.isEmpty()) {
                logView.text = t("还没有发送记录。", "No send records yet.")
                return
            }
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            logView.text = lines.reversed().joinToString("\n") { raw ->
                val p = raw.split(",", limit = 4)
                val time = p.getOrNull(0)?.toLongOrNull()?.let { fmt.format(java.util.Date(it)) } ?: "?"
                val conv = p.getOrNull(1) ?: "-"
                val result = p.getOrNull(2) ?: "?"
                val detail = p.getOrNull(3) ?: ""
                "$time  ${if (result == "sent") "✓" else "✗"} $conv" +
                    (if (detail.isNotBlank()) "  · $detail" else "")
            }
        }
        refresh()
        c.addView(logView)
        c.addView(cardBtn(t("刷新", "Refresh")) { refresh() })
        root.addView(c)
    }

    private fun buildRelationshipCard(root: LinearLayout) {
        root.addView(section(t("我和对方的关系", "My relationship with them")))
        val c = card()
        c.addView(text(t("写清你们是什么关系、现在怎么样，AI 判断时会更准。",
            "Describe your relationship and where things stand — the AI judges more accurately."), 12f, sub))
        c.addView(label(t("关系描述", "Relationship notes")))
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

        c.addView(cardBtn(t("知识库与联系人", "Knowledge base & contacts")) {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        c.addView(text(t("记下对方的重要信息，判断和回复会更贴合。",
            "Note important things about them — judgments and replies fit better."), 11f, sub))
        root.addView(c)
    }

    // ---------------------------------------------------------- 3. 外观

    private fun buildAppearanceCard(root: LinearLayout) {
        root.addView(section(t("外观", "Appearance")))
        val c = card()
        val opacityLabel = label(t("悬浮窗透明度", "Card opacity"))
        c.addView(opacityLabel)
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = t("悬浮窗透明度", "Card opacity") + "：${100 - p - 60}%".replace("：", ": ")
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    // 拖完就存：滑块没有「需要点保存」这种交互。
                    prefs.overlayOpacity = sb!!.progress + 60
                }
            })
        }
        c.addView(seek)
        c.addView(text(t("越透明越能看清下面的聊天内容。",
            "More transparent makes the chat underneath easier to read."), 11f, sub))
        root.addView(c)
    }

    // ------------------------------------------------------- 3.5 换手机不丢

    /**
     * 云端备份卡片。放回设置页的原因不是「备份功能重要」，而是它同时扛着两件
     * 卸载重装会丢的事：用户配置，以及**已付款的购买凭证**（见卡片底部说明）。
     * 去掉它，用户会以为自己没丢东西，实际重装后要重新配一遍。
     */
    private fun buildCloudSyncCard(root: LinearLayout) {
        root.addView(section(t("换手机 / 重装不丢", "Survives phone changes / reinstalls")))
        val syncCard = card()
        val syncStatusView = text(syncStatusText(), 12.5f, ink, bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
        }
        syncStatus = syncStatusView
        syncCard.addView(syncStatusView)
        syncCard.addView(text(
            t("把你的设置存一份到网上。换个手机、重装一次，登录回来就能接着用。聊天记录不会上传。",
              "Keeps a copy of your settings online. Switch phones or reinstall and sign back in to continue. Chat content is never uploaded."),
            11f, sub))
        syncCard.addView(toggleRow(t("开启备份", "Back up"), prefs.syncEnabled) { on ->
            prefs.syncEnabled = on
            syncStatusView.text = syncStatusText()
        })
        syncCard.addView(cardBtn(t("立即备份", "Back up now")) { doSyncBackup(syncStatusView) })
        syncCard.addView(cardBtn(t("从云端恢复", "Restore from cloud")) { doSyncRestore(syncStatusView) })
        syncCard.addView(text(
            t("已经买过的也不怕：只要这台手机没换，付款记录会自己找回来，不用重复买。",
              "Already purchased? As long as this phone hasn't changed, your payment record comes back on its own — no need to buy again."),
            11f, sub))
        root.addView(syncCard)
    }

    // ------------------------------------------------------ 3.7 话术包

    /**
     * 话术包卡片：导入商家签发的 .wpk（加密 + 有期限，见 docs/scriptpack-spec.md）。
     * 导入是重操作（验签 + 解密 + 落盘 + 网络时间校准），放 worker 线程，
     * 结束后回主线程整卡重画。失败按 [PackError] 给双语文案。
     */
    private fun buildScriptPackCard(root: LinearLayout) {
        root.addView(section(t("话术包", "Script packs")))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        packCardHost = host
        root.addView(host)
        renderScriptPackCard()
    }

    private fun renderScriptPackCard() {
        val host = packCardHost ?: return
        host.removeAllViews()
        val c = card()
        c.addView(text(
            t("导入商家签发的话术包（.wpk 文件），AI 回复会按包内的人设与风格生成。",
              "Import a signed script pack (.wpk); AI replies follow its persona and style."),
            12f, sub))
        c.addView(text(scriptPackStatusText(), 12.5f, ink, bold = true)
            .apply { setPadding(0, dp(10), 0, dp(2)) })
        c.addView(cardBtn(t("导入话术包", "Import script pack")) {
            packImportLauncher.launch(arrayOf("*/*"))
        })
        val activeId = ScriptPackStore.activePackId(this)
        for (h in ScriptPackStore.packs(this)) {
            val isActive = h.packId == activeId
            c.addView(text(packLine(h, isActive), 12f, if (isActive) ink else sub, bold = isActive)
                .apply { setPadding(0, dp(10), 0, dp(2)) })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            if (!isActive) {
                row.addView(smallBtn(t("启用", "Enable")) {
                    ScriptPackStore.setActive(this, h.packId)
                    renderScriptPackCard()
                })
            }
            row.addView(smallBtn(t("删除", "Remove")) { confirmRemovePack(h.packId) })
            c.addView(row)
        }
        c.addView(text(
            t("本机绑定码（商家绑机签发时用）：${ScriptPackStore.deviceHashHex(this)}",
              "Device binding code (for device-bound signing): ${ScriptPackStore.deviceHashHex(this)}"),
            11f, sub).apply { setPadding(0, dp(12), 0, 0) })
        host.addView(c)
    }

    private fun packLine(h: com.jev.probe.core.scriptpack.PackHeader, isActive: Boolean): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(h.expiresAt * 1000))
        val bound = if (h.deviceBound) t("绑机", "device-bound") else t("未绑机", "any device")
        val prefix = if (isActive) "▶ " else ""
        return "$prefix${h.name} · v${h.version} · $bound · ${t("到期", "expires")} $date"
    }

    private fun scriptPackStatusText(): String {
        val activeId = ScriptPackStore.activePackId(this)
            ?: return t("未导入话术包，当前使用内置默认话术。",
                "No script pack imported — using the built-in default persona.")
        val h = ScriptPackStore.packs(this).firstOrNull { it.packId == activeId }
            ?: return t("启用中的话术包文件缺失。", "The active pack file is missing.")
        val days = (h.expiresAt * 1000 - ScriptPackStore.effectiveNow(this)) / 86400000
        return if (days >= 0) {
            t("${h.name} 生效中，剩余 $days 天", "${h.name} active — $days day(s) left")
        } else {
            t("${h.name} 已到期，已回退默认话术", "${h.name} expired — fell back to the default persona")
        }
    }

    private fun importPackAsync(uri: Uri) {
        worker.execute {
            try {
                ScriptPackStore.importPack(this, uri)
                main.post {
                    Toast.makeText(this, t("话术包已导入并启用", "Script pack imported and enabled"),
                        Toast.LENGTH_LONG).show()
                    renderScriptPackCard()
                }
            } catch (e: com.jev.probe.core.scriptpack.PackException) {
                Log.w(TAG, "scriptpack import rejected: ${e.code}")
                main.post {
                    Toast.makeText(this, packErrorText(e.code), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "scriptpack import io error: ${e.javaClass.simpleName}")
                main.post {
                    Toast.makeText(this, t("导入失败：读不了这个文件", "Import failed: cannot read this file"),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun packErrorText(code: PackError): String = when (code) {
        PackError.NOT_A_PACK -> t("不是话术包文件（.wpk）", "Not a script pack file (.wpk)")
        PackError.BAD_VERSION -> t("话术包版本太新，请升级 App", "Pack version too new — update the app")
        PackError.TRUNCATED -> t("文件不完整", "File is incomplete")
        PackError.BAD_HEADER -> t("话术包头损坏", "Pack header is corrupt")
        PackError.SIGNATURE -> t("签名校验失败：文件被改过或不是官方签发", "Signature check failed — tampered or not officially signed")
        PackError.DEVICE -> t("这是绑机话术包，不适用于本机", "Device-bound pack — not valid on this device")
        PackError.EXPIRED -> t("话术包已到期，请联系商家续期", "Pack expired — contact the merchant to renew")
        PackError.DECRYPT -> t("解密失败：文件损坏或设备不匹配", "Decrypt failed — corrupt file or device mismatch")
        PackError.CHECKSUM -> t("内容校验失败", "Content checksum failed")
        PackError.EXPIRES_MISMATCH -> t("包内时间信息不一致", "Inconsistent expiry inside the pack")
        PackError.APP_TOO_OLD -> t("App 版本太低，请升级后导入", "App too old — update before importing")
    }

    private fun confirmRemovePack(packId: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(t("删除话术包", "Remove script pack"))
            .setMessage(t("删除后该话术包不再可用，需要重新导入。",
                "The pack will stop working until re-imported."))
            .setPositiveButton(t("删除", "Remove")) { _, _ ->
                ScriptPackStore.remove(this, packId)
                renderScriptPackCard()
            }
            .setNegativeButton(t("取消", "Cancel"), null)
            .show()
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------- 4. 授权中心

    /**
     * 授权中心（取代旧「订阅」卡）。WeAuto 不再售卖卡密，使用方式收敛为三种：
     *  ① 免费额度 —— 默认模式，每天若干条，走 WeAuto 云端（共享网关，峰期限流 429）；
     *  ② 自带接口 —— 填自己的 OpenAI 兼容 base_url + key，直连自家模型，不计免费条数；
     *  ③ 话术包 —— 商家签发的 .wpk（见上方「话术包」卡），离线授权给 N 次回复。
     * 本卡负责「你现在是什么状态 + 怎么填自带接口」；话术包另起一节管理。
     */
    private fun buildAuthCenterCard(root: LinearLayout): LinearLayout {
        root.addView(section(t("授权中心", "Authorization")))
        val authCard = card()
        val statusView = text(authStatusText(), 12.5f, ink, bold = true).apply {
            setPadding(0, dp(2), 0, dp(8))
        }
        authCard.addView(statusView)

        // 额度 / 授权进度条（授权模式显示话术包剩余，否则显示今日免费条数）
        authCardRef = authCard
        renderQuotaBar(authCard)

        // 自带接口开关：开启后判断/回复直连用户网关，不再走云端、不计免费条数
        authCard.addView(toggleRow(
            t("使用自带接口（自带 Key / Base URL）",
              "Use my own endpoint (bring your own key / base URL)"),
            prefs.isTextCustom()
        ) { on ->
            prefs.textCustom = on
            renderAuthByo(authCard, statusView)
            statusView.text = authStatusText()
        })
        authCard.addView(text(
            t("开启后判断与回复直连你填的接口，不经过 WeAuto 云端、不计免费条数、不耗 Workers AI 额度。",
              "When on, judging and replies go straight to your endpoint — not via the WeAuto cloud, not counted against the free quota, no Workers AI usage."),
            11f, sub).apply { setPadding(0, dp(4), 0, dp(2)) })

        // BYO 录入区：按开关显示 / 隐藏（首次进入若已开启则直接展开）
        renderAuthByo(authCard, statusView)

        root.addView(authCard)
        return authCard
    }

    /** 自带接口录入区：以 tag="byo" 定位旧区并重建（开关切换时就地刷新）。 */
    private fun renderAuthByo(host: LinearLayout, statusView: TextView) {
        host.findViewWithTag<View>("byo")?.let { host.removeView(it) }
        if (!prefs.textCustom) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "byo"
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(label(t("接口地址（Base URL）", "Base URL")))
        val baseEdit = edit(prefs.textBaseUrl, "https://your-endpoint/v1").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        baseEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.textBaseUrl = s?.toString().orEmpty().trim()
            }
        })
        box.addView(baseEdit)

        box.addView(label(t("API Key（加密保存，不回显）", "API Key (encrypted, not shown)")))
        val keyEdit = edit(if (prefs.textKey.isNotBlank()) "••••••••" else "", "sk-...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString().orEmpty()
                if (v != "••••••••") prefs.textKey = v.trim()   // 占位时不回写，避免清空已存 key
            }
        })
        box.addView(keyEdit)

        box.addView(label(t("模型名（留空 = 用默认）", "Model (blank = default)")))
        val modelEdit = edit(prefs.textModel, "gpt-4o-mini")
        modelEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.textModel = s?.toString().orEmpty().trim()
            }
        })
        box.addView(modelEdit)

        host.addView(box)
    }

    /** 授权 / 免费额度进度条（数据来自 PackLedger）。 */
    private fun freeQuotaBar(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val q = PackLedger.checkQuota(this)
        if (q.licensed) {
            val lic = PackLedger.offlineLicense(this)
            box.addView(text(
                t("话术包授权", "Script-pack licence") + " · ${lic?.remaining ?: 0} / ${q.limit}",
                12.5f, ink, bold = true))
            return box
        }
        val used = q.limit - q.remaining
        val pct = (used * 100 / q.limit).toInt().coerceIn(0, 100)
        box.addView(text(
            t("今日免费额度", "Today's free quota") + " $used / ${q.limit}（$pct%）"
                .replace("（", " (").replace("）", ")"),
            12.5f, ink, bold = true))
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
            t("免费额度快用完了，可在上方开启「自带接口」绕开云端限流。",
              "Free quota almost used — turn on \"Use my own endpoint\" above to bypass the cloud rate limit."),
            11f, danger).apply { setPadding(0, dp(5), 0, 0) })
        // 重置今日免费计数：本地账本手动归零（云端 KV 仍是 30 条/天/设备硬墙，
        // 重置不突破云端上限）。授权模式不显示——授权按包内总次数走。
        box.addView(smallBtn(t("重置今日免费计数", "Reset today's free count")) {
            confirmResetDaily()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        return box
    }

    /**
     * 额度进度条就地刷新：以 tag 定位旧视图移除后插到状态文本之后（索引 1），
     * 避免重置计数后整页 recreate。授权中心卡重建时由 buildAuthCenterCard 调用。
     */
    private fun renderQuotaBar(host: LinearLayout) {
        host.findViewWithTag<View>(TAG_QUOTA_BAR)?.let { host.removeView(it) }
        host.addView(freeQuotaBar().apply { tag = TAG_QUOTA_BAR }, 1)
    }

    /** 重置今日免费计数：二次确认后清本地 count（云端配额不变），就地刷新进度条。 */
    private fun confirmResetDaily() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(t("重置今日免费计数？", "Reset today's free count?"))
            .setMessage(t(
                "只清本机今日计数。云端仍是每天 30 条/设备的硬上限，重置不会突破云端额度。",
                "Clears this device's local counter only. The cloud still enforces 30/day/device — resetting does not raise the cloud limit."))
            .setPositiveButton(t("重置", "Reset")) { _, _ ->
                PackLedger.resetDaily(this)
                authCardRef?.let { renderQuotaBar(it) }
            }
            .setNegativeButton(t("取消", "Cancel"), null)
            .show()
    }

    private fun authStatusText(): String {
        val q = PackLedger.checkQuota(this)
        return if (q.licensed) {
            val lic = PackLedger.offlineLicense(this)
            t("✓ 话术包授权生效，剩余 ${lic?.remaining ?: 0} 次回复",
              "✓ Script-pack licence active — ${lic?.remaining ?: 0} replies left")
        } else if (prefs.isTextCustom()) {
            t("✓ 自带接口已启用，回复直连你填的网关",
              "✓ Your own endpoint is on — replies go to your gateway")
        } else {
            t("免费额度模式：每天 ${q.limit} 条，云端共享网关（峰期限流 429）",
              "Free-quota mode: ${q.limit} per day on the shared cloud gateway (rate-limited 429 at peak)")
        }
    }

    // ---------------------------------------------------------- 5. 关于

    private fun buildAboutCard(root: LinearLayout) {
        root.addView(section(t("关于", "About")))
        val c = card()

        // 自检与诊断仅调试期可见；正式发行版去掉，减少可被逆向利用的内部信息面。
        if (BuildConfig.DEBUG) {
            c.addView(cardBtn(t("自检与诊断", "Diagnostics")) {
                startActivity(android.content.Intent(this, MainActivity::class.java)
                    .putExtra("diag", true))
            })
        }
        c.addView(text(t("助手没反应、上次分析成没成、有没有崩过，都在这里。",
            "Whether the assistant is unresponsive, whether the last analysis succeeded, and any crashes — all here."), 11f, sub))

        val kbResult = resultText()
        c.addView(cardBtn(t("清空知识库与聊天历史", "Clear knowledge base & chat history")) {
            val n = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(t("清空知识库与历史", "Clear knowledge base & history"))
                .setMessage(t("将删除 ${n.notes} 条笔记、${n.contacts} 个联系人、${n.logLines} 条聊天历史。其他设置不受影响，不可恢复。",
                    "This deletes ${n.notes} notes, ${n.contacts} contacts and ${n.logLines} chat history lines. Other settings are unaffected. This cannot be undone."))
                .setPositiveButton(t("清空", "Clear")) { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = t("已清空", "Cleared")
                }
                .setNegativeButton(t("取消", "Cancel"), null)
                .show()
        })
        c.addView(kbResult)

        c.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(14), 0, dp(2)) })
        root.addView(c)
    }

    // ---------------------------------------------------------------- atoms

    private fun applySwitchLook(sw: TextView, on: Boolean) {
        sw.text = if (on) t("开", "ON") else t("关", "OFF")
        sw.setTextColor(if (on) Color.WHITE else sub)
        sw.background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
    }

    private fun switchView(on: Boolean) = TextView(this).apply {
        text = if (on) t("开", "ON") else t("关", "OFF"); textSize = 13f; gravity = Gravity.CENTER
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
            text = if (initial) t("开", "ON") else t("关", "OFF"); textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = (sw.text == t("关", "OFF"))
            sw.text = if (now) t("开", "ON") else t("关", "OFF")
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
            text = if (granted) t("已开启", "On") else t("去开启", "Enable")
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
        t("版本", "Version") + " v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        t("版本", "Version") + " —"
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
        if (!prefs.syncEnabled) return t("云端备份：已关闭", "Cloud backup: off")
        val last = prefs.lastSyncAt
        val ago = if (last <= 0) t("尚未备份", "never backed up") else {
            val mins = (System.currentTimeMillis() - last) / 60000
            when {
                mins < 1 -> t("刚刚备份", "backed up just now")
                mins < 60 -> t("${mins} 分钟前备份", "backed up ${mins} min ago")
                else -> t("${mins / 60} 小时前备份", "backed up ${mins / 60} h ago")
            }
        }
        return t("云端备份：", "Cloud backup: ") + ago + t(" · 设备 ", " · device ") + DeviceId.shortHash(this).take(8)
    }

    private fun doSyncBackup(statusView: TextView) {
        statusView.text = t("正在备份…", "Backing up…")
        worker.execute {
            val res = CloudSync.uploadNow(this, prefs)
            main.post {
                if (isFinishing || isDestroyed) return@post
                statusView.text = when (res) {
                    is SyncClient.Result.Ok -> syncStatusText()
                    SyncClient.Result.NotDeployed -> t("暂时连不上服务器，稍后再试", "Can't reach the server right now — try again later")
                    SyncClient.Result.Empty -> t("云端备份：已关闭", "Cloud backup: off")
                    is SyncClient.Result.Failure -> t("备份失败：", "Backup failed: ") + res.message
                }
            }
        }
    }

    private fun doSyncRestore(statusView: TextView) {
        statusView.text = t("正在恢复…", "Restoring…")
        worker.execute {
            val msg = CloudSync.restoreNow(this, prefs)
            main.post {
                if (isFinishing || isDestroyed) return@post
                statusView.text = msg ?: if (prefs.syncRestored) {
                    t("云端没有更早的数据", "No earlier data in the cloud")
                } else {
                    t("暂时连不上服务器，稍后再试", "Can't reach the server right now — try again later")
                }
            }
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** 额度进度条视图 tag：renderQuotaBar 据此定位旧视图并替换。 */
        private const val TAG_QUOTA_BAR = "quota_bar"
    }
}
