package com.jev.probe.input

import android.content.Context

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
 * 写完 ProbeInputMethod 不自动切回——由视图上的「↩ 返回普通输入法」按钮让用户自己切回（App 不替用户切换输入法）。
 */
object ProbeImeBus {

    /** IME 在 Settings.Secure 里的 id 形式：包名/manifest 声明名（相对名原样进 id）。 */
    val imeId: String = "com.jev.probe/.input.ProbeInputMethod"

    @Volatile
    var pendingText: String? = null
        private set

    /** 每投递一次 +1，防 ProbeInputMethod 轮询重复消费同一条。 */
    @Volatile
    var pendingId: Long = 0L
        private set

    @Volatile
    var onWritten: ((Boolean) -> Unit)? = null

    /**
     * ProbeInputMethod 当前是否正持有输入连接（onStartInput=true / onFinishInput=false）。
     * 替代读 Settings.Secure.DEFAULT_INPUT_METHOD——Android 14 起 targetSdk>33 的 app
     * 读 ENABLED_INPUT_METHODS / DEFAULT_INPUT_METHOD 直接抛 SecurityException
     * （2026-10-13 实测把探针进程崩到循环重启）。IME 服务自己知道自己在不在位，最准。
     */
    @Volatile
    var imeActive: Boolean = false

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

    /**
     * 用户在系统设置里启用过探针输入法吗。
     * 走 InputMethodManager.enabledInputMethodList（IMMS 在系统进程里读设置，
     * 不受 targetSdk 34+ 的 Secure settings 读取限制）；按包名匹配，
     * 不纠结 id 是相对名还是全限定名。异常一律当未启用，绝不上抛崩进程。
     */
    fun isEnabled(ctx: Context): Boolean = try {
        val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.enabledInputMethodList?.any { it.packageName == ctx.packageName } == true
    } catch (e: Exception) {
        false
    }
}
