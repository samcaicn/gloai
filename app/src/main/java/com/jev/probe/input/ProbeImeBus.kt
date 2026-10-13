package com.jev.probe.input

import android.content.Context
import android.provider.Settings

/**
 * 探针写入通道总线（同进程单例，ChatCaptureService 与 ProbeInputMethod 共享）。
 *
 * 背景：微信 8.0.78 聊天页把 a11y 树剥光（rootInActiveWindow=null），
 * ACTION_SET_TEXT 无节点可写；普通 app 也不能像 adb shell 那样 input text。
 * 实测唯一可靠的写入通道 = 探针自己的输入法（InputMethodService.commitText）。
 *
 * 流程：ChatCaptureService.fillInput → requestWrite(text) → 弹系统输入法选择器 →
 * 用户选「AI聊天助手 写入」→ ProbeInputMethod.onStartInput 检出 pending →
 * commitText → completeWrite(true) → ChatCaptureService 继续 sendFor（点发送键）。
 * 写完 ProbeInputMethod 自动引导用户切回普通输入法。
 */
object ProbeImeBus {

    /** IME 在 Settings.Secure 里的 id 格式：包名/类全名。 */
    val imeId: String = "com.jev.probe/com.jev.probe.input.ProbeInputMethod"

    @Volatile
    var pendingText: String? = null
        private set

    /** 每投递一次 +1，防 ProbeInputMethod 轮询重复消费同一条。 */
    @Volatile
    var pendingId: Long = 0L
        private set

    @Volatile
    var onWritten: ((Boolean) -> Unit)? = null

    @Synchronized
    fun requestWrite(text: String, done: (Boolean) -> Unit) {
        pendingText = text
        pendingId += 1
        onWritten = done
    }

    /** ProbeInputMethod 消费：返回 (id, text)，消费后 id 记录在 consumedId。 */
    @Synchronized
    fun consumePending(): Pair<Long, String>? {
        val t = pendingText ?: return null
        val id = pendingId
        pendingText = null
        return id to t
    }

    @Synchronized
    fun completeWrite(ok: Boolean) {
        val cb = onWritten
        onWritten = null
        pendingText = null
        cb?.invoke(ok)
    }

    /** 用户在系统设置里启用过探针输入法吗（ enabled_input_methods 列表）。 */
    fun isEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: return false
        return enabled.split(':').any { it.trim() == imeId }
    }

    /** 当前默认输入法就是探针吗。 */
    fun isCurrent(ctx: Context): Boolean {
        val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return cur.trim() == imeId
    }
}
