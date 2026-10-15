package com.jev.probe.input

import android.content.Context

/**
 * 探针写入通道总线（同进程单例，ChatCaptureService 与 ProbeInputMethod 共享）。
 *
 * 背景：微信 8.0.78 聊天页把 a11y 树剥光（rootInActiveWindow=null），
 * ACTION_SET_TEXT 无节点可写；普通 app 也不能像 adb shell 那样 input text。
 * 实测唯一可靠的写入通道 = 探针自己的输入法（InputMethodService.commitText）。
 *
 * 流程：ChatCaptureService.fillInput → requestWrite(text) →（用户自己已切到探针
 * 输入法）ProbeInputMethod.onStartInput/poll 检出 pending → 清空旧草稿 →
 * commitText → completeWrite(true) → ChatCaptureService 走 sendFor 验证式发送
 * （截图确认绿键 → 点 → 复检）。App 不弹选择器、不替用户切输入法。
 * 写完 ProbeInputMethod 不自动切回——由视图上的「↩ 返回普通输入法」按钮让用户自己切回。
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

    /** 写入成功后是否顺便发送（autoSend 开时 true）。**仅信息性**：IME 只负责写入，
     *  发送统一由 ChatCaptureService「截图确认绿键 → 点 → 复检」完成（保留倒计时
     *  撤回窗口 + 复检抓假阳性）——2026-10-14 真机决定，IME 内直接发会吞掉撤回窗口。 */
    @Volatile
    var pendingSend: Boolean = false
        private set

    /** 最近一次写入后 IME 是否声称已发送。现在恒 false（IME 不做发送），
     *  保留仅供日志诊断——ChatCaptureService 不再拿它短路验证链。 */
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

    /**
     * ProbeInputMethod 在 onStartInput 注册的「读输入框」闭包（InputConnection 只在
     * IME 侧持有），onFinishInput/onDestroy 注销。sendWeChatByVerifiedTap 用它读
     * 输入框判断消息是否真发出：微信发出消息后会清空输入框 → 框空=已发出。
     * 微信 8.0.78 FLAG_SECURE 使截屏恒黑、绿键检测恒 0（2026-10-15 实测误报 sent），
     * 输入框判据是黑屏下唯一可靠的发送确认信号。
     */
    @Volatile
    var boxReader: (() -> String?)? = null

    /**
     * 让输入法代执行一次「发送」动作（performEditorAction / 回车）。
     *
     * 只在坐标点击重试耗尽后调用，作为**最后兜底**——坐标来自屏幕比例经验公式，
     * 微信改版或半屏形态下会打偏；而 IME 持有 InputConnection，是直接通道。
     * 是否真能发出取决于目标 App 有没有声明 IME_ACTION_SEND（微信还要看
     * 「回车键发送消息」开关），所以返回值可能为 false，调用方需再判一次。
     */
    @Volatile
    var sendAction: (() -> Boolean)? = null

    /** 请求输入法代发一次；IME 不在位返回 false。 */
    fun requestSendViaIme(): Boolean = try {
        sendAction?.invoke() == true
    } catch (_: Exception) {
        false
    }

    /** 读当前输入框全文；IME 不在位/异常返回 null（调用方退回截屏软参考）。 */
    fun readBox(): String? = try {
        boxReader?.invoke()
    } catch (_: Exception) {
        null
    }

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
