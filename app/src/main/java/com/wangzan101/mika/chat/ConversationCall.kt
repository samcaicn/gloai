package com.wangzan101.mika.chat

import kotlinx.serialization.Serializable

/**
 * 对话里的一通语音通话:接通的时刻、挂断的时刻和原因、有没有说上话。
 * 通话里说的每一轮照常存进对话([com.wangzan101.mika.core.agent.Turn.callId] 指回这里的 [id]),
 * 这份记录只给界面用:聊天页在那几轮前后画「语音通话 · 9:41」「通话结束 · 3:12」,
 * 通话页右上角的「通话记录」列出它们。一句话没说的通话也记一条,聊天页画成一行。
 *
 * [endMs] 为 null:还在通话里,或通话中途 App 被杀没来得及记下挂断。
 * [endReason] 是 [END_IDLE] 这类线上值;不认识的当普通挂断。
 */
@Serializable
data class ConversationCall(
    val id: String,
    val startMs: Long,
    val endMs: Long? = null,
    val endReason: String? = null,
    /** 这一通里有没有存进对话的轮次;没有的在聊天页只画一行「没有说话」。 */
    val spoke: Boolean = false,
) {
    val durationMs: Long? get() = endMs?.let { (it - startMs).coerceAtLeast(0) }

    companion object {
        const val END_HANGUP = "hangup"
        const val END_IDLE = "idle"
    }
}

/** 记录按接通时刻排;同一编号的新记录替换旧的(接通时记一条,挂断时补上结束)。 */
internal fun List<ConversationCall>.upsert(call: ConversationCall): List<ConversationCall> =
    (filterNot { it.id == call.id } + call).sortedBy { it.startMs }
