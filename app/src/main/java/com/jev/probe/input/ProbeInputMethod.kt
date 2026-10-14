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

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            val pair = ProbeImeBus.pendingText?.let { text ->
                val id = ProbeImeBus.pendingId
                if (id != consumedId) id to text else null
            }
            if (pair != null) {
                consumedId = pair.first
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
        polling = true
        handler.post(poll)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        // 窗口高度必须与实测讯飞键盘一致（1080x2412 上 = 1005px，屏高 0.4167）：
        // 微信输入栏紧贴 IME 窗顶，高度一变输入栏位移，ChatCaptureService 里
        // 按比例计算的发送键坐标就点空。只设 view 高度不够——IME 窗口默认
        // wrap_content，会按内容缩成 304px（2026-10-14 真机实测，输入栏被推高
        // 750px，发送全点空）。这里 window.setLayout 强制窗口撑到键盘同高。
        val kbH = (resources.displayMetrics.heightPixels * ProbeImeBus.KB_RATIO).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF2F3F5.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
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
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // onWindowShown 时 decorView 才 attach 到 IME 窗口，此时 setLayout 才生效。
        val kbH = (resources.displayMetrics.heightPixels * ProbeImeBus.KB_RATIO).toInt()
        try {
            window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, kbH
            )
            Log.i(TAG, "ProbeIme: onWindowShown forced window h=$kbH")
        } catch (e: Exception) {
            Log.w(TAG, "ProbeIme: setLayout failed ${e.message}")
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ProbeImeBus.imeActive = true
        Log.i(TAG, "ProbeIme: onStartInput restarting=$restarting pending=${ProbeImeBus.pendingText != null}")
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
        val send = ProbeImeBus.pendingSend
        commitAndFinish(pair.second, send)
    }

    override fun onFinishInput() {
        ProbeImeBus.imeActive = false
        Log.i(TAG, "ProbeIme: onFinishInput")
        super.onFinishInput()
    }

    override fun onDestroy() {
        polling = false
        ProbeImeBus.imeActive = false
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "ProbeIme: onDestroy")
        super.onDestroy()
    }

    private fun commitAndFinish(text: String, send: Boolean) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "ProbeIme: no input connection, fail")
            ProbeImeBus.completeWrite(false)
            return
        }
        ic.commitText(text, 1)
        Log.i(TAG, "ProbeIme: committed ${text.length} chars send=$send")
        val sentOk = if (send) sendViaIme(ic) else false
        ProbeImeBus.reportSent(sentOk)
        ProbeImeBus.completeWrite(true)
        // 注意：写完后**不自动弹选择器切回**。切回交给视图上的「↩ 返回普通输入法」
        // 按钮，用户自己掌握时机（App 不替用户切换输入法）。
    }

    /**
     * 设备无关的发送尝试（不用屏幕坐标，换手机/换分辨率都有效）：
     * 1. performEditorAction(IME_ACTION_SEND)——正规输入法「发送」键的同款路径。
     *    微信输入框实测 imeOptions=0x40000006（NO_ENTER_ACTION|NO_FULLSCREEN）、
     *    actionId=0（ACTION_NONE），所以这条路大概率返回 false，但免费试。
     * 2. 回车 keyevent——用户开启微信「回车键发送消息」后这条直接发送；没开则
     *    是换行。**无法从返回值确认是否真发**，所以只做 best-effort 注入，
     *    不算成功，让调用方继续走动态坐标兜底（双保险，不会漏发）。
     * 只有 performEditorAction 明确返回 true 才认为发送成功。
     */
    private fun sendViaIme(ic: android.view.inputmethod.InputConnection): Boolean {
        try {
            val sent = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            Log.i(TAG, "ProbeIme: performEditorAction(SEND)=$sent")
            if (sent) return true
        } catch (e: Exception) {
            Log.w(TAG, "ProbeIme: performEditorAction failed ${e.message}")
        }
        try {
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            Log.i(TAG, "ProbeIme: ENTER keyevent injected (best-effort, not confirmed)")
        } catch (e: Exception) {
            Log.w(TAG, "ProbeIme: ENTER keyevent failed ${e.message}")
        }
        return false
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
