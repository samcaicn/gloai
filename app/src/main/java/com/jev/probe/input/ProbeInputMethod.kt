package com.jev.probe.input

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.probe.core.t

/**
 * 探针写入输入法：唯一能把回复文本写进微信输入栏的通道。
 *
 * 只做「写入」一件事，不做日常键盘：视图就是一句话 + 一个「返回普通输入法」按钮。
 * 典型时序：fillInput 存 pending → 系统选择器切到本 IME → onStartInput（输入框
 * 已聚焦时系统回调）→ commitText → completeWrite(true) → ChatCaptureService 点发送。
 * onStartInput 若不来（上一轮切过来没切回、输入法一直活着），onStartInputView 里
 * 的 200ms 轮询兜底消费 pending。
 */
class ProbeInputMethod : InputMethodService() {

    companion object {
        const val TAG = "JEVASSIST"
        private const val POLL_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var consumedId = -1L
    private var kbRoot: View? = null

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            // 每个 tick 都刷新窗口高度：fillInput 算聚焦/发送坐标时要读它，
            // 只在消费 pending 时发布会有竞态（真机见过 focus 点落到 y=1322
            // 的聊天区——那时高度还没发布，走了 KB_RATIO 兜底）。
            publishWindowHeight()
            val pair = ProbeImeBus.pendingText?.let { text ->
                val id = ProbeImeBus.pendingId
                if (id != consumedId) id to text else null
            }
            if (pair != null) {
                consumedId = pair.first
                Log.i(TAG, "ProbeIme: poll consuming pending id=${pair.first} len=${pair.second.length}")
                publishWindowHeight()
                val send = ProbeImeBus.pendingSend
                commitAndFinish(pair.second, send)
            }
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ProbeIme: onCreate")
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "ProbeIme: onCreateInputView (poll chain start)")
        polling = true
        handler.post(poll)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        // 视图给键盘同高（屏高 0.4167，讯飞实测 1080x2412 上 1005px）。注意：IME 窗口
        // 是 wrap_content，实际会把窗口缩到内容高（实测 304px）——窗口多高不影响正确性，
        // 微信输入栏永远紧跟 IME 窗顶，ChatCaptureService 用 ProbeImeBus.imeWindowHeightPx
        // 动态算坐标，窗口 304px 还是 1005px 都点得中发送键。
        val kbH = (resources.displayMetrics.heightPixels * ProbeImeBus.KB_RATIO).toInt()
        kbRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF2F3F5.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, kbH
            )
            addView(TextView(this@ProbeInputMethod).apply {
                text = t("AI 聊天助手 · 写入通道\n（正在写入回复，写完点下面返回普通输入法）",
                    "AI Assistant · write channel\n(Writing the reply — tap below to return to your normal keyboard)")
                textSize = 15f
                setPadding(dp(16), dp(14), dp(16), dp(6))
            })
            addView(TextView(this@ProbeInputMethod).apply {
                text = t("↩ 返回普通输入法", "↩ Back to normal keyboard")
                textSize = 17f
                setTextColor(0xFF07C160.toInt())
                setPadding(dp(16), dp(6), dp(16), dp(14))
                setOnClickListener { backToNormalIme() }
            })
        }
        return kbRoot!!
    }

    /**
     * 把 IME 窗口实际高度发布到 ProbeImeBus。decorView 就是 IME 窗口根 view，
     * 其 height 即窗口真实占位（wrap_content 下可能是 304px 而非键盘高）。
     * 发送/聚焦坐标全靠它算，所以每次准备消费 pending 前都刷新一次。
     */
    private fun publishWindowHeight() {
        try {
            // SoftInputWindow 本质是 Dialog，Dialog 无 decorView，需再 .window 取其 Window。
            // 取不到时退回我们自己键盘根 view 的高度；两者都是「微信输入栏上沿到屏底的距离」。
            // 窗口收缩瞬间（会话切换/重建）decorView 会短暂量出 48px 这种假高度，
            // 一旦发布出去会把发送键坐标算到屏幕中间（2026-10-15 实测 y=2024 点空）。
            // 真实 IME 最矮也是「仅输入栏」≈56dp*density>130px，低于 100px 一律不发布。
            // 上界同理：窗口未附着/测量前夕 decorView 会量到接近全屏的假高（2026-10-15
            // 实测发布 2300px，把聚焦点算到屏幕顶部 y=34）。键盘最高 KB_RATIO*H
            // （≈0.417H），超过 0.6H 一律是异常值，弃用并保留上一次的合理值。
            val h = window?.window?.decorView?.height
                ?: kbRoot?.height
                ?: 0
            val maxSane = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            if (h > 0 && h < 100) {
                // 窗口收起态（无编辑器聚焦时 decorView 只剩 48px）。旧逻辑只丢日志、
                // 继续沿用上次的 1012px，坐标就还按「键盘弹起」算——2026-10-16 07:52
                // 实测聚焦点因此落到屏幕中央 y=1323，编辑器没被点聚焦。改为发布
                // 「收起」语义：imeWindowHeightPx 归 0 + imeCollapsed=true。
                // 只在状态翻转时记一行，避免轮询每 200ms 刷屏（实测 5 行/秒）。
                if (!ProbeImeBus.imeCollapsed || ProbeImeBus.imeWindowHeightPx != 0) {
                    ProbeImeBus.imeWindowHeightPx = 0
                    ProbeImeBus.imeCollapsed = true
                    Log.i(TAG, "ProbeIme: window collapsed (h=$h) -> IME hidden, coords use window bottom")
                }
            } else if (h >= maxSane) {
                Log.w(TAG, "ProbeIme: suspicious huge window height=$h (>$maxSane), ignore")
            } else if (h > 0 && (h != ProbeImeBus.imeWindowHeightPx || ProbeImeBus.imeCollapsed)) {
                ProbeImeBus.imeWindowHeightPx = h
                ProbeImeBus.imeCollapsed = false
                Log.i(TAG, "ProbeIme: window height published = $h")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ProbeIme: publishWindowHeight failed ${e.message}")
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ProbeImeBus.imeActive = true
        // 编辑器身份捕获（2026-10-16 19:25 假阳性修复）：假连接（语音态隐藏 EditText /
        // TYPE_NULL fallback）的 commitText 会静默丢失、readBox 恒空——上层据此谎报
        // 「已发送」。onStartInput 是每个新连接的必回调，在这记录 EditorInfo 全貌，
        // 既供写入前守卫（editorIsTextEditor），也让下次翻车能自证。
        val ei = currentInputEditorInfo
        ProbeImeBus.editorPkg = ei?.packageName
        ProbeImeBus.editorInputType = ei?.inputType ?: 0
        Log.i(TAG, "ProbeIme: editor identity pkg=${ei?.packageName} inputType=0x${Integer.toHexString(ei?.inputType ?: 0)} imeOptions=0x${Integer.toHexString(ei?.imeOptions ?: 0)}")
        publishWindowHeight()
        // poll 看门狗：onCreateInputView 只在 IME 视图首次创建时调一次，poll 链一旦
        // 断裂（onDestroy 后服务重建 / tick 里 polling=false 提前 return 就不再自恢复）
        // 就永久停摆——requestWrite 的 pending 永远不被消费。2026-10-15 实测：
        // fillInput 后既无 committed 也无 sendFor 日志，发送链整个没启动，dwell 兜底
        // 切了会话，回复烂死在 pending 里。onStartInput 每次输入连接必调，在这幂等重启。
        if (!polling) {
            polling = true
            handler.post(poll)
            Log.w(TAG, "ProbeIme: poll watchdog restarted (chain was dead)")
        }
        // 注册读框闭包：sendWeChatByVerifiedTap 靠它判断消息真发出（发出后微信
        // 清空输入框）。InputConnection 只在本服务里有效，必须由本侧提供。
        ProbeImeBus.boxReader = { readBoxText() }
        // 注册代发闭包：坐标点击全部重试失败后，ChatCaptureService 用它让 IME 直接
        // 执行发送动作。IME 持有 InputConnection，是绕开屏幕坐标的最直接通道。
        ProbeImeBus.sendAction = { performSendAction() }
        Log.i(TAG, "ProbeIme: onStartInput restarting=$restarting pending=${ProbeImeBus.pendingText != null} polling=$polling")
        val pair = ProbeImeBus.consumePending()
        if (pair == null) {
            // 无待写内容：保持激活，不自动切回。
            // 理由：自动回复需 fillInput 时 imeActive==true 才能走 poll path 直接填字+点发送；
            // 旧逻辑「600ms 后弹选择器切回普通输入法」会形成死循环——每次自动回复都要用户
            // 手动确认一次输入法切换，无法无人值守。切回改由视图「↩ 返回普通输入法」按钮控制。
            Log.i(TAG, "ProbeIme: onStartInput no pending, staying active (manual back only)")
            return
        }
        consumedId = pair.first
        publishWindowHeight()
        val send = ProbeImeBus.pendingSend
        commitAndFinish(pair.second, send)
    }

    override fun onFinishInput() {
        ProbeImeBus.imeActive = false
        ProbeImeBus.boxReader = null
        ProbeImeBus.sendAction = null
        ProbeImeBus.editorPkg = null
        ProbeImeBus.editorInputType = 0
        ProbeImeBus.lastWriteVerified = false
        Log.i(TAG, "ProbeIme: onFinishInput")
        super.onFinishInput()
    }

    override fun onDestroy() {
        polling = false
        ProbeImeBus.imeActive = false
        ProbeImeBus.boxReader = null
        ProbeImeBus.sendAction = null
        ProbeImeBus.editorPkg = null
        ProbeImeBus.editorInputType = 0
        ProbeImeBus.lastWriteVerified = false
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "ProbeIme: onDestroy")
        super.onDestroy()
    }

    /**
     * 读输入框全文：ExtractedText 优先（最完整），微信不支持时退回
     * 光标前后各 1000 字符拼接。任何异常返回 null（调用方走截屏软参考）。
     */
    private fun readBoxText(): String? {
        val ic = currentInputConnection ?: return null
        return try {
            val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            et?.text?.toString() ?: run {
                val before = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                val after = ic.getTextAfterCursor(1000, 0)?.toString() ?: ""
                before + after
            }
        } catch (e: Exception) {
            Log.w(TAG, "ProbeIme: readBoxText failed ${e.message}")
            null
        }
    }

    private fun commitAndFinish(text: String, send: Boolean) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "ProbeIme: no input connection, fail")
            ProbeImeBus.completeWrite(false)
            return
        }
        // commitText 是**追加**语义：不清空的话，之前几轮没发出去的回复会串成
        // 一条大杂烩消息（2026-10-14 真机实锤：8 段回复挤进同一条已发气泡，
        // 而当天只 commit 了 7 个字符）。光标前后各删一圈清旧草稿，
        // 再 select-all 双保险，然后写新回复。
        ic.deleteSurroundingText(4096, 4096)
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(text, 1)
        Log.i(TAG, "ProbeIme: committed ${text.length} chars send=$send (box cleared first)")
        // 读回校验（2026-10-16 19:25 假阳性修复）：commit 后立刻读框。真连接上刚提交的
        // 文本必须读得回来（健康轮次 attemptSend 的 pre-read 就是这么拿到文本的）；
        // 读不回 = 假连接（语音态隐藏 EditText / TYPE_NULL fallback）——字已写进虚空，
        // 用户框里什么都没有，上层任何「框空=已发」都是谎言。直接判失败：走上层
        // 剪贴板兜底 + 明示，绝不让死读框进发送验证链。
        val readBack = readBoxText()
        if (readBack.isNullOrEmpty()) {
            Log.e(TAG, "ProbeIme: read-back EMPTY after commit -> fake connection (pkg=${ProbeImeBus.editorPkg} type=0x${Integer.toHexString(ProbeImeBus.editorInputType)}), write LOST")
            ProbeImeBus.reportSent(false)
            ProbeImeBus.lastWriteVerified = false
            ProbeImeBus.completeWrite(false)
            return
        }
        ProbeImeBus.lastWriteVerified = true
        Log.i(TAG, "ProbeIme: read-back ok (${readBack.length} chars) -> write landed in a real editor")
        // 发送不归输入法管：IME 只负责写入。真正发送由 ChatCaptureService 的
        // 「截图确认绿键在 → 点 (0.9111W, H-IME实际高-56dp/2) → 1.1s 复检 →
        // 重点一次」完成——保留 sendDelayMs 倒计时撤回窗口（IME 内直接发会吞掉
        // 这个窗口），且截图复检能抓住任何「返回 true 但其实没发」的故障。
        // （2026-10-14 实测：performEditorAction 在微信上确实能发送，但发送权
        // 统一收归 ChatCaptureService，行为可预期、可验证、可重试。）
        ProbeImeBus.reportSent(false)
        ProbeImeBus.completeWrite(true)
        // 注意：写完后**不自动弹选择器切回**。切回交给视图上的「↩ 返回普通输入法」
        // 按钮，用户自己掌握时机（App 不替用户切换输入法）。
    }

    /**
     * 代执行一次「发送」动作。
     *
     * 优先走编辑器声明的 IME_ACTION_SEND；没声明就发回车。注意两点：
     * - 返回值只表示**动作已派发**，不代表消息真的发出去了。微信里回车默认是换行
     *   还是发送，取决于它自己的「回车键发送消息」开关。调用方仍要用 readBox()
     *   复检一次。
     * - 这里只是坐标兜底走投无路时的最后一搏，不参与常规时序，因此不触碰
     *   sendDelayMs 的倒计时撤回窗口——那条链还归 ChatCaptureService 管。
     */
    private fun performSendAction(): Boolean {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "ProbeIme: sendAction skipped, no input connection")
            return false
        }
        val action = currentInputEditorInfo
            ?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
        return if (action == EditorInfo.IME_ACTION_SEND) {
            val ok = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            Log.i(TAG, "ProbeIme: performEditorAction(SEND) -> $ok")
            ok
        } else {
            sendDefaultEditorAction(true)
            Log.i(TAG, "ProbeIme: sendDefaultEditorAction dispatched (action=$action)")
            true
        }
    }

    private fun backToNormalIme() {
        try {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            Log.i(TAG, "ProbeIme: showed input method picker")
        } catch (e: Exception) {
            Log.w(TAG, "ProbeIme: picker failed ${e.message}")
        }
    }
}
