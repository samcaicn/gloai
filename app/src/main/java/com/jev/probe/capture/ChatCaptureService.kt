package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.PaddleOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ConversationHistory
import com.jev.probe.core.ErrCatalog
import com.jev.probe.core.Metrics
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * Write actions are deliberately narrow: ACTION_SET_TEXT (or a clipboard PASTE
 * fallback) fills the chat input box when the user taps "填入", and — only with
 * [Prefs.autoSend] on — the chat's own send button is clicked immediately after.
 * The text is always a candidate the user picked, never something decided here,
 * and nothing is ever sent without a successful fill first.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. WeChat is now wired in: the
     *  capture service is registered under the disguised [SelectToSpeakService]
     *  class name, which is what lets WeChat expose its node tree (an ordinary
     *  service only gets an empty root). Tree-based reading is safe; we only
     *  skip the screenshot/OCR fallback inside WeChat to avoid its anti-screenshot
     *  risk control. [WeChatAdapter] reads the chat and [inputFor] fills the box. */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter(), WeChatAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    /** Screen state (sides+lengths) already auto-answered, so one incoming message
     *  can never trigger two automatic replies. Reset whenever the chat changes. */
    private var lastAutoSentSig: String = ""
    private var activePkg: String? = null
    /** 是否正在分析。跨线程读写（worker 提交 / main 复位）所以必须 volatile。
     *
     *  **任何**退出路径都必须把它复位**：一旦漏掉，`runAnalysis()` 开头的
     *  `if (analyzing) return` 会把之后每一次分析全部挡掉，界面永远停在
     *  「分析中…」——2026-10-05「自动获取对话 / 自动聊天卡死」的根因就在这里。 */
    @Volatile private var analyzing = false

    /** 一轮分析内部的结果，供收尾时写埋点与自检卡（不含任何聊天内容）。 */
    @Volatile private var roundOk = false
    @Volatile private var roundErr: ErrCatalog.Kind = ErrCatalog.Kind.UNKNOWN

    /** 上一次错误卡里的类别：决定「重试」到底是重跑分析还是再截屏识别一次。 */
    private var lastErrKind: ErrCatalog.Kind = ErrCatalog.Kind.UNKNOWN

    /** 最近一次已知悬浮窗权限状态：从 true→false 时只报一次警，避免每个事件都弹 toast。 */
    private var overlayOk = true

    /** 最近一次分析命中 429 限流的时间戳；自动分析冷却期内不再自动触发（手动不受影响）。 */
    private var lastBusyAt = 0L

    /**
     * 错误卡上那个按钮（P0-3 / P1-3）。「重试」按错误类别分流：没读到文字时重试 =
     * 截屏 OCR 一次，其它错误才是重跑一轮分析。
     */
    private fun handleErrorAction(a: ErrCatalog.Action) {
        when (a) {
            ErrCatalog.Action.RETRY -> {
                if (lastErrKind == ErrCatalog.Kind.NO_TEXT) ocrCaptureManual()
                else currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
            }
            ErrCatalog.Action.SETTINGS -> openSettings(focusPlan = false)
            ErrCatalog.Action.PLAN -> openSettings(focusPlan = true)
            ErrCatalog.Action.A11Y -> runCatching {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { overlay?.toast("打不开无障碍设置") }
        }
    }

    /**
     * 把当前会话加入 / 移出白名单（P1-8）。白名单是关键词集合，所以「移出」= 删掉
     * 能匹配上当前标题的那些关键词，而不是删掉一个不存在的等值项。
     */
    private fun toggleWhitelist() {
        val title = currentSnapshot?.title
        if (title.isNullOrBlank()) { overlay?.toast("当前会话没有标题，改不了白名单"); return }
        val wl = prefs.whitelist
        val matched = wl.filter { title.contains(it, ignoreCase = true) }
        if (matched.isEmpty()) {
            prefs.whitelist = wl + title
            overlay?.toast("已加入白名单：$title")
        } else {
            prefs.whitelist = wl - matched.toSet()
            overlay?.toast("已移出白名单（删掉 ${matched.size} 个匹配项）")
        }
    }

    /** 打开设置页；[focusPlan] 时定位到订阅/档位卡片（429 的升级入口走这条路）。 */
    private fun openSettings(focusPlan: Boolean) {
        runCatching {
            startActivity(android.content.Intent()
                .setClassName(this, "com.jev.probe.SettingsActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("focus", if (focusPlan) "plan" else ""))
        }.onFailure { overlay?.toast("打不开设置") }
    }
    private val session = ConversationSession()
    private val analysisTasks = ArrayList<Future<*>>()
    private var destroyed = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "whitelist") {
            main.post {
                leaveConversation()
                overlay?.hide()
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        session.invalidate()
        main.removeCallbacks(debounce)
        main.removeCallbacks(analysisWatchdog)
        pendingSnapshot = null
        analyzing = false
        lastAutoSentSig = ""      // a different conversation may legitimately be answered
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        overlay?.resetForNewConversation()
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        if (session.observe(target)) {
            cancelAnalysis()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
        }
    }

    private fun leaveConversation() {
        observeTarget(null)
        cancelAnalysis()
        currentSnapshot = null
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home") return null
        val adapter = adapters[pkg]
        var messagesSignature: String? = null
        val title = if (adapter != null) {
            val snapshot = adapter.extract(root, resources) ?: return null
            messagesSignature = snapshot.takeIf { it.messages.isNotEmpty() }?.signature()
            // A title we cannot read must not abort the capture. A non-empty
            // message list already proves we are inside a chat window, and some
            // builds never expose the action-bar title at all (WeChat 8.0.78 on
            // ColorOS: the title node is simply absent from the tree), which used
            // to leave the assistant dead on arrival — every event returned here
            // and only the idle bubble was ever shown. Keep the target with a
            // null title: it is a label for the overlay/KB, never the proof.
            val good = snapshot.title?.takeUnless { isTransientTitle(it) }
            if (good == null && messagesSignature == null) return null
            good
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || !session.accepts(token)) return false
        val live = rootInActiveWindow?.let { targetFor(it) }
        if (live != token.target || !prefs.isAllowed(currentSnapshot?.title ?: live.title)) {
            leaveConversation()
            overlay?.hide()
            return false
        }
        return true
    }

    /** Only called on the main thread, including the context-completion callback.
     *
     *  返回 **false = 任务没能排上队**（服务已销毁 / 线程池已关闭）。调用方必须照样
     *  把计数复位，否则 `analyzing` 永远等不到归零，UI 就冻在「分析中…」。 */
    private fun submitAnalysis(task: () -> Unit): Boolean = try {
        analysisTasks.add(worker.submit(task))
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = PaddleOcr
    private var ocrBusy = false

    /** Set by [wxMsgReceiver] when a WeChat notification reports an image message,
     *  so the next capture OCRs the latest picture even if the tree missed it.
     *  Consumed (reset to false) once an image OCR is dispatched. */
    private var imageOcrRequested = false
    /** Dedupe key for image OCR: title + image rect, so we shoot each picture once. */
    private var lastImageOcrSig: String = ""

    /** Tree snapshot captured alongside an OCR-primary pass; if PaddleOCR draws
     *  a blank we fall back to the tree's text so the bubble still has content. */
    private var lastTreeSnapshot: ChatSnapshot? = null

    /** Bridge from [WxNotificationListener]: a new WeChat message arrived. Re-runs
     *  capture when WeChat is foreground; flags image OCR when the message was a
     *  picture. Registered NOT_EXPORTED — only our own app sends this. */
    private val wxMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_WX_MSG) return
            if (intent.getBooleanExtra("image", false)) imageOcrRequested = true
            // The notification preview (sender + text) is the earliest signal we get;
            // fold it into history so a background message is never lost even if the
            // chat never opens or the tree read misses it.
            val sender = intent.getStringExtra("sender")
            val text = intent.getStringExtra("text")
            if (!text.isNullOrBlank()) {
                ConversationHistory.appendFromNotification(
                    PKG_WECHAT, currentSnapshot?.title ?: sender, sender, text,
                    intent.getBooleanExtra("image", false))
            }
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg == PKG_WECHAT) {
                main.post { if (prefs.enabled) runCatching { maybeCapture() } }
            }
        }
    }

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        overlay = OverlayController(this)
        Metrics.init(this, prefs.metricsEnabled)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        overlay?.onErrorAction = { a -> handleErrorAction(a) }
        overlay?.onReplyFeedback = { rank, good ->
            Metrics.log("reply_feedback", "rank" to rank, "good" to good,
                "conv" to Metrics.hash(currentSnapshot?.title),
                "pkg" to (activePkg ?: ""))
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // 白名单（P1-8）：用户最常问的是「为什么这里不分析」，把开关摆到手边。
        overlay?.onWhitelistToggle = { toggleWhitelist() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { PaddleOcr.warmUp(this) }
        // Restore cross-session conversation history (survives process death).
        ConversationHistory.attach(this)
        // Listen for new WeChat messages from the NotificationListenerService.
        runCatching {
            ContextCompat.registerReceiver(
                this, wxMsgReceiver, IntentFilter(ACTION_WX_MSG),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Live foreground state for the notification listener (reliable, no extra perms).
        weChatForeground = rootInActiveWindow?.packageName?.toString() == PKG_WECHAT
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        // 悬浮窗权限被系统收回（用户手动关掉、或 ROM 把设置重置）时，气泡会无声消失，
        // 用户只看到「助手不工作了」却不知为什么。这里只报一次警；权限回来后复位，
        // 并强制重建悬浮窗——系统已销毁旧 window，必须重 add 而非复用已分离的旧 root。
        val canOverlayNow = Settings.canDrawOverlays(this)
        if (canOverlayNow) {
            if (!overlayOk) { overlayOk = true; overlay?.resetWindow() }
        } else if (overlayOk) {
            overlayOk = false
            Toast.makeText(this, "悬浮窗权限被收回，去设置重新开启后助手才能显示", Toast.LENGTH_LONG).show()
        }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg !in adapters) {
                val target = rootInActiveWindow?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                foregroundPkg = fg
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) overlay?.hide() else overlay?.showIdle(null)
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        lastTreeSnapshot = null // fresh capture cycle; only the OCR-primary branch re-sets it
        val root = rootInActiveWindow ?: run { leaveConversation(); overlay?.hide(); return }
        val pkg = root.packageName?.toString()
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) { leaveConversation(); overlay?.showIdle(null); return }
        val target = targetFor(root)
        if (target == null) { leaveConversation(); overlay?.showIdle(null); return }
        observeTarget(target)
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            // WeChat hides its screenshot under risk control — skip the screenshot
            // OCR fallback there; the tree read already ran above, so park the bubble.
            if (prefs.ocrFallback && pkg != PKG_WECHAT) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        // Fold the visible bubbles into cross-session history so the judge later
        // sees the whole thread (history + screen), not just what fits on screen.
        ConversationHistory.appendFromSnapshot(pkg ?: "", snapshot.title, snapshot.messages)
        // Image message from the other person: OCR the picture to read its text,
        // then analyze. This is the ONLY screenshot we take inside WeChat (a normal
        // screenshot there trips its risk control; the accessibility screenshot API
        // is a separate path). Dedupe by image rect so we don't re-shoot per event.
        if (pkg == PKG_WECHAT && prefs.ocrFallback && prefs.ocrImages &&
            snapshot.imageMessage != null && (snapshot.imageIsLatest || imageOcrRequested)
        ) {
            val img = snapshot.imageMessage!!
            val sig2 = "${snapshot.title}|${img.rect.left},${img.rect.top},${img.rect.right},${img.rect.bottom}"
            if (sig2 != lastImageOcrSig && !ocrBusy) {
                lastImageOcrSig = sig2
                imageOcr(img, snapshot.title, pkg)
                return
            }
        }
        // OCR-primary: read each bubble via local PaddleOCR (accessibility
        // screenshot API — safe inside WeChat, no risk-control trigger). The tree
        // still supplies bubble geometry + sides; its text is the fallback when
        // OCR returns empty. This is what makes "优先本地 OCR" actually run in the
        // main WeChat flow instead of being skipped for a tree-only read.
        if (prefs.ocrPrimary && snapshot.bubbleRects.isNotEmpty()) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            val sig3 = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
            if (sig3 == lastOcrSignature && overlay?.isShowing() == true) return
            if (ocrBusy) return
            lastOcrSignature = sig3
            lastTreeSnapshot = snapshot
            ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            return
        }
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { overlay?.showIdle(snapshot.title); return }
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        cancelAnalysis()
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        // 429 冷却期内不再自动分析（见 BUSY_COOLDOWN_MS）。避免无人值守模式下反复打共享
        // 网关、既浪费额度又加剧限流。手动「重新分析」走 runAnalysis 直接调用，不经此处，
        // 所以用户点重试仍能立即再试，不受冷却影响。
        if (lastBusyAt != 0L && System.currentTimeMillis() - lastBusyAt < BUSY_COOLDOWN_MS) {
            overlay?.showIdle(snapshot.title); return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /**
     * A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing || destroyed || !prefs.enabled) return
        val previous = session.token() ?: return
        if (!isCurrent(previous)) return
        if (!prefs.hasKey()) { overlay?.showOcrResult(snapshot.messages, "未设置判断接口密钥，去设置里填"); return }
        val token = session.begin() ?: return
        analyzing = true
        roundOk = false
        roundErr = ErrCatalog.Kind.UNKNOWN
        Metrics.markStart("analysis")
        Metrics.log("analysis_start",
            "pkg" to (activePkg ?: foregroundPkg ?: ""),
            "conv" to Metrics.hash(snapshot.title),
            "n" to snapshot.messages.size,
            "src" to if (snapshot.note.isNullOrBlank()) "tree" else "ocr")
        // Merge the persisted cross-session history into the visible snapshot so the
        // judge sees the full thread, not only the bubbles currently on screen.
        val augmented = ConversationHistory.mergeInto(snapshot, token.target.pkg)
        overlay?.showLoading()
        overlay?.setNote(snapshot.note)
        overlay?.setStatus("整理上下文…")
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submitAnalysis {
            val ctx = try {
                ContextBuilder.build(this, augmented, token.target.pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (!isCurrent(token)) {
                    // 会话已失效（切了聊天 / 关了开关 / token 过期）。必须在这里复位，
                    // 因为下面两个任务压根不会被提交，没人会去归零 analyzing。
                    analyzing = false
                    analysisTasks.clear()
                    return@post
                }
                overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                overlay?.setStatus("判断意图…")
                var remaining = 2
                fun completed() {
                    remaining--
                    if (remaining <= 0) {
                        analyzing = false
                        analysisTasks.clear()
                        main.removeCallbacks(analysisWatchdog)
                        finishRound()
                        Log.d(TAG, "analysis round finished")
                    }
                }
                // 兜底看门狗：不管走哪条路径，超时没归零就强制放开，
                // 绝不允许把面板永久冻在「分析中…」。
                main.postDelayed(analysisWatchdog, ANALYSIS_TIMEOUT_MS)

                val judgeQueued = submitAnalysis {
                    val judgment = client.judge(augmented, rel, ctx)
                    main.post {
                        // 结果归类放在 isCurrent **外面**：无论会话还匹不匹配，这一轮
                        // 是成是败都要如实记进埋点与自检卡，否则统计会漏掉失败。
                        if (judgment.error != null) {
                            val v = ErrCatalog.classify(judgment.error)
                            roundErr = v.kind
                            lastErrKind = v.kind
                            Log.i(TAG, "judge failed: ${v.kind.id}")
                            // 只有 UI 更新需要会话仍然匹配（旧代码把收尾也关在里面，
                            // 会话一失效就永久卡死——2026-10-05 的卡死根因）。
                            if (isCurrent(token)) overlay?.showOcrResult(augmented.messages, failure = v)
                        } else {
                            roundOk = true
                            if (isCurrent(token)) overlay?.showJudgment(judgment)
                        }
                        completed()
                    }
                }
                val replyQueued = submitAnalysis {
                    var replyError: String? = null
                    val ranked = try { client.draftAndRank(augmented, rel, ctx) } catch (e: Exception) {
                        replyError = e.message ?: e.javaClass.simpleName
                        emptyList()
                    }
                    main.post {
                        if (replyError != null && roundErr == ErrCatalog.Kind.UNKNOWN) {
                            roundErr = ErrCatalog.classify(replyError).kind
                        }
                        if (isCurrent(token)) {
                            overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
                            // 真·无人值守（微信）：worker/jev 返回候选后直接按打分最高的
                            // 一条填入，autoSend 开着就再延迟 sendDelayMs（默认 2s）发出。
                            maybeAutoSendBest(token, augmented, ranked, replyError)
                        }
                        completed()
                    }
                }
                // 任务没排上队（服务已销毁）→ 手动补计数，别让 analyzing 空等。
                if (!judgeQueued) completed()
                if (!replyQueued) completed()
            }
        }
    }

    /**
     * 兜底：一轮分析超过 [ANALYSIS_TIMEOUT_MS] 仍未归零，强制复位 [analyzing] 并把面板
     * 从「分析中…」放开。正常路径下 [completed] 会把它取消掉；只有真出了意外才轮到它。
     */
    private val analysisWatchdog = Runnable {
        if (!analyzing) return@Runnable
        Log.w(TAG, "analysis watchdog fired after ${ANALYSIS_TIMEOUT_MS}ms — force reset")
        analysisTasks.forEach { runCatching { it.cancel(true) } }
        analysisTasks.clear()
        analyzing = false
        roundErr = ErrCatalog.Kind.TIMEOUT
        lastErrKind = ErrCatalog.Kind.TIMEOUT
        Metrics.log("analysis_timeout", "conv" to Metrics.hash(currentSnapshot?.title))
        overlay?.showFailure(ErrCatalog.classify("分析超时"))
    }

    /**
     * 一轮分析结束（无论成败）：写埋点 + 刷新自检卡要用的三个数字。
     * 只记耗时、成败、错误类别和会话摘要，**没有任何聊天内容**。
     */
    private fun finishRound() {
        val ms = Metrics.markEnd("analysis", "analysis_end",
            "ok" to roundOk,
            "err" to roundErr.id,
            "pkg" to (activePkg ?: foregroundPkg ?: ""),
            "conv" to Metrics.hash(currentSnapshot?.title))
        if (ms != null) prefs.lastAnalysisMs = ms
        prefs.lastAnalysisOk = roundOk
        prefs.lastAnalysisErr = if (roundOk) "" else roundErr.id
        // 命中 429 限流：记下时间戳，自动分析进入冷却（见 BUSY_COOLDOWN_MS），
        // 不让无人值守模式反复撞同一道限流墙。
        if (roundErr == ErrCatalog.Kind.BUSY) lastBusyAt = System.currentTimeMillis()
    }

    /**
     * Unattended reply: take the top-ranked candidate the worker/jev route produced,
     * drop it into the chat's input box, and let [Prefs.autoSend] fire it after
     * [Prefs.sendDelayMs].
     *
     * Two guards keep this from misbehaving, because a wrong send here talks to a
     * real person:
     *  - the same screen state is only ever auto-answered once (a bubble whose side
     *    got misread as "other" would otherwise re-trigger this forever);
     *  - a non-empty input box means the human is typing — never stomp on that.
     */
    private fun maybeAutoSendBest(
        token: ConversationSession.Token,
        snapshot: ChatSnapshot,
        ranked: List<RankedReply>,
        replyError: String?
    ) {
        if (!prefs.autoFillBest) return
        if (token.target.pkg != PKG_WECHAT) return
        if (replyError != null || ranked.isEmpty()) return
        if (snapshot.latestFrom != "other") return
        val best = ranked.maxByOrNull { it.prob } ?: ranked.first()
        val text = best.text.trim()
        if (text.isEmpty()) return
        val sig = snapshot.signature()
        if (sig == lastAutoSentSig) {
            Log.i(TAG, "auto-send skipped: already answered this screen state")
            return
        }
        val draft = inputFor(token)?.text?.toString()?.trim()
        if (!draft.isNullOrEmpty()) {
            Log.i(TAG, "auto-send skipped: input box not empty")
            return
        }
        lastAutoSentSig = sig
        Log.i(TAG, "auto-send best(${(best.prob * 100).toInt()}%): ${text.take(24)}")
        main.postDelayed({ fillInput(token, text) }, 500L)
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // WeChat's anti-screenshot risk control makes an in-WeChat screenshot
        // unreliable and risky, so manual OCR is disabled there; tree capture
        // (the bubble's auto analysis) still works.
        if (pkg == PKG_WECHAT) { overlay?.toast("微信内暂不支持截屏识别，已用树读取"); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.toast("无法确认当前会话，请等待标题加载后重试")
            return
        }
        observeTarget(target)
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy || destroyed || !prefs.enabled) return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        ocrBusy = true
        Metrics.markStart("ocr")
        overlay?.setStatus("截屏识别…")
        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    Metrics.markEnd("ocr", "ocr_end", "ok" to false, "code" to res.code)
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, token)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        ocrBusy = false
        if (!isCurrent(token)) return
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        Metrics.markEnd("ocr", "ocr_end",
            "ok" to snapshot.messages.isNotEmpty(),
            "n" to snapshot.messages.size,
            "manual" to manual)
        if (snapshot.messages.isEmpty()) {
            if (manual) {
                lastErrKind = ErrCatalog.Kind.NO_TEXT
                overlay?.showFailure(ErrCatalog.classify("这一屏没认出文字"))
                return
            }
            // OCR drew a blank: fall back to the tree text captured alongside so the
            // bubble still has content (local OCR just couldn't read these pixels).
            if (prefs.ocrPrimary) {
                val tree = lastTreeSnapshot
                if (tree != null && tree.messages.isNotEmpty()) {
                    lastTreeSnapshot = null
                    finishOcrSnapshot(tree.copy(note = "本地OCR未识别，已回退树读取"), pkg, manual, token)
                    return
                }
            }
            return
        }
        lastTreeSnapshot = null
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        cancelAnalysis()
        lastSignature = sig

        // 进对话/内容变化即自动分析（不再要求"对方最新消息"）：打开微信对话
        // 就直接出结果，配合面板"分析中…"提示（手动按钮已按需求移除）。
        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.twitter.android" -> findEditable(root)
            PKG_WECHAT -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) return
            if (ok) {
                // sendFor announces the countdown itself; don't double-toast here.
                if (prefs.autoSend) sendFor(token)
                else overlay?.toast("已填入，确认后自己发送")
            } else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isCurrent(token)) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (inputFor(token) == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                inputFor(token)?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    /**
     * Click the chat's send button after a successful fill. Called only when
     * [Prefs.autoSend] is on — the reply text itself is still the one the user
     * picked from the candidates, so this sends what they chose, not something the
     * AI decided on its own.
     *
     * Waits [Prefs.sendDelayMs] (default 2s) before clicking: WeChat needs a beat
     * to commit the draft and flip its send control out of the voice/emoji state —
     * clicking sooner lands on nothing and the message silently never goes out.
     */
    private fun sendFor(token: ConversationSession.Token) {
        val delay = prefs.sendDelayMs.toLong()
        if (delay > 0) overlay?.toast("已填入，${delay / 1000} 秒后自动发送…")
        main.postDelayed({
            if (!isCurrent(token)) return@postDelayed
            val root = rootInActiveWindow ?: return@postDelayed
            if (targetFor(root) != token.target) return@postDelayed
            val send = findSendButton(root, token.target.pkg) ?: run {
                overlay?.toast("已填入，未能自动发送，请手动点发送"); return@postDelayed
            }
            if (!(send.refresh() && send.isVisibleToUser && send.isEnabled)) {
                overlay?.toast("已填入，请手动点发送"); return@postDelayed
            }
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            overlay?.toast("已自动发送")
            // Confirm the input cleared (message actually went out); retry once if not.
            main.postDelayed({
                if (!isCurrent(token)) return@postDelayed
                val input = inputFor(token)
                if (input != null && !input.text.isNullOrBlank()) {
                    findSendButton(rootInActiveWindow ?: return@postDelayed, token.target.pkg)
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }, 800L)
        }, delay)
    }

    /** The chat's send control. WeChat uses a stable view-id; others fall back to a
     *  clickable node whose description reads "发送" / "Send". */
    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        if (pkg == PKG_WECHAT) {
            root.findAccessibilityNodeInfosByViewId(WeChatAdapter.SEND_ID)
                .firstOrNull()?.takeIf { it.refresh() }?.let { return it }
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val cd = node.contentDescription?.toString().orEmpty()
            if (node.isClickable && (cd.contains("发送") || cd.contains("Send", true))) {
                if (node.refresh()) return node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    /**
     * OCR a single image message to read the text inside the picture (the only
     * place we screenshot inside WeChat). On success the recognized text is merged
     * into the current snapshot as an "other" message and analysis runs; an empty
     * picture (sticker, no text) is silently ignored.
     */
    private fun imageOcr(rect: BubbleRect, title: String?, pkg: String) {
        if (ocrBusy || destroyed || !prefs.enabled) return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        // Consume the notification flag only once we are really shooting: bailing
        // out above must not swallow it, or the next image would never be OCR'd.
        imageOcrRequested = false
        ocrBusy = true
        // Which dedupe key to roll back if this attempt fails. A failed screenshot
        // is transient (WeChat was mid-transition, capture got cancelled), so the
        // same picture must stay eligible for a retry — otherwise one bad shot
        // disables image OCR for that bubble forever. An EMPTY result is different:
        // the picture genuinely carries no text, so keep it marked as done.
        val sig = lastImageOcrSig
        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) runCatching { res.bitmap.recycle() }
                ocrBusy = false
                // We left the chat mid-shot: nothing was read, so let a later
                // return to this conversation retry it.
                if (lastImageOcrSig == sig) lastImageOcrSig = ""
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "imgOcr: screenshot failed code=${res.code}")
                    // Transient failure (capture cancelled, screen mid-transition)
                    // → allow a retry instead of blacklisting this picture for good.
                    if (lastImageOcrSig == sig) lastImageOcrSig = ""
                    // maybeCapture returned early to get here, so nothing else would
                    // put the bubble back on screen — do it ourselves.
                    overlay?.showIdle(title)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    val sx = if (ocr.scaleX > 0f) ocr.scaleX else 1f
                    val sy = if (ocr.scaleY > 0f) ocr.scaleY else 1f
                    val region = Rect(
                        ((rect.rect.left - ocr.originX) * sx).toInt(),
                        ((rect.rect.top - ocr.originY) * sy).toInt(),
                        ((rect.rect.right - ocr.originX) * sx).toInt(),
                        ((rect.rect.bottom - ocr.originY) * sy).toInt())
                    ocr.recognize(res.bitmap, region) { lines ->
                        runCatching { res.bitmap.recycle() }
                        ocrBusy = false
                        val text = lines.joinToString("\n") { it.text }.trim()
                        if (text.isEmpty()) {
                            Log.i(TAG, "imgOcr: no text in picture")
                            overlay?.showIdle(title) // see the Failed branch
                            return@recognize
                        }
                        val base = currentSnapshot ?: ChatSnapshot(title, emptyList())
                        val merged = base.copy(
                            messages = base.messages + Msg("other", "【图片】$text"),
                            note = "图片里的文字已用 OCR 读取")
                        // Keep the OCR'd text on the live snapshot so a later fill /
                        // send sees it, not just the analysis that follows.
                        currentSnapshot = merged
                        // Same gate as the tree path instead of analyzing
                        // unconditionally: only when this is a new message from the
                        // other person and auto-analysis is on. Otherwise park the
                        // bubble carrying the OCR'd text.
                        if (prefs.autoAnalyze && merged.latestFrom == "other") {
                            pendingSnapshot = merged
                            main.removeCallbacks(debounce)
                            runAnalysis()
                        } else {
                            overlay?.setNote(merged.note)
                            overlay?.showIdle(merged.title)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        // The flag lives in the companion, so it outlives this instance. Left true
        // it would tell the notification listener "WeChat is already open" and
        // silently disable auto-opening the chat for good.
        weChatForeground = false
        runCatching { unregisterReceiver(wxMsgReceiver) }
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onWhitelistToggle = null
        overlay?.onErrorAction = null
        overlay?.onReplyFeedback = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Sent by [WxNotificationListener] when a new WeChat message arrives. */
        const val ACTION_WX_MSG = "com.jev.probe.action.WX_MSG"

        /** Live: is WeChat the foreground app right now? Updated every accessibility
         *  event so [WxNotificationListener] can decide whether to auto-open a chat
         *  without needing a restricted usage/running-tasks permission. */
        @Volatile var weChatForeground: Boolean = false

        /** 一轮分析（判断 + 候选回复各打一次 LLM）的兜底上限。判定接口最坏 3 次重试
         *  × 20s 读超时，90s 足够宽；超时由 analysisWatchdog 强制复位 [analyzing]。 */
        private const val ANALYSIS_TIMEOUT_MS = 90_000L

        /** 429 限流后的自动分析冷却时长：这段时间内不再自动触发分析（手动「重新分析」
         *  不受影响）。避免无人值守模式下反复打共享网关、既浪费额度又加剧限流。 */
        private const val BUSY_COOLDOWN_MS = 60_000L

        /** WeChat's package. Now wired in via [WeChatAdapter]; the capture service
         *  is disguised as SelectToSpeakService so WeChat exposes its node tree.
         *  Screenshots inside WeChat are still avoided (risk control). */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
