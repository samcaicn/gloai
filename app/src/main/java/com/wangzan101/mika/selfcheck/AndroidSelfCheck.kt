package com.wangzan101.mika.selfcheck

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.FrameMath
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebScreen
import com.wangzan101.mika.cap.web.WebTabPool
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckOutcome
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.DeviceFacts
import com.wangzan101.mika.core.selfcheck.SelfCheck
import com.wangzan101.mika.core.selfcheck.SelfCheckPort
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PhoneSetupFlow
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.shell.IMikaShell
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 兼容性自检的安卓实现。手机这几项走任务用的同一套:进程里共用的那条助手连接([PhoneServices.sharedShell])、
 * [AgentDisplay] 建屏、[AndroidScreen] 打开页面、读屏、点按、打字、滚动、返回;网页走内置浏览器的 [WebScreen];
 * 沙箱走 [AndroidSandbox]。只碰 Mika 自己的自检页和系统设置(只打开、不点),不改设置、不联网。
 * 报告里的事实只写状态和数字,不写别的 App 的包名、账号和用户的内容。
 * [status] 是界面那边此刻的系统状态(无障碍、通知、内置助手)。
 */
class AndroidSelfCheck(context: Context, private val status: () -> SystemStatus) : SelfCheckPort {
    private val app = context.applicationContext
    private val phone = PhoneServices.of(app)

    @Volatile private var connection: ShellBinding? = null
    @Volatile private var shell: IMikaShell? = null
    @Volatile private var display: AgentDisplay? = null
    @Volatile private var screen: AndroidScreen? = null
    @Volatile private var web: Web? = null

    /** 开跑时主屏最上面的任务;之后每到一个节点再看一次,变了的节点记下来。 */
    private var mainBefore: Int? = null
    private val mainChanged = mutableListOf<String>()

    private class Web(val host: WebHost, val pool: WebTabPool, val screen: WebScreen) {
        fun close() {
            runCatching { pool.closeAll(TASK) }
            runCatching { pool.close() }
            runCatching { host.close() }
        }
    }

    override suspend fun facts(): DeviceFacts = withContext(Dispatchers.IO) {
        val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val m = metrics()
        val st = status()
        val devPageMissing = DeveloperSettings.developerOptionsPageMissing(app.packageManager)
        val wirelessMissing = DeveloperSettings.wirelessDebuggingMissing(app.contentResolver)
        DeviceFacts(
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
            mem.totalMem, m.widthPixels, m.heightPixels,
            wirelessDebugging = !wirelessMissing,
            appVersion = BuildConfig.VERSION_NAME, appBuild = BuildConfig.GIT_HASH,
            developerOptionsPage = !devPageMissing,
            phoneControlUnavailable = PhoneSetupFlow.unavailable(devPageMissing, wirelessMissing, st.builtinShell, st.helperRestart),
        )
    }

    override suspend fun check(check: SelfCheck): CheckOutcome = when (check) {
        SelfCheck.DEVICE -> error("judged from facts()")
        SelfCheck.PERMISSIONS -> permissions()
        SelfCheck.HELPER -> helper()
        SelfCheck.VIRTUAL_DISPLAY -> virtualDisplay()
        SelfCheck.APP_LAUNCH -> appLaunch()
        SelfCheck.SCREEN_READING -> screenReading()
        SelfCheck.ACTIONS -> actions()
        SelfCheck.STAYS_ALIVE -> staysAlive()
        SelfCheck.MAIN_SCREEN -> mainScreen()
        SelfCheck.BROWSER -> browser()
        SelfCheck.SANDBOX -> sandbox()
        SelfCheck.APP_COPIES -> appCopies()
    }

    // ---------- 熄屏自检(手动诊断) ----------

