package com.wangzan101.mika.cap.phone.a11y

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.uistate.OpenedElsewhere
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenEvent
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.visual.FrameGrid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 读屏 / 截图 / 界面事件:把 Agent 虚拟屏读成快照与文字,截帧,监视界面事件。
 * 共享 [AndroidScreen] 的字段与方法经 [screen] 后向引用访问。
 */
internal class ScreenCapture(private val screen: AndroidScreen) {

    internal fun windows() = screen.a11y.windowsOn(screen.displayId)
    /**
     * 应用访问的窗口探针:屏上各 App 窗口的归属,只读根节点的包名、不读内容。读不到根节点的那一项为 null(归属未知);
     * 没有 App 窗口时是最上面任务的 App,也没有就是空屏(空列表)。规则和读屏结果里的窗口归属一致。
     */
    suspend fun accessPackages(): List<String?> = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext emptyList()
        val packages = windows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { it.root?.packageName?.toString() }
        packages.ifEmpty { listOfNotNull(foregroundTaskPackage()) }
    }

    internal fun read() = ScreenReader.read(windows(), screen.screenWidth, screen.screenHeight)
    internal fun treeHash() = ScreenReader.hash(windows())

    /** 显式使用系统命令或事件工具时按需建屏;普通任务等到第一次打开 App 才建。 */
    suspend fun ensureDisplay() { screen.display.get() }

    // ---------- 读屏 ----------

    suspend fun snapshot(): ScreenSnapshot = capture(register = true)

    // 不进编号登记表:模型手里最新的 ref 仍然有效
    suspend fun peek(): ScreenSnapshot = capture(register = false)

    /** Device-local SMS verification needs the exact IDs, including IDs on labelled buttons. */
    internal suspend fun smsSnapshot(register: Boolean): ScreenSnapshot =
        capture(register, fullResourceIds = true)

    internal suspend fun capture(register: Boolean, fullResourceIds: Boolean = false): ScreenSnapshot = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext ScreenSnapshot(snapshotId(), null, emptyList(), screen.screenWidth, screen.screenHeight, hasWindow = false)
        // 上一个动作过了一会儿才开到用户主屏上的:先挪回来再读(只在模型要读的这一屏上做,不在判效果的那一眼里做)
        val late = if (register) screen.spill.lateSpill() else null
        val r = read()
        val after = treeHash()
        val foreground = if (r.topPackage == null) foregroundTaskPackage() else null
        val snap = NodeSnapshot(snapshotId(), r.entries)
        if (register) screen.ids.put(snap)
        ScreenSnapshot(
            snapshotId = snap.id,
            packageName = r.topPackage,
            elements = r.entries.map { entry ->
                if (fullResourceIds) entry.element.copy(resourceId = entry.locator.resourceId) else entry.element
            },
            width = screen.screenWidth,
            height = screen.screenHeight,
            changedDuringRead = after != r.hash,
            hasWindow = r.anyAppWindow,
            unreadableApp = foreground,
            windows = r.windows,
            // 还在等用户在主屏上选:照样说一声
            opened = late?.opened ?: screen.spill._userPick.value?.let { OpenedElsewhere(it.packageName, it.app, pulledBack = false, picker = true) },
            // 黑帧只说明画面不可用,不能据此声称窗口防截屏;读屏元素仍可作为降级路径。
            captureError = if ((r.anyAppWindow || foreground != null) && frameServable() && screen.frameBlack() == true)
                ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE else null,
        )
    }

    /**
     * 窗口内容读不到时(安全窗口、启动画面、显示在别处),按 Agent 屏上最上面的任务认前台是哪个 App;
     * 屏还没建、没有任务或问不到为 null。
     */
    internal fun foregroundTaskPackage(): String? {
        if (!screen.hasDisplay) return null
        val task = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (task < 0) return null
        return runCatching { screen.shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Mika 屏上最上面的那个 App(包名、任务、所属用户),每换一个给一次:订阅时先给一次,之后窗口一变(开了新页面、
     * 从别的 App 里点开了另一个 App、另一份顶上来)就重读。屏还没建、没有任务时不给。在调用方的线程上问内置助手。
     */
    fun foregroundApps(): Flow<AndroidScreen.TargetApp> = flow {
        emit(Unit)
        emitAll(screen.a11y.windowChanges())
    }.conflate().mapNotNull {
        if (!screen.hasDisplay) return@mapNotNull null
        val task = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (task < 0) return@mapNotNull null
        val pkg = runCatching { screen.shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AndroidScreen.TargetApp(pkg, task, screen.userOf(task))
    }.distinctUntilChanged { a, b -> a.packageName == b.packageName && a.userId == b.userId }

    suspend fun screenshotPng(): ByteArray? = withContext(Dispatchers.IO) {
        // 黑图没有可用画面信息,也不能凭它区分安全窗口和合成失败;不要交给模型或 OCR。
        if (!frameServable() || screen.frameBlack() == true) return@withContext null
        screen.display.current?.latestFramePng()
    }
    /** 此刻能不能把 Agent 屏的最新帧交给模型:启动后还没收到新帧时不给,直到新帧到达才解除。 */
    internal fun frameServable(): Boolean = when (FrameFreshness.serve({ screen.display.current?.lastFrameAt() }, screen.frameGuardSince)) {
        FrameFreshness.Serve.Guarded -> false
        FrameFreshness.Serve.Fresh -> {
            screen.frameGuardSince = null
            true
        }
    }

    /** 等这次画面变化之后的首帧;等不到也继续保持保护,不能把旧帧放行。 */
    internal suspend fun awaitFrameAfter(startedAt: Long) {
        screen.frameGuardSince = startedAt
        if (FrameFreshness.awaitNewFrame(
                { SystemClock.uptimeMillis() },
                ::delay,
                { screen.display.current?.lastFrameAt() },
                startedAt,
            )
        ) screen.frameGuardSince = null
    }

    /**
     * 认屏上文字。自绘界面几乎没有无障碍事件,事件安静了画面可能还在过渡:先等画面停稳再认;认的过程中换了整页就按新的一页再认
     * (规则见 [StillFrameRead])。局部在动(动图头像、光标)不影响字的位置,照用,不再整个丢掉。
     */
    suspend fun readVisibleText(): List<VisibleTextLine>? = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay || !frameServable() || screen.frameBlack() == true) return@withContext null
        StillFrameRead.read(SystemClock::uptimeMillis, { delay(it) }, screen::frameSignature) {
            screen.display.current?.latestFrameBitmap()?.let { PhoneOcr.read(it) }
        }
    }

    suspend fun frameGrid(): FrameGrid? = withContext(Dispatchers.IO) {
        if (!frameServable()) return@withContext null
        val bmp = screen.display.current?.frames?.value ?: return@withContext null
        runCatching { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    suspend fun checkScreen(): ScreenCheck = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        // 先看界面会不会安静下来:一直有变化就是还在加载
        val quiet = screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.QUIET_MS, AndroidScreen.CHECK_CAP_MS) }
        // 安静时看两次;持续全黑只报画面不可用,不把黑像素当成防截屏的证据
        val black = mutableListOf<Boolean>()
        screen.frameBlack()?.let(black::add)
        if (quiet) {
            screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.SECOND_LOOK_QUIET_MS, AndroidScreen.SECOND_LOOK_CAP_MS) }
            screen.frameBlack()?.let(black::add)
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

    suspend fun waitStable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext true
        screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.QUIET_MS, timeoutMs) }
    }

    /** 这块虚拟屏的下一条界面事件(悬停一类不算);屏还没开时等满时限。 */
    suspend fun awaitChange(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) { delay(timeoutMs.coerceAtLeast(0)); return@withContext false }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { screen.a11y.eventsFor(screen.displayId).first { it.eventType and AndroidScreen.IGNORED_EVENTS == 0 } } != null
    }

    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext false
        if (condition == "stable") return@withContext waitStable(timeoutMs)
        if (condition !in setOf("appear", "disappear", "activity") || value.isNullOrBlank()) return@withContext false
        coroutineScope {
            val changed = Channel<Boolean>(Channel.CONFLATED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                screen.a11y.eventsFor(screen.displayId).collect { event ->
                    if (condition == "activity" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.className?.toString()?.contains(value, true) == true) changed.trySend(true)
                    else if (condition != "activity") changed.trySend(false)
                }
            }
            try {
                fun matched(): Boolean = when (condition) {
                    "activity" -> runCatching { screen.shell.topActivity(screen.displayId)?.contains(value, true) == true }.getOrDefault(false)
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

    internal fun containsText(node: com.wangzan101.mika.core.uistate.UiTreeNode, text: String): Boolean =
        (!node.secret && (node.text?.contains(text, true) == true || node.description?.contains(text, true) == true)) ||
            node.children.any { containsText(it, text) }

    suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = withContext(Dispatchers.IO) {
        if (kind == "notification") return@withContext withTimeoutOrNull(timeoutMs) {
            screen.a11y.notificationEvents().first { packageName == null || it.packageName == packageName }
        }
        if (!screen.hasDisplay) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            val event = screen.a11y.eventsFor(screen.displayId).first { e ->
                (packageName == null || e.packageName?.toString() == packageName) && when (kind) {
                    "window" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(), event.text.joinToString(" ").take(2_000))
        }
    }

    suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = withContext(Dispatchers.IO) {
        if (kind != "notification" && !screen.hasDisplay) return@withContext emptyList()
        val collected = mutableListOf<ScreenEvent>()
        val stream = if (kind == "notification") screen.a11y.notificationEvents().filter { packageName == null || it.packageName == packageName }
        else screen.a11y.eventsFor(screen.displayId)
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
}
