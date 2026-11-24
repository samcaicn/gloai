package com.wangzan101.mika.frontdesk

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MarkdownModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 一条要发出去的前台通知:点开进 [conversationId];[needsYou] 为 true 时走「需要你处理」频道。 */
data class DeskNotice(val conversationId: String, val title: String, val text: String, val needsYou: Boolean)

/**
 * 没有入口在线时前台的兜底:收尾了的汇报作为一行前台回话(`status = desk_report`)写回会话;哪些再发一条通知由
 * [ReportPolicy.notifies] 定(收尾了的、前置条件不满足的发;开始、排队、进度不发;要回答、确认、接管的由任务自己那条
 * 带按钮的通知提醒,这里不再发)。
 * 写哪些行:只写收尾的——办好了写后台给的完整结果原文,没办成写「这件:没办成…」一句。开始、排队、进度、要你处理不写
 * (聊天页的进度条和问题卡看的是任务的实时状态,不靠会话里的行)。前台正跟着的那次运行收尾时任务不再自己发「任务完成」
 * ([DeskRegistry.reportsEnding]),所以一件任务办完只有这一条。
 * 只有这条会话没有入口接着时才会走到这里(用户没在看它),所以不管 Mika 在不在前台都发。
 */
class DeskNotifier(
    private val conversations: ConversationStore,
    private val notify: (DeskNotice) -> Unit,
) : DeskSink {
    override suspend fun record(conversationId: String, report: DeskReport) {
        val event = report.event
        if (event is DeskEvent.Queued) return
        val line = line(event)
        val outcome = rowText(report)
        if (outcome != null) withContext(Dispatchers.IO) {
            conversations.append(conversationId, Turn(userText = "", outcome = outcome, status = STATUS,
                runId = DeskTurn.RUN_PREFIX + UUID.randomUUID(), deskTasks = listOf(event.taskId)))
        }
        // 哪些发通知由 ReportPolicy 定
        if (!ReportPolicy.notifies(event)) return
        val needsYou = event is DeskEvent.NeedsAnswer || event is DeskEvent.NeedsAction
        // 通知不渲染 Markdown:正文去掉记号;写回会话的那一行保留原文,聊天页照常渲染
        val text = MarkdownModel.toPlainText(line).ifBlank { line }
        notify(DeskNotice(conversationId, report.title, text, needsYou))
    }

    companion object {
        /** 前台兜底写回会话的那一行的状态。 */
        const val STATUS = DeskTurn.REPORT_STATUS
        private const val TAG = "MikaDesk"

        /**
         * 收尾的汇报写回会话的那一行:办好了是完整结果原文(结果为空时退回「这件:办好了」),没办成、取消是「这件:…」一句;
         * 开始、排队、进度、要你处理不写行,为 null。
         */
        fun rowText(report: DeskReport): String? = when (val event = report.event) {
            is DeskEvent.Succeeded -> event.answer.trim().ifBlank { tr(R.string.desk_report_line, report.title, line(event)) }
            is DeskEvent.Failed, is DeskEvent.Cancelled -> tr(R.string.desk_report_line, report.title, line(event))
            else -> null
        }

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
