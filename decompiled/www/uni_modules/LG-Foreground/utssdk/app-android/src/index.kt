@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGForeground
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import io.dcloud.uts.UTSAndroid
interface ForegroundOptions {
    var title: String?
    var content: String?
    var channelName: String?
    var notificationId: Number?
    var wakeLock: Boolean?
}
val SERVICE_CLASS = "uts.sdk.modules.LGForeground.LGForegroundService"
val CHANNEL_ID = "lg_live_keepalive"
val DEFAULT_NOTIFY_ID: Number = 9001
var running = false
open class LGForegroundService : Service {
    open var wakeLock: PowerManager.WakeLock? = null
    constructor() : super() {}
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val ctx = this.getApplicationContext()
        var title = "直播运行中"
        var content = "息屏 / 切后台不停播"
        var channelName = "直播运行"
        var notifyId = DEFAULT_NOTIFY_ID
        var wantWake = true
        if (intent != null) {
            val t = intent.getStringExtra("title")
            if (t != null && t != "") {
                title = t
            }
            val c = intent.getStringExtra("content")
            if (c != null && c != "") {
                content = c
            }
            val cn = intent.getStringExtra("channelName")
            if (cn != null && cn != "") {
                channelName = cn
            }
            notifyId = intent.getIntExtra("notificationId", DEFAULT_NOTIFY_ID.toInt())
            wantWake = intent.getBooleanExtra("wakeLock", true)
        }
        this.startAsForeground(ctx, title, content, channelName, notifyId.toInt())
        if (wantWake) {
            this.acquireWakeLock(ctx)
        }
        running = true
        return Service.START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    override fun onDestroy(): Unit {
        this.releaseWakeLock()
        try {
            this.stopForeground(true)
        }
         catch (e: Throwable) {
            console.log("LG-Foreground stopForeground 失败: " + e)
        }
        running = false
        super.onDestroy()
    }
    open fun startAsForeground(ctx: Context, title: String, content: String, channelName: String, notifyId: Int): Unit {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        var builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            builder = Notification.Builder(ctx, CHANNEL_ID)
        } else {
            builder = Notification.Builder(ctx)
        }
        builder.setContentTitle(title)
        builder.setContentText(content)
        builder.setSmallIcon(ctx.getApplicationInfo().icon)
        builder.setOngoing(true)
        builder.setAutoCancel(false)
        builder.setOnlyAlertOnce(true)
        val launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName())
        if (launch != null) {
            val pi = PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setContentIntent(pi)
        }
        val n = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            this.startForeground(notifyId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            this.startForeground(notifyId, n)
        }
    }
    open fun acquireWakeLock(ctx: Context): Unit {
        if (this.wakeLock != null) {
            return
        }
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LG-Foreground:live")
            wl.setReferenceCounted(false)
            wl.acquire()
            this.wakeLock = wl
        }
         catch (e: Throwable) {
            console.log("LG-Foreground acquireWakeLock 失败: " + e)
        }
    }
    open fun releaseWakeLock(): Unit {
        val wl = this.wakeLock
        if (wl == null) {
            return
        }
        try {
            if (wl.isHeld()) {
                wl.release()
            }
        }
         catch (e: Throwable) {}
        this.wakeLock = null
    }
}
fun startForeground(options: ForegroundOptions? = null): Unit {
    val ctx = UTSAndroid.getAppContext()!!
    val intent = Intent()
    intent.setClassName(ctx.getPackageName(), SERVICE_CLASS)
    var title = "直播运行中"
    var content = "息屏 / 切后台不停播"
    var channelName = "直播运行"
    var notifyId = DEFAULT_NOTIFY_ID
    var wakeLock = true
    if (options != null) {
        if (options.title != null && options.title != "") {
            title = options.title!!
        }
        if (options.content != null && options.content != "") {
            content = options.content!!
        }
        if (options.channelName != null && options.channelName != "") {
            channelName = options.channelName!!
        }
        if (options.notificationId != null) {
            notifyId = options.notificationId!!
        }
        if (options.wakeLock != null) {
            wakeLock = options.wakeLock!!
        }
    }
    intent.putExtra("title", title)
    intent.putExtra("content", content)
    intent.putExtra("channelName", channelName)
    intent.putExtra("notificationId", notifyId)
    intent.putExtra("wakeLock", wakeLock)
    try {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        running = true
    }
     catch (e: Throwable) {
        console.log("LG-Foreground 启动失败: " + e)
    }
}
fun updateNotification(options: ForegroundOptions? = null): Unit {
    startForeground(options)
}
fun stopForeground(): Unit {
    val ctx = UTSAndroid.getAppContext()!!
    val intent = Intent()
    intent.setClassName(ctx.getPackageName(), SERVICE_CLASS)
    try {
        ctx.stopService(intent)
    }
     catch (e: Throwable) {
        console.log("LG-Foreground 停止失败: " + e)
    }
    running = false
}
fun isRunning(): Boolean {
    return running
}
open class LGForegroundServiceByJs : LGForegroundService {
    constructor() : super() {}
    open fun onStartCommandByJs(intent: Intent, flags: Int, startId: Int): Int {
        return this.onStartCommand(intent, flags, startId)
    }
    open fun onBindByJs(intent: Intent?): IBinder? {
        return this.onBind(intent)
    }
    open fun onDestroyByJs(): Unit {
        return this.onDestroy()
    }
    open fun startAsForegroundByJs(ctx: Context, title: String, content: String, channelName: String, notifyId: Int): Unit {
        return this.startAsForeground(ctx, title, content, channelName, notifyId)
    }
    open fun acquireWakeLockByJs(ctx: Context): Unit {
        return this.acquireWakeLock(ctx)
    }
    open fun releaseWakeLockByJs(): Unit {
        return this.releaseWakeLock()
    }
}
fun startForegroundByJs(options: ForegroundOptions? = null): Unit {
    return startForeground(options)
}
fun updateNotificationByJs(options: ForegroundOptions? = null): Unit {
    return updateNotification(options)
}
fun stopForegroundByJs(): Unit {
    return stopForeground()
}
fun isRunningByJs(): Boolean {
    return isRunning()
}