    /**
     * 手动诊断:主屏熄屏且锁定之后,任务用的那一套(内置助手、虚拟屏、打开系统设置、读树、截图、点按)还能不能工作。
     * 用真机端口,但持的是 PARTIAL 唤醒锁:不点亮主屏、不走任务解锁。监视挂上之后,主屏亮起、用户回到手机、
     * 或有任务开跑,这次自检就作废,如实报「被打断」。每段结果经 [report] 交给界面。
     */
    internal suspend fun checkScreenOff(taskBusy: () -> Boolean, report: (Int, CheckOutcome) -> Unit) {
        val displays = app.getSystemService(DisplayManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        fun screenOff() = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_OFF
        fun lockedAndOff() = screenOff() && keyguard.isDeviceLocked
        val armed = AtomicBoolean()
        val invalidated = AtomicBoolean()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (armed.get()) invalidated.set(true)
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && armed.get() && !lockedAndOff()) invalidated.set(true)
            }
        }
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:ScreenOffSelfCheck")
        fun valid() = !invalidated.get() && !taskBusy() && lockedAndOff()
        val waitStage = R.string.selfcheck_screen_off_lock
        val finalStage = R.string.selfcheck_screen_off_start
        var stage = waitStage
        var registeredReceiver = false
        var registeredListener = false
        var completed = false
        var firstProblem: Pair<Int, CheckOutcome>? = null
        fun reportStep(label: Int, outcome: CheckOutcome) {
            if (outcome.status != CheckStatus.Pass && firstProblem == null) firstProblem = label to outcome
            report(label, outcome)
        }
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registeredReceiver = true
            displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            registeredListener = true
            lock.acquire(SCREEN_OFF_WAKE_LOCK_MS)
            // 等用户按电源键熄屏并锁定;期间有任务开跑就不开始
            val ready = withTimeoutOrNull(SCREEN_OFF_READY_MS) {
                while (!lockedAndOff() && !taskBusy()) delay(POLL_MS)
                !taskBusy()
            }
            if (ready != true) {
                reportStep(stage, CheckOutcome.fail(
                    if (taskBusy()) tr(R.string.selfcheck_task_running) else tr(R.string.selfcheck_advice_timeout),
                    Advice.TimedOut,
                ))
                return
            }
            armed.set(true)
            reportStep(stage, CheckOutcome.pass(tr(R.string.selfcheck_status_pass)))
            val finished = withTimeoutOrNull(SCREEN_OFF_STAGES_MS) {
                completed = runScreenOffStages(::valid, { stage = it }, ::reportStep)
                true
            }
            if (finished != true && valid()) reportStep(stage, CheckOutcome.fail(tr(R.string.selfcheck_advice_timeout), Advice.TimedOut))
        } catch (e: CancellationException) {
            throw e
        } catch (_: ScreenOffInterrupted) {
            // 主屏状态变了:收尾释放虚拟屏之后统一报「被打断」
        } catch (e: Exception) {
            reportStep(stage, CheckOutcome.fail(e.javaClass.simpleName, Advice.Error))
        } finally {
            try {
                release()
                withContext(Dispatchers.Main.immediate) {
                    if (armed.get() && !valid()) {
                        report(finalStage, CheckOutcome.fail(tr(R.string.selfcheck_screen_off_interrupted), Advice.MainScreenChanged))
                    } else {
                        val problem = firstProblem
                        if (problem != null) {
                            val (label, outcome) = problem
                            report(finalStage, outcome.copy(detail = tr(label)))
                        } else if (completed) {
                            report(finalStage, CheckOutcome.pass(tr(R.string.selfcheck_status_pass)))
                        }
                    }
                }
            } finally {
                if (registeredListener) displays.unregisterDisplayListener(listener)
                if (registeredReceiver) app.unregisterReceiver(receiver)
                if (lock.isHeld) lock.release()
            }
        }
    }

    /** 主屏状态一变就中断在跑的阶段;真正的「被打断」报告在虚拟屏放掉之后才发。 */
    private class ScreenOffInterrupted : Exception()

    /**
     * 熄屏自检的几段,顺序和正常自检一致:内置助手 → 虚拟屏 → 打开系统设置 → 读树和截图 → 点按搜索。
     * 每步前后都核一次主屏状态,变了立即中断;任何一步不过就停。
     */
    private suspend fun runScreenOffStages(
        valid: () -> Boolean,
        starting: (Int) -> Unit,
        report: (Int, CheckOutcome) -> Unit,
    ): Boolean {
        fun verifyLock() {
            if (!valid()) throw ScreenOffInterrupted()
        }
        suspend fun step(label: Int, work: suspend () -> CheckOutcome): Boolean {
            starting(label)
            verifyLock()
            val outcome = work()
            verifyLock()
            report(label, outcome)
            return outcome.status == CheckStatus.Pass
        }
        if (!step(R.string.selfcheck_check_helper, ::helper)) return false
        if (!step(R.string.selfcheck_check_display, ::virtualDisplay)) return false
        val sh = checkNotNull(shell)
        val d = checkNotNull(display)
        val settings = SystemPickers.settingsPackage(app.packageManager)
        var deviceScreen: AndroidScreen? = null
        // 打开系统设置(只打开、不点):主屏熄着,设置要开在刚建好的虚拟屏上
        if (!step(R.string.selfcheck_check_launch) {
            val a11y = ScreenAccessibility.instance.value
            if (a11y == null || settings == null) {
                return@step CheckOutcome.fail("Settings or accessibility unavailable", Advice.LaunchFailed)
            }
            val s = AndroidScreen(a11y, sh, LazyAgentDisplay(d.width, d.height) { d }.also { it.get() })
            screen = s
            deviceScreen = s
            verifyLock()
            // 开在新建好的虚拟屏上;不去 force-stop 系统设置
            val opened = s.openApp(settings, cold = false)
            if (opened is ActionResult.Ok && poll(PAGE_WAIT_MS) {
                    verifyLock()
                    topPackage(sh, d.displayId) == settings
                }) CheckOutcome.pass("Settings opened on the virtual display")
            else CheckOutcome.fail("Settings: ${reason(opened)}", Advice.LaunchFailed)
        }) return false
        val s = checkNotNull(deviceScreen)
        val pkg = checkNotNull(settings)
        val treeReadable = step(R.string.selfcheck_check_reading) {
            var readFailure = R.string.selfcheck_screen_off_no_app_window
            val readable = poll(PAGE_WAIT_MS) {
                verifyLock()
                val snapshot = s.peek()
                val settingsWindow = selfCheckWindowContent(snapshot, pkg)
                readFailure = when {
                    !snapshot.hasWindow -> R.string.selfcheck_screen_off_no_app_window
                    settingsWindow == null && snapshot.windows.any {
                        it.type == "application" && it.packageName == null
                    } -> R.string.selfcheck_screen_off_no_root
                    settingsWindow == null -> R.string.selfcheck_screen_off_no_settings_window
                    else -> R.string.selfcheck_screen_off_no_elements
                }
                settingsWindow?.elements?.isNotEmpty() == true
            }
            if (readable) CheckOutcome.pass(tr(R.string.selfcheck_screen_off_tree_readable))
            else CheckOutcome.fail(tr(readFailure), Advice.ReadFailed)
        }
        val frameReadable = step(R.string.selfcheck_screen_off_capture) {
            val frame = awaitFrame(d)
                ?: return@step CheckOutcome.fail(tr(R.string.selfcheck_screen_off_no_frame), Advice.BlankFrames)
            val black = try { FrameMath.isBlack(frame.width, frame.height, frame::getPixel) }
                finally { frame.recycle() }
            if (black) CheckOutcome.fail(tr(R.string.selfcheck_screen_off_black_frame), Advice.BlankFrames)
            else CheckOutcome.pass(tr(R.string.selfcheck_screen_off_frame_readable))
        }
        if (!treeReadable || !frameReadable) return false
        return step(R.string.selfcheck_check_actions) {
            val window = selfCheckWindowContent(s.snapshot(), pkg)
                ?: return@step CheckOutcome.fail("Settings window is no longer readable", Advice.ReadFailed)
            val search = window.elements.firstOrNull { element ->
                element.clickable && !element.editable && !element.secret &&
                    (element.resourceId?.substringAfterLast('/') in SETTINGS_SEARCH_IDS ||
                        element.name in SETTINGS_SEARCH_LABELS)
            } ?: return@step CheckOutcome.partial("Settings search button not identified", Advice.ActionFailed)
            verifyLock()
            if (s.tapRef(search.ref, window.snapshotId) !is ActionResult.Ok) {
                return@step CheckOutcome.fail("Settings search tap failed", Advice.ActionFailed)
            }
            val changed = poll(PAGE_WAIT_MS) {
                verifyLock()
                val target = topPackage(sh, d.displayId)
                target != null && systemPackage(target) &&
                    selfCheckWindowContent(s.peek(), target)?.elements.orEmpty().any { element ->
                        element.editable && !element.secret &&
                            (element.searchField || element.focused) &&
                            window.elements.none { old -> old.editable && old.resourceId == element.resourceId && old.name == element.name }
                    }
            }
            if (changed) CheckOutcome.pass("Tapping Settings search opened a search field")
            else CheckOutcome.fail("Settings search did not open", Advice.ActionFailed)
        }
    }

    // ---------- 2 权限 ----------

    private fun permissions(): CheckOutcome {
        val s = status()
        val battery = runCatching { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) }.getOrNull()
        val detail = "accessibility " + when (s.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        } + ", notifications " + (if (s.notificationsAllowed) "allowed" else "blocked") +
            ", battery " + when (battery) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" }
        return when {
            s.accessibility == AccessibilityState.Off -> CheckOutcome.fail(detail, Advice.TurnOnAccessibility)
            s.accessibility == AccessibilityState.EnabledNotRunning -> CheckOutcome.fail(detail, Advice.RestartAccessibility)
            !s.notificationsAllowed -> CheckOutcome.partial(detail, Advice.AllowNotifications)
            battery == false -> CheckOutcome.partial(detail, Advice.AllowBattery)
            else -> CheckOutcome.pass(detail)
        }
    }

    // ---------- 3 内置助手 ----------

    private suspend fun helper(): CheckOutcome {
        val st = status().builtinShell
        // 没配过对就不去连(连的时候会提示去设置);配过对的,看门狗可能正在起它,等一会儿
        if (st == BuiltinShellStatus.NotPaired && !phone.shellControl.paired) return CheckOutcome.fail("not set up ($st)", Advice.SetUpPhoneControl)
        val conn = connection ?: phone.sharedShell.acquire().also { connection = it }
        val sh = withTimeoutOrNull(HELPER_WAIT_MS) { conn.shell.filterNotNull().first() }
            ?: return CheckOutcome.fail("not running (${phone.shellControl.status.value})", Advice.SetUpPhoneControl)
        shell = sh
        return withContext(Dispatchers.IO) {
            val uid = sh.identity().getOrNull(0) ?: -1
            val path = when {
                uid == ROOT_UID -> "root"
                phone.shellControl.paired -> "paired wireless debugging"
                else -> "started from a computer (USB)"
            }
            val who = when (uid) { SHELL_UID -> "shell"; ROOT_UID -> "root"; else -> "uid $uid" }
            val times = List(LATENCY_CALLS) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                sh.identity()
                (SystemClock.elapsedRealtimeNanos() - t0) / 1_000
            }.sorted()
            mainBefore = mainTop(sh)
            CheckOutcome.pass("running as $who, $path, round trip ${format(times[times.size / 2] / 1000.0)} ms")
        }
    }

    // ---------- 4 虚拟屏 ----------

    private suspend fun virtualDisplay(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val m = metrics()
        // 建屏不中途打断:建好的一定记下,收尾时放掉
        val made = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { AgentDisplay(sh, m.widthPixels, m.heightPixels, m.densityDpi, app.getSystemService(DisplayManager::class.java)) }.onSuccess { display = it }
        }
        val d = made.getOrElse { return CheckOutcome.fail("could not be created (${it.javaClass.simpleName})", Advice.DisplayFailed) }
        val flags = runCatching { app.getSystemService(DisplayManager::class.java).getDisplay(d.displayId)?.flags }.getOrNull()
        val own = flags?.let { it and ownFocusFlag() != 0 }
        val detail = "created ${d.width}x${d.height}, own focus " + when (own) { true -> "yes"; false -> "no"; null -> "unknown" }
        return when {
            Build.VERSION.SDK_INT < DeviceFacts.OWN_FOCUS_SDK -> CheckOutcome.partial("$detail (own focus needs Android 14)", Advice.FocusShared)
            own == false -> CheckOutcome.partial(detail, Advice.FocusShared)
            else -> CheckOutcome.pass(detail)
        }
    }

    /** 安卓 14 起显示的「自己的焦点」标志;常量没公开,先按名字取,取不到用原版的值。 */
    private fun ownFocusFlag(): Int = runCatching { Display::class.java.getField("FLAG_OWN_FOCUS").getInt(null) }.getOrDefault(1 shl 11)

    // ---------- 5 打开页面 ----------

    private suspend fun appLaunch(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        val a11y = ScreenAccessibility.instance.value ?: return CheckOutcome.fail("accessibility service not connected", Advice.RestartAccessibility)
        val s = screen ?: AndroidScreen(a11y, sh, LazyAgentDisplay(d.width, d.height) { d }.also { it.get() }).also { screen = it }
        val own = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (own !is ActionResult.Ok || topPackage(sh, d.displayId) != app.packageName) {
            return CheckOutcome.fail("Mika's test page did not open on Mika's display (${reason(own)})", Advice.LaunchFailed)
        }
        sampleMain(sh, "test page")
        // 先关掉自检页再开系统设置:之后从头开一页,不靠把旧的那一页翻回前面
        withContext(Dispatchers.Main) { SelfCheckTargetActivity.finishOpen() }
        val settings = SystemPickers.settingsPackage(app.packageManager)
        val settingsNote = if (settings == null) "no system Settings app found" else when (val r = s.openApp(settings, cold = false)) {
            is ActionResult.Failed -> if (r.reason == "app_in_use_by_user") SETTINGS_IN_USE else "Settings did not open (${r.reason})"
            is ActionResult.Ok -> {
                val onMika = withContext(Dispatchers.IO) { topPackage(sh, d.displayId) } == settings
                val onMain = withContext(Dispatchers.IO) { mainTop(sh)?.let { runCatching { sh.packageOfTask(it) }.getOrNull() } } == settings
                sampleMain(sh, "Settings")
                // 只看不点:返回一次离开它
                s.back()
                when {
                    onMain -> SETTINGS_ON_MAIN
                    !onMika -> "Settings did not come to the front of Mika's display"
                    else -> null
                }
            }
        }
        // 后面几项在自检页上做:重新打开一次,从头来
        val again = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (again !is ActionResult.Ok || !poll { withContext(Dispatchers.IO) { isTestPageOnTop(sh, d.displayId) } }) {
            return CheckOutcome.fail("Mika's test page did not reopen after Settings (${reason(again)})", Advice.LaunchFailed)
        }
        return when (settingsNote) {
            null -> CheckOutcome.pass("Mika's test page and Settings opened on Mika's display")
            SETTINGS_ON_MAIN -> CheckOutcome.fail("test page ok; $settingsNote", Advice.OpenedOnMainScreen)
            SETTINGS_IN_USE -> CheckOutcome.partial("test page ok; $settingsNote", Advice.SettingsInUse)
            else -> CheckOutcome.partial("test page ok; $settingsNote", Advice.LaunchFailed)
        }
    }

    // ---------- 6 读屏 ----------

    private suspend fun screenReading(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        s.waitStable(SETTLE_MS)
        // 页面刚开出来时树可能还没齐:只认 Mika 自己窗口里的控件,最多等一会儿。
        // 系统覆盖窗口不属于新任务;完整系统应用则会成为最上层任务,不能一并容忍。
        suspend fun inspect(source: ScreenSnapshot) = readSelfCheckWindow(
            source, app.packageName, withContext(Dispatchers.IO) { topPackage(sh, d.displayId) }, EXPECTED, ::systemPackage,
        ) { key -> find(key) }
        var snap = s.snapshot()
        var inspected = inspect(snap)
        poll(PAGE_WAIT_MS) {
            snap = s.snapshot()
            inspected = inspect(snap)
            inspected.ready
        }
        val result = inspected
        if (!result.ready) {
            val wrongApp = if (result.wrongAppOnTop) "; another app is above Mika" else ""
            return CheckOutcome.fail(
                "missing ${result.missing.joinToString().ifEmpty { "none" }}; ${seen(result.content ?: snap)}$wrongApp",
                Advice.ReadFailed,
            )
        }
        val content = result.content ?: return CheckOutcome.fail("Mika's window is not readable", Advice.ReadFailed)
        val frame = awaitFrame(d) ?: return CheckOutcome.fail("tree ok; no screenshot from the display", Advice.BlankFrames)
        val blank = try {
            val grid = FrameMath.lumaGrid(frame.width, frame.height, frame::getPixel)
            FrameMath.isBlack(frame.width, frame.height, frame::getPixel) || grid.max() - grid.min() < BLANK_SPREAD
        } finally {
            frame.recycle()
        }
        if (blank) return CheckOutcome.fail("tree ok; screenshot is blank or black", Advice.BlankFrames)
        return CheckOutcome.pass("${content.elements.size} Mika-window elements, all expected found; screenshot not blank")
    }

    // ---------- 7 动作 ----------

    private suspend fun actions(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        fun mark(name: String, passed: Boolean) { if (passed) ok += name else bad += name }
        // 系统覆盖窗口存在时也只对 Mika 的控件做动作;编号仍属于原始快照,可直接交给动作端口。
        fun own(source: ScreenSnapshot) = ownContent(source) ?: source.copy(elements = emptyList(), windows = emptyList())

        // 点按:计数加一
        var snap = own(s.snapshot())
        val before = snap.find(COUNT)?.number()
        val button = snap.find(BUTTON)
        if (button != null && before != null) {
            s.tapRef(button.ref, snap.snapshotId)
            mark("tap", poll { own(s.peek()).find(COUNT)?.number() == before + 1 })
        } else mark("tap", false)

        // 打字:英文和中文各打一次,回读输入框
        snap = own(s.snapshot())
        mark("type ASCII", snap.find(INPUT)?.let { typed(s, it, snap, ASCII) } == true)
        snap = own(s.snapshot())
        mark("type Chinese", snap.find(INPUT)?.let { typed(s, it, snap, CHINESE) } == true)

        // 滚动:看得见的行换了
        snap = own(s.snapshot())
        val rowsBefore = snap.rows()
        val list = snap.find(LIST)
        if (list != null && rowsBefore.isNotEmpty()) {
            s.scroll(list.ref, snap.snapshotId, down = true)
            mark("scroll", poll { own(s.peek()).rows().let { it.isNotEmpty() && it.first() != rowsBefore.first() } })
        } else mark("scroll", false)

        // 返回:自检页离开 Mika 屏的最上面。刚打完字输入法多半还弹着,那几下是收起输入法(安卓本来如此),不算在次数里
        val gone = BackPresses.until(
            tries = BACK_TRIES,
            maxPresses = BACK_MAX_PRESSES,
            keyboardUp = { withContext(Dispatchers.IO) { s.keyboardUp() } },
            press = { s.back() },
            left = { poll { withContext(Dispatchers.IO) { !isTestPageOnTop(sh, d.displayId) } } },
        )
        mark("back", gone)
        sampleMain(sh, "actions")

        val detail = (ok.map { "$it ok" } + bad.map { "$it failed" }).joinToString(", ")
        return when {
            bad.isEmpty() -> CheckOutcome.pass(detail)
            bad == listOf("type Chinese") -> CheckOutcome.partial(detail, Advice.ActionFailed)
            else -> CheckOutcome.fail(detail, Advice.ActionFailed)
        }
    }

    private suspend fun typed(s: AndroidScreen, field: UiElement, snap: ScreenSnapshot, text: String): Boolean {
        s.clearText(field.ref, snap.snapshotId)
        val fresh = ownContent(s.snapshot()) ?: return false
        val f = fresh.find(INPUT) ?: return false
        s.typeText(f.ref, fresh.snapshotId, text)
        return poll { ownContent(s.peek())?.find(INPUT)?.name?.let { SelfCheckText.contains(it, text) } == true }
    }

    // ---------- 8 一直在动 ----------

    private suspend fun staysAlive(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        if (s.openOwnPage(SelfCheckTargetActivity.intent(app)) !is ActionResult.Ok) {
            return CheckOutcome.fail("Mika's test page did not reopen", Advice.LaunchFailed)
        }
        val start = SystemClock.uptimeMillis()
        var lastChange = start
        var prev = frameHash(d)
        var lastTick = s.peek().find(TICK)?.number()
        var treeLostAt: Long? = null
        while (SystemClock.uptimeMillis() - start < ALIVE_MS) {
            delay(ALIVE_STEP_MS)
            val now = SystemClock.uptimeMillis()
            val h = frameHash(d)
            if (h != null && h != prev) lastChange = now
            prev = h
            if (now - lastChange > STALL_MS) {
                return CheckOutcome.fail("frames stopped after ${(lastChange - start + 500) / 1000} s", Advice.FramesStopped)
            }
            val tick = s.peek().find(TICK)?.number()
            if (tick == null || tick == lastTick) {
                if (treeLostAt == null) treeLostAt = now
            } else treeLostAt = null
            lastTick = tick
            treeLostAt?.let { lost ->
                if (now - lost > STALL_MS) return CheckOutcome.fail("tree stopped updating after ${(lost - start + 500) / 1000} s", Advice.FramesStopped)
            }
        }
        return CheckOutcome.pass("frames and tree kept changing for ${ALIVE_MS / 1000} s")
    }

    // ---------- 9 主屏 ----------

    private suspend fun mainScreen(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val before = mainBefore ?: return CheckOutcome.fail("main screen was not readable at the start", Advice.Error)
        sampleMain(sh, "end")
        val who = if (withContext(Dispatchers.IO) { runCatching { sh.packageOfTask(before) }.getOrNull() } == app.packageName) "Mika" else "another app"
        return if (mainChanged.isEmpty()) CheckOutcome.pass("top task unchanged ($who stayed on top)")
        else CheckOutcome.fail("top task changed during: ${mainChanged.joinToString()}", Advice.MainScreenChanged)
    }

    // ---------- 10 内置浏览器 ----------

    private suspend fun browser(): CheckOutcome {
        if (runCatching { WebViewCompat.getCurrentWebViewPackage(app) == null }.getOrDefault(true)) return CheckOutcome.fail("no WebView on this device", Advice.NoWebView)
        val m = metrics()
        // 和任务一样在主线程建;建好的一定记下,收尾时关掉。访问不记进网站列表(不给 registry)
        val built = withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                val host = WebHost(app, m.widthPixels, m.heightPixels, m.densityDpi)
                try {
                    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
                    val pool = WebTabPool(app, host, assetLoaders = listOf(loader))
                    Web(host, pool, WebScreen(pool, TASK, host, SystemClock::uptimeMillis)).also { web = it }
                } catch (e: Exception) {
                    host.close()
                    throw e
                }
            }
        }
        val w = built.getOrElse { return CheckOutcome.fail("browser could not start (${it.javaClass.simpleName})", Advice.WebFailed) }
        val isArabic = AppLanguage.resources(app).configuration.locales[0].language == "ar"
        val pageUrl = if (isArabic) WEB_PAGE_AR else WEB_PAGE
        val clickedText = if (isArabic) WEB_CLICKED_AR else WEB_CLICKED
        val nav = w.screen.navigate(pageUrl, newTab = false)
        if (!nav.loaded) return CheckOutcome.fail("local page did not load (${nav.netError ?: "not loaded"})", Advice.WebFailed)
        val read = w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text
        if (!SelfCheckText.contains(read, WEB_MARKER)) return CheckOutcome.fail("page loaded but its text was not read", Advice.WebFailed)
        val snap = w.screen.snapshot()
        val button = snap.elements.firstOrNull { it.resourceId == WEB_BUTTON }
            ?: return CheckOutcome.fail("text read; button not found", Advice.WebFailed)
        w.screen.tapRef(button.ref, snap.snapshotId)
        val clicked = poll { SelfCheckText.contains(w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text, clickedText) }
        return if (clicked) CheckOutcome.pass("local page loaded, text read, button tap verified")
        else CheckOutcome.fail("text read; button tap did not change the page", Advice.WebFailed)
    }

    // ---------- 11 沙箱 ----------

    private suspend fun sandbox(): CheckOutcome {
        val state = SandboxState.get(app)
        when (val st = state.status.value) {
            is SandboxStatus.Unsupported -> return CheckOutcome.skipped("not supported on ${Build.SUPPORTED_ABIS.firstOrNull() ?: "this device"}", Advice.SandboxUnsupported)
            is SandboxStatus.Ready -> Unit
            // 不为自检现装运行环境(要好几分钟)
            else -> return CheckOutcome.partial("runtime not prepared (${st.javaClass.simpleName})", Advice.SandboxNotPrepared)
        }
        val box = AndroidSandbox(app, state)
        // 命令出错(比如断网过滤装不上被拒)照实记下;取消照常往外抛
        suspend fun run(argv: List<String>): Result<CommandResult> = try {
            Result.success(box.run(SANDBOX_WORKSPACE, argv, null, COMMAND_MS) {})
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val echo = run(listOf("sh", "-c", "echo ok"))
        val echoOk = echo.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "ok" } == true
        val py = run(listOf("python3", "-c", "print(1+1)"))
        val pyOk = py.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "2" } == true
        fun what(r: Result<CommandResult>) = r.fold(
            { if (it.timedOut) "timed out" else "exit ${it.exitCode}, ${it.durationMs} ms" }, { "error ${it.javaClass.simpleName}" },
        )
        val detail = "echo ${if (echoOk) "ok" else "failed"} (${what(echo)}), python3 ${if (pyOk) "ok" else "failed"} (${what(py)})"
        return when {
            echoOk && pyOk -> CheckOutcome.pass(detail)
            echoOk -> CheckOutcome.partial(detail, Advice.SandboxFailed)
            else -> CheckOutcome.fail(detail, Advice.SandboxFailed)
        }
    }

    // ---------- 12 另一份 App ----------

    private suspend fun appCopies(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val users = withContext(Dispatchers.IO) { runCatching { sh.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) }.getOrNull() }
            ?: return CheckOutcome.partial("other users could not be read", Advice.Error)
        return CheckOutcome.pass("${users.size} usable other profile(s)")
    }

    // ---------- 收尾 ----------

    override suspend fun release() {
        withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                runCatching { SelfCheckTargetActivity.finishOpen() }
                web?.close()
                web = null
            }
            withContext(Dispatchers.IO) {
                display?.release()
                display = null
            }
            screen = null
            shell = null
            connection?.let {
                connection = null
                phone.sharedShell.release()
            }
        }
    }

    // ---------- 小工具 ----------

    private fun metrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** 主屏最上面的任务;读不到为 null。 */
    private fun mainTop(sh: IMikaShell): Int? = runCatching { sh.topTaskId(Display.DEFAULT_DISPLAY) }.getOrNull()?.takeIf { it >= 0 }

    private suspend fun sampleMain(sh: IMikaShell, stage: String) {
        val before = mainBefore ?: return
        val now = withContext(Dispatchers.IO) { mainTop(sh) }
        if (now != before && stage !in mainChanged) mainChanged += stage
    }

    private fun topPackage(sh: IMikaShell, displayId: Int): String? = runCatching {
        sh.topTaskId(displayId).takeIf { it >= 0 }?.let(sh::packageOfTask)
    }.getOrNull()

    private fun isTestPageOnTop(sh: IMikaShell, displayId: Int): Boolean =
        runCatching { sh.topActivity(displayId) }.getOrNull()?.contains(SelfCheckTargetActivity::class.java.simpleName) == true

    private suspend fun awaitFrame(d: AgentDisplay): Bitmap? {
        repeat(FRAME_TRIES) {
            d.latestFrameBitmap()?.let { return it }
            delay(POLL_MS)
        }
        return null
    }

    /** 实时画面(缩小的那一份)的指纹;还没有画面为 null。 */
    private fun frameHash(d: AgentDisplay): Int? {
        val bmp = d.frames.value ?: return null
        return runCatching {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            px.contentHashCode()
        }.getOrNull()
    }

    private suspend fun poll(timeoutMs: Long = VERIFY_MS, block: suspend () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (block()) return true
            delay(POLL_MS)
        }
        return block()
    }

    private fun ownContent(source: ScreenSnapshot): ScreenSnapshot? = selfCheckWindowContent(source, app.packageName)

    /**
     * 自检页上的一个控件:先按资源 id(读屏给的是短名,也认完整写法的结尾),再按页面上的文字(有字的控件读屏时不带资源 id)。
     * 文字和自检页一样按 App 语言取;比的时候不分大小写、不计较空白(见 [SelfCheckText])。
     */
    private fun ScreenSnapshot.find(key: String): UiElement? =
        elements.firstOrNull { e -> e.resourceId?.let { it == key || it.endsWith("/$key") } == true }
            ?: elements.firstOrNull { e ->
                when (key) {
                    TITLE -> SelfCheckText.same(e.name, tr(R.string.selfcheck_target_title))
                    COUNT -> tapsText.matches(e.name)
                    BUTTON -> e.clickable && SelfCheckText.same(e.name, tr(R.string.selfcheck_target_button))
                    INPUT -> e.editable
                    TICK -> tickText.matches(e.name)
                    LIST -> e.scrollable
                    else -> false
                }
            }

    /** 自检页上看得见的行(按从上到下)。 */
    private fun ScreenSnapshot.rows(): List<String> =
        elements.filter { rowText.matches(it.name) }.sortedBy { it.centerY }.map { it.name }

    /**
     * 读屏没找到自检页时写进报告的「看到了什么」:是自检页就写包名和前几个控件;是系统界面只写包名、个数和控件的种类或 id
     * (系统设置顶上可能有用户的名字,不写文字);别的 App 连包名都不写。
     */
    private fun seen(snap: ScreenSnapshot): String {
        val pkg = snap.packageName
        val n = snap.elements.size
        val first = snap.elements.take(SEEN_ELEMENTS)
        return when {
            pkg == app.packageName -> "saw Mika, $n elements: " + first.joinToString { (it.resourceId ?: it.name).take(SEEN_CHARS) }
            pkg == null -> "saw no readable window, $n elements"
            systemPackage(pkg) -> "saw $pkg, $n elements: " + first.joinToString { (it.resourceId ?: it.role).take(SEEN_CHARS) }
            else -> "saw another app, $n elements"
        }
    }

    private fun systemPackage(pkg: String): Boolean = pkg == SystemPickers.settingsPackage(app.packageManager) || runCatching {
        app.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** 带一个数字的文字(「点了 %d 次」「计时 %d」「第 %d 行」):按 App 语言取,用来认控件。 */
    private fun numbered(res: Int) = SelfCheckText.Numbered(tr(res, SENTINEL), SENTINEL.toString())

    private val tapsText by lazy { numbered(R.string.selfcheck_target_taps) }
    private val tickText by lazy { numbered(R.string.selfcheck_target_tick) }
    private val rowText by lazy { numbered(R.string.selfcheck_target_row) }

    private fun UiElement.number(): Int? = Regex("\\d+").findAll(SelfCheckText.digitsToAscii(name)).lastOrNull()?.value?.toIntOrNull()

    private fun reason(r: ActionResult): String = (r as? ActionResult.Failed)?.reason ?: "not on top"

    private fun format(ms: Double) = String.format(java.util.Locale.ROOT, "%.1f", ms)

    private companion object {
        const val TASK = "self-check"
        const val SANDBOX_WORKSPACE = "self-check"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val HELPER_WAIT_MS = 10_000L
        const val LATENCY_CALLS = 5
        const val SETTLE_MS = 2_000L
        const val VERIFY_MS = 2_000L
        const val POLL_MS = 100L
        const val FRAME_TRIES = 20
        const val BLANK_SPREAD = 8
        const val BACK_TRIES = 2
        /** 连收起输入法在内最多按这么多下。 */
        const val BACK_MAX_PRESSES = 5
        const val ALIVE_MS = 15_000L
        const val ALIVE_STEP_MS = 500L
        /** 这么久画面一点没变就算停了(页面上的数字每 250 毫秒跳一次)。 */
        const val STALL_MS = 3_000L
        const val COMMAND_MS = 8_000L
        const val MAX_CHARS = 4_000
        const val SETTINGS_IN_USE = "Settings is open on the main screen, so it was not opened"
        const val SETTINGS_ON_MAIN = "Settings opened on the main screen"
        /** 认系统设置的搜索入口:按资源 id 的末段,或按入口名字(中英文)。 */
        val SETTINGS_SEARCH_IDS = setOf("search", "search_icon", "search_action_bar_title", "search_button")
        val SETTINGS_SEARCH_LABELS = setOf("Search", "Search settings", "搜索", "搜索设置", "搜尋", "搜尋設定")
        /** 熄屏自检:唤醒锁上限(不点亮主屏)、等熄屏锁定的超时、各阶段的总预算。 */
        const val SCREEN_OFF_WAKE_LOCK_MS = 90_000L
        const val SCREEN_OFF_READY_MS = 45_000L
        const val SCREEN_OFF_STAGES_MS = 35_000L

        const val COUNT = "selfcheck_count"
        const val BUTTON = "selfcheck_button"
        const val INPUT = "selfcheck_input"
        const val TICK = "selfcheck_tick"
        const val LIST = "selfcheck_list"
        const val TITLE = "selfcheck_title"
        val EXPECTED = listOf(TITLE, COUNT, BUTTON, INPUT, TICK, LIST)
        const val ASCII = "Mika 123"
        const val CHINESE = "你好米卡"
        const val SENTINEL = 987_654_321
        /** 读屏一项等自检页出现最多这么久。 */
        const val PAGE_WAIT_MS = 5_000L
        const val SEEN_ELEMENTS = 5
        const val SEEN_CHARS = 24

        const val WEB_PAGE = "https://appassets.androidplatform.net/assets/selfcheck/page.html"
        const val WEB_PAGE_AR = "https://appassets.androidplatform.net/assets/selfcheck/page-ar.html"
        const val WEB_MARKER = "SELFCHECK-READ-OK"
        const val WEB_BUTTON = "#selfcheck-button"
        const val WEB_CLICKED = "Clicked 1"
        const val WEB_CLICKED_AR = "عدد النقرات: 1"
    }
}

