package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpGeometry
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTimeoutException
import com.wangzan101.mika.core.cap.web.cdp.InputOutcome
import com.wangzan101.mika.core.cap.web.cdp.InputTrack
import com.wangzan101.mika.core.cap.web.cdp.input
import com.wangzan101.mika.core.cap.web.cdp.mouseClick
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.Relocation
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.relocate
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.WebPoint
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import com.wangzan101.mika.core.cap.web.WebTabHandedOver
import com.wangzan101.mika.cap.web.cdp.NetworkTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import com.wangzan101.mika.cap.web.WebScreen.InputUncertain
import com.wangzan101.mika.cap.web.WebScreen.PageState
import com.wangzan101.mika.cap.web.WebScreen.Reach
import com.wangzan101.mika.cap.web.WebScreen.Snap
import com.wangzan101.mika.cap.web.WebScreen.Spot
import com.wangzan101.mika.cap.web.WebScreen.Target
import com.wangzan101.mika.cap.web.WebScreen.Companion.TAG
import com.wangzan101.mika.cap.web.WebScreen.Companion.INPUT_TIMEOUT_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.POLL_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.UPLOAD_NOT_OPENED
import com.wangzan101.mika.cap.web.WebScreen.Companion.OBSCURED
import com.wangzan101.mika.cap.web.WebScreen.Companion.OFFSCREEN
import com.wangzan101.mika.cap.web.WebScreen.Companion.DEVTOOLS_REF
import com.wangzan101.mika.cap.web.WebScreen.Companion.STALE

// 网页屏:调试协议连接、网络静默等待、点击与坐标换算
/** 网络与等待用的当前标签:崩溃、接管中照读页的规矩抛出;没有标签为 null。 */
internal fun WebScreen.netTab(): WebTab? {
    crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
    val tab = currentTab() ?: return null
    if (tab.crashed) throw WebTabCrashed(tab.id)
    if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
    return tab
}

internal fun WebScreen.crashedTab(): String? = taskId?.let { pool?.crashedTab(it) }

internal fun WebScreen.uploadResult(r: ActionResult, handed: Boolean, prepared: WebFiles.Upload.Ok): ActionResult {
    if (r !is ActionResult.Ok) return r
    return if (handed) {
        r.copy(changed = true, hint = listOfNotNull(r.hint, "Gave ${prepared.names.size} file(s) to the page: ${prepared.names.joinToString()}.").joinToString(" "))
    } else {
        ActionResult.Failed(UPLOAD_NOT_OPENED)
    }
}

/**
 * 编号 → 页面里还在的元素。快照里的元素还连在文档上就直接用(页面元素有真实身份,不怕列表复用);
 * 已不在(重新渲染、换了页面)时重读一屏,按 M1 的规则找唯一对应者,找不到为 null(ref_expired)。
 * 两条路都会把元素滚进视口中央。
 */
internal suspend fun WebScreen.resolve(tab: WebTab, ref: String, snapshotId: String): Target? {
    val snap = synchronized(history) { history.firstOrNull { it.id == snapshotId } } ?: return null
    if (snap.tab !== tab || snap.key == null) return null
    val index = ref.removePrefix("@e").toIntOrNull()?.minus(1) ?: return null
    val element = snap.snapshot.elements.getOrNull(index) ?: return null
    val s = script(tab)
    val pageRef = snap.pageRef(ref)
    if (pageRef != null) {
        if (s.centerOf(pageRef, snap.key) != null) return Target(snap.key, pageRef, element, null, snap.scale)
    } else {
        devtoolsTarget(tab, snap, ref, snap.key, element, null)?.let { return it }
    }
    val key = "w${tab.id}-r${++internalReads}"
    val fresh = read(tab, key, key, fuse = true)
    val shortSide = min(fresh.snapshot.width, fresh.snapshot.height)
    val found = relocate(snap.locators[index], snap.locators, fresh.locators, shortSide) as? Relocation.Found ?: return null
    val ref = "@e${found.index + 1}"
    val now = fresh.snapshot.elements[found.index]
    val freshRef = fresh.pageRef(ref) ?: return devtoolsTarget(tab, fresh, ref, key, now, now.name)
    if (s.centerOf(freshRef, key) == null) return null
    return Target(key, freshRef, now, now.name, fresh.scale)
}

