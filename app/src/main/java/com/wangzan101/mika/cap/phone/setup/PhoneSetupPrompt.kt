package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务要用手机、内置助手却没在运行时举一下手:对话页在那轮问答下面放一行「去设置」。
 * 网页、文件类任务不会碰到这里。由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份,任务与对话页共用。
 */
class PhoneSetupPrompt(context: Context) {
    /** 发现手机没就绪的那次运行:它所在的对话,和(运行记录开得出来时)它的编号;对话页据此把提示挂在那轮下面。 */
    data class Need(val conversationId: String?, val runId: String?)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _needed = MutableStateFlow<Need?>(null)
    val needed: StateFlow<Need?> = _needed.asStateFlow()

    fun request(conversationId: String?, runId: String?) {
        _needed.value = Need(conversationId, runId)
    }

    fun clear() {
        _needed.value = null
    }

    /** 首次打开时自动进引导;看过(完成或点了「稍后」)就不再自动进。 */
    fun guideSeen(): Boolean = prefs.getBoolean(SEEN, false)

    fun markGuideSeen() {
        prefs.edit().putBoolean(SEEN, true).apply()
    }

    private companion object {
        const val PREFS = "phone_setup"
        const val SEEN = "guide_seen"
    }
}
