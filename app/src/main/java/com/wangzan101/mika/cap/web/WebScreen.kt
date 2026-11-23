package com.wangzan101.mika.cap.web

import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.wangzan101.mika.core.cap.web.cdp.CdpGeometry
import com.wangzan101.mika.core.screen.uistate.CdpKeys
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.cap.web.cdp.InputTrack
import com.wangzan101.mika.core.cap.web.cdp.SelectOutcome
import com.wangzan101.mika.core.cap.web.cdp.input
import com.wangzan101.mika.core.cap.web.cdp.InputPacing
import com.wangzan101.mika.core.cap.web.cdp.mouseWheel
import com.wangzan101.mika.core.cap.web.cdp.wheelInPlace
import com.wangzan101.mika.core.cap.web.cdp.typeText
import com.wangzan101.mika.core.cap.web.cdp.mouseClick
import com.wangzan101.mika.core.cap.web.cdp.mouseDoubleClick
import com.wangzan101.mika.core.cap.web.cdp.mouseDrag
import com.wangzan101.mika.core.cap.web.cdp.mouseMove
import com.wangzan101.mika.core.cap.web.cdp.pressCombo
import com.wangzan101.mika.core.cap.web.cdp.selectOptionAt
import com.wangzan101.mika.core.cap.web.cdp.viewportCssWidth
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.visual.FrameGrid
import com.wangzan101.mika.core.screen.uistate.ElementLocator
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.DevtoolsUnavailable
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.EvalJs
import com.wangzan101.mika.core.cap.web.NavResult
import com.wangzan101.mika.core.cap.web.cdp.CdpConnectionClosedException
import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.PageRead
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptRun
import com.wangzan101.mika.cap.web.cdp.PageScriptRunner
import com.wangzan101.mika.core.cap.web.cdp.ReadScriptRunner
import com.wangzan101.mika.core.cap.web.SiteModeResult
import com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS
import com.wangzan101.mika.core.cap.web.KeepOpenResult
import com.wangzan101.mika.core.cap.web.WebTabIsUsers
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebBarrier
import com.wangzan101.mika.core.cap.web.WebFacts
import com.wangzan101.mika.core.cap.web.DialogAnswer
import com.wangzan101.mika.core.cap.web.NO_DIALOG
import com.wangzan101.mika.core.cap.web.WebDialogOpen
import com.wangzan101.mika.core.cap.web.WebPoint
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import com.wangzan101.mika.core.cap.web.WebTabHandedOver
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.cap.web.SettlePolicy
import com.wangzan101.mika.core.cap.web.WaitKind
import com.wangzan101.mika.core.cap.web.WaitOutcome
import com.wangzan101.mika.cap.web.cdp.NetworkTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 网页端口:在当前标签上实现和手机同一套读屏与动作。
 * 点击、滑动、打字、按键都走真实输入,事后诚实核实。标签有 DevTools 会话时点击、按键、插入文字走它的鼠标键盘(桌面版网页要真实鼠标语义),
 * 没有或调用失败时走 [WebInput] 的原生触摸和按键;滚动始终走原生滑动(DevTools 滚轮在后台标签会卡死页面输入)。核实:
 * 「变了没有」比较地址、页面变动计数和元素树指纹,等页面安静(连续 500 毫秒无变动且主文档不在加载)、至多 3 秒;
 * 标签有 DevTools 会话时,本次动作发出的请求还在途就再等它们、合计至多 5 秒([settle]),等不到在结果里记 settled=false;
 * 换了主机名记在 appSwitched;打字后回读,折叠空白后整段比较,密码框不读值(UNKNOWN)。
 * 编号:snapshotId 形如 w<标签>-<n>,页面脚本按快照号保留编号表;元素已不在时按角色、名字、位置在新树里找唯一对应者。
 * 标签由 [currentTab] 提供(标签池接手后是当前任务的当前标签)。
 * 当前标签是借来的(用户在浏览器页上看着的那一个,见 [WebTabPool.attachExternal])时:它不在网页显示上,截图直接画这个视图;
 * 读屏前把用户的双指放大缩回整页(编号的坐标按整页算);停留时不轻滚;打开网址另开新标签,不把用户的这一页换走。
 * 读脚本(web_evaluate_js、站点命令)在当前标签的读取世界里只读地跑,每个标签一个 [ReadScriptRunner]。
 */
