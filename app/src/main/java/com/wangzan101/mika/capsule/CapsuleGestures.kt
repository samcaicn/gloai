package com.wangzan101.mika.capsule

import android.util.Log
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.capsule.CapsuleController.Touch
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG

// 胶囊:点按、展开收起与卡片动作
/** 胶囊上闪着一句时点它:那句说要打开 Mika 就打开,否则只收掉那句;这一下不参与单击或双击计时。 */
internal fun CapsuleController.tapCapsule() {
    val n = note
    clearNote()
    if (n != null) {
        resetTaps()
        if (n.opensMika) open(null)
        return
    }
    val touch = capsuleTouch
    capsuleTouch = null
    val age = touch?.let { SystemClock.uptimeMillis() - it.at }
    if (touch == null || age == null || age !in 0..taps.timeoutMs) {
        // 读屏直接执行 onClick,没有 MotionEvent:它是一次明确的单击,不和之前摸过胶囊拼成双击
        resetTaps()
        expand()
        return
    }
    tap(touch)
}

/** 真实触摸点一颗或胶囊:两下要在系统时限内、同一位置附近且来源相同;单击仍等双击时限过去再展开。 */
internal fun CapsuleController.tap(touch: Touch) {
    if (capturing) return
    when (taps.tap(touch.at, touch.x, touch.y, touch.source)) {
        DoubleTap.Tap.Double -> {
            tapJob?.cancel()
            previewScreen()
        }
        DoubleTap.Tap.First -> {
            tapJob?.cancel()
            tapJob = scope.launch {
                delay(taps.timeoutMs)
                if (taps.settle(SystemClock.uptimeMillis())) expand()
            }
        }
    }
}

/** 展开卡片(空闲图标是空闲卡片)。每次展开都按用户此刻在哪个对话重读一次空闲卡片,不拿上次那份。 */
internal fun CapsuleController.expand() {
    val v = view ?: return
    // 值得提醒:单击把挑出来的几条通知写进今天的提醒对话(Mika 的一条消息,不调模型),那个对话成为当前对话;
    // 再和空闲时一样展开卡片,卡片接着的就是它。没写成(出了错)也照常展开。双击发屏幕、长按说话和空闲时一样
    if (v.kind == CapsuleKind.Notice) {
        scope.launch {
            com.wangzan101.mika.proactive.NoticeHintList.openTalk(context)
            idle = loadIdle()
            open()
            refresh()
        }
        return
    }
    if (v.kind == CapsuleKind.Idle) {
        openIdle()
        return
    }
    open()
    refresh()
    scope.launch {
        val fresh = loadIdle()
        if (fresh != idle) {
            idle = fresh
            refresh()
        }
    }
}

/** 展开卡片:先记下此刻那一颗的方位,卡片从那边长出来。 */
internal fun CapsuleController.open() {
    if (!expanded) anchorY = placedY + (window?.height ?: 0) / 2
    expanded = true
    closing = false
    syncWindowVisibility()
}

/** 回到 Mika 的那个对话(和点通知同一个入口)。 */
internal fun CapsuleController.open(conversationId: String?) {
    runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
        .onFailure { Log.w(TAG, "could not open Mika from capsule", it) }
}

/** 点在窗口外面:先关掉放大的画面,再收起卡片。 */
internal fun CapsuleController.outside() {
    when {
        zoomed -> zoom(false)
        expanded -> collapse()
    }
}

internal fun CapsuleController.freshChat(fresh: Boolean) {
    idle = idle?.copy(fresh = fresh)
    refresh()
}

/** 收起卡片:输入框马上收起(焦点还给下面的 App),卡片缩回那一颗的方位后([closed])才真的收起;关了动画时直接收起。 */
internal fun CapsuleController.collapse() {
    if (!expanded) return
    closeInput()
    if (closing) return
    if (animationsOff(context) || card == null) {
        resetCard()
        refresh()
        return
    }
    closing = true
    syncWindowVisibility()
}

internal fun CapsuleController.closed() {
    if (!closing) return
    resetCard()
    refresh()
}

/** 卡片缩放的原点:那一颗停靠的那一边,高度是那一颗的中心在卡片上的位置(超出卡片时取上下边)。 */
internal fun CapsuleController.cardOrigin(size: Size): TransformOrigin {
    val y = if (size.height > 0f) ((anchorY - cardTop) / size.height).coerceIn(0f, 1f) else 0f
    return TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, y)
}

internal fun CapsuleController.runFor(key: String): TaskRun? = tasks.runs.value.firstOrNull { it.key == key }
