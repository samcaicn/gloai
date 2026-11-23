package com.wangzan101.mika.task

import android.app.Service.STOP_FOREGROUND_REMOVE

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.task.TaskService.Companion.openApp
import com.wangzan101.mika.task.TaskService.Companion.ACTION_CLOSE_KEPT_PAGE
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.ID_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.ID_KEPT
import com.wangzan101.mika.task.TaskService.Companion.REQUEST_CLOSE_KEPT
import com.wangzan101.mika.task.TaskService.Companion.TAG
import com.wangzan101.mika.task.TaskService.Companion.cancel
import com.wangzan101.mika.task.TaskService.Companion.answer

// 任务服务:运行结束收尾、留着的页面与对话记录
/**
 * 停止之后写交代,记成这一轮的回答(结局照旧是取消)。用进程级作用域在后台写:不挡着收尾、下一件事和再点停止,服务退出也写完。
 * 写好或写不成都从「正在整理」里拿掉。
 */
internal fun TaskService.summarizeStop(run: TaskRun, runId: String, draft: String?, store: ConversationStore) {
    MemoryRuntime.of(this).scope.launch(store.meter(run.conversationId)) {
        try {
            val text = runCatching { run.stopSummary(draft) }.onFailure { Log.w(TAG, "stop summary failed", it) }.getOrNull()
            if (text != null) withContext(Dispatchers.IO) {
                runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = text, outcomeSystemType = null) } }
                    .onFailure { Log.w(TAG, "could not save stop summary", it) }
            }
        } finally {
            taskState._summarizing.update { it - runId }
        }
    }
}

/** 失败通知仍只给短原因;对话里补上已确认的进度,下一次「继续」不会把它当成没做过。 */
internal fun TaskService.summarizeFailure(run: TaskRun, runId: String, reason: String, store: ConversationStore) {
    MemoryRuntime.of(this).scope.launch {
        val text = runCatching { run.checkpointSummary() }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "failure summary failed", it) }.getOrNull()
        if (text != null) withContext(Dispatchers.IO) {
            runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = reason + "\n" + text) } }
                .onFailure { Log.w(TAG, "could not save failure summary", it) }
        }
    }
}

/** 任务收尾之后:都结束了就退出前台;还有别的任务就把前台交给它们,并放出排队的任务。 */
internal fun TaskService.onRunEnded(run: TaskRun, store: ConversationStore) {
    val wasShown = taskState.shownRun === run
    val started = center.onEnded(run)
    // 收尾时替接班的拿着的屏幕锁交给第一个放出来的;没有放出来的(排着的被取消、同一对话还有任务在跑)就放开
    run.wakeHandoff?.let { lease ->
        run.wakeHandoff = null
        started.firstOrNull()?.let { it.wakeHandoff = lease } ?: lease.release()
    }
    publishRuns()
    if (wasShown) started.firstOrNull { it.conversationId == run.conversationId }?.show()
    // 对话空出来就开始留给它的下一件,不等别的对话的任务
    val ready = center.takeReady(run.conversationId)
    if (center.runs().isEmpty()) {
        if (ready.isNotEmpty()) {
            ready.forEach(::startFollowUp)
            return
        }
        // 还留着一页在后台运行:前台通知换成它的,服务不退(进程靠它撑着);关掉时由 [onKeptPage] 退出
        val kept = keeper.page.value
        if (kept != null) {
            synchronized(notifyLock) {
                startForeground(ID_KEPT, keptNotification(kept))
                getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run))
            }
            foreground = null
            taskState._busy.value = false
            return
        }
        if (workflowStarts.get() > 0) {
            foreground = null
            taskState._busy.value = false
            keepForeground()
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 先取编号再放开:放开之后才到的新任务会带来更新的编号,这里的 stopSelf 就不会把它停掉
        val id = lastStartId
        taskState._busy.value = false
        stopSelf(id)
        return
    }
    // 先换成「正在准备」:放出来的任务可能正好接过前台,通知不能还写着排队
    started.forEach { it.dequeue() }
    if (foreground === run) center.running().firstOrNull()?.let { next ->
        foreground = next
        startInForeground(next, progressNotification(next, next.state.value))
    }
    synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run)) }
    started.forEach {
        launch(it, store)
        refreshNotifications(it)
    }
    center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    center.running().forEach(::refreshNotifications)
    ready.forEach(::startFollowUp)
}

