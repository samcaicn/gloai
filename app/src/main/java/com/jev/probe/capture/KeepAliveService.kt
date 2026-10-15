package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import com.jev.probe.MainActivity
import com.jev.probe.core.AutoReply
import com.jev.probe.core.Prefs
import com.jev.probe.core.SelfCheck
import com.jev.probe.input.ProbeImeBus

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
    private var warnedImeReset = false

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
                // 输入法重置巡检：ColorOS 在 install -r（覆盖安装）后会把 default_input_method
                // 重置回讯飞——2026-10-15 实测装机后探针 IME 静默失效，用户按旧记忆
                // 「切过就不用管」→ 回复永远写不进输入框 → 「卡住没回复」。
                // 铁律：App 不替用户切输入法，这里只检测+提示，通知文案写清手动切法。
                val imeNow = Settings.Secure.getString(
                    contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
                ) ?: ""
                val imeWanted = ProbeImeBus.imeId
                if (prefs.autoFillBest && imeNow != imeWanted) {
                    if (!warnedImeReset) {
                        BootReceiver.notifyImeReset(this@KeepAliveService)
                        warnedImeReset = true
                    }
                } else if (imeNow == imeWanted) {
                    warnedImeReset = false
                }
                // 通知监听自愈：进程被系统回收后，即使 enabled_notification_listeners
                // 授权还在，系统也不会自动重新 bind listener（dumpsys 的 Live listeners
                // 里没有它 → 通知全丢、后台自动分析静默失效）。requestRebind 是官方
                // 补救 API（API 24+）：授权开着时请求重绑，系统已绑时会忽略。
                if (SelfCheck.isNotifyOnPublic(this@KeepAliveService)) {
                    runCatching {
                        NotificationListenerService.requestRebind(
                            ComponentName(this@KeepAliveService, WxNotificationListener::class.java)
                        )
                    }
                }
            }
            handler.postDelayed(this, HEAL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        startForeground(NOTIF_ID, buildNotification(this))
        // 自愈轮询：装机/系统重置后授权丢失要在 1 分钟内被发现（原来 15 分钟，
        // 用户装机后马上用，等提示早就骂娘了）。首检 10 秒：服务刚拉起时 settings
        // 可能还没落盘，立刻查会误报。
        handler.postDelayed(heal, 10_000L)
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
        private const val HEAL_INTERVAL_MS = 60_000L

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
                // IMPORTANCE_MIN：动作按钮常驻但不响铃不震动——控制项不该打扰用户。
                val ch = NotificationChannel(CH_ID, "Jev 助手运行中", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
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
