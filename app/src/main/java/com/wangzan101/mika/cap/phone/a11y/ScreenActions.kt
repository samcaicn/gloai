package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.BackAffordance
import com.wangzan101.mika.core.uistate.DEFAULT_SWIPE_MS
import com.wangzan101.mika.core.uistate.SystemCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 点 / 滑 / 滚 / 返回等动作:下发触摸、点击、滚动、按键,并核对结果。
 * 共享 [AndroidScreen] 的字段与方法经 [screen] 后向引用访问。
 */
internal class ScreenActions(private val screen: AndroidScreen) {

    suspend fun systemCommand(command: String): SystemCommandResult? = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext null
        val result = screen.shell.runSystemCommand(screen.displayId, command)
        SystemCommandResult(result.getInt("exitCode", -1), result.getString("output").orEmpty())
    }

    // ---------- 动作 ----------

    suspend fun tapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val (node, relocatedTo) = screen.resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val target = screen.ancestorOrSelf(node) { screen.isClickableTarget(it) }
        val rect = Rect().also(node::getBoundsInScreen)
        val touchFallback: suspend (AndroidScreen.EventClock) -> Boolean = { clock ->
            clock.mark()
            screen.touch(screen.pacing.within(rect.left, rect.top, rect.right, rect.bottom))
        }
        if (target == null) return@withContext screen.verified(relocatedTo = relocatedTo) {
            screen.touch(screen.pacing.within(rect.left, rect.top, rect.right, rect.bottom))
        }
        val outward = ClickFallback.mayBeOutward(ScreenReader.labelOf(node), node.viewIdResourceName) ||
            ClickFallback.mayBeOutward(ScreenReader.labelOf(target), target.viewIdResourceName)
        screen.verified(relocatedTo = relocatedTo, fallback = touchFallback, retryWhenUnchanged = !outward) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    suspend fun tapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified(fallback = { clock -> screen.clickNodeAt(x, y, clock) }) { screen.tapAt(x, y) }
    }

    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val target = screen.resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val rect = android.graphics.Rect().also { target.first.getBoundsInScreen(it) }
        screen.verified(relocatedTo = target.second) { doubleTapAt(screen.pacing.within(rect.left, rect.top, rect.right, rect.bottom)) }
    }

    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified { doubleTapAt(screen.pacing.near(x, y)) }
    }

    // 双击只能是一次手势里的两笔:分两次下发,中间的回调延迟可能超过系统认双击的时限
    internal suspend fun doubleTapAt(at: Pair<Int, Int>): Boolean {
        delay(screen.pacing.approach())
        return screen.a11y.taps(screen.displayId, at.first.toFloat(), at.second.toFloat(), 2, screen.pacing.doublePress(), screen.pacing.doubleGap())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified { screen.drag(listOf(x1 to y1, x2 to y2), durationMs, bow = true) }
    }

    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        if (paths.isEmpty() || paths.size > 2 || paths.any { it.size < 2 || it.size > 32 }) return@withContext ActionResult.Failed("invalid_gesture")
        screen.verified {
            // 一根手指照着画好的路径走,按节奏排快慢;几根手指一起动的只让时长浮动一点
            if (paths.size == 1) return@verified screen.drag(paths.single(), durationMs, bow = false)
            delay(screen.pacing.approach())
            screen.a11y.gesture(screen.displayId, paths.map(screen::pathOf), screen.pacing.duration(durationMs))
        }
    }

    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        var relocatedTo: String? = null
        val start = if (ref != null && snapshotId != null) {
            val target = screen.resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
            relocatedTo = target.second
            target.first
        } else {
            screen.read().entries.firstOrNull { it.element.scrollable }?.node
        }
        val node = start?.let { screen.ancestorOrSelf(it) { n -> n.isScrollable } }
        if (node == null) {
            // 没有可滚动的控件时用屏幕中部的滑动代替
            val cx = screen.screenWidth / 2
            val (from, to) = if (down) screen.screenHeight * 3 / 4 to screen.screenHeight / 4 else screen.screenHeight / 4 to screen.screenHeight * 3 / 4
            val r = swipe(cx, from, cx, to)
            return@withContext if (r is ActionResult.Ok && relocatedTo != null) r.copy(relocated = true, relocatedTo = relocatedTo) else r
        }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        screen.verified(relocatedTo = relocatedTo) { node.performAction(action) }
    }

    /**
     * 停留时顺手轻轻滚一下:在占了大半屏的那块可滚动内容(信息流、正文)正中慢慢划 [fraction] 个屏高,停稳再抬手、不甩。
     * 弹着输入法、输入框拿着焦点、上面盖着别的窗口(弹框、浮层)、没有够大的可滚动内容时不滚。
     */
    suspend fun idleScroll(fraction: Double): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay || screen.textInput.imeBox() != null || screen.focusedEditable() != null) return@withContext false
        if (ScreenReader.contentWindows(screen.windows()).count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1) return@withContext false
        val area = screen.read().entries.filter { it.element.scrollable }
            .map { e -> android.graphics.Rect().also { e.node.getBoundsInScreen(it) } }
            .maxByOrNull { it.width().toLong() * it.height() } ?: return@withContext false
        if (area.width().toLong() * area.height() < screen.screenWidth.toLong() * screen.screenHeight * AndroidScreen.IDLE_MIN_AREA) return@withContext false
        val dist = (screen.screenHeight * kotlin.math.abs(fraction)).roundToInt().coerceAtMost(area.height() / 2)
        if (dist < AndroidScreen.IDLE_MIN_PX) return@withContext false
        val cx = area.centerX()
        val mid = area.centerY()
        // 往下看(fraction 为正)手指往上划
        val (from, to) = if (fraction > 0) mid + dist / 2 to mid - dist / 2 else mid - dist / 2 to mid + dist / 2
        screen.drag(listOf(cx to from, cx to to), AndroidScreen.IDLE_SCROLL_MS, bow = true)
    }

    suspend fun back(): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        // 按键注入 → 点屏上返回控件 → 无障碍全局返回。逐级降级,不把某个设备上的单点失败当整体不可用。
        screen.verified(fallback = { clock -> tapBackControl(clock) || screen.a11y.performGlobalBack() }) {
            screen.shell.injectKey(screen.displayId, KeyEvent.KEYCODE_BACK)
        }
    }

    /** back 注入没生效时,点屏上可见的返回控件。认不到返回控件就 false,不硬点。 */
    internal suspend fun tapBackControl(clock: AndroidScreen.EventClock): Boolean {
        val c = BackAffordance.find(screen.peek().elements, screen.screenWidth, screen.screenHeight) ?: return false
        clock.mark()
        return screen.tapAt(c.centerX, c.centerY)
    }

    suspend fun pressKey(keyCode: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified { screen.shell.injectKey(screen.displayId, keyCode) }
    }
}
