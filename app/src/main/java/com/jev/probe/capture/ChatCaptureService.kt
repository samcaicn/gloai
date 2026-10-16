package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
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
import com.jev.probe.core.PendingSend
import com.jev.probe.core.SendQueueStore
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
    // 4 线程：通知轮 runAnalysis 内部会嵌套 submit(judge) 再 submit(reply)，且 jev 失败会
    // 退回 LlmJudge 兜底（又一路网络）。2 线程时 judge 占满后 reply 只能排队，慢网络叠加
    // fallback 极易饿死整池、单轮分析 > 60s 而被 HARD_MAX 兜底推进。扩到 4 留足余量。
    private val worker = Executors.newFixedThreadPool(4)

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

    /**
     * 「不回复」一键开关（悬浮窗菜单用）：把当前会话加进/移出 [Prefs.noReplyList]。
     * 与 [toggleWhitelist] 对称，但方向相反——这是排除（命中后完全不自动回复）。
     */
    private fun toggleNoReply() {
        val title = currentSnapshot?.title
        if (title.isNullOrBlank()) {
            overlay?.toast(t("当前会话没有标题，改不了名单", "This chat has no title yet — can't edit the list")); return }
        val nl = prefs.noReplyList
        val matched = nl.filter { title.contains(it, ignoreCase = true) }
        if (matched.isEmpty()) {
            prefs.noReplyList = nl + title
            overlay?.toast(t("已加入不回复名单：之后「$title」的消息不再自动回复",
                "Added to no-reply list: \"$title\" won't get auto replies anymore"))
        } else {
            prefs.noReplyList = nl - matched.toSet()
            overlay?.toast(t("已移出不回复名单：「$title」恢复自动回复",
                "Removed from no-reply list: \"$title\" will get auto replies again"))
        }
    }

    /** 打开设置页；[focusPlan] 时定位到授权中心卡片（429 时切自带接口的入口走这条路）。 */
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
                // 关掉总开关时把待回队列与攒批一起清掉：否则再次打开会集中补发一批几分钟前
                // 的旧回复——那些消息对方早就不等了，补发只会显得莫名其妙（还容易发错会话）。
                if (!prefs.enabled) {
                    notifRoundQueue.clear()
                    synchronized(notifBatch) {
                        notifBatchTimer.values.forEach { main.removeCallbacks(it) }
                        notifBatch.clear()
                        notifBatchTimer.clear()
                        notifBatchFirstAt.clear()
                    }
                    Log.i(TAG, "notif-round: queue+batch cleared (assistant turned off)")
                }
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
            // 会话键必须用 WxNotificationListener 传来的 conv（群消息=群名、单聊=对方昵称），
            // 不能回退到 sender——否则后台群消息会被当成「某个群成员的私聊」，
            // beginNotification 用它去找会话只会定位到那个人的单聊，群消息永远发不出去。
            val conv = intent.getStringExtra("conv") ?: currentSnapshot?.title
            if (!text.isNullOrBlank()) {
                ConversationHistory.appendFromNotification(
                    PKG_WECHAT, conv ?: sender, sender, text,
                    intent.getBooleanExtra("image", false))
                // 同会话多条通知攒一波（默认 1.2s 防抖）再统一分析+回复一次，
                // 而不是每条通知各触发一次——否则对方连发 3 条会回 3 次、
                // 且每条只看自己那一条，丢失「多条消息 + 历史 + 谁说的」全貌。
                enqueueNotif(conv ?: sender, sender, text,
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
    private val notifBatchFirstAt = HashMap<String, Long>()
    private val NOTIF_BATCH_MS = 1200L
    // 攒批总窗口上限：真人连发几条（"在吗" / "忙吗" / "看到回我"）间隔常在 1-3s，
    // 固定 1.2s 会把一句话拆成两批、然后连回两次——一眼假。做法是"每条新消息都顺延
    // 1.2s"，但总窗口到 4s 就强制收口，避免群里刷屏把回复无限拖后。
    private val NOTIF_BATCH_MAX_MS = 4000L

    // ---- 通知轮 FIFO 队列：多个会话同时来消息时，一次只处理一个会话（分析→生成→发送→
    // 下一个），且每次发送前用该会话暂存的 PendingIntent 把微信重开到那个会话，杜绝
    // "第二个通知被 analyzing 挡掉即丢" 与 "回复填进错误会话窗口" 两个竞态。 ----
    private data class NotifRound(val conv: String, val msgs: List<Msg>, val enqueuedAt: Long = System.currentTimeMillis())
    private val notifRoundQueue = ArrayList<NotifRound>()
    private var notifRoundBusy = false
    private var notifRoundConv: String? = null
    // 本轮是否缺少"定位到目标会话"的锚点（微信不在前台且无可用/新鲜的 PendingIntent）。
    // 无锚点时**禁止自动发送**：探针读不到标题，回复会填进"此刻恰好前台"的任意会话，
    // 等于把私聊的话发进群里——这种错比不回严重得多，宁可不回。
    private var notifRoundAnchorless = false
    // 本轮是否"亲手用该会话的 PendingIntent 把微信重开到了这个会话"。锚点的第一要件：
    // 只有我们重放过、且微信确实到了前台，才能认为"当前输入框属于这个会话"。
    private var notifRoundReplayed = false
    // 同一会话的发送冷却：刚回过就别再回。真人连发 / 通知重复投递最容易触发"连回两次"，
    // 这是最露馅的机器感。冷却期内照样分析和出建议，只是不自动发出去。
    private val lastAutoSentAtByConv = HashMap<String, Long>()
    private val CONV_COOLDOWN_MS = 20_000L
    // 上一次真正发给该会话的文本（+ 时间戳）。模型对"在吗"这类短句极易给出同一句回复，
    // 连着发两句一模一样的话是最露馅的机器感，所以对同一会话做一次"与上条相同就不发"的拦截。
    private val lastSentTextByConv = HashMap<String, Pair<String, Long>>()
    private val REPLY_DEDUP_TTL_MS = 600_000L
    // 队列上限：群刷屏时别几十个会话排队回十几分钟（那时早没人等回复了）。
    // 被挤掉的会话文本已进 history，下一条消息会带着完整上下文重新触发。
    private val NOTIF_QUEUE_MAX = 4
    // 轮次保鲜：从入队到开跑超过这个时长就丢弃。回一条"迟到几分钟"的回复很怪，
    // 而且对方多半已经自己解决了。
    private val NOTIF_ROUND_TTL_MS = 120_000L
    // 轮次代号：每开一轮自增。滞后的"发送完成"回调只许终结自己所处的那一轮，
    // 防止上一轮慢速发送的 onDone 在宽限已推进到下一轮后误伤当前轮。
    private var notifRoundSeq = 0L
    // 本轮分析跑完（judge+reply 均返回）后，留给"异步发送"落定的宽限；到点即推进队列。
    private val SEND_DWELL_MS = 6000L
    // 从轮次启动起算的硬上限兜底：分析挂住（看门狗复位 analyzing 但不走 completed）也不卡死队列。
    // 60s 而非 100s：一轮卡住要卡一队列，真实使用里 60s 已是很长的容忍上限。
    private val NOTIF_HARD_MAX_MS = 60000L
    // 发送链（IME 写入 → sendFor → verified-tap 验证）是否已落定。false 期间通知轮
    // dwell 到点也**不准 advance**——2026-10-15 实测：dwell 强切会把还没被 IME 消费的
    // pending 带到下一个会话（快剧运营的回复在星享春会话里 commit 发出=串缸），或把
    // 草稿烂在输入框里切走。门控最多 hold 20×2s=40s，超时强制放行防死锁。
    @Volatile private var sendSettled = true
    private var advanceHoldTicks = 0
    // 本轮已交给探针 IME 写入的草稿全文。发送前读框为空时（编辑器失焦 → 读的是
    // fallback 连接，恒空）用它原地重写一次，见 recoverEmptyBox（2026-10-16）。
    private var lastDraftText: String? = null
    // 重放 PendingIntent 后等微信前台落定的轮询上限（100ms 一跳）。2.5s 够微信冷启动到
    // 聊天页；再久就是重放失败，早点放行让下一轮开工（本轮按无锚点处理，不会误发）。
    private val NOTIF_FG_WAIT_TICKS = 25
    // 单条回复自带重试上限（坐标点击 + IME 兜底各算一次尝试）。用退避间隔错开，
    // 避免贴着屏幕狂点。超过后落盘到 SendQueueStore，等会话下次活跃再发，不丢队列。
    private val MAX_SEND_TRIES = 6
    private val SEND_RETRY_BASE_MS = 1500L
    private fun sendRetryBackoff(attempt: Int) = (SEND_RETRY_BASE_MS * attempt).coerceAtMost(8000L)
    // 持久化未发队列保鲜：一条失败回复挂超过这个时长不再重试（对方多半已自己解决）。
    private val PENDING_TTL_MS = 10 * 60_000L
    // 当前正在发送的目标会话与文本（供失败落盘用，免层层透传）。
    private var currentSendConv: String? = null
    private var currentSendText: String? = null
    // drainPendingSends 重入保护：同时只驱动一条持久化重发。
    @Volatile private var drainingPending = false
    // 未发送提醒节流：IME 没激活时别每条通知轮都弹。
    private var lastPendingReminderAt = 0L
    // by lazy：hold 期间 lambda 需 postDelayed 自己（自引用）。直接 val 初始化器里
    // 自引用 K2 报 "must be initialized"，lazy 延迟到首次访问，自引用合法。
    private val notifAdvanceRunnable: Runnable by lazy {
        Runnable {
        if (!sendSettled && advanceHoldTicks < 20) {
            // 发送链未落定（IME 还没 commit / verify 还在重试）：绝不准切会话。
            advanceHoldTicks++
            Log.w(TAG, "notif-round: dwell reached but send unsettled -> hold (tick=$advanceHoldTicks)")
            main.postDelayed(notifAdvanceRunnable, 2000L)
            return@Runnable
        }
        if (!sendSettled) Log.w(TAG, "notif-round: send unsettled past hold cap -> force advance")
        advanceHoldTicks = 0
        Log.w(TAG, "notif-round: dwell elapsed -> advance")
        // 兜底推进时必须复位分析态：本轮可能因慢网络（设备访问 Cloudflare Worker
        // 在国内移动网下常超时）卡在 worker 线程里，completed() 没被调，analyzing
        // 仍 true。若不在这里清掉，后续所有轮 runAnalysis 会在开头 `if (analyzing)
        // return` 被静默吞掉——表现为"每条消息都卡 60s 然后推进、从不回复"。
        analyzing = false
        analysisTasks.forEach { runCatching { it.cancel(true) } }
        analysisTasks.clear()
        main.removeCallbacks(analysisWatchdog)
        finishNotifRound(notifRoundSeq)
        }
    }


    private fun enqueueNotif(conv: String?, speaker: String?, text: String, isImage: Boolean) {
        val key = conv ?: speaker ?: "wechat"
        val msg = Msg("other", if (isImage) "[图片] $text" else text, speaker)
        synchronized(notifBatch) {
            val list = notifBatch.getOrPut(key) { ArrayList() }
            // 同一窗口内重复 OCR 的同一条不重复计入（防抖期间同一条通知可能重投）。
            if (list.none { it.sender == msg.sender && it.text == msg.text }) list.add(msg)
            notifBatchTimer[key]?.let { main.removeCallbacks(it) }
        }
        val nowAt = System.currentTimeMillis()
        val firstAt = notifBatchFirstAt.getOrPut(key) { nowAt }
        // 还在 4s 窗口内 → 顺延 1.2s 等下一句；已超窗口 → 立即收口，不再让刷屏拖后。
        val delay = if (nowAt - firstAt >= NOTIF_BATCH_MAX_MS) 0L else NOTIF_BATCH_MS
        val r = Runnable { flushNotifBatch(key) }
        notifBatchTimer[key] = r
        main.postDelayed(r, delay)
    }

    private fun flushNotifBatch(conv: String) {
        val msgs = synchronized(notifBatch) { notifBatch.remove(conv) } ?: return
        notifBatchTimer.remove(conv)
        notifBatchFirstAt.remove(conv)
        if (destroyed || !prefs.enabled) {
            Log.d(TAG, "notif-batch: drop destroyed=$destroyed enabled=${prefs.enabled}")
            return
        }
        // 不再立即分析：投入通知轮 FIFO 队列，一次只处理一个会话，发送前重开目标会话。
        enqueueNotifRound(NotifRound(conv, msgs.toList()))
    }

    /** 把一个攒好批的会话轮次入队；空闲则立即开跑，忙则按 conv 合并/排队。 */
    private fun enqueueNotifRound(round: NotifRound) {
        if (destroyed || !prefs.enabled) return
        // 不回复名单：命中即整轮丢弃（不分析、不回复、不排队）。这是最早也最省的闸口——
        // 过了这里才会烧 AI 额度，放更晚就来不及了。
        if (prefs.isNoReply(round.conv)) {
            Log.i(TAG, "notif-round: dropped conv=${round.conv} (no-reply list)")
            return
        }
        if (!notifRoundBusy) {
            startNotifRound(round)
            return
        }
        // 已有轮次在跑
        val idx = notifRoundQueue.indexOfFirst { it.conv == round.conv }
        when {
            idx >= 0 -> {
                // 同一会话在排队中又攒了一批：合并去重，稍后一并分析（mergeInto 仍会补历史）。
                val prev = notifRoundQueue[idx]
                val merged = prev.msgs.toMutableList()
                round.msgs.forEach { m -> if (merged.none { it.sender == m.sender && it.text == m.text }) merged.add(m) }
                notifRoundQueue[idx] = prev.copy(msgs = merged)
                Log.i(TAG, "notif-round: merged into queued conv=${round.conv} (+${round.msgs.size}) depth=${notifRoundQueue.size}")
            }
            round.conv == notifRoundConv -> {
                // 正是当前活跃会话又来消息：文本已 appendFromNotification 进历史，
                // 本轮 runAnalysis 的 mergeInto 会看到，无需再排一轮（避免同会话连回两次）。
                Log.i(TAG, "notif-round: conv=${round.conv} already active, folded into history")
            }
            notifRoundQueue.size >= NOTIF_QUEUE_MAX -> {
                // 队列已满（群刷屏）：挤掉排队最久的那个。刚发消息的人还在等回复，
                // 排了几分钟的那个多半早就不等了——保新不保旧。被挤掉的文本已在 history 里，
                // 下一条消息会带着完整上下文重新触发。
                val dropped = notifRoundQueue.removeAt(0)
                Log.w(TAG, "notif-round: queue full($NOTIF_QUEUE_MAX) -> drop oldest conv=${dropped.conv}")
                notifRoundQueue.add(round)
                Log.i(TAG, "notif-round: queued conv=${round.conv} depth=${notifRoundQueue.size}")
            }
            else -> {
                notifRoundQueue.add(round)
                Log.i(TAG, "notif-round: queued conv=${round.conv} (busy=${notifRoundConv}) depth=${notifRoundQueue.size}")
            }
        }
    }

    /**
     * 跑一个通知轮：先用该会话暂存的 PendingIntent 把微信重开到"这个"会话（8.0.78 读不到
     * 标题，这是唯一可靠锚点），等微信前台落定后再分析+回复。多个会话同时来消息时，
     * 这一步保证每条回复都填进各自正确的窗口，而不是当前恰好前台的任意会话。
     */
    private fun startNotifRound(round: NotifRound) {
        notifRoundBusy = true
        notifRoundConv = round.conv
        notifRoundSeq++
        val mySeq = notifRoundSeq
        Log.i(TAG, "notif-round: START conv=${round.conv} msgs=${round.msgs.size} seq=$mySeq queueDepth=${notifRoundQueue.size}")

        // 保鲜：排队太久的会话直接跳过。回一条迟到几分钟的回复，体验比不回更差。
        val ageMs = System.currentTimeMillis() - round.enqueuedAt
        if (ageMs > NOTIF_ROUND_TTL_MS) {
            Log.w(TAG, "notif-round: conv=${round.conv} stale (${ageMs}ms > ${NOTIF_ROUND_TTL_MS}ms) -> skip")
            finishNotifRound(mySeq)
            return
        }

        // 兜底防队列卡死（分析挂住 / 发送无回调时也能推进）。
        scheduleNotifAdvance(NOTIF_HARD_MAX_MS)
        // 锚点：只认"新鲜"的 PendingIntent（群改名/会话重建后的旧 intent 会把微信开错会话）。
        val pi = if (prefs.autoOpenChat) NotifIntentStore.fresh(round.conv) else null
        // 本轮是否亲手把微信重开到了这个会话——锚点的第一要件，见 [resolveNotifAnchor]。
        notifRoundReplayed = pi != null
        if (pi != null) {
            Log.i(TAG, "notif-round: reopen conv=${round.conv} via saved PendingIntent")
            runCatching { pi.send() }
        } else if (prefs.autoOpenChat) {
            Log.w(TAG, "notif-round: no saved PendingIntent for conv=${round.conv}, relying on current fg")
        }
        if (pi == null && !prefs.autoOpenChat) {
            // 既没有重放动作、也不允许开窗：微信不会自己到前台，傻等纯属拖慢队列——直接分析。
            resolveNotifAnchor(round, mySeq)
        } else {
            // 锚点必须在"重放之后、微信前台落定"时才能判：重放瞬间微信还没起来，此刻判必假阴性。
            awaitWeChatForegroundThen { resolveNotifAnchor(round, mySeq) }
        }
    }

    /**
     * 微信前台落定后判定本轮锚点。**没有锚点就绝不自动发送**：探针读不到标题，回复会
     * 填进"此刻恰好前台"的任意会话——把私聊的话发进群里，这种错比不回严重得多。
     *
     * 锚点成立要三条同时满足：本轮用新鲜 PendingIntent 亲手重放过该会话 +
     * 微信确实在前台 + 设备没锁屏（锁屏时发出去用户看不见，也不该由机器代发）。
     */
    private fun resolveNotifAnchor(round: NotifRound, seq: Long) {
        if (seq != notifRoundSeq) {
            Log.d(TAG, "notif-round: anchor resolve stale seq=$seq cur=$notifRoundSeq -> ignored")
            return
        }
        val locked = deviceLocked()
        val fg = weChatForeground
        // 微信 8.0.78 把聊天页无障碍树剥掉，lastWindowClass 退化为壳类名（android.widget.FrameLayout），
        // 拿不到 ChattingUI——再要求 onChatPage 会让所有通知轮被误判无锚点、一条都发不出去（实测退回此坑）。
        // 锚点改为建立在"新鲜 PendingIntent 亲手重放该会话 + 微信前台 + 未锁屏"上：重放本身是系统保证跳到
        // 该会话，且发送链路双保险（fillInput 找不到输入框即失败、sendWeChatByVerifiedTap 验证绿键消失）
        // 已兜住"重放失效开到列表页"的发错风险，无需再用不可靠的 onChatPage 否决。
        // 仅当能明确识别微信停在会话列表页（LauncherUI）时才否决——剥树下拿不到该类名，自然不触发。
        val clearlyOnListPage = lastWindowClass.contains("LauncherUI", ignoreCase = true)
        notifRoundAnchorless = !(notifRoundReplayed && fg && !locked && !clearlyOnListPage)
        if (notifRoundAnchorless) {
            Log.w(TAG, "notif-round: NO ANCHOR conv=${round.conv} (replayed=$notifRoundReplayed fg=$fg " +
                "locked=$locked listPage=$clearlyOnListPage class=$lastWindowClass) -> analyze only, no auto-send")
        }
        startNotifAnalysis(round, seq)
    }

    /** 设备是否处于锁屏/未解锁状态。无法判定（服务异常）时按未锁处理，宁可多试一次。 */
    private fun deviceLocked(): Boolean {
        val km = runCatching { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }.getOrNull()
            ?: return false
        return runCatching {
            km.isKeyguardLocked || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && km.isDeviceLocked)
        }.getOrDefault(false)
    }

    /** 通知轮的分析主体：用通知文本构造快照驱动一次分析+回复。 */
    private fun startNotifAnalysis(round: NotifRound, seq: Long) {
        if (destroyed || !prefs.enabled) { finishNotifRound(seq); return }
        // 后台纯通知场景：微信在后台，无障碍没抓到聊天窗口，session.target 可能为 null，
        // 旧代码用 session.token() 会直接 return、连日志都不打（静默黑洞）。改用
        // beginNotification 构造逻辑 target，绕开前台窗口假设，让通知文本真正驱动分析+回复。
        val token = session.beginNotification(PKG_WECHAT, round.conv)
        val snapshot = ChatSnapshot(round.conv, round.msgs, note = NOTIFY_NOTE)
        Log.i(TAG, "notif-batch: conv=${round.conv} msgs=${round.msgs.size} senders=${round.msgs.map { it.sender }.distinct()} -> single analyze+reply")
        finishOcrSnapshot(snapshot, PKG_WECHAT, manual = false, token = token)
    }

    /**
     * 结束序号为 [seq] 的通知轮并推进队列里的下一个。序号不匹配（上一轮滞后的发送回调）
     * 时直接忽略，避免干扰正在进行的新一轮。
     */
    private fun finishNotifRound(seq: Long) {
        if (seq != notifRoundSeq) {
            Log.d(TAG, "notif-round: stale finish seq=$seq cur=$notifRoundSeq -> ignored")
            return
        }
        main.removeCallbacks(notifAdvanceRunnable)
        val next = if (notifRoundQueue.isNotEmpty()) notifRoundQueue.removeAt(0) else null
        if (next == null) {
            notifRoundBusy = false
            notifRoundConv = null
            // 通知轮空了：趁空闲把持久化的失败回复补发掉（进程重启/切走会话后遗留的）。
            drainPendingSends()
            return
        }
        if (destroyed || !prefs.enabled) {
            Log.d(TAG, "notif-round: next dropped (destroyed/enabled off)")
            notifRoundBusy = false
            notifRoundConv = null
            return
        }
        Log.i(TAG, "notif-round: advance -> next conv=${next.conv} remaining=${notifRoundQueue.size}")
        // 延到下一个消息再开跑：调用方（分析回调 / 发送回调 / bail）往往还处在同一条消息里，
        // analyzing 之类的状态尚未复位，此刻立刻开跑会被 runAnalysis 开头的
        // `if (analyzing) return` 静默吞掉——表现为"第二个会话永远不回"。
        // busy 在这段间隙保持 true，期间新到的会话照常排队，不会插队抢跑。
        main.post {
            if (!destroyed && prefs.enabled) startNotifRound(next)
            else { notifRoundBusy = false; notifRoundConv = null }
        }
    }

    /** 重新排一次"推进队列"定时器（后到覆盖先到，取更早的触发）。 */
    private fun scheduleNotifAdvance(delayMs: Long) {
        main.removeCallbacks(notifAdvanceRunnable)
        advanceHoldTicks = 0
        main.postDelayed(notifAdvanceRunnable, delayMs)
    }

    /** 轮询等待微信前台落定（重开 PendingIntent 后需要点时间），超时(~2s)也放行。 */
    private fun awaitWeChatForegroundThen(attempt: Int = 0, block: () -> Unit) {
        if (destroyed || !prefs.enabled) return
        if (weChatForeground || attempt >= NOTIF_FG_WAIT_TICKS) {
            if (attempt >= NOTIF_FG_WAIT_TICKS) Log.w(TAG, "notif-round: WeChat fg wait timeout, proceeding anyway")
            block()
            return
        }
        main.postDelayed({ awaitWeChatForegroundThen(attempt + 1, block) }, 100L)
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
        // 持久化未发队列初始化；进程重启后把上次遗留的失败回复重新挂上，会话下次活跃自动补发。
        SendQueueStore.init(this)
        main.postDelayed({ drainPendingSends() }, 3000L)
        // 硬证据日志：无障碍能力位（AOSP AccessibilityServiceInfo 常量）：
        //   0x01=RETRIEVE_WINDOW_CONTENT  0x20=CAN_PERFORM_GESTURES  0x80=CAN_TAKE_SCREENSHOT
        // 2026-10-16 实测：修前 capabilities=129（1+128）**缺 0x20** → dispatchGesture
        // 一律静默失败（返 false、触摸事件根本没注入），「sendTap: dispatching tap at
        // (983,2024)」打得再准也点不动发送键，输入框 12 个字纹丝不动 3 轮后放弃——
        // 「点了没发出」的根因（同坐标 adb shell input tap 一戳就发，硬对照已证）。
        // config_disguised.xml 加 android:canPerformGestures="true" 后 =161（1+32+128），
        // 手势位已在。**注意：别再把 16(magnification) 当手势位查——我犯过这个错，
        // 日志显示 gesture=false 是误报，白绕一轮。**
        val caps = serviceInfo?.capabilities ?: 0
        Log.i(TAG, "a11y capabilities=$caps gesture=${caps and 0x20 != 0} screenshot=${caps and 0x80 != 0}")
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
        overlay?.onNoReplyToggle = { toggleNoReply() }
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
        // 微信 8.0.78 把聊天页的无障碍树剥掉（rootInActiveWindow 恒 null），只认 root 会把
        // "微信明明在前台"误判成不在——每轮都被判成无锚点、一条都发不出去。补两条不依赖树
        // 的信号兜底：事件包名、最近一次窗口类名（ChattingUI 属于 com.tencent.mm）。
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        val evPkg = event.packageName?.toString()
        weChatForeground = when {
            rootPkg != null -> rootPkg == PKG_WECHAT
            evPkg == PKG_WECHAT -> true
            else -> lastWindowClass.startsWith("com.tencent.mm")
        }
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> maybeCapture()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 内容变化/滚动事件在动画页、视频通话页会洪泛：实测微信 VoIP 页
                // 10ms 内 6 轮，每轮都取树 + 提取 +（可能）OCR，纯耗电耗 CPU，
                // 还把 logcat 冲出 "Unexpected EOF" 断流。窗口状态变化是真实状态
                // 切换，永远放行；这两类加节流地板——新消息气泡以秒级到来，
                // 300ms 延迟无感，洪泛场景直接降两个数量级。
                if (now - lastCycleAt >= CYCLE_MIN_INTERVAL_MS) {
                    lastCycleAt = now
                    maybeCapture()
                }
            }
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

    /** 上一次内容变化驱动的捕获时刻，配 [CYCLE_MIN_INTERVAL_MS] 防洪泛。 */
    private var lastCycleAt = 0L

    /**
     * While the soft keyboard is up, [rootInActiveWindow] is the IME window and its
     * tree carries no chat content, so adapters report "not a chat". The chat
     * Activity window is still live behind the IME and reachable via [getWindows];
     * return its root so callers can read the real conversation. Skips
     * TYPE_INPUT_METHOD windows and anything that is not WeChat.
     */
    private fun weChatChatRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // getWindows() 个别机型会返回 null；循环体逐窗 catch——AccessibilityWindowInfo.getRoot()
        // 对已失效/回收的窗口会直接抛 NPE（2026-10-15 实测崩溃栈：onAccessibilityEvent →
        // weChatChatRoot → getRoot → NPE），不是判空能挡的，必须 try。
        for (w in getWindows() ?: emptyList()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = try {
                w.root
            } catch (_: Throwable) {
                null
            } ?: continue
            if (r.packageName?.toString() == PKG_WECHAT) return r
        }
        return null
    }

    /**
     * 微信窗口在屏幕上的实际 bounds。半屏回复界面（ColorOS 闪回小窗 / 通知横幅
     * 半屏回复）下微信窗口只占屏幕一部分，所有按屏幕比例算的坐标（输入栏、
     * 发送键）都会点到窗外——必须先取真实窗口边界再换算。
     * FLAG_SECURE 只剪内容不剪节点坐标，root.getBoundsInScreen 仍可读。
     */
    private fun weChatWindowBounds(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // 2026-10-16 修复（08:52 实测教训）：微信会同时挂多个无障碍窗口——聊天主窗
        // **加各种 PopupWindow**（表情面板/更多面板/语音面板等）。旧代码取「第一个
        // 拿到非空 bounds 的微信 root」，Popup 的 root 只有一百来像素（实测吞下一个
        // 103x103 的假矩形 Rect(448,2065-551,2168)）→ 按比例算发送键 x = 448+103*0.9111
        // = 541，比真键 (989,2035) 偏了 450px，三连 tap 全点在输入框里什么都没触发
        // （日志 `box still has 11 chars -> not sent, retry`×3，用户看到「没回复」）。
        // 改为：收集全部候选，优先 TYPE_APPLICATION，取面积最大的真实窗口。
        var bestApp: Rect? = null
        var bestAppArea = 0L
        var bestOther: Rect? = null
        var bestOtherArea = 0L
        for (w in getWindows() ?: emptyList()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = try {
                w.root
            } catch (_: Throwable) {
                null
            } ?: continue
            if (r.packageName?.toString() != PKG_WECHAT) continue
            val b = Rect()
            try {
                r.getBoundsInScreen(b)
            } catch (_: Throwable) {
                continue
            }
            if (b.isEmpty) continue
            val area = b.width().toLong() * b.height().toLong()
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (area > bestAppArea) {
                    bestAppArea = area
                    bestApp = b
                }
            } else if (area > bestOtherArea) {
                bestOtherArea = area
                bestOther = b
            }
        }
        val chosen = bestApp ?: bestOther
        if (bestOther != null && bestOther !== bestApp) {
            Log.i(TAG, "weChatWindowBounds: rejected popup-sized window $bestOther, using $chosen")
        }
        return chosen
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
        Log.i(TAG, "runAnalysis enter manual=$manual hasKey=${prefs.hasKey()} analyzing=$analyzing destroyed=$destroyed enabled=${prefs.enabled}")
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
                // 本轮启动时所属的通知轮序号。分析跑完时队列可能已被本轮自己推进到下一轮
                // （冷却 / 无锚点会提前收尾），这时绝不能再给新一轮重排一个 6s 推进定时器——
                // 那会在 6s 处把下一轮硬砍掉。只认"还是我这一轮"才排。
                val roundSeq = if (token.target.windowId == ConversationSession.NOTIF_WINDOW) notifRoundSeq else -1L
                fun completed() {
                    remaining--
                    if (remaining <= 0) {
                        analyzing = false
                        analysisTasks.clear()
                        main.removeCallbacks(analysisWatchdog)
                        finishRound()
                        Log.d(TAG, "analysis round finished")
                        // 通知轮：分析已跑完，转入"发送落定"宽限；发送回调先到则立即推进，
                        // 否则到点(SEND_DWELL)兜底推进，绝不让第二个会话卡在这一轮后面。
                        if (roundSeq >= 0 && notifRoundBusy && roundSeq == notifRoundSeq) {
                            scheduleNotifAdvance(SEND_DWELL_MS)
                        }
                    }
                }
                // 兜底看门狗：不管走哪条路径，超时没归零就强制放开，
                // 绝不允许把面板永久冻在「分析中…」。
                main.postDelayed(analysisWatchdog, ANALYSIS_TIMEOUT_MS)

                val judgeQueued = submitAnalysis {
                    val judgment = client.judge(augmented, rel, ctx)
                    main.post {
                        Log.i(TAG, "judge done err=${judgment.error?.take(80)}")
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
                        Log.i(TAG, "reply done ranked=${ranked.size} err=${replyError?.take(80)} isCurrent=${isCurrent(token)}")
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
        // 命中 429 限流 / 免费额度耗尽（4006）：记下时间戳，自动分析进入冷却（见 BUSY_COOLDOWN_MS），
        // 不让无人值守模式反复撞同一道限流墙。额度耗尽要等约 24h（滚动窗口），尤其不能硬撞。
        if (roundErr == ErrCatalog.Kind.BUSY || roundErr == ErrCatalog.Kind.QUOTA) {
            lastBusyAt = System.currentTimeMillis()
        }
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
        val notif = token.target.windowId == ConversationSession.NOTIF_WINDOW
        Log.i(TAG, "maybeAutoSendBest enter notif=$notif autoFillBest=${prefs.autoFillBest} ranked=${ranked.size} latestFrom=${snapshot.latestFrom} replyError=$replyError")
        // 通知轮的每一条提前返回都必须收尾轮次：否则队列要白等 SEND_DWELL 才轮到下一个会话。
        fun bail(reason: String) {
            Log.i(TAG, "auto-send skipped: $reason")
            if (notif) finishNotifRound(notifRoundSeq)
        }
        if (!prefs.autoFillBest) { bail("autoFillBest off"); return }
        if (token.target.pkg != PKG_WECHAT) { bail("not WeChat"); return }
        if (replyError != null) { bail("reply failed: ${replyError.take(40)}"); return }
        if (ranked.isEmpty()) { bail("no candidates"); return }
        if (snapshot.latestFrom != "other") { bail("latest bubble is not from the other side"); return }
        val best = ranked.maxByOrNull { it.prob } ?: ranked.first()
        val text = sanitizeReply(best.text)
        if (text.isEmpty()) { bail("best candidate empty after sanitize"); return }
        // 「同一屏只回一次」只对前台树路径有意义（同一画面会被反复 OCR 出同一批气泡）。
        // 通知轮**绝不能**用它：通知 = 对方新说的一句话，若与几分钟前那句字面相同（"在吗"），
        // 签名撞车会让这句此后再也不自动回复。通知轮的去重交给攒批 + 会话冷却 + 同文本拦截。
        if (!notif) {
            val sig = snapshot.signature()
            if (sig == lastAutoSentSig) { bail("already answered this screen state"); return }
            lastAutoSentSig = sig
        }
        val draft = inputFor(token)?.text?.toString()?.trim()
        if (!draft.isNullOrEmpty()) { bail("input box not empty (user is typing)"); return }
        val convKey = snapshot.title ?: ""
        val now = System.currentTimeMillis()
        // 同一会话冷却：刚回过就别再回（真人连发 / 通知重复投递会触发连回两次，一眼假）。
        if (notif) {
            val lastAt = lastAutoSentAtByConv[convKey]
            if (lastAt != null && now - lastAt < CONV_COOLDOWN_MS) {
                bail("conv='$convKey' cooled down (${(now - lastAt) / 1000}s ago)")
                return
            }
        }
        // 无锚点：不知道前台到底是哪个会话，回复填进去就可能发错人（私聊话进群）。
        // 只给建议、绝不自动发送——这种错比不回严重得多。
        if (notif && notifRoundAnchorless) {
            Log.w(TAG, "auto-send blocked: no anchor for conv='$convKey' -> suggestion only, not sending")
            overlay?.toast(t("没定位到该会话，未自动发送", "Couldn't open that chat — not sent"))
            finishNotifRound(notifRoundSeq)
            return
        }
        // 与上一次发给该会话的话一模一样 → 不发（去标点空白后比较）。只在有记录时拦，
        // 绝不影响首轮；不同内容照发。
        val prev = lastSentTextByConv[convKey]?.first
        if (notif && prev != null && sameReply(prev, text)) {
            bail("identical to the reply just sent to '$convKey'")
            return
        }
        if (notif && prefs.autoSend) {
            lastAutoSentAtByConv[convKey] = now
            lastSentTextByConv[convKey] = text to now
            evictExpired(now)
        }
        Log.i(TAG, "auto-send best(${(best.prob * 100).toInt()}%): ${text.take(24)}")
        // 记录当前发送目标，供发送失败落盘（failAndQueue）按会话持久化，不丢队列。
        currentSendConv = convKey
        currentSendText = text
        // 通知轮：发送真正落定后推进队列（处理下一个会话）；前台/手动轮不涉及队列。
        // 回传 sent：只有真发出去了才把回复写进历史——写错等于污染 AI 对"我说过什么"的认知。
        // 无论成败都 finishNotifRound 推进：失败那条已由 sendWeChatByVerifiedTap 落盘到
        // SendQueueStore（drainPendingSends 会话下次活跃再补发），这里只负责让通知轮不卡死。
        val mySeq = notifRoundSeq
        val onSendComplete: ((Boolean) -> Unit)? = if (notif) ({ sent ->
            // 自己说过的话也要进历史：微信把气泡对无障碍隐藏，读不到"我"发过什么，
            // 不记的话下一轮模型不知道自己已经答过，会对同一个问题换个说法再答一遍。
            if (sent) ConversationHistory.appendOwnReply(PKG_WECHAT, convKey, text)
            finishNotifRound(mySeq)
        }) else null
        main.postDelayed({ fillInput(token, text, onSendComplete = onSendComplete) }, 500L)
    }

    /** 发给真人的文本清洗：去掉包裹引号与模型爱加的「回复：」前缀，压缩多余空白。 */
    private fun sanitizeReply(raw: String): String {
        var s = raw.trim()
        while (s.length >= 2) {
            val a = s.first(); val b = s.last()
            val wrapped = (a == '"' && b == '"') || (a == '“' && b == '”') ||
                (a == '\'' && b == '\'') || (a == '「' && b == '」')
            if (!wrapped) break
            s = s.substring(1, s.length - 1).trim()
        }
        s = REPLY_PREFIX.replace(s, "").trim()
        return s.replace(REPLY_WS, " ").trim()
    }

    /** 忽略空白与标点后的完全相等——只拦"同一句话再说一遍"，不同内容一律放行。 */
    private fun sameReply(a: String, b: String): Boolean {
        fun norm(s: String) = s.filterNot { it.isWhitespace() || it in REPLY_PUNCT }
        val na = norm(a); val nb = norm(b)
        return na.isNotEmpty() && na == nb
    }

    /**
     * 冷却/去重表按**时间**淘汰，而不是 size 到了就 clear()。
     * 一刀切清空会把别的会话的冷却也抹掉，正好制造"连回两次"这个最露馅的毛病。
     */
    private fun evictExpired(now: Long) {
        val it1 = lastAutoSentAtByConv.iterator()
        while (it1.hasNext()) if (now - it1.next().value > CONV_COOLDOWN_MS) it1.remove()
        val it2 = lastSentTextByConv.iterator()
        while (it2.hasNext()) if (now - it2.next().value.second > REPLY_DEDUP_TTL_MS) it2.remove()
        // 极端兜底：真堆到几百个会话（群刷屏）时时间淘汰也拦不住，才允许整体清空。
        if (lastAutoSentAtByConv.size > 256) lastAutoSentAtByConv.clear()
        if (lastSentTextByConv.size > 256) lastSentTextByConv.clear()
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
        if (!prefs.isAllowed(snapshot.title)) { Log.i(TAG, "fo: blocked isAllowed=false title=${snapshot.title}"); leaveConversation(); overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        overlay?.currentTitle = snapshot.title
        overlay?.currentPkg = pkg
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            Log.i(TAG, "fo: dedup notif sig match -> idle (no re-analysis)")
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
        Log.i(TAG, "fo: auto=$auto (ocrAutoAnalyze=${prefs.ocrAutoAnalyze} autoAnalyze=${prefs.autoAnalyze}) msgs=${snapshot.messages.size} title=${snapshot.title} allowed=${prefs.isAllowed(snapshot.title)}")
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis(manual = manual)
        } else {
            Log.i(TAG, "fo: auto off -> showIdle only (no analysis queued)")
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
        // 窗口相对化（半屏兼容）：全屏时 bounds.bottom==屏高，yBase 取到 屏高-IME高，
        // 与旧屏幕比例公式逐像素一致；半屏小窗时取「小窗底」与「屏底-IME高」中更靠上
        // 的一个——输入栏永远紧贴这两个上沿的下方的那个（三种窗口形态自洽）。
        val b = weChatWindowBounds()
        val left = b?.left?.toFloat() ?: 0f
        val width = b?.width()?.toFloat() ?: dm.widthPixels.toFloat()
        val yBase = listOfNotNull(b?.bottom?.toFloat(), dm.heightPixels - imeH)
            .filter { it > 0f }
            .minOrNull() ?: dm.heightPixels.toFloat()
        return left + width * 0.462f to (yBase - barHalf)
    }

    /**
     * 从无障碍树里找微信发送按钮（SEND_ID）的真实 bounds 中心。
     *
     * 2026-10-16 08:52 实测教训：键盘弹起时 `rootInActiveWindow` 常常是 **IME 窗口**
     * （SoftInputWindow），它的树里没有微信的 bql 节点——旧代码只搜这一个 root，
     * 静默失败后回落到比例公式，又被假窗口矩形带偏 x（差 450px），三连 tap 全打空。
     * 改为：rootInActiveWindow + getWindows() 里**全部**非 IME 微信窗口 root 都搜，
     * 第一个拿到非空 bounds 的节点就用。不要求 refresh() 成功——节点过期时
     * getBoundsInScreen 通常仍可读（08:52 的 `clickSend: treeNode=true` 日志证明
     * 节点确实在树里，旧代码的 takeIf{refresh()} 却把它丢了）。
     */
    private fun sendButtonCenterFromTree(): Pair<Float, Float>? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (w in getWindows() ?: emptyList()) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val r = try {
                    w.root
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (r.packageName?.toString() == PKG_WECHAT) roots.add(r)
            }
        }
        for (r in roots) {
            val n = try {
                r.findAccessibilityNodeInfosByViewId(WeChatAdapter.SEND_ID)?.firstOrNull()
            } catch (_: Throwable) {
                null
            } ?: continue
            val rect = Rect()
            try {
                n.getBoundsInScreen(rect)
            } catch (_: Throwable) {
                continue
            }
            if (rect.width() > 0 && rect.height() > 0) {
                return rect.centerX().toFloat() to rect.centerY().toFloat()
            }
        }
        return null
    }

    private fun wxSendPoint(): Pair<Float, Float> {
        // 2026-10-16 修复：坐标优先取树里发送按钮的真实 bounds 中心。
        //
        // 实测事实：微信 8.0.78 只剥掉了气泡（id/bkl，bubbles=0），**发送按钮
        // SEND_ID 仍在无障碍树里**（日志 treeNode=true）。旧代码要求
        // `isVisibleToUser && isEnabled` 全过才走树路径，微信把这两项关掉反自动化
        // → 判定失败 → 回落到下面的屏幕比例公式，精度就是在这里丢的。
        // 实测后果：算出 (983,2024) 点击，输入框 12 字纹丝不动，重试 3 次后放弃，
        // 草稿烂在框里（用户看到的就是「没回复」）。
        //
        // 节点被标记不可见时 getBoundsInScreen() 通常仍返回有效矩形，比比例公式准。
        val fromTree = sendButtonCenterFromTree()
        if (fromTree != null) {
            Log.i(TAG, "wxSendPoint: tree bounds -> (${fromTree.first.toInt()},${fromTree.second.toInt()})")
            return fromTree
        }

        // 兜底：屏幕比例经验公式。
        // 绿按钮中心 = (0.9111W, 图标行垂直中心)。图标行紧贴 IME 窗顶，所以
        // y = H - IME实际窗口高 - 图标行半高(56dp*density/2)。IME 窗口是 wrap_content
        // （实测被内容缩到 304px），用 ProbeImeBus.imeWindowHeightPx 动态取实际高度。
        // 2026-10-14 真机 PIL 标定（1080x2412, 微信 8.0.78）：imeH=304 时
        // 2412-304-77=2031，与绿键实测中心 (984,2031) 吻合；**回复多行时绿键
        // 并不移位**（多行只撑高上面的输入框，图标行位置不变——曾把已发气泡
        // 误读成按钮，虚惊一场）。输入框空时同一位置变灰色「+」。
        // 半屏兼容（2026-10-15）：全部改按微信窗口实际 bounds 相对换算——
        // 全屏时与旧屏幕比例公式逐像素一致（bounds=全屏），半屏小窗自动跟随窗口边界。
        val dm = resources.displayMetrics
        val imeH = imeWindowHeight()
        val barHalf = com.jev.probe.input.ProbeImeBus.WX_INPUT_BAR_DP * dm.density / 2f
        val b = weChatWindowBounds()
        val left = b?.left?.toFloat() ?: 0f
        val width = b?.width()?.toFloat() ?: dm.widthPixels.toFloat()
        val yBase = listOfNotNull(b?.bottom?.toFloat(), dm.heightPixels - imeH)
            .filter { it > 0f }
            .minOrNull() ?: dm.heightPixels.toFloat()
        val fx = left + width * 0.9111f
        val fy = yBase - barHalf
        // 出窗告警：树路径失败时比例公式是最后依靠，算出的点若落在窗口外
        // （08:52 假窗口矩形事故的形态：点 y=2024 高出窗顶 2065），tap 必然打空，
        // 日志里必须留痕，别又静默错过。
        if (b != null && (fx < b.left - 8 || fx > b.right + 8 || fy < b.top - 8 || fy > b.bottom + 8)) {
            Log.w(TAG, "wxSendPoint: fallback (${fx.toInt()},${fy.toInt()}) OUTSIDE window $b — tree lookup failed, tap will likely miss")
        }
        return fx to fy
    }

    /** IME 窗口实际高度：IME 活着时取其发布值（<100px 是窗口收缩瞬间的假值，弃用），否则回落键盘同高兜底。
     *  2026-10-16：新增「收起」态（imeCollapsed）→ 返回 0，输入栏坐标改贴窗口底边。 */
    private fun imeWindowHeight(): Float {
        if (com.jev.probe.input.ProbeImeBus.imeCollapsed) return 0f
        // 2026-10-16 19:04 复盘：imeWindowHeightPx 是 IME 侧发布值，键盘收起后不会重发布，
        // 语音态下会残留上一次的 304 → 聚焦/发送坐标全按键盘态算（y=2024），点哪都错。
        // 没有实时 IME 窗口时一律按 0（坐标改贴窗口底边），与 imeCollapsed 同语义。
        if (!anyImeWindowPresent()) return 0f
        val h = com.jev.probe.input.ProbeImeBus.imeWindowHeightPx
        if (h >= 100) return h.toFloat()
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
     * 当前是否有任意输入法窗口（含系统讯飞）。用于分辨「微信语音态」与「探针没被设为当前输入法」：
     *  - 语音态：没有文本框，任何输入法都不会起 → 无 IME 窗口。
     *  - 非探针输入法：系统键盘起着 → 有 IME 窗口（只是不是探针）。
     * 只有前者才该点语音/键盘切换钮。
     */
    private fun anyImeWindowPresent(): Boolean {
        return runCatching {
            getWindows()?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        }.getOrDefault(false)
    }

    /**
     * 微信语音态下点输入栏最左的「语音/键盘」切换钮，切回键盘（文本）态，让输入框重新出现。
     *
     * 为什么必须点它：语音态输入栏是「按住 说话」大按钮、没有文本框，点输入栏中心只是触发录音，
     * 键盘永远不弹，探针输入法挂不上 → 自动回复只能走剪贴板。切换钮在输入栏最左，点一下即回文本态。
     * 这是微信内部 UI 操作，不是切换系统输入法，不碰「App 不替用户切输入法」铁律。
     *
     * 坐标（2026-10-16 真机标定，双向确认）：切换钮在输入栏最左，x = 屏宽 * [WX_VOICE_TOGGLE_X_RATIO]（≈84@1080）；
     * 语音态键盘收起、输入栏贴屏底，y 取「窗口/屏底 - 半输入栏高」（≈2335@2412），与 wxFocusPoint 同源口径，半屏小窗自适应。
     */
    /**
     * 微信主窗 root（面积最大的非 IME 微信窗口）。聊天主窗之外微信还会挂各种
     * PopupWindow（表情/更多/语音面板），其 root 只有一百来像素——2026-10-16
     * 08:52 实测踩过：拿 Popup root 算坐标全打偏。树查找/dump 一律以主窗为准。
     */
    private fun weChatMainRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return rootInActiveWindow?.takeIf { it.packageName?.toString() == PKG_WECHAT }
        }
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        for (w in getWindows() ?: emptyList()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = try {
                w.root
            } catch (_: Throwable) {
                null
            } ?: continue
            if (r.packageName?.toString() != PKG_WECHAT) continue
            val b = Rect().also { r.getBoundsInScreen(it) }
            val area = b.width().toLong() * b.height()
            if (area > bestArea) {
                bestArea = area
                best = r
            }
        }
        return best
    }

    /**
     * 诊断（2026-10-16 21:10 重定标）：列出微信挂在无障碍系统里的全部窗口。
     * 语音/表情/更多面板都会以额外 root 出现（bounds 远小于主窗）。语音分支
     * 点击前先看清单：面板开着就先 BACK 关掉，否则栏位被面板按钮占据。
     */
    private fun logWeChatWindows(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val main = weChatWindowBounds()
        for (w in getWindows() ?: emptyList()) {
            val r = try {
                w.root
            } catch (_: Throwable) {
                null
            } ?: continue
            if (r.packageName?.toString() != PKG_WECHAT) continue
            val b = Rect().also { r.getBoundsInScreen(it) }
            Log.i(TAG, "wxwin[$reason]: type=${w.type} bounds=$b isMain=${main != null && b == main}")
        }
    }

    /**
     * 微信面板是否开着：存在一个非主窗、面积在主窗 1/7~3/5 之间、贴在下半屏的
     * 微信窗口（表情/更多/语音面板的实测特征）。Toast 等系统窗口不属微信包，
     * 天然排除。
     */
    private fun weChatPanelOpen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val main = weChatWindowBounds() ?: return false
        val mainArea = (main.width().toLong() * main.height()).coerceAtLeast(1L)
        for (w in getWindows() ?: emptyList()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = try {
                w.root
            } catch (_: Throwable) {
                null
            } ?: continue
            if (r.packageName?.toString() != PKG_WECHAT) continue
            val b = Rect().also { r.getBoundsInScreen(it) }
            if (b == main) continue
            val area = b.width().toLong() * b.height()
            if (area in (mainArea / 7)..(mainArea * 3 / 5) && b.top > main.height() / 2) return true
        }
        return false
    }

    /**
     * 诊断（2026-10-16 21:10 重定标）：把微信主窗输入栏区域的所有无障碍节点
     * 打出来（class/viewId/text/cd/clickable/bounds）。FLAG_SECURE 只剪内容不剪
     * 节点，发送按钮 bql 已证实在树里——切换钮理应也在，只是 id 未知。这个 dump
     * 把「最左可点击节点 = 切换钮」从猜测变成事实。
     */
    private fun dumpWeChatBarNodes(reason: String) {
        val root = weChatMainRoot() ?: run {
            Log.w(TAG, "wxdump[$reason]: no wechat main root")
            return
        }
        val win = weChatWindowBounds()
        val dm = resources.displayMetrics
        val imeH = imeWindowHeight()
        val barHalf = com.jev.probe.input.ProbeImeBus.WX_INPUT_BAR_DP * dm.density / 2f
        val yBase = listOfNotNull(win?.bottom?.toFloat(), dm.heightPixels - imeH)
            .filter { it > 0f }
            .minOrNull() ?: dm.heightPixels.toFloat()
        val rowTop = yBase - barHalf * 2.4f
        val out = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || count > 120) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.width() > 0 && b.height() > 0 && b.centerY() >= rowTop && b.centerY() <= yBase + barHalf) {
                count++
                out.append("\n  ").append(n.className)
                    .append(" id=").append(n.viewIdResourceName)
                    .append(" text=").append(n.text)
                    .append(" cd=").append(n.contentDescription)
                    .append(" clk=").append(n.isClickable)
                    .append(" b=").append(b)
            }
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        Log.i(TAG, "wxdump[$reason]: rowTop=${rowTop.toInt()} yBase=${yBase.toInt()} nodes=$count$out")
    }

    /**
     * 从树里找语音/键盘切换钮：输入栏行内最左的**小尺寸可点击**节点。
     * 2026-10-16 之前的盲坐标 (0.078W, 屏底-半栏) 从未被真正验证过——旧「双向
     * 标定」把「点输入栏外部收起键盘」误当成「切语音态」，整套校准作废。
     * 树里最左可点击图标即切换钮：文本态它是「切语音」、语音态它是「切键盘」，
     * 同一按钮两种状态。排除宽节点（>30% 窗宽，输入框/容器）与不可点击节点。
     * 找不到才回落坐标公式（并打日志说明回落）。
     */
    private fun voiceToggleCenterFromTree(): Pair<Float, Float>? {
        val root = weChatMainRoot() ?: return null
        val win = weChatWindowBounds()
        val dm = resources.displayMetrics
        val imeH = imeWindowHeight()
        val barHalf = com.jev.probe.input.ProbeImeBus.WX_INPUT_BAR_DP * dm.density / 2f
        val yBase = listOfNotNull(win?.bottom?.toFloat(), dm.heightPixels - imeH)
            .filter { it > 0f }
            .minOrNull() ?: dm.heightPixels.toFloat()
        val rowTop = yBase - barHalf * 2.4f
        val winW = win?.width()?.toFloat() ?: dm.widthPixels.toFloat()
        var bestLeft = Float.MAX_VALUE
        var bestNode: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.width() > 0 && b.height() > 0 &&
                b.centerY() >= rowTop && b.centerY() <= yBase + barHalf &&
                n.isClickable && b.width() < winW * 0.30f && b.left < bestLeft
            ) {
                bestLeft = b.left.toFloat()
                bestNode = n
            }
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        val n = bestNode ?: return null
        val b = Rect().also { n.getBoundsInScreen(it) }
        Log.i(TAG, "voiceToggle: tree hit id=${n.viewIdResourceName} class=${n.className} bounds=$b")
        return b.exactCenterX() to b.exactCenterY()
    }

    private fun tapWeChatVoiceToggle(): Boolean {
        // 优先树节点（2026-10-16 21:10）：切换钮在无障碍树里（FLAG_SECURE 不剪
        // 节点），点真实 bounds 中心；找不到才回落比例坐标——旧坐标从未验证准过。
        val hit = voiceToggleCenterFromTree()
        val dm = resources.displayMetrics
        val b = weChatWindowBounds()
        val left = b?.left?.toFloat() ?: 0f
        val width = b?.width()?.toFloat() ?: dm.widthPixels.toFloat()
        val barHalf = com.jev.probe.input.ProbeImeBus.WX_INPUT_BAR_DP * dm.density / 2f
        val yBase = listOfNotNull(b?.bottom?.toFloat(), dm.heightPixels.toFloat())
            .filter { it > 0f }
            .minOrNull() ?: dm.heightPixels.toFloat()
        val x = hit?.first ?: (left + width * WX_VOICE_TOGGLE_X_RATIO)
        val y = hit?.second ?: (yBase - barHalf)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
            .build()
        Log.i(TAG, "tapWeChatVoiceToggle: tap at (${x.toInt()},${y.toInt()}) source=${if (hit != null) "tree" else "coord-fallback"} ratio=$WX_VOICE_TOGGLE_X_RATIO")
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
    private fun sendWeChatByVerifiedTap(token: ConversationSession.Token, onDone: ((Boolean) -> Unit)? = null) {
        /**
         * 发送动作统一入口：坐标点击优先，被拒（dispatchGesture 返 false，缺 canPerformGestures
         * 能力位）立即走 IME 代发兜底。
         *
         * `dispatchGesture` 需要服务声明 canPerformGestures（能力位 0x20），缺这一位时它
         * 不报错、只返回 false，触摸事件根本没进系统——2026-10-16 实测修前本探针
         * capabilities=129（1+128，无 0x20）就是这种情况。此时继续等「读框复检发现没发出」
         * 纯属浪费，直接改走 IME 的 performEditorAction（微信编辑器支持 IME_ACTION_SEND）。
         */
        var usedIme = false
        fun tapOrIme(): Boolean {
            val (x, y) = wxSendPoint()
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.i(TAG, "sendTap: dispatching tap at (${x.toInt()},${y.toInt()}) win=${weChatWindowBounds()} dispatched=$dispatched")
            if (dispatched) { usedIme = false; return true }
            usedIme = ProbeImeBus.requestSendViaIme()
            Log.w(TAG, "sendTap: dispatchGesture REFUSED (capabilities=${serviceInfo?.capabilities}) -> IME send=$usedIme")
            return usedIme
        }
        // 发送链开始：dwell advance 被门控（sendSettled=false），直到判定「真发出 / 放弃」才放行。
        sendSettled = false
        // 发送前守卫：回复已 commit 进输入框后，某些会话形态（ColorOS 通知半屏/横幅回复）会在
        // 写完字后自动收起 → 此刻微信已不在前台、读框为空是假象。点发送前先确保微信在这个会话
        // 的前台：不在就用该会话的 PendingIntent 重新拉起，等前台落定再继续；拉不回来才放行。
        ensureWeChatFgForSend {
            main.postDelayed({
            if (!isCurrent(token)) {
                // 用户已切走：本次不判失败，把草稿落盘等该会话下次活跃再发（不丢队列）。
                Log.w(TAG, "sendTap: token stale before send -> queue draft for later")
                failAndQueue()
                sendSettled = true
                onDone?.invoke(false)
                return@postDelayed
            }
            var attempt = 0
            // 本次发送链是否已走过「空框恢复」（refocus + rewrite）。走过之后读框仍盲，
            // 不再二次恢复——那会形成 rewrite→give up→落队→立即重发→再 rewrite 的风暴
            // （2026-10-16 15:48 实测：同一会话 30 秒 6 轮，回复直接被放弃）。
            var recoverUsed = false
            // 点发送这一刻读框是否盲（看不到框内容）。盲态下「点后框空」仍是唯一发送
            // 信号，但台账要标记出来，便于和真读到空的情况区分。
            var blindTap = false
            // 读框本轮是否证明过自己：读到过非空文本，或 commit 时读回校验通过
            // （ProbeImeBus.lastWriteVerified）。死读框的「框空」零信息量——
            // 2026-10-16 19:25 假阳性根因：假连接下读框恒空，「框空=已发」连报
            // 3 条 sent，回复从未到达。没有这个证据链，框空一律不判已发送。
            var sawBoxText = false
            // 一次完整发送尝试：读框为空先恢复聚焦 → 点/IME → 复检。
            // 复检：输入框空 = 真发出（FLAG_SECURE 黑屏下唯一可靠信号）。非空 = 没发出 → 退避重试。
            fun attemptSend() {
                if (!isCurrent(token)) {
                    // 用户已切走：本次不判失败，把草稿落盘等该会话下次活跃再发（不丢队列）。
                    Log.w(TAG, "sendTap: token stale before send -> queue draft for later")
                    failAndQueue()
                    sendSettled = true
                    onDone?.invoke(false)
                    return
                }
                val pre = ProbeImeBus.readBox()
                if (!pre.isNullOrBlank()) sawBoxText = true
                else if (ProbeImeBus.lastWriteVerified) sawBoxText = true
                if (pre != null && pre.isBlank()) {
                    if (!recoverUsed) {
                        // 空框 ≠ 一定没填进去：编辑器失焦后 InputConnection 退回系统 fallback 连接，
                        // 读框恒空。先重新聚焦复读、必要时重写草稿，再进来。只恢复一次。
                        recoverUsed = true
                        recoverEmptyBox(token) { ready ->
                            if (ready) attemptSend()
                            else {
                                Log.e(TAG, "sendTap: box empty + unrecoverable -> queue draft for later")
                                failAndQueue()
                                sendSettled = true
                                overlay?.toast(t("消息未填入，稍后重试", "Fill failed — will retry later"))
                                onDone?.invoke(false)
                            }
                        }
                        return
                    }
                    // 恢复过一次仍读不到框：草稿已 commit 成功（completeWrite(true)），
                    // 大概率真在输入框里，只是读不回。放行去点——点空无害，点中这条就真发出；
                    // 一律 give up 只会把会话推进重发风暴（见 recoverUsed 注释）。
                    Log.w(TAG, "sendTap: box still blind after recover -> proceed to tap anyway")
                }
                blindTap = pre != null && pre.isBlank()
                tapOrIme()
                if (usedIme) {
                    // IME 兜底：微信 armed 一个「自动发送 2 秒后发出」倒计时，且探针 IME 持有编辑器
                    // 期间会卡住不跑。轮询到 ~4s 给足触发时间；期间框空即成功，仍非空才认输。
                    var imeChecks = 0
                    fun imePoll() {
                        main.postDelayed({
                            if (!isCurrent(token)) { failAndQueue(); sendSettled = true; onDone?.invoke(false); return@postDelayed }
                            val after = ProbeImeBus.readBox()
                            // 同 sendTap verify 护栏：没有实时键盘窗时「框空」不是发送证据。
                            val sent = after != null && after.isBlank() && anyImeWindowPresent()
                            Log.i(TAG, "sendTap: post-IME verify empty=$sent chars=${after?.length} check=$imeChecks")
                            imeChecks++
                            if (sent) {
                                sendSettled = true
                                appendSendLedger(currentSendConv, true, "ime send verified")
                                overlay?.toast(t("已自动发送", "Sent automatically"))
                                onDone?.invoke(true)
                                return@postDelayed
                            }
                            if (imeChecks < 3) { imePoll(); return@postDelayed }
                            Log.e(TAG, "sendTap: IME send failed after poll -> queue draft for retry")
                            failAndQueue()
                            sendSettled = true
                            overlay?.toast(t("发送未成功，已加入重试队列", "Send failed — queued for retry"))
                            onDone?.invoke(false)
                        }, 1300L)
                    }
                    imePoll()
                    return
                }
                main.postDelayed({
                    if (!isCurrent(token)) { failAndQueue(); sendSettled = true; onDone?.invoke(false); return@postDelayed }
                    val box = ProbeImeBus.readBox()
                    if (box != null && box.isBlank() && anyImeWindowPresent() && sawBoxText) {
                        Log.i(TAG, "sendTap verify: box EMPTY -> message really sent (attempt=$attempt blind=$blindTap)")
                        sendSettled = true
                        appendSendLedger(currentSendConv, true, if (blindTap) "box empty after tap (blind)" else "box empty after tap")
                        overlay?.toast(t("已自动发送", "Sent automatically"))
                        onDone?.invoke(true)
                        return@postDelayed
                    }
                    if (box != null && box.isBlank() && anyImeWindowPresent() && !sawBoxText) {
                        // 死读框防护（2026-10-16 19:25 假阳性根因）：读框本轮从没读到过
                        // 文本、commit 读回校验也没过——「框空」不是发送证据，绝不记已发。
                        // 补一次 IME 代发（连接真就能发出），再复检；仍不可判 → 落队 + 明示。
                        Log.e(TAG, "sendTap verify: box EMPTY but reader never proved itself -> NOT evidence of send (attempt=$attempt)")
                        ProbeImeBus.requestSendViaIme()
                        main.postDelayed({
                            if (!isCurrent(token)) { failAndQueue(); sendSettled = true; onDone?.invoke(false); return@postDelayed }
                            val box2 = ProbeImeBus.readBox()
                            if (!box2.isNullOrBlank()) {
                                Log.w(TAG, "sendTap: IME send dispatched but box still has ${box2.length} chars -> not sent, queue")
                                failAndQueue()
                                sendSettled = true
                                overlay?.toast(t("发送未确认，已加入重试队列", "Send unconfirmed — queued for retry"))
                                onDone?.invoke(false)
                                return@postDelayed
                            }
                            Log.e(TAG, "sendTap: IME send dispatched, reader still blind -> queue (unverified)")
                            failAndQueue()
                            sendSettled = true
                            overlay?.toast(t("发送未确认，请手动发送或稍后重试", "Send unconfirmed — send manually or retry later"))
                            onDone?.invoke(false)
                        }, 1300L)
                        return@postDelayed
                    }
                    if (box != null && box.isBlank()) {
                        // 2026-10-16 19:04 真机翻车：语音态下探针 IME 连着隐藏 EditText，readBox
                        // 恒返回空串，发送点到的是消息列表——「框空=已发」连报 3 条假阳性、
                        // 回复从未到达。没有实时 IME 窗口（= 不在文本键盘态）时框空**不是**
                        // 发送证据：落队重试，等切回文本态再发，绝不记假账。
                        Log.e(TAG, "sendTap verify: box empty but NO IME window -> not evidence of send (voice mode?), queue draft")
                        failAndQueue()
                        sendSettled = true
                        overlay?.toast(t("发送未确认，已加入重试队列", "Send unconfirmed — queued for retry"))
                        onDone?.invoke(false)
                        return@postDelayed
                    }
                    attempt++
                    if (box != null && attempt < MAX_SEND_TRIES) {
                        Log.w(TAG, "sendTap verify: box still has ${box.length} chars -> not sent, retry ($attempt/$MAX_SEND_TRIES) backoff=${sendRetryBackoff(attempt)}ms")
                        main.postDelayed({ attemptSend() }, sendRetryBackoff(attempt))
                        return@postDelayed
                    }
                    if (box == null) {
                        attempt++
                        if (attempt >= MAX_SEND_TRIES) {
                            Log.e(TAG, "sendTap verify: box unreadable after $attempt tries -> queue draft")
                            failAndQueue()
                            sendSettled = true
                            overlay?.toast(t("发送未成功，已加入重试队列", "Send failed — queued for retry"))
                            onDone?.invoke(false)
                            return@postDelayed
                        }
                        // 读框不可用（IME 未激活）：退回截屏软参考。绿键在 → 补点一次；否则信任已发。
                        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
                            if (res is ScreenCapture.Result.Ok && weChatSendButtonPresent(res)) {
                                Log.w(TAG, "sendTap verify: box unreadable + green present -> retry once")
                                main.postDelayed({ attemptSend() }, sendRetryBackoff(attempt))
                            } else if (anyImeWindowPresent()) {
                                // 文本键盘态 + 读框不可用 + 截图无绿键（FLAG_SECURE 恒黑，绿键检测
                                // 本就是死逻辑）→ 维持原设计：信任已发。
                                Log.i(TAG, "sendTap verify: box unreadable, green gone, text mode -> trust sent")
                                sendSettled = true
                                appendSendLedger(currentSendConv, true, "green gone, trust sent")
                                onDone?.invoke(true)
                            } else {
                                // 无键盘窗（语音态/已收起）：框空与截图都没有判别力，不能记已发。
                                Log.e(TAG, "sendTap verify: box unreadable + NO IME window -> queue draft")
                                failAndQueue()
                                sendSettled = true
                                overlay?.toast(t("发送未确认，已加入重试队列", "Send unconfirmed — queued for retry"))
                                onDone?.invoke(false)
                            }
                        }
                        return@postDelayed
                    }
                    // 框非空且次数用尽：失败落盘，等会话下次活跃重发（不丢队列）。
                    Log.e(TAG, "sendTap verify: $attempt attempts exhausted -> queue draft for retry")
                    failAndQueue()
                    sendSettled = true
                    overlay?.toast(t("发送未成功，已加入重试队列", "Send failed — queued for retry"))
                    onDone?.invoke(false)
                }, 1200L)
            }
            attemptSend()
            }, 400L)
        }
    }

    /**
     * 发送失败兜底：把当前草稿按「会话」落盘到 [SendQueueStore]。
     * 次数超限或会话下次活跃都靠 drainPendingSends 重新驱动，确保一条该发的回复不丢。
     * 不在这里弹「失败」噪音——上层（sendWeChatByVerifiedTap）已按场景 toast 过。
     */
    /**
     * 发送台账：每次发送结果（成功/失败）追加一行到外部私有目录 send_ledger.csv。
     * logcat ring buffer 会被系统日志挤掉，导致 flow_check 拿不到「上次到底发没发出去」；
     * 文件是持久的，adb shell 直接可读（ColorOS 允许 shell 列 Android/data）。
     * IO 放后台线程，失败静默——台账只是观测，绝不能影响发送主路。
     */
    private fun appendSendLedger(conv: String?, ok: Boolean, detail: String) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val line = "${System.currentTimeMillis()},${(conv ?: "-").replace(',', ';')}," +
                (if (ok) "sent" else "failed") + "," + detail.replace(',', ';').take(80) + "\n"
            Thread {
                try {
                    val f = java.io.File(dir, "send_ledger.csv")
                    dir.mkdirs() // 首次写入前目录可能还没建（外部目录惰性创建）
                    f.appendText(line)
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    private fun failAndQueue() {
        val conv = currentSendConv ?: run {
            Log.w(TAG, "failAndQueue: no currentSendConv -> drop (cannot persist)")
            return
        }
        appendSendLedger(conv, false, "queue draft for retry")
        val text = (currentSendText ?: lastDraftText) ?: run {
            Log.w(TAG, "failAndQueue: no draft text -> drop")
            return
        }
        val existing = SendQueueStore.all().firstOrNull { it.conv == conv }
        val tries = (existing?.tries ?: 0) + 1
        if (tries > MAX_SEND_TRIES) {
            SendQueueStore.remove(conv)
            Log.w(TAG, "failAndQueue: conv='$conv' tries=$tries exceeded -> drop pending")
            overlay?.toast(t("多次发送失败，已放弃该条", "Gave up after $tries tries"))
            return
        }
        SendQueueStore.add(PendingSend(conv, text, tries, existing?.firstAt ?: System.currentTimeMillis()))
        Log.i(TAG, "failAndQueue: queued conv='$conv' tries=$tries text=${text.take(20)}")
    }

    /**
     * 驱动持久化未发队列：串行重发每条失败回复。重发前确保探针输入法已激活（否则只留提示、
     * 不空转重试）；会话下次活跃（来通知/回前台）或进程重启都会重新触发本函数。TTL 过期清理。
     */
    private fun drainPendingSends() {
        if (drainingPending || destroyed || !prefs.enabled) return
        val now = System.currentTimeMillis()
        SendQueueStore.all().forEach {
            if (now - it.firstAt > PENDING_TTL_MS) {
                SendQueueStore.remove(it.conv)
                Log.w(TAG, "drainPendingSends: expired pending conv='${it.conv}' -> dropped")
            }
        }
        val next = SendQueueStore.all().firstOrNull { now - it.firstAt <= PENDING_TTL_MS } ?: return
        if (next.tries >= MAX_SEND_TRIES) { SendQueueStore.remove(next.conv); return }
        // 探针输入法未激活时点了也发不出去，只留一条提醒，等用户切好再触发。
        if (!ProbeImeBus.isEnabled(this) || !ProbeImeBus.imeActive) {
            Log.i(TAG, "drainPendingSends: probe IME not active -> hold ${next.conv} (${SendQueueStore.all().size} pending)")
            val now = System.currentTimeMillis()
            if (now - lastPendingReminderAt > 60_000L) {
                lastPendingReminderAt = now
                overlay?.toast(t("有 ${SendQueueStore.all().size} 条未发送，切到「AI聊天助手·写入」后自动补发",
                                 "${SendQueueStore.all().size} unsent — switch to the probe keyboard to auto-resend"))
            }
            return
        }
        drainingPending = true
        resendPending(next.conv, next.text) { sent ->
            drainingPending = false
            // 失败必须退避：旧代码立即重排，同一会话几十秒内反复 commit+落账+toast，
            // MAX_SEND_TRIES 瞬间烧光、回复被放弃（2026-10-16 15:48 实测 6 轮/30 秒）。
            // 按已失败次数线性退避、封顶 20s；成功则 1s 后驱动下一条。
            val backoff = if (sent) 1_000L else (2_000L * (next.tries + 1)).coerceAtMost(20_000L)
            main.postDelayed({ drainPendingSends() }, backoff)
        }
    }

    /** 重发一条持久化失败回复：重放该会话 PendingIntent 拉起微信 → 填字 → 走自带重试发送。 */
    private fun resendPending(conv: String, text: String, onResolved: (Boolean) -> Unit) {
        Log.i(TAG, "resendPending: conv='$conv' text=${text.take(20)}")
        if (!prefs.autoFillBest || !prefs.autoSend) { SendQueueStore.remove(conv); onResolved(false); return }
        if (destroyed || !prefs.enabled) { onResolved(false); return }
        currentSendConv = conv
        currentSendText = text
        fun fire() {
            if (destroyed || !prefs.enabled) { onResolved(false); return }
            val token = session.beginNotification(PKG_WECHAT, conv)
            fillInput(token, text, onSendComplete = { sent ->
                if (sent) {
                    ConversationHistory.appendOwnReply(PKG_WECHAT, conv, text)
                    SendQueueStore.remove(conv)
                }
                // 失败的那条已由 sendWeChatByVerifiedTap 落盘，这里只负责继续驱动下一条。
                onResolved(sent)
            })
        }
        if (weChatForeground) {
            main.post { fire() }
        } else {
            val pi = if (prefs.autoOpenChat) NotifIntentStore.fresh(conv) else null
            if (pi != null) runCatching { pi.send() }
            awaitWeChatForegroundThen { fire() }
        }
    }

    /**
     * [sendWeChatByVerifiedTap] 的前台守卫：确保微信此刻在前台（否则读框/点发送键
     * 全是对着桌面操作）。不在前台时用当前会话暂存的 PendingIntent 重新拉起，
     * 100ms 一跳、最多约 4 秒；拉不回来也放行，由调用方按读框结果判真假。
     */
    private fun ensureWeChatFgForSend(attempt: Int = 0, block: () -> Unit) {
        if (destroyed || !prefs.enabled || attempt >= 40) {
            if (attempt >= 40) Log.e(TAG, "sendTap guard: WeChat fg wait timeout, proceed anyway")
            block()
            return
        }
        if (weChatForeground) {
            if (attempt > 0) Log.i(TAG, "sendTap guard: WeChat back to fg after $attempt ticks")
            block()
            return
        }
        if (attempt == 0) {
            val conv = notifRoundConv
            val pi = if (prefs.autoOpenChat) NotifIntentStore.fresh(conv) else null
            Log.w(TAG, "sendTap guard: WeChat NOT fg (conv=$conv) -> replay PendingIntent=$pi")
            if (pi != null) runCatching { pi.send() }
        }
        main.postDelayed({ ensureWeChatFgForSend(attempt + 1, block) }, 100L)
    }

    /**
     * 发送前读框为空的恢复流程（2026-10-16 07:52 真机实测后新增）。
     *
     * 空框有两个来源，单看 readBox 分不出来：
     *  ① 真没写进去（输入法没挂上 / 被系统顶掉）；
     *  ② 写进去了，但那一刻编辑器已失焦——InputConnection 退回系统的 fallback 连接
     *     （挂在 DecorView 上），getExtractedText 恒返回空串。07:52 那轮就是 ②：
     *     探针 IME 窗口已收起（decorView 只剩 48px），聚焦点却仍按「键盘弹起」算到
     *     屏幕中央 y=1323（输入栏实际在屏底 y≈2335）→ 编辑器从未被点聚焦，
     *     commitText 看似成功，2 秒后读框读到空 → 整轮 abort，回复烂掉。
     * 所以不能一见空框就 abort：先按**当前实际键盘态**重点一次输入栏重新聚焦，再复读；
     * 仍读不到就把本轮草稿重写一次。两条都失败才认输（照旧 toast，绝不盲点发送）。
     */
    private fun recoverEmptyBox(token: ConversationSession.Token, onReady: (Boolean) -> Unit) {
        val draft = lastDraftText
        val (fx, fy) = wxFocusPoint()
        Log.i(TAG, "sendTap recover: enter focus=($fx,$fy) imeCollapsed=${ProbeImeBus.imeCollapsed} imeH=${ProbeImeBus.imeWindowHeightPx} imeActive=${ProbeImeBus.imeActive}")
        tapWeChatInputBar()
        main.postDelayed({
            if (!isCurrent(token)) { onReady(false); return@postDelayed }
            val b1 = ProbeImeBus.readBox()
            if (!b1.isNullOrBlank()) {
                Log.i(TAG, "sendTap recover: box readable after refocus (${b1.length} chars) -> continue")
                onReady(true)
                return@postDelayed
            }
            if (draft.isNullOrBlank()) {
                Log.e(TAG, "sendTap recover: box empty and no draft kept -> give up")
                onReady(false)
                return@postDelayed
            }
            Log.w(TAG, "sendTap recover: box still empty after refocus -> rewrite draft (${draft.length} chars)")
            // send=false：恢复只负责把草稿写回输入框，发送统一由外层点绿键完成
            // （IME 内直接发会吞掉 sendDelayMs 撤回窗口，2026-10-14 决定）。
            ProbeImeBus.requestWrite(draft, false) { ok ->
                Log.i(TAG, "sendTap recover: rewrite delivered ok=$ok")
                if (!ok) {
                    Log.e(TAG, "sendTap recover: rewrite failed -> give up")
                    onReady(false)
                    return@requestWrite
                }
                main.postDelayed({
                    if (!isCurrent(token)) { onReady(false); return@postDelayed }
                    val b2 = ProbeImeBus.readBox()
                    if (!b2.isNullOrBlank()) {
                        Log.i(TAG, "sendTap recover: rewrite landed (${b2.length} chars) -> continue")
                        onReady(true)
                    } else {
                        // commit 已确认成功（completeWrite(true)）但读框仍盲：文本大概率
                        // 真在输入框里，读不回是微信 8.0.78 + 失焦 fallback 的老问题。
                        // 放行去点发送——由 attemptSend 的 recoverUsed 闸门保证不会二次恢复；
                        // 点空无害，点中这条就真发出。旧行为在这里 give up → 落队 → 立即
                        // 重发 → 再 give up，形成风暴（2026-10-16 15:48 实测）。
                        Log.w(TAG, "sendTap recover: rewrite committed but box unreadable -> proceed blind")
                        onReady(true)
                    }
                }, 900L)
            }
        }, 700L)
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
    private fun fillInput(token: ConversationSession.Token, text: String, attempt: Int = 0, onSendComplete: ((Boolean) -> Unit)? = null) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) {
                Log.w(TAG, "fillInput finish: token stale, drop")
                // 必须照样收尾：通知轮靠这个回调推进队列，丢了就要白等 SEND_DWELL。
                onSendComplete?.invoke(false)
                return
            }
            if (ok) {
                // 不信 IME 的 lastSent 短路：2026-10-14 真机教训——IME 报了
                // 「已发送」就跳过 sendFor，验证链整段被绕过。autoSend 开就
                // 一律走 sendFor（倒计时 → 截图确认绿键 → 点 → 复检 → 重点），
                // 真发没发出去以截屏复检为准，不以任何返回值承诺为准。
                Log.i(TAG, "fillInput ok: autoSend=${prefs.autoSend} imeClaimedSent=${ProbeImeBus.lastSent} -> ${if (prefs.autoSend) "sendFor" else "toast-only"}")
                // sendFor announces the countdown itself; don't double-toast here.
                if (prefs.autoSend) {
                    sendFor(token, onSendComplete)
                } else {
                    overlay?.toast(t("已填入，确认后自己发送", "Filled in — review it and send yourself"))
                    // 只填入模式：填完就是本轮终点（没人会再去点发送），通知轮立刻推进下一个会话。
                    onSendComplete?.invoke(false)
                }
            } else {
                Log.w(TAG, "fillInput failed -> clipboard fallback")
                copyToClipboard(text)
                overlay?.toast(t("已复制，长按输入框粘贴", "Copied — long-press the input box to paste"))
                onSendComplete?.invoke(false)
            }
        }
        if (!isCurrent(token)) {
            Log.w(TAG, "fillInput: token stale at entry (destroyed=$destroyed enabled=${prefs.enabled})")
            overlay?.toast(t("会话已变化，请重新分析后填入", "The chat changed — analyze again before filling"))
            onSendComplete?.invoke(false)
            return
        }
        // 微信 8.0.78 把输入框从 a11y 树里剪掉（rootInActiveWindow 恒 null），树写/焦点写
        // 全部不可达——唯一可靠写入通道是探针自己的输入法（ProbeInputMethod.commitText）。
        if (token.target.pkg == PKG_WECHAT && ProbeImeBus.isEnabled(this)) {
            val (fx, fy) = wxFocusPoint()
            // 2026-10-16 19:04 真机翻车复盘（假阳性三连发）：imeActive 只代表「IME 持有编辑器
            // 连接」，而微信语音态输入栏有隐藏 EditText，通知 reopen 时它被聚焦 →
            // onStartInput → imeActive=true，可语音态根本没有键盘窗（getWindows 无
            // TYPE_INPUT_METHOD）。只认 imeActive 会把语音态误判成文本态：焦点算到
            // y=2024（语音态下那是消息列表）、8 字 commit 进隐藏框（用户不可见）、盲点后
            // 「框空=已发」假阳性——台账连报 3 条 sent，回复从未到达。
            // 「文本态」判据 = imeActive **且** 此刻真有 IME 窗口（实时 getWindows）。
            // 第三重守卫（2026-10-16 19:25 假阳性修复）：连接身份。imeActive+IME 窗口
            // 仍可能是假连接——微信语音态输入栏的隐藏 EditText 被聚焦时两者都真，但
            // commitText 静默写进虚空、读框恒空。只有「微信聊天输入框的真文本编辑器」
            // 才放行写入；假连接走下方「点输入栏重挂 + 剪贴板兜底 + 复查」。
            if (ProbeImeBus.imeActive && anyImeWindowPresent() && ProbeImeBus.editorIsTextEditor(PKG_WECHAT)) {
                // 探针已激活（用户自己在系统/通知栏切到了探针输入法）：投递 + 聚焦输入栏，
                // 走 poll path 直接填字（输入法写入前会先清空旧草稿，防多轮回复串缸）。
                // autoSend 开时由 sendFor 走「截图确认绿键 → 点 → 复检 → 重点」
                // 完成真正发送，倒计时撤回窗口保留。
                // App 全程不弹选择器、不替用户切输入法。
                ProbeImeBus.requestWrite(text, prefs.autoSend) { ok -> finish(ok) }
                lastDraftText = text
                tapWeChatInputBar()
                Log.i(TAG, "fillInput: probe IME active, wrote via poll path (focus=$fx,$fy, autoSend=${prefs.autoSend})")
            } else {
                // 输入法非探针/键盘没起来：Android 不允许 app 静默切输入法，也绝不自动弹选择器
                // 打扰。剪贴板兜底先做上（无害）；提示 toast 挪到终端分支，避免语音态下弹错提示
                // （语音态用户早就切好了探针输入法，问题在微信侧）。
                // 时序坑修复（2026-10-15 实测四轮全兜底定位）：用户按提示切好输入法后，
                // 若没人点微信输入栏，探针 IME 不会 onStartInput，imeActive 永远 false
                // ——「切了也白切」，每轮都只复制剪贴板。这里幂等点一下输入栏把用户
                // 已切好的探针 IME 挂上去（点输入栏聚焦是既有操作，不是切输入法，
                // 不碰铁律），复查通过就直接走 poll path 发出去。
                Log.i(TAG, "fillInput: probe IME not active -> tap input bar, clipboard fallback (focus=$fx,$fy)")
                copyToClipboard(text)
                if (attempt == 0) {
                    Log.i(TAG, "fillInput: tap input bar to mount probe IME, recheck in 600ms")
                    tapWeChatInputBar()
                    // 复查判据（2026-10-16 19:04 翻车复盘后收紧）：「文本态键盘真起来了」=
                    // imeActive **且** 有实时 IME 窗口。微信语音态输入栏有隐藏 EditText，
                    // reopen/点按都会让它聚焦 → imeActive 恒真，但语音态永远没有键盘窗。
                    // 键盘窗口绘制还有延迟：600ms 没起来不急着下结论，再宽限 800ms 二次复查，
                    // 两次都无窗口才判语音态——绝不把「文本态键盘慢」误切成语音（越切越坏）。
                    fun recheckAfterTap(round: Int) {
                        // 复查判据加上连接身份（2026-10-16 19:25 假阳性修复）：点输入栏
                        // 不仅要让 IME 挂上，还要让**真编辑器**（微信聊天输入框）聚焦——
                        // 语音态隐藏 EditText 被聚焦时 imeActive 恒真但连接是假的。
                        if (ProbeImeBus.imeActive && anyImeWindowPresent() && ProbeImeBus.editorIsTextEditor(PKG_WECHAT)) {
                            Log.i(TAG, "fillInput: probe IME mounted after tap (round=$round) -> retry via poll path")
                            fillInput(token, text, attempt = 1, onSendComplete = onSendComplete)
                            return
                        }
                        if (round < 1) {
                            main.postDelayed({ recheckAfterTap(1) }, 800L)
                            return
                        }
                        // 点了输入栏、宽限 ~1.4s 仍无任何 IME 窗口。先分清两种原因：
                        //  ① 微信在语音态（按住说话）：根本没有文本框 → 任何输入法都不会起 → 无 IME 窗口。
                        //     只有这种才点语音/键盘切换钮切回文本态（微信内部 UI 操作，不碰「不替用户切输入法」铁律）。
                        //  ② 探针没被设为当前输入法：系统键盘起着（只是不是探针）→ 有 IME 窗口，
                        //     该走「提示切换」路，绝不能点切换钮（会把文本误切成语音）。
                        // 语音态切回文本态 + 重试链（2026-10-16 21:10 提取为局部 fun：
                        // 面板关闭是异步的，600ms 后才执行；同时避开局部函数前向引用）。
                        fun tapToggleAndRetry() {
                            if (tapWeChatVoiceToggle()) {
                                main.postDelayed({
                                    if (ProbeImeBus.imeActive && anyImeWindowPresent()) {
                                        Log.i(TAG, "fillInput: probe IME up after toggle -> retry via poll path")
                                        fillInput(token, text, attempt = 1, onSendComplete = onSendComplete)
                                    } else {
                                        // 切换钮点了键盘还没起：再点一次输入栏聚焦，最后一次机会。
                                        tapWeChatInputBar()
                                        main.postDelayed({
                                            if (ProbeImeBus.imeActive && anyImeWindowPresent()) {
                                                fillInput(token, text, attempt = 1, onSendComplete = onSendComplete)
                                            } else {
                                                // 语音态切不回/切不准：草稿落盘等下次活跃重发，不丢队列。
                                                Log.w(TAG, "fillInput: still no probe IME after toggle+focus -> queue draft for retry")
                                                failAndQueue()
                                            }
                                        }, 700L)
                                    }
                                }, 900L)
                            } else {
                                Log.w(TAG, "fillInput: voice toggle gesture dispatch failed -> clipboard only")
                            }
                        }
                        if (!anyImeWindowPresent()) {
                            Log.i(TAG, "fillInput: no IME window at all -> WeChat likely voice mode, tap voice/keyboard toggle")
                            // 重定标仪表（2026-10-16 21:10）：FLAG_SECURE 下唯一能"看"到输入栏的
                            // 通道就是无障碍节点本身——窗口清单 + 栏内节点 dump，既服务本次真切换钮
                            // 标定，也让 tapWeChatVoiceToggle 的树查找有据可依。
                            logWeChatWindows("voice-branch")
                            dumpWeChatBarNodes("voice-branch")
                            if (weChatPanelOpen()) {
                                // 面板（表情/更多/语音）开着时栏位被面板按钮占据，先 BACK 关掉
                                // 再点切换钮——19:24 盲点曾误开「+」8 按钮面板（用户实测）。
                                Log.w(TAG, "fillInput: wechat panel open -> BACK close, toggle after 600ms")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                main.postDelayed({
                                    dumpWeChatBarNodes("voice-branch-postback")
                                    tapToggleAndRetry()
                                }, 600L)
                            } else {
                                tapToggleAndRetry()
                            }
                        } else {
                            Log.i(TAG, "fillInput: probe IME still not active after tap -> clipboard only this round")
                            overlay?.toast(t("请先在系统输入法切到「AI聊天助手·写入」才能自动回复，回复已复制可手动粘贴",
                                             "Switch your keyboard to \"AI Assistant · Write\" to auto-reply; reply copied for manual paste"))
                        }
                    }
                    main.postDelayed({ recheckAfterTap(0) }, 600L)
                } else {
                    // 重试一轮（attempt>0）仍拿不到探针键盘：落队重试，不丢队列。
                    Log.w(TAG, "fillInput: retry attempt=$attempt still no probe IME -> queue draft for retry")
                    failAndQueue()
                }
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
                main.postDelayed({ fillInput(token, text, attempt = 1, onSendComplete = onSendComplete) }, 450L)
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
    private fun sendFor(token: ConversationSession.Token, onSendComplete: ((Boolean) -> Unit)? = null) {
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
            if (!isCurrent(token)) { Log.w(TAG, "sendFor: token stale at fire time, aborted"); onSendComplete?.invoke(false); return@postDelayed }
            // 用户在这段等待里点了「本次不发送」：只放弃这一条，不动总开关。
            if (skipThisSend) { skipThisSend = false; Log.i(TAG, "sendFor: user held back this one"); onSendComplete?.invoke(false); return@postDelayed }
            // 发送不可逆：用户可能在这 2 秒等待里点了「暂停自动收发」（气泡菜单一步可达）。
            // 这里必须重新读开关，让暂停立刻生效——否则「随时收回控制权」就是空话。
            if (!prefs.autoSend) { overlay?.toast(t("已暂停自动发送，本次未发出", "Auto-send paused — not sent this time")); onSendComplete?.invoke(false); return@postDelayed }
            val root = rootInActiveWindow
            // 微信 8.0.78 聊天页 rootInActiveWindow 恒 null（树被剥光），旧代码在这里
            // 直接 abort——发送永远走不到。null 时只认 NOTIF token / isCurrent 放行。
            if (root == null) {
                Log.w(TAG, "sendFor: rootInActiveWindow=null (WeChat FLAG_SECURE path), proceeding by token")
            } else if (token.target.windowId != ConversationSession.NOTIF_WINDOW &&
                targetFor(root) != token.target) {
                Log.w(TAG, "sendFor: fg window no longer matches, aborted")
                onSendComplete?.invoke(false)
                return@postDelayed
            }
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
                    sendWeChatByVerifiedTap(token, onSendComplete)
                    return true
                }
                return false
            }
            if (!clickSend()) {
                Log.w(TAG, "sendFor: clickSend failed -> manual tap needed")
                overlay?.toast(t("已填入，未能自动发送，请手动点发送", "Filled in but couldn't auto-send - tap send manually"))
                onSendComplete?.invoke(false)
                return@postDelayed
            }
            if (token.target.pkg == PKG_WECHAT) {
                // 微信：确认/重试/提示都在 sendWeChatByVerifiedTap 里异步完成。
                return@postDelayed
            }
            // 非微信：树可读，保留「输入框是否还有字」的事后确认。
            Log.i(TAG, "sendFor: clicked via tree, awaiting confirm")
            overlay?.toast(t("已自动发送", "Sent automatically"))
            onSendComplete?.invoke(true)
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
        // 通知轮队列随服务销毁清空；NotifIntentStore 是进程单例、跨重启保留（LRU 上限 32）。
        notifRoundQueue.clear()
        notifRoundBusy = false
        notifRoundConv = null
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
        overlay?.onNoReplyToggle = null
        overlay?.onDiagnostics = null
        overlay?.onErrorAction = null
        overlay?.onReplyFeedback = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        /** 微信输入栏最左「语音/键盘」切换钮中心的水平比例。2026-10-16 真机标定：x=84 @1080 宽
         *  （≈0.0778），文本态/语音态同 x；语音态输入栏贴屏底 y≈屏高-半输入栏高(≈2335@2412)。 */
        private const val WX_VOICE_TOGGLE_X_RATIO = 0.078f

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

        /** 内容变化/滚动事件的最小间隔（见 onAccessibilityEvent 的节流注释）。
         *  窗口状态变化不受此限——那是一次真实的状态切换，必须立即响应。 */
        private const val CYCLE_MIN_INTERVAL_MS = 300L

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

        /** 模型常给候选回复加的自我介绍式前缀，发给真人前去掉。 */
        private val REPLY_PREFIX = Regex(
            """^(回复|回覆|回信|回答|答|AI\s*回复|助手\s*回复|Reply|Answer)\s*[:：]\s*""",
            RegexOption.IGNORE_CASE)
        private val REPLY_WS = Regex("[\\t\\u00A0 ]+")
        /** 比较"是不是同一句话"时忽略的标点。 */
        private val REPLY_PUNCT = "。，、！？；：,.!?;:~～\"'“”‘’「」()（）…—-"

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
