package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ElementLocator
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.PageTree
import com.wangzan101.mika.core.screen.uistate.WebInfo
import com.wangzan101.mika.core.screen.uistate.PageDialog
import com.wangzan101.mika.core.cap.web.DIALOG_OPEN
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import com.wangzan101.mika.cap.web.WebScreen.Ready
import com.wangzan101.mika.cap.web.WebScreen.Snap
import com.wangzan101.mika.cap.web.WebScreen.Companion.TAG
import com.wangzan101.mika.cap.web.WebScreen.Companion.NO_TAB
import com.wangzan101.mika.cap.web.WebScreen.Companion.TAB_CRASHED
import com.wangzan101.mika.cap.web.WebScreen.Companion.HANDED_OVER
import com.wangzan101.mika.cap.web.WebScreen.Companion.UNZOOM_STEPS
import com.wangzan101.mika.cap.web.WebScreen.Companion.UNZOOM_SETTLE_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.PINCH_PROBE

// 网页屏:读页快照、对话框快照、元素定位与视口缩放
internal fun WebScreen.ready(): Ready {
    val tab = currentTab() ?: return Ready.Fail(ActionResult.Failed(if (crashedTab() != null) TAB_CRASHED else NO_TAB))
    if (tab.crashed) return Ready.Fail(ActionResult.Failed(TAB_CRASHED))
    // 用户正在接管界面里操作这个页面:Agent 不能同时动它
    if (pool?.isLocked(tab.id) == true) return Ready.Fail(ActionResult.Failed(HANDED_OVER))
    // 页面弹框挂着时页面停着,点、打字都落不到页面上:先回话
    if (tab.dialogs.current != null) return Ready.Fail(ActionResult.Failed(DIALOG_OPEN))
    return Ready.Tab(tab)
}

/** [fuse] 为真且标签有 DevTools 会话时做元素融合;核实、障碍判定用的读屏不做,保持快。 */
internal suspend fun WebScreen.read(tab: WebTab, key: String?, id: String, fuse: Boolean = false): Snap {
    // 弹框挂着时页面脚本停着,读页面只会等到超时:只报弹框
    tab.dialogs.current?.let { d -> return dialogSnap(tab, id, d) }
    val s = script(tab)
    val urlBefore = url(tab)
    val mutBefore = s.mutationCount()
    val json = s.tree(key)
    val (w, h) = viewSize(tab)
    val urlAfter = url(tab)
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
    val mut = (root?.get("mut") as? JsonPrimitive)?.longOrNull ?: mutBefore
    val vw = (root?.get("vw") as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
    val info = WebInfo(urlAfter.orEmpty(), title(tab), tab.id, tab.httpErrorStatus)
    val parsed = PageTree.parse(json, w, h, id, info)
    val snapshot = parsed.copy(
        packageName = urlAfter?.let(WebUrlPolicy::host),
        changedDuringRead = urlBefore != urlAfter || mut != mutBefore,
    )
    val scale = vw?.let { w / it } ?: 1.0
    val session = if (fuse && snapshot.elements.isNotEmpty()) tab.devtools.session(tab) else null
    if (session != null) fused(tab, s, session, key, id, snapshot, scale, mut)?.let { return it }
    return Snap(id, tab, key, snapshot, locators(snapshot.elements), scale, mut)
}

/** 元素融合;DevTools 超时、出错时为 null(记下原因),读屏照用页面脚本的结果。 */
internal suspend fun WebScreen.fused(tab: WebTab, s: PageScript, session: CdpSession, key: String?, id: String, snapshot: ScreenSnapshot, scale: Double, mut: Long): Snap? {
    val outcome = try {
        ElementProbe(session).enrich(snapshot, s.refNodesExpression(key), scale)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ElementProbe.Outcome.Skipped(e.message ?: e.javaClass.simpleName)
    }
    if (outcome !is ElementProbe.Outcome.Fused) {
        Log.i(TAG, "element fusion skipped on tab ${tab.id}: ${(outcome as ElementProbe.Outcome.Skipped).reason}")
        return null
    }
    Log.d(TAG, "element fusion $id: ${outcome.stats}")
    val r = outcome.result
    // 补信息期间页面又动了:如实标上
    val moved = s.mutationCount() != mut
    val out = snapshot.copy(
        elements = r.elements,
        changedDuringRead = snapshot.changedDuringRead || moved,
        web = snapshot.web?.let { it.copy(truncated = it.truncated || r.omitted > 0, omitted = r.omitted) },
    )
    return Snap(id, tab, key, out, locators(out.elements), scale, mut, r.pageRefs, r.backendIds, outcome.anchors)
}

internal suspend fun WebScreen.dialogSnap(tab: WebTab, id: String, d: PageDialog): Snap {
    val u = url(tab).orEmpty()
    val (w, h) = viewSize(tab)
    val snapshot = ScreenSnapshot(id, WebUrlPolicy.host(u), emptyList(), w, h,
        web = WebInfo(u, title(tab), tab.id, tab.httpErrorStatus, dialog = d))
    return Snap(id, tab, null, snapshot, emptyList(), 1.0, 0L)
}

internal fun WebScreen.emptySnapshot(tab: WebTab?) = ScreenSnapshot(
    snapshotId = "w${tab?.id ?: "none"}-empty", packageName = null, elements = emptyList(),
    width = host.width, height = host.height, hasWindow = false,
)

// 上下文是遍历顺序里前面最近一个有名字的元素,防止别处的同名元素顶上来被误认
internal fun WebScreen.locators(elements: List<UiElement>): List<ElementLocator> {
    var last: String? = null
    return elements.map { e ->
        ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, hint = e.hint).also {
            if (e.name.isNotBlank()) last = e.name
        }
    }
}

