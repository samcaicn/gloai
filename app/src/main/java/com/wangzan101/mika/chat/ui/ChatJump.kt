package com.wangzan101.mika.chat.ui

/** 聊天页里一个可定位的消息位置。轮次从 0 开始;steerIndex 是这一轮插话的序号。 */
sealed interface ChatTarget {
    /** 用户气泡;steerIndex 为 null 表示这一轮的第一条用户消息。 */
    data class Message(val turnIndex: Int, val steerIndex: Int?) : ChatTarget

    /** 插话之后完成或被打断的回答。 */
    data class Reply(val turnIndex: Int, val steerIndex: Int, val interrupted: Boolean) : ChatTarget

    /** 这一轮的文件卡片。 */
    data class Files(val turnIndex: Int) : ChatTarget

    /** 这一轮的结果卡片。 */
    data class Status(val turnIndex: Int) : ChatTarget

    /** 一通语音通话开头那行(从通话页的「通话记录」跳过来)。 */
    data class Call(val callId: String) : ChatTarget
}

/** 一次性的跳转请求。serial 让连续两次跳到同一处也算新请求。 */
data class ChatJump(val conversationId: String, val target: ChatTarget, val serial: Long)
