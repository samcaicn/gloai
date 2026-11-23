package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskBackground
import com.wangzan101.mika.core.frontdesk.DeskPrompt
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.deskPhaseOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 电话里请示主人的通道(系统通知里直接回复)。 */
interface OwnerAsk {
    /** 把 [question] 发给主人;发不出去返回 false。主人回复时用原文调 [onReply]。 */
    fun ask(question: String, onReply: (String) -> Unit): Boolean

    /** 撤掉还没回的问题和它的通知;之后迟到的回复不再回调。 */
    fun cancel()
}

/**
 * AI 电话里的前台:电话那头是第三方,所以它只有一个工具 [DeskTools.ASK_OWNER](请示主人),
 * 没有派活、补充、取消,也没有查询类工具;用户那套前台工具名到这里一律 unsupported,绝不走用户前台的执行器。
 * 这通电话所属任务([ownerTask])的状态由代码渲染成一句话放进上下文:标准通话每轮作为尾随附注([Companion.background]),
 * 实时通话开场写进提示词、之后变了用 [notice] 推([pushOwnerTaskChanges])。
 * 主人回复后用 [notice] 转进通话;没说出去(收不下或被打断)重发一次,还不行交给 [unrelayed] 记进通话记录。
 */
class PhoneDesk(
    private val ownerTask: () -> StateFlow<TaskState>?,
    private val ask: OwnerAsk,
    private val notice: (text: String, ended: (spoken: Boolean) -> Unit) -> Boolean = { _, _ -> false },
    private val unrelayed: (reply: String) -> Unit = {},
) {
    private val lock = Any()
    private var pending = false
    private var closed = false
    private val ended = CompletableDeferred<Unit>()

    suspend fun execute(name: String, args: JsonObject): JsonObject {
        if (name != DeskTools.ASK_OWNER) return status("unsupported")
        val question = (args["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.take(MAX_QUESTION_CHARS)
        if (question.isNullOrEmpty()) return status("invalid_arguments")
        synchronized(lock) {
            if (closed) return unavailable()
            if (pending) return status("busy")
            pending = true
        }
        // 只认这一次提问的回复:回过一次就不再收
        var answered = false
        val posted = ask.ask(question) { reply ->
            val relay = synchronized(lock) {
                if (closed || answered) return@synchronized false
                answered = true
                pending = false
                true
            }
            if (relay && reply.isNotBlank()) relay(reply.trim(), attemptsLeft = 2)
        }
        if (!posted) {
            synchronized(lock) { pending = false }
            return unavailable()
        }
        return buildJsonObject {
            put("status", "asked")
            put("next", "Tell the caller you are checking and will reply shortly.")
        }
    }

    /** 这通电话所属任务此刻的状态,一句话;没有所属任务时为 null。 */
    fun ownerTaskLine(): String? = renderOwnerTask(ownerTask()?.value)

    /**
     * 实时通话:开场的状态已经写进提示词,之后状态变了(在做 → 做完这一级的变化,不是每一步)就推一句。
     * 一直等到 [close] 才返回。
     */
    suspend fun pushOwnerTaskChanges() {
        val flow = ownerTask() ?: return
        coroutineScope {
            val watcher = launch {
                flow.map { renderOwnerTask(it) }.distinctUntilChanged().drop(1).collect { line ->
                    if (line != null && !isClosed()) notice(line) {}
                }
            }
            ended.await()
            watcher.cancel()
        }
    }

    /** 通话结束:撤掉待回的问题和通知,之后迟到的回复不再转进来。 */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = false
        }
        ask.cancel()
        ended.complete(Unit)
    }

    private fun isClosed() = synchronized(lock) { closed }

    private fun relay(reply: String, attemptsLeft: Int) {
        if (attemptsLeft <= 0 || isClosed()) {
            unrelayed(reply)
            return
        }
        val accepted = notice(OWNER_REPLY_PREFIX + reply) { spoken -> if (!spoken) relay(reply, attemptsLeft - 1) }
        if (!accepted) relay(reply, attemptsLeft - 1)
    }

    private fun status(value: String) = buildJsonObject { put("status", value) }

    private fun unavailable() = buildJsonObject {
        put("status", "unavailable")
        put("next", "The owner can't be reached right now. Don't commit to anything; offer to get back to the caller later.")
    }

    companion object {
        /** 主人的回复转进通话时的开头,逐字使用。 */
        const val OWNER_REPLY_PREFIX = "主人回复："
        const val OWNER_TASK_PREFIX = "这通电话所属的后台任务："
        const val MAX_QUESTION_CHARS = 300

        /** 标准通话的尾随附注:每次调模型前取一次 [owner] 的当前状态,不进 system 提示(保住缓存前缀)。 */
        fun background(owner: StateFlow<TaskState>): DeskBackground = DeskBackground { renderOwnerTask(owner.value) }

        fun renderOwnerTask(state: TaskState?): String? = state?.let {
            OWNER_TASK_PREFIX + when (deskPhaseOf(it)) {
                DeskTaskPhase.Running -> "进行中"
                DeskTaskPhase.Queued -> "排队中"
                DeskTaskPhase.NeedsYou -> "等主人处理"
                DeskTaskPhase.Done -> "已完成"
                DeskTaskPhase.Failed -> "没做成"
                DeskTaskPhase.Cancelled -> "已取消"
            }
        }

        /**
         * 电话的系统提示:现有电话提示([phonePrompt],含通话规则和本次任务)在前,前台说明在后。
         * [openingOwnerTask] 只在实时通话给(开场时的任务状态);标准通话的状态走尾随附注,提示保持字节不变。
         */
        fun systemPrompt(phonePrompt: String, task: CallTaskBrief?, openingOwnerTask: String?): String {
            val brief = buildString {
                append(task?.let { "目标：" + it.goal } ?: "本通电话没有交代具体目标。")
                task?.questions?.takeIf { it.isNotEmpty() }?.let { append("\n必问事项：").append(it.joinToString("；")) }
                openingOwnerTask?.let { append('\n').append(it) }
            }
            return phonePrompt + "\n\n" + DeskPrompt.phone(brief, POLICY)
        }

        private const val POLICY = "以上文的通话规则和授权边界为准；与上文冲突时从严。"
    }
}
