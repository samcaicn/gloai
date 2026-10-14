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

    /**
     * 探针 IME 窗口高度占屏高比例——必须与主流键盘（讯飞实测 1080x2412 上 1005px）
     * 一致。微信输入栏紧贴 IME 窗顶：IME 高度一变输入栏位移，发送键坐标就点空。
     * 旧实现只设了 view 高度没设窗口高度，IME 被 wrap 成 304px，输入栏被推高
     * 750px——2026-10-14 真机"填了没发出"的根因。
     */
    const val KB_RATIO = 0.4167f

    /** 微信聊天页输入栏高度（dp，实测讯飞态标定：绿按钮中心=输入栏垂直中心）。 */
    const val WX_INPUT_BAR_DP = 56f

    @Volatile
    var pendingText: String? = null
        private set

    /** 每投递一次 +1，防 ProbeInputMethod 轮询重复消费同一条。 */
    @Volatile
    var pendingId: Long = 0L
        private set

    /** 写入成功后是否顺便发送（autoSend 开时 true）。由 ProbeInputMethod 执行。 */
    @Volatile
    var pendingSend: Boolean = false
        private set

    /** 最近一次写入的发送结果（IME 通道 performEditorAction/回车是否成功）。 */
    @Volatile
    var lastSent: Boolean = false
        private set

    /**
     * 探针 IME 窗口实际高度（px，0=未知）。IME 窗口是 wrap_content，实测会被内容
     * 缩到 304px（而非设想的键盘高 1005px）——窗口多高决定了微信输入栏被顶到哪。
     * ProbeInputMethod 每次消费 pending 前从 decorView 刷新此值；
     * ChatCaptureService 的发送/聚焦坐标用它算，窗口多高都不点空。
     */
    @Volatile
    var imeWindowHeightPx: Int = 0

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
    fun requestWrite(text: String, send: Boolean = false, done: (Boolean) -> Unit) {
        pendingText = text
        pendingSend = send
        pendingId += 1
        lastSent = false
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
        pendingSend = false
        cb?.invoke(ok)
    }

    /** ProbeInputMethod 写入+发送完成后回填发送结果，供 ChatCaptureService 判断是否还要走坐标兜底。 */
    @Synchronized
    fun reportSent(sent: Boolean) {
        lastSent = sent
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
