package com.wangzan101.mika.voicecall

import com.wangzan101.mika.chat.ConversationCall
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一通语音通话的文字聚合:用户每段完整话语开一轮([onUser]),助手在两段用户话语之间
 * 说的都并进当轮的答([onAssistant]);与聊天走同一个存储入口写回发起会话。
 * 给了 [writeScope] 时边说边存:用户开口说下一句,上一轮就说完了,当场写进会话,通话中途 App 被杀也只丢正在说的那一轮;
 * 会话结束([close])补写还没写的。没给时到结束才一次写完。
 *
 * 装配:会话编排的用户/助手转写回调(只要 final)喂进来;[conversationId] 是发起这次通话
 * 的会话(从胶囊直开、没有发起会话时为 null,不写回);[appendTurn] 接对话存储的逐轮写入。
 *
 * 通话记录:接通时([onConnected])给会话记一条 [ConversationCall],结束时([close])补上挂断时刻、原因和有没有说上话;
 * 写进会话的每一轮都带上 [callId],聊天页据此画通话的起止线。没接通的不记。
 */
class VoiceCallTranscript(
    private val conversationId: () -> String?,
    private val appendTurn: suspend (conversationId: String, turn: ChatTurn) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 边说边存用的作用域(要比通话长寿,装配方给应用级的);null 时只在 [close] 写。 */
    private val writeScope: CoroutineScope? = null,
    /** 这一通的编号,记在通话记录和每一轮上。 */
    val callId: String = java.util.UUID.randomUUID().toString(),
    /** 写一条通话记录(同编号的替换旧的)。 */
    private val recordCall: suspend (conversationId: String, call: ConversationCall) -> Unit = { _, _ -> },
) {
    /** 一轮问答:用户问的、助手答的(多段按行拼)、问的时刻(毫秒)、这轮前台派出的任务编号。 */
    data class Turn(val question: String, val answer: String, val at: Long, val tasks: List<String> = emptyList())

    private val lock = Any()
    private val done = mutableListOf<Turn>()
    private var open: Turn? = null
    private var closed = false

    /** 交给写入协程的已说完的轮次(在 [done] 里的下标);写入协程按顺序一条条写。 */
    private val queue = Channel<Pair<String, Int>>(Channel.UNLIMITED)
    private var writer: Job? = null

    /** [done] 里前这么多条已经写过(或不值得写、已跳过);写入协程和 [close] 都只往后推。 */
    @Volatile private var written = 0

    /** 接通的时刻;没接通为 null。 */
    private var connectedAt: Long? = null
    private var startWrite: Job? = null

    /** 接通了:记下接通的时刻(重连再接通不改),给会话记一条还没结束的通话。 */
    fun onConnected(at: Long) {
        val record = synchronized(lock) {
            if (closed || connectedAt != null) return
            connectedAt = at
            ConversationCall(callId, at)
        }
        val id = conversationId() ?: return
        val scope = writeScope ?: return
        startWrite = scope.launch { withContext(NonCancellable) { recordCall(id, record) } }
    }

    /** 用户说了一段完整的话:闭合上一轮,开新一轮。空白忽略。 */
    fun onUser(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            open?.let {
                done += it
                handOver(done.lastIndex)
            }
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

    /** 这一轮前台派出了一件任务:记在当轮上,写回后会话重开时能接回它。没有当轮时忽略。 */
    fun onTask(taskId: String) {
        synchronized(lock) {
            if (closed) return
            val current = open ?: return
            if (taskId !in current.tasks) open = current.copy(tasks = current.tasks + taskId)
        }
    }

    /** 说完的一轮交给写入协程(在锁里调);没有写入作用域或没有发起会话时留到 [close]。 */
    private fun handOver(index: Int) {
        val scope = writeScope ?: return
        val id = conversationId() ?: return
        if (writer == null) {
            writer = scope.launch {
                for ((conversation, i) in queue) {
                    val turn = synchronized(lock) { done[i] }
                    if (worthWriting(turn)) withContext(NonCancellable) { appendTurn(conversation, chatTurn(turn)) }
                    written = i + 1
                }
            }
        }
        queue.trySend(id to index)
    }

    /** 截至此刻的全部轮次,含还没等来回答的那一轮。 */
    fun turns(): List<Turn> = synchronized(lock) { done + listOfNotNull(open) }

    /**
     * 会话结束写回:把有问有答的轮次逐条写进发起会话,只写一次——
     * 重复结束不再写;收尾在通话协程域被取消时也照常写完(NonCancellable),
     * 装配方在通话收尾的 finally 里直接调即可。空问轮(只有助手说过话,如纯问候)不写;
     * 有问无答、也没派任务的轮次(没等来回答就挂了)也不写——空 outcome 的聊天气泡没有意义;
     * 派了任务的轮次照写,带上任务编号。
     */
    suspend fun close(endReason: String = ConversationCall.END_HANGUP) {
        val (writing, id) = synchronized(lock) {
            if (closed) return
            closed = true
            queue.close()
            writer to conversationId()
        }
        val endedAt = nowMillis()
        withContext(NonCancellable) {
            // 先等写入协程把交给它的写完(它被撤了也不要紧:没写的下面接着写),再补其余的
            writing?.join()
            if (id == null) return@withContext
            turns().drop(written).filter(::worthWriting).forEach { appendTurn(id, chatTurn(it)) }
            // 接通过的才记:先等接通那条写完,再补上结束
            val start = connectedAt ?: return@withContext
            startWrite?.join()
            recordCall(id, ConversationCall(callId, start, endedAt, endReason, spoke = turns().any(::worthWriting)))
        }
    }

    private fun worthWriting(turn: Turn) = turn.question.isNotBlank() && (turn.answer.isNotBlank() || turn.tasks.isNotEmpty())

    private fun chatTurn(turn: Turn) =
        ChatTurn(userText = turn.question, outcome = turn.answer, status = DeskTurn.STATUS, atMs = turn.at, deskTasks = turn.tasks, callId = callId)
}
