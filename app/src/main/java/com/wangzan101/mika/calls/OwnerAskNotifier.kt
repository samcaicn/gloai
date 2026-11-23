package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import java.util.UUID

/**
 * 电话里请示主人:发一条带直接回复(RemoteInput,标准 API)的通知,主人在通知里回一句,
 * [OwnerReplyReceiver] 收到后交回 [deliver]。走 AI 电话已有的通知频道([CallNotifications.CHANNEL])。
 * 一通电话同时只有一个待回的问题;每次提问一个口令,迟到的、撤掉以后的回复对不上口令,一律丢弃。
 */
internal class OwnerAskNotifier(private val context: Context) : OwnerAsk {
    private var token: String? = null

    override fun ask(question: String, onReply: (String) -> Unit): Boolean {
        cancel()
        if (!CallNotifications(context).ready()) return false
        val id = UUID.randomUUID().toString()
        synchronized(pending) { pending[id] = Pending(question, onReply) }
        token = id
        if (!post(context, id, question)) {
            cancel()
            return false
        }
        return true
    }

    override fun cancel() {
        val id = token ?: return
        token = null
        synchronized(pending) { pending.remove(id) }
        manager(context)?.let { runCatching { it.cancel(NOTIFICATION_ID) } }
    }

    private class Pending(val question: String, val onReply: (String) -> Unit)

    companion object {
        const val KEY_REPLY = "owner_reply"
        const val EXTRA_TOKEN = "owner_ask_token"
        const val ACTION_REPLY = "com.wangzan101.mika.calls.OWNER_REPLY"
        private const val NOTIFICATION_ID = 90_118
        private val pending = HashMap<String, Pending>()

        /** 通知里回了一句:对得上口令就交给提问方,撤掉通知;空回复不算,原样再发一次让主人重填。 */
        fun deliver(context: Context, token: String?, reply: String?) {
            if (token == null) return
            val text = reply?.trim().orEmpty()
            if (text.isEmpty()) {
                // 重发一次:系统等着这条通知更新,不然回复框一直转圈
                synchronized(pending) { pending[token] }?.let { post(context, token, it.question) }
                return
            }
            val entry = synchronized(pending) { pending.remove(token) } ?: return
            manager(context)?.let { runCatching { it.cancel(NOTIFICATION_ID) } }
            entry.onReply(text)
        }

        private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

        private fun post(context: Context, token: String, question: String): Boolean = runCatching {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.calls_owner_ask_hint)).build()
            // RemoteInput 要往 Intent 里填回复,PendingIntent 必须可变;Intent 指明接收方,不导出
            val replyIntent = Intent(context, OwnerReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_TOKEN, token)
            val reply = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val action = Notification.Action.Builder(null, context.getString(R.string.calls_owner_ask_reply), reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            // 锁着屏也能直接回,对方还在电话那头等
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, CallNotifications.CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(context.getString(R.string.calls_owner_ask_title))
                .setContentText(question)
                .setStyle(Notification.BigTextStyle().bigText(question))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(action.build())
                .build()
            val nm = manager(context) ?: return false
            nm.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }
}
