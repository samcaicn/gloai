package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.cap.phone.GesturePacing
import com.wangzan101.mika.core.cap.phone.TypingPacing
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.uistate.AppWait
import com.wangzan101.mika.core.uistate.AppCopy
import com.wangzan101.mika.core.uistate.AppProfiles
import com.wangzan101.mika.core.uistate.LaunchChoice
import com.wangzan101.mika.core.uistate.LaunchChooser
import com.wangzan101.mika.core.uistate.OpenedElsewhere
import com.wangzan101.mika.core.uistate.UnseenField
import com.wangzan101.mika.core.uistate.normalize
import com.wangzan101.mika.core.uistate.Relocation
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenEvent
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.SystemCommandResult
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.WheelOutcome
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import com.wangzan101.mika.core.visual.FrameGrid
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.uistate.TextField
import com.wangzan101.mika.core.uistate.typeAndVerify
import com.wangzan101.mika.core.uistate.relocate
import com.wangzan101.mika.core.uistate.RELOCATE_MAX_SHIFT
import com.wangzan101.mika.cap.phone.display.FrameMath
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [ScreenPort] 的安卓实现:读 Agent 虚拟屏的无障碍窗口,在这块屏上操作。
 * 每个动作之后等界面安静下来,用控件树指纹(辅以画面)核实有没有生效,并检查前台是否被别的 App 切走。
 * 虚拟屏用到时才建(第一次 launch_app,或系统命令、事件工具):没建之前读屏是一块空屏,其余动作失败 [NO_APP_OPEN],这些动作自己不会去建屏。
 * 屏建好不等于打开过 App:只跑系统命令的任务屏上是空的。
 */
