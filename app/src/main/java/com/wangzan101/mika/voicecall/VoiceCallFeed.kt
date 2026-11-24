package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 通话页对话流里的一条:一句话(你/Mika,说到一半的是 partial),或一张办事步骤卡;
 * [Earlier] 是分隔线,它上面 earlier 为 true 的是打开通话前这条会话里已有的内容。
 */
sealed interface VoiceCallFeedItem {
    val id: Long

    data class Said(
        override val id: Long,
        val speaker: Speaker,
        val text: String,
        val partial: Boolean,
        val earlier: Boolean = false,
    ) : VoiceCallFeedItem

    /** 「以上是之前的对话」那条分隔线。 */
    data class Earlier(override val id: Long) : VoiceCallFeedItem

    /** 前台派出的一件任务:步骤按出现顺序排,最后一条是当前这步;[phase] 是任务此刻所处的阶段。 */
    data class Task(
        override val id: Long,
        val taskId: String,
        val title: String,
        val steps: List<String>,
        val phase: DeskTaskPhase,
        val earlier: Boolean = false,
    ) : VoiceCallFeedItem {
        /** 还没收尾(在做、排队、等你处理)。 */
        val running: Boolean get() = phase == DeskTaskPhase.Running || phase == DeskTaskPhase.Queued || phase == DeskTaskPhase.NeedsYou
    }

    enum class Speaker { User, Mika }
}

/**
 * 通话页的对话流:把会话的转写(含说到一半的)和前台派出的任务排成一列,给页面直接画气泡。
 * 只管显示,不写存储(写回聊天是 [VoiceCallTranscript] 的事)。各回调可能来自不同线程,内部加锁。
 *
 * - 你说的:partial 段落替换同一条,final 落定。
 * - Mika 说的:草稿逐段替换同一条,final(或被打断时的 non-final)落定。
 * - 办事:前台派出的每件任务一张卡,见 [onTasks]。
 */
class VoiceCallFeed(private val maxItems: Int = MAX_ITEMS) {
    private val _items = MutableStateFlow<List<VoiceCallFeedItem>>(emptyList())
    val items: StateFlow<List<VoiceCallFeedItem>> = _items

    private val lock = Any()
    private var nextId = 0L
    /** 第一次收到任务板时已收尾的任务编号(之前派的,不上卡);还没收到过时为 null。 */
    private var ignored: Set<String>? = null

    /**
     * 打开通话时把这条会话已有的内容摆在上面(只在对话流还空着时):最近 [EARLIER_TURNS] 轮,
     * 用户的话和回话各一条气泡(回话转成纯文字、太长截断;没办成的只留用户的话),那一轮派过的任务按 [tasks] 里的样子各一张卡,
     * 最后一条分隔线。之后任务板再变,这些卡照样跟着变,不另开一张。
     */
    fun seed(turns: List<ChatTurn>, tasks: List<DeskTaskView>, plain: (String) -> String = { it }) {
        synchronized(lock) {
            if (_items.value.isNotEmpty()) return
            val out = mutableListOf<VoiceCallFeedItem>()
            for (turn in turns.takeLast(EARLIER_TURNS)) {
                if (turn.userText.isNotBlank()) out += VoiceCallFeedItem.Said(nextId++, VoiceCallFeedItem.Speaker.User, cut(turn.userText), false, earlier = true)
                val reply = turn.outcome.takeIf { DeskTurn.isDesk(turn) || turn.status in ANSWERED }?.let(plain)
                if (!reply.isNullOrBlank()) out += VoiceCallFeedItem.Said(nextId++, VoiceCallFeedItem.Speaker.Mika, cut(reply), false, earlier = true)
                for (taskId in turn.deskTasks) {
                    val view = tasks.firstOrNull { it.taskId == taskId } ?: continue
                    if (out.none { it is VoiceCallFeedItem.Task && it.taskId == taskId }) {
                        out += VoiceCallFeedItem.Task(nextId++, view.taskId, view.title, steps(view), view.state, earlier = true)
                    }
                }
            }
            if (out.isEmpty()) return
            publish(out + VoiceCallFeedItem.Earlier(nextId++))
        }
    }

    private fun cut(text: String): String {
        val clean = text.trim()
        return if (clean.length <= EARLIER_TEXT_CHARS) clean else clean.take(EARLIER_TEXT_CHARS).trimEnd() + "…"
    }

    private fun steps(view: DeskTaskView) = (view.recent + listOfNotNull(view.line)).distinct()

    fun onUser(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.User, text, partial = !isFinal)

    fun onAssistantDraft(text: String) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = true)

    /** 助手一段话落定;被打断的(isFinal=false)也落定,说到哪算哪。 */
    @Suppress("UNUSED_PARAMETER")
    fun onAssistant(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = false)

    /**
     * 前台这次对话的任务板:每件任务一张卡(按任务编号),第一次出现时排到对话流末尾,
     * 之后每出现一句新进展加一步、阶段跟着变。第一次收到时已经收尾的是之前派的旧任务,不上卡。
     */
    fun onTasks(views: List<DeskTaskView>) {
        synchronized(lock) {
            val baseline = ignored ?: views.filter { it.state in ENDED }.map { it.taskId }.toSet().also { ignored = it }
            var current = _items.value
            // 已经有卡的(之前对话里摆上来的)照样跟着变;没卡又已收尾的是更早的,不上卡
            for (view in views.filter { it.taskId !in baseline || hasCard(current, it.taskId) }) current = withTask(current, view)
            if (current !== _items.value) publish(current)
        }
    }

    private fun hasCard(current: List<VoiceCallFeedItem>, taskId: String) =
        current.any { it is VoiceCallFeedItem.Task && it.taskId == taskId }

    private fun withTask(current: List<VoiceCallFeedItem>, view: DeskTaskView): List<VoiceCallFeedItem> {
        val seen = steps(view)
        val index = current.indexOfFirst { it is VoiceCallFeedItem.Task && it.taskId == view.taskId }
        if (index < 0) return current + VoiceCallFeedItem.Task(nextId++, view.taskId, view.title, seen, view.state)
        val card = current[index] as VoiceCallFeedItem.Task
        val steps = card.steps + seen.filter { it !in card.steps }
        if (steps == card.steps && view.state == card.phase) return current
        return current.toMutableList().also { it[index] = card.copy(steps = steps, phase = view.state) }
    }

    private fun said(speaker: VoiceCallFeedItem.Speaker, text: String, partial: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val current = _items.value
            val index = current.indexOfLast { it is VoiceCallFeedItem.Said && it.speaker == speaker && it.partial }
            publish(
                if (index >= 0) {
                    val item = current[index] as VoiceCallFeedItem.Said
                    current.toMutableList().also { it[index] = item.copy(text = clean, partial = partial) }
                } else {
                    current + VoiceCallFeedItem.Said(nextId++, speaker, clean, partial)
                },
            )
        }
    }

    private fun publish(items: List<VoiceCallFeedItem>) {
        _items.value = if (items.size > maxItems) items.takeLast(maxItems) else items
    }

    companion object {
        const val MAX_ITEMS = 80

        /** 打开通话时摆上来的之前的对话最多这么多轮。 */
        const val EARLIER_TURNS = 20

        /** 之前的一句话在通话页上最多显示这么多字,多的截掉。 */
        const val EARLIER_TEXT_CHARS = 200

        /** 有回答可显示的非前台轮次。 */
        private val ANSWERED = setOf("succeeded", "unverified")
        private val ENDED = setOf(DeskTaskPhase.Done, DeskTaskPhase.Failed, DeskTaskPhase.Cancelled)
    }
}
