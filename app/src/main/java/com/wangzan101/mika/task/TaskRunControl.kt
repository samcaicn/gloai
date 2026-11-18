package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.child.ChildAgentRunner
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 一个任务的生命周期控制与用户交互(取消、接管、问答、确认、收尾)。原 TaskRun 里的对应函数整体搬到这里。
 * 需要读/写的 TaskRun 瞬时字段经 lambda 注入。
 */
internal class TaskRunControl(
    private val service: TaskService,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val state: TaskRunState,
    private val inbox: RunInbox,
    private val loop: () -> AgentLoop?,
    private val gate: () -> TaskGate?,
    private val takeover: () -> Takeover?,
    private val children: () -> ChildAgentRunner?,
    private val job: () -> Job?,
    private val cancelRequested: () -> Boolean,
    private val setCancelRequested: (Boolean) -> Unit,
    private val shownRef: () -> Boolean,
    private val publish: (TaskState) -> Unit,
    private val key: String,
    private val setStopPressed: (Boolean) -> Unit,
) {
    fun cancel() {
        setStopPressed(true)
        children()?.cancelAll(RunId(key))
        val running = loop()
        val t = takeover()
        when {
            t != null -> scope.launch { t.cancel() }
            running != null -> inbox.cancel()
            else -> {
                // 刚从队里放出来、协程还没挂上时点的取消:挂上时立即取消
                setCancelRequested(true)
                job()?.cancel()
            }
        }
    }

    fun resume() {
        val g = gate() ?: return
        val wasOnPage = g.userOnPagePending
        g.resume()
        // 用户在页面上操作的那种暂停:循环可能还没走到关卡(还在想),按它此刻的状态重新显示,不再写着暂停
        if (wasOnPage) loop()?.state?.value?.takeIf { it is TaskState.Running }?.let(publish)
    }

    fun takeOver(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.handOver(waitKey) } } }
    fun handBack(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.handBack(waitKey) } } }
    fun finishedMyself(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.finishedMyself(waitKey) } } }
    fun skip(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.skip(waitKey) } } }
    fun putAside(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.putAside(waitKey) } } }

    /** 回答提问;已经不在等(超时后才答、已答过)时调 [tooLate] 告诉用户这条回答没用上。 */
    fun answer(text: String, tooLate: () -> Unit) {
        if (loop() == null) return
        if (state._state.value is TaskState.WaitingInput) scope.launch {
            if (inbox.send(RunInput.Answer(text)) != InputReceipt.Accepted) tooLate()
        }
        else tooLate()
    }

    /** 渠道已经核对过提问编号；仍由同一个收件箱回答，避免绕过运行生命周期。 */
    suspend fun answerFromChannel(text: String, askId: String): Boolean =
        inbox.send(RunInput.Answer(text, askId)) == InputReceipt.Accepted

    /** 新指令取代尚未回答的普通提问；旧任务按取消收尾，排队的新指令随后执行。 */
    fun cancelAsk(askId: String): Boolean = loop()?.cancelInput(askId) == true

    val activeAsk: Delivery.Ask? get() = loop()?.activeAsk

    /** 通知和卡片的按钮都带着它所属的申报 [key];点在已经换掉的旧通知、旧卡片上不算数。 */
    fun confirm(choice: ConfirmChoice, key: Int?) {
        val running = loop()
        val waiting = state._state.value as? TaskState.WaitingConfirmation
        if (running != null && waiting != null && key == ConfirmationNotifier.requestKey(waiting)) {
            scope.launch { inbox.send(RunInput.Confirm(choice)) }
        }
    }

    /**
     * 任务的终态:[execute] 收尾之后才写,不经 [publish]。不论显示的是不是自己,都先把本次的运行编号记到
     * 自己对话名下([TaskService.endedRunIds]),界面收到结束状态时按对话取;显示中时也写 [TaskService.endedRunId]。
     */
    fun end(end: TaskState) = synchronized(service.taskState.shownLock) {
        service.taskState._endedRunIds.value = service.taskState._endedRunIds.value + (conversationId to state._runId.value)
        if (shownRef()) service.taskState._endedRunId.value = state._runId.value
        state.setState(end)
    }

    /** 超出并行上限在排队;放出来开跑时 [dequeue]。 */
    fun queue(position: Int) { state.setState(TaskState.Held(GateReason.queued(trPlural(R.plurals.queue_ahead, position)))) }
    fun dequeue() { state.setState(TaskState.Running(0, TaskService.PREPARING)) }

    /** 网页接管界面顶部的说明:接管原因的话,付款时再带上商品、金额、地址。 */
    fun takeoverMessage(state: TaskState): String? {
        val w = state as? TaskState.WaitingTakeover ?: return null
        return listOfNotNull(
            w.message.takeIf { it.isNotBlank() },
            w.item?.let { tr(R.string.payment_item, it) },
            w.amount?.let { tr(R.string.payment_amount, it) },
            w.address?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }
    }
}
