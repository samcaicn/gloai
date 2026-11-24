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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 问题送到了哪儿:弹了通知(会话里也有)、只进了会话(通知发不出去)、哪儿都没送到。 */
enum class OwnerReach { Notified, InConversation, Unreachable }

/** 电话里请示主人的通道:写进这通电话所属的会话,同时发一条能直接回复的通知。 */
interface OwnerAsk {
    /** 把 [question] 送给主人;主人(在会话里或通知里)回复时用原文调 [onReply],先到的算数。 */
    fun ask(question: String, onReply: (String) -> Unit): OwnerReach

    /** 撤掉还没回的问题和它的通知;之后迟到的回复不再回调。 */
    fun cancel()
}

/**
 * AI 电话里的前台:电话那头是第三方,所以它只有一个工具 [DeskTools.ASK_OWNER](请示主人),
 * 没有派活、补充、取消,也没有查询类工具;用户那套前台工具名到这里一律 unsupported,绝不走用户前台的执行器。
 *
 * 主人的回复和所属任务的状态都只交给模型、由它转述,绝不原样念给对方:
 * - 回复用 [cue](一条提示,模型读了接着说);没说出去(被打断)重发一次;两次都没说出去就留着,模型下次请示时作为结果拿到。
 * - 协议收不了提示([canCue] 为 false):回复一律留着,请示结果和提示词都明说回复不会自己到、要再请示一次去取;
 *   再请示时还没回就是 still_waiting(不算忙)。挂断时还没取走的交给 [unrelayed] 记进通话记录。
 * - 状态:标准通话每轮作为尾随附注([Companion.background]);实时通话开场写进提示词,之后变了用 [context] 当背景推,
 *   推不了就只在请示结果里带着。
 */
