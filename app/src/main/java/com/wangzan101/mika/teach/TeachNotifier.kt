package com.wangzan101.mika.teach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.learn.teach.TeachPhase

/** 采集或暂停时的常驻通知。没有这条通知就不算在看；进程没了不会自己再采。 */
object TeachNotifier {
    const val PAUSE = "com.wangzan101.mika.teach.PAUSE"
    const val RESUME = "com.wangzan101.mika.teach.RESUME"
    const val END = "com.wangzan101.mika.teach.END"
    const val DISCARD = "com.wangzan101.mika.teach.DISCARD"
    private const val CHANNEL = "teach"
    private const val ID = 7

    /** 通知只是附加提示。失败（例如没有通知权限）不影响采集；采集靠 [TeachChip]。 */
    fun tryShow(context: Context, phase: TeachPhase) {
        runCatching { show(context, phase) }
    }

    fun show(context: Context, phase: TeachPhase) {
        if (phase != TeachPhase.Recording && phase != TeachPhase.Paused) {
            cancel(context)
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.teach_channel), NotificationManager.IMPORTANCE_LOW))
        val learning = phase == TeachPhase.Recording
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(if (learning) R.string.teach_learning else R.string.teach_paused))
            .setContentText(tr(R.string.teach_notification))
            .setOngoing(learning)
            .setContentIntent(open(context))
        if (learning) builder.addAction(action(context, PAUSE, tr(R.string.teach_pause), 1))
        else builder.addAction(action(context, RESUME, tr(R.string.teach_resume), 2))
        builder.addAction(action(context, END, tr(R.string.teach_end), 3))
        builder.addAction(action(context, DISCARD, tr(R.string.teach_discard), 4))
        nm.notify(ID, builder.build())
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    private fun action(context: Context, name: String, title: String, request: Int): Notification.Action {
        val intent = Intent(context, TeachReceiver::class.java).setAction(name)
        val pending = PendingIntent.getBroadcast(
            context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        return Notification.Action.Builder(icon, title, pending).build()
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 5,
        com.wangzan101.mika.task.TaskService.openAppIntent(context).putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_OPEN_TEACH, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