class WebScreen(
    internal val currentTab: () -> WebTab?,
    internal val host: WebHost,
    internal val clock: () -> Long,
    internal val pool: WebTabPool? = null,
    internal val taskId: String? = null,
    /** 下载与上传走的工作区;为空时下载报不可用、上传报不可用。 */
    internal val files: WebFiles? = null,
    /** 此刻留在后台的那一页(见 [PageKeeper]);为空时不能留页,web_keep_open 报不可用。 */
    internal val keptNow: (() -> KeptPage?)? = null,
) : WebPort {
    /** 一个任务的网页端口:当前标签取自标签池里这个任务的当前标签。 */
    constructor(pool: WebTabPool, taskId: String, host: WebHost, clock: () -> Long, files: WebFiles? = null, keptNow: (() -> KeptPage?)? = null) :
        this({ pool.current(taskId) }, host, clock, pool, taskId, files, keptNow)

    /** 模型要在任务结束后留下的那一页(web_keep_open):[tabId] 是哪个标签;任务结束时由 [WebStack.close] 取。 */
    data class KeepMark(val tabId: String, val reason: String)

    @Volatile var keepMark: KeepMark? = null
        internal set

    internal val scripts = WeakHashMap<WebTab, PageScript>()
    internal val readers = WeakHashMap<WebTab, ReadScriptRunner>()
    internal val inputs = WeakHashMap<WebTab, WebInput>()
    /** Input 调用超时过的会话:后台页面的 Input 可能整体卡住,同一个会话不再用来输入,免得每个动作都等超时。 */
    internal val inputStuck = WeakHashMap<WebTab, CdpSession>()
    /** DevTools 鼠标键盘的节奏:轨迹、按住时长、键间隔、滚轮分格(见 [InputPacing])。 */
    internal val pacing = InputPacing.human()
    internal val history = ArrayDeque<Snap>()
    internal var counter = 0
    internal var internalReads = 0

    /**
     * 一次读屏:交给模型的元素、认元素用的特征、CSS 像素到视图像素的倍数。[key] 是页面里编号表的名字。
     * 经过元素融合时编号重排:[pageRefs] 是快照编号 → 页面脚本编号表里的编号(null 表示两者相同),
     * 只有无障碍树有的元素不在里面,改用 [anchors] 经 DevTools 找;[backendIds] 是能对上的 DOM 节点号。
     */
    internal class Snap(
        val id: String,
        val tab: WebTab,
        val key: String?,
        val snapshot: ScreenSnapshot,
        val locators: List<ElementLocator>,
        val scale: Double,
        val mutations: Long,
        val pageRefs: Map<String, String>? = null,
        val backendIds: Map<String, Int> = emptyMap(),
        val anchors: Map<String, ElementProbe.Anchor> = emptyMap(),
    ) {
        fun pageRef(ref: String): String? = if (pageRefs == null) ref else pageRefs[ref]
    }

    /**
     * 编号解析的结果:页面里的编号表 [key] 与编号 [ref];经过重定位时 [relocatedTo] 是找回的元素现在的名字。
     * [devtools] 为真表示元素只有无障碍树知道(如跨源框架里的),[ref] 不在页面脚本的表里,
     * 点的位置是 [point](视图像素,不在视口里为 null),[covered] 为真表示命中检测落在别的元素上。
     */
    internal class Target(
        val key: String,
        val ref: String,
        val element: UiElement,
        val relocatedTo: String?,
        val scale: Double,
        val devtools: Boolean = false,
        val point: Pair<Int, Int>? = null,
        val covered: Boolean = false,
        /** [point] 对应的顶层视口 CSS 像素(DevTools 输入用)。 */
        val cssPoint: Pair<Int, Int>? = null,
    )

    internal data class PageState(val url: String?, val mutations: Long, val fingerprint: Int)

    internal sealed interface Ready {
        class Tab(val tab: WebTab) : Ready
        class Fail(val result: ActionResult) : Ready
    }

    @Synchronized
    internal fun script(tab: WebTab) = scripts.getOrPut(tab) { PageScript(tab) }

    @Synchronized
    internal fun input(tab: WebTab) = inputs.getOrPut(tab) { WebInput(tab) }

    /** 页面弹框挂着时页面脚本停着:要读页面的调用一律立刻报 dialog_open,不去等超时。 */
    internal fun dialogGuard(tab: WebTab) {
        if (tab.dialogs.current != null) throw WebDialogOpen(tab.id)
    }

    override suspend fun snapshot(): ScreenSnapshot {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return emptySnapshot(currentTab())
        if (borrowed(tab)) unzoom(tab)
        val id = "w${tab.id}-${nextId()}"
        val snap = read(tab, id, id, fuse = true)
        synchronized(history) {
            history.addFirst(snap)
            while (history.size > KEEP_SNAPSHOTS) history.removeLast()
        }
        return snap.snapshot
    }

    /** 读一屏但不登记编号:模型手里的编号不受影响。 */
    override suspend fun peek(): ScreenSnapshot {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return emptySnapshot(currentTab())
        return read(tab, null, "w${tab.id}-peek", fuse = true).snapshot
    }

    /** 这一屏里 [ref] 对应的 DOM 节点号(backendNodeId);没登记、对不上、没走 DevTools 时为 null。 */
    internal fun backendNodeId(ref: String, snapshotId: String): Int? =
        synchronized(history) { history.firstOrNull { it.id == snapshotId } }?.backendIds?.get(ref)

    override suspend fun screenshotPng(): ByteArray? {
        val tab = currentTab()?.takeIf { !it.crashed && borrowed(it) } ?: return withContext(Dispatchers.IO) { host.latestFramePng() }
        val bmp = viewFrame(tab) ?: return null
        return withContext(Dispatchers.IO) {
            java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            }
        }
    }

    override suspend fun frameGrid(): FrameGrid? {
        val tab = currentTab()?.takeIf { !it.crashed && borrowed(it) }
        val bmp = (if (tab != null) viewFrame(tab) else withContext(Dispatchers.IO) { host.latestFrame() }) ?: return null
        return withContext(Dispatchers.IO) { try { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) } finally { bmp.recycle() } }
    }

    override suspend fun checkScreen(): ScreenCheck {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        val quiet = awaitQuiet(tab, QUIET_MS, CAP_MS)
        val snap = read(tab, null, "w${tab.id}-check").snapshot
        // 弹框挂着时页面脚本读不动:只按读屏(里面带着弹框)判
        val facts = if (tab.dialogs.current != null) PageFacts(false, emptyList()) else script(tab).facts()
        val site = snap.packageName
        return WebBarrier.classify(
            WebFacts(
                base = ScreenFacts(
                    currentApp = site,
                    hasWindow = true,
                    readableElements = snap.elements.count { it.meaningful },
                    changing = !quiet,
                    blackFrames = emptyList(),
                    texts = snap.elements.map { it.name }.filter { it.isNotBlank() },
                    passwordField = facts.passwordField,
                ),
                httpStatus = tab.httpErrorStatus,
                frameUrls = facts.frameUrls,
                host = site,
            ),
        )
    }

    override suspend fun waitStable(timeoutMs: Long): Boolean {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return true
        return awaitQuiet(tab, QUIET_MS, timeoutMs)
    }

    /** 页面的下一次变动(文档变动计数、地址、加载中、弹框),和等安静同一套信号;没有标签时等满时限。 */
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        // 页面弹框挂着时页面停着,不会再变,脚本也读不动
        val tab = currentTab()?.takeIf { !it.crashed && it.dialogs.current == null } ?: run { delay(timeoutMs.coerceAtLeast(0)); return false }
        val s = script(tab)
        val deadline = clock() + timeoutMs
        val mut = s.mutationCount()
        val was = url(tab) to tab.loading
        while (clock() < deadline) {
            delay(POLL_MS)
            if (tab.crashed || tab.dialogs.current != null || (url(tab) to tab.loading) != was || s.mutationCount() != mut) return true
        }
        return false
    }

    // ---------- 动作 ----------

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val t = resolve(tab, ref, snapshotId) ?: return STALE
        val p = when (val hit = reachable(tab, t)) {
            is Reach.Point -> hit
            is Reach.Blocked -> return hit.result
        }
        return verified(tab, t.relocatedTo) {
            click(tab, p)
            true
        }
    }

    override suspend fun tapXY(x: Int, y: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            val clicked = cdp(tab, "click") {
                val k = cssScale(tab)
                mouseClick(CdpGeometry.toCss(x, k), CdpGeometry.toCss(y, k))
            }
            if (!clicked) input(tab).tap(x, y)
            true
        }
    }

    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val t = resolve(tab, ref, snapshotId) ?: return STALE
        val p = when (val hit = reachable(tab, t)) {
            is Reach.Point -> hit
            is Reach.Blocked -> return hit.result
        }
        return verified(tab, t.relocatedTo) {
            if (!cdp(tab, "double click") { mouseDoubleClick(p.cssX.toDouble(), p.cssY.toDouble()) }) nativeDoubleTap(tab, p.x, p.y)
            true
        }
    }

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            val clicked = cdp(tab, "double click") {
                val k = cssScale(tab)
                mouseDoubleClick(CdpGeometry.toCss(x, k), CdpGeometry.toCss(y, k))
            }
            if (!clicked) nativeDoubleTap(tab, x, y)
            true
        }
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            input(tab).swipe(x1, y1, x2, y2, durationMs)
            true
        }
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val s = script(tab)
        val (w, h) = viewSize(tab)
        val target = if (ref != null && snapshotId != null) resolve(tab, ref, snapshotId) ?: return STALE else null
        val cx: Int
        val cy: Int
        val distance: Int
        if (target != null) {
            val c = if (target.devtools) target.point ?: return STALE
            else s.centerOf(target.ref, target.key)?.let { px(it.first, target.scale) to px(it.second, target.scale) } ?: return STALE
            cx = c.first
            cy = c.second
            val heightPx = (target.element.bounds[3] - target.element.bounds[1]) * (h - 1) / 999
            distance = min(heightPx * 6 / 10, h / 2).coerceAtLeast(MIN_SCROLL_PX)
        } else {
            cx = w / 2
            cy = h / 2
            distance = h * 4 / 10
        }
        val before = s.scrollState(target?.ref, target?.key ?: PageScript.DEFAULT_KEY)
        // 桌面版且标签正在显示:用鼠标滚轮一格一格地滚(桌面身份不出触摸);否则、或滚轮发不出去时用原生滑动
        val wheeled = wheelable(tab) && try {
            cdp(tab, "wheel") {
                val k = cssScale(tab)
                mouseWheel(CdpGeometry.toCss(cx, k), CdpGeometry.toCss(cy, k), (if (down) distance else -distance) / k)
            }
        } catch (e: InputUncertain) {
            // 滚动不是不可逆的动作:不重做,照常比较前后
            true
        }
        if (!wheeled) {
            val (from, to) = if (down) cy + distance / 2 to cy - distance / 2 else cy - distance / 2 to cy + distance / 2
            input(tab).swipe(cx, from, cx, to, SCROLL_SWIPE_MS)
        }
        awaitQuiet(tab, SCROLL_QUIET_MS, SCROLL_CAP_MS)
        val after = s.scrollState(target?.ref, target?.key ?: PageScript.DEFAULT_KEY)
        return ActionResult.Ok(
            changed = before != after,
            relocated = target?.relocatedTo != null,
            relocatedTo = target?.relocatedTo,
        )
    }

    /**
     * 停留时顺手轻轻滚一下整页:有页面弹框、输入法开着、输入框拿着焦点、标签不在显示时不滚。
     * 桌面版用滚轮(指针不动),否则用一下慢慢的原生滑动;都不移动鼠标、不点。
     */
    override suspend fun idleScroll(fraction: Double): Boolean {
        // 用户看着的那一页不替他滚
        val tab = (ready() as? Ready.Tab)?.tab?.takeIf { !borrowed(it) } ?: return false
        val shown = withContext(Dispatchers.Main) {
            val v = tab.view
            // WindowInsets.Type 是安卓 11 起才有的;更早的系统不判断输入法,其余条件照旧。
            val ime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                v.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            v.isAttachedToWindow && v.isShown && v.windowVisibility == android.view.View.VISIBLE && !ime
        }
        if (!shown) return false
        if (script(tab).fieldValue(null) !is FieldValue.NotAField) return false
        val (w, h) = viewSize(tab)
        val distance = (h * kotlin.math.abs(fraction)).roundToInt()
        if (distance < 1) return false
        val down = fraction > 0
        val wheeled = wheelable(tab) && try {
            cdp(tab, "idle wheel") {
                val k = cssScale(tab)
                wheelInPlace(CdpGeometry.toCss(w / 2, k), CdpGeometry.toCss(h / 2, k), (if (down) distance else -distance) / k)
            }
        } catch (e: InputUncertain) {
            true
        }
        if (!wheeled) {
            val cy = h / 2
            val (from, to) = if (down) cy + distance / 2 to cy - distance / 2 else cy - distance / 2 to cy + distance / 2
            input(tab).swipe(w / 2, from, w / 2, to, IDLE_SWIPE_MS)
        }
        return true
    }

    override suspend fun back(): ActionResult = historyBack()

    override suspend fun historyBack(): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        if (!withContext(Dispatchers.Main) { tab.view.canGoBack() }) return ActionResult.Failed(NO_HISTORY)
        return verified(tab) {
            withContext(Dispatchers.Main) { tab.view.goBack() }
            true
        }
    }

    override suspend fun pressKey(keyCode: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        if (keyCode !in ALLOWED_KEYS) return ActionResult.Failed(KEY_NOT_ALLOWED)
        return verified(tab) {
            val k = CdpKeys.forAndroid(keyCode)
            if (k == null || !cdp(tab, "key") { pressCombo(KeyCombo(emptyList(), k)) }) input(tab).key(keyCode)
            true
        }
    }

    // ---------- web_interact ----------

    override suspend fun hover(at: WebPoint): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        devtools(tab) ?: return ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        val p = when (val c = cssPoint(tab, at)) { is Spot.Fail -> return c.result; is Spot.At -> c }
        return verified(tab, p.relocatedTo, DEVTOOLS_UNAVAILABLE) { cdp(tab, "hover") { mouseMove(p.x, p.y) } }
    }

    /** 先解析终点(会把它滚进视口)再解析起点,最后再量一次终点;两头不能同时在视口里(相距太远)时如实报 offscreen。 */
    override suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        devtools(tab) ?: return ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        (cssPoint(tab, to) as? Spot.Fail)?.let { return it.result }
        val a = when (val c = cssPoint(tab, from)) { is Spot.Fail -> return c.result; is Spot.At -> c }
        val b = when (val c = cssPoint(tab, to)) { is Spot.Fail -> return c.result; is Spot.At -> c }
        var native = false
        val r = verified(tab, a.relocatedTo ?: b.relocatedTo, DEVTOOLS_UNAVAILABLE) {
            cdp(tab, "drag") { native = mouseDrag(a.x, a.y, b.x, b.y, steps) }
        }
        return if (native && r is ActionResult.Ok) r.copy(hint = listOfNotNull(r.hint, "Dropped with the page's own drag and drop.").joinToString(" ")) else r
    }

    override suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        devtools(tab) ?: return ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        val p = when (val c = cssPoint(tab, WebPoint.Ref(ref, snapshotId))) { is Spot.Fail -> return c.result; is Spot.At -> c }
        var outcome: SelectOutcome? = null
        val r = verified(tab, p.relocatedTo, DEVTOOLS_UNAVAILABLE) {
            cdp(tab, "select") { outcome = selectOptionAt(p.x, p.y, option) }
        }
        return when (val o = outcome) {
            null -> r
            is SelectOutcome.Selected -> if (r !is ActionResult.Ok) r else r.copy(
                changed = r.changed == true || o.changed,
                verification = Verification.MATCH,
                hint = listOfNotNull(
                    r.hint,
                    "Selected ${JsonPrimitive(o.text)} (option text comes from the page)." + if (o.changed) "" else " It was already selected.",
                    "The arrow keys did not change it, so it was set by script; check that the page reacted.".takeIf { o.byScript },
                ).joinToString(" "),
            )
            SelectOutcome.NotASelect -> ActionResult.Failed(NOT_A_SELECT)
            SelectOutcome.Disabled -> ActionResult.Failed(SELECT_DISABLED)
            is SelectOutcome.OptionDisabled -> ActionResult.Failed(OPTION_DISABLED, hint = JsonPrimitive(o.text).toString())
            is SelectOutcome.NotApplied -> ActionResult.Failed(SELECT_NOT_APPLIED)
            is SelectOutcome.NoSuchOption -> ActionResult.Failed(
                NO_SUCH_OPTION,
                hint = "Options: " + o.available.take(MAX_LISTED_OPTIONS).joinToString(", ") { JsonPrimitive(it).toString() } +
                    if (o.available.size > MAX_LISTED_OPTIONS) " and ${o.available.size - MAX_LISTED_OPTIONS} more." else ".",
            )
        }
    }

    /** 组合键:有 DevTools 时走它的键盘(修饰键各自按下抬起),否则原生按键带修饰状态。 */
    override suspend fun answerDialog(accept: Boolean, text: String?): ActionResult {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return ActionResult.Failed(if (crashedTab() != null) TAB_CRASHED else NO_TAB)
        if (pool?.isLocked(tab.id) == true) return ActionResult.Failed(HANDED_OVER)
        val before = url(tab)
        val answered = when (val a = tab.dialogs.answer(accept, text)) {
            DialogAnswer.NoDialog -> return ActionResult.Failed(NO_DIALOG)
            is DialogAnswer.Answered -> a
        }
        val settled = settle(tab)
        val after = url(tab)
        val hint = listOfNotNull(
            "Answered the page's ${answered.dialog.kind} dialog (${if (accept) "accept" else "dismiss"}).",
            "The text was not used: only an accepted prompt dialog takes text.".takeIf { answered.textIgnored },
        ).joinToString(" ")
        return ActionResult.Ok(
            changed = true,
            appSwitched = after?.let(WebUrlPolicy::host)?.takeIf { it != before?.let(WebUrlPolicy::host) },
            hint = hint, settled = settled,
        )
    }

    override suspend fun pressCombo(combo: KeyCombo): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            if (!cdp(tab, "key") { pressCombo(combo) }) input(tab).key(combo.key.androidKeyCode, combo.androidMeta)
            true
        }
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult =
        try { typeInto(ref, snapshotId, text) } catch (e: InputUncertain) { UNCERTAIN }

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult =
        try { clearField(ref, snapshotId) } catch (e: InputUncertain) { UNCERTAIN }

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult =
        throw UnsupportedOperationException("the web surface cannot open apps")

    override suspend fun listApps(): List<Pair<String, String>> = throw UnsupportedOperationException("the web surface has no apps")

    // ---------- 网页独有 ----------

    override suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean): PageText {
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return PageText("", "", "", emptyList(), emptyList(), false, null)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        val raw = script(tab).readPage(maxChars, offset, scroll = scrollLazy)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        val o = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun pairs(k: String, a: String) = (o?.get(k) as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val first = (m[a] as? JsonPrimitive)?.content.orEmpty()
            val url = (m["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            first to url
        }
        return PageText(
            url = str("url").ifEmpty { url(tab).orEmpty() },
            title = str("title"),
            text = str("text"),
            links = pairs("links", "text"),
            images = pairs("images", "alt"),
            truncated = (o?.get("truncated") as? JsonPrimitive)?.booleanOrNull == true,
            nextOffset = (o?.get("nextOffset") as? JsonPrimitive)?.intOrNull,
        )
    }

    /** web_read_page:当前标签按 [digestTab] 读;给了网址时在临时后台标签里读,不动当前标签。 */
    override suspend fun read(req: PageRead): PageText {
        if (req.url != null) return readInBackground(req, req.url!!)
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return PageText("", "", "", emptyList(), emptyList(), false, null)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        return digestTab(tab, req)
    }

    /** 读脚本:在当前标签真实页面的读取世界里只读地跑(见 [ReadScriptRunner]);每个标签留一个,state 跨调用保留。没有 DevTools 会话时报 devtools_unavailable。 */
    override suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject?): EvalJs {
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return EvalJs.Failed(NO_TAB, "No web page is open.")
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        val session = devtools(tab)
            ?: return EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "This page has no DevTools connection, so read scripts cannot run here. Use web_read_page or web_observe instead.")
        val runner = readers[tab]?.takeIf { it.session === session } ?: ReadScriptRunner(session).also { readers[tab] = it }
        return try {
            runner.run(script, args, timeoutMs)
        } catch (e: CdpConnectionClosedException) {
            if (tab.crashed) throw WebTabCrashed(tab.id)
            EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "The page's DevTools connection was lost.")
        }
    }

    /** web_script:在当前标签真实页面的隔离世界里跑;动作经 [bridge] 回到 Mika 的动作路径。没有 DevTools 会话时报 devtools_unavailable。 */
    override suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun {
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return ScriptRun.Failed(NO_TAB, "No web page is open.")
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        val session = tab.devtools.session(tab)
            ?: return ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "This page has no DevTools connection, so page scripts cannot run here.")
        return try {
            // 每个 mika 动作之前再查一次:脚本跑着时页面可能交给了用户或崩了
            PageScriptRunner(session, preflight = {
                when {
                    tab.crashed -> ScriptRun.Failed("tab_crashed", "The page's tab crashed.")
                    pool?.isLocked(tab.id) == true -> ScriptRun.Failed("handed_over", "The user took over this page, so the script stopped.")
                    currentTab() !== tab -> ScriptRun.Failed("tab_changed", "The current tab changed, so the script stopped.")
                    else -> null
                }
            }).run(code, timeoutMs, bridge)
        } catch (e: CdpConnectionClosedException) {
            if (tab.crashed) throw WebTabCrashed(tab.id)
            ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "The page's DevTools connection was lost.")
        }
    }

    /**
     * 打开网址:没有标签或要求新标签时在池里开一个,否则用当前标签。等主文档加载完(上限 20 秒)再等页面安静
     * (脚本跳转也跟上),返回跳转后的实际地址、标题、主文档 HTTP 状态(拿得到时)和拦下的跳 App 链接。
     */
    override suspend fun navigate(url: String, newTab: Boolean): NavResult {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        val task = taskId!!
        var opened = false
        // 用户正在处理当前页面时不在它上面打开别的网址
        pool.current(task)?.takeIf { !newTab && !borrowed(it) && pool.isLocked(it.id) }?.let { throw WebTabHandedOver(it.id) }
        // 当前页面弹框挂着:先回话,不在停着的页面上开别的网址
        pool.current(task)?.takeIf { !newTab && !it.crashed && !borrowed(it) }?.let(::dialogGuard)
        // 用户看着的那一页(借来的)不换走:别的网址另开新标签
        val tab = withContext(Dispatchers.Main) {
            pool.current(task)?.takeIf { !newTab && !it.crashed && !borrowed(it) } ?: pool.open(task, null).also { opened = true }
        }
        val net = network(tab)
        val downloadsBefore = files?.downloads?.count() ?: 0
        tab.load(url, NAVIGATE_TIMEOUT_MS)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        // 明文 http 被拒绝:换 https 再试一次。App 的网络安全配置已允许明文,这只是退路(设备策略或 WebView 仍拒绝时)
        var upgraded: String? = null
        if (tab.mainFrameError?.contains(CLEARTEXT) == true && url.startsWith("http://", ignoreCase = true)) {
            upgraded = url
            tab.load("https://" + url.substring("http://".length), NAVIGATE_TIMEOUT_MS)
            if (tab.crashed) throw WebTabCrashed(tab.id)
        }
        // 打开的其实是个文件:下载要先问页面要名字才登记,等它登记上再数
        files?.downloads?.awaitNamed(DOWNLOAD_NAMING_CAP_MS)
        val download = files?.downloads?.since(downloadsBefore)?.lastOrNull()
        val error = tab.mainFrameError.takeIf { download == null && !tab.loadBecameDownload }
        // 加载中页面弹了框:加载等待已停下,页面停着,不再等也不再读
        val dialog = tab.dialogs.current
        val settled = if (tab.lastLoadFinished && error == null && dialog == null) settle(tab, net) else null
        if (tab.crashed) throw WebTabCrashed(tab.id)
        val status = tab.httpErrorStatus ?: if (error == null && download == null && dialog == null) script(tab).navStatus() else null
        return NavResult(
            tab = TabInfo(tab.id, url(tab).orEmpty(), title(tab), current = true),
            loaded = tab.lastLoadFinished && error == null && download == null && dialog == null,
            httpStatus = status,
            newTab = opened,
            externalLink = tab.lastExternalLink,
            settled = settled,
            netError = error,
            upgradedFrom = upgraded,
            download = download,
            dialog = dialog,
        )
    }

    override suspend fun tabs(): List<TabInfo> {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        return withContext(Dispatchers.Main) { pool.list(taskId!!) }
    }

    /** 只能切到本任务的标签;别的任务的、不存在的为 null。 */
    override suspend fun switchTab(tabId: String): TabInfo? {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        // 当前页面在用户手上:不切走(切走会把它从接管界面里换下来)
        pool.current(taskId!!)?.takeIf { pool.isLocked(it.id) }?.let { throw WebTabHandedOver(it.id) }
        return withContext(Dispatchers.Main) {
            pool.switchTo(taskId!!, tabId) ?: return@withContext null
            pool.list(taskId).firstOrNull { it.tabId == tabId }
        }
    }

    /** 只能关本任务的标签。 */
    override suspend fun closeTab(tabId: String): Boolean {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        return withContext(Dispatchers.Main) {
            pool.close(taskId!!, tabId)
        }
    }

    /**
     * 记下任务结束后留下当前这一页(只记下,此刻什么也不动);[keep] 为 false 时撤销。借来的(用户自己的)那一页本来就不随任务关,
     * 不用留。只记一页:再标别的标签就换成它。
     */
    override suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? {
        val now = keptNow ?: throw UnsupportedOperationException("no page keeper")
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        if (!keep) {
            val had = keepMark
            keepMark = null
            return KeepOpenResult(false, had?.tabId, null, null, null, KEEP_OPEN_HOURS)
        }
        val tab = currentTab() ?: return crashedTab()?.let { throw WebTabCrashed(it) }
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool.isExternal(tab.id)) throw WebTabIsUsers(tab.id)
        val (u, title) = withContext(Dispatchers.Main) { tab.view.url to tab.view.title }
        keepMark = KeepMark(tab.id, reason)
        Log.i(TAG, "tab ${tab.id} marked to keep running after the task")
        return KeepOpenResult(true, tab.id, u, title?.takeIf { it.isNotBlank() }, u?.let(WebUrlPolicy::host), KEEP_OPEN_HOURS, replaces = now()?.host)
    }

    override suspend fun siteMode(): String? =
        currentTab()?.takeIf { !it.crashed }?.let { if (it.devtools.isMobile(it)) "mobile" else "desktop" }

    /** 记下当前站点用哪一版,按新版本重新打开当前页并等它安静。 */
    override suspend fun setSiteMode(mobile: Boolean): SiteModeResult? {
        val tab = currentTab() ?: return crashedTab()?.let { throw WebTabCrashed(it) }
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        val u = url(tab)?.takeIf { it.startsWith("http") } ?: return null
        val site = WebUrlPolicy.host(u) ?: return null
        tab.devtools.setSiteMode(site, mobile)
        tab.load(u, NAVIGATE_TIMEOUT_MS)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (tab.lastLoadFinished) awaitQuiet(tab, QUIET_MS, CAP_MS)
        return SiteModeResult(site, tab.devtools.isMobile(tab), tab.lastLoadFinished)
    }

    // ---------- 网络记录与等待 ----------

    override suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> {
        val tab = netTab() ?: return emptyList()
        val n = NetworkTracker.of(tab, clock) ?: throw DevtoolsUnavailable()
        return n.list(urlContains, method, sinceLastAction)
    }

    override suspend fun capture(id: String): CapturedResponse? {
        val tab = netTab() ?: return null
        val n = NetworkTracker.of(tab, clock) ?: throw DevtoolsUnavailable()
        return n.get(id)
    }

    /** 文字条件读整页正文(不滚动,不动页面);网络空闲要 DevTools。 */
    override suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome {
        val start = clock()
        val tab = netTab() ?: return WaitOutcome(kind == WaitKind.TEXT_ABSENT, 0, null)
        if (kind == WaitKind.NETWORK_IDLE) {
            val n = NetworkTracker.of(tab, clock) ?: throw DevtoolsUnavailable()
            val met = n.awaitIdle(timeoutMs - (clock() - start))
            return WaitOutcome(met, clock() - start, url(tab))
        }
        val want = collapse(value.orEmpty())
        val deadline = start + timeoutMs
        while (true) {
            if (tab.crashed) throw WebTabCrashed(tab.id)
            val met = when (kind) {
                WaitKind.URL_CONTAINS -> url(tab).orEmpty().contains(value.orEmpty(), ignoreCase = true)
                else -> {
                    dialogGuard(tab)
                    val raw = script(tab).readPage(WAIT_TEXT_CHARS, 0, scroll = false)
                    val text = (runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()?.get("text") as? JsonPrimitive)?.content.orEmpty()
                    collapse(text).contains(want, ignoreCase = true) == (kind == WaitKind.TEXT_PRESENT)
                }
            }
            if (met || clock() >= deadline) return WaitOutcome(met, clock() - start, url(tab))
            delay(WAIT_POLL_MS)
        }
    }

    /** 让 [tab] 的页面脚本把一个 blob: 网址读出来(下载用)。 */
    internal suspend fun readBlob(tab: WebTab, url: String, maxBytes: Long): PageScript.Blob = script(tab).readBlob(url, maxBytes)

    /** 页面在指向 [url] 的链接上给的下载名(`<a download>`);没有、或页面读不到时为 null。 */
    internal suspend fun downloadName(tab: WebTab, url: String): String? = script(tab).downloadName(url)

    /** 本任务的下载(沙箱路径、大小、类型、状态);没有工作区时报不可用。 */
    override suspend fun downloads(): List<DownloadInfo> =
        files?.downloads?.list() ?: throw UnsupportedOperationException(DOWNLOADS_UNAVAILABLE)

    /**
     * 上传:先把工作区文件登记为「待交」,再真实点一下文件控件;页面弹出选择框时交出这些文件。
     * 路径不在工作区、不存在(工具层已检查,这里交出前再核一次)分别报 path_outside_workspace / file_not_found。
     * 点了却没有弹出选择框:如实说,待交的文件作废。
     */
    override suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val f = files ?: return ActionResult.Failed(UPLOAD_UNAVAILABLE)
        val t = resolve(tab, ref, snapshotId) ?: return STALE
        val prepared = when (val u = withContext(Dispatchers.IO) { f.prepareUpload(paths) }) {
            is WebFiles.Upload.Refused -> return ActionResult.Failed(u.error)
            is WebFiles.Upload.Ok -> u
        }
        // 交给页面的是中转区里的副本:选择框取走了就留一阵(页面提交时才读),没取走马上删
        var handed = false
        try {
            val p = when (val hit = reachable(tab, t)) {
                is Reach.Point -> hit
                is Reach.Blocked -> return hit.result
            }
            withContext(Dispatchers.Main) { tab.pendingUpload = prepared.uris }
            val r = verified(tab, t.relocatedTo) {
                click(tab, p)
                true
            }
            handed = withContext(Dispatchers.Main) { (tab.pendingUpload == null).also { tab.pendingUpload = null } }
            return uploadResult(r, handed, prepared)
        } finally {
            f.release(prepared.staged, consumed = handed)
        }
    }

    /** [x]、[y] 是 view 像素(原生触摸用),[cssX]、[cssY] 是页面视口的 CSS 像素(DevTools 用)。 */
    internal sealed interface Reach {
        class Point(val x: Int, val y: Int, val cssX: Int, val cssY: Int) : Reach
        class Blocked(val result: ActionResult) : Reach
    }

    internal sealed interface Focus {
        /** [target] 为 null 表示没给编号,用当前焦点。 */
        class Ok(val target: Target?) : Focus {
            val relocatedTo: String? get() = target?.relocatedTo
        }
        class Fail(val result: ActionResult) : Focus
    }

    /**
     * 动作之后的等待。页面安静照旧等(上限 [CAP_MS]);有 DevTools 时,本次动作发出的请求还在途才往后等,
     * 合计至多 [SETTLE_CAP_MS](见 [SettlePolicy]),返回网络是否静下来;没有 DevTools 时返回 null。
     */
    internal suspend fun settle(tab: WebTab, net: NetworkTracker? = null): Boolean? {
        val n = net ?: NetworkTracker.of(tab, clock)
        if (n == null) {
            awaitQuiet(tab, QUIET_MS, CAP_MS)
            return null
        }
        return SettlePolicy.settle(clock, { cap -> awaitQuiet(tab, QUIET_MS, cap) }, n::actionBusy, CAP_MS, SETTLE_CAP_MS, POLL_MS)
    }

    /** 发出过 DevTools 输入事件后失败:页面可能已收到,这次动作不能换原生输入再做一遍。 */
    internal class InputUncertain : Exception()

    /**
     * 每个 CSS 像素占多少 view 像素:按浏览器给的视口宽(桌面版时是仿真的宽度,不是 view 宽)。
     * 不读页面脚本里的 innerWidth:页面能改写它,把点击引到别处。
     */
    internal suspend fun InputTrack.cssScale(tab: WebTab): Double = CdpGeometry.scale(viewSize(tab).first, viewportCssWidth())

    internal sealed interface Spot {
        class At(val x: Double, val y: Double, val relocatedTo: String?) : Spot
        class Fail(val result: ActionResult) : Spot
    }

    internal suspend fun url(tab: WebTab): String? = withContext(Dispatchers.Main) { tab.view.url }
    internal suspend fun title(tab: WebTab): String = withContext(Dispatchers.Main) { tab.view.title.orEmpty() }
    @Synchronized
    internal fun nextId() = ++counter

    internal companion object {
        const val TAG = "MikaWebScreen"
        /** 每次 DevTools 输入调用的上限;后台页面卡住时不为它拖住动作。 */
        const val INPUT_TIMEOUT_MS = 3_000L
        const val MAX_LISTED_OPTIONS = 30
        val SELECT_ALL = CdpKeys.parseCombo("Control+A")!!
        val BACKSPACE = CdpKeys.parseCombo("Backspace")!!
        const val NOT_A_SELECT = "not_a_select"
        const val SELECT_DISABLED = "select_disabled"
        const val OPTION_DISABLED = "option_disabled"
        const val NO_SUCH_OPTION = "no_such_option"
        const val SELECT_NOT_APPLIED = "select_not_applied"
        val UNCERTAIN = ActionResult.Failed("input_uncertain")
        const val KEEP_SNAPSHOTS = 4
        const val QUIET_MS = 500L
        const val CAP_MS = 3_000L
        /** 有 DevTools 时动作后等「网络空闲 + 页面安静」的总上限。 */
        const val SETTLE_CAP_MS = 5_000L
        const val WAIT_POLL_MS = 250L
        /** 读页前等正文稳定:每 [CONTENT_POLL_MS] 取一次样,连续这么多次不变算稳定;一个字都没有时要更久。 */
        const val CONTENT_POLL_MS = 250L
        const val CONTENT_STABLE_POLLS = 2
        const val CONTENT_EMPTY_POLLS = 6
        const val CONTENT_CAP_MS = 4_000L
        const val CONTENT_AFTER_SCROLL_CAP_MS = 2_500L
        /** 全进程同时开着的读页后台标签至多几个;多的排队。 */
        const val BACKGROUND_READS = 3
        val backgroundReads = Semaphore(BACKGROUND_READS)
        const val BACKGROUND_CRASHED = "the page crashed while loading"
        const val BACKGROUND_DOWNLOAD = "the address is a file download, not a page"
        const val BACKGROUND_DIALOG = "the page opened a JavaScript dialog while loading"
        const val WAIT_TEXT_CHARS = 200_000
        /** 问页面要下载名是一次脚本往返,通常几十毫秒;页面卡住时不为它拖住动作结果。 */
        const val DOWNLOAD_NAMING_CAP_MS = 1_500L
        const val POLL_MS = 100L
        const val FOCUS_QUIET_MS = 150L
        const val FOCUS_CAP_MS = 800L
        const val READBACK_QUIET_MS = 200L
        const val READBACK_CAP_MS = 1_500L
        const val SCROLL_SWIPE_MS = 300L
        /** 停留时顺手那一下滑得慢些,不带出惯性。 */
        const val IDLE_SWIPE_MS = 450L
        const val SCROLL_QUIET_MS = 300L
        const val SCROLL_CAP_MS = 2_000L
        const val MIN_SCROLL_PX = 40
        const val TYPE_ATTEMPTS = 2
        const val FOCUS_TAPS = 2

        const val NO_TAB = "no_tab_open"
        const val TAB_CRASHED = "tab_crashed"
        const val NO_HISTORY = "no_history"
        const val KEY_NOT_ALLOWED = "key_not_allowed"
        const val NO_FOCUS = "no_focus"
        const val NOT_EDITABLE = "not_editable"
        const val FOCUS_MOVED = "focus_moved"
        const val HANDED_OVER = "handed_over"
        const val UPLOAD_UNAVAILABLE = "upload_unavailable"
        const val UPLOAD_NOT_OPENED = "upload_not_opened"
        const val DOWNLOADS_UNAVAILABLE = "downloads are not available yet"
        const val OBSCURED = "obscured"
        const val OFFSCREEN = "offscreen"
        const val NO_POOL = "this web port has no tab pool"
        /** 缩回双指放大:至多缩几下,每下之后等页面换好。 */
        const val UNZOOM_STEPS = 12
        const val UNZOOM_SETTLE_MS = 120L
        /** 视觉视口比排版视口窄或挪开了(用户放大过)时为 "1"。 */
        const val PINCH_PROBE = "(function(){var v=window.visualViewport;if(!v)return '0';" +
            "return (v.width<window.innerWidth*0.98||v.offsetLeft>1||v.offsetTop>1)?'1':'0';})()"
        const val NAVIGATE_TIMEOUT_MS = 20_000L
        /** 系统拒绝明文 http 时浏览器给的错误码。 */
        internal const val CLEARTEXT = "ERR_CLEARTEXT_NOT_PERMITTED"
        /** 只有无障碍树知道的元素在 [Target.ref] 里的前缀;页面脚本认不得它,按编号查都当元素不在。 */
        const val DEVTOOLS_REF = "devtools:"
        val STALE = ActionResult.Failed("ref_expired")

        /** 网页上只接受回车、退格、删除、Tab、方向键、翻页键。 */
        val ALLOWED_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )

        internal val BLANK = Regex("[\\s\\u200B\\u200C\\u200D\\uFEFF]+")
        fun collapse(s: String) = s.replace(BLANK, " ").trim()
    }
}
