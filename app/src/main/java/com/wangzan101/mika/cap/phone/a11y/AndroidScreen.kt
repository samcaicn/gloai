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
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.media.ShareableFile
import com.wangzan101.mika.core.screen.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.AppCopy
import com.wangzan101.mika.core.screen.uistate.AppProfiles
import com.wangzan101.mika.core.screen.uistate.LaunchChoice
import com.wangzan101.mika.core.screen.uistate.LaunchChooser
import com.wangzan101.mika.core.screen.uistate.OpenedElsewhere
import com.wangzan101.mika.core.screen.uistate.UnseenField
import com.wangzan101.mika.core.screen.uistate.normalize
import com.wangzan101.mika.core.screen.uistate.Relocation
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.SystemCommandResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import com.wangzan101.mika.core.screen.uistate.WheelOutcome
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import com.wangzan101.mika.core.screen.visual.FrameGrid
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.screen.uistate.TextField
import com.wangzan101.mika.core.screen.uistate.typeAndVerify
import com.wangzan101.mika.core.screen.uistate.relocate
import com.wangzan101.mika.core.screen.uistate.RELOCATE_MAX_SHIFT
import com.wangzan101.mika.core.screen.uistate.BackAffordance
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
    internal val a11y: ScreenAccessibility,
    internal val shell: IMikaShell,
    internal val display: LazyAgentDisplay,
    /** 剪贴板暂时换成 Mika 的文字时为 true,还原后为 false;任务服务据此记下,进程意外结束后下次启动还原。 */
    internal val clipHeld: (Boolean) -> Unit = {},
    /** 剪贴板是全局的:几个任务同时跑时粘贴兜底共用一把锁。 */
    internal val clipLock: Lock = ReentrantLock(),
    /** 并行任务时为 true:打开 App 前先查它是不是正在 Mika 的另一块屏上给别的任务用。 */
    internal val checkOtherTasks: Boolean = false,
    /** 并行任务时替本任务占下这个包;别的任务已占着(且还在用)时返回 false。 */
    internal val reserve: (String) -> Boolean = { true },
    /** 同 [reserve],占的是装在另一份用户(用户编号)里的那份:和本来那份分开占,两个任务能各用一份。 */
    internal val reserveCopy: (String, Int) -> Boolean = { _, _ -> true },
    /** 换剪贴板成功后交出用户原来的文字和 Mika 放进去的文字,供加密留底(进程被杀后据此还原)。 */
    internal val clipSaved: (original: String, ours: String) -> Unit = { _, _ -> },
    /** App 此刻在用户手上(接管中):这时主屏上开出什么都是用户的事,不去挪。 */
    internal val handedOver: () -> Boolean = { false },
    /** 手势的节奏:落指前的停顿、按住时长、落点偏差、滑动的弧线和快慢(见 [GesturePacing])。 */
    internal val pacing: GesturePacing = GesturePacing.human(),
    /** 在这个包名的 App 里打字的节奏(见 [TypingPacing]);默认一次写入全文。 */
    internal val typing: (String?) -> TypingPacing = { TypingPacing.Instant },
    /** 发出启动之前:要开的是 [packageName](装在 [user] 那份用户里的;null 为本来那份)。任务服务据此先静上,开头就没声。 */
    internal val beforeOpen: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    /** 启动被拒、没开出来:撤回 [beforeOpen] 提前做的。 */
    internal val notOpened: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    override val appAccess: com.wangzan101.mika.core.device.access.AppAccessPolicy = com.wangzan101.mika.core.device.access.AppAccessPolicy.ALLOW,
    /** 把工作区里的音频变成可分享的 content URI;为空时这个屏不支持音频分享。 */
    internal val shareAudioFile: (suspend (path: String) -> ShareableFile?)? = null,
    /** 把工作区里的图片变成可分享的 content URI;为空时这个屏不支持图片分享。 */
    internal val shareImageFile: (suspend (path: String) -> ShareableFile?)? = null,
    /** 并行任务时:按占用登记,本任务此刻能不能占这个包([user] 那份;null 为本来那份)。只查不占。 */
    internal val appFree: (packageName: String, user: Int?) -> Boolean = { _, _ -> true },
    /**
     * 并行任务时:等别的任务放开这个包,最多那么久;[free] 是完整的判断(别的屏上没有它、登记里也没人占)。
     * 任务服务据此登记「谁在等谁」、认出互相等,等的时候界面写「等另一个任务用完某 App」。
     */
    internal val awaitApp: suspend (packageName: String, user: Int?, timeoutMs: Long, free: suspend () -> Boolean) -> AppWait =
        { _, _, _, _ -> AppWait.Unsupported },
) : ScreenPort {
    /** 屏还没建时为 -1;只在确认屏已建好之后用。 */
    internal val displayId get() = display.current?.displayId ?: -1
    /** 屏已经建好(不等于打开过 App)。 */
    internal val hasDisplay get() = display.current != null
    internal val screenWidth get() = display.current?.width ?: display.width
    internal val screenHeight get() = display.current?.height ?: display.height
    internal val ids = NodeIds()

    /** 最近一次 launch_app 成功打开的包,用于判断是否被切走。 */
    @Volatile internal var targetPackage: String? = null

    /** 最近一次打开 App 时系统问「打开哪一个」的那个窗口所在的包:它在前台不算被切走。 */
    @Volatile internal var chooserPackage: String? = null

    /**
     * Agent 正在用的 App 及其任务编号(打开时不在最上面则为 -1);任务服务据此静音、监视是否被挪走。
     * [userId] 是那个任务所属的用户(装了两份时分得出是哪一份);读不到为 -1。
     */
    data class TargetApp(val packageName: String, val taskId: Int, val userId: Int = -1)

    internal val _target = MutableStateFlow<TargetApp?>(null)
    val target: StateFlow<TargetApp?> = _target.asStateFlow()

    internal val systemPickers: Set<String> by lazy { SystemPickers.resolve(a11y.packageManager) }

    /** 系统设置所在的包(按 Settings.ACTION_SETTINGS 解析):目标 App 在自己任务里打开的设置页不算被切走。 */
    internal val settingsPackage: String? by lazy { SystemPickers.settingsPackage(a11y.packageManager) }

    // ---------- 读屏 / 截图 / 事件 ----------

    /** 读屏 / 截图 / 事件的子对象,见 [ScreenCapture]。 */
    internal val capture = ScreenCapture(this)

    internal fun windows() = capture.windows()
    internal fun read() = capture.read()
    internal fun treeHash() = capture.treeHash()

    suspend fun accessPackages(): List<String?> = capture.accessPackages()
    suspend fun ensureDisplay() { capture.ensureDisplay() }

    override suspend fun snapshot(): ScreenSnapshot = capture.snapshot()
    override suspend fun peek(): ScreenSnapshot = capture.peek()
    internal suspend fun smsSnapshot(register: Boolean): ScreenSnapshot = capture.smsSnapshot(register)
    internal fun foregroundTaskPackage(): String? = capture.foregroundTaskPackage()
    fun foregroundApps(): Flow<TargetApp> = capture.foregroundApps()
    override suspend fun screenshotPng(): ByteArray? = capture.screenshotPng()
    internal fun frameServable(): Boolean = capture.frameServable()
    internal suspend fun awaitFrameAfter(startedAt: Long) = capture.awaitFrameAfter(startedAt)
    override suspend fun readVisibleText(): List<VisibleTextLine>? = capture.readVisibleText()
    override suspend fun frameGrid(): FrameGrid? = capture.frameGrid()
    override suspend fun checkScreen(): ScreenCheck = capture.checkScreen()
    override suspend fun waitStable(timeoutMs: Long): Boolean = capture.waitStable(timeoutMs)
    override suspend fun awaitChange(timeoutMs: Long): Boolean = capture.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = capture.waitFor(condition, value, timeoutMs)
    internal fun containsText(node: com.wangzan101.mika.core.screen.uistate.UiTreeNode, text: String): Boolean = capture.containsText(node, text)
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = capture.nextEvent(kind, packageName, timeoutMs)
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = capture.watchEvents(kind, packageName, durationMs, maxEvents)


    /** 最近一次启动开始的单调时钟时刻（纳秒），与虚拟屏生产者的帧时间戳比较。 */
    @Volatile internal var frameGuardSince: Long? = null


    // ---------- 动作 ----------

    /** 点 / 滑 / 滚 / 返回等动作,见 [ScreenActions]。 */
    internal val actions = ScreenActions(this)

    override suspend fun systemCommand(command: String): SystemCommandResult? = actions.systemCommand(command)
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = actions.tapRef(ref, snapshotId)
    override suspend fun tapXY(x: Int, y: Int): ActionResult = actions.tapXY(x, y)
    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = actions.doubleTapRef(ref, snapshotId)
    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = actions.doubleTapXY(x, y)
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = actions.swipe(x1, y1, x2, y2, durationMs)
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = actions.gesture(paths, durationMs)
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = actions.scroll(ref, snapshotId, down)
    override suspend fun idleScroll(fraction: Double): Boolean = actions.idleScroll(fraction)
    override suspend fun back(): ActionResult = actions.back()
    override suspend fun pressKey(keyCode: Int): ActionResult = actions.pressKey(keyCode)


    /** 打字 / 粘贴 / 清空,见 [TextInput]。 */
    internal val textInput = TextInput(this)

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = textInput.typeText(ref, snapshotId, text)

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = textInput.clearText(ref, snapshotId)

    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = textInput.typeAt(x, y, text)

    override suspend fun clearAt(x: Int, y: Int): ActionResult = textInput.clearAt(x, y)

    fun keyboardUp(): Boolean = textInput.keyboardUp()


    /** 短信登录,见 [SmsLogin]。 */
    internal val smsLogin = SmsLogin(this)

    suspend fun prepareSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.prepareSmsLogin(p, authorize)
    suspend fun submitSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.submitSmsLogin(p, code, authorize)
    suspend fun smsLoginOutcome(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray): com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome = smsLogin.smsLoginOutcome(p, code)
    suspend fun fillSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.fillSmsLoginCode(p, code, authorize)
    suspend fun requestSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.requestSmsLoginCode(p, authorize)


    /**
     * 启动到虚拟屏;[cold] 时先结束进程,回到 App 的初始页面。用户正在主屏用这个 App 时不启动也不结束它,
     * 稍等后重试,仍不行就交给 Agent 去暂停或请用户接管。虚拟屏在这里第一次用到时才建。
     */
    // ---------- 打开 / 分享 / 链接 ----------

    /** 打开 App / 分享 / 链接,见 [AppOpening]。 */
    internal val opening = AppOpening(this)

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = opening.openApp(packageName, cold)
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult = opening.openAppCopy(packageName, userId, cold)
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = opening.awaitAppFree(packageName, userId, timeoutMs)
    override suspend fun shareText(packageName: String, text: String): ActionResult = opening.shareText(packageName, text)
    override suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult = opening.shareAudio(packageName, path, text)
    override val canShareAudio: Boolean get() = opening.canShareAudio
    override suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult = opening.shareImage(packageName, path, text)
    override val canShareImage: Boolean get() = opening.canShareImage
    suspend fun openOwnPage(intent: Intent): ActionResult = opening.openOwnPage(intent)


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
    internal fun allowedLinkHandler(action: String?, uri: String?): String? {
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
    internal fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in systemPickers }

    internal fun hintLinkElsewhere(top: String?, expected: String) =
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
        val w = spill.armSpillWatch()
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
            ok(hint = hint, spill = spill.takeSpill(w)), handledBy = pkg,
            nextAlarmBefore = alarmBefore.getOrNull(), nextAlarmAfter = alarmAfter, alarmReadable = alarmBefore.isSuccess, showing = showing,
        )
    }

    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        withContext(Dispatchers.IO) { CalendarBulkWriter.insert(a11y, events) }

    internal fun hintNotInFront(top: String?, expected: String) =
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
            imeShown = { textInput.imeBox() != null },
            back = { runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }.getOrDefault(false) },
        )
        driver.set(node, value)
    }

    // ---------- 打开哪一个 ----------

    /** 最上面一个读得到的窗口(不论类型:系统的选择框有的是应用窗口,有的是系统窗口)所在的包。 */
    internal fun topWindowPackage(): String? =
        ScreenReader.contentWindows(windows()).firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /**
     * 打开 [packageName] 后系统在问打开哪一个:最上面换成了别的系统包的窗口([before] 是打开前最上面的),窗口里的选项提到这个 App 的名字。
     * 不是为 null。只认系统包:别的 App 的窗口提到这个名字不算。
     */
    internal fun chooserOf(packageName: String, before: String?): LaunchChoice? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val elements = ScreenReader.read(listOf(w), screenWidth, screenHeight).entries.map { it.element }
        val options = LaunchChooser.options(label, elements, profiles?.get(packageName).orEmpty()) ?: return null
        return LaunchChoice(pkg, options)
    }

    /** 最上面一个读得到的窗口换成了系统包的(不是 [packageName]、也不是启动前的 [before]):那个包;不是为 null。 */
    internal fun systemPickerOf(packageName: String, before: String?): String? {
        val pkg = topWindowPackage() ?: return null
        return pkg.takeIf { it != packageName && it != before && isSystemPackage(it) }
    }

    internal val systemPackages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 系统自带的包;Mika 自己看不到的包(安卓 11 起的包可见性)问内置助手。 */
    internal fun isSystemPackage(pkg: String): Boolean = pkg in systemPickers || systemPackages.getOrPut(pkg) {
        val flags = runCatching { a11y.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull()
        if (flags != null) flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        else runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false)
    }

    internal fun appLabel(pkg: String): String? = runCatching {
        val pm = a11y.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 也装在别的用户里的 App(包名 → 那几份用户的名字);第一次问内置助手,之后用这份。问不到时不记,下次再问。 */
    @Volatile internal var profiles: Map<String, List<String>>? = null

    override suspend fun otherProfiles(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        profiles ?: readProfiles()?.also { profiles = it }.orEmpty()
    }

    internal fun readProfiles(): Map<String, List<String>>? {
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
    @Volatile internal var copiedPackages: Set<String> = emptySet()

    /** 任务所属的用户;任务不存在或问不到为 -1。 */
    internal fun userOf(task: Int): Int = if (task < 0) -1 else runCatching { shell.userOfTask(task) }.getOrDefault(-1)

    // ---------- 核实 ----------

    /**
     * 执行动作,等界面安静后比对前后指纹;再看前台有没有被切走。动作前后盯着主屏:动作让 App 开到了用户主屏上就挪回来,
     * 结果里报 opened(见 [takeSpill])。上一个动作过了一会儿才开到主屏上的,这一个动作不做,先说屏幕变了。
     */
    internal suspend fun verified(
        relocatedTo: String? = null,
        fallback: (suspend (EventClock) -> Boolean)? = null,
        retryWhenUnchanged: Boolean = true,
        action: suspend () -> Boolean,
    ): ActionResult {
        spill.lateSpill()?.let { return ActionResult.Failed("opened_elsewhere", spill.hintLateSpill(it.opened)) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        // 只有可能补点的动作才需要在动作前记住前台包名和输入法边界,其余动作不白读两次窗口
        val backup = fallback?.takeIf { retryWhenUnchanged }
        val beforePkg = backup?.let { ScreenReader.topPackage(windows()) }
        val beforeIme = if (backup != null) textInput.imeBounds() else null
        val w = spill.armSpillWatch()
        val changed = withEventClock { clock ->
            clock.mark()
            if (action()) {
                val retry: (suspend () -> Boolean)? = if (backup != null) {
                    {
                        if (!handedOver() && ScreenReader.topPackage(windows()) == beforePkg && textInput.imeBounds() == beforeIme) {
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
        val profile = spill.adoptFromAdopted(w)
        val spilled = spill.takeSpill(w)
        return ok(changed = changed || spilled?.opened?.pulledBack == true, relocatedTo = relocatedTo, spill = spilled, inProfile = profile)
    }

    // ---------- 开到用户主屏上的 ----------

    /**
     * 系统在用户主屏上问打开哪一个、等用户选的那一次:[taskId] 问的那个窗口的任务,[packageName] 它的包,[app] 问的是哪个 App,
     * [topBefore] 它出来前主屏最上面的任务,[agentTop] 那时 Mika 屏上最上面的任务,[since] 它出来的时刻。
     * 选择框从不挪:挪了屏它要按新屏重建,有的重建时自己关掉;Mika 也不替别的 App 重新打开它(打开的一方得是原来那个 App)。
     */
    class UserPick(val taskId: Int, val packageName: String, val app: String?, val topBefore: Int, val agentTop: Int, val since: Long)

    /** 用户选完之后:选出来的 App 要用户亲手处理([NeedsUser],留在主屏),其余的已经办好(结果在下一次读屏或动作里报)。 */
    sealed interface PickResult {
        data object Settled : PickResult
        class NeedsUser(val taskId: Int, val packageName: String, val app: String?, val userId: Int) : PickResult
    }

    /** 「App 被开到用户主屏上」的子对象:挪回 / 等用户选,见 [ScreenSpill]。 */
    internal val spill = ScreenSpill(this)

    /** 正等着用户在主屏上选;任务据此停住,胶囊上请用户选一下。 */
    val userPick: StateFlow<UserPick?> get() = spill.userPick

    /**
     * 任务在下一个改动屏幕的动作前调:等用户在主屏上选完(选之前一直盯着主屏)。选完后,开到主屏上的普通页面挪回 Mika 的屏
     * (只挪,打开它的仍是原来那个 App);要用户亲手处理的(要输密码、窗口读不到,像付款、指纹)留在主屏,返回 [PickResult.NeedsUser];
     * 开在了 Mika 屏上的一并算进来;用户把问题关了就照实说。没在等时为 null。
     */
    suspend fun awaitUserPick(): PickResult? = spill.awaitUserPick()

    /** 用户处理完([PickResult.NeedsUser])点了继续:它还在主屏上就挪回 Mika 的屏接着做,已经关了就照实说。 */
    suspend fun afterUserStep(r: PickResult.NeedsUser) = spill.afterUserStep(r)

    /** 等界面稳定后比对指纹(规则见 [ChangeWait]);确认变了立即返回 true。 */
    internal suspend fun awaitChange(
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
    internal fun switchedTo(): AppSwitch.Switch? {
        val target = _target.value
        return AppSwitch.switchedTo(
            targetPackage,
            ScreenReader.topPackage(windows()),
            // 从用户主屏挪回来的(及从它们里面打开的)也是目标 App 这一路打开的
            sameTask = {
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                top >= 0 && (target != null && target.taskId == top || top in spill.adopted)
            },
            partOfTask = chooserPackage?.let { systemPickers + it } ?: systemPickers,
            settingsPage = { it == settingsPackage },
            // 同一个 App 装了两份:最上面换成了另一份用户里的那份(不是目标任务、也不是挪回来的)也算被切走
            // 只在目标是另一份、或这个 App 列出过另一份时才问任务(只装一份的 App 每个动作不多问两次)
            otherCopy = {
                target != null && target.userId >= 0 && (target.userId != myUser || target.packageName in copiedPackages) && run {
                    val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                    top >= 0 && top != target.taskId && top !in spill.adopted && userOf(top).let { it >= 0 && it != target.userId }
                }
            },
        )
    }

    /**
     * 打开时目标 App 还没到前台(系统先问打开哪一个、启动慢),之后它到了最上面:记下它的任务编号,
     * 任务服务才能据此静音、盯着它有没有被挪走。
     */
    internal suspend fun adoptTargetTask(): String? {
        val t = _target.value ?: return null
        if (t.taskId >= 0 || foregroundTaskPackage() != t.packageName) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        val user = userOf(task)
        _target.value = TargetApp(t.packageName, task, user)
        // 系统问过打开哪一个(装了两份):报开的是哪一份
        return spill.inProfileOf(t.packageName, user)
    }

    /** 带上「是否被切走」的动作结果;[spill] 是这个动作开到用户主屏上的 App,[inProfile] 见 [ActionResult.Ok.inProfile]。 */
    internal suspend fun ok(
        changed: Boolean? = null, relocatedTo: String? = null, verification: Verification? = null,
        hint: String? = null, secret: Boolean = false, spill: ScreenSpill.Spill? = null, inProfile: String? = null,
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
    @Volatile internal var requestedUser: Int? = null

    /**
     * 目标 App 装了两份(列出过另一份、要的就是另一份,或打开的就是另一份)时,此刻 Mika 屏上是哪一份:(inProfile, 所属用户)。
     * 只装一份、最上面不是它或读不到为 null;只装一份的 App 不多问。
     */
    internal fun shownCopy(): Pair<String, Int>? {
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
    internal fun resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
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
    internal fun unchanged(entry: NodeEntry): Boolean {
        val n = entry.node
        if (!n.refresh()) return false
        val now = ScreenReader.locatorOf(n, screenWidth, screenHeight) ?: return false
        return now == entry.locator.copy(context = null)
    }

    internal sealed interface Field {
        /** [tapped]:给的元素本身不是输入框,点了它之后才出现(或聚焦了)真正的输入框。 */
        class Found(val node: AccessibilityNodeInfo, val tapped: Boolean = false, val relocatedTo: String? = null) : Field
        class Missing(val reason: String) : Field
    }

    /**
     * 找要输入的框。给了编号:元素本身或它里面的输入框;都不是、而它看上去是输入入口(首页那条「假搜索框」:
     * 可点、不是按钮、带提示语或名字像搜索)时先点它,点成功且出现了一个点之前没有的、拿着焦点的输入框,才用那个框。
     * 没给编号:当前拿着输入焦点的框。
     */
    internal suspend fun findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
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
    internal data class FieldShape(
        val pkg: String?, val windowId: Int, val cls: String?, val rid: String?, val rect: Rect,
    ) {
        companion object {
            fun of(n: AccessibilityNodeInfo) =
                FieldShape(n.packageName?.toString(), n.windowId, n.className?.toString(), n.viewIdResourceName,
                    Rect().also(n::getBoundsInScreen))
        }
    }

    internal fun sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
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
    internal fun looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
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
    internal fun somethingHoldsInputFocus(): Boolean =
        textInput.imeBounds() != null || focusedInput() != null

    internal fun focusedInput(): AccessibilityNodeInfo? {
        val content = ScreenReader.contentWindows(windows())
        val focusedWindow = content.firstOrNull { it.isFocused }
        if (focusedWindow != null) return focusedWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return content.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    }

    internal fun visibleInputTarget(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && ScreenReader.locatorOf(node, screenWidth, screenHeight) != null

    /** Keep a known hidden native editor distinct from a custom editor absent from the tree. */
    internal fun focusedField(): Field {
        val focus = focusedInput() ?: return Field.Missing(NO_FOCUS)
        val editor = if (ScreenReader.canSetText(focus)) focus
            else descendant(focus) { it.isFocused && ScreenReader.canSetText(it) } ?: return Field.Missing(NO_FOCUS)
        return if (editor.refresh() && visibleInputTarget(editor)) Field.Found(editor) else Field.Missing(EDITOR_NOT_VISIBLE)
    }

    internal fun focusedEditable(): AccessibilityNodeInfo? = (focusedField() as? Field.Found)?.node

    internal fun blockedFocusedEditor(): Boolean = (focusedField() as? Field.Missing)?.reason == EDITOR_NOT_VISIBLE

    // ---------- 底层操作 ----------

    internal fun isClickableTarget(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

    /** 坐标点击无反应时,反查该点处最小的可点控件改走无障碍点击;命中对外/不可逆按钮或大容器时不补点。 */
    internal fun clickNodeAt(x: Int, y: Int, clock: EventClock): Boolean {
        if (!hasDisplay || handedOver() || textInput.imeBounds()?.contains(x, y) == true) return false
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
    internal suspend fun click(node: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(node) { isClickableTarget(it) }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
    }

    internal suspend fun tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

    /** 在这一点上点一下:先停一会儿再落指,按住随机的一小会儿。无障碍手势不行时由内置助手注入触摸。 */
    internal suspend fun touch(at: Pair<Int, Int>): Boolean {
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
    internal suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
        if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
        delay(pacing.approach())
        val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, screenWidth to screenHeight)
        if (injectStroke(stroke.points)) return true
        return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
    }

    internal fun injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
        shell.injectStroke(
            displayId,
            IntArray(points.size) { points[it].x.roundToInt() },
            IntArray(points.size) { points[it].y.roundToInt() },
            LongArray(points.size) { points[it].t },
        )
    }.getOrDefault(false)

    internal fun pathOf(points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(points.first().first.toFloat(), points.first().second.toFloat())
        points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
    }

    /**
     * 网页里的编辑区(contenteditable)只认真实输入:SET_TEXT 只改了网页上的字,编辑器收不到 input 事件,
     * App 会当作没输入(「完成」不亮、退出不保存)。这类输入框改用选中原内容再粘贴或按删除键。
     */
    internal fun inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

    /**
     * 选中原内容后粘贴(或按删除键清空)。返回 null 表示剪贴板不能安全使用,没有改动任何文字;
     * 其余结果用 [AsciiTextKeys] 三态表示。选择失败时不发删除或粘贴,避免只删一个字符或在光标处插入。
     */
    internal fun inputText(node: AccessibilityNodeInfo, text: String): Int? {
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
    internal fun withClipboardText(text: String, action: () -> Int): Int? {
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

    internal fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh() || !visibleInputTarget(node)) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    internal inline fun ancestorOrSelf(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= MAX_ANCESTORS) {
            if (test(n)) return n
            n = n.parent
            depth++
        }
        return null
    }

    internal fun descendant(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
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
    internal fun frameSignature(): IntArray? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.lumaGrid(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    /** 画面是否全黑;安全窗口和虚拟屏合成失败都可能产生这种帧,不能单靠它判原因。 */
    internal fun frameBlack(): Boolean? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.isBlack(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    // ---------- 事件时钟 ----------

    /** 记录这块屏上最近一次界面事件的时间。 */
    internal class EventClock {
        internal val last = AtomicLong(SystemClock.uptimeMillis())
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
    internal suspend fun <T> withEventClock(block: suspend (EventClock) -> T): T = coroutineScope {
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

    internal companion object {
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
        const val HINT_FIELD_REPLACED = "Tapping the field opened a new input in its place (often a separate search page), so the text went into the field that holds focus now. Check which field holds your text before submitting."
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