class AndroidScreen(
    private val a11y: ScreenAccessibility,
    private val shell: IMikaShell,
    private val display: LazyAgentDisplay,
    /** 剪贴板暂时换成 Mika 的文字时为 true,还原后为 false;任务服务据此记下,进程意外结束后下次启动还原。 */
    private val clipHeld: (Boolean) -> Unit = {},
    /** 剪贴板是全局的:几个任务同时跑时粘贴兜底共用一把锁。 */
    private val clipLock: Lock = ReentrantLock(),
    /** 并行任务时为 true:打开 App 前先查它是不是正在 Mika 的另一块屏上给别的任务用。 */
    private val checkOtherTasks: Boolean = false,
    /** 并行任务时替本任务占下这个包;别的任务已占着(且还在用)时返回 false。 */
    private val reserve: (String) -> Boolean = { true },
    /** 同 [reserve],占的是装在另一份用户(用户编号)里的那份:和本来那份分开占,两个任务能各用一份。 */
    private val reserveCopy: (String, Int) -> Boolean = { _, _ -> true },
    /** 换剪贴板成功后交出用户原来的文字和 Mika 放进去的文字,供加密留底(进程被杀后据此还原)。 */
    private val clipSaved: (original: String, ours: String) -> Unit = { _, _ -> },
    /** App 此刻在用户手上(接管中):这时主屏上开出什么都是用户的事,不去挪。 */
    private val handedOver: () -> Boolean = { false },
    /** 手势的节奏:落指前的停顿、按住时长、落点偏差、滑动的弧线和快慢(见 [GesturePacing])。 */
    private val pacing: GesturePacing = GesturePacing.human(),
    /** 在这个包名的 App 里打字的节奏(见 [TypingPacing]);默认一次写入全文。 */
    private val typing: (String?) -> TypingPacing = { TypingPacing.Instant },
    /** 发出启动之前:要开的是 [packageName](装在 [user] 那份用户里的;null 为本来那份)。任务服务据此先静上,开头就没声。 */
    private val beforeOpen: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    /** 启动被拒、没开出来:撤回 [beforeOpen] 提前做的。 */
    private val notOpened: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    override val appAccess: com.wangzan101.mika.core.access.AppAccessPolicy = com.wangzan101.mika.core.access.AppAccessPolicy.ALLOW,
    /** 并行任务时:按占用登记,本任务此刻能不能占这个包([user] 那份;null 为本来那份)。只查不占。 */
    private val appFree: (packageName: String, user: Int?) -> Boolean = { _, _ -> true },
    /**
     * 并行任务时:等别的任务放开这个包,最多那么久;[free] 是完整的判断(别的屏上没有它、登记里也没人占)。
     * 任务服务据此登记「谁在等谁」、认出互相等,等的时候界面写「等另一个任务用完某 App」。
     */
    private val awaitApp: suspend (packageName: String, user: Int?, timeoutMs: Long, free: suspend () -> Boolean) -> AppWait =
        { _, _, _, _ -> AppWait.Unsupported },
) : ScreenPort {
    /** 屏还没建时为 -1;只在确认屏已建好之后用。 */
    private val displayId get() = display.current?.displayId ?: -1
    /** 屏已经建好(不等于打开过 App)。 */
    private val hasDisplay get() = display.current != null
    private val screenWidth get() = display.current?.width ?: display.width
    private val screenHeight get() = display.current?.height ?: display.height
    private val ids = NodeIds()

    /** 最近一次 launch_app 成功打开的包,用于判断是否被切走。 */
    @Volatile private var targetPackage: String? = null

    /** 最近一次打开 App 时系统问「打开哪一个」的那个窗口所在的包:它在前台不算被切走。 */
    @Volatile private var chooserPackage: String? = null

    /**
     * Agent 正在用的 App 及其任务编号(打开时不在最上面则为 -1);任务服务据此静音、监视是否被挪走。
     * [userId] 是那个任务所属的用户(装了两份时分得出是哪一份);读不到为 -1。
     */
    data class TargetApp(val packageName: String, val taskId: Int, val userId: Int = -1)

    private val _target = MutableStateFlow<TargetApp?>(null)
    val target: StateFlow<TargetApp?> = _target.asStateFlow()

    private val systemPickers: Set<String> by lazy { SystemPickers.resolve(a11y.packageManager) }

    /** 系统设置所在的包(按 Settings.ACTION_SETTINGS 解析):目标 App 在自己任务里打开的设置页不算被切走。 */
    private val settingsPackage: String? by lazy { SystemPickers.settingsPackage(a11y.packageManager) }

    private fun windows() = a11y.windowsOn(displayId)
    /**
     * 应用访问的窗口探针:屏上各 App 窗口的归属,只读根节点的包名、不读内容。读不到根节点的那一项为 null(归属未知);
     * 没有 App 窗口时是最上面任务的 App,也没有就是空屏(空列表)。规则和读屏结果里的窗口归属一致。
     */
    suspend fun accessPackages(): List<String?> = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext emptyList()
        val packages = windows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { it.root?.packageName?.toString() }
        packages.ifEmpty { listOfNotNull(foregroundTaskPackage()) }
    }

    private fun read() = ScreenReader.read(windows(), screenWidth, screenHeight)
    private fun treeHash() = ScreenReader.hash(windows())

    /** 显式使用系统命令或事件工具时按需建屏;普通任务等到第一次打开 App 才建。 */
    suspend fun ensureDisplay() { display.get() }

    // ---------- 读屏 ----------

    override suspend fun snapshot(): ScreenSnapshot = capture(register = true)

    // 不进编号登记表:模型手里最新的 ref 仍然有效
    override suspend fun peek(): ScreenSnapshot = capture(register = false)

    /** Device-local SMS verification needs the exact IDs, including IDs on labelled buttons. */
    internal suspend fun smsSnapshot(register: Boolean): ScreenSnapshot =
        capture(register, fullResourceIds = true)

    private suspend fun capture(register: Boolean, fullResourceIds: Boolean = false): ScreenSnapshot = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext ScreenSnapshot(snapshotId(), null, emptyList(), screenWidth, screenHeight, hasWindow = false)
        // 上一个动作过了一会儿才开到用户主屏上的:先挪回来再读(只在模型要读的这一屏上做,不在判效果的那一眼里做)
        val late = if (register) lateSpill() else null
        val r = read()
        val after = treeHash()
        val foreground = if (r.topPackage == null) foregroundTaskPackage() else null
        val snap = NodeSnapshot(snapshotId(), r.entries)
        if (register) ids.put(snap)
        ScreenSnapshot(
            snapshotId = snap.id,
            packageName = r.topPackage,
            elements = r.entries.map { entry ->
                if (fullResourceIds) entry.element.copy(resourceId = entry.locator.resourceId) else entry.element
            },
            width = screenWidth,
            height = screenHeight,
            changedDuringRead = after != r.hash,
            hasWindow = r.anyAppWindow,
            unreadableApp = foreground,
            windows = r.windows,
            // 还在等用户在主屏上选:照样说一声
            opened = late?.opened ?: _userPick.value?.let { OpenedElsewhere(it.packageName, it.app, pulledBack = false, picker = true) },
            // 黑帧只说明画面不可用,不能据此声称窗口防截屏;读屏元素仍可作为降级路径。
            captureError = if ((r.anyAppWindow || foreground != null) && frameServable() && frameBlack() == true)
                ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE else null,
        )
    }

    /**
     * 窗口内容读不到时(安全窗口、启动画面、显示在别处),按 Agent 屏上最上面的任务认前台是哪个 App;
     * 屏还没建、没有任务或问不到为 null。
     */
    private fun foregroundTaskPackage(): String? {
        if (!hasDisplay) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        return runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Mika 屏上最上面的那个 App(包名、任务、所属用户),每换一个给一次:订阅时先给一次,之后窗口一变(开了新页面、
     * 从别的 App 里点开了另一个 App、另一份顶上来)就重读。屏还没建、没有任务时不给。在调用方的线程上问内置助手。
     */
    fun foregroundApps(): Flow<TargetApp> = flow {
        emit(Unit)
        emitAll(a11y.windowChanges())
    }.conflate().mapNotNull {
        if (!hasDisplay) return@mapNotNull null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return@mapNotNull null
        val pkg = runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TargetApp(pkg, task, userOf(task))
    }.distinctUntilChanged { a, b -> a.packageName == b.packageName && a.userId == b.userId }

    override suspend fun screenshotPng(): ByteArray? = withContext(Dispatchers.IO) {
        // 黑图没有可用画面信息,也不能凭它区分安全窗口和合成失败;不要交给模型或 OCR。
        if (!frameServable() || frameBlack() == true) return@withContext null
        display.current?.latestFramePng()
    }

    /** 最近一次启动开始的单调时钟时刻（纳秒），与虚拟屏生产者的帧时间戳比较。 */
    @Volatile private var frameGuardSince: Long? = null

    /** 此刻能不能把 Agent 屏的最新帧交给模型:启动后还没收到新帧时不给,直到新帧到达才解除。 */
    private fun frameServable(): Boolean = when (FrameFreshness.serve({ display.current?.lastFrameAt() }, frameGuardSince)) {
        FrameFreshness.Serve.Guarded -> false
        FrameFreshness.Serve.Fresh -> {
            frameGuardSince = null
            true
        }
    }

    /** 等这次画面变化之后的首帧;等不到也继续保持保护,不能把旧帧放行。 */
    private suspend fun awaitFrameAfter(startedAt: Long) {
        frameGuardSince = startedAt
        if (FrameFreshness.awaitNewFrame(
                { SystemClock.uptimeMillis() },
                ::delay,
                { display.current?.lastFrameAt() },
                startedAt,
            )
        ) frameGuardSince = null
    }

    /**
     * 认屏上文字。自绘界面几乎没有无障碍事件,事件安静了画面可能还在过渡:先等画面停稳再认;认的过程中换了整页就按新的一页再认
     * (规则见 [StillFrameRead])。局部在动(动图头像、光标)不影响字的位置,照用,不再整个丢掉。
     */
    override suspend fun readVisibleText(): List<VisibleTextLine>? = withContext(Dispatchers.IO) {
        if (!hasDisplay || !frameServable() || frameBlack() == true) return@withContext null
        StillFrameRead.read(SystemClock::uptimeMillis, { delay(it) }, ::frameSignature) {
            display.current?.latestFrameBitmap()?.let { PhoneOcr.read(it) }
        }
    }

    override suspend fun frameGrid(): FrameGrid? = withContext(Dispatchers.IO) {
        if (!frameServable()) return@withContext null
        val bmp = display.current?.frames?.value ?: return@withContext null
        runCatching { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    override suspend fun checkScreen(): ScreenCheck = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        // 先看界面会不会安静下来:一直有变化就是还在加载
        val quiet = withEventClock { clock -> clock.awaitQuiet(QUIET_MS, CHECK_CAP_MS) }
        // 安静时看两次;持续全黑只报画面不可用,不把黑像素当成防截屏的证据
        val black = mutableListOf<Boolean>()
        frameBlack()?.let(black::add)
        if (quiet) {
            withEventClock { clock -> clock.awaitQuiet(SECOND_LOOK_QUIET_MS, SECOND_LOOK_CAP_MS) }
            frameBlack()?.let(black::add)
        }
        val r = read()
        ScreenDiagnosis.classify(
            ScreenFacts(
                currentApp = r.topPackage,
                hasWindow = r.hasAppWindow,
                anyWindow = r.anyAppWindow,
                readableElements = r.entries.count { it.element.meaningful },
                changing = !quiet,
                blackFrames = black,
                texts = r.texts,
                passwordField = r.passwordField,
                foregroundApp = if (r.topPackage == null) foregroundTaskPackage() else null,
            ),
        )
    }

    override suspend fun waitStable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext true
        withEventClock { clock -> clock.awaitQuiet(QUIET_MS, timeoutMs) }
    }

    /** 这块虚拟屏的下一条界面事件(悬停一类不算);屏还没开时等满时限。 */
    override suspend fun awaitChange(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) { delay(timeoutMs.coerceAtLeast(0)); return@withContext false }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { a11y.eventsFor(displayId).first { it.eventType and IGNORED_EVENTS == 0 } } != null
    }

    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        if (condition == "stable") return@withContext waitStable(timeoutMs)
        if (condition !in setOf("appear", "disappear", "activity") || value.isNullOrBlank()) return@withContext false
        coroutineScope {
            val changed = Channel<Boolean>(Channel.CONFLATED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(displayId).collect { event ->
                    if (condition == "activity" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.className?.toString()?.contains(value, true) == true) changed.trySend(true)
                    else if (condition != "activity") changed.trySend(false)
                }
            }
            try {
                fun matched(): Boolean = when (condition) {
                    "activity" -> runCatching { shell.topActivity(displayId)?.contains(value, true) == true }.getOrDefault(false)
                    else -> {
                        val r = read()
                        val exists = r.windows.any { w ->
                            w.title?.contains(value, true) == true || w.nodes.any { n -> containsText(n, value) }
                        } || r.texts.any { it.contains(value, true) }
                        exists == (condition == "appear")
                    }
                }
                if (matched()) return@coroutineScope true
                withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
                    while (true) {
                        val activityEvent = changed.receive()
                        if (condition == "activity") {
                            if (activityEvent) return@withTimeoutOrNull true
                        } else if (matched()) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
            } finally { job.cancel(); changed.close() }
        }
    }

    private fun containsText(node: com.wangzan101.mika.core.uistate.UiTreeNode, text: String): Boolean =
        (!node.secret && (node.text?.contains(text, true) == true || node.description?.contains(text, true) == true)) ||
            node.children.any { containsText(it, text) }

    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = withContext(Dispatchers.IO) {
        if (kind == "notification") return@withContext withTimeoutOrNull(timeoutMs) {
            a11y.notificationEvents().first { packageName == null || it.packageName == packageName }
        }
        if (!hasDisplay) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            val event = a11y.eventsFor(displayId).first { e ->
                (packageName == null || e.packageName?.toString() == packageName) && when (kind) {
                    "window" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(), event.text.joinToString(" ").take(2_000))
        }
    }

    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = withContext(Dispatchers.IO) {
        if (kind != "notification" && !hasDisplay) return@withContext emptyList()
        val collected = mutableListOf<ScreenEvent>()
        val stream = if (kind == "notification") a11y.notificationEvents().filter { packageName == null || it.packageName == packageName }
        else a11y.eventsFor(displayId)
            .filter { event ->
                (packageName == null || event.packageName?.toString() == packageName) && when (kind) {
                    "window" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            .map { event -> ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(),
                event.text.joinToString(" ").ifBlank { event.className?.toString().orEmpty() }.take(2_000)) }
        withTimeoutOrNull(durationMs) { stream.take(maxEvents).collect { collected += it } }
        collected
    }

    override suspend fun systemCommand(command: String): SystemCommandResult? = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext null
        val result = shell.runSystemCommand(displayId, command)
        SystemCommandResult(result.getInt("exitCode", -1), result.getString("output").orEmpty())
    }

    // ---------- 动作 ----------

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val (node, relocatedTo) = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val target = ancestorOrSelf(node) { isClickableTarget(it) }
        val rect = Rect().also(node::getBoundsInScreen)
        val touchFallback: suspend (EventClock) -> Boolean = { clock ->
            clock.mark()
            touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
        }
        if (target == null) return@withContext verified(relocatedTo = relocatedTo) {
            touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
        }
        val outward = ClickFallback.mayBeOutward(ScreenReader.labelOf(node), node.viewIdResourceName) ||
            ClickFallback.mayBeOutward(ScreenReader.labelOf(target), target.viewIdResourceName)
        verified(relocatedTo = relocatedTo, fallback = touchFallback, retryWhenUnchanged = !outward) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    override suspend fun tapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified(fallback = { clock -> clickNodeAt(x, y, clock) }) { tapAt(x, y) }
    }

    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val target = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val rect = android.graphics.Rect().also { target.first.getBoundsInScreen(it) }
        verified(relocatedTo = target.second) { doubleTapAt(pacing.within(rect.left, rect.top, rect.right, rect.bottom)) }
    }

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { doubleTapAt(pacing.near(x, y)) }
    }

    // 双击只能是一次手势里的两笔:分两次下发,中间的回调延迟可能超过系统认双击的时限
    private suspend fun doubleTapAt(at: Pair<Int, Int>): Boolean {
        delay(pacing.approach())
        return a11y.taps(displayId, at.first.toFloat(), at.second.toFloat(), 2, pacing.doublePress(), pacing.doubleGap())
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { drag(listOf(x1 to y1, x2 to y2), durationMs, bow = true) }
    }

    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        if (paths.isEmpty() || paths.size > 2 || paths.any { it.size < 2 || it.size > 32 }) return@withContext ActionResult.Failed("invalid_gesture")
        verified {
            // 一根手指照着画好的路径走,按节奏排快慢;几根手指一起动的只让时长浮动一点
            if (paths.size == 1) return@verified drag(paths.single(), durationMs, bow = false)
            delay(pacing.approach())
            a11y.gesture(displayId, paths.map(::pathOf), pacing.duration(durationMs))
        }
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        var relocatedTo: String? = null
        val start = if (ref != null && snapshotId != null) {
            val target = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
            relocatedTo = target.second
            target.first
        } else {
            read().entries.firstOrNull { it.element.scrollable }?.node
        }
        val node = start?.let { ancestorOrSelf(it) { n -> n.isScrollable } }
        if (node == null) {
            // 没有可滚动的控件时用屏幕中部的滑动代替
            val cx = screenWidth / 2
            val (from, to) = if (down) screenHeight * 3 / 4 to screenHeight / 4 else screenHeight / 4 to screenHeight * 3 / 4
            val r = swipe(cx, from, cx, to)
            return@withContext if (r is ActionResult.Ok && relocatedTo != null) r.copy(relocated = true, relocatedTo = relocatedTo) else r
        }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        verified(relocatedTo = relocatedTo) { node.performAction(action) }
    }

    /**
     * 停留时顺手轻轻滚一下:在占了大半屏的那块可滚动内容(信息流、正文)正中慢慢划 [fraction] 个屏高,停稳再抬手、不甩。
     * 弹着输入法、输入框拿着焦点、上面盖着别的窗口(弹框、浮层)、没有够大的可滚动内容时不滚。
     */
    override suspend fun idleScroll(fraction: Double): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay || imeBox() != null || focusedEditable() != null) return@withContext false
        if (ScreenReader.contentWindows(windows()).count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1) return@withContext false
        val area = read().entries.filter { it.element.scrollable }
            .map { e -> android.graphics.Rect().also { e.node.getBoundsInScreen(it) } }
            .maxByOrNull { it.width().toLong() * it.height() } ?: return@withContext false
        if (area.width().toLong() * area.height() < screenWidth.toLong() * screenHeight * IDLE_MIN_AREA) return@withContext false
        val dist = (screenHeight * kotlin.math.abs(fraction)).roundToInt().coerceAtMost(area.height() / 2)
        if (dist < IDLE_MIN_PX) return@withContext false
        val cx = area.centerX()
        val mid = area.centerY()
        // 往下看(fraction 为正)手指往上划
        val (from, to) = if (fraction > 0) mid + dist / 2 to mid - dist / 2 else mid - dist / 2 to mid + dist / 2
        drag(listOf(cx to from, cx to to), IDLE_SCROLL_MS, bow = true)
    }

    override suspend fun back(): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }
    }

    override suspend fun pressKey(keyCode: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { shell.injectKey(displayId, keyCode) }
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val found = when (val f = findField(ref, snapshotId)) {
            is Field.Found -> f
            is Field.Missing -> return@withContext when {
                f.reason == "not_editable" -> {
                    if (ref != null && snapshotId != null) {
                        contextPasteAnchor(ref, snapshotId)?.let { anchor ->
                            pasteFromContextMenu(anchor, text)?.let { return@withContext it }
                        }
                    }
                    ActionResult.Failed(f.reason, "The referenced control did not expose an editor or a verified Paste menu.")
                }
                f.reason != NO_FOCUS -> ActionResult.Failed(f.reason)
                // 屏上什么都没拿着输入焦点、也没弹输入法:没有框接得住粘贴,不去粘(粘了界面别处一动就会被当成「可能打进去了」)
                !somethingHoldsInputFocus() -> ActionResult.Failed(NO_FOCUS)
                else -> typeUnseen(text)
            }
        }
        typeField(found, text)
    }

    /** Prepare the explicitly configured account locally; never overwrite a different visible account. */
    suspend fun prepareSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!smsPrimaryUser()) return@withContext false
        val s = smsSnapshot(register = true)
        if (!com.wangzan101.mika.core.cap.phone.sms.SmsLoginMatcher.target(s, p.app, p.codeField)) return@withContext false
        val field = s.elements.filter { it.resourceId == p.accountField }.singleOrNull() ?: return@withContext false
        val found = findField(field.ref, s.snapshotId, tapToFocus = false) as? Field.Found ?: return@withContext false
        val node = found.node
        if (found.relocatedTo != null || inWebView(node) || node.isPassword || node.packageName?.toString() != p.app ||
            node.viewIdResourceName != p.accountField || !node.refresh() || !node.isEnabled) return@withContext false
        val rawAccount = if (field.hint) "" else node.text?.toString().orEmpty().trim()
        val current = rawAccount.filter(Char::isDigit)
        if (rawAccount.isNotEmpty() && current != p.account) return@withContext false
        if (current == p.account) true else authorize { setText(node, p.account) }
    }

    suspend fun submitSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val scope = com.wangzan101.mika.core.cap.phone.sms.SmsLoginScope(p.sender, p.signature, p.account)
        val s = smsLoginScreen(p.app, p.codeField, scope) ?: return@withContext false
        val account = s.elements.filter { it.resourceId == p.accountField }.singleOrNull() ?: return@withContext false
        if (account.name.filter(Char::isDigit) != p.account) return@withContext false
        val editor = s.elements.single { it.resourceId == p.codeField }
        if (editor.name.filter(Char::isDigit) != String(code)) return@withContext false
        val button = s.elements.filter { it.resourceId == p.loginButton }.singleOrNull() ?: return@withContext false
        if (!Regex("^(?:登录|登 录|登入|sign in|log in)$", RegexOption.IGNORE_CASE).matches(button.name.trim())) return@withContext false
        val (node, relocated) = resolve(button.ref, s.snapshotId) ?: return@withContext false
        if (relocated != null || inWebView(node) || node.packageName?.toString() != p.app || node.viewIdResourceName != p.loginButton) return@withContext false
        authorize { node.refresh() && node.isEnabled && node.isVisibleToUser && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    suspend fun smsLoginOutcome(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray): com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome = withContext(Dispatchers.IO) {
        if (!smsPrimaryUser()) return@withContext com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Unsafe
        val s = smsSnapshot(register = false)
        val outcome = com.wangzan101.mika.core.cap.phone.sms.SmsLoginEvidence.outcome(p, s, code)
        if (outcome != com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Verified) return@withContext outcome
        // OCR stays on device. Missing OCR or residual code pixels cannot authorize resuming observations.
        val lines = kotlinx.coroutines.withTimeoutOrNull(2000) { readVisibleText() }
            ?: return@withContext com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Pending
        if (com.wangzan101.mika.core.cap.phone.sms.SmsLoginEvidence.containsCode(lines.joinToString(" ") { it.text }, code))
            com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Pending
        else com.wangzan101.mika.core.cap.phone.sms.SmsLoginEvidence.outcome(p, smsSnapshot(register = false), code)
    }

    private fun smsPrimaryUser(): Boolean {
        if (!hasDisplay) return false
        val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        return top >= 0 && userOf(top) == 0
    }

    /** SMS-only native input path. Never use the generic clipboard/input fallback or echo values. */
    suspend fun fillSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val pkg = p.app; val resourceId = p.codeField
        val current = profileLoginScreen(p) ?: return@withContext false
        val field = current.elements.single { it.resourceId == resourceId }
        val found = findField(field.ref, current.snapshotId, tapToFocus = false) as? Field.Found ?: return@withContext false
        val node = found.node
        if (found.relocatedTo != null || inWebView(node) || node.isPassword ||
            node.packageName?.toString() != pkg || node.viewIdResourceName != resourceId) return@withContext false
        authorize {
            // No focus/tap, no retry and no clipboard: SET_TEXT accepts only this exact native node.
            if (!node.refresh() || !visibleInputTarget(node) || !node.isEnabled) false
            else setText(node, String(code))
        }
    }

    /** Listen first, then request one code on the verified login page. */
    suspend fun requestSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val pkg = p.app; val sendButtonId = p.sendButton
        val current = profileLoginScreen(p) ?: return@withContext false
        if (!sendButtonId.startsWith("$pkg:id/")) return@withContext false
        val button = current.elements.filter { it.resourceId == sendButtonId }.singleOrNull() ?: return@withContext false
        if (!button.clickable || button.editable || !Regex("获取验证码|发送验证码|重新获取|重新发送|send.?code|resend", RegexOption.IGNORE_CASE)
                .containsMatchIn(button.name)) return@withContext false
        val (node, relocated) = resolve(button.ref, current.snapshotId) ?: return@withContext false
        if (relocated != null || inWebView(node) || node.packageName?.toString() != pkg || node.viewIdResourceName != sendButtonId) return@withContext false
        authorize {
            node.refresh() && node.isEnabled && node.isVisibleToUser && node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private suspend fun profileLoginScreen(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile): ScreenSnapshot? {
        val scope = com.wangzan101.mika.core.cap.phone.sms.SmsLoginScope(p.sender, p.signature, p.account)
        val s = smsLoginScreen(p.app, p.codeField, scope) ?: return null
        val account = s.elements.filter { it.resourceId == p.accountField }.singleOrNull() ?: return null
        return s.takeIf { !account.hint && account.name.filter(Char::isDigit) == p.account }
    }

    private suspend fun smsLoginScreen(pkg: String, resourceId: String,
        scope: com.wangzan101.mika.core.cap.phone.sms.SmsLoginScope): ScreenSnapshot? {
        if (!hasDisplay) return null
        // Do not guess which installation/account owns a copied or work-profile app.
        val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (top < 0 || userOf(top) != 0) return null
        appAccess.current().denial(pkg)?.let { return null }
        val current = smsSnapshot(register = true)
        return current.takeIf { com.wangzan101.mika.core.cap.phone.sms.SmsLoginMatcher.target(it, pkg, resourceId, scope) }
    }

    private suspend fun typeField(found: Field.Found, text: String): ActionResult {
        var field = found.node
        val original = FieldShape.of(field)
        var focusUncertain = false
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.refresh()
            // ACTION_FOCUS 的返回值和焦点标志都可能不可靠:没确认拿到焦点时,再按普通输入框点一下。
            if (!field.isFocused) {
                withEventClock { clock ->
                    clock.mark()
                    click(field).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
                }
                focusedEditable()?.takeIf { sameField(original, it) }?.let { field = it }
            }
            // SET_TEXT 不要求焦点,仍可以尝试;最后以读回核对为准,但把焦点未确认如实带给调用方。
            focusUncertain = !field.isFocused
        }
        val web = inWebView(field)
        // 网页编辑区要粘贴才收得到输入;密码框不经剪贴板
        var pasteUnavailable = web && field.isPassword
        var inputUncertain = false
        val placeholder = ScreenReader.placeholderOf(field)
        fun writeByInput(value: String): Boolean = when (val r = inputText(field, value)) {
            null -> { pasteUnavailable = true; setText(field, value) }
            AsciiTextKeys.NOT_SENT -> false
            AsciiTextKeys.DISPATCHED -> true
            else -> {
                // 动作结果不确定时也可能已经写入:让读回核对停在 UNKNOWN,不能再换一种方式重写。
                inputUncertain = true
                true
            }
        }
        val io = object : TextField {
            override fun read(): String? {
                if (inputUncertain) return null
                if (field.refresh() && visibleInputTarget(field)) return ScreenReader.fieldText(field, placeholder, text)
                // 有的 App 内容一变就换掉输入控件:当前拿着输入焦点的那个和原来的类型、资源 ID、位置都对得上才改读它,
                // 否则算读不回,也不往别的框里写
                val now = focusedEditable()?.takeIf { sameField(original, it) } ?: return null
                field = now
                return ScreenReader.fieldText(now, placeholder, text)
            }
            override fun write(text: String): Boolean {
                if (!web || pasteUnavailable) return setText(field, text)
                return writeByInput(text)
            }
            override fun retry(text: String): Boolean {
                if (web || pasteUnavailable) return write(text)
                // 原生框第一次 SET_TEXT 没落地时,第二次改走选择加粘贴,让编辑器收到真实输入。
                return writeByInput(text)
            }
        }
        val textsBefore = read().texts.toSet()
        // 像人一样一段一段打出来(见 TypingPacing);密码框、要粘贴才收得到的网页框照旧一次写入。
        // 写不进、框不接受半截字时停下,下面照旧整段写入并读回核对
        typing(field.packageName?.toString()).forField(web, field.isPassword).leadIn(io, text)
        var frozen = false
        val verification = typeAndVerify(
            io, text, TYPE_ATTEMPTS,
            settle = { withEventClock { clock -> clock.awaitQuiet(READBACK_QUIET_MS, READBACK_CAP_MS) } },
            // 读回一直不变时,看界面上有没有冒出含所输文字的新文字(联想词等)
            reacted = {
                val typed = text.filterNot { it.isWhitespace() }
                (read().texts.toSet() - textsBefore).any { typed in it.filterNot { c -> c.isWhitespace() } }.also { frozen = it }
            },
            secret = field.isPassword,
        )
        val hint = listOfNotNull(
            HINT_TAPPED_TO_FOCUS.takeIf { found.tapped },
            HINT_FOCUS_UNCONFIRMED.takeIf { focusUncertain },
            HINT_READBACK_FROZEN.takeIf { frozen },
            HINT_PASTE_UNAVAILABLE.takeIf { pasteUnavailable },
        ).joinToString(" ").ifEmpty { null }
        // 字可能只是显示出来、App 并没收到:不能报「一致」
        val reported = if (pasteUnavailable && verification != Verification.MISMATCH) Verification.UNKNOWN else verification
        return ok(verification = reported, hint = hint, secret = field.isPassword, relocatedTo = found.relocatedTo)
    }

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val found = when (val f = findField(ref, snapshotId, tapToFocus = false)) {
            is Field.Found -> f
            is Field.Missing -> return@withContext when {
                f.reason != NO_FOCUS -> ActionResult.Failed(f.reason)
                !somethingHoldsInputFocus() -> ActionResult.Failed(NO_FOCUS)
                else -> clearUnseen()
            }
        }
        clearField(found)
    }

    private suspend fun clearField(found: Field.Found): ActionResult {
        val field = found.node
        val cleared = if (inWebView(field)) inputText(field, "") else {
            if (setText(field, "")) AsciiTextKeys.DISPATCHED else AsciiTextKeys.NOT_SENT
        }
        return when (cleared) {
            null -> ActionResult.Failed(PASTE_UNAVAILABLE, HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> ActionResult.Failed("action_failed")
            else -> {
                val result = verified(relocatedTo = found.relocatedTo) { true }
                if (cleared == AsciiTextKeys.UNCONFIRMED && result is ActionResult.Ok) result.copy(
                    verification = Verification.UNKNOWN,
                    hint = "The clear action did not confirm delivery. Read the field before trying again.",
                ) else result
            }
        }
    }

    // ---------- 树里看不到的输入框 ----------

    /**
     * 往这一点上的输入框里打字:先点它,等界面安静;点出了树里看得到、拿着焦点的输入框就照常对着它打,
     * 否则按 [typeUnseen] 粘贴并核对,粘贴前的屏上文字在点完之后读(点出的光标、键盘不算打进去的字)。
     * 点了就换到别的 App(点到了链接、按钮)时不打字。
     */
    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val hit = pointHit(x, y)
        val anchor = hit?.contextPasteAnchor()
        val target = hit?.node?.takeIf(ScreenReader::canSetText) ?: anchor
        val original = anchor?.let(FieldShape::of)
        val keyTask = if (anchor != null && AsciiTextKeys.supports(text)) {
            runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        } else -1
        val tapped = verified { tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // 点了开出了别的 App(开到用户主屏上又挪回来的也是):不打字,照实报这一下点的结果
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        when (val focused = focusedField()) {
            is Field.Missing -> if (focused.reason == EDITOR_NOT_VISIBLE) {
                return@withContext ActionResult.Failed(EDITOR_NOT_VISIBLE)
            }
            is Field.Found -> {
                if (target != null && !samePointFocus(target, focused.node)) {
                    return@withContext ActionResult.Failed(
                        INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was sent.",
                    )
                }
                return@withContext typeField(focused, text)
            }
        }
        val typed = if (target != null && pointEditorFocusMismatch(target)) {
            // 点击没有让目标拿到焦点,另一个隐藏编辑框还拿着焦点时,按键会写进那个框;这次正文尚未发送。
            ActionResult.Failed(INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was sent.")
        } else {
            typeUnseen(text, atPoint = true)
        }
        if (typed is ActionResult.Failed && anchor != null && original != null && tapped.appSwitched == null) {
            // 只有粘贴键按下确定没发出,或剪贴板在改动前就被拒绝,才换输入通道。
            // 「已经发出但没观察到变化」可能已写入,不能再走一条会实际输入的路径。
            if (keyTask >= 0 && (typed.reason == INPUT_NOT_SENT || typed.reason == PASTE_UNAVAILABLE)) {
                typeWithKeys(anchor, original, keyTask, text)?.let { return@withContext it }
            }
            if (ContextPastePolicy.canRetry(typed.reason)) {
                val sameEditorFocused = pointEditorHasFocus(anchor, original)
                // The local Paste command itself confirms that the custom control can accept text.
                pasteFromContextMenu(anchor, text)?.let { return@withContext it }
                if (sameEditorFocused && keyTask < 0) {
                    return@withContext typed.copy(hint = "The editor did not accept clipboard input. Call hand_to_user to enter the text.")
                }
            }
        }
        typed
    }

    /** 显式点中的自绘编辑器的硬件按键降级:先确认全选已投递,再输入 ASCII,不在粘贴之后重放。 */
    private suspend fun typeWithKeys(
        anchor: AccessibilityNodeInfo, original: FieldShape, taskId: Int, text: String,
    ): ActionResult? {
        if (blockedFocusedEditor()) return ActionResult.Failed(EDITOR_NOT_VISIBLE)
        if (imeBounds() != null || focusedEditable() != null || !pointEditorHasFocus(anchor, original)) return null
        val pkg = anchor.packageName?.toString() ?: return null
        currentCoroutineContext().ensureActive()
        when (ctrlKey(KeyEvent.KEYCODE_A)) {
            AsciiTextKeys.NOT_SENT -> return null
            AsciiTextKeys.UNCONFIRMED -> return ActionResult.Failed(
                "key_input_unconfirmed", "Select-all may have reached the field. Check it before entering text again.",
            )
        }
        if (blockedFocusedEditor()) return ActionResult.Failed(EDITOR_NOT_VISIBLE)
        val sent = runCatching { shell.injectAsciiText(displayId, taskId, pkg, text) }.getOrDefault(AsciiTextKeys.UNCONFIRMED)
        return when (sent) {
            AsciiTextKeys.NOT_SENT -> null
            AsciiTextKeys.DISPATCHED -> ok(
                verification = Verification.UNKNOWN,
                hint = "Sent keyboard events after select-all. Check the field before retrying or submitting.",
            )
            else -> ActionResult.Failed("key_input_unconfirmed", "Some keys may have reached the field. Check it before retrying.")
        }
    }

    /** 焦点明确落在点位之外的节点时为 true;读不到焦点时保留原来的隐藏编辑器输入路径。 */
    private fun pointEditorFocusMismatch(anchor: AccessibilityNodeInfo): Boolean {
        val window = ScreenReader.contentWindows(windows()).firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: return false
        if (window.id != anchor.windowId) return true
        val focus = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return !samePointFocus(anchor, focus)
    }

    private fun samePointFocus(target: AccessibilityNodeInfo, focus: AccessibilityNodeInfo): Boolean =
        target.windowId == focus.windowId && (within(target, focus) || within(focus, target))

    private fun pointEditorHasFocus(anchor: AccessibilityNodeInfo, original: FieldShape): Boolean {
        if (anchor.isCheckable || anchor.isScrollable) return false
        val window = contextPasteScope(anchor, original)?.second?.singleOrNull()?.takeIf { it.isFocused } ?: return false
        if (anchor.isFocused) return true
        val root = window.root ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return !focus.isPassword && (within(anchor, focus) || within(focus, anchor))
    }

    /** [node] 是不是 [possibleAncestor] 自己或它下面的节点。 */
    private fun within(node: AccessibilityNodeInfo, possibleAncestor: AccessibilityNodeInfo): Boolean =
        ancestorOrSelf(node) { it == possibleAncestor } != null

    /** 清空这一点上的输入框,做法同 [typeAt]。 */
    override suspend fun clearAt(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val hit = pointHit(x, y)
        val target = hit?.node?.takeIf(ScreenReader::canSetText) ?: hit?.contextPasteAnchor()
        val tapped = verified { tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // 点了开出了别的 App(开到用户主屏上又挪回来的也是):不清空,照实报这一下点的结果
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        when (val focused = focusedField()) {
            is Field.Missing -> if (focused.reason == EDITOR_NOT_VISIBLE) {
                return@withContext ActionResult.Failed(EDITOR_NOT_VISIBLE)
            }
            is Field.Found -> {
                if (target != null && !samePointFocus(target, focused.node)) {
                    return@withContext ActionResult.Failed(
                        INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was cleared.",
                    )
                }
                return@withContext clearField(focused)
            }
        }
        if (target != null && pointEditorFocusMismatch(target)) {
            return@withContext ActionResult.Failed(
                INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was cleared.",
            )
        }
        clearUnseen(atPoint = true)
    }

    /**
     * 树里没有拿着焦点的输入框:输入框可能在 App 自己画的界面里,树读不到它,焦点在它上面。
     * 先全选(替换原有的字,和对着节点设字一样),再经剪贴板粘贴,按键只发到 Mika 的屏;之后比打字前后屏上读到的文字
     * 核对([UnseenField.typed])。不要求弹着输入法:按键发到这块屏拿着焦点的窗口,框不经输入法也收得到;
     * 没弹输入法而前后一点没变,就请模型先点输入框。弹着输入法时键盘上的字不算屏上的字。
     */
    private suspend fun typeUnseen(text: String, atPoint: Boolean = false): ActionResult {
        if (blockedFocusedEditor()) return ActionResult.Failed(EDITOR_NOT_VISIBLE)
        val ime = imeBox()
        val before = readVisibleText()?.let { UnseenField.outside(it, ime) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        var pasted: Int? = null
        val changed = withEventClock { clock ->
            clock.mark()
            if (blockedFocusedEditor()) return@withEventClock null
            pasted = pasteUnseen(text)
            pasted != null && pasted != AsciiTextKeys.NOT_SENT && awaitChange(clock, beforeTree, beforeFrame)
        } ?: return ActionResult.Failed(EDITOR_NOT_VISIBLE)
        when (pasted) {
            null -> return ActionResult.Failed(PASTE_UNAVAILABLE, HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> return ActionResult.Failed(
                INPUT_NOT_SENT, "The input shortcut was not delivered; no text was sent by this attempt.",
            )
            AsciiTextKeys.UNCONFIRMED -> return ok(
                changed = changed, verification = Verification.UNKNOWN,
                hint = "The paste shortcut may have been partially delivered. Check the field before retrying.",
            )
        }
        val after = readVisibleText()?.let { UnseenField.outside(it, imeBox() ?: ime) }
        return when (val r = UnseenField.typed(text, before, after, changed, keyboardUp = ime != null, atPoint = atPoint)) {
            is ActionResult.Ok -> ok(changed = r.changed, verification = r.verification, hint = r.hint)
            else -> r
        }
    }

    /**
     * 剪贴板换成 [text]、全选、粘贴,给 App 留出异步读取时间再还原。返回 null:剪贴板不能安全使用(读不到用户原来的内容),
     * 什么都没动。按键结果沿用 [AsciiTextKeys] 三态;只有正文按下确定没投递才是 NOT_SENT。
     * 标准粘贴动作存在时只调它一次,返回 false 也按不确定处理,不再补发 Ctrl+V。
     */
    private fun pasteUnseen(text: String): Int? =
        withClipboardText(text) {
            if (blockedFocusedEditor()) return@withClipboardText AsciiTextKeys.NOT_SENT
            when (ctrlKey(KeyEvent.KEYCODE_A)) {
                AsciiTextKeys.NOT_SENT -> return@withClipboardText AsciiTextKeys.NOT_SENT
                AsciiTextKeys.UNCONFIRMED -> return@withClipboardText AsciiTextKeys.UNCONFIRMED
            }
            if (blockedFocusedEditor()) return@withClipboardText AsciiTextKeys.NOT_SENT
            val focus = ScreenReader.contentWindows(windows()).firstOrNull { it.isFocused }?.root
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
            val sent = if (focus?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } == true) {
                if (focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
            } else {
                ctrlKey(KeyEvent.KEYCODE_V)
            }
            // 选区或光标可能先重绘,App 随后才异步读取剪贴板。
            Thread.sleep(PASTE_WAIT_MS)
            sent
        }

    /** Retain the node at an explicit point independently of context-menu eligibility. */
    private fun pointHit(x: Int, y: Int): NodeEntry? {
        if (handedOver() || imeBounds()?.contains(x, y) == true) return null
        val window = ScreenReader.contentWindows(windows()).firstOrNull()
            ?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
        val entries = ScreenReader.read(listOf(window), screenWidth, screenHeight).entries
        return entries.mapNotNull { entry ->
            val bounds = Rect().also(entry.node::getBoundsInScreen)
            if (bounds.contains(x, y)) entry to bounds.width().toLong() * bounds.height() else null
        }.minByOrNull { it.second }?.first
    }

    private fun NodeEntry.contextPasteAnchor(): AccessibilityNodeInfo? = node.takeIf { contextPasteCandidate(this) }

    private fun contextPasteAnchor(ref: String, snapshotId: String): AccessibilityNodeInfo? {
        if (handedOver()) return null
        val node = resolve(ref, snapshotId)?.first ?: return null
        val entry = read().entries.firstOrNull { it.node == node } ?: return null
        return node.takeIf { contextPasteCandidate(entry) }
    }

    private fun contextPasteCandidate(entry: NodeEntry): Boolean = ContextPastePolicy.canProbe(
        entry.node.isEnabled, entry.node.isPassword, entry.locator.inList, entry.element.role,
    )

    /** The editor must still be present, with only its own app's windows above it. */
    private fun contextPasteScope(
        anchor: AccessibilityNodeInfo, original: FieldShape,
    ): Pair<Rect, List<AccessibilityWindowInfo>>? {
        if (!hasDisplay || handedOver() || !anchor.refresh() || !visibleInputTarget(anchor) || blockedFocusedEditor() ||
            anchor.isPassword || (anchor.textSelectionStart >= 0 && anchor.textSelectionEnd >= 0 &&
                anchor.textSelectionStart != anchor.textSelectionEnd)) return null
        val current = FieldShape.of(anchor)
        val bounds = current.rect
        val ime = imeBounds()
        // Focusing an editor can resize or move it, even when there is no IME.
        // A changed rectangle must still belong to the original, focused node.
        val sameSize = bounds.width() == original.rect.width() && bounds.height() == original.rect.height()
        if (current.cls != original.cls || current.rid != original.rid ||
            (bounds != original.rect && !anchor.isFocused && (ime == null || !sameSize)) ||
            ime?.contains(bounds.centerX(), bounds.centerY()) == true ||
            !Rect(0, 0, screenWidth, screenHeight).contains(bounds)) return null
        val pkg = anchor.packageName?.toString() ?: return null
        val content = ScreenReader.contentWindows(windows())
        val index = content.indexOfFirst { it.id == anchor.windowId }
        if (index < 0) return null
        val scoped = content.take(index + 1)
        if (scoped.any { it.type != AccessibilityWindowInfo.TYPE_APPLICATION || it.root?.packageName?.toString() != pkg }) return null
        return bounds to scoped
    }

    /** Confirm a newly opened editor menu before using its local Paste command. */
    private suspend fun pasteFromContextMenu(anchor: AccessibilityNodeInfo, text: String): ActionResult? {
        if (!anchor.refresh()) return null
        val original = FieldShape.of(anchor)
        if (contextPasteScope(anchor, original) == null) return null
        val beforeText = readVisibleText()?.let { UnseenField.outside(it, imeBox()) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        val menu = ContextEditMenu(Resources.getSystem().getString(android.R.string.paste))
        fun isPaste(node: AccessibilityNodeInfo): Boolean {
            val label = ScreenReader.ownLabel(node)
            return menu.isPaste(node.viewIdResourceName, node.className?.toString()?.substringAfterLast('.'), label)
        }
        fun controls(scoped: List<AccessibilityWindowInfo>) =
            ScreenReader.read(scoped, screenWidth, screenHeight).entries.filter { it.node.isClickable && it.node.isEnabled }
        fun near(bounds: Rect, controls: List<NodeEntry>): List<AccessibilityNodeInfo> {
            val margin = min(screenWidth, screenHeight) / 5
            val vicinity = Rect(bounds).apply { inset(-margin, -margin) }
            val ime = imeBounds()
            return controls.filter { entry ->
                val x = entry.element.centerX
                val y = entry.element.centerY
                vicinity.contains(x, y) && ime?.contains(x, y) != true
            }.map { it.node }
        }
        val context = currentCoroutineContext()
        val pasted = withClipboardText(text) {
            context.ensureActive()
            val (startBounds, scoped) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
            val before = controls(scoped)
            if (near(startBounds, before).any(::isPaste)) return@withClipboardText AsciiTextKeys.NOT_SENT
            val existing = before.map { it.node }.toSet()
            // 自绘编辑器的长按阈值可能比系统设置更长。
            val hold = maxOf(PASTE_MENU_MIN_HOLD_MS, ViewConfiguration.getLongPressTimeout().toLong() + 2 * POLL_MS)
            for (attempt in 0..1) {
                context.ensureActive()
                val (bounds, windows) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
                if (attempt > 0 && (!anchor.isFocused || windows.singleOrNull()?.isFocused != true ||
                        controls(windows).any { it.node !in existing })) {
                    return@withClipboardText AsciiTextKeys.NOT_SENT
                }
                val point = GesturePacing.Point(bounds.centerX().toDouble(), bounds.centerY().toDouble(), 0)
                if (!injectStroke(listOf(point, point.copy(t = hold)))) return@withClipboardText AsciiTextKeys.NOT_SENT
                val deadline = SystemClock.uptimeMillis() + PASTE_MENU_WAIT_MS
                var previous: AccessibilityNodeInfo? = null
                var sawNewControl = false
                do {
                    context.ensureActive()
                    val (currentBounds, currentWindows) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
                    val current = controls(currentWindows)
                    sawNewControl = sawNewControl || current.any { it.node !in existing }
                    // Some editors add a clickable backdrop around the existing field.
                    // Its ancestors are containers, not newly offered edit commands.
                    val containers = generateSequence(anchor) { it.parent }.toSet()
                    val paste = menu.freshPaste(existing, near(currentBounds, current), ::isPaste, containers)
                    if (paste != null && paste == previous) {
                        if (!paste.refresh() || !paste.isVisibleToUser || !paste.isEnabled || !paste.isClickable || !isPaste(paste) || handedOver()) {
                            return@withClipboardText AsciiTextKeys.NOT_SENT
                        }
                        context.ensureActive()
                        // performAction=false 不能证明点击没落到菜单上;按不确定收尾,不补发别的输入。
                        if (!paste.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
                        Thread.sleep(PASTE_WAIT_MS)
                        return@withClipboardText AsciiTextKeys.DISPATCHED
                    }
                    previous = paste
                    Thread.sleep(POLL_MS)
                } while (SystemClock.uptimeMillis() < deadline)
                // 编辑器可能忽略第一次长按;只有没冒出菜单控件时才再按一次。
                if (sawNewControl) return@withClipboardText AsciiTextKeys.NOT_SENT
            }
            AsciiTextKeys.NOT_SENT
        }
        return when (pasted) {
            null -> ActionResult.Failed(PASTE_UNAVAILABLE, HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> null
            AsciiTextKeys.DISPATCHED -> {
                val afterText = readVisibleText()?.let { UnseenField.outside(it, imeBox()) }
                val changed = beforeTree != treeHash() || !beforeFrame.contentEquals(frameSignature())
                val verification = UnseenField.verify(text, beforeText, afterText, changed)
                ok(changed = changed, verification = verification, hint = "Used and checked the editor's Paste menu.")
            }
            else -> ok(
                verification = Verification.UNKNOWN,
                hint = "The Paste menu click did not confirm delivery. Check the field before retrying.",
            )
        }
    }

    /** 和 [typeUnseen] 同样的前提;全选确认投递后才按删除,不确定时停止,不换路径重试。 */
    private suspend fun clearUnseen(atPoint: Boolean = false): ActionResult {
        if (blockedFocusedEditor()) return ActionResult.Failed(EDITOR_NOT_VISIBLE)
        val keyboardUp = imeBox() != null
        when (ctrlKey(KeyEvent.KEYCODE_A)) {
            AsciiTextKeys.NOT_SENT -> return ActionResult.Failed(
                INPUT_NOT_SENT, "The select-all key-down was not delivered; delete was not sent.",
            )
            AsciiTextKeys.UNCONFIRMED -> return ok(
                verification = Verification.UNKNOWN,
                hint = "Select-all may have been partially delivered; delete was not sent. Check the field before retrying.",
            )
        }
        if (blockedFocusedEditor()) return ActionResult.Failed(EDITOR_NOT_VISIBLE)
        if (!shell.injectKey(displayId, KeyEvent.KEYCODE_DEL)) return ok(
            verification = Verification.UNKNOWN,
            hint = "Delete did not confirm delivery. Check the field before trying to clear it again.",
        )
        return UnseenField.cleared(verified { true }, keyboardUp, atPoint)
    }

    private fun ctrlKey(keyCode: Int): Int =
        runCatching { shell.injectCtrlKey(displayId, keyCode) }.getOrDefault(AsciiTextKeys.UNCONFIRMED)

    /** 这块屏上弹着输入法(这时按返回,安卓先收起输入法)。 */
    fun keyboardUp(): Boolean = hasDisplay && imeBox() != null

    /** 这块屏上输入法窗口的范围(0–999 的 [l,t,r,b]);没弹输入法为 null。弹着时它上面的字不算屏上的字。 */
    private fun imeBox(): List<Int>? {
        val r = imeBounds() ?: return null
        return listOf(normalize(r.left, screenWidth), normalize(r.top, screenHeight), normalize(r.right, screenWidth), normalize(r.bottom, screenHeight))
    }

    private fun imeBounds(): Rect? {
        val w = windows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        return Rect().also(w::getBoundsInScreen).takeUnless { it.isEmpty }
    }

    /**
     * 启动到虚拟屏;[cold] 时先结束进程,回到 App 的初始页面。用户正在主屏用这个 App 时不启动也不结束它,
     * 稍等后重试,仍不行就交给 Agent 去暂停或请用户接管。虚拟屏在这里第一次用到时才建。
     */
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = open(packageName, null, cold)

    /**
     * 打开装在另一份用户 [userId] 里的那份(调用方先按 [appCopies] 核对过):内置助手启动前再按同一规则核一次那份用户能不能用,
     * 不能用为 copy_not_found;占用、冷启动都只看这一份。[userId] 就是 Mika 自己的用户时等于 [openApp]。
     */
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        open(packageName, userId.takeIf { it != myUser }, cold)

    /**
     * 等别的任务放开 [packageName](模型打开它时要了等):放开指别的屏上已经没有它、占用登记里也没有别的任务占着。
     * 只在并行任务时有意义,否则没有别的任务和它争。
     */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        if (!checkOtherTasks) return AppWait.Unsupported
        val user = userId?.takeIf { it != myUser }
        return awaitApp(packageName, user, timeoutMs) {
            withContext(Dispatchers.IO) { !onOtherDisplay(packageName, user) && appFree(packageName, user) }
        }
    }

    /** 这个包([user] 那份)此刻开在 Mika 的另一块屏上(别的任务手里)。 */
    private fun onOtherDisplay(packageName: String, user: Int?): Boolean {
        val other = runCatching { if (user == null) shell.displayOfPackage(packageName) else shell.displayOfPackageInUser(packageName, user) }.getOrDefault(-1)
        return other >= 0 && other != display.current?.displayId
    }

    /** [openApp] / [openAppCopy] 的共同部分;[user] 为空是本来那份。 */
    private suspend fun open(packageName: String, user: Int?, cold: Boolean): ActionResult = withContext(Dispatchers.IO) {
        // 同一个 App 不能同时在两块屏上:系统会把整个任务挪过来,等于从另一个任务手里抢走它。装了两份的,两份各算各的
        if (checkOtherTasks) {
            if (onOtherDisplay(packageName, user)) return@withContext IN_USE_BY_OTHER
            if (!(if (user == null) reserve(packageName) else reserveCopy(packageName, user))) return@withContext IN_USE_BY_OTHER
        }
        // 打开前最上面的窗口:之后冒出来的才可能是这次打开引起的选择框(上次没选、又打开一次时还是同一个选择框)
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        // 画面新鲜度从这次启动开始算:之后的截图、认字只认这之后到的帧(见 FrameFreshness)
        val startedAt = System.nanoTime()
        beforeOpen(packageName, user)
        fun launch() = when {
            user != null -> shell.startAppInUser(id, packageName, user, cold)
            cold -> shell.restartApp(id, packageName)
            else -> shell.startApp(id, packageName)
        }
        var code = launch()
        val retryUntil = SystemClock.uptimeMillis() + IN_USE_RETRY_MS
        while (code == ShellService.START_IN_USE_ON_MAIN && SystemClock.uptimeMillis() < retryUntil) {
            delay(IN_USE_POLL_MS)
            code = launch()
        }
        if (code != ShellService.START_OK && code != ShellService.START_OK_COLD_IGNORED) notOpened(packageName, user)
        when (code) {
            ShellService.START_OK, ShellService.START_OK_COLD_IGNORED -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_NOT_FOUND -> return@withContext ActionResult.Failed("app_not_found")
            ShellService.START_USER_NOT_ALLOWED -> return@withContext ActionResult.Failed(COPY_NOT_FOUND)
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        // 同一个 App 只是回到已有静态页时可能不会来新帧,旧帧仍就是当前画面;冷启动、另一份用户或换 App 必须等新帧。
        val needsNewFrame = cold || user != null || before != packageName
        awaitOpened(packageName, before, hint = if (code == ShellService.START_OK_COLD_IGNORED) HINT_COLD_IGNORED else null, user = user,
            launchedAt = if (needsNewFrame) startedAt else 0L)
    }

    /**
     * 把文字分享给 [packageName]:和 [openApp] 一样第一次用时才建屏、用户正在主屏用它时不打开,打开后同样等它到前台、
     * 认系统问打开哪一个。这个 App 不收文字分享为 cannot_share。只打开分享页,发不发由模型在那一页上点。
     */
    override suspend fun shareText(packageName: String, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (checkOtherTasks) {
            val other = runCatching { shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != display.current?.displayId) return@withContext IN_USE_BY_OTHER
            if (!reserve(packageName)) return@withContext IN_USE_BY_OTHER
        }
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        val startedAt = System.nanoTime()
        beforeOpen(packageName, null)
        val code = runCatching { shell.startShare(id, packageName, text) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /**
     * 在 Mika 的屏上打开 Mika 的兼容性自检页([intent] 指明组件;只给兼容性自检用)。那一页只对 shell 这一级的调用方开放,
     * 交给内置助手以 shell 身份直接启动到这块屏(助手只认这一个组件);shell 启动不受后台启动页面的限制,Mika 在不在前台都一样。
     * 之后和 [openApp] 一样等它到 Mika 屏的最上面;第一次用时建屏。
     */
    suspend fun openOwnPage(intent: Intent): ActionResult = withContext(Dispatchers.IO) {
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        val startedAt = System.nanoTime()
        when (runCatching { shell.startSystemAction(id, intent, null) }.getOrDefault(ShellService.START_ERROR)) {
            ShellService.START_OK -> Unit
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("own_page_not_allowed")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(a11y.packageName, before, hint = null, launchedAt = if (before != a11y.packageName) startedAt else 0L)
    }

    /**
     * 启动发出之后:等 [packageName] 到 Mika 屏的最上面,或系统先问打开哪一个([before] 是启动前最上面的窗口所在的包)。
     * [sharing]:这次是分享(App 有几个收分享的页面时系统问用哪一个,选项里不一定有 App 名):最上面换成了系统包的窗口也算在问。
     */
    private suspend fun awaitOpened(
        packageName: String, before: String?, hint: String?, sharing: Boolean = false, user: Int? = null, launchedAt: Long = 0L,
    ): ActionResult {
        val beforeTree = treeHash()
        targetPackage = packageName
        chooserPackage = null
        requestedUser = user
        // 从等待窗口到顶起就保护所有返回路径(包括系统选择器),不是只有最后的成功路径才拦旧帧。
        // 同 App 静态页可以不另等新帧,但不能借此清掉上一次尚未由新帧解除的保护。
        if (launchedAt > 0) frameGuardSince = launchedAt
        // 只有确实还在原来的页面,失败后才能继续用原来的静态帧;已经切走或换页时仍须等新帧。
        fun clearGuardIfUnchanged() {
            val now = foregroundTaskPackage() ?: ScreenReader.topPackage(windows())
            if (now == before && treeHash() == beforeTree) frameGuardSince = null
        }
        // 打开了:Mika 屏上最上面的任务是它(问不到任务时退回看无障碍读到的最上面的窗口);指定了哪一份时还得是那份用户的任务
        fun launched(): Boolean = (foregroundTaskPackage()?.let { it == packageName } ?: (ScreenReader.topPackage(windows()) == packageName)) &&
            (user == null || userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).let { it < 0 || it == user })
        fun picker(): String? = if (sharing) systemPickerOf(packageName, before) else null
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        // 系统先问打开哪一个时 App 不会自己到前台:看到选择框就不再干等
        while (!launched() && chooserOf(packageName, before) == null && picker() == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val onTop = launched()
        val task = if (onTop) shell.topTaskId(displayId) else -1
        _target.value = TargetApp(packageName, task, if (task >= 0) userOf(task) else user ?: -1)
        if (!onTop && user != null) {
            // 要的是另一份,系统却先问打开哪一个:自己逐个点选项,认开出来的是哪一份,不交给模型猜
            if (chooserTargets(packageName, before) != null) return resolveCopyChooser(packageName, user, before, hint)
                .also { if (it is ActionResult.Failed) clearGuardIfUnchanged() }
            // 直接开出来的是这个 App 的别的那份:照实失败,不当成要的那份
            if (foregroundTaskPackage() == packageName) return copyNotOpened(user, userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)))
                .also { clearGuardIfUnchanged() }
        }
        // 系统在问打开哪一个(App 装了两份等):算打开了,等模型选;选完 App 到了前台,之后的动作照常认它是目标。
        // App 已在最上面时盖着的系统窗口(权限框)不算
        if (!onTop) chooserOf(packageName, before)?.let { first ->
            // 第一次碰到时才去问装了几份,好标出哪个是另一份
            if (profiles == null) otherProfiles()
            val choice = chooserOf(packageName, before) ?: first
            chooserPackage = choice.packageName
            return ok().copy(chooser = choice, appSwitched = null, switchedByTarget = false)
        }
        // 分享时系统问用这个 App 的哪个分享页:同样算打开了,等模型选
        if (!onTop) picker()?.let { pkg ->
            chooserPackage = pkg
            return ok(hint = HINT_SHARE_PICKER).copy(appSwitched = null, switchedByTarget = false)
        }
        // 没打开就照实说,并说清此刻最上面的是什么,不报成功
        if (!onTop) {
            clearGuardIfUnchanged()
            val now = foregroundTaskPackage() ?: ScreenReader.topPackage(windows())
            // 要的是另一份,最上面却是这个 App 的别的那份
            val copy = if (user != null && now == packageName) " (a different copy of the app)" else ""
            return ActionResult.Failed("app_not_in_front", hint = now?.let { "In the foreground now: $it$copy." } ?: "Nothing came to the foreground.")
        }
        // 树稳定只是窗口到了顶,画面可能还没合成:等这次启动之后的第一帧;等不到就持续拦住旧帧,
        // 不把上一屏留在缓冲里的残影当这次打开的画面交给模型(issue #253)
        if (launchedAt > 0) awaitFrameAfter(launchedAt)
        // 打开了,但另一个包的窗口盖在上面(权限弹窗、账号选择器):照实说是谁,仍算打开了
        val cover = ScreenReader.topPackage(windows())?.takeIf { it != packageName }
        return ok(hint = hint).let { if (cover != null) it.copy(coveredBy = cover, appSwitched = null, switchedByTarget = false) else it }
    }

    /**
     * 要的是另一份用户 [user] 里的那份,系统却在 Mika 屏上问打开哪一个(选项可能名字一模一样,看不出哪个是哪份):
     * 逐个点选项,等开出来,读最上面任务所属的用户;是要的那份就算打开。不是就按一次返回让它退到后台(那可能是用户手上那份的任务,
     * 不结束它),再打开一次、点下一个。都试过还不是,照实失败 copy_not_opened,说开出来的是哪一份;不把错的那份当成功。
     */
    private suspend fun resolveCopyChooser(packageName: String, user: Int, before: String?, hint: String?): ActionResult {
        fun topTask() = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        suspend fun opened(): ActionResult? {
            if (foregroundTaskPackage() != packageName) return null
            val task = topTask()
            if (userOf(task) != user) return null
            _target.value = TargetApp(packageName, task, user)
            return ok(hint = hint)
        }
        var tried = 0
        var shown: Int? = null
        while (true) {
            val targets = chooserTargets(packageName, before) ?: break
            if (tried >= targets.size) break
            val (x, y) = targets[tried++]
            if (!runCatching { shell.injectTap(displayId, x, y) }.getOrDefault(false)) break
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (foregroundTaskPackage() != packageName && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            waitStable(SETTLE_CAP_MS)
            if (foregroundTaskPackage() != packageName) break
            opened()?.let { return it }
            shown = userOf(topTask())
            Log.i(TAG, "chooser option $tried opened user $shown, not the requested $user")
            runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }
            waitStable(SETTLE_CAP_MS)
            if (tried >= targets.size) break
            if (runCatching { shell.startAppInUser(displayId, packageName, user, false) }.getOrDefault(ShellService.START_ERROR) != ShellService.START_OK) break
            val until = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (chooserTargets(packageName, before) == null && SystemClock.uptimeMillis() < until) {
                // 这一次直接开出了要的那份
                opened()?.let { return it }
                delay(POLL_MS)
            }
        }
        return copyNotOpened(user, shown)
    }

    /** 要的那份 [user] 没打开;[shown] 是开出来的那份所属的用户(没开出来或读不到为 null / 负数)。 */
    private fun copyNotOpened(user: Int, shown: Int?): ActionResult.Failed {
        val what = when {
            shown == null || shown < 0 -> "Nothing from the app came to the front."
            shown == myUser -> "The main app opened instead (user $shown)."
            else -> "The copy in user $shown opened instead."
        }
        return ActionResult.Failed(COPY_NOT_OPENED, "$what Requested: the copy in user $user.")
    }

    /** 系统在 Mika 屏上问打开哪一个时,那几个选项的中心点(屏上像素,先后同选项);不是在问为 null。认法同 [chooserOf]。 */
    private fun chooserTargets(packageName: String, before: String?): List<Pair<Int, Int>>? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val entries = ScreenReader.read(listOf(w), screenWidth, screenHeight).entries
        val picked = LaunchChooser.optionIndices(label, entries.map { it.element }) ?: return null
        return picked.map { i -> android.graphics.Rect().also(entries[i].node::getBoundsInScreen).let { it.centerX() to it.centerY() } }
    }

    /**
     * 打开链接或设置页。和 [openApp] 一样第一次用时才建屏;用户在主屏用着那个 App 时不打开。
     * 设置页预期落在系统设置所在的包,指定了 App 的落在那个 App;落到别处(系统选择器、跳转页)照样成功,带提示。
     */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = withContext(Dispatchers.IO) {
        // 指定了 App 的由应用访问边界看过;没指定的,名单非空时在这里挑一个没被禁用的接手
        val pinned = packageName ?: allowedLinkHandler(action, uri)
        val id = display.get().displayId
        val expected = pinned ?: settingsPackage.takeIf { action != null }
        if (checkOtherTasks && expected != null && !reserve(expected)) return@withContext IN_USE_BY_OTHER
        val before = ScreenReader.topPackage(windows())
        val beforeTree = treeHash()
        val startedAt = System.nanoTime()
        // 事先知道由谁打开的(指定了 App、设置页、只有一个 App 接的链接):启动前先静上
        val early = expected ?: linkHandler(action, uri)
        early?.let { beforeOpen(it, null) }
        val code = shell.startView(id, action, uri, pinned)
        if (code != ShellService.START_OK) early?.let { notOpened(it, null) }
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("link_unresolved")
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("invalid_link")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        // 链接可能落到另一个 App、系统选择器或同 App 的新页面,同样不能把启动前的帧交出去。
        frameGuardSince = startedAt
        fun landed(): Boolean {
            val top = ScreenReader.topPackage(windows())
            return if (expected != null) top == expected else top != null && (top != before || treeHash() != beforeTree)
        }
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (!landed() && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val top = ScreenReader.topPackage(windows())
        val changed = top != before || treeHash() != beforeTree
        if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
        // 只认真正打开链接的 App:落在系统选择器或别处时不把它当成目标
        val opened = top?.takeIf { if (expected != null) it == expected else it !in systemPickers }
        if (opened != null) {
            // 事先不知道由谁打开的链接,落地后再占;占不到也不撤回(外壳那边已查过别的屏)
            if (checkOtherTasks) reserve(opened)
            targetPackage = opened
            requestedUser = null
            val task = shell.topTaskId(displayId)
            _target.value = TargetApp(opened, task, userOf(task))
        }
        ok(hint = if (expected != null && top != expected) hintLinkElsewhere(top, expected) else null)
    }

    /**
     * 禁用名单非空、链接没指定 App 时由谁打开:系统默认的那个没被禁用就用它,不然用第一个没被禁用的;能接的全被禁用就拒绝。
     * 名单为空、或没有 App 能接时为 null(照常交给系统,不多查一次)。
     */
    private fun allowedLinkHandler(action: String?, uri: String?): String? {
        val access = appAccess.current()
        if (access.blocked.isEmpty()) return null
        val intent = Intent(action ?: Intent.ACTION_VIEW).apply {
            uri?.let { data = android.net.Uri.parse(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = a11y.packageManager
        val candidates = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val allowed = candidates.filter { access.denial(it) == null }
        if (allowed.isEmpty()) access.denial(candidates.first())?.let { throw it }
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return default?.takeIf { it in allowed } ?: allowed.first()
    }

    /** 没指定 App 的链接由谁打开:系统直接定下的那一个;要问用户选(落在系统选择器)或读不到为 null。 */
    private fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in systemPickers }

    private fun hintLinkElsewhere(top: String?, expected: String) =
        "The link opened, but the screen shows ${top ?: "no app yet"} instead of $expected: a system chooser or a redirect page may have taken it. Observe before acting."

    /**
     * 有任务被挪回这块屏后,等它的窗口出现在无障碍窗口列表里再让 Agent 读屏;
     * 否则紧接着的 observe 可能读到一块空屏。等不到也返回,由 Agent 自己读屏判断。
     */
    suspend fun awaitContent(): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (ScreenReader.topPackage(windows()) == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        ScreenReader.topPackage(windows()) != null
    }

    override suspend fun listApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) { launchableApps(a11y.packageManager, a11y.packageName) }

    // ---------- 系统动作 ----------

    /**
     * 经安卓标准 intent 做系统动作(见 [SystemIntents])。先在 Mika 这边查谁接(能力检测,不用建屏):没有就 not_supported;
     * 有几个时按 [SystemIntents.choose] 直接指定一个。再交给内置助手启动到 Mika 的屏(第一次用时建屏),动作前后照常盯着主屏。
     * 闹钟、计时器本该悄悄设好:之后读系统的下一个闹钟核对;App 却开了界面时照实报。闹钟列表、新日程要等那个 App 到最上面。
     */
    override suspend fun systemAction(action: SystemAction): SystemActionResult = withContext(Dispatchers.IO) {
        val intent = SystemIntents.toIntent(SystemIntents.spec(action))
        val pm = a11y.packageManager
        val handlers = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
                SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
        }.getOrDefault(emptyList())
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        // 由谁接只有这里知道:跳过被禁用的;能接的全被禁用就拒绝
        val access = appAccess.current()
        val allowedHandlers = if (access.blocked.isEmpty()) handlers else handlers.filter { access.denial(it.packageName) == null }
        if (handlers.isNotEmpty() && allowedHandlers.isEmpty()) access.denial(handlers.first().packageName)?.let { throw it }
        val handler = SystemIntents.choose(allowedHandlers, default)
            ?: return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
        val pkg = handler.packageName
        intent.component = android.content.ComponentName(pkg, handler.className)
        if (checkOtherTasks && !action.silent && !reserve(pkg)) return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
        val alarms = a11y.getSystemService(AlarmManager::class.java)
        val alarmBefore = runCatching { alarms?.nextAlarmClock?.triggerTime }
        val id = display.get().displayId
        val topBefore = ScreenReader.topPackage(windows())
        val beforeTree = treeHash()
        val startedAt = System.nanoTime()
        val w = armSpillWatch()
        // 接手的页面要求的权限(设闹钟)shell 可能没有:备一个以 Mika 身份启动的
        val asMika = PendingIntent.getActivity(a11y, SYSTEM_ACTION_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // 悄悄设好的(闹钟、计时器)不开界面,不去静它:时钟到点要响
        if (!action.silent) beforeOpen(pkg, null)
        val code = runCatching { shell.startSystemAction(id, intent, asMika) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK && !action.silent) notOpened(pkg, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext SystemActionResult(ActionResult.Failed("app_in_use_by_user"), pkg)
            ShellService.START_IN_USE_BY_OTHER -> return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
            ShellService.START_UNRESOLVED -> return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
            else -> return@withContext SystemActionResult(ActionResult.Failed("launch_failed"), pkg)
        }
        if (!action.silent) frameGuardSince = startedAt
        fun top() = ScreenReader.topPackage(windows())
        var alarmAfter = alarmBefore.getOrNull()
        if (action is SystemAction.SetAlarm && alarmBefore.isSuccess) {
            // 时钟 App 多半在后台写好再登记:等系统的下一个闹钟变了,最多等一会儿
            val deadline = SystemClock.uptimeMillis() + ALARM_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                alarmAfter = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
                if (alarmAfter != alarmBefore.getOrNull()) break
                delay(ALARM_POLL_MS)
            }
        } else if (!action.silent) {
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (top() != pkg && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        }
        waitStable(SETTLE_CAP_MS)
        val landed = top() == pkg
        // 本该悄悄设好的,屏上却多了那个 App 的界面
        val showing = action.silent && landed && topBefore != pkg
        if (!action.silent || showing) {
            val changed = top() != topBefore || treeHash() != beforeTree
            if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
        }
        if (landed && (!action.silent || showing)) {
            targetPackage = pkg
            requestedUser = null
            val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
            _target.value = TargetApp(pkg, task, userOf(task))
        }
        val hint = if (!action.silent && !landed) hintNotInFront(top(), pkg) else null
        SystemActionResult(
            ok(hint = hint, spill = takeSpill(w)), handledBy = pkg,
            nextAlarmBefore = alarmBefore.getOrNull(), nextAlarmAfter = alarmAfter, alarmReadable = alarmBefore.isSuccess, showing = showing,
        )
    }

    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        withContext(Dispatchers.IO) { CalendarBulkWriter.insert(a11y, events) }

    private fun hintNotInFront(top: String?, expected: String) =
        "$expected took the action, but the screen shows ${top ?: "no app yet"}. Observe before acting."

    // ---------- 滚轮 ----------

    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext WheelOutcome(false, null, null, 0, "none", error = "no_app_open")
        val (node, _) = resolve(ref, snapshotId) ?: return@withContext WheelOutcome(false, null, null, 0, "none", error = "ref_expired")
        val driver = WheelDriver(
            screenWidth, screenHeight,
            quiet = { q, cap -> withEventClock { it.awaitQuiet(q, cap) } },
            // 慢拖:按节奏走时末尾减速到停、松手不甩,免得转过头;慢拖拖不动时换成快快划一下
            drag = { points, slow -> drag(points, if (slow) WHEEL_DRAG_MS else WHEEL_SWIPE_MS, bow = false) },
            ocr = { readVisibleText() },
            imeShown = { imeBox() != null },
            back = { runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }.getOrDefault(false) },
        )
        driver.set(node, value)
    }

    // ---------- 打开哪一个 ----------

    /** 最上面一个读得到的窗口(不论类型:系统的选择框有的是应用窗口,有的是系统窗口)所在的包。 */
    private fun topWindowPackage(): String? =
        ScreenReader.contentWindows(windows()).firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /**
     * 打开 [packageName] 后系统在问打开哪一个:最上面换成了别的系统包的窗口([before] 是打开前最上面的),窗口里的选项提到这个 App 的名字。
     * 不是为 null。只认系统包:别的 App 的窗口提到这个名字不算。
     */
    private fun chooserOf(packageName: String, before: String?): LaunchChoice? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val elements = ScreenReader.read(listOf(w), screenWidth, screenHeight).entries.map { it.element }
        val options = LaunchChooser.options(label, elements, profiles?.get(packageName).orEmpty()) ?: return null
        return LaunchChoice(pkg, options)
    }

    /** 最上面一个读得到的窗口换成了系统包的(不是 [packageName]、也不是启动前的 [before]):那个包;不是为 null。 */
    private fun systemPickerOf(packageName: String, before: String?): String? {
        val pkg = topWindowPackage() ?: return null
        return pkg.takeIf { it != packageName && it != before && isSystemPackage(it) }
    }

    private val systemPackages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 系统自带的包;Mika 自己看不到的包(安卓 11 起的包可见性)问内置助手。 */
    private fun isSystemPackage(pkg: String): Boolean = pkg in systemPickers || systemPackages.getOrPut(pkg) {
        val flags = runCatching { a11y.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull()
        if (flags != null) flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        else runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false)
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = a11y.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 也装在别的用户里的 App(包名 → 那几份用户的名字);第一次问内置助手,之后用这份。问不到时不记,下次再问。 */
    @Volatile private var profiles: Map<String, List<String>>? = null

    override suspend fun otherProfiles(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        profiles ?: readProfiles()?.also { profiles = it }.orEmpty()
    }

    private fun readProfiles(): Map<String, List<String>>? {
        val me = myUser
        val users = AppProfiles.parseUsers(runCatching { shell.listUsers() }.getOrNull() ?: return null).filter { it.id != me && it.running }
        return AppProfiles.alsoIn(users.associateWith { u ->
            AppProfiles.parsePackages(runCatching { shell.listPackages(u.id) }.getOrNull() ?: return null)
        })
    }

    /** 能打开的另一份 App(规则见 [AppCopies],内置助手判哪几份用户能用);每次现问,问不到为空。 */
    override suspend fun appCopies(): List<AppCopy> = withContext(Dispatchers.IO) {
        runCatching { AppCopyReader.read(shell, listApps()) }.getOrNull().orEmpty()
            .also { copies -> copiedPackages = copiedPackages + copies.map { it.packageName } }
    }

    /** 列出过另一份的包:换没换成另一份([switchedTo])只对它们问。 */
    @Volatile private var copiedPackages: Set<String> = emptySet()

    /** 任务所属的用户;任务不存在或问不到为 -1。 */
    private fun userOf(task: Int): Int = if (task < 0) -1 else runCatching { shell.userOfTask(task) }.getOrDefault(-1)

    // ---------- 核实 ----------

    /**
     * 执行动作,等界面安静后比对前后指纹;再看前台有没有被切走。动作前后盯着主屏:动作让 App 开到了用户主屏上就挪回来,
     * 结果里报 opened(见 [takeSpill])。上一个动作过了一会儿才开到主屏上的,这一个动作不做,先说屏幕变了。
     */
    private suspend fun verified(
        relocatedTo: String? = null,
        fallback: (suspend (EventClock) -> Boolean)? = null,
        retryWhenUnchanged: Boolean = true,
        action: suspend () -> Boolean,
    ): ActionResult {
        lateSpill()?.let { return ActionResult.Failed("opened_elsewhere", hintLateSpill(it.opened)) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        // 只有可能补点的动作才需要在动作前记住前台包名和输入法边界,其余动作不白读两次窗口
        val backup = fallback?.takeIf { retryWhenUnchanged }
        val beforePkg = backup?.let { ScreenReader.topPackage(windows()) }
        val beforeIme = if (backup != null) imeBounds() else null
        val w = armSpillWatch()
        val changed = withEventClock { clock ->
            clock.mark()
            if (action()) {
                val retry: (suspend () -> Boolean)? = if (backup != null) {
                    {
                        if (!handedOver() && ScreenReader.topPackage(windows()) == beforePkg && imeBounds() == beforeIme) {
                            backup(clock)
                        } else false
                    }
                } else null
                awaitChange(clock, beforeTree, beforeFrame, retry)
            } else if (fallback != null) {
                if (fallback(clock)) awaitChange(clock, beforeTree, beforeFrame) else null
            } else {
                null
            }
        } ?: return ActionResult.Failed("action_failed")
        // 从挪回来的任务里又打开了一个的,一并算进来
        val profile = adoptFromAdopted(w)
        val spill = takeSpill(w)
        return ok(changed = changed || spill?.opened?.pulledBack == true, relocatedTo = relocatedTo, spill = spill, inProfile = profile)
    }

    // ---------- 开到用户主屏上的 ----------

    /** 一次动作的盯梢:[since] 开始的时刻,[topBefore] 那时主屏最上面的任务,[agentTopBefore] 那时 Mika 屏上最上面的任务。 */
    private class Watch(val since: Long, val topBefore: Int, val agentTopBefore: Int)

    /** 挪回来的 App 和(系统问打开哪一个时)认出的选项。 */
    private class Spill(val opened: OpenedElsewhere, val chooser: LaunchChoice?)

    /** 最近一次动作的盯梢;过了 [SPILL_WINDOW_MS] 清掉。 */
    @Volatile private var watch: Watch? = null

    /** 已经判过的记录到哪个时刻:同一条不判两次。 */
    @Volatile private var consumed = 0L

    /** 从用户主屏挪回 Mika 屏上的任务,和从它们里面打开的:它们在最上面算目标 App 打开的,不算被切走。 */
    private val adopted: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 系统在用户主屏上问打开哪一个、等用户选的那一次:[taskId] 问的那个窗口的任务,[packageName] 它的包,[app] 问的是哪个 App,
     * [topBefore] 它出来前主屏最上面的任务,[agentTop] 那时 Mika 屏上最上面的任务,[since] 它出来的时刻。
     * 选择框从不挪:挪了屏它要按新屏重建,有的重建时自己关掉;Mika 也不替别的 App 重新打开它(打开的一方得是原来那个 App)。
     */
    class UserPick(val taskId: Int, val packageName: String, val app: String?, val topBefore: Int, val agentTop: Int, val since: Long)

    private val _userPick = MutableStateFlow<UserPick?>(null)
    /** 正等着用户在主屏上选;任务据此停住,胶囊上请用户选一下。 */
    val userPick: StateFlow<UserPick?> = _userPick.asStateFlow()

    /** 用户选完之后:选出来的 App 要用户亲手处理([NeedsUser],留在主屏),其余的已经办好(结果在下一次读屏或动作里报)。 */
    sealed interface PickResult {
        data object Settled : PickResult
        class NeedsUser(val taskId: Int, val packageName: String, val app: String?, val userId: Int) : PickResult
    }

    /** 等用户的那些事办完之后要告诉模型的,下一次读屏或动作时报出去。 */
    @Volatile private var pendingReport: Spill? = null

    /** 用户在主屏上点东西的时刻和包(只在动作前后那一小段里记)。 */
    private val userTaps = java.util.concurrent.ConcurrentLinkedDeque<MainScreenSpill.Tap>()
    private val spillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var tapWatch: Job? = null
    @Volatile private var tapWatchUntil = 0L

    /** 用户回主屏桌面的导航证据；桌面 App 按 HOME 解析，不写死。 */
    private val homeApps: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching { a11y.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList()).toSet()
    }

    /** 不算开到主屏上的:Mika 自己、桌面 App。 */
    private val notSpills: Set<String> by lazy { homeApps + a11y.packageName }

    /** 动作之前:请内置助手盯着主屏,记下此刻两块屏最上面的任务;没有屏、接管中或助手不支持时为 null(不盯)。 */
    private fun armSpillWatch(): Watch? {
        if (!hasDisplay || handedOver()) return null
        val since = SystemClock.uptimeMillis()
        watchUserTaps(since + SPILL_WINDOW_MS)
        val top = runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }.getOrNull() ?: return null
        val agentTop = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        return Watch(since, top, agentTop).also { watch = it }
    }

    /** 盯梢期间记下用户在主屏上的点按(Mika 自己的浮层不算);到期自己停。 */
    private fun watchUserTaps(until: Long) {
        tapWatchUntil = maxOf(tapWatchUntil, until)
        if (tapWatch?.isActive == true) return
        tapWatch = spillScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                    val systemTouch = e.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    if (MainScreenSpill.isUserTap(e.eventType) &&
                        // Mika 浮层自己的控件点击不是用户打开了别的 App;系统触摸事件没有这种包名歧义
                        (systemTouch || e.packageName?.toString() != a11y.packageName)
                    ) {
                        // 系统触摸事件带有真实发生时刻;通知栏、桌面等不发控件点击事件的入口也不能漏掉
                        val at = if (systemTouch) e.eventTime else SystemClock.uptimeMillis()
                        userTaps.addLast(MainScreenSpill.Tap(at, e.packageName?.toString()))
                        while (userTaps.size > MAX_USER_TAPS) userTaps.pollFirst()
                    }
                }
            }
            while (true) {
                val left = tapWatchUntil - SystemClock.uptimeMillis()
                if (left <= 0) break
                delay(left)
            }
            collecting.cancel()
        }
    }

    /**
     * 动作之后:问内置助手这次动作开始后主屏冒出来的任务,按 [MainScreenSpill] 判;开到主屏上的挪回 Mika 的屏,
     * 开在用户正用着的 App 里的只报不动。没有为 null。
     */
    private suspend fun takeSpill(w: Watch?): Spill? {
        if (w == null || handedOver()) return null
        val b = runCatching { shell.mainArrivals(maxOf(w.since, consumed + 1)) }.getOrNull() ?: return null
        val arrivals = parseArrivals(b)
        arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
        var last: Spill? = null
        for (v in MainScreenSpill.decide(arrivals, w.since, SPILL_WINDOW_MS, w.topBefore, notSpills, userTaps.toList(), homeApps)) {
            val a = v.arrival
            val pkg = a.packageName ?: continue
            // 开到用户主屏上的不在应用访问边界看的那块屏上:被禁用的不挪、不读
            appAccess.current().denial(pkg)?.let { throw it }
            last = when (v) {
                // 系统问打开哪一个的先按选择框处理;要用户亲手处理的弹窗(登录验证、账号确认一类)留在主屏,不挪到 Mika 的屏上
                is MainScreenSpill.PullBack -> leaveForUser(a, pkg, w) ?: leavePopup(a, pkg) ?: pullBack(a, pkg) ?: last
                is MainScreenSpill.InUse -> Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId)), null)
            }
        }
        if (last != null) Log.i(TAG, "opened on the main screen after an action: ${last.opened}")
        return last
    }

    /**
     * 上一次动作的盯梢还没过期时再问一次(那个动作过了一会儿才开出来的);过期了就清掉。
     * 用户在主屏上选完、处理完之后要说的,先说这个。
     */
    private suspend fun lateSpill(): Spill? {
        pendingReport?.let { pendingReport = null; return it }
        val w = watch ?: return null
        val spill = takeSpill(w)
        if (SystemClock.uptimeMillis() > w.since + SPILL_WINDOW_MS) watch = null
        return spill
    }

    /**
     * 冒到主屏上的是要用户亲手处理的弹窗(登录验证、账号确认、付款确认一类):不挪,留在用户屏上。
     * 判据:系统账号登录服务(登记了账号认证器、没有桌面图标的包)的页面,顶层窗口不是整屏(对话框、底部弹层),窗口里有密码框,或页面本身不进最近任务(excludeFromRecents)。
     * 这类窗口挪到 Mika 的屏上就从用户眼前消失,用户也就没法在主屏上点确认。不是为 null。
     * noHistory 不算:很多 App 的开屏页带着它,算进来就把普通 App 当成了弹窗。
     */
    private suspend fun leavePopup(a: MainScreenSpill.Arrival, pkg: String): Spill? =
        if (needsUserWindow(a, pkg)) popup(a, pkg) else null

    /** 主屏上这个任务的顶层窗口是不是一个要用户亲手处理的弹窗(见 [leavePopup])。最多多等两次 [POPUP_SETTLE_MS]。 */
    private suspend fun needsUserWindow(a: MainScreenSpill.Arrival, pkg: String): Boolean {
        if (isAuthenticatorUi(pkg)) return true
        val activity = a.topActivity?.let {
            runCatching { a11y.packageManager.getActivityInfo(ComponentName(pkg, it), 0) }.getOrNull()
        }
        if (activity != null && activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0) return true
        val metrics = a11y.resources.displayMetrics
        // 刚冒出来的窗口可能还在开场动画里(画得比整屏小),等它停稳再看一次,免得把普通 App 当成弹窗
        var window = topWindowOf(pkg)
        if (window == null) {
            delay(POPUP_SETTLE_MS)
            window = topWindowOf(pkg) ?: return false
        }
        if (isNarrow(window, metrics)) {
            delay(POPUP_SETTLE_MS)
            val settled = topWindowOf(pkg) ?: return false
            if (isNarrow(settled, metrics)) return true
            window = settled
        }
        return ScreenReader.read(listOf(window), metrics.widthPixels, metrics.heightPixels).passwordField
    }

    private fun topWindowOf(pkg: String): AccessibilityWindowInfo? =
        ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg }

    /**
     * 这个包是不是系统的账号登录服务:登记了账号认证器(AccountManager.authenticatorTypes,平台数据),自己又没有桌面图标
     * (没有 LAUNCHER 入口)。这种包没有自己的首页,冒到主屏上的只会是加账号、登录、验证、验证码一类页面,本身就是用户的事,
     * 整屏、没有密码框也不能挪到 Mika 的屏上。有桌面图标的认证器包(同步联系人的聊天 App、邮箱 App 等)不算,
     * 它们的会话、分享、写信页照常挪回。认证器表每次现读:新装的认证器也认得出,这一步只在有页面冒到主屏时才走。
     */
    private fun isAuthenticatorUi(pkg: String): Boolean {
        val authenticators = runCatching { AccountManager.get(a11y).authenticatorTypes.mapNotNull { it.packageName }.toSet() }
            .getOrDefault(emptySet())
        if (pkg !in authenticators) return false
        // 有桌面图标的包对 Mika 一定看得见(清单里查了 LAUNCHER);查不到才算没有
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val hasLauncher = runCatching { a11y.packageManager.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(true)
        return MainScreenSpill.authenticatorUi(pkg, authenticators, hasLauncher)
    }

    /** 窗口没有遮住整块屏:对话框、底部弹层、悬浮窗一类。 */
    private fun isNarrow(window: AccessibilityWindowInfo, metrics: android.util.DisplayMetrics): Boolean {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return bounds.width() < metrics.widthPixels * FULL_SCREEN_SHARE || bounds.height() < metrics.heightPixels * FULL_SCREEN_SHARE
    }

    private suspend fun popup(a: MainScreenSpill.Arrival, pkg: String): Spill =
        Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId), needsUser = true), null)

    /**
     * 开到主屏上的是系统问「用哪一个打开」的窗口(系统的选择器,或系统包的窗口里的选项对得上装了两份的 App;
     * 各系统的选择框不一定是原版那个):不挪,留给用户在自己屏上选,记下来让任务等着。不是为 null。
     */
    private suspend fun leaveForUser(a: MainScreenSpill.Arrival, pkg: String, w: Watch): Spill? {
        val system = isSystemPackage(pkg)
        val choice = if (system) mainChooser(pkg) else null
        if (MainScreenSpill.handling(system, pkg in systemPickers, choice != null) != MainScreenSpill.Handling.LeaveForUser) return null
        val app = choice?.options?.firstOrNull()?.label ?: appLabel(pkg)
        _userPick.value = UserPick(a.taskId, pkg, app, w.topBefore, w.agentTopBefore, a.at)
        return Spill(OpenedElsewhere(pkg, app, pulledBack = false, picker = true), choice)
    }

    /** 把开到主屏上的任务挪到 Mika 的屏(只挪,不替它重新打开),等它到最上面;挪不动为 null。 */
    private suspend fun pullBack(a: MainScreenSpill.Arrival, pkg: String, byUser: Boolean = false): Spill? {
        if (!runCatching { shell.moveTask(a.taskId, displayId) }.getOrDefault(false)) {
            Log.w(TAG, "could not move task ${a.taskId} ($pkg) from the main screen")
            return null
        }
        adopted += a.taskId
        // 事先不知道会开出哪个 App:挪回来后再占;占不到也不撤回
        if (checkOtherTasks) reserve(pkg)
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (runCatching { shell.topTaskId(displayId) }.getOrDefault(-1) != a.taskId && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val profile = if (isSystemPackage(pkg)) null else inProfileOf(pkg, a.userId)
        return Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = true, inProfile = profile, byUser = byUser), null)
    }

    /**
     * 主屏上这个系统包的窗口是不是在问打开哪一个:事先不知道是为哪个 App 问的,拿装了两份的 App 的名字去对窗口里的选项
     * (同一个 App 装了两份正是会这样问的情形);只读这个系统窗口,对不上为 null。
     */
    private suspend fun mainChooser(pkg: String): LaunchChoice? {
        val w = ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg } ?: return null
        val elements = ScreenReader.read(listOf(w), display.width, display.height).entries.map { it.element }
        for ((app, names) in otherProfiles()) {
            val label = appLabel(app) ?: continue
            LaunchChooser.options(label, elements, names)?.let { return LaunchChoice(pkg, it) }
        }
        return null
    }

    /**
     * 任务在下一个改动屏幕的动作前调:等用户在主屏上选完(选之前一直盯着主屏)。选完后,开到主屏上的普通页面挪回 Mika 的屏
     * (只挪,打开它的仍是原来那个 App);要用户亲手处理的(要输密码、窗口读不到,像付款、指纹)留在主屏,返回 [PickResult.NeedsUser];
     * 开在了 Mika 屏上的一并算进来;用户把问题关了就照实说。没在等时为 null。
     */
    suspend fun awaitUserPick(): PickResult? = withContext(Dispatchers.IO) {
        val p = _userPick.value ?: return@withContext null
        try {
            while (runCatching { shell.displayOfTask(p.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY) {
                runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }
                delay(PICK_POLL_MS)
            }
            val gone = SystemClock.uptimeMillis()
            runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }
            val deadline = gone + PICK_WAIT_MS
            while (true) {
                val arrivals = runCatching { shell.mainArrivals(p.since) }.getOrNull()?.let(::parseArrivals).orEmpty()
                val opened = MainScreenSpill.picked(arrivals, p.taskId, gone, PICK_SLACK_MS, PICK_WAIT_MS, p.topBefore, notSpills, homeApps)
                arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
                if (opened != null) {
                    val pkg = opened.packageName!!
                    if (needsUser(pkg) || needsUserWindow(opened, pkg)) {
                        return@withContext PickResult.NeedsUser(opened.taskId, pkg, appLabel(pkg) ?: p.app, opened.userId)
                    }
                    pendingReport = pullBack(opened, pkg)
                    return@withContext PickResult.Settled
                }
                // 选中的 App 开在了 Mika 的屏上(从那边打开的)
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                if (top >= 0 && top != p.agentTop && top !in adopted) {
                    adopted += top
                    waitStable(SETTLE_CAP_MS)
                    val pkg = runCatching { shell.packageOfTask(top) }.getOrNull() ?: p.packageName
                    val profile = inProfileOf(pkg, runCatching { shell.userOfTask(top) }.getOrDefault(-1))
                    pendingReport = Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = true, inProfile = profile), null)
                    return@withContext PickResult.Settled
                }
                if (SystemClock.uptimeMillis() >= deadline) break
                delay(POLL_MS)
            }
            pendingReport = Spill(OpenedElsewhere(p.packageName, p.app, pulledBack = false, picker = true, closed = true), null)
            PickResult.Settled
        } finally {
            _userPick.value = null
        }
    }

    /** 用户处理完([PickResult.NeedsUser])点了继续:它还在主屏上就挪回 Mika 的屏接着做,已经关了就照实说。 */
    suspend fun afterUserStep(r: PickResult.NeedsUser) = withContext(Dispatchers.IO) {
        val still = runCatching { shell.displayOfTask(r.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY
        val moved = if (still) pullBack(MainScreenSpill.Arrival(r.taskId, r.packageName, r.userId, 0, Display.DEFAULT_DISPLAY, false, true, Display.DEFAULT_DISPLAY), r.packageName, byUser = true) else null
        pendingReport = moved ?: Spill(OpenedElsewhere(r.packageName, r.app, pulledBack = false, byUser = true), null)
    }

    /**
     * 主屏上这个 App 的窗口要用户亲手处理:有密码框,或树里读不出东西而截主屏时系统报防截屏窗口(截到的画面不留,马上丢掉)。
     * 只看有没有,不读里面写了什么。
     */
    private suspend fun needsUser(pkg: String): Boolean {
        // 读的是用户主屏上的窗口,应用访问边界看不到:被禁用的不读
        appAccess.current().denial(pkg)?.let { throw it }
        // 刚上来的窗口要一会儿才读得到:读得到内容(或看到密码框)就下结论,到时限还读不到再看是不是防截屏
        val deadline = SystemClock.uptimeMillis() + PICK_READ_MS
        while (true) {
            val mine = ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).filter { it.root?.packageName?.toString() == pkg }
            val r = mine.takeIf { it.isNotEmpty() }?.let { ScreenReader.read(it, display.width, display.height) }
            val readable = r?.entries?.any { it.element.meaningful } == true
            if (r?.passwordField == true || readable) return MainScreenSpill.needsUser(r?.passwordField == true, readable, secureWindow = false)
            if (SystemClock.uptimeMillis() >= deadline) {
                val shot = runCatching { a11y.takeMainScreenshot() }.getOrNull()
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = shot is ScreenAccessibility.MainShot.Secure)
            }
            delay(POLL_MS * 2)
        }
    }

    /**
     * 动作之前 Mika 屏上最上面的是挪回来的任务(或从它里面打开的),动作之后最上面换成了另一个任务:
     * 这个也是那条路上打开的,一并算进来。装了两份的 App 报开的是哪一份。
     */
    private suspend fun adoptFromAdopted(w: Watch?): String? {
        if (w == null || w.agentTopBefore !in adopted) return null
        val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (top < 0 || top == w.agentTopBefore || top in adopted || top == _target.value?.taskId) return null
        adopted += top
        val pkg = runCatching { shell.packageOfTask(top) }.getOrNull() ?: return null
        return inProfileOf(pkg, runCatching { shell.userOfTask(top) }.getOrDefault(-1))
    }

    /** 装了两份的 App 开的是哪一份:和 Mika 同一个用户的是本来那份;只装了一份或看不出为 null。 */
    private suspend fun inProfileOf(pkg: String, userId: Int): String? {
        if (userId < 0 || pkg !in otherProfiles()) return null
        return if (userId == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE
    }

    private fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
        val tasks = b.getIntArray(MainScreenWatch.KEY_TASK) ?: return emptyList()
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val users = b.getIntArray(MainScreenWatch.KEY_USER)
        val at = b.getLongArray(MainScreenWatch.KEY_AT)
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)
        val activity = b.getStringArray(MainScreenWatch.KEY_ACTIVITY)
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)
        if (packages == null || users == null || at == null || from == null || same == null || standard == null || now == null) return emptyList()
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i], activity?.getOrNull(i))
        }
    }

    private fun hintLateSpill(o: OpenedElsewhere): String {
        val name = "${o.app ?: o.packageName} (${o.packageName})"
        val copy = when (o.inProfile) {
            OpenedElsewhere.OTHER_PROFILE -> " It is the other copy of the app (clone or other profile): if the user did not ask for that copy, go back and pick the other option."
            OpenedElsewhere.MAIN_PROFILE -> " It is the main copy of the app."
            else -> ""
        }
        return when {
            o.closed -> "The user closed the system's question on their screen without picking, so nothing was opened. Decide what to do next; do not simply repeat the action."
            o.byUser && !o.pulledBack -> "$name needed the user; they handled it on their own screen and tapped continue, and it has closed. Observe before acting."
            o.byUser -> "$name needed the user; they handled it on their own screen and tapped continue. It is now in front on Mika's screen. Observe before acting."
            o.needsUser && o.onUserScreen -> "It was $name: a popup on the user's screen that needs them (a sign-in, verification or confirmation step). " +
                "It was left there on purpose; do not repeat the action. Call hand_to_user so they can finish it, then observe before acting."
            o.pulledBack -> "It was $name, now in front on Mika's screen.$copy Observe before acting."
            else -> "It was $name, which the user is using on their own screen, so it was left there. " +
                "Do not repeat the previous action: call hand_to_user with reason other so the user can do that step there, or wait and try again later."
        }
    }

    /** 等界面稳定后比对指纹(规则见 [ChangeWait]);确认变了立即返回 true。 */
    private suspend fun awaitChange(
        clock: EventClock,
        beforeTree: Long,
        beforeFrame: IntArray?,
        fallback: (suspend () -> Boolean)? = null,
    ): Boolean =
        ChangeWait.awaitStableChange(
            now = SystemClock::uptimeMillis,
            sleep = { delay(it) },
            frame = ::frameSignature,
            quietForMs = clock::quietForMs,
            treeChanged = { treeHash() != beforeTree },
            beforeFrame = beforeFrame,
            fallback = fallback,
        )

    /** 前台不是目标 App、也不是它调起的系统页面或系统选择器时,返回当前前台包名及是否由目标 App 打开。 */
    private fun switchedTo(): AppSwitch.Switch? {
        val target = _target.value
        return AppSwitch.switchedTo(
            targetPackage,
            ScreenReader.topPackage(windows()),
            // 从用户主屏挪回来的(及从它们里面打开的)也是目标 App 这一路打开的
            sameTask = {
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                top >= 0 && (target != null && target.taskId == top || top in adopted)
            },
            partOfTask = chooserPackage?.let { systemPickers + it } ?: systemPickers,
            settingsPage = { it == settingsPackage },
            // 同一个 App 装了两份:最上面换成了另一份用户里的那份(不是目标任务、也不是挪回来的)也算被切走
            // 只在目标是另一份、或这个 App 列出过另一份时才问任务(只装一份的 App 每个动作不多问两次)
            otherCopy = {
                target != null && target.userId >= 0 && (target.userId != myUser || target.packageName in copiedPackages) && run {
                    val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                    top >= 0 && top != target.taskId && top !in adopted && userOf(top).let { it >= 0 && it != target.userId }
                }
            },
        )
    }

    /**
     * 打开时目标 App 还没到前台(系统先问打开哪一个、启动慢),之后它到了最上面:记下它的任务编号,
     * 任务服务才能据此静音、盯着它有没有被挪走。
     */
    private suspend fun adoptTargetTask(): String? {
        val t = _target.value ?: return null
        if (t.taskId >= 0 || foregroundTaskPackage() != t.packageName) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        val user = userOf(task)
        _target.value = TargetApp(t.packageName, task, user)
        // 系统问过打开哪一个(装了两份):报开的是哪一份
        return inProfileOf(t.packageName, user)
    }

    /** 带上「是否被切走」的动作结果;[spill] 是这个动作开到用户主屏上的 App,[inProfile] 见 [ActionResult.Ok.inProfile]。 */
    private suspend fun ok(
        changed: Boolean? = null, relocatedTo: String? = null, verification: Verification? = null,
        hint: String? = null, secret: Boolean = false, spill: Spill? = null, inProfile: String? = null,
    ): ActionResult.Ok {
        val adopted = adoptTargetTask()
        val shown = shownCopy()
        val profile = adopted ?: inProfile ?: shown?.first
        val sw = switchedTo()
        return ActionResult.Ok(
            changed = changed, appSwitched = sw?.packageName, switchedByTarget = sw?.byTarget == true,
            relocated = relocatedTo != null, relocatedTo = relocatedTo, verification = verification, hint = hint, secret = secret,
            chooser = spill?.chooser, opened = spill?.opened, inProfile = profile,
            profileUser = shown?.second, requestedUser = requestedUser.takeIf { shown != null },
        )
    }

    /** 打开 App 时要的那份用户(另一份);要的是本来那份、或这次不是打开 App 为 null。 */
    @Volatile private var requestedUser: Int? = null

    /**
     * 目标 App 装了两份(列出过另一份、要的就是另一份,或打开的就是另一份)时,此刻 Mika 屏上是哪一份:(inProfile, 所属用户)。
     * 只装一份、最上面不是它或读不到为 null;只装一份的 App 不多问。
     */
    private fun shownCopy(): Pair<String, Int>? {
        val t = _target.value ?: return null
        if (requestedUser == null && t.packageName !in copiedPackages && (t.userId < 0 || t.userId == myUser)) return null
        if (foregroundTaskPackage() != t.packageName) return null
        val user = userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).takeIf { it >= 0 } ?: return null
        return (if (user == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE) to user
    }

    // ---------- 编号解析 ----------

    /**
     * 把编号解析成可操作的节点;经过重定位时第二个值是找回的元素现在的名字,否则为 null。
     * 最新快照里的节点还在且没变就直接用;快照过期或节点变了,按原元素特征在当前界面里找唯一对应者。
     */
    private fun resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
        val (entry, snapshot) = when (val l = ids.lookup(ref, snapshotId)) {
            is NodeIds.Lookup.Unknown -> return null
            is NodeIds.Lookup.Current -> {
                if (unchanged(l.entry)) return l.entry.node to null
                l.entry to l.snapshot
            }
            is NodeIds.Lookup.Old -> l.entry to l.snapshot
        }
        val now = read()
        return when (val r = relocate(entry.locator, snapshot.locators, now.entries.map { it.locator }, min(screenWidth, screenHeight))) {
            is Relocation.Found -> now.entries[r.index].let { it.node to it.element.name }
            else -> null
        }
    }

    /** 节点还在,且类型、名字、资源 ID、位置都和快照时一样(列表复用后同一个节点可能换了内容)。 */
    private fun unchanged(entry: NodeEntry): Boolean {
        val n = entry.node
        if (!n.refresh()) return false
        val now = ScreenReader.locatorOf(n, screenWidth, screenHeight) ?: return false
        return now == entry.locator.copy(context = null)
    }

    private sealed interface Field {
        /** [tapped]:给的元素本身不是输入框,点了它之后才出现(或聚焦了)真正的输入框。 */
        class Found(val node: AccessibilityNodeInfo, val tapped: Boolean = false, val relocatedTo: String? = null) : Field
        class Missing(val reason: String) : Field
    }

    /**
     * 找要输入的框。给了编号:元素本身或它里面的输入框;都不是、而它看上去是输入入口(首页那条「假搜索框」:
     * 可点、不是按钮、带提示语或名字像搜索)时先点它,点成功且出现了一个点之前没有的、拿着焦点的输入框,才用那个框。
     * 没给编号:当前拿着输入焦点的框。
     */
    private suspend fun findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
        if (ref != null && snapshotId != null) {
            val (node, relocatedTo) = resolve(ref, snapshotId) ?: return Field.Missing("ref_expired")
            val editable = if (ScreenReader.canSetText(node)) node
                else descendant(node) { ScreenReader.canSetText(it) && visibleInputTarget(it) }
            if (editable != null) {
                if (!editable.refresh() || !visibleInputTarget(editable)) return Field.Missing(EDITOR_NOT_VISIBLE)
                return Field.Found(editable, relocatedTo = relocatedTo)
            }
            if (!tapToFocus || !looksLikeInputEntry(node)) return Field.Missing("not_editable")
            val focusedBefore = focusedEditable()
            val clicked = withEventClock { clock ->
                clock.mark()
                click(node).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
            }
            val now = focusedField()
            return when {
                clicked && now is Field.Found && now.node != focusedBefore ->
                    Field.Found(now.node, tapped = true, relocatedTo = relocatedTo)
                clicked && now is Field.Missing && now.reason == EDITOR_NOT_VISIBLE -> now
                else -> Field.Missing("not_editable")
            }
        }
        return focusedField()
    }

    /** 认「同一个输入框」用的特征:类型、资源 ID、位置大小。 */
    private data class FieldShape(
        val pkg: String?, val windowId: Int, val cls: String?, val rid: String?, val rect: Rect,
    ) {
        companion object {
            fun of(n: AccessibilityNodeInfo) =
                FieldShape(n.packageName?.toString(), n.windowId, n.className?.toString(), n.viewIdResourceName,
                    Rect().also(n::getBoundsInScreen))
        }
    }

    private fun sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
        val b = FieldShape.of(n)
        if (a.pkg != b.pkg || a.windowId != b.windowId || a.cls != b.cls || a.rid != b.rid) return false
        // 大小要几乎一样;位置允许整页挪动一点(输入时页面常会上移),和编号找回用同一个上限
        val short = min(screenWidth, screenHeight)
        val sizeTol = short * SAME_FIELD_SIZE
        val moveTol = short * RELOCATE_MAX_SHIFT
        return kotlin.math.abs(a.rect.width() - b.rect.width()) <= sizeTol && kotlin.math.abs(a.rect.height() - b.rect.height()) <= sizeTol &&
            kotlin.math.hypot((a.rect.centerX() - b.rect.centerX()).toDouble(), (a.rect.centerY() - b.rect.centerY()).toDouble()) <= moveTol
    }

    /** 像输入入口:可点,不是按钮类,且带提示语或名字像搜索。 */
    private fun looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(n) { it.isClickable } ?: return false
        val cls = target.className?.toString().orEmpty()
        if (cls.endsWith("Button")) return false
        if (ScreenReader.placeholderOf(n) != null || !n.hintText.isNullOrBlank()) return true
        val name = ScreenReader.ownLabel(n) ?: ScreenReader.ownLabel(target) ?: return false
        return SEARCH_WORDS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * 这块屏上有东西拿着输入焦点(哪怕不是树里的输入框,比如 App 自己画的界面整块拿着焦点),或弹着输入法。
     * 都没有时按键没有框可去:树里看不到的输入框也只有拿着焦点才收得到粘贴。
     */
    private fun somethingHoldsInputFocus(): Boolean =
        imeBounds() != null || focusedInput() != null

    private fun focusedInput(): AccessibilityNodeInfo? {
        val content = ScreenReader.contentWindows(windows())
        val focusedWindow = content.firstOrNull { it.isFocused }
        if (focusedWindow != null) return focusedWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return content.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    }

    private fun visibleInputTarget(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && ScreenReader.locatorOf(node, screenWidth, screenHeight) != null

    /** Keep a known hidden native editor distinct from a custom editor absent from the tree. */
    private fun focusedField(): Field {
        val focus = focusedInput() ?: return Field.Missing(NO_FOCUS)
        val editor = if (ScreenReader.canSetText(focus)) focus
            else descendant(focus) { it.isFocused && ScreenReader.canSetText(it) } ?: return Field.Missing(NO_FOCUS)
        return if (editor.refresh() && visibleInputTarget(editor)) Field.Found(editor) else Field.Missing(EDITOR_NOT_VISIBLE)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? = (focusedField() as? Field.Found)?.node

    private fun blockedFocusedEditor(): Boolean = (focusedField() as? Field.Missing)?.reason == EDITOR_NOT_VISIBLE

    // ---------- 底层操作 ----------

    private fun isClickableTarget(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

    /** 坐标点击无反应时,反查该点处最小的可点控件改走无障碍点击;命中对外/不可逆按钮或大容器时不补点。 */
    private fun clickNodeAt(x: Int, y: Int, clock: EventClock): Boolean {
        if (!hasDisplay || handedOver() || imeBounds()?.contains(x, y) == true) return false
        val window = ScreenReader.contentWindows(windows()).firstOrNull {
            Rect().also(it::getBoundsInScreen).contains(x, y)
        }?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        val entries = ScreenReader.read(listOf(window), screenWidth, screenHeight).entries
        val rect = Rect()
        val hitEntries = entries.filter { entry ->
            entry.node.getBoundsInScreen(rect)
            rect.intersect(0, 0, screenWidth, screenHeight) && rect.contains(x, y)
        }
        if (hitEntries.isEmpty()) return false
        if (hitEntries.any { ClickFallback.mayBeOutward(it.element.name, it.locator.resourceId, clickable = it.element.clickable) }) return false
        val seen = HashSet<AccessibilityNodeInfo>()
        val candidates = mutableListOf<ClickFallback.Candidate<AccessibilityNodeInfo>>()
        for (entry in hitEntries) {
            val target = ancestorOrSelf(entry.node) { isClickableTarget(it) } ?: continue
            if (!seen.add(target) || !target.isVisibleToUser) continue
            target.getBoundsInScreen(rect)
            if (!rect.intersect(0, 0, screenWidth, screenHeight) || rect.isEmpty) continue
            candidates += ClickFallback.Candidate(
                item = target,
                left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                label = ScreenReader.labelOf(target).ifBlank { entry.element.name.ifBlank { null } },
                resourceId = target.viewIdResourceName ?: entry.locator.resourceId,
                scrollable = target.isScrollable,
                webView = target.className == WEB_VIEW,
            )
        }
        val picked = ClickFallback.pickTarget(x, y, screenWidth, screenHeight, candidates) ?: return false
        clock.mark()
        return picked.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 优先对节点(或最近的可点祖先)发点击;不行再在其中心做手势,最后注入触摸。 */
    private suspend fun click(node: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(node) { isClickableTarget(it) }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

    /** 在这一点上点一下:先停一会儿再落指,按住随机的一小会儿。无障碍手势不行时由内置助手注入触摸。 */
    private suspend fun touch(at: Pair<Int, Int>): Boolean {
        val (x, y) = at
        delay(pacing.approach())
        val press = pacing.press()
        if (a11y.gesture(displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, press)) return true
        if (pacing.paced && injectStroke(listOf(GesturePacing.Point(x.toDouble(), y.toDouble(), 0), GesturePacing.Point(x.toDouble(), y.toDouble(), press)))) return true
        return shell.injectTap(displayId, x, y)
    }

    /**
     * 一根手指沿 [points] 滑动。有节奏时按 [GesturePacing.stroke] 排好每个点的时刻,由内置助手逐点注入(App 看到先快后慢的真实速度);
     * 助手注不了时退回无障碍手势沿同一条弧线走(系统按路径匀速走,只少了快慢)。不加节奏时和以前一样:无障碍手势按给定时长匀速走折线。
     */
    private suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
        if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
        delay(pacing.approach())
        val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, screenWidth to screenHeight)
        if (injectStroke(stroke.points)) return true
        return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
    }

    private fun injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
        shell.injectStroke(
            displayId,
            IntArray(points.size) { points[it].x.roundToInt() },
            IntArray(points.size) { points[it].y.roundToInt() },
            LongArray(points.size) { points[it].t },
        )
    }.getOrDefault(false)

    private fun pathOf(points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(points.first().first.toFloat(), points.first().second.toFloat())
        points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
    }

    /**
     * 网页里的编辑区(contenteditable)只认真实输入:SET_TEXT 只改了网页上的字,编辑器收不到 input 事件,
     * App 会当作没输入(「完成」不亮、退出不保存)。这类输入框改用选中原内容再粘贴或按删除键。
     */
    private fun inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

    /**
     * 选中原内容后粘贴(或按删除键清空)。返回 null 表示剪贴板不能安全使用,没有改动任何文字;
     * 其余结果用 [AsciiTextKeys] 三态表示。选择失败时不发删除或粘贴,避免只删一个字符或在光标处插入。
     */
    private fun inputText(node: AccessibilityNodeInfo, text: String): Int? {
        if (!node.refresh() || !visibleInputTarget(node)) return AsciiTextKeys.NOT_SENT
        val before = node.text?.toString().orEmpty()
        fun selectAll(): Boolean {
            if (!node.refresh() || !visibleInputTarget(node) || node.text?.toString().orEmpty() != before) return false
            if (before.isEmpty()) return true
            val range = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
        }
        if (text.isEmpty()) {
            if (before.isEmpty()) return AsciiTextKeys.DISPATCHED
            if (!selectAll()) return AsciiTextKeys.NOT_SENT
            if (!node.refresh() || !visibleInputTarget(node)) return AsciiTextKeys.NOT_SENT
            return if (shell.injectKey(displayId, KeyEvent.KEYCODE_DEL)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
        }
        return withClipboardText(text) {
            if (!selectAll()) return@withClipboardText AsciiTextKeys.NOT_SENT
            if (!node.refresh() || !visibleInputTarget(node)) return@withClipboardText AsciiTextKeys.NOT_SENT
            // 标准动作返回 false 时也可能已经产生效果;按不确定处理,不补发快捷键。
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
            // App 是异步去读剪贴板的:等字变了再还原,最多等一会儿
            val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (node.refresh() && node.text?.toString().orEmpty() != before) break
                Thread.sleep(POLL_MS)
            }
            AsciiTextKeys.DISPATCHED
        }
    }

    /** 持有全局剪贴板锁同步执行;设置后不论结果都尽量留底,只有确认恢复完成才清除持有标记和加密留底。 */
    private fun withClipboardText(text: String, action: () -> Int): Int? {
        clipLock.lock()
        try {
            clipHeld(true)
            val set = runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED)
            runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
            if (set != ShellService.CLIP_SET) return null
            return action()
        } finally {
            val restored = runCatching { shell.restoreClip() }.getOrDefault(false)
            try {
                if (restored) clipHeld(false)
            } finally {
                clipLock.unlock()
            }
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh() || !visibleInputTarget(node)) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private inline fun ancestorOrSelf(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= MAX_ANCESTORS) {
            if (test(n)) return n
            n = n.parent
            depth++
        }
        return null
    }

    private fun descendant(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val n = queue.removeFirst()
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    // ---------- 画面 ----------

    /** 预览帧的粗粒度亮度格子;没有画面时为 null。 */
    private fun frameSignature(): IntArray? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.lumaGrid(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    /** 画面是否全黑;安全窗口和虚拟屏合成失败都可能产生这种帧,不能单靠它判原因。 */
    private fun frameBlack(): Boolean? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.isBlack(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    // ---------- 事件时钟 ----------

    /** 记录这块屏上最近一次界面事件的时间。 */
    private class EventClock {
        private val last = AtomicLong(SystemClock.uptimeMillis())
        fun mark() = last.set(SystemClock.uptimeMillis())
        fun quietForMs() = SystemClock.uptimeMillis() - last.get()

        /** 等到连续 [quietMs] 没有界面事件;[capMs] 内等到返回 true。 */
        suspend fun awaitQuiet(quietMs: Long, capMs: Long): Boolean {
            val deadline = SystemClock.uptimeMillis() + capMs.coerceAtLeast(0)
            while (true) {
                val now = SystemClock.uptimeMillis()
                val quietFor = now - last.get()
                if (quietFor >= quietMs) return true
                if (now >= deadline) return false
                delay(min(quietMs - quietFor, deadline - now).coerceIn(10, 100))
            }
        }
    }

    /** 在订阅该屏无障碍事件期间执行 [block];订阅先于 block 建立,不会漏掉动作引起的第一批事件。 */
    private suspend fun <T> withEventClock(block: suspend (EventClock) -> T): T = coroutineScope {
        val clock = EventClock()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            a11y.eventsFor(displayId).collect { if (it.eventType and IGNORED_EVENTS == 0) clock.mark() }
        }
        try {
            block(clock)
        } finally {
            job.cancel()
        }
    }

    private companion object {
        const val TAG = "MikaScreen"
        /** 动作开始后多久之内主屏冒出来的任务算这个动作开出来的。 */
        const val SPILL_WINDOW_MS = 6_000L
        /**
         * 窗口的宽、高都至少有主屏的这么多才算整屏的页面;比这小的是对话框、底部弹层一类。
         * 留得宽一些:状态栏、导航栏、挖孔会让整屏 App 的窗口也比屏小一截,横屏时导航栏还在侧边。
         */
        const val FULL_SCREEN_SHARE = 0.8f
        /** 刚冒出来的窗口可能还在开场动画里:等这么久再看一次,确认它是不是一直不是整屏。 */
        const val POPUP_SETTLE_MS = 300L
        const val MAX_USER_TAPS = 32
        /** 用户在主屏的选择框里选了之后,等选中的 App 上来最多多久;等着用户选时多久看一次。 */
        const val PICK_WAIT_MS = 3_000L
        const val PICK_POLL_MS = 300L
        /** 选择框关掉之前多久冒出来的也算选出来的(看选择框关没关是隔一会儿看一次的)。 */
        const val PICK_SLACK_MS = 1_000L
        /** 选出来的 App 上来后,至多等这么久读它的窗口,看要不要用户亲手处理。 */
        const val PICK_READ_MS = 2_000L
        val NO_APP_OPEN = ActionResult.Failed("no_app_open")
        const val NO_FOCUS = "no_focus"
        const val EDITOR_NOT_VISIBLE = "editor_not_visible"
        const val PER_USER_RANGE = 100_000
        /** Mika 所在的用户:本来那份 App 在这里。 */
        val myUser: Int get() = AppCopyReader.myUser
        const val COPY_NOT_FOUND = "copy_not_found"
        const val COPY_NOT_OPENED = "copy_not_opened"
        const val PASTE_UNAVAILABLE = "paste_unavailable"
        const val INPUT_NOT_SENT = "input_not_sent"
        const val HINT_PASTE_BLOCKED = "The clipboard could not be used safely right now. " +
            "Call hand_to_user so the user can type in this field."
        val IN_USE_BY_OTHER = ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK)

        const val QUIET_MS = 500L

        /** 停留里轻轻滚的那块可滚动内容至少占屏幕这么大。 */
        const val IDLE_MIN_AREA = 0.5

        /** 停留里轻轻滚一下至少这么多像素(再少看不出)。 */
        const val IDLE_MIN_PX = 8

        /** 停留里轻轻滚一下的时长:够慢,当慢拖,停稳再抬手。 */
        const val IDLE_SCROLL_MS = 900L
        const val CHECK_CAP_MS = 1_500L
        const val SECOND_LOOK_QUIET_MS = 300L
        const val SECOND_LOOK_CAP_MS = 800L
        const val SETTLE_CAP_MS = 2_000L
        const val READBACK_QUIET_MS = 200L
        const val READBACK_CAP_MS = 800L
        const val LAUNCH_WAIT_MS = 5_000L
        const val IN_USE_RETRY_MS = 3_000L
        const val IN_USE_POLL_MS = 500L
        const val POLL_MS = 100L
        /** 设闹钟后等系统的下一个闹钟变,最多这么久、这么久看一次。 */
        const val ALARM_WAIT_MS = 3_000L
        const val ALARM_POLL_MS = 150L
        const val SYSTEM_ACTION_REQUEST = 0x5A
        /** 拖滚轮的时长:不短于节奏里的慢拖时长,松手时已经停住。 */
        const val WHEEL_DRAG_MS = 1_000L
        /** 慢拖拖不动时快划一下的时长(像人手划滚轮)。 */
        const val WHEEL_SWIPE_MS = 400L
        const val TYPE_ATTEMPTS = 2
        const val WEB_VIEW = "android.webkit.WebView"
        const val PASTE_WAIT_MS = 1_500L
        const val PASTE_MENU_MIN_HOLD_MS = 800L
        const val PASTE_MENU_WAIT_MS = 3_000L
        const val HINT_PASTE_UNAVAILABLE = "This field only receives real input (it needs a paste), but the clipboard could not be used safely, " +
            "so the text may only be displayed and the app may not have received it. Check that the app reacted (e.g. its save button became enabled) " +
            "before relying on it; if it did not, call hand_to_user so the user can type it."
        const val HINT_TAPPED_TO_FOCUS = "The element you gave is not a text field itself, so it was tapped first and the text went into the field that got focus after the tap (often a separate search page). Check which field holds your text before submitting."
        const val HINT_FOCUS_UNCONFIRMED = "The field did not confirm input focus after both focus and tap, so the text was written directly. Trust the readback verification, not the focus state."
        const val FOCUS_WAIT_MS = 2_000L
        const val SAME_FIELD_SIZE = 0.03
        val SEARCH_WORDS = listOf("搜索", "搜一搜", "查找", "search")
        const val HINT_READBACK_FROZEN = "The field keeps reporting the same text, so what was typed could not be read back; " +
            "but new text containing it appeared on the screen after typing (e.g. suggestions), so the app seems to have received it. Observe to check before submitting."
        const val HINT_SHARE_PICKER = "The system is asking which of the app's share screens to use (the screen lists them). " +
            "Tap the one the task needs (for a message, the one that sends to a contact or chat); do not share again."
        const val HINT_COLD_IGNORED = "coldIgnored: app has a task outside Mika's screen, so it was not restarted (that would wipe the user's state there). It was opened as-is; navigate back to its start screen yourself if needed."
        const val MAX_ANCESTORS = 8

        // 悬停、触摸探索、手势识别、无障碍焦点这些不是界面内容的变化
        const val IGNORED_EVENTS = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
    }
}

/** 桌面上能打开的 App(包名, 显示名),不含 Mika 自己;只用包管理器,不要内置助手和无障碍。 */
fun launchableApps(pm: android.content.pm.PackageManager, ownPackage: String): List<Pair<String, String>> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ownPackage }
        .distinctBy { it.first }
        .sortedBy { it.second }
}
