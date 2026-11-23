package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.INJECT_MODE_WAIT_FOR_FINISH
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.injectMode
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sendPair

// 助手服务:向指定屏幕注入按键与触摸
internal inline fun ShellService.injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
    sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

/** 同 [injectPair],但定向投给 [targetUid] 拥有的焦点窗口,不经过全局输入焦点。 */
internal inline fun ShellService.injectPairTo(down: InputEvent, up: InputEvent, displayId: Int, targetUid: Int, fallback: () -> Boolean): Boolean =
    sendPair({ injectTo(down, displayId, targetUid) }, { injectTo(up, displayId, targetUid) }, fallback)

@SuppressLint("PrivateApi")
internal fun ShellService.inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
    event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
    val manager = inputManager()
    manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
        .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "injectInputEvent failed", t)
    false
}

/**
 * 只投给 [targetUid] 拥有的焦点窗口,等处理完再返回;焦点不在它那里时系统拒绝,事件不会落到别的窗口。
 * 用安卓 13 起的隐藏重载 `injectInputEvent(event, mode, targetUid)`,没有这个重载时不注入。
 * 失败只记异常类型:被拒的原因里不含按键,但这条路径输入的是锁屏凭据,不留多余信息。
 */
@SuppressLint("PrivateApi")
internal fun ShellService.injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
    event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
    val manager = inputManager()
    manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
        .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
    false
}

/** API 34 起注入入口在 InputManagerGlobal,更早在 InputManager。 */
@SuppressLint("PrivateApi")
internal fun ShellService.inputManager(): Any = runCatching {
    Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
}.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
