package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Telecom keeps its bound InCallService alive; we need a cancellable notification, not an idle FGS. */
internal class CallNotifications(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun ready(): Boolean = runCatching {
        val notificationManager = manager ?: return false
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.calls_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(COMPLETED_CHANNEL, context.getString(R.string.calls_completed_notification_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        notificationManager.areNotificationsEnabled() &&
            notificationManager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    /**
     * 电话里请示主人用的频道:对方在电话那头等,要弹出来(高重要性)。
     * 用户关了这个频道或整个通知时返回 false,调用方改成只写进会话,并在会话里提醒去打开。
     */
    fun ownerAskReady(): Boolean = runCatching {
        val notificationManager = manager ?: return false
        notificationManager.createNotificationChannel(
            NotificationChannel(OWNER_ASK_CHANNEL, context.getString(R.string.calls_owner_ask_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        notificationManager.areNotificationsEnabled() &&
            notificationManager.getNotificationChannel(OWNER_ASK_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    fun show(callId: String, message: String, ringing: Boolean): Boolean = runCatching {
        if (!ready()) return false
        val takeOverIntent = Intent(context, CallNotificationAction::class.java)
            .setAction(ACTION).setData(Uri.fromParts("mika-call", callId, null))
        val takeOver = PendingIntent.getBroadcast(context, 0, takeOverIntent, PendingIntent.FLAG_IMMUTABLE)
        val openCalls = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(message)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openCalls)
            .addAction(Notification.Action.Builder(null,
                context.getString(if (ringing) R.string.calls_cancel_ai_answer else R.string.calls_takeover), takeOver).build())
        manager?.notify(ID, builder.build()) ?: return false
        true
    }.getOrDefault(false)

    fun handoff() {
        runCatching {
            if (!ready()) return
            manager?.notify(ID, Notification.Builder(context, CHANNEL)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.calls_notification_handoff))
                .setSmallIcon(android.R.drawable.sym_action_call).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(12_000L).build())
        }
    }

    fun clear() { runCatching { manager?.cancel(ID) } }

    /** Separate, user-dismissable call-complete notice; never reuses the live-call notification ID. */
    fun completed(callId: String, number: String?, direction: CallDirection = CallDirection.INCOMING,
                  summary: CallSummary? = null, summaryPending: Boolean = false, summaryFailed: Boolean = false) {
        postCompleted(callId, number, direction, summary, summaryPending, summaryFailed, onlyIfActive = false)
    }

    fun updateCompletedSummary(callId: String, number: String?, direction: CallDirection = CallDirection.INCOMING,
                               summary: CallSummary?, failed: Boolean) {
        postCompleted(callId, number, direction, summary, summaryPending = false, summaryFailed = failed, onlyIfActive = true)
    }

    private fun postCompleted(callId: String, number: String?, direction: CallDirection, summary: CallSummary?, summaryPending: Boolean,
                              summaryFailed: Boolean, onlyIfActive: Boolean) {
        runCatching {
        val notificationManager = manager ?: return@runCatching
        ready() // Ensure channel creation, but a disabled live-call channel must not suppress this one.
        if (!notificationManager.areNotificationsEnabled() ||
            notificationManager.getNotificationChannel(COMPLETED_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return@runCatching
        val notificationId = completedId(callId)
        if (onlyIfActive && notificationManager.activeNotifications.none { it.id == notificationId }) return@runCatching
        val openDetail = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true)
            .putExtra(MainActivity.EXTRA_CALL_HISTORY_ID, callId)
        val pending = PendingIntent.getActivity(context, callId.hashCode(), openDetail,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val caller = number?.takeIf { it.isNotBlank() } ?: context.getString(R.string.calls_completed_notification_text)
        val titleRes = if (direction == CallDirection.OUTGOING) R.string.calls_completed_notification_title_outgoing
            else R.string.calls_completed_notification_title
        val title = "${context.getString(titleRes)} · $caller"
        val summaryText = summary?.let { value ->
            buildList {
                value.purpose?.takeIf(String::isNotBlank)?.let { add("${context.getString(R.string.calls_summary_purpose)}: $it") }
                value.highlights.take(3).forEach { add("• $it") }
                value.actions.filter { it.status == CallFollowUp.Status.PENDING }.take(2)
                    .forEach { add("• ${it.text}") }
            }.joinToString("\n").take(700).ifBlank { null }
        }
        val body = summaryText ?: when {
            summaryPending -> context.getString(R.string.calls_summary_generating)
            summaryFailed -> context.getString(R.string.calls_summary_failed)
            else -> context.getString(R.string.calls_completed_notification_text)
        }
        val publicNotification = Notification.Builder(context, COMPLETED_CHANNEL)
            .setContentTitle(context.getString(R.string.calls_completed_notification_title))
            .setContentText(context.getString(R.string.calls_completed_notification_text))
            .setSmallIcon(android.R.drawable.sym_action_call).build()
        notificationManager.notify(notificationId, Notification.Builder(context, COMPLETED_CHANNEL)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setContentIntent(pending)
            .build())
        }
    }

    companion object {
        const val ACTION = "com.wangzan101.mika.calls.TAKEOVER"
        const val ACTION_CANCEL_LOCKED_DIAL = "com.wangzan101.mika.calls.CANCEL_LOCKED_DIAL"
        const val LOCKED_DIAL_SCHEME = "mika-locked-dial"
        const val CHANNEL = "mika_ai_calls"
        const val COMPLETED_CHANNEL = "mika_ai_call_completed"
        const val OWNER_ASK_CHANNEL = "mika_ai_call_owner_ask"
        private const val ID = 90_117
        private fun completedId(callId: String) = 91_000 + (callId.hashCode() and 0x7fffffff) % 8_000
    }
}

class CallNotificationAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val uri = intent.data ?: return
        if (!((action == CallNotifications.ACTION && uri.scheme == "mika-call") ||
                (action == CallNotifications.ACTION_CANCEL_LOCKED_DIAL && uri.scheme == CallNotifications.LOCKED_DIAL_SCHEME))) return
        val id = uri.schemeSpecificPart ?: return
        val app = context.applicationContext as? MikaApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (action == CallNotifications.ACTION_CANCEL_LOCKED_DIAL) app.calls.cancelLockedDial(id)
                else app.calls.takeover(id)
            } finally {
                pending.finish()
            }
        }
    }
}
