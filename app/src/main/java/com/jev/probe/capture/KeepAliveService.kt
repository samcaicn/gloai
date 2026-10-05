package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.jev.probe.core.Prefs
import com.jev.probe.core.SelfCheck

/**
 * A minimal foreground service whose only job is to keep the app process at
 * foreground importance so MIUI/HyperOS "Greezer" does not freeze the
 * accessibility service (which otherwise dies within seconds — see P1 report).
 * Not a full fix on its own: the user must also grant autostart / no battery
 * restriction, but this holds the process while the app is set up and running.
 *
 * 自愈轮询（R2）：每 15 分钟检查一次无障碍是否还真的开着。用户明明开了助手、
 * 却被 OEM 把无障碍悄悄关掉时，弹一条恢复通知把它带回设置页——否则服务活着、
 * 权限却没了，助手静默失效且用户不知为何。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var warnedA11yOff = false

    private val heal = object : Runnable {
        override fun run() {
            runCatching {
                val prefs = Prefs(this@KeepAliveService)
                val on = SelfCheck.isA11yOnPublic(this@KeepAliveService)
                if (prefs.enabled && !on) {
                    if (!warnedA11yOff) {
                        BootReceiver.notifyRecovery(this@KeepAliveService)
                        warnedA11yOff = true
                    }
                } else if (on) {
                    warnedA11yOff = false
                }
            }
            handler.postDelayed(this, HEAL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channelId = "jev_keepalive"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Jev 助手运行中", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Jev 助手运行中")
            .setContentText("在聊天旁读消息、给回复建议")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
        // 启动自愈轮询（首次延迟一个间隔，避免刚拉起就弹通知）。
        handler.postDelayed(heal, HEAL_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(heal)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** 自愈轮询间隔：15 分钟。足够及时发现 OEM 关掉的权限，又不至于频繁唤醒。 */
        private const val HEAL_INTERVAL_MS = 15 * 60_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
