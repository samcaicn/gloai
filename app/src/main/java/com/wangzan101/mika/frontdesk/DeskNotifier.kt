package com.wangzan101.mika.frontdesk

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条要发出去的前台通知:点开进 [conversationId];[needsYou] 为 true 时走「需要你处理」频道。 */
data class DeskNotice(val conversationId: String, val title: String, val text: String, val needsYou: Boolean)

/**
 * 没有入口在线时前台的兜底:把这条汇报作为一行前台回话(`status = desk_report`)写回会话,再发一条通知。
 * 只有这条会话没有入口接着时才会走到这里(用户没在看它),所以不管 Mika 在不在前台都发。
 */
class DeskNotifier(
    private val conversations: ConversationStore,
    private val notify: (DeskNotice) -> Unit,
) : DeskSink {
    override suspend fun record(conversationId: String, report: DeskReport) {
        val line = line(report.event)
        val turn = Turn(userText = "", outcome = tr(R.string.desk_report_line, report.title, line), status = STATUS,
            deskTasks = listOf(report.event.taskId))
        withContext(Dispatchers.IO) { conversations.append(conversationId, turn) }
        val needsYou = report.event is DeskEvent.NeedsAnswer || report.event is DeskEvent.NeedsAction
        notify(DeskNotice(conversationId, report.title, line, needsYou))
    }

    companion object {
        /** 前台兜底写回会话的那一行的状态。 */
        const val STATUS = "desk_report"
        private const val TAG = "MikaDesk"

        /** 一件任务这次变化的一句话(不带任务名),按界面语言。 */
        fun line(event: DeskEvent): String = when (event) {
            is DeskEvent.Started -> tr(R.string.desk_report_started)
            is DeskEvent.Queued -> trPlural(R.plurals.queue_ahead, event.position)
            is DeskEvent.Progress -> event.line
            is DeskEvent.NeedsAnswer -> tr(R.string.desk_report_needs_answer, event.prompt)
            is DeskEvent.NeedsAction -> tr(when (event.kind) {
                ActionKind.Confirm -> R.string.desk_report_needs_confirm
                ActionKind.Takeover -> R.string.desk_report_needs_takeover
                ActionKind.Prerequisite -> R.string.desk_report_needs_prerequisite
            })
            is DeskEvent.Succeeded -> tr(R.string.desk_report_succeeded, event.answer)
            is DeskEvent.Failed -> tr(R.string.desk_report_failed, event.reason)
            is DeskEvent.Cancelled -> tr(R.string.desk_report_cancelled)
        }

        /** 用任务通知的频道发出去:要你处理的走「需要你处理」,其余走结果频道;点开进那条会话。 */
        fun post(context: Context, notice: DeskNotice) {
            try {
                TaskService.ensureChannels(context)
                val channel = if (notice.needsYou) TaskService.CHANNEL_ATTENTION else TaskService.CHANNEL_RESULT
                context.getSystemService(NotificationManager::class.java).notify(TaskService.nextResultId(),
                    Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(notice.title)
                        .setContentText(notice.text)
                        .setStyle(Notification.BigTextStyle().bigText(notice.text))
                        .setContentIntent(TaskService.openApp(context, notice.conversationId))
                        .setAutoCancel(true)
                        .build())
            } catch (e: Exception) {
                // 发不出通知(没有通知权限等)时汇报已经写回会话,打开会话照样看得到。
                Log.w(TAG, "could not post desk notice", e)
            }
        }
    }
}
