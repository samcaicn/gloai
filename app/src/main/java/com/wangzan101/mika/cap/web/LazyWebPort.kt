package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.device.access.AppAccessPolicy
import com.wangzan101.mika.core.screen.uistate.AfterTaskMarks
import com.wangzan101.mika.core.screen.uistate.AppCopy
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import com.wangzan101.mika.core.screen.uistate.SystemCommandResult
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.WheelOutcome
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.EvalJs
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.cap.web.NavResult
import com.wangzan101.mika.core.cap.web.PageRead
import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptRun
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebPoint
import com.wangzan101.mika.core.cap.web.WaitKind
import com.wangzan101.mika.core.cap.web.WaitOutcome
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用到时才建的网页端口(和手机虚拟屏到第一次 launch_app 才建同一个道理):第一次打开网址时才调 [create] 建后台网页显示、标签池和脚本引擎;
 * 只用手机的任务从头到尾不建。没建之前,其余方法都按「还没有打开的网页」回答,和建好后没有标签时一样。
 * 建不起来(系统 WebView 出问题)时这次打开网址按标签崩溃报(tab_crashed),下次再试。
 */
class LazyWebPort(private val create: suspend () -> WebPort) : WebPort {
    private val lock = Mutex()
    @Volatile private var port: WebPort? = null

    val created: Boolean get() = port != null

