package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.frontdesk.DeskNotifier
import java.util.UUID

/**
 * 电话里还没回的请示,整个进程一份:问题写进这通电话所属的会话(有的话),主人在会话里下一句话、或在通知里回复,
 * 先到的算数,问题随即清掉。回答也记进会话,留个底。没有所属会话的电话只能从通知里回。
 * 写会话用前台兜底写回的那种一行([DeskNotifier.STATUS]),在会话里就是一句正常的回话。
 */
class OwnerQuestions(
    private val write: (conversationId: String, turn: Turn) -> Unit,
    private val askedLine: (question: String, notified: Boolean) -> String,
    private val answeredLine: () -> String,
    private val withdrawnLine: () -> String,
) {
    class Handle(val token: String, val conversationId: String?, val question: String)

    private class Entry(val handle: Handle, val onAnswer: (String) -> Unit)

    private val open = LinkedHashMap<String, Entry>()

    /** 登记一个问题;[notified] 为 false 表示通知发不出去,会话里那一行顺带提醒去打开。 */
    fun open(conversationId: String?, question: String, notified: Boolean, onAnswer: (String) -> Unit): Handle {
        val handle = Handle(UUID.randomUUID().toString(), conversationId, question)
        synchronized(open) { open[handle.token] = Entry(handle, onAnswer) }
        conversationId?.let { write(it, Turn(userText = "", outcome = askedLine(question, notified), status = DeskNotifier.STATUS)) }
        return handle
    }

    /** 还没回的那个问题;已回或已撤为 null。 */
    fun question(token: String): String? = synchronized(open) { open[token]?.handle?.question }

    /** 通知里回的;对不上(已回、已撤)或空回复返回 false。 */
    fun answer(token: String, text: String): Boolean {
        val reply = text.trim().takeIf { it.isNotEmpty() } ?: return false
        val entry = synchronized(open) { open.remove(token) } ?: return false
        deliver(entry, reply)
        return true
    }

    /** 会话 [conversationId] 里用户发了一句:那里有在等的问题就算回答(最近问的那个),返回 true;否则 false,照常处理。 */
    fun answerIn(conversationId: String, text: String): Boolean {
        val reply = text.trim().takeIf { it.isNotEmpty() } ?: return false
        val entry = synchronized(open) {
            open.values.lastOrNull { it.handle.conversationId == conversationId }?.also { open.remove(it.handle.token) }
        } ?: return false
        deliver(entry, reply)
        return true
    }

    /** 悄悄撤掉(换成新问的一句时用:新问题那一行已经写进会话)。 */
    fun cancel(token: String) {
        synchronized(open) { open.remove(token) }
    }

    /**
     * 电话挂了、问题还没回:撤掉,并在所属会话里补一行「已撤回」,免得主人去回一个没人等的问题,
     * 迟到的话也有上下文(之后在会话里打的字照常交给前台)。已回、已撤的不再写。
     */
    fun withdraw(token: String) {
        val entry = synchronized(open) { open.remove(token) } ?: return
        entry.handle.conversationId?.let { write(it, Turn(userText = "", outcome = withdrawnLine(), status = DeskNotifier.STATUS)) }
    }

    private fun deliver(entry: Entry, reply: String) {
        entry.handle.conversationId?.let { write(it, Turn(userText = reply, outcome = answeredLine(), status = DeskNotifier.STATUS)) }
        entry.onAnswer(reply)
    }
}
