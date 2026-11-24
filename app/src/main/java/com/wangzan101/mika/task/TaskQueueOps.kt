package com.wangzan101.mika.task

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.i18n.tr
import android.app.ActivityManager
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import com.wangzan101.mika.task.TaskService.Companion.nextResultId
import com.wangzan101.mika.task.TaskService.Companion.notifyDelivery
import com.wangzan101.mika.task.TaskService.Companion.ID_RESULT
import com.wangzan101.mika.task.TaskService.Companion.start

// 任务服务:并发名额、追问排队与渠道回执
internal fun TaskService.localDeliveryChannel(scheduled: Boolean) = LocalChannel { _, delivery ->
    if (delivery is Delivery.Ask) {
        center.byConversation(delivery.threadId)?.let(::refreshNotifications)
        SendOutcome.Delivered
    } else {
        val run = center.byConversation(delivery.threadId)
        val resultId = if (delivery is Delivery.Result && run?.parallel == false) ID_RESULT else nextResultId()
        val own = delivery !is Delivery.Result ||
            ownResultNotice(delivery, run?.key, run?.state?.value ?: TaskState.Idle, (application as MikaApp).deskReportsEnding)
        notifyDelivery(this, delivery, resultId, scheduled, ownResult = own)
    }
}

/**
 * 任务办完时自己发不发「任务完成」通知:运行 [runKey] 以 [end] 收尾、有前台此刻正跟着它并会把这个结局说出来
 * ([deskReportsEnding])时不发,前台那一条就是唯一的声音;进程没了以后补送的([Delivery.Result.restored])、
 * 找不到是哪次运行的,和其余情况一样照旧自己发。
 */
internal fun ownResultNotice(
    delivery: Delivery.Result,
    runKey: String?,
    end: TaskState,
    deskReportsEnding: (runKey: String, end: TaskState) -> Boolean,
): Boolean = delivery.restored || runKey == null || !deskReportsEnding(runKey, end)

internal fun TaskService.limit(): ParallelLimit {
    if (!parallel) return ParallelLimit(1, null)
    val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
    return ParallelCapability.detect(Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(this).parallelMax(), taskState.secondDisplayFailed)
        .also { taskState._parallelLimit.value = it }
}

/** 登记处里没人用的最小槽位;只在登记处的锁内调用。 */
internal fun TaskService.freeSlot(): Int {
    val used = center.runs().map { it.slot }.toSet()
    return generateSequence(0) { it + 1 }.first { it !in used }
}

/** 这个对话空着就当场开始;还有任务就留在持久队列里,它收尾时([onRunEnded])交出来。查与留在同一把锁里。 */
internal fun TaskService.deferMessage(threadId: String, text: String, messageId: String) {
    val item = QueuedFollowUp(messageId, threadId, text, System.currentTimeMillis(), QueuedSource.Fallback)
    if (center.deferOrStart(threadId, text, messageId, QueuedSource.Fallback)) startFollowUp(item)
    else keepForeground()
}

/** 用户明确选了「排队」:这个对话还有任务就写进持久队列;刚好空了就当场开始。 */
internal fun TaskService.queueFollowUp(threadId: String, text: String, messageId: String, source: QueuedSource) {
    val item = QueuedFollowUp(messageId, threadId, text, System.currentTimeMillis(), source)
    if (center.deferOrStart(threadId, text, messageId, source)) {
        // 这个对话刚空:不先入队,直接走 startNew;若这中间又来了任务,tryStart 会把它原子放回队列
        startFollowUp(item)
    } else {
        keepForeground()
    }
}

/** 取这个对话的下一件;只取一件,由 startNew 登记或放回。 */
internal fun TaskService.drainReady(conversationId: String?) {
    if (teachingBlocks()) return
    center.takeReady(conversationId).forEach(::startFollowUp)
}

/** 起动一件排队项;先确认已经写盘,起不来就放回队列,别停在 claim 状态。 */
internal fun TaskService.startFollowUp(item: QueuedFollowUp) {
    if (!center.persistFollowUp(item)) return
    runCatching {
        startNew(item.text, item.conversationId, provenance = item.provenance, followUp = item,
            channelPeer = item.channelPeer, channelMessageId = item.channelMessageId)
    }.onFailure { center.followUps.add(item) }
}

internal fun TaskService.channelFor(peer: ChannelPeer, scheduled: Boolean = false): Channel = when (peer.channelId) {
    LocalChannel.ID -> if (scheduled) scheduledLocalChannel else localChannel
    else -> checkNotNull((application as com.wangzan101.mika.MikaApp).wechat.channelFor(peer.channelId)) {
        "unknown channel ${peer.channelId}"
    }
}

internal fun TaskService.acknowledgeChannel(peer: ChannelPeer?, messageId: String?) {
    if (peer != null && messageId != null) channelFor(peer).acknowledge(messageId)
}

/**
 * 每个新登记的任务只确认一次，不转发运行阶段。立即进入可靠送达账本并占住发送顺序，
 * 网络发送在后台进行；重复消息不会建任务，因此也不会再确认。
 */
internal fun TaskService.acceptChannel(run: TaskRun) {
    val peer = run.channelPeer ?: return
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        outbox.deliver(channelFor(peer), peer,
            Delivery.Notice("accepted:${run.key}", peer.threadId, tr(R.string.channel_task_accepted)))
    }
}

/** 排队中的任务被取消(已移出队伍):没开跑,直接记为已取消;排在它后面的下一件接着取。 */
internal fun TaskService.cancelQueued(run: TaskRun) {
    run.handoffUnconsumed(cancelled = true)
    run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, TaskState.Cancelled, run.steers.value, run.runId.value, run.sharedFiles.value) }
    run.workflowAttemptId?.let { attempt ->
        (application as com.wangzan101.mika.MikaApp).workflowInterrupted(attempt)
    }
    run.triggerWindowKey?.let { key ->
        TriggerRuntime.actionFailed(this, key, Conversation.CANCELLED_OUTCOME)
    }
    run.end(TaskState.Cancelled)
    recordTurn(ConversationStore.shared(this), run, TaskState.Cancelled, null, audios = run.audios.value)
    run.mcpExternalId = null
    publishRuns()
    center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    drainReady(run.conversationId)
}
