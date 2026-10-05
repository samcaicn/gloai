package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jev.probe.MainActivity
import com.jev.probe.core.AutoReply
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
        isAlive = true
        startForeground(NOTIF_ID, buildNotification(this))
        // 启动自愈轮询（首次延迟一个间隔，避免刚拉起就弹通知）。
        handler.postDelayed(heal, HEAL_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isAlive = false
        handler.removeCallbacks(heal)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CH_ID = "jev_keepalive"
        const val NOTIF_ID = 1

        /** 自愈轮询间隔：15 分钟。足够及时发现 OEM 关掉的权限，又不至于频繁唤醒。 */
        private const val HEAL_INTERVAL_MS = 15 * 60_000L

        /** 保活服务当前是否在运行（由 onCreate/onDestroy 维护，供 [refresh] 判活）。 */
        @Volatile
        var isAlive: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /**
         * 重画保活通知（切换自动收发后调用，让通知上的动作文字与状态立刻变准）。
         * 服务没在跑就跳过：常驻通知必须由前台服务持有，单独 notify 一条 ongoing
         * 在 Android 14+ 会被判为无效前台服务通知。
         */
        fun refresh(ctx: Context) {
            if (!isAlive) return
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(ctx))
            }
        }

        /**
         * 保活通知 = 唯一的「全局控制面」。用户在任何 App / 锁屏下拉通知栏都能：
         * 一步暂停/恢复自动收发（不必回 App、不必解锁进设置），一步打开助手。
         * 这条通知本来就常驻，顺手做成控制入口比再加一个设置项更顺。
         */
        private fun buildNotification(ctx: Context): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CH_ID, "Jev 助手运行中", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                ch.setShowAction(false) // 动作默认收起，展开才显示，不喧宾夺主
                nm.createNotificationChannel(ch)
            }
            val on = AutoReply.isOn(ctx)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0

            val toggle = NotificationCompat.Action.Builder(
                0,
                if (on) "暂停自动收发" else "开启自动收发",
                PendingIntent.getBroadcast(ctx, 1,
                    Intent(ctx, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_TOGGLE_AUTO), piFlags)
            ).build()

            val open = NotificationCompat.Action.Builder(
                0,
                "打开助手",
                PendingIntent.getActivity(ctx, 2,
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), piFlags)
            ).build()

            return NotificationCompat.Builder(ctx, CH_ID)
                .setContentTitle("Jev 助手运行中")
                .setContentText(AutoReply.statusText(ctx))
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(toggle)
                .addAction(open)
                .build()
        }
    }
}