class PhoneDesk(
    private val ownerTask: () -> StateFlow<TaskState>?,
    private val ask: OwnerAsk,
    private val cue: (text: String, ended: (spoken: Boolean) -> Unit) -> Boolean = { _, _ -> false },
    private val context: (text: String) -> Boolean = { false },
    /** 这条通话的协议能不能收提示([cue]);不能时回复不会自己到,让模型再请示一次去取。见 [canCue]。 */
    private val canCue: Boolean = true,
    private val unrelayed: (reply: String) -> Unit = {},
) {
    private val lock = Any()
    private var pending = false
    private var closed = false
    /** 主人回了、还没能交给模型的那句。 */
    private var held: String? = null
    private val ended = CompletableDeferred<Unit>()

    suspend fun execute(name: String, args: JsonObject): JsonObject {
        if (name != DeskTools.ASK_OWNER) return status("unsupported")
        val question = (args["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.take(MAX_QUESTION_CHARS)
        if (question.isNullOrEmpty()) return status("invalid_arguments")
        synchronized(lock) {
            if (closed) return unavailable()
            held?.let { reply ->
                held = null
                return result("owner_replied", "The owner has answered. Relay it to the caller in your own words; never read it out verbatim.") {
                    put("reply", reply)
                }
            }
            if (pending) {
                // 收不了提示时,再请示就是来取回复的:还没回,不算忙
                return if (canCue) result("busy", "Still waiting for the owner. Ask the caller to hold on a moment, or offer to get back to them.")
                else result("still_waiting", "No answer from the owner yet. Ask the caller to hold on a moment, or offer to get back to them; " +
                    "call ask_owner again a little later to collect the answer.")
            }
            pending = true
        }
        // 只认这一次提问的回复:回过一次就不再收
        var answered = false
        val reach = ask.ask(question) { reply ->
            val relay = synchronized(lock) {
                if (closed || answered) return@synchronized false
                answered = true
                pending = false
                true
            }
            if (relay && reply.isNotBlank()) relay(reply.trim(), attemptsLeft = 2)
        }
        return when (reach) {
            OwnerReach.Unreachable -> {
                synchronized(lock) { pending = false }
                unavailable()
            }
            OwnerReach.Notified -> result("asked", "Tell the caller you are checking and will reply shortly. " + collectHint())
            OwnerReach.InConversation -> result("asked", "The owner may not see this right away. Tell the caller you have " +
                "passed the question on; if no answer comes during this call, say the owner will get back to them. " + collectHint())
        }
    }

    /** 回复怎么到:能收提示时会作为提示送来;收不了时要模型自己再请示去取。 */
    private fun collectHint() = if (canCue) "The owner's answer will reach you as a note; relay it in your own words."
    else "The owner's answer will NOT arrive by itself: call ask_owner again to collect it when the caller is waiting " +
        "or after a short while, then relay it in your own words."

    /** 这通电话所属任务此刻的状态,一句话;没有所属任务时为 null。 */
    fun ownerTaskLine(): String? = renderOwnerTask(ownerTask()?.value)

    /**
     * 实时通话:开场的状态已经写进提示词,之后状态变了(在做 → 做完这一级的变化,不是每一步)就当背景推一句,不让模型为此开口。
     * 一直等到 [close] 才返回。
     */
    suspend fun pushOwnerTaskChanges() {
        val flow = ownerTask() ?: return
        coroutineScope {
            val watcher = launch {
                flow.map { renderOwnerTask(it) }.distinctUntilChanged().drop(1).collect { line ->
                    if (line != null && !isClosed()) context(CUE_MARK + line)
                }
            }
            ended.await()
            watcher.cancel()
        }
    }

    /** 通话结束:撤掉待回的问题和通知,迟到的回复不再转进来;还没交给模型的回复记进通话记录。 */
    fun close() {
        val left = synchronized(lock) {
            if (closed) return
            closed = true
            pending = false
            held.also { held = null }
        }
        ask.cancel()
        ended.complete(Unit)
        left?.let(unrelayed)
    }

    private fun isClosed() = synchronized(lock) { closed }

    private fun relay(reply: String, attemptsLeft: Int) {
        if (attemptsLeft <= 0) return hold(reply)
        if (isClosed()) return unrelayed(reply)
        if (!canCue) return hold(reply)
        val accepted = cue(replyCue(reply)) { spoken -> if (!spoken) relay(reply, attemptsLeft - 1) }
        // 协议没有提示输入:重发也没用,直接留给下一次请示
        if (!accepted) hold(reply)
    }

    private fun hold(reply: String) {
        val stored = synchronized(lock) {
            if (closed) false else { held = reply; true }
        }
        if (!stored) unrelayed(reply)
    }

    private fun status(value: String) = buildJsonObject { put("status", value) }

    private fun result(status: String, next: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("status", status)
        extra()
        put("next", next)
        ownerTaskLine()?.let { put("owner_task", it) }
    }

    private fun unavailable() = result("unavailable",
        "The owner can't be reached right now. Don't commit to anything; offer to get back to the caller later.")

    companion object {
        /** 提示的开头:告诉模型这不是对方说的话,也不要照念。 */
        const val CUE_MARK = "[Mika 给你的提示，不是对方说的话，不要照念] "
        const val OWNER_TASK_PREFIX = "这通电话所属的任务："
        const val MAX_QUESTION_CHARS = 300

        /** 主人回复交给模型的那条提示。 */
        fun replyCue(reply: String): String = CUE_MARK + "主人回复了你刚才的请示：「" + reply + "」。用你自己的话简短转告对方。"

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
        fun systemPrompt(phonePrompt: String, task: CallTaskBrief?, openingOwnerTask: String?, canCue: Boolean = true): String {
            val brief = buildString {
                append(task?.let { "目标：" + it.goal } ?: "本通电话没有交代具体目标。")
                task?.questions?.takeIf { it.isNotEmpty() }?.let { append("\n必问事项：").append(it.joinToString("；")) }
                openingOwnerTask?.let { append('\n').append(it) }
                append('\n').append(if (canCue) RELAY_BY_CUE else RELAY_BY_ASKING)
            }
            return phonePrompt + "\n\n" + DeskPrompt.phone(brief, POLICY)
        }

        private const val POLICY = "以上文的通话规则和授权边界为准；与上文冲突时从严。"

        /**
         * 这条通话能不能把提示交给模型、让它接着说:标准通话(记进模型历史的一轮)和能加 system 文字项的实时协议(加一项再请它回话)能;
         * 双工对话协议能交给模型的文字只有照念的 speech_text_buffer,不能。和各会话的 sendCue 保持一致。
         */
        fun canCue(standard: Boolean, protocol: CallVoiceProtocol): Boolean = standard || protocol == CallVoiceProtocol.OPENAI_REALTIME

        /** 能收提示的通话:回复会自己送到。 */
        const val RELAY_BY_CUE = "主人的回复会以「[Mika 给你的提示…]」开头的提示送到你这里：用你自己的话转告对方，不要照念提示。"

        /** 收不了提示的通话:回复不会自己到,要再请示一次去取。 */
        const val RELAY_BY_ASKING = "主人的回复不会自己送到你这里：请示之后，对方在等或过一会儿，再调用一次请示主人去取；" +
            "结果是 owner_replied 就用你自己的话转告对方，是 still_waiting 就请对方稍等或说回头答复。"
    }
}
