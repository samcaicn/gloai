package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.device.access.AppAccessPolicy
import com.wangzan101.mika.core.screen.uistate.AfterTaskMarks
import com.wangzan101.mika.core.screen.uistate.AppCopy
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.SystemCommandResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import com.wangzan101.mika.core.screen.uistate.WheelOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 操作手机 App 需要的(内置助手、无障碍)此刻没有;[hint] 是告诉模型和用户怎么弄好的一句话。 */
class PhoneUnavailable(val hint: String) : Exception(hint)

/**
 * 用到时才接上的手机端口:第一次 launch_app / open_link,或系统命令、事件工具时才调 [acquire](连内置助手、等无障碍、备好虚拟屏;屏在这些动作用到时才建);
 * 只用网页或沙箱的任务从头到尾不需要内置助手和无障碍。没接上之前读屏是一块空屏、其余动作报 no_app_open,
 * 和接上后还没打开 App 时一样。接不上时这次动作报 phone_unavailable(附怎么弄好),下次再试。
 * 装了哪些 App 只问包管理器([listApps]),不用接上;另一份用户里的 App 没接上时问 [appCopies](内置助手已连着才有,不为它去接)。
 */
class LazyPhoneScreen(
    private val width: Int,
    private val height: Int,
    private val listApps: suspend () -> List<Pair<String, String>>,
    private val appCopies: suspend () -> List<AppCopy> = { emptyList() },
    override val canShareAudio: Boolean = false,
    private val calendarBulkWriter: (suspend (List<SystemAction.AddCalendarEvent>) -> SystemActionResult)? = null,
    private val acquire: suspend () -> ScreenPort,
) : ScreenPort {
    private val lock = Mutex()
    @Volatile private var port: ScreenPort? = null

    /** 模型标下的任务结束时各 App 的去留;收尾时由任务交给内置助手执行(没接上过手机就没有可处理的)。 */
    override val afterTask = AfterTaskMarks()

    /** 本任务用过手机(已接上)。 */
    val acquired: Boolean get() = port != null

    private suspend fun built(): ScreenPort? = lock.withLock {
        port ?: try {
            acquire().also { port = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            lastProblem = e.hint
            null
        }
    }

    @Volatile private var lastProblem: String? = null

    private fun unavailable() = ActionResult.Failed(PHONE_UNAVAILABLE, lastProblem)

    override val appAccess: AppAccessPolicy get() = port?.appAccess ?: AppAccessPolicy.ALLOW
    override suspend fun checkAppAccess() { port?.checkAppAccess() }

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = built()?.openApp(packageName, cold) ?: unavailable()
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        built()?.openAppCopy(packageName, userId, cold) ?: unavailable()
    // 被别的任务占着才会等,那时手机早已接上;没接上就没什么可等
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait =
        port?.awaitAppFree(packageName, userId, timeoutMs) ?: AppWait.Unsupported
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult =
        built()?.openLink(action, uri, packageName) ?: unavailable()
    override suspend fun shareText(packageName: String, text: String): ActionResult = built()?.shareText(packageName, text) ?: unavailable()
    override suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult =
        built()?.shareAudio(packageName, path, text) ?: unavailable()
    override suspend fun systemAction(action: SystemAction): SystemActionResult = built()?.systemAction(action) ?: SystemActionResult(unavailable())
    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        calendarBulkWriter?.invoke(events)
            ?: built()?.bulkCalendarInsert(events)
            ?: SystemActionResult(unavailable())
    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        port?.setWheel(ref, snapshotId, value) ?: WheelOutcome(false, null, null, 0, "none", error = "no_app_open")

    // ---------- 没接上之前:还没有打开的 App ----------

    override suspend fun snapshot(): ScreenSnapshot = port?.snapshot() ?: empty()
    override suspend fun peek(): ScreenSnapshot = port?.peek() ?: empty()
    override suspend fun screenshotPng(): ByteArray? = port?.screenshotPng()
    override suspend fun readVisibleText(): List<VisibleTextLine>? = port?.readVisibleText()
    override suspend fun tapRef(ref: String, snapshotId: String) = port?.tapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun tapXY(x: Int, y: Int) = port?.tapXY(x, y) ?: NO_APP_OPEN
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = port?.doubleTapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun doubleTapXY(x: Int, y: Int) = port?.doubleTapXY(x, y) ?: NO_APP_OPEN
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = port?.typeText(ref, snapshotId, text) ?: NO_APP_OPEN
    override suspend fun clearText(ref: String?, snapshotId: String?) = port?.clearText(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun typeAt(x: Int, y: Int, text: String) = port?.typeAt(x, y, text) ?: NO_APP_OPEN
    override suspend fun clearAt(x: Int, y: Int) = port?.clearAt(x, y) ?: NO_APP_OPEN
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = port?.scroll(ref, snapshotId, down) ?: NO_APP_OPEN
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = port?.swipe(x1, y1, x2, y2, durationMs) ?: NO_APP_OPEN
    override suspend fun idleScroll(fraction: Double): Boolean = port?.idleScroll(fraction) ?: false
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = port?.gesture(paths, durationMs) ?: NO_APP_OPEN
    override suspend fun back() = port?.back() ?: NO_APP_OPEN
    override suspend fun pressKey(keyCode: Int) = port?.pressKey(keyCode) ?: NO_APP_OPEN
    override suspend fun waitStable(timeoutMs: Long) = port?.waitStable(timeoutMs) ?: true
    override suspend fun awaitChange(timeoutMs: Long): Boolean = port?.awaitChange(timeoutMs) ?: super.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = port?.waitFor(condition, value, timeoutMs) ?: false
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        val screen = built() ?: return null
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.nextEvent(kind, packageName, timeoutMs)
    }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        val screen = built() ?: return emptyList()
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.watchEvents(kind, packageName, durationMs, maxEvents)
    }
    override suspend fun systemCommand(command: String): SystemCommandResult? {
        val screen = built() as? AndroidScreen ?: return null
        screen.ensureDisplay()
        return screen.systemCommand(command)
    }
    override suspend fun listApps(): List<Pair<String, String>> = port?.listApps() ?: listApps.invoke()
    // 要问内置助手:没接上之前不为它去接(接不上会请用户设置),打开过 App 之后才有
    override suspend fun otherProfiles(): Map<String, List<String>> = port?.otherProfiles() ?: emptyMap()
    override suspend fun appCopies(): List<AppCopy> = port?.appCopies() ?: appCopies.invoke()
    override suspend fun checkScreen(): ScreenCheck? = port?.checkScreen()
    override suspend fun isProtected() = port?.isProtected() ?: false
    override suspend fun frameGrid() = port?.frameGrid()

    private fun empty() = ScreenSnapshot(snapshotId(), null, emptyList(), width, height, hasWindow = false)

    companion object {
        const val PHONE_UNAVAILABLE = "phone_unavailable"
        private val NO_APP_OPEN = ActionResult.Failed("no_app_open")
    }
}
