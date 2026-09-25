package com.facemark.camhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 自查保活前台服务。
 *  - 启动后显示常驻通知 + 持有 PARTIAL_WAKE_LOCK，避免上传/推理途中被杀
 *  - 仅做合规保活，不偷偷后台录像、不常驻后台采集（隐私红线）
 */
class ForegroundInspectService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camhub:inspect")
        wakeLock?.acquire(30 * 60 * 1000L) // 最多 30 分钟，防止泄漏
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val chanId = "camhub_inspect"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(chanId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(chanId, "自查保活", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, chanId)
            .setContentTitle("店长自查进行中")
            .setContentText("正在上传并识别画面，请勿关闭")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFY_ID = 1001

        fun register(ctx: MainActivity, engine: FlutterEngine, channel: String) {
            MethodChannel(engine.dartExecutor.binaryMessenger, channel)
                .setMethodCallHandler { call, result ->
                    when (call.method) {
                        "start" -> {
                            val intent = Intent(ctx, ForegroundInspectService::class.java)
                            ctx.startForegroundService(intent)
                            result.success(null)
                        }
                        "stop" -> {
                            ctx.stopService(Intent(ctx, ForegroundInspectService::class.java))
                            result.success(null)
                        }
                        else -> result.notImplemented()
                    }
                }
        }
    }
}
