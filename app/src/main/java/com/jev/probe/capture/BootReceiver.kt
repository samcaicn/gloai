package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jev.probe.core.Prefs

/**
 * 开机后若用户曾开启助手，发一条「助手已停止，点此恢复」通知。
 *
 * 无障碍服务无法被代码直接启动（只能由用户在设置里开启时由系统绑定），所以这里
 * 只负责把用户带回开启入口：点通知直接跳到无障碍设置页。同时拉起前台保活服务，
 * 至少让进程在开机后不被立刻冻死。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(ctx)
        if (!prefs.enabled) return
        // 拉起前台保活，尽量让进程活着，等用户回到设置重新开启无障碍。
        runCatching { KeepAliveService.start(ctx) }
        notifyRecovery(ctx)
    }

    companion object {
        const val CH_ID = "jev_recovery"
        const val NOTIF_ID = 2

        /** 发一条高优先级通知，点开直接跳无障碍设置页。OEM 把无障碍悄悄关掉时复用此入口。 */
        fun notifyRecovery(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CH_ID, "Jev 助手恢复", NotificationManager.IMPORTANCE_HIGH)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val go = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(ctx, 0, go, flags)
            val n = NotificationCompat.Builder(ctx, CH_ID)
                .setContentTitle("Jev 聊天助手已停止")
                .setContentText("点此重新开启无障碍服务，恢复自动分析")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            @Suppress("DEPRECATION")
            nm.notify(NOTIF_ID, n)
        }
    }
}