/**
 * 留着的那一页变了:还留着就发(或换)它的常驻通知——没有任务在跑时它就是前台通知;关掉了就撤掉通知,
 * 这时没有任务就退出前台、停掉服务。
 */
internal fun TaskService.onKeptPage(page: com.wangzan101.mika.cap.web.KeptPage?) {
    val nm = getSystemService(NotificationManager::class.java)
    synchronized(notifyLock) {
        val idle = center.runs().isEmpty()
        if (page != null) {
            if (idle && foreground == null) startForeground(ID_KEPT, keptNotification(page)) else nm.notify(ID_KEPT, keptNotification(page))
            return
        }
        nm.cancel(ID_KEPT)
        if (!idle || taskState._busy.value || workflowStarts.get() > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }
}

/** 留在后台的那一页的常驻通知:站点、离自动关还剩多久(倒计时),按钮「关闭网页」「打开对话」。 */
internal fun TaskService.keptNotification(page: com.wangzan101.mika.cap.web.KeptPage): Notification {
    val close = PendingIntent.getService(
        this, REQUEST_CLOSE_KEPT, Intent(this, TaskService::class.java).setAction(ACTION_CLOSE_KEPT_PAGE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.kept_notif_title))
        .setContentText(page.host)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        // 时间那里倒计时到自动关的那一刻(通知文字不会过时)
        .setWhen(page.closesAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        .setContentIntent(openApp(page.conversationId))
        .addAction(Notification.Action.Builder(null, tr(R.string.kept_close), close).build())
        .addAction(Notification.Action.Builder(null, tr(R.string.kept_open_chat), openApp(page.conversationId)).build())
        .build()
}

/**
 * 给模型的上文里,留页那一轮的页面此刻是不是还开着:记着「留着」却已经不在了(Mika 中途退出过)的,按已关算。
 */
internal fun TaskService.withKeptState(t: Turn): Turn =
    if (t.keptPage != null && t.keptPageClosed == null && keeper.page.value?.runId != t.runId) t.copy(keptPageClosed = KeptPageClose.AppExit.wire) else t

/** 任务结束时记一轮,后续消息可带上它作参考。写盘失败不影响任务结果。 */
internal fun TaskService.recordTurn(store: ConversationStore, run: TaskRun, end: TaskState, runId: String?, files: List<SharedFile> = emptyList(),
    audios: List<com.wangzan101.mika.core.chat.GeneratedAudio> = emptyList(),
    photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto> = emptyList(),
    steers: List<String> = emptyList(), groups: List<String> = emptyList(), interrupted: List<String> = emptyList(),
    finished: List<String> = emptyList()) {
    val goal = run.triggerCondition ?: run.goal
    val turn = when (end) {
        // 没核对过的(聊天、回答里没有可核对的值)和核实过的一样记成 succeeded;核对过却没能核实的才记 unverified
        is TaskState.Succeeded -> if (end.doubtful) Turn(goal, end.answer, "unverified", end.note, unconfirmed = end.unconfirmed, verified = false)
            else Turn(goal, end.answer, "succeeded", verified = end.verified.takeIf { end.reviewed })
        is TaskState.Failed -> Turn(goal, end.reason, "failed")
        TaskState.Cancelled -> Turn(goal, Conversation.CANCELLED_OUTCOME, "cancelled", outcomeSystemType = "task_cancelled")
        else -> return
    }.copy(runId = runId, remoteTaskId = run.mcpExternalId, steers = steers, groups = groups, interruptedReplies = interrupted, finishedReplies = finished,
        audios = audios, photos = photos)
        // 这一轮留下了一页:记下站点(已经关了的连同原因),对话页那一轮下面据此显示
        .let { t -> keeper.recordFor(runId)?.let { (host, closed) -> t.copy(keptPage = host, keptPageClosed = closed) } ?: t }
    runCatching { store.append(run.conversationId, turn, files) }.onFailure { Log.w(TAG, "could not save conversation turn", it) }
}