/** 只有无障碍树知道的元素:经 DevTools 滚进视口、重新取框;会话没了、元素已不在时为 null。 */
internal suspend fun WebScreen.devtoolsTarget(tab: WebTab, snap: Snap, ref: String, key: String, element: UiElement, relocatedTo: String?): Target? {
    val anchor = snap.anchors[ref] ?: return null
    val session = tab.devtools.session(tab) ?: return null
    val placed = ElementProbe(session).locate(anchor) ?: return null
    val (w, h) = viewSize(tab)
    val x = (placed.x * snap.scale).roundToInt()
    val y = (placed.y * snap.scale).roundToInt()
    val point = (x to y).takeIf { x in 0 until w && y in 0 until h }
    return Target(key, "$DEVTOOLS_REF${anchor.backendId}", element, relocatedTo, snap.scale, devtools = true, point = point, covered = placed.onTop == false,
        cssPoint = placed.x.roundToInt() to placed.y.roundToInt())
}

// 目标中心点上真是它自己(或它的子孙)才点;被盖住时说出盖住它的是什么
internal suspend fun WebScreen.reachable(tab: WebTab, t: Target): Reach = if (t.devtools) {
    when {
        t.point == null || t.cssPoint == null -> Reach.Blocked(ActionResult.Failed(OFFSCREEN))
        t.covered -> Reach.Blocked(ActionResult.Failed(OBSCURED))
        else -> Reach.Point(t.point.first, t.point.second, t.cssPoint.first, t.cssPoint.second)
    }
} else when (val hit = script(tab).hitTest(t.ref, t.key)) {
    is HitResult.Hit -> Reach.Point(px(hit.x, t.scale), px(hit.y, t.scale), hit.x, hit.y)
    is HitResult.Obscured -> Reach.Blocked(
        ActionResult.Failed(OBSCURED, hint = hit.byName.takeIf { it.isNotBlank() }?.let { "Covered by 「$it」." }),
    )
    HitResult.Offscreen -> Reach.Blocked(ActionResult.Failed(OFFSCREEN))
    HitResult.Gone -> Reach.Blocked(STALE)
}

// 指纹不算非输入框的焦点:点一下按钮让它获得焦点不是「界面变了」
internal suspend fun WebScreen.state(tab: WebTab): PageState {
    val snap = read(tab, null, "w${tab.id}-state").snapshot
    val fingerprint = snap.elements.map { e -> listOf(e.role, e.name, e.bounds, e.editable && e.focused) }.hashCode()
    // 弹框挂着时页面脚本停着,变动计数读不到
    val mutations = if (snap.web?.dialog != null) -1L else script(tab).mutationCount()
    return PageState(snap.web?.url, mutations, fingerprint)
}

/** 动作开始前调:有 DevTools 时拿到(必要时打开)这个标签的网络记录,并记下「这次动作从这里开始」。 */
internal suspend fun WebScreen.network(tab: WebTab): NetworkTracker? = NetworkTracker.of(tab, clock)?.also { it.markAction() }

/** 连续 [quietMs] 没有页面变动、地址不变且主文档不在加载时为安静;到 [capMs] 还不安静就返回 false。 */
internal suspend fun WebScreen.awaitQuiet(tab: WebTab, quietMs: Long, capMs: Long): Boolean {
    // 页面弹框挂着:页面停着,不会再变,也读不动
    if (tab.dialogs.current != null) return true
    val s = script(tab)
    val deadline = clock() + capMs
    var lastMut = s.mutationCount()
    var lastUrl = url(tab)
    var since = clock()
    while (true) {
        delay(POLL_MS)
        if (tab.crashed) return false
        // 动作弹出了页面弹框:页面停着,不会再变,也读不动
        if (tab.dialogs.current != null) return true
        val m = s.mutationCount()
        val u = url(tab)
        val now = clock()
        if (m != lastMut || u != lastUrl) {
            lastMut = m
            lastUrl = u
            since = now
        }
        if (!tab.loading && now - since >= quietMs) return true
        if (now >= deadline) return false
    }
}

