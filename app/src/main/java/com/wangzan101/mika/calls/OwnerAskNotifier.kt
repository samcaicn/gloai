package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity

/**
 * 电话里请示主人:问题登记进 [questions](写进这通电话所属的会话 [conversationId]),
 * 同时在请示频道([CallNotifications.OWNER_ASK_CHANNEL],高重要性,会弹出)发一条带直接回复(RemoteInput,标准 API)的通知。
 * 主人在会话里回、或在通知里回,先到的算数;另一边随即作废(通知撤掉)。
 * 频道被关了:问题照样进会话(那一行提醒去打开),通话告诉对方回头答复;连会话也没有才算没送到。
 */
internal class OwnerAskNotifier(
    private val context: Context,
    private val questions: OwnerQuestions,
    private val conversationId: String?,
) : OwnerAsk {
    private var token: String? = null

    override fun ask(question: String, onReply: (String) -> Unit): OwnerReach {
        // 又问了一句:旧的那个悄悄换掉(新问题那一行会写进会话)
        token?.let {
            questions.cancel(it)
            dismiss(context)
        }
        token = null
        val ready = CallNotifications(context).ownerAskReady()
        val handle = questions.open(conversationId, question, notified = ready) { reply ->
            dismiss(context)
            onReply(reply)
        }
        token = handle.token
        if (ready && post(context, handle.token, question, conversationId)) return OwnerReach.Notified
        if (conversationId != null) return OwnerReach.InConversation
        cancel()
        return OwnerReach.Unreachable
    }

    /** 通话结束:还没回的问题撤掉,会话里补一行「已撤回」,通知一并撤掉。 */
    override fun cancel() {
        val id = token ?: return
        token = null
        questions.withdraw(id)
        dismiss(context)
    }

    companion object {
        const val KEY_REPLY = "owner_reply"
        const val EXTRA_TOKEN = "owner_ask_token"
        const val EXTRA_CONVERSATION = "owner_ask_conversation"
        const val ACTION_REPLY = "com.wangzan101.mika.calls.OWNER_REPLY"
        private const val NOTIFICATION_ID = 90_118

        /** 通知里回了一句:对得上就交给提问方;空回复不算,原样再发一次让主人重填(系统等着这条通知更新,不然回复框一直转圈)。 */
        fun deliver(context: Context, questions: OwnerQuestions, token: String?, reply: String?, conversationId: String?) {
            if (token == null) return
            if (questions.answer(token, reply.orEmpty())) return
            questions.question(token)?.let { post(context, token, it, conversationId) }
        }

        private fun dismiss(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.let { runCatching { it.cancel(NOTIFICATION_ID) } }
        }

        private fun post(context: Context, token: String, question: String, conversationId: String?): Boolean = runCatching {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.calls_owner_ask_hint)).build()
            // RemoteInput 要往 Intent 里填回复,PendingIntent 必须可变;Intent 指明接收方,不导出
            val replyIntent = Intent(context, OwnerReplyReceiver::class.java).setAction(ACTION_REPLY)
                .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_CONVERSATION, conversationId)
            val reply = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val action = Notification.Action.Builder(null, context.getString(R.string.calls_owner_ask_reply), reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            // 锁着屏也能直接回,对方还在电话那头等
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
            // 点开:有所属会话就进会话(在那里也能回),没有就进 AI 电话页
            val open = if (conversationId != null) TaskService.openApp(context, conversationId)
            else PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, CallNotifications.OWNER_ASK_CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(context.getString(R.string.calls_owner_ask_title))
                .setContentText(question)
                .setStyle(Notification.BigTextStyle().bigText(question))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(action.build())
                .build()
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            nm.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }
}
