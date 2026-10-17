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
import com.jev.probe.core.t

/**
 * AppOps 里的「允许发通知」开关名。
 *
 * **必须写字面量**：AppOpsManager.OPSTR_POST_NOTIFICATION 是 @hide 常量，不在公开
 * SDK 里，直接引用会 Unresolved reference（2026-10-16 CI run 1415 编译失败就是这个）。
 */
private const val OP_POST_NOTIFICATION = "android:post_notification"

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

        /** 落盘提示的类型标记。 */
        const val KIND_A11Y = "a11y"
        const val KIND_IME = "ime"

        /**
         * 通知还能送到用户眼前吗。
         *
         * Android 8+ 用 NotificationManager.areNotificationsEnabled()；
         * 低版本查 AppOps 的 OP_POST_NOTIFICATION——有些 OEM 会把这里关掉
         * 而 areNotificationsEnabled() 仍返回 true，两个都看才稳。
         */
        private fun notificationsEnabled(nm: NotificationManager): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!nm.areNotificationsEnabled()) return false
            }
            return try {
                val ctx = nm as? Context ?: return true
                val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    aom.unsafeCheckOpNoThrow(
                        OP_POST_NOTIFICATION,
                        android.os.Process.myUid(), ctx.packageName)
                } else {
                    aom.checkOpNoThrow(
                        OP_POST_NOTIFICATION,
                        android.os.Process.myUid(), ctx.packageName)
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) { true }
        }

        /** 发一条高优先级通知，点开直接跳无障碍设置页。OEM 把无障碍悄悄关掉时复用此入口。 */
        fun notifyRecovery(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CH_ID, "Jev 助手恢复", NotificationManager.IMPORTANCE_HIGH)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            // 双通道兜底：通知被屏蔽时提示会静默丢失，落到 prefs 由首页横幅补送。
            if (!notificationsEnabled(nm)) {
                Prefs(ctx).apply {
                    pendingAlert = ctx.t("助手已停止：无障碍服务被关闭，点此重新开启",
                        "Assistant stopped: accessibility service is off — tap to re-enable")
                    pendingAlertKind = KIND_A11Y
                }
                android.util.Log.w("JEVASSIST",
                    "notifyRecovery: notifications blocked -> pending alert saved for home banner")
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

        /**
         * 输入法被系统重置（ColorOS 覆盖安装后必发）时提示用户手动切回。
         * 铁律：App 不替用户切输入法（showInputMethodPicker / ime set 都禁止），
         * 所以这里只发通知写清手动切法，点击进 App 主页。通知 ID 与恢复通知错开。
         */
        fun notifyImeReset(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CH_ID, "Jev 助手恢复", NotificationManager.IMPORTANCE_HIGH)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            // 同上：通知被屏蔽时，提示落盘等首页补送。
            if (!notificationsEnabled(nm)) {
                Prefs(ctx).apply {
                    pendingAlert = ctx.t("输入法被重置，自动回复已暂停，点此切回写入通道",
                        "Keyboard was reset, auto-reply paused — tap to switch back to the write channel")
                    pendingAlertKind = KIND_IME
                }
                android.util.Log.w("JEVASSIST",
                    "notifyImeReset: notifications blocked -> pending alert saved for home banner")
            }
            val go = Intent(ctx, com.jev.probe.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(ctx, 1, go, flags)
            val n = NotificationCompat.Builder(ctx, CH_ID)
                .setContentTitle("输入法被系统重置，自动回复已暂停")
                .setContentText("在聊天里点输入框 → 键盘图标 → 选「AI聊天助手·写入」即可恢复")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "系统（覆盖安装后）把默认输入法重置回了讯飞，回复写不进输入框。" +
                    "请在聊天里点一下输入框 → 讯飞工具栏键盘图标 → 选「AI聊天助手·写入」，" +
                    "之后每轮自动生效，不用再切。"))
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            @Suppress("DEPRECATION")
            nm.notify(NOTIF_ID + 1, n)
        }
    }
}
