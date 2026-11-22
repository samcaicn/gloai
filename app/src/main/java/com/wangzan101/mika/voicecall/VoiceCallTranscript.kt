package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.Turn as ChatTurn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 一通语音通话的文字聚合:用户每段完整话语开一轮([onUser]),助手在两段用户话语之间
 * 说的都并进当轮的答([onAssistant]);会话结束把整通问答批量写回发起会话([close]),
 * 与聊天走同一个存储入口。
 *
 * 装配:会话编排的用户/助手转写回调(只要 final)喂进来;[conversationId] 是发起这次通话
 * 的会话(从胶囊直开、没有发起会话时为 null,不写回);[appendTurn] 接对话存储的逐轮写入。
 */
class VoiceCallTranscript(
    private val conversationId: () -> String?,
    private val appendTurn: suspend (conversationId: String, turn: ChatTurn) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 一轮问答:用户问的、助手答的(多段按行拼)、问的时刻(毫秒)。 */
    data class Turn(val question: String, val answer: String, val at: Long)

    private val lock = Any()
    private val done = mutableListOf<Turn>()
    private var open: Turn? = null
    private var closed = false

    /** 用户说了一段完整的话:闭合上一轮,开新一轮。空白忽略。 */
    fun onUser(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            open?.let { done += it }
            open = Turn(text.trim(), "", nowMillis())
        }
    }

    /** 助手说了一段话:并进当轮的答;用户还没开口(如问候语)就聚成一条空问轮。空白忽略。 */
    fun onAssistant(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            val current = open
            open = if (current == null) Turn("", text.trim(), nowMillis())
            else current.copy(answer = if (current.answer.isEmpty()) text.trim() else current.answer + "\n" + text.trim())
        }
    }

    /** 截至此刻的全部轮次,含还没等来回答的那一轮。 */
    fun turns(): List<Turn> = synchronized(lock) { done + listOfNotNull(open) }

    /**
     * 会话结束写回:把有问有答的轮次逐条写进发起会话,只写一次——
     * 重复结束不再写;收尾在通话协程域被取消时也照常写完(NonCancellable),
     * 装配方在通话收尾的 finally 里直接调即可。空问轮(只有助手说过话,如纯问候)不写;
     * 有问无答的轮次(没等来回答就挂了)也不写——空 outcome 的聊天气泡没有意义。
     */
    suspend fun close() {
        val (snapshot, id) = synchronized(lock) {
            if (closed) return
            closed = true
            turns() to conversationId()
        }
        if (id == null) return
        withContext(NonCancellable) {
            snapshot.filter { it.question.isNotBlank() && it.answer.isNotBlank() }.forEach { turn ->
                appendTurn(id, ChatTurn(userText = turn.question, outcome = turn.answer, status = "succeeded", atMs = turn.at))
            }
        }
    }
}
