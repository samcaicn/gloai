package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunInbox
import java.util.UUID

/**
 * 一个任务进行中插的话的收发、去向与撤回。原 TaskRun 里的插话逻辑整体搬到这里。
 * 需要读的 TaskRun 瞬时字段([loop]/[stopPressed]/[mcpExternalId])经 lambda 注入,读当前值。
 */
internal class SteerCoordinator(
    private val service: TaskService,
    private val conversationId: String,
    private val state: TaskRunState,
    private val inbox: RunInbox,
    private val loop: () -> AgentLoop?,
    private val stopPressed: () -> Boolean,
    private val mcpExternalId: () -> String?,
) {
    /** 插话撤掉的那段回复,等这一批插话里第一句交给模型时挂到它上面。 */
    private val steerJoin = SteerJoin()

    /**
     * 同一对话在任务进行中的新原话，交给下一次模型步；准备阶段也会先排在 Inbox。
     * [messageId] 是界面给这句话的编号,界面据此标出它的去向(见 [SteerNote])。
     */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) {
        synchronized(service.taskState.shownLock) {
            state._steers.value = state._steers.value + SteerNote(messageId, text, SteerState.Pending)
            // 收件箱收下了就马上告诉用户:模型此刻的请求已撤掉,下一步就带着这句话想(交给模型时再改成已加入)
            if (inbox.steer(messageId, text) == InputReceipt.Accepted) markSteers(setOf(messageId), SteerState.Received, from = SteerState.Pending)
            else {
                if (stopPressed()) markSteers(setOf(messageId), SteerState.Cancelled)
                else {
                    markSteers(setOf(messageId), SteerState.Next)
                    service.deferMessage(conversationId, text, messageId)
                }
            }
        }
    }

    /** 远程追加只归当前任务;与状态发布互斥,收件箱再原子核对是否已经结束/取消。 */
    fun steerRemote(text: String): String? = synchronized(state.publishLock) delivery@{
        if (state.ended || stopPressed() || mcpExternalId() == null || state._state.value !is TaskState.Running) return@delivery null
        if (loop()?.state?.value?.let { it !is TaskState.Running } == true) return@delivery null
        val id = UUID.randomUUID().toString()
        synchronized(service.taskState.shownLock) {
            // 先记消息,再唤醒模型;消费事件可能紧跟着发生,不能把 Joined 改回 Received。
            state._steers.value = state._steers.value + SteerNote(id, text, SteerState.Received, remote = true)
            if (inbox.steerCurrent(id, text) != InputReceipt.Accepted) {
                state._steers.value = state._steers.value.filterNot { it.id == id }
                return@delivery null
            }
            id
        }
    }

    /** 撤回手机上插的、模型还没取走的那句;已取走、已收尾或是电脑追加的(收件箱拒绝)返回 false,去向不变。 */
    fun withdrawSteer(messageId: String): Boolean = synchronized(service.taskState.shownLock) {
        if (!inbox.withdrawSteer(messageId)) return@synchronized false
        markSteers(setOf(messageId), SteerState.Withdrawn)
        true
    }

    /** 手机插话可转下一任务;远程插话只能用于当前任务,未消费的一律作废,不改变来源和权限。 */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed()) {
        val left = inbox.handoffUnconsumed()
        val (discard, next) = left.partition { cancelled || it.currentTaskOnly }
        markSteers(discard.map { it.messageId }.toSet(), SteerState.Cancelled)
        markSteers(next.map { it.messageId }.toSet(), SteerState.Next)
        next.forEach { service.deferMessage(conversationId, it.text, it.messageId) }
    }

    fun joinedSteers(): List<String> = joined().map { it.text }

    /** 和 [joinedSteers] 一一对应:每句插话打断的那段回复,没打断的是空串。 */
    fun joinedInterrupted(): List<String> = joined().map { it.interrupted.orEmpty() }

    /** 和 [joinedSteers] 一一对应:每句插话交给模型之前已经说完的那段回复,没有的是空串。 */
    fun joinedFinished(): List<String> = joined().map { it.finished.orEmpty() }

    private fun joined() = state._steers.value.filter { it.state == SteerState.Joined }

    private fun joinSteer(id: String) {
        synchronized(service.taskState.shownLock) { state._steers.value = steerJoin.joined(state._steers.value, id) }
    }

    /** 把 [ids] 这几句改成 [to];给了 [from] 时只改还在那个状态的(收下和交给模型可能先后颠倒,不往回改)。 */
    private fun markSteers(ids: Set<String>, to: SteerState, from: SteerState? = null) {
        if (ids.isEmpty()) return
        synchronized(service.taskState.shownLock) {
            state._steers.value = state._steers.value.map { if (it.id in ids && (from == null || it.state == from)) it.copy(state = to) else it }
        }
    }

    // 供 TaskRun 的 liveEvents 委托(插话相关事件)
    fun onInterrupted(detail: String) { steerJoin.interrupted(detail) }
    fun onFinished(detail: String) { steerJoin.finished(detail) }
    fun onSteerReceived(detail: String) { joinSteer(detail) }
}