    private suspend fun built(): WebPort = lock.withLock {
        port ?: try {
            create().also { port = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WebTabCrashed(UNAVAILABLE).apply { initCause(e) }
        }
    }

    override suspend fun navigate(url: String, newTab: Boolean): NavResult = built().navigate(url, newTab)

    override val appAccess: AppAccessPolicy get() = port?.appAccess ?: AppAccessPolicy.ALLOW
    override suspend fun checkAppAccess() { port?.checkAppAccess() }

    /** 现在就建好(任务开局就要用网页时,如借来了用户看着的页面);建不起来返回 false,下次用到时再试。 */
    suspend fun prepare(): Boolean = try {
        built()
        true
    } catch (e: WebTabCrashed) {
        false
    }

    // ---------- 没建之前:没有打开的网页 ----------

    override suspend fun snapshot(): ScreenSnapshot = port?.snapshot() ?: EMPTY
    override suspend fun peek(): ScreenSnapshot = port?.peek() ?: EMPTY
    override suspend fun screenshotPng(): ByteArray? = port?.screenshotPng()
    override suspend fun readVisibleText(): List<VisibleTextLine>? = port?.readVisibleText()
    override suspend fun tapRef(ref: String, snapshotId: String) = port?.tapRef(ref, snapshotId) ?: NO_TAB
    override suspend fun tapXY(x: Int, y: Int) = port?.tapXY(x, y) ?: NO_TAB
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = port?.doubleTapRef(ref, snapshotId) ?: NO_TAB
    override suspend fun doubleTapXY(x: Int, y: Int) = port?.doubleTapXY(x, y) ?: NO_TAB
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = port?.typeText(ref, snapshotId, text) ?: NO_TAB
    override suspend fun clearText(ref: String?, snapshotId: String?) = port?.clearText(ref, snapshotId) ?: NO_TAB
    override suspend fun typeAt(x: Int, y: Int, text: String) = port?.typeAt(x, y, text) ?: NO_TAB
    override suspend fun clearAt(x: Int, y: Int) = port?.clearAt(x, y) ?: NO_TAB
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = port?.scroll(ref, snapshotId, down) ?: NO_TAB
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = port?.swipe(x1, y1, x2, y2, durationMs) ?: NO_TAB
    override suspend fun idleScroll(fraction: Double): Boolean = port?.idleScroll(fraction) ?: false
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult =
        port?.gesture(paths, durationMs) ?: ActionResult.Failed("unsupported")
    override suspend fun back() = port?.back() ?: NO_TAB
    override suspend fun pressKey(keyCode: Int) = port?.pressKey(keyCode) ?: NO_TAB
    override suspend fun openApp(packageName: String, cold: Boolean) = port?.openApp(packageName, cold) ?: NO_TAB
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        port?.openAppCopy(packageName, userId, cold) ?: ActionResult.Failed("unsupported")
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait =
        port?.awaitAppFree(packageName, userId, timeoutMs) ?: AppWait.Unsupported
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult =
        port?.openLink(action, uri, packageName) ?: ActionResult.Failed("unsupported")
    override suspend fun shareText(packageName: String, text: String): ActionResult =
        port?.shareText(packageName, text) ?: ActionResult.Failed("unsupported")
    override val canShareAudio: Boolean get() = port?.canShareAudio ?: false
    override suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult =
        port?.shareAudio(packageName, path, text) ?: ActionResult.Failed("unsupported")
    override suspend fun systemAction(action: SystemAction): SystemActionResult =
        port?.systemAction(action) ?: SystemActionResult(ActionResult.Failed("unsupported"))
    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        port?.bulkCalendarInsert(events) ?: SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        port?.setWheel(ref, snapshotId, value) ?: WheelOutcome(false, null, null, 0, "none", error = "unsupported")
    override suspend fun waitStable(timeoutMs: Long) = port?.waitStable(timeoutMs) ?: true
    override suspend fun awaitChange(timeoutMs: Long): Boolean = port?.awaitChange(timeoutMs) ?: super.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean =
        port?.waitFor(condition, value, timeoutMs) ?: false
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) =
        port?.nextEvent(kind, packageName, timeoutMs)
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int) =
        port?.watchEvents(kind, packageName, durationMs, maxEvents) ?: emptyList()
    override suspend fun systemCommand(command: String): SystemCommandResult? = port?.systemCommand(command)
    override suspend fun listApps(): List<Pair<String, String>> = port?.listApps().orEmpty()
    override suspend fun otherProfiles(): Map<String, List<String>> = port?.otherProfiles().orEmpty()
    override suspend fun appCopies(): List<AppCopy> = port?.appCopies().orEmpty()
    override val afterTask: AfterTaskMarks? get() = port?.afterTask
    override suspend fun checkScreen(): ScreenCheck? = port?.checkScreen()
    override suspend fun isProtected() = port?.isProtected() ?: false
    override suspend fun frameGrid() = port?.frameGrid()
    override suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean): PageText =
        port?.readPage(maxChars, offset, scrollLazy) ?: PageText("", "", "", emptyList(), emptyList(), false, null)
    /** 读一个网址(后台标签)和打开网址一样要先建好;读当前标签没建之前是空页。 */
    override suspend fun read(req: PageRead): PageText =
        if (req.url != null) built().read(req) else port?.read(req) ?: PageText("", "", "", emptyList(), emptyList(), false, null)
    override suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject?): EvalJs =
        port?.evaluate(script, timeoutMs, args) ?: EvalJs.Failed(NO_TAB_CODE, "No web page is open.")
    override suspend fun tabs(): List<TabInfo> = port?.tabs().orEmpty()
    override suspend fun switchTab(tabId: String): TabInfo? = port?.switchTab(tabId)
    override suspend fun closeTab(tabId: String) = port?.closeTab(tabId) ?: false
    override suspend fun downloads(): List<DownloadInfo> = port?.downloads() ?: throw UnsupportedOperationException("downloads are not available yet")
    override suspend fun upload(ref: String, snapshotId: String, paths: List<String>) = port?.upload(ref, snapshotId, paths) ?: NO_TAB
    override suspend fun historyBack() = port?.historyBack() ?: NO_TAB
    override suspend fun setSiteMode(mobile: Boolean) = port?.setSiteMode(mobile)
    override suspend fun siteMode(): String? = port?.siteMode()
    override suspend fun keepOpen(keep: Boolean, reason: String) = port?.keepOpen(keep, reason)
    override suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun =
        port?.runScript(code, timeoutMs, bridge) ?: ScriptRun.Failed(NO_TAB_CODE, "No web page is open.")
    override suspend fun hover(at: WebPoint) = port?.hover(at) ?: NO_TAB
    override suspend fun drag(from: WebPoint, to: WebPoint, steps: Int) = port?.drag(from, to, steps) ?: NO_TAB
    override suspend fun selectOption(ref: String, snapshotId: String, option: String) = port?.selectOption(ref, snapshotId, option) ?: NO_TAB
    override suspend fun pressCombo(combo: KeyCombo) = port?.pressCombo(combo) ?: NO_TAB
    override suspend fun answerDialog(accept: Boolean, text: String?) = port?.answerDialog(accept, text) ?: NO_TAB
    override suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> =
        port?.captures(urlContains, method, sinceLastAction).orEmpty()
    override suspend fun capture(id: String): CapturedResponse? = port?.capture(id)
    override suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome =
        port?.waitFor(kind, value, timeoutMs) ?: WaitOutcome(kind == WaitKind.TEXT_ABSENT, 0, null)

    private companion object {
        const val UNAVAILABLE = "browser"
        const val NO_TAB_CODE = "no_tab_open"
        val NO_TAB = ActionResult.Failed(NO_TAB_CODE)
        val EMPTY = ScreenSnapshot("w-none-empty", null, emptyList(), 0, 0, hasWindow = false)
    }
}
