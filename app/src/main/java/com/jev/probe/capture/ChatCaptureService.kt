package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jev.probe.BuildConfig
import com.jev.probe.input.ProbeImeBus
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
import com.jev.probe.core.t
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

    /**
     * 「本次不发送」的闸门。发送前有 [Prefs.sendDelayMs] 的等待期，用户点悬浮窗上
     * 的「本次不发送」就把它置真；[sendFor] 在真正点击发送前检查它。
     *
     * 只挡这一条，不动自动收发总开关——用户的意思是「这条别发」，不是「以后都别发」。
     */
    @Volatile private var skipThisSend = false

    /** 最近一次已知悬浮窗权限状态：从 true→false 时只报一次警，避免每个事件都弹 toast。 */
    private var overlayOk = true

    /** 上次复查悬浮窗权限的时间戳（节流到每 3s 一次，避免每个无障碍事件都做 IPC）。 */
    private var lastOverlayCheckAt = 0L

    /** 最近一次分析命中 429 限流的时间戳；自动分析冷却期内不再自动触发（手动不受影响）。 */
    private var lastBusyAt = 0L

    /**
     * 错误卡上那个按钮（P0-3 / P1-3）。「重试」按错误类别分流：没读到文字时重试 =
     * 截屏 OCR 一次，其它错误才是重跑一轮分析。
     */
    private fun handleErrorAction(a: ErrCatalog.Action) {
        when (a) {
            ErrCatalog.Action.RETRY -> {
                // NO_TEXT 原本一律改走截屏 OCR。但微信里整屏截屏会被风控拒掉
                // （ocrCaptureManual 会直接拒绝），所以在微信内老老实实重跑分析。
                if (lastErrKind == ErrCatalog.Kind.NO_TEXT && activePkg != PKG_WECHAT) {
                    ocrCaptureManual()
                } else currentSnapshot?.let { pendingSnapshot = it; runAnalysis(manual = true) }
            }
            ErrCatalog.Action.SETTINGS -> openSettings(focusPlan = false)
            ErrCatalog.Action.PLAN -> openSettings(focusPlan = true)
            ErrCatalog.Action.A11Y -> runCatching {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { overlay?.toast(t("打不开无障碍设置", "Can't open accessibility settings")) }
        }
    }

    /**
     * 把当前会话加入 / 移出「只分析这些会话」名单（P1-8）。
     *
     * 语义澄清（原来这里是最容易招致困惑的一处）：[Prefs.isAllowed] 的规则是
     * **名单非空时只分析命中的会话**——所以这是一个「缩小范围」的开关，不是「排除」。
     * 界面上原来叫「白名单」，用户理所当然理解成后者，点了之后发现助手突然不分析了，
     * 却又找不到原因。菜单与 toast 都按真实语义措辞，并明确说出影响面。
     *
     * 因为名单存的是关键词（不是会话 id），「移出」= 删掉所有能匹配上当前标题的关键词。
     */
    private fun toggleWhitelist() {
        val title = currentSnapshot?.title
        if (title.isNullOrBlank()) {
            overlay?.toast(t("当前会话没有标题，改不了名单", "This chat has no title yet — can't edit the list")); return }
        val wl = prefs.whitelist
        val matched = wl.filter { title.contains(it, ignoreCase = true) }
        if (matched.isEmpty()) {
            prefs.whitelist = wl + title
            // 说清后果：用户最常见的场景是「只想看这一个」，那他就该知道别人都不分析了。
            overlay?.toast(t("已加入：之后只分析「$title」，其它会话都不再分析",
                "Added: from now on only \"$title\" is analyzed, other chats are skipped"))
        } else {
            prefs.whitelist = wl - matched.toSet()
            overlay?.toast(if (wl.size == matched.size)
                t("已退出「只分析这些会话」，现在所有会话都会分析",
                  "Left \"only analyze listed chats\" — all chats are analyzed again")
            else
                t("已移出「$title」，名单里还剩 ${wl.size - matched.size} 个",
                  "Removed \"$title\" — ${wl.size - matched.size} left in the list"))
        }
    }

    /** 打开设置页；[focusPlan] 时定位到订阅/档位卡片（429 的升级入口走这条路）。 */
    private fun openSettings(focusPlan: Boolean) {
        runCatching {
            startActivity(android.content.Intent()
                .setClassName(this, "com.jev.probe.SettingsActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("focus", if (focusPlan) "plan" else ""))
        }.onFailure { overlay?.toast(t("打不开设置", "Can't open settings")) }
    }

    /** 悬浮球菜单「诊断与自检」→ 打开主页并直接弹出诊断卡。 */
    private fun openDiagnostics() {
        runCatching {
            startActivity(android.content.Intent()
                .setClassName(this, "com.jev.probe.MainActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("diag", true))
        }.onFailure { overlay?.toast(t("打不开诊断页", "Can't open diagnostics")) }
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
        // 通知直驱轮次进行中（autoOpenChat 刚把微信拉到前台）：target 换成真实窗口
        // 是正常的前台语义，但绝不许顺带 cancelAnalysis——那会在 judge 跑到一半时
        // 把通知轮次拆掉，表现为「context 打完就断、之后什么都不日志」。
        val notifRound = analyzing && session.target?.windowId == ConversationSession.NOTIF_WINDOW
        if (session.observe(target) && !notifRound) {
            cancelAnalysis()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
        }
    }

    private fun leaveConversation() {
        // 后台通知直驱轮次在跑：无障碍的窗口 churn（autoOpenChat 拉起微信、用户切
        // 桌面、target 失配）不该拆台——文本来自通知，轮次要跑完出结果。
        // prefs 总开关除外：关了她必须立刻停。
        if (analyzing && prefs.enabled &&
            session.target?.windowId == ConversationSession.NOTIF_WINDOW) return
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
            val snapshot = adapter.extract(root, resources) ?: run {
                // WeChat 8.0.78 blanks the chat bubbles out of the accessibility
                // tree while the user is really inside the conversation. The tree
                // then reports "not a chat window", and because every downstream
                // gate is built on this call — maybeCapture's target check,
                // ocrCapture's session.token() and isCurrent() — the whole
                // pipeline died: no tree read AND no OCR, which is exactly the
                // recent 识别失效. The window class does not depend on the tree,
                // so when it still says ChattingUI we know the tree is lying.
                // Keep a target with an unreadable title so the OCR fallback can
                // still run (its screenshot path survives WeChat's risk control).
                if (pkg == PKG_WECHAT && lastWindowClass.contains("chatting", ignoreCase = true)) {
                    return ConversationSession.Target(pkg, root.windowId, null, null)
                }
                return null
            }
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
        // 后台通知驱动（windowId=NOTIF_WINDOW）：微信故意在后台，rootInActiveWindow 是
        // 桌面/其他 app，「前台窗口必须匹配」这条前台假设不适用；消息文本来自通知，
        // 只要 token 仍被 session 接受就放行，让通知直驱分析能跑完并回填结果。
        if (token.target.windowId == ConversationSession.NOTIF_WINDOW) return true
        val live = rootInActiveWindow?.let { targetFor(it) }
        if (live != token.target || !prefs.isAllowed(currentSnapshot?.title ?: live.title)) {
            leaveConversation();
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

    // 自动触发的分析：结果只挂蓝点，不弹面板（见 OverlayController.setManualRun）。
    private val debounce = Runnable { runAnalysis(manual = false) }
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
            Log.d(TAG, "wxMsgReceiver: WX_MSG received sender=${intent.getStringExtra("sender")} textLen=${intent.getStringExtra("text")?.length}")
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
                // 同会话多条通知攒一波（默认 1.2s 防抖）再统一分析+回复一次，
                // 而不是每条通知各触发一次——否则对方连发 3 条会回 3 次、
                // 且每条只看自己那一条，丢失「多条消息 + 历史 + 谁说的」全貌。
                enqueueNotif(currentSnapshot?.title ?: sender, sender, text,
                    intent.getBooleanExtra("image", false))
            }
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg == PKG_WECHAT) {
                main.post { if (prefs.enabled) runCatching { maybeCapture() } }
            }
        }
    }

    /** DEBUG-only synthetic injector receiver (see [ACTION_TEST_WX_MSG]). Null in
     *  release; set only when [BuildConfig.DEBUG] so the branch is R8-stripped. */
    private var debugInjectReceiver: BroadcastReceiver? = null

    /**
     * 通知攒批：同会话([conv])在 [NOTIF_BATCH_MS] 内到达的多条消息合并成一个快照，
     * 统一走一次分析+回复。微信 8.0.78 屏蔽了前台读屏，自动回复只能靠通知，
     * 不攒批就会「每条通知各回一次 + 每条只看自己」——既刷屏又丢上下文。
     * 每条带真实发送人([speaker])，群聊也能在提示词里区分谁说的。
     */
    private val notifBatch = LinkedHashMap<String, MutableList<Msg>>()
    private val notifBatchTimer = HashMap<String, Runnable>()
    private val NOTIF_BATCH_MS = 1200L

    private fun enqueueNotif(conv: String?, speaker: String?, text: String, isImage: Boolean) {
        val key = conv ?: speaker ?: "wechat"
        val msg = Msg("other", if (isImage) "[图片] $text" else text, speaker)
        synchronized(notifBatch) {
            val list = notifBatch.getOrPut(key) { ArrayList() }
            // 同一窗口内重复 OCR 的同一条不重复计入（防抖期间同一条通知可能重投）。
            if (list.none { it.sender == msg.sender && it.text == msg.text }) list.add(msg)
            notifBatchTimer[key]?.let { main.removeCallbacks(it) }
        }
        val r = Runnable { flushNotifBatch(key) }
        notifBatchTimer[key] = r
        main.postDelayed(r, NOTIF_BATCH_MS)
    }

    private fun flushNotifBatch(conv: String) {
        val msgs = synchronized(notifBatch) { notifBatch.remove(conv) } ?: return
        notifBatchTimer.remove(conv)
        if (destroyed || !prefs.enabled) {
            Log.d(TAG, "notif-batch: drop destroyed=$destroyed enabled=${prefs.enabled}")
            return
        }
        // 后台纯通知场景：微信在后台，无障碍没抓到聊天窗口，session.target 可能为 null，
        // 旧代码用 session.token() 会直接 return、连日志都不打（静默黑洞）。改用
        // beginNotification 构造逻辑 target，绕开前台窗口假设，让通知文本真正驱动分析+回复。
        val token = session.beginNotification(PKG_WECHAT, conv)
        val snapshot = ChatSnapshot(conv, msgs.toList(), note = NOTIFY_NOTE)
        Log.i(TAG, "notif-batch: conv=$conv msgs=${msgs.size} senders=${msgs.map { it.sender }.distinct()} -> single analyze+reply")
        finishOcrSnapshot(snapshot, PKG_WECHAT, manual = false, token = token)
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
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis(manual = true) }
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
                title.isNullOrBlank() -> overlay?.toast(t("当前会话没有标题，存不了",
                    "This chat has no title yet — can't save"))
                isTransientTitle(title) -> overlay?.toast(t("当前会话标题还没加载出来，稍后再试",
                    "The chat title hasn't loaded yet — try again shortly"))
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { t("保存失败：", "Save failed: ") + e.javaClass.simpleName }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // 「只分析这些会话」名单（P1-8）：用户最常问的是「为什么这里不分析」，把开关摆到手边。
        overlay?.onWhitelistToggle = { toggleWhitelist() }
        // 悬浮球菜单 → 在 App 内打开「诊断与自检」，不用退出当前聊天就能看到
        // 权限 / 上次分析 / 崩溃记录，自己定位「为什么没反应」。
        overlay?.onDiagnostics = { openDiagnostics() }
        // 首次引导：无障碍刚绑上、悬浮球还没出现过时，弹一张产品说明卡（只展示一次，
        // 见 OverlayController.showIdle）。否则新用户只看到一个半透明小圆点，不知干嘛。
        if (!prefs.onboarded) overlay?.showIdle(null)
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
        // DEBUG-only synthetic injector: drive the real enqueueNotif pipeline from
        // `adb shell am broadcast` so multi-message / group-sender batching can be
        // verified on-device without a real WeChat notification. Stripped in release.
        if (BuildConfig.DEBUG) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_TEST_WX_MSG) return
                    val conv = intent.getStringExtra("conv")
                    val sender = intent.getStringExtra("sender")
                    val text = intent.getStringExtra("text")
                    val image = intent.getBooleanExtra("image", false)
                    Log.i(TAG, "debug-inject: WX_MSG conv='$conv' sender='$sender' textLen=${text?.length} image=$image")
                    if (!text.isNullOrBlank()) enqueueNotif(conv, sender, text, image)
                }
            }
            debugInjectReceiver = r
            runCatching {
                ContextCompat.registerReceiver(
                    this, r, IntentFilter(ACTION_TEST_WX_MSG),
                    ContextCompat.RECEIVER_EXPORTED)
            }
            Log.i(TAG, "debug-inject: TEST_WX_MSG receiver registered (DEBUG build)")
        }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
        if (event == null) return
        // Live foreground state for the notification listener (reliable, no extra perms).
        weChatForeground = rootInActiveWindow?.packageName?.toString() == PKG_WECHAT
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        // 悬浮窗权限被系统收回（用户手动关掉、或 ROM 把设置重置）时，气泡会无声消失，
        // 用户只看到「助手不工作了」却不知为什么。这里每 3s 复查一次（避免每个无障碍事件
        // 都做 IPC），权限回来后复位并强制重建悬浮窗——系统已销毁旧 window，必须重 add
        // 而非复用已分离的旧 root；权限刚被收回时只报一次警。
        val now = System.currentTimeMillis()
        if (now - lastOverlayCheckAt > 3000) {
            lastOverlayCheckAt = now
            val canOverlayNow = Settings.canDrawOverlays(this)
            if (canOverlayNow) {
                if (!overlayOk) { overlayOk = true; overlay?.resetWindow() }
            } else if (overlayOk) {
                overlayOk = false
                Toast.makeText(this, t("悬浮窗权限被收回，去设置重新开启后助手才能显示", "Overlay permission was revoked — re-enable it in Settings or the bubble can\u0027t show"), Toast.LENGTH_LONG).show()
            }
        }

        val type = event.eventType
        // Remember the foreground window class on every window switch. It is the
        // only "am I in a chat screen" signal that does not depend on the tree,
        // which WeChat 8.0.78 blanks out intermittently.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cn = event.className?.toString()?.takeIf { it.isNotBlank() }
            // The soft keyboard reports its own window class (SoftInputWindow / the
            // IME class). While it is up the chat Activity is still foreground, so
            // letting that event overwrite lastWindowClass would make us think the
            // chat closed and kill the OCR fallback — i.e. the recent 识别失效
            // whenever the user types or sends a message. Ignore IME-class windows
            // so lastWindowClass keeps the real Activity class (ChattingUI).
            if (cn != null && !cn.contains("inputmethod", ignoreCase = true)
                && !cn.contains("SoftInputWindow", ignoreCase = true)
            ) {
                lastWindowClass = cn
            }
        }
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
        } catch (t: Throwable) {
            // A bad event must never kill the service: Android marks an
            // AccessibilityService that throws from onAccessibilityEvent as
            // crashed and stops dispatching events entirely, which silently
            // breaks all capture until the user re-enables it. Swallow and
            // keep alive.
            Log.e(TAG, "onAccessibilityEvent swallowed to keep service alive", t)
        }
    }

    /**
     * Last window class reported by TYPE_WINDOW_STATE_CHANGED (usually the
     * Activity name, e.g. com.tencent.mm.ui.chatting.ChattingUI). Kept because
     * WeChat 8.0.78 intermittently drops the chat bubbles from the
     * accessibility tree while the user really is inside a conversation — in
     * that state the tree alone cannot tell "chat screen" from "list screen",
     * but the window class can. Never derived from the tree, so it survives
     * those blank-tree episodes.
     */
    private var lastWindowClass: String = ""

    /**
     * While the soft keyboard is up, [rootInActiveWindow] is the IME window and its
     * tree carries no chat content, so adapters report "not a chat". The chat
     * Activity window is still live behind the IME and reachable via [getWindows];
     * return its root so callers can read the real conversation. Skips
     * TYPE_INPUT_METHOD windows and anything that is not WeChat.
     */
    private fun weChatChatRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (w in getWindows()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = w.root ?: continue
            if (r.packageName?.toString() == PKG_WECHAT) return r
        }
        return null
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
        // While the soft keyboard is up, rootInActiveWindow is the IME window
        // (SoftInputWindow) whose tree has no chat bubbles, so extract() would
        // return null even though WeChat is foreground. The real chat window is
        // still present in getWindows() behind the IME — read that tree instead.
        val effectiveRoot = if (pkg == PKG_WECHAT) weChatChatRoot() ?: root else root
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(effectiveRoot, resources)
        if (rawSnapshot == null) {
            Log.i(TAG, "wx-cycle: snapshot=null pkg=$pkg class=$lastWindowClass effChat=${effectiveRoot != root} fb=${prefs.ocrFallback}")
            // WeChat 8.0.78 intermittently hides every chat bubble from the
            // accessibility tree while the user IS in a conversation. The adapter
            // then says "not a chat window" (null) and we used to drop the cycle
            // right here — no tree read AND no OCR, i.e. the recent 识别失效
            // (zero output even though the probe was healthy and bound).
            // The window class does not depend on the tree, so when it still says
            // ChattingUI we know the tree lied: fall back to the accessibility
            // screenshot + local OCR, which takeScreenshot serves without tripping
            // WeChat's FLAG_SECURE risk control.
            val chatWin = weChatChatRoot()
            val inConversation = pkg == PKG_WECHAT && prefs.ocrFallback && (
                lastWindowClass.contains("chatting", ignoreCase = true) ||
                // WeChat may rename the chat Activity across builds, but the list
                // screen is always LauncherUI. Trusting "not the list" instead of a
                // hard-coded "chatting" string makes the OCR fallback survive renames.
                (chatWin != null && chatWin.className?.toString()
                    ?.contains("Launcher", ignoreCase = true) != true)
            )
            Log.i(TAG, "wx-cycle: ocr-gate pkg=$pkg class=$lastWindowClass inConv=$inConversation")
            if (inConversation) {
                val pkgName = pkg ?: ""
                // Establish the target first: ocrCapture gates on session.token()
                // and isCurrent(), both of which need one. targetFor() now keeps a
                // target for a blanked-out chat tree (see above), so this succeeds
                // where it used to return null and kill the OCR fallback.
                targetFor(effectiveRoot)?.let { observeTarget(it) }
                val title = session.target?.title ?: ""
                if (overlay?.isShowing() != true) {
                    overlay?.showIdle(if (title.isBlank()) null else title)
                }
                val sig = ocrSignature(pkgName, title, emptyList())
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(title, emptyList(), pkgName, manual = false)
                return
            }
            leaveConversation(); overlay?.showIdle(null); return
        }
        val target = targetFor(effectiveRoot)
        if (target == null) { leaveConversation(); overlay?.showIdle(null); return }
        observeTarget(target)
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            Log.i(TAG, "wx-cycle: empty-tree pkg=$pkg class=$lastWindowClass fb=${prefs.ocrFallback} pri=${prefs.ocrPrimary} rects=${snapshot.bubbleRects.size}")
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            // WeChat 8.0.78 hides bubble text in the accessibility tree, so the
            // tree read above yields no messages. The OCR below uses the
            // AccessibilityService capture API (takeScreenshot), which WeChat's
            // FLAG_SECURE risk control does NOT block — safe inside WeChat. Allow it.
            if (prefs.ocrFallback) {
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
            } else {
                Log.i(TAG, "wx-cycle: OCR skipped (ocrFallback=false)")
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        // 气泡菜单里「只分析这个会话 / 退出」要按当前是否已在名单里显示不同文案，
        // 所以把标题同步给悬浮窗一份（判断逻辑在 Prefs.isAllowed 那边保持唯一事实源）。
        overlay?.currentTitle = snapshot.title
        // 包名同理：菜单要靠它决定「截屏识别一次」该不该摆（微信内会被风控拒绝）。
        overlay?.currentPkg = pkg
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

    private fun runAnalysis(manual: Boolean = true) {
        val snapshot = pendingSnapshot ?: return
        if (analyzing || destroyed || !prefs.enabled) return
        // 通知直驱快照（note=NOTIFY_NOTE）：session.target 可能已被无障碍的 observe()
        // 换成真实窗口 target（autoOpenChat 拉起微信的必然结果），token() 于是不再是
        // 通知 token。通知轮次的合法性不依赖实时窗口——补签一个逻辑 token 放行。
        val previous = if (snapshot.note == NOTIFY_NOTE &&
            session.token()?.target?.windowId != ConversationSession.NOTIF_WINDOW
        ) session.beginNotification(PKG_WECHAT, snapshot.title)
        else session.token() ?: return
        if (!isCurrent(previous)) return
        if (!prefs.hasKey()) { overlay?.showOcrResult(snapshot.messages, "未设置判断接口密钥，去设置里填"); return }
        val token = session.begin() ?: return
        analyzing = true
        // 手动 / 自动的区别只影响「要不要强弹面板」，判断逻辑本身完全一样，
        // 所以只把标志交给悬浮窗，本类不再留一份副本（两处状态必然会走偏）。
        overlay?.setManualRun(manual)
        overlay?.currentTitle = snapshot.title
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
                    Log.w(TAG, "runAnalysis: stale session -> drop round " +
                        "(notif=${token.target.windowId == ConversationSession.NOTIF_WINDOW}, " +
                        "liveTarget=${session.target?.windowId}, enabled=${prefs.enabled})")
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
                            val v = ErrCatalog.classify(this, judgment.error,
                                inWeChat = token.target.pkg == PKG_WECHAT)
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
                            roundErr = ErrCatalog.classify(this, replyError,
                                inWeChat = token.target.pkg == PKG_WECHAT).kind
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
        // 超时文案不区分微信与否（它在任何 App 上都一样），但把 inWeChat 传准，
        // 万一日志里出现别的 no_text 归类也不会给出在微信里无效的按钮。
        overlay?.showFailure(ErrCatalog.classify(this, "分析超时",
            inWeChat = activePkg == PKG_WECHAT))
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
        if (pkg == PKG_WECHAT) { overlay?.toast(t("微信内暂不支持截屏识别，已用树读取", "Screen capture isn\u0027t supported inside WeChat — used tree reading instead")); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.toast(t("无法确认当前会话，请等待标题加载后重试", "Can\u0027t confirm the current chat — wait for the title to load and retry"))
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
                    // WeChat 8.0.78 blocks the accessibility screenshot (FLAG_SECURE),
                    // so the OCR path is dead inside a live chat. The notification
                    // preview we already accumulated is the only fallback left — use
                    // it to keep auto-analysis alive for messages that arrived while
                    // WeChat was in the background (they sit in history). Foreground
                    // real-time messages are not in history (WeChat posts no
                    // notification while the chat is open), so this cannot recover
                    // those — that is a WeChat limit.
                    if (!manual && pkg == PKG_WECHAT && !transient) {
                        val fb = ConversationHistory.latestOtherForPkg(PKG_WECHAT)
                        if (!fb.isNullOrBlank()) {
                            val tk = session.token()
                            if (tk != null && isCurrent(tk)) {
                                finishOcrSnapshot(
                                    ChatSnapshot(null, listOf(Msg("other", fb)), note = NOTIFY_NOTE),
                                    PKG_WECHAT, manual = false, token = tk)
                            }
                        }
                    }
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
                // pkg 传进去：微信里整屏截屏会被风控拒绝，按钮必须换成
                // 「重新分析」而不是那个必然失败的「截屏识别一次」。
                overlay?.showFailure(ErrCatalog.classify(this, "这一屏没认出文字",
                    inWeChat = pkg == PKG_WECHAT))
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
        overlay?.currentTitle = snapshot.title
        overlay?.currentPkg = pkg
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
        // 就直接出结果。manual=true（用户自己点的「截屏识别一次」）才展开面板；
        // 自动这一路只挂蓝点，别把卡片压在用户正在看的聊天上。
        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis(manual = manual)
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (token.target.windowId == ConversationSession.NOTIF_WINDOW) {
            // 后台通知直驱：token 是逻辑 target（windowId=NOTIF_WINDOW），永远不等于真实
            // 窗口 target。autoOpenChat 已用 contentIntent 打开指定会话——只要前台还是
            // 同一个 app，输入框就属于那个会话。（8.0.78 读不到聊天标题，无法按会话名
            // 校验；防误填靠 autoFillBest 的「输入框必须为空 + 同一屏只回一次」两道闸。）
            if (root.packageName?.toString() != token.target.pkg) return null
        } else if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.twitter.android" -> findEditable(root)
            PKG_WECHAT -> findEditable(root)
                // 8.0.78 把聊天页树剪到个位数节点，输入框不在树里——但它获得焦点后
                // findFocus(FOCUS_INPUT) 不走树遍历，仍拿得到（配合 fillInput 的
                // 「点按输入栏聚焦」重试）。
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /**
     * 微信输入栏/发送键坐标（2026-10-13 真机实证，1080x2412, density 2.75）：
     *  - 聚焦点：无键盘态输入栏中心 (499, 2255)，tap 后键盘弹起、输入栏升到 y≈1305
     *  - 发送键：键盘弹起态微信绿色「发送」按钮 (984, 1331)（仅输入栏有文本时出现）
     * 按屏幕比例换算适配其它分辨率。旧硬编码 (540,2241)/(964,2241) 在键盘弹起态
     * 整行落在键盘区域——这是 70e588b 之后仍发不出去的直接原因之一。
     */
    private fun wxFocusPoint(): Pair<Float, Float> {
        // 输入栏中心：紧贴 IME 窗顶，与 wxSendPoint 同一条线（y=H-IME实际高-输入栏半高）。
        // IME 实际高度从 ProbeImeBus.imeWindowHeightPx 读（IME 窗口 wrap_content，
        // 实测 304px 而非键盘高），读不到才回落 KB_RATIO*H 兜底。
        val dm = resources.displayMetrics
        val imeH = imeWindowHeight()
        val barHalf = com.jev.probe.input.ProbeImeBus.WX_INPUT_BAR_DP * dm.density / 2f
        return dm.widthPixels * 0.462f to (dm.heightPixels - imeH - barHalf)
    }

    private fun wxSendPoint(): Pair<Float, Float> {
        // 绿按钮中心 = (0.9111W, 图标行垂直中心)。图标行紧贴 IME 窗顶，所以
        // y = H - IME实际窗口高 - 图标行半高(56dp*density/2)。IME 窗口是 wrap_content
        // （实测被内容缩到 304px），用 ProbeImeBus.imeWindowHeightPx 动态取实际高度。
        // 2026-10-14 真机 PIL 标定（1080x2412, 微信 8.0.78）：imeH=304 时
        // 2412-304-77=2031，与绿键实测中心 (984,2031) 吻合；**回复多行时绿键
        // 并不移位**（多行只撑高上面的输入框，图标行位置不变——曾把已发气泡
        // 误读成按钮，虚惊一场）。输入框空时同一位置变灰色「+」。
        // 全部按屏幕参数算，换手机/换分辨率自动适配，无硬编码坐标。
        val dm = resources.displayMetrics
        val imeH = imeWindowHeight()
        val barHalf = com.jev.probe.input.ProbeImeBus.WX_INPUT_BAR_DP * dm.density / 2f
        return dm.widthPixels * 0.9111f to (dm.heightPixels - imeH - barHalf)
    }

    /** IME 窗口实际高度：IME 活着时取其发布值，否则回落键盘同高兜底。 */
    private fun imeWindowHeight(): Float {
        val h = com.jev.probe.input.ProbeImeBus.imeWindowHeightPx
        if (h > 0) return h.toFloat()
        return resources.displayMetrics.heightPixels * com.jev.probe.input.ProbeImeBus.KB_RATIO
    }

    /**
     * 点微信聊天页底部输入栏中心，让输入框获得焦点——8.0.78 把它从无障碍树里剪掉了，
     * 树遍历找不到，点一下之后输入框才持有焦点（探针输入法靠这个焦点拿到
     * InputConnection）。点偏了（表情键 / “+”键）最坏是弹出对应面板，不会误发任何东西。
     */
    private fun tapWeChatInputBar(): Boolean {
        val (x, y) = wxFocusPoint()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 微信发送（2026-10-14 真机复盘后重写）：截图确认 → 点绿键 → 复检 → 重点。
     *
     * 真机事实（1080x2412 + 微信 8.0.78 截图 PIL 标定）：
     * - 绿键（品牌绿 #07C160 纸飞机圆钮）位置**固定**在图标行右端
     *   (984,2031) = (0.9111W, H-IME实际窗口高-56dp/2)，**多行输入不会移位**
     *   （曾把已发气泡误读成按钮，以为多行会带动它——不会）。
     * - 输入框空时同一位置变灰色「+」：品牌绿像素数=0（实测）。
     *   所以「绿键在」= 还没发出去，「绿键没了」= 已发送。
     * - performEditorAction 在微信上确实能发送，但发送权统一收在这里：
     *   保留 sendDelayMs 倒计时撤回窗口 + 截图复检抓假阳性 + 失败可重试。
     *
     * 流程：截屏 → 绿键在才点（防止点到「+」弹出面板）→ 1.1s 后复检 →
     * 没了=成功 toast；还在=再点一次并请用户人工确认。
     * 截屏失败时按旧行为点计算坐标兜底（点在绿键上发送，点空无害）。
     */
    private fun sendWeChatByVerifiedTap(token: ConversationSession.Token) {
        fun tapSend() {
            val (x, y) = wxSendPoint()
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
                .build()
            Log.i(TAG, "sendTap: dispatching tap at (${x.toInt()},${y.toInt()})")
            dispatchGesture(gesture, null, null)
        }
        // onResult(true) = 已确认发送成功（绿键没了）；false = 点了但未确认/截屏失败
        fun checkThenAct(onResult: (Boolean) -> Unit) {
            screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
                if (!isCurrent(token)) { onResult(false); return@capture }
                if (res is ScreenCapture.Result.Ok) {
                    val present = weChatSendButtonPresent(res)
                    Log.i(TAG, "sendTap check: greenButtonPresent=$present")
                    if (present) tapSend()
                    onResult(!present)
                } else {
                    Log.w(TAG, "sendTap check: screenshot failed code=${(res as? ScreenCapture.Result.Failed)?.code}, tapping computed point as fallback")
                    tapSend()
                    onResult(false)
                }
            }
        }
        checkThenAct { firstOk ->
            if (firstOk) {
                // 点之前绿键就已不在（极端情况：别处已发）——没点，直接报成功。
                overlay?.toast(t("已自动发送", "Sent automatically"))
                return@checkThenAct
            }
            // 1.1s：等微信发送动画落定，同时避开 ScreenCapture 1s 截屏节流。
            main.postDelayed({
                checkThenAct { secondOk ->
                    if (secondOk) {
                        Log.i(TAG, "sendTap verify: green button gone -> message actually sent")
                        overlay?.toast(t("已自动发送", "Sent automatically"))
                    } else {
                        Log.w(TAG, "sendTap verify: not confirmed (green button still there or shot failed)")
                        overlay?.toast(t("请确认消息是否已发出", "Please check whether the message went out"))
                    }
                }
            }, 1100L)
        }
    }

    /**
     * 微信绿键在位检测：截 [wxSendPoint] 周围 ±70px 小窗，数品牌绿
     * #07C160 容差像素。有文本时微信在此显示纸飞机圆钮（约 120px 直径，
     * 数千绿像素）；输入框空时同一位置是灰色「+」（0 绿像素，2026-10-14
     * 真机实测）。光标在输入框文本区（左侧），不会进这个小窗。
     */
    private fun weChatSendButtonPresent(ok: ScreenCapture.Result.Ok): Boolean {
        val (sx, sy) = wxSendPoint()
        val bmp = ok.bitmap
        val half = 70f
        val x0 = ((sx - half - ok.originX) * ok.scaleX).toInt().coerceIn(0, bmp.width - 1)
        val x1 = ((sx + half - ok.originX) * ok.scaleX).toInt().coerceIn(0, bmp.width)
        val y0 = ((sy - half - ok.originY) * ok.scaleY).toInt().coerceIn(0, bmp.height - 1)
        val y1 = ((sy + half - ok.originY) * ok.scaleY).toInt().coerceIn(0, bmp.height)
        if (x1 <= x0 || y1 <= y0) return false
        val w = x1 - x0; val h = y1 - y0
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x0, y0, w, h)
        var green = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            if (kotlin.math.abs(r - 0x07) <= 40 &&
                kotlin.math.abs(g - 0xC1) <= 40 &&
                kotlin.math.abs(b - 0x60) <= 40) green++
        }
        Log.i(TAG, "sendTap: brand-green px in button region = $green (region ${w}x${h})")
        return green >= 300
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String, attempt: Int = 0) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) { Log.w(TAG, "fillInput finish: token stale, drop"); return }
            if (ok) {
                // 不信 IME 的 lastSent 短路：2026-10-14 真机教训——IME 报了
                // 「已发送」就跳过 sendFor，验证链整段被绕过。autoSend 开就
                // 一律走 sendFor（倒计时 → 截图确认绿键 → 点 → 复检 → 重点），
                // 真发没发出去以截屏复检为准，不以任何返回值承诺为准。
                Log.i(TAG, "fillInput ok: autoSend=${prefs.autoSend} imeClaimedSent=${ProbeImeBus.lastSent} -> ${if (prefs.autoSend) "sendFor" else "toast-only"}")
                // sendFor announces the countdown itself; don't double-toast here.
                if (prefs.autoSend) sendFor(token)
                else overlay?.toast(t("已填入，确认后自己发送", "Filled in — review it and send yourself"))
            } else { Log.w(TAG, "fillInput failed -> clipboard fallback"); copyToClipboard(text); overlay?.toast(t("已复制，长按输入框粘贴", "Copied — long-press the input box to paste")) }
        }
        if (!isCurrent(token)) { Log.w(TAG, "fillInput: token stale at entry (destroyed=$destroyed enabled=${prefs.enabled})"); overlay?.toast(t("会话已变化，请重新分析后填入", "The chat changed — analyze again before filling")); return }
        // 微信 8.0.78 把输入框从 a11y 树里剪掉（rootInActiveWindow 恒 null），树写/焦点写
        // 全部不可达——唯一可靠写入通道是探针自己的输入法（ProbeInputMethod.commitText）。
        if (token.target.pkg == PKG_WECHAT && ProbeImeBus.isEnabled(this)) {
            val (fx, fy) = wxFocusPoint()
            if (ProbeImeBus.imeActive) {
                // 探针已激活（用户自己在系统/通知栏切到了探针输入法）：投递 + 聚焦输入栏，
                // 走 poll path 直接填字（输入法写入前会先清空旧草稿，防多轮回复串缸）。
                // autoSend 开时由 sendFor 走「截图确认绿键 → 点 → 复检 → 重点」
                // 完成真正发送，倒计时撤回窗口保留。
                // App 全程不弹选择器、不替用户切输入法。
                ProbeImeBus.requestWrite(text, prefs.autoSend) { ok -> finish(ok) }
                tapWeChatInputBar()
                Log.i(TAG, "fillInput: probe IME active, wrote via poll path (focus=$fx,$fy, autoSend=${prefs.autoSend})")
            } else {
                // 输入法非探针：Android 不允许 app 静默切输入法，也绝不自动弹选择器打扰。
                // 只在界面提示用户自己去系统/通知栏切到「AI聊天助手·写入」，
                // 回复复制剪贴板兜底（用户切好后可手动粘贴或等下次自动回复）。
                Log.i(TAG, "fillInput: probe IME not active -> prompt to switch, clipboard fallback (focus=$fx,$fy)")
                copyToClipboard(text)
                overlay?.toast(t("请先在系统输入法切到「AI聊天助手·写入」才能自动回复，回复已复制可手动粘贴",
                                 "Switch your keyboard to \"AI Assistant · Write\" to auto-reply; reply copied for manual paste"))
            }
            return
        }
        if (token.target.pkg == PKG_WECHAT && attempt == 0) {
            // 探针输入法未启用：一次性引导 + 剪贴板降级（不自动重试，避免死循环）。
            Log.w(TAG, "fillInput: probe IME not enabled -> guide + clipboard")
            finish(false)
            overlay?.toast(t("自动发送需先启用一次：设置→语言和输入法→开启「AI聊天助手·写入」", "Enable probe IME once in Settings for auto-send"))
            return
        }
        if (inputFor(token) == null) {
            Log.w(TAG, "fillInput: inputFor=null attempt=$attempt fgPkg=${rootInActiveWindow?.packageName} rootNull=${rootInActiveWindow == null}")
            // 非微信 app 或树可读时：点一下底部输入栏再试。
            if (attempt == 0 && tapWeChatInputBar()) {
                Log.i(TAG, "fillInput: tapped input bar, retrying in 450ms")
                main.postDelayed({ fillInput(token, text, attempt = 1) }, 450L)
                return
            }
            Log.w(TAG, "fillInput: no retry possible -> clipboard")
            finish(false); return
        }
        Log.i(TAG, "fillInput: input node found, writing via GuardedInputWriter (attempt=$attempt)")
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
     *
     * 这段等待期是**用户唯一的反悔窗口**，所以不能只弹个 toast（Toast 会消失、
     * 不可点、还可能被别的通知顶掉）。改成在悬浮窗上摆一张带「本次不发送」的
     * 倒计时卡：发出去的消息收不回来，给一个能点的撤销口是最低成本的对价。
     */
    private fun sendFor(token: ConversationSession.Token) {
        val delay = prefs.sendDelayMs.toLong()
        skipThisSend = false
        Log.i(TAG, "sendFor: delay=${delay}ms autoSend=${prefs.autoSend} notifToken=${token.target.windowId == ConversationSession.NOTIF_WINDOW}")
        if (delay > 0) {
            overlay?.showSendCountdown(delay.toInt()) {
                skipThisSend = true
                overlay?.toast(t("已拦下这一条，不会发出去", "Held back — this one won\u0027t be sent"))
            }
        }
        main.postDelayed({
            if (!isCurrent(token)) { Log.w(TAG, "sendFor: token stale at fire time, aborted"); return@postDelayed }
            // 用户在这段等待里点了「本次不发送」：只放弃这一条，不动总开关。
            if (skipThisSend) { skipThisSend = false; Log.i(TAG, "sendFor: user held back this one"); return@postDelayed }
            // 发送不可逆：用户可能在这 2 秒等待里点了「暂停自动收发」（气泡菜单一步可达）。
            // 这里必须重新读开关，让暂停立刻生效——否则「随时收回控制权」就是空话。
            if (!prefs.autoSend) { overlay?.toast(t("已暂停自动发送，本次未发出", "Auto-send paused — not sent this time")); return@postDelayed }
            val root = rootInActiveWindow
            // 微信 8.0.78 聊天页 rootInActiveWindow 恒 null（树被剥光），旧代码在这里
            // 直接 abort——发送永远走不到。null 时只认 NOTIF token / isCurrent 放行。
            if (root == null) {
                Log.w(TAG, "sendFor: rootInActiveWindow=null (WeChat FLAG_SECURE path), proceeding by token")
            } else if (token.target.windowId != ConversationSession.NOTIF_WINDOW &&
                targetFor(root) != token.target) { Log.w(TAG, "sendFor: fg window no longer matches, aborted"); return@postDelayed }
            fun clickSend(): Boolean {
                val r = rootInActiveWindow
                if (r != null) {
                    val send = findSendButton(r, token.target.pkg)
                    Log.i(TAG, "clickSend: treeNode=${send != null} fgPkg=${r.packageName}")
                    if (send != null && send.refresh() && send.isVisibleToUser && send.isEnabled) {
                        send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "clickSend: clicked via tree node")
                        return true
                    }
                } else {
                    Log.i(TAG, "clickSend: root=null, tree path skipped")
                }
                // 8.0.78 树被剪，发送按钮不在树里——微信走「截图确认绿键 → 点 →
                // 复检 → 重点」的验证式发送（sendWeChatByVerifiedTap 内部完成，
                // 含截屏失败时计算坐标兜底）。IME 的 performEditorAction 不在
                // 这条链里：发送权统一收在这里，倒计时撤回窗口才作数。
                if (token.target.pkg == PKG_WECHAT) {
                    Log.i(TAG, "clickSend: WeChat -> verified-tap send path")
                    sendWeChatByVerifiedTap(token)
                    return true
                }
                return false
            }
            if (!clickSend()) {
                Log.w(TAG, "sendFor: clickSend failed -> manual tap needed")
                overlay?.toast(t("已填入，未能自动发送，请手动点发送", "Filled in but couldn't auto-send - tap send manually")); return@postDelayed
            }
            if (token.target.pkg == PKG_WECHAT) {
                // 微信：确认/重试/提示都在 sendWeChatByVerifiedTap 里异步完成。
                return@postDelayed
            }
            // 非微信：树可读，保留「输入框是否还有字」的事后确认。
            Log.i(TAG, "sendFor: clicked via tree, awaiting confirm")
            overlay?.toast(t("已自动发送", "Sent automatically"))
            main.postDelayed({
                if (!isCurrent(token)) return@postDelayed
                val input = inputFor(token)
                if (input == null) {
                    Log.i(TAG, "sendFor verify: no input node, cannot confirm")
                } else {
                    Log.i(TAG, "sendFor verify: inputStillHasText=${!input.text.isNullOrBlank()}")
                    if (!input.text.isNullOrBlank()) {
                        if (!clickSend()) {
                            findSendButton(rootInActiveWindow ?: return@postDelayed, token.target.pkg)
                                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
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
                            runAnalysis(manual = false)
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
        runCatching { debugInjectReceiver?.let { unregisterReceiver(it) } }
        debugInjectReceiver = null
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
        overlay?.onDiagnostics = null
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

        /** DEBUG-only synthetic injector. Lets on-device E2E tests drive the exact
         *  production [enqueueNotif] -> [flushNotifBatch] -> reply path without a
         *  real WeChat notification (which the shell cannot forge on Android 14+).
         *  Never registered in release builds (BuildConfig.DEBUG folds to false and
         *  R8 removes the branch), so it leaves no test surface in shipped APKs. */
        const val ACTION_TEST_WX_MSG = "com.jev.probe.action.TEST_WX_MSG"

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

        /** Said on the panel when the chat text came from the notification preview
         *  instead of the (WeChat-8.0.78-blocked) live screen read. */
        private const val NOTIFY_NOTE = "来自通知预览（微信前台屏蔽了读屏，已用通知文本兜底）"

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
