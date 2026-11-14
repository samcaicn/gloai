package com.wangzan101.mika.cap.phone.shell

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceCredential

/**
 * 只由已认证的 Mika Binder 调用。主屏专用;凭据从不进命令行、剪贴板、截图或日志。
 * 凭据的每个按键都经 [injectTo] 只投给 SystemUI 拥有的焦点窗口:锁屏上方另有窗口(锁屏时显示的 App、来电、
 * 全屏通知)或已经解锁回到 App 时,系统拒绝注入,按键不会落到别处。[inject] 只用于不含秘密的亮屏键。
 */
internal class DeviceUnlockInput(private val context: Context, private val inject: (InputEvent) -> Boolean,
    private val injectTo: (InputEvent, Int) -> Boolean, private val command: (List<String>) -> Boolean) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    /** 锁屏界面属于 SystemUI;查不到它的 uid 时不输入凭据。 */
    private val systemUiUid: Int? by lazy { runCatching { context.packageManager.getPackageUid(SYSTEM_UI, 0) }.getOrNull() }

    @Suppress("DEPRECATION")
    fun wake(): Boolean {
        // WakeLock + ACQUIRE_CAUSES_WAKEUP 是旧式但兼容的亮屏路;注入 KEYCODE_WAKEUP / input keyevent 224 在部分 ROM 上失效(#618)。
        // 这里只负责「尽力唤醒」，是否真亮屏由 DeviceAccess 读系统状态判断。
        val woken = runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "mika:wake").acquire(1_000)
        }.isSuccess
        return woken || key(KeyEvent.KEYCODE_WAKEUP, send = inject) || command(listOf("input", "keyevent", "224"))
    }

    fun dismiss(): Boolean = command(listOf("wm", "dismiss-keyguard"))

    /** 安全锁屏仍停在通知/指纹页时做一次通用的上滑。事件定向给 SystemUI,解锁中途完成也不会落进前台 App。 */
    fun reveal(): Boolean {
        val target = systemUiUid ?: return false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
        val bounds = runCatching { context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds }.getOrNull() ?: return false
        val x = bounds.exactCenterX()
        val startY = bounds.bottom - bounds.height() * 0.15f
        val endY = bounds.top + bounds.height() * 0.45f
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, y: Float): Boolean {
            val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                injectTo(event, target)
            } finally {
                event.recycle()
            }
        }
        if (!send(MotionEvent.ACTION_DOWN, downAt, startY)) return false
        var completed = false
        try {
            repeat(6) { step ->
                if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
                SystemClock.sleep(24)
                val progress = (step + 1) / 6f
                if (!send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), startY + (endY - startY) * progress)) return false
            }
            if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            completed = send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endY)
            return completed
        } finally {
            if (!completed) send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), endY)
        }
    }

    /** 对 SystemUI 做一次定向点击,用于点「用密码解锁」这类回退按钮;不含凭据。 */
    fun tap(x: Int, y: Int): Boolean {
        val target = systemUiUid ?: return false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int, eventTime: Long): Boolean {
            val event = MotionEvent.obtain(downAt, eventTime, action, x.toFloat(), y.toFloat(), 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                injectTo(event, target)
            } finally {
                event.recycle()
            }
        }
        if (!send(MotionEvent.ACTION_DOWN, downAt)) return false
        return send(MotionEvent.ACTION_UP, downAt + TAP_MS)
    }

    fun enter(kind: Int, bytes: ByteArray, clear: Int = 0): Boolean {
        var chars = CharArray(0)
        var sent = false
        val target = systemUiUid
        val deadline = SystemClock.uptimeMillis() + 8_000
        fun toKeyguard(event: InputEvent): Boolean { sent = true; return injectTo(event, target!!) }
        try {
            if (target == null || !power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            val type = CredentialKind.entries.getOrNull(kind) ?: return false
            if (bytes.any { it.toInt() !in 32..126 }) return false
            chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
            if (!DeviceCredential.valid(type, chars)) return false
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = if (type == CredentialKind.Password) map.getEvents(chars) ?: return false else null
            // 已由调用方确认 SystemUI 的凭据输入框。只按已有输入的长度回删残留半截,空框不删:
            // 部分系统在空输入框上收到 DEL 会直接关掉密码页,盲发 16 次 DEL 会让后面的数字全部落空。
            if (type == CredentialKind.Pin) {
                repeat(clear.coerceIn(0, MAX_CLEAR)) { if (!key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false }
            } else if (!key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, ::toKeyguard) ||
                !key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false
            if (type == CredentialKind.Pin) {
                for (c in chars) {
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive) return false
                    if (!key(KeyEvent.KEYCODE_0 + (c - '0'), send = ::toKeyguard)) return false
                }
            } else {
                for (event in events!!) {
                    event.source = InputDevice.SOURCE_KEYBOARD
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive || !toKeyguard(event)) return false
                }
            }
            // 某些系统在最后一位自动提交。回车同样只投给 SystemUI:这期间已经解锁回到 App 时被系统拒绝,不会发进 App。
            SystemClock.sleep(200)
            return !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || key(KeyEvent.KEYCODE_ENTER, send = ::toKeyguard)
        } finally {
            bytes.fill(0); chars.fill('\u0000')
            // 密码里的符号可能用到修饰键;发过按键才释放,同样只投给 SystemUI,不往前台 App 发任何事件。
            if (sent && target != null) for (code in MODIFIERS) {
                val now = SystemClock.uptimeMillis()
                injectTo(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD), target)
            }
        }
    }

    private fun key(code: Int, meta: Int = 0, send: (InputEvent) -> Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD)
        return send(down) && send(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val MAX_CLEAR = 16
        const val TAP_MS = 30L
        val MODIFIERS = intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)
    }
}
