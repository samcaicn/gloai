package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.CrashLog
import com.jev.probe.core.ErrCatalog
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** 错误卡上那个按钮被点了（去设置 / 看档位 / 重试 / 无障碍设置）。 */
    var onErrorAction: ((ErrCatalog.Action) -> Unit)? = null

    /** 候选回复的「有用 / 没用」反馈（P1-6）：只有排序与是否被采纳，绝不回传正文。 */
    var onReplyFeedback: ((rank: Int, good: Boolean) -> Unit)? = null

    /** 悬浮球菜单 → 把当前会话加入 / 移出白名单（P1-8）。 */
    var onWhitelistToggle: (() -> Unit)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** 悬浮球菜单 → 在 App 内打开「诊断与自检」，不用退出当前聊天就能看到权限/上次分析/崩溃。 */
    var onDiagnostics: (() -> Unit)? = null

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null

    /** 崩溃提示是否已在本会话弹过一次，避免每次 idle 都读盘+弹 toast。 */
    private var crashNoted = false

    /** 空闲态去重：同一状态只渲染一次，避免每次无障碍事件都重绘 / 反复弹开面板。 */
    private var lastIdleKey: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = "Jev"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 58, 122, 254))
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "Jev 分析"; setTextColor(Color.parseColor("#111827")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var downTime = 0L; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false; downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        // 最常用：随时手动分析当前对话（关掉自动分析后这几乎是唯一的触发入口）。
        menu.addView(menuItem("分析当前对话") { root?.removeView(menu); onManualAnalyze?.invoke() })
        // 自动收发总开关：自动发送是不可逆的（消息真发出去了），所以把「暂停 / 恢复」
        // 摆在气泡菜单一步可达的位置——用户不必跳设置就能立刻收回控制权。
        val autoOn = prefs.autoSend && prefs.autoFillBest
        menu.addView(menuItem(if (autoOn) "暂停自动收发" else "开启自动收发") {
            root?.removeView(menu)
            val on = !autoOn
            prefs.autoFillBest = on
            prefs.autoSend = on
            toast(if (on) "已开启自动收发：对方发消息→我读→自动回复并发送"
                  else "已暂停自动收发：只给建议并自动填好，发送由你点")
        })
        menu.addView(menuItem("截屏识别一次") { root?.removeView(menu); onOcrCapture?.invoke() })
        menu.addView(menuItem("把当前会话存为联系人") { onSaveContact?.invoke(); root?.removeView(menu) })
        if (onWhitelistToggle != null) {
            menu.addView(menuItem("白名单：加入 / 移出当前会话") { root?.removeView(menu); onWhitelistToggle?.invoke() })
        }
        menu.addView(menuItem("诊断与自检") { root?.removeView(menu); onDiagnostics?.invoke() })
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("隐藏助手（本次）") { hide() })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#111827")); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.jev.probe.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high (clear of the
            // input box), regardless of which edge the bubble was snapped to.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
        }
        android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    /**
     * 空闲态（没在跑分析、也没结果可展示）。
     *
     * 体验上两个坑要填：
     * 1）首次出现的悬浮球只是个半透明小圆点，用户不知道它是干嘛的——所以第一次
     *    用一张卡把产品讲清楚（[prefs.onboarded] 控制只展示一次，且首次自动展开）。
     * 2）关掉「自动分析」后，气泡菜单里并没有「分析当前对话」入口，点气泡又只会
     *    弹空面板——等于无处可点。这里在空闲卡里常驻一个手动分析按钮，保证总有路可走。
     *
     * 用 [lastIdleKey] 去重：同一状态只渲染一次，避免每次无障碍事件都重绘 / 反复弹开面板。
     * 空闲卡不自动展开（只刷新内容），用户点一下气泡才看得到分析入口，不打扰。
     */
    fun showIdle(title: String?) {
        ensureRoot()
        bubble?.alpha = 0.9f
        // 崩溃闭环（P0-5）：本机存在崩溃记录时，整个会话只提示一次，引导用户去诊断页查看。
        if (!crashNoted) {
            crashNoted = true
            runCatching {
                if (CrashLog.text(ctx) != null) toast("检测到崩溃记录，可点悬浮球菜单「诊断与自检」查看")
            }
        }
        val key = if (prefs.onboarded) "idle" else "onboard"
        if (key == lastIdleKey) return
        val views = ArrayList<View>()
        if (!prefs.onboarded) {
            views.add(line("我是 AI 聊天助手", "#3A7AFE", 15f, true))
            views.add(hint("在聊天 App 里，我读对方最新的消息，给你「对方意图」判断和几条回复建议。\n\n· 微信：对方发消息我就自动读并生成回复，可「自动填入并发送」；配合通知读取，你不在手机旁也能收发\n· QQ / 飞书等：给建议并把最佳回复自动填好，你点一下发送\n· 点气泡：随时手动分析 · 长按气泡：暂停自动 / 诊断 / 设置"))
            views.add(bigButton("开启自动收发并开始") {
                prefs.onboarded = true
                prefs.autoFillBest = true
                prefs.autoSend = true
                toast("已开启自动收发：对方发消息我就自动处理。随时长按气泡可暂停")
                if (expanded) toggle()
            })
            // 次要选项：只给建议并自动填好，发送仍由用户点——同一张卡里把选择权交回用户。
            views.add(TextView(ctx).apply {
                text = "先只看建议，不自动发送"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(dp(10), dp(12), dp(10), dp(6))
                setOnClickListener {
                    prefs.onboarded = true
                    toast("已就绪：给建议并自动填好，发送由你点。设置里可随时开启自动发送")
                    if (expanded) toggle()
                }
            })
            setContent(views)
            if (contentBox != null) lastIdleKey = key // 只在悬浮窗真建好后记状态
            if (!expanded) toggle() // 首次自动展开说明卡
        } else {
            if (title != null) {
                views.add(hint("「$title」已就绪。点下面按钮可随时手动分析当前对话。"))
            } else {
                views.add(hint("已就绪。点这个气泡可随时手动分析当前对话。"))
            }
            views.add(bigButton("分析当前对话") { onManualAnalyze?.invoke() })
            setContent(views) // 不自动展开：气泡亮着，用户点一下才看得到入口
            if (contentBox != null) lastIdleKey = key
        }
    }

    /**
     * Drop whatever judgment/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover [lastJudgment] from a prior conversation can keep [showIdle]
     * from putting the "分析当前对话" button back, and a leftover [lastFill]
     * could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        lastJudgment = null
        lastFill = null
        noteText = null
        replyError = null
        contentBox?.removeAllViews()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Color.parseColor("#3A7AFE"))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /** 「分析中…」那一行。阶段变化时直接改它，不重建整个面板。 */
    private var statusLine: TextView? = null

    fun showLoading() {
        ensureRoot(); bubble?.alpha = 1f
        ctxNotes = 0; ctxHistory = 0   // counts for the round that is starting
        replyError = null              // this round has not failed (yet)
        statusLine = hint("分析中…")
        setContent(listOf(statusLine!!))
        if (!expanded) toggle()
    }

    /**
     * 更新面板顶部的阶段（P0-2 状态可见）：「读界面…」→「识别文字…」→「判断中…」→「写候选…」。
     * 面板不在 loading 态时（已经出了结果）调用会被忽略。
     */
    fun setStatus(text: String) {
        statusLine?.text = text
    }

    /** How many knowledge notes / history lines went into the pending analysis. */
    fun setContextInfo(notes: Int, history: Int) {
        ctxNotes = notes; ctxHistory = history
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    fun setHiddenForShot(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** 兼容旧调用：把原始错误串归一成一张带动作的错误卡。 */
    fun showError(msg: String) = showFailure(ErrCatalog.classify(msg))

    /**
     * 错误卡（P0-3 / P1-3）：一句人话说明「发生了什么」，加一个**能立刻做**的按钮。
     * 429 走的是「看看专属额度」而不是「重试」——用户正吃共享网关的苦头，
     * 这时候给升级入口才有用，让他重试只会再撞一次限流。
     */
    fun showFailure(v: ErrCatalog.View) {
        ensureRoot(); bubble?.alpha = 1f
        statusLine = null
        val views = ArrayList<View>()
        views.add(line(v.title, "#DC2626", 14f, true))
        views.add(hint(v.message))
        views.add(bigButton(v.actionLabel) { onErrorAction?.invoke(v.action) })
        // 重试永远保留：动作按钮是「更有用的那一步」，不是唯一出路。
        views.add(reAnalyzeBtn())
        setContent(views)
        if (!expanded) toggle()
    }

    /**
     * 判断接口（LLM）不可用时的本地兜底：把本地 OCR / 无障碍树读到的对话直接
     * 显示出来，让用户即使没网、没额度、没 key 也能看到探针"读到了什么"。
     * reason 为非空时附一行判断失败的原因，并在底部提供"重新分析"按钮。
     */
    fun showOcrResult(messages: List<Msg>, reason: String? = null, failure: ErrCatalog.View? = null) {
        ensureRoot(); bubble?.alpha = 1f
        val views = ArrayList<View>()
        views.add(line("本地识别到的对话（无需联网）", "#3A7AFE", 14f, true))
        if (messages.isEmpty()) {
            views.add(hint("（这一屏没认出文字）"))
        } else {
            var lastSide = ""
            for (m in messages) {
                val side = m.side
                val label = if (side == "me") "我" else "对方"
                val prefix = if (side != lastSide) "$label：" else ""
                lastSide = side
                val color = if (side == "me") "#1F2937" else "#374151"
                views.add(line("$prefix${m.text}", color, 13f))
            }
        }
        if (failure != null) {
            // 判断挂了但内容读到了：先把读到的对话留给用户，再摆上「发生了什么 + 能做的一步」
            //（429 时那一步是「看看专属额度」，让他重试只会再撞一次限流）。
            views.add(divider())
            views.add(line(failure.title, "#DC2626", 13f, true))
            views.add(hint(failure.message))
            views.add(bigButton(failure.actionLabel) { onErrorAction?.invoke(failure.action) })
        } else {
            reason?.let { views.add(hint("⚠ 判断接口暂不可用：$it")) }
        }
        statusLine = null
        views.add(bigButton("重新分析") { onManualAnalyze?.invoke() })
        setContent(views)
        if (!expanded) toggle()
    }

    /**
     * A neutral one-time notice (used when the foreground is WeChat, which is
     * fully disabled). Not framed as an error: shows the bubble, drops any stale
     * judgment from the previous chat, puts the message in the panel and opens it
     * once so the user actually reads it. Never auto-dismisses (unlike a toast)
     * and never takes input focus (the overlay window is FLAG_NOT_FOCUSABLE).
     */
    fun showNotice(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        resetForNewConversation()
        statusLine = null
        setContent(listOf(
            line("提示", "#3A7AFE", 14f, true),
            hint(msg)))
        if (!expanded) toggle()
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null; expanded = false
        crashNoted = false
        lastIdleKey = null
    }

    /**
     * 强制丢弃当前 window 引用（系统已销毁它，例如悬浮窗权限被收回时），让下次
     * [ensureRoot] 干净地重建，而不是复用已分离的旧 view——后者会让 updateViewLayout
     * 在已移除的 window 上抛异常。比 [hide] 轻：不依赖 root 是否还挂着。
     */
    fun resetWindow() {
        runCatching { root?.let { wm.removeView(it) } }
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null; expanded = false
        crashNoted = false
        lastIdleKey = null
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        statusLine = null
        val views = ArrayList<View>()

        // 云端额度（P1-4）：数字是接口响应头给的，本地只展示。没额度信息时不占版面。
        val quota = prefs.billingQuota
        if (quota > 0) views.add(hint("本月额度 ${prefs.billingUsed} / $quota"))

        // 合规角标：自动发送开着时必须让用户一眼看见，不能只在设置页里写着。
        if (prefs.autoSend && prefs.autoFillBest) {
            views.add(line("⚠ 自动发送已开启（仅微信）", "#DC2626", 12f, bold = true))
        }

        // What context this read was based on (knowledge base / remembered history).
        views.add(hint(
            if (ctxNotes == 0 && ctxHistory == 0) "未用知识库"
            else "知识库 $ctxNotes 条 · 历史 $ctxHistory 条"))

        // How this snapshot was captured, when it changes how to read it.
        noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }

        // Danger badge — the alarm signal, up top and color-coded.
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            views.add(dangerBadge(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            views.add(line("对方真实意图：${INTENT[it.choice] ?: it.choice}", "#111827", 15f, true))
            views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }

        views.add(divider())
        views.add(line("候选回复（Jev 排序）", "#9CA3AF", 12f))
        if (generating) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt(), fill))
            }
            if (a.rankedReplies.isEmpty()) {
                val msg = replyError?.let { "回复接口出错：$it" } ?: "（未生成候选回复）"
                views.add(hint(msg))
            }
        }
        views.add(reAnalyzeBtn())

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int, onFill: (String) -> Unit): View {
        val top = rank == 1
        val cardBg = if (top) Color.parseColor("#EAF1FF") else Color.parseColor("#F3F4F6")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = "#$rank · ${pct}%"; setTextColor(Color.parseColor("#3A7AFE")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#111827")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        btns.addView(pill("填入", true) { android.util.Log.d("JEVASSIST", "overlay: fill tapped"); onFill(text); if (expanded) toggle() })
        // P1-6：只回传「第几名 + 有没有用」，不回传正文，用来校准排序。
        if (onReplyFeedback != null) {
            btns.addView(feedbackPill("有用") { onReplyFeedback?.invoke(rank, true); toast("谢谢，已记下") })
            btns.addView(feedbackPill("没用") { onReplyFeedback?.invoke(rank, false); toast("谢谢，已记下") })
        }
        c.addView(btns)
        return c
    }

    /** 反馈用的小号弱化按钮，跟主操作（复制 / 填入）在视觉上分开。 */
    private fun feedbackPill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#3A7AFE"))
        background = card(18, if (primary) Color.parseColor("#3A7AFE") else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