internal data class SelfCheckWindowRead(
    val content: ScreenSnapshot?,
    val missing: List<String>,
    val wrongAppOnTop: Boolean,
) {
    val ready: Boolean get() = content != null && missing.isEmpty() && !wrongAppOnTop
}

/**
 * 把合并读屏结果收窄到 Mika 自己的应用窗口,再检查控件。只有 Mika 仍是最上层任务时,
 * 系统应用的覆盖窗口才不影响自检;完整系统应用有自己的顶层任务,仍算切走。
 */
internal fun readSelfCheckWindow(
    screen: ScreenSnapshot,
    packageName: String,
    topTaskPackage: String?,
    expected: List<String>,
    isSystemPackage: (String) -> Boolean,
    find: ScreenSnapshot.(String) -> UiElement?,
): SelfCheckWindowRead {
    val content = selfCheckWindowContent(screen, packageName)
    val missing = expected.filter { key -> content?.let { it.find(key) } == null }
    val top = screen.packageName
    val wrongApp = when {
        topTaskPackage != null && topTaskPackage != packageName -> true
        top == null || top == packageName -> false
        !isSystemPackage(top) -> true
        // 任务归属读不到时不能把系统窗口认定为覆盖层,保持失败。
        else -> topTaskPackage != packageName
    }
    return SelfCheckWindowRead(content, missing, wrongApp)
}

/** 原始快照的编号不变,只把元素和窗口树收窄到指定 App 的应用窗口。 */
internal fun selfCheckWindowContent(screen: ScreenSnapshot, packageName: String): ScreenSnapshot? {
    val ownWindows = screen.windows.filter { it.type == "application" && it.packageName == packageName }
    if (ownWindows.isEmpty()) return null
    val refs = buildSet {
        fun visit(nodes: List<UiTreeNode>) {
            for (node in nodes) {
                node.ref?.let(::add)
                visit(node.children)
            }
        }
        ownWindows.forEach { visit(it.nodes) }
    }
    return screen.copy(
        packageName = packageName,
        elements = screen.elements.filter { it.ref in refs },
        windows = ownWindows,
    )
}