/** 当前标签是借来的(在用户的界面里,不在网页显示上)。 */
internal fun WebScreen.borrowed(tab: WebTab): Boolean = pool?.isExternal(tab.id) == true

/**
 * 借来的标签此刻的样子:把视图本身画进一张图(和视图像素一一对应,点按坐标照用),不含盖在它上面的界面(对话面板)。
 * 视图还没排版时为 null。
 */
internal suspend fun WebScreen.viewFrame(tab: WebTab): android.graphics.Bitmap? = withContext(Dispatchers.Main) {
    val v = tab.view
    if (tab.destroyed || v.width <= 0 || v.height <= 0) return@withContext null
    runCatching {
        android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
    }.onFailure { Log.w(TAG, "could not draw borrowed tab ${tab.id}", it) }.getOrNull()
}

/**
 * 用户在借来的标签上双指放大过:缩回整页再读屏。页面脚本按排版视口报坐标,放大后看得见的只是其中一块,
 * 不缩回编号的坐标就对不上屏幕。没放大时什么也不做;缩不回(页面禁止缩放等)就照样读。
 */
internal suspend fun WebScreen.unzoom(tab: WebTab) {
    if (!pinched(tab)) return
    repeat(UNZOOM_STEPS) {
        val more = withContext(Dispatchers.Main) { !tab.destroyed && tab.view.zoomOut() }
        delay(UNZOOM_SETTLE_MS)
        if (!more || !pinched(tab)) return
    }
}

// 看得见的那块(视觉视口)比排版视口窄,或挪开了:用户放大过
internal suspend fun WebScreen.pinched(tab: WebTab): Boolean {
    val raw = kotlinx.coroutines.withTimeoutOrNull(UNZOOM_SETTLE_MS * 4) {
        kotlinx.coroutines.suspendCancellableCoroutine<String?> { c ->
            tab.view.post {
                if (tab.destroyed) c.resumeWith(Result.success(null))
                else tab.view.evaluateJavascript(PINCH_PROBE) { c.resumeWith(Result.success(it)) }
            }
        }
    }
    return raw?.trim('"') == "1"
}
