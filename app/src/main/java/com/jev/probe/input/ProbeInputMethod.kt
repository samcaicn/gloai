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

        /**
         * IME 视图高度占屏高比例——与实测讯飞键盘一致（1080x2412：1005px/2412=0.4167）。
         * 微信输入栏紧贴 IME 窗顶；高度一致，实测发送键坐标 (984,1331) 才有效。
         */
        private const val KB_RATIO = 0.4167f
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
                commitAndFinish(pair.second)
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
        // 视图高度必须与实测讯飞键盘一致（1080x2412 上 = 1005px，屏高 0.4167）：
        // 微信输入栏紧贴 IME 窗顶，高度一变输入栏位移，ChatCaptureService 里
        // 实测校准的发送键 (984,1331) 就会点空。这里把 IME 窗撑成同高键盘，
        // 写入-发送全程输入栏不动。
        val kbH = (resources.displayMetrics.heightPixels * KB_RATIO).toInt()
        return LinearLayout(this).apply {
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
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ProbeImeBus.imeActive = true
        Log.i(TAG, "ProbeIme: onStartInput restarting=$restarting pending=${ProbeImeBus.pendingText != null}")
        val pair = ProbeImeBus.consumePending()
        if (pair == null) {
            // 用户手动切到了探针输入法但没有待写内容：引导返回，避免卡在不能打字的键盘上。
            handler.postDelayed({ backToNormalIme() }, 600L)
            return
        }
        consumedId = pair.first
        commitAndFinish(pair.second)
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

    private fun commitAndFinish(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "ProbeIme: no input connection, fail")
            ProbeImeBus.completeWrite(false)
            return
        }
        ic.commitText(text, 1)
        Log.i(TAG, "ProbeIme: committed ${text.length} chars")
        ProbeImeBus.completeWrite(true)
        // 写完了：弹系统选择器让用户一步切回普通输入法（讯飞）。
        handler.postDelayed({ backToNormalIme() }, 500L)
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