/** 这个标签此刻能用来输入的会话;没有、已断、或 Input 卡住过的为 null。 */
internal fun WebScreen.devtools(tab: WebTab): CdpSession? {
    val s = runCatching { tab.devtools.session(tab) }.getOrNull()?.takeIf { !it.closed.value } ?: return null
    return s.takeIf { synchronized(inputStuck) { inputStuck[tab] } !== s }
}

/**
 * 用 DevTools 做一次输入 [block]:做完为 true;一个事件都没送出去(没有会话、会话已断、第一发就被拒)为 false,
 * 调用方可以改走原生输入;送出过事件后失败(含超时)抛 [InputUncertain],按着的键已抬起。
 */
internal suspend fun WebScreen.cdp(tab: WebTab, what: String, block: suspend InputTrack.() -> Unit): Boolean {
    val s = devtools(tab) ?: return false
    return when (val o = s.input(INPUT_TIMEOUT_MS, pacing, block)) {
        is InputOutcome.Done -> true
        is InputOutcome.NotSent -> {
            stuckIfTimedOut(tab, s, o.error)
            Log.w(TAG, "devtools $what not sent, using native input", o.error)
            false
        }
        is InputOutcome.Uncertain -> {
            stuckIfTimedOut(tab, s, o.error)
            Log.w(TAG, "devtools $what failed after sending input; not retrying", o.error)
            throw InputUncertain()
        }
    }
}

/** 滚轮只给桌面版、且正挂在窗口上显示的标签用:后台(没在画)的页面上滚轮会卡住 Input。 */
internal suspend fun WebScreen.wheelable(tab: WebTab): Boolean = !tab.devtools.isMobile(tab) && withContext(Dispatchers.Main) {
    val v = tab.view
    v.isAttachedToWindow && v.isShown && v.windowVisibility == android.view.View.VISIBLE
}

internal fun WebScreen.stuckIfTimedOut(tab: WebTab, s: CdpSession, e: Throwable) {
    if (e is CdpTimeoutException) synchronized(inputStuck) { inputStuck[tab] = s }
}

internal suspend fun WebScreen.click(tab: WebTab, p: Reach.Point) {
    if (!cdp(tab, "click") { mouseClick(p.cssX.toDouble(), p.cssY.toDouble()) }) input(tab).tap(p.x, p.y)
}

/** 落点换成 CSS 像素:编号按点击的规则解析并做遮挡检查;坐标是 view 像素,按此刻的视口宽换算。 */
internal suspend fun WebScreen.cssPoint(tab: WebTab, at: WebPoint): Spot = when (at) {
    is WebPoint.Ref -> {
        val t = resolve(tab, at.ref, at.snapshotId)
        if (t == null) Spot.Fail(STALE) else when (val hit = reachable(tab, t)) {
            is Reach.Point -> Spot.At(hit.cssX.toDouble(), hit.cssY.toDouble(), t.relocatedTo)
            is Reach.Blocked -> Spot.Fail(hit.result)
        }
    }
    is WebPoint.Xy -> {
        val k = (devtools(tab)?.input(INPUT_TIMEOUT_MS) { cssScale(tab) } as? InputOutcome.Done)?.value
        if (k == null) Spot.Fail(ActionResult.Failed(DEVTOOLS_UNAVAILABLE)) else Spot.At(CdpGeometry.toCss(at.x, k), CdpGeometry.toCss(at.y, k), null)
    }
}

internal suspend fun WebScreen.viewSize(tab: WebTab): Pair<Int, Int> = withContext(Dispatchers.Main) {
    val v = tab.view
    if (v.width > 0 && v.height > 0) v.width to v.height else host.width to host.height
}

internal fun WebScreen.px(css: Int, scale: Double) = (css * scale).roundToInt()
