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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
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
    private var activePkg: String? = null
    private var analyzing = false
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
        pendingSnapshot = null
        analyzing = false
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
            // A loading/unknown title cannot prove which conversation is open.
            snapshot.title?.takeUnless { isTransientTitle(it) } ?: return null
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

    /** Only called on the main thread, including the context-completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
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
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** Set by [wxMsgReceiver] when a WeChat notification reports an image message,
     *  so the next capture OCRs the latest picture even if the tree missed it.
     *  Consumed (reset to false) once an image OCR is dispatched. */
    private var imageOcrRequested = false
    /** Dedupe key for image OCR: title + image rect, so we shoot each picture once. */
    private var lastImageOcrSig: String = ""

    /** Bridge from [WxNotificationListener]: a new WeChat message arrived. Re-runs
     *  capture when WeChat is foreground; flags image OCR when the message was a
     *  picture. Registered NOT_EXPORTED — only our own app sends this. */
    private val wxMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_WX_MSG) return
            if (intent.getBooleanExtra("image", false)) imageOcrRequested = true
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
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
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
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
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

    // -------------------------------------------------------------- DIAG
    // TEMPORARY node-visibility probe: answers "is the target app's tree empty,
    // or is it there with its resource-ids stripped?". Throttled to one report
    // per 3 s so a chatty window cannot flood logcat. Prints counts, booleans and
    // resource-ids only — never message text.
    private var lastDiagAt = 0L

    /** One-line diag for the paths that never reach [diagReport] (root null, or a
     *  foreground package with no adapter). Shares the same throttle. */
    private fun diagSimple(msg: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDiagAt < 3000) return
        lastDiagAt = now
        Log.d(TAG, "diag[$msg] focus=${rootInActiveWindow?.packageName}")
    }

    private fun diagReport(root: AccessibilityNodeInfo, pkg: String, snap: ChatSnapshot?) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDiagAt < 3000) return
        lastDiagAt = now
        Log.d(TAG, "diag[active] pkg=$pkg rootPkg=${root.packageName} rootCls=${root.className} " +
            "rootChildren=${root.childCount} extract=" +
            (snap?.let { "title=${it.title} n=${it.messages.size} img=${it.imageMessage != null}" } ?: "null"))
        Log.d(TAG, "diag[active] ${describeTree(root)}")
        val ws = windows
        if (ws == null) { Log.d(TAG, "diag[windows] null"); return }
        Log.d(TAG, "diag[windows] count=${ws.size}")
        for (w in ws) {
            val r = w.root
            if (r == null) {
                Log.d(TAG, "diag[win] type=${w.type} active=${w.isActive} focused=${w.isFocused} root=null")
                continue
            }
            Log.d(TAG, "diag[win] type=${w.type} active=${w.isActive} focused=${w.isFocused} " +
                "pkg=${r.packageName} ${describeTree(r)}")
        }
    }

    /** Walks a tree once and summarises it: node count, how many carry a
     *  resource-id / text, how many WeChat bubbles and editable fields exist, plus
     *  a sample of ids — enough to tell "empty tree" from "ids stripped" from
     *  "wrong root". */
    private fun describeTree(root: AccessibilityNodeInfo): String {
        var total = 0; var withId = 0; var withText = 0; var bkl = 0; var editable = 0
        val ids = LinkedHashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 8000) {
            guard++
            val n = stack.removeLast()
            total++
            val id = n.viewIdResourceName
            if (!id.isNullOrBlank()) { withId++; if (ids.size < 24) ids.add(id) }
            if (!n.text.isNullOrEmpty()) withText++
            if (id == "com.tencent.mm:id/bkl") bkl++
            if (n.isEditable) editable++
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return "nodes=$total withId=$withId withText=$withText bkl=$bkl editable=$editable " +
            "ids=[${ids.joinToString(",")}]"
    }

    private fun maybeCapture() {
        val root = rootInActiveWindow ?: run { diagSimple("root=null"); leaveConversation(); overlay?.hide(); return }
        val pkg = root.packageName?.toString()
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run {
            diagSimple("no-adapter fg=$pkg")
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        diagReport(root, pkg ?: "", rawSnapshot)
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
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        val token = session.begin() ?: return
        analyzing = true
        overlay?.showLoading()
        overlay?.setNote(snapshot.note)
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submitAnalysis {
            val ctx = try {
                ContextBuilder.build(this, snapshot, token.target.pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (!isCurrent(token)) return@post
                overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                var remaining = 2
                fun completed() {
                    remaining--
                    if (remaining == 0) {
                        analyzing = false
                        analysisTasks.clear()
                    }
                }
                submitAnalysis {
                    val judgment = client.judge(snapshot, rel, ctx)
                    main.post {
                        if (isCurrent(token)) {
                            if (judgment.error != null) overlay?.showError(judgment.error)
                            else overlay?.showJudgment(judgment)
                            completed()
                        }
                    }
                }
                submitAnalysis {
                    var replyError: String? = null
                    val ranked = try { client.draftAndRank(snapshot, rel, ctx) } catch (e: Exception) {
                        replyError = e.message ?: e.javaClass.simpleName
                        emptyList()
                    }
                    main.post {
                        if (isCurrent(token)) {
                            overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
                            // 真·无人值守（微信）：用户显式开启后，直接按打分最高的候选
                            // 填入；配合 autoSend 就形成完整的自动收发闭环。默认关，
                            // 且只填候选原文、不改写，只在微信生效。
                            if (prefs.autoFillBest && token.target.pkg == PKG_WECHAT &&
                                replyError == null && ranked.isNotEmpty()
                            ) {
                                val best = ranked.maxByOrNull { it.prob } ?: ranked.first()
                                main.postDelayed({ fillInput(token, best.text) }, 400L)
                            }
                            completed()
                        }
                    }
                }
            }
        }
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
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
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

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
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
                if (prefs.autoSend) {
                    overlay?.toast("已填入，正在发送…")
                    sendFor(token)
                } else {
                    overlay?.toast("已填入，确认后自己发送")
                }
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
     */
    private fun sendFor(token: ConversationSession.Token) {
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
            // Confirm the input cleared (message actually went out); retry once if not.
            main.postDelayed({
                if (!isCurrent(token)) return@postDelayed
                val input = inputFor(token)
                if (input != null && !input.text.isNullOrBlank()) {
                    findSendButton(rootInActiveWindow ?: return@postDelayed, token.target.pkg)
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }, 600L)
        }, 300L)
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
