package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationManager
import com.wangzan101.mika.R
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import com.wangzan101.mika.task.TaskService.Companion.ensureChannels
import com.wangzan101.mika.task.TaskService.Companion.openApp
import com.wangzan101.mika.task.TaskService.Companion.nextResultId
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_ATTENTION
import com.wangzan101.mika.task.TaskService.Companion.ID_RELOCK
import com.wangzan101.mika.task.TaskService.Companion.RELOCK_IDLE_GRACE_MS
import com.wangzan101.mika.task.TaskService.Companion.RELOCK_POLL_TRIES
import com.wangzan101.mika.task.TaskService.Companion.RELOCK_POLL_MS
import com.wangzan101.mika.task.TaskService.Companion.cancel

// 任务服务:任务期间解锁与结束后重新上锁
/** 一次任务驱动的解锁完成:以此刻的主屏交互计数为基线,并挂上常驻提醒(见 [startUnlockGuard])。 */
internal fun TaskService.noteTaskUnlock() = taskRelock.unlocked(
    com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.mainInteractionCount())

/**
 * 任务收尾时调用([ignore] 是正在收尾的这个)。还有任务在跑或排队就等;都结束了才裁决:
 * 已锁→了结;不能锁(无障碍不在、或还留着一页)→警告;等待期里用户动了主屏→等一段空闲再裁;
 * 否则经全局动作回锁,并轮询等系统确认,两次不确认就换成常驻警告。
 */
internal suspend fun TaskService.finishTaskRelock(ignore: TaskRun? = null) = relockMutex.withLock {
    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
    fun count() = com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.mainInteractionCount()
    fun runsRemain() = center.runs().any { it !== ignore }
    fun accessibility() = com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.instance.value
    fun decision() = taskRelock.decide(
        runsRemain = runsRemain(), locked = systemLockConfirmed(keyguard), interactions = count(),
        canLock = accessibility() != null && keeper.page.value == null,
    )
    var result = decision()
    if (result == TaskRelock.Decision.WAIT_IDLE) {
        val before = count()
        delay(RELOCK_IDLE_GRACE_MS)
        if (!taskRelock.acceptIdle(before, count())) { notifyRelockFailed(); return@withLock }
        result = decision()
    }
    when (result) {
        TaskRelock.Decision.NONE -> { clearUnlockGuardIfSafe(); return@withLock }
        TaskRelock.Decision.WAIT -> return@withLock
        TaskRelock.Decision.WARN, TaskRelock.Decision.WAIT_IDLE -> { notifyRelockFailed(); return@withLock }
        TaskRelock.Decision.LOCK -> Unit
    }
    repeat(2) {
        // 重试间隙里来了新任务、或用户动了手机:这次回锁一律让位,重新裁决
        when (decision()) {
            TaskRelock.Decision.NONE -> { clearUnlockGuardIfSafe(); return@withLock }
            TaskRelock.Decision.WAIT -> return@withLock
            TaskRelock.Decision.WARN, TaskRelock.Decision.WAIT_IDLE -> { notifyRelockFailed(); return@withLock }
            TaskRelock.Decision.LOCK -> Unit
        }
        val accepted = accessibility()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        if (accepted) repeat(RELOCK_POLL_TRIES) {
            if (systemLockConfirmed(keyguard)) {
                taskRelock.confirmed()
                clearUnlockGuardIfSafe()
                return@withLock
            }
            delay(RELOCK_POLL_MS)
        }
    }
    notifyRelockFailed()
}

/** 系统意义上的已锁:锁屏在显示;有安全锁的还要求设备层也锁上。 */
internal fun TaskService.systemLockConfirmed(keyguard: android.app.KeyguardManager): Boolean =
    keyguard.isKeyguardLocked && (!keyguard.isKeyguardSecure || keyguard.isDeviceLocked)

/**
 * 解锁期间挂的常驻提醒:任务被中断、进程没了它也留在通知栏,提醒用户检查锁屏。
 * 通知发不出去(权限、渠道被关)时回锁义务立不住,调用方据此不去解锁。
 */
internal fun TaskService.startUnlockGuard(): Boolean {
    ensureChannels(this)
    val manager = getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(CHANNEL_ATTENTION) ?: return false
    if (!manager.areNotificationsEnabled() || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    return runCatching {
        manager.notify(ID_RELOCK, Notification.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.relock_guard_title))
            .setContentText(tr(R.string.relock_guard_body))
            .setStyle(Notification.BigTextStyle().bigText(tr(R.string.relock_guard_body)))
            .setContentIntent(openApp()).setOngoing(true).build())
        true
    }.getOrDefault(false)
}

/** 没有挂着的回锁义务时摘掉常驻提醒;义务还挂着(别的任务解的)不能摘。 */
internal fun TaskService.clearUnlockGuardIfSafe() {
    if (!taskRelock.hasPending()) runCatching { getSystemService(NotificationManager::class.java).cancel(ID_RELOCK) }
}

/** 回锁没能确认:同一个编号的常驻警告换上岗,用户看到之前一直在。 */
internal fun TaskService.notifyRelockFailed() {
    ensureChannels(this)
    runCatching {
        getSystemService(NotificationManager::class.java).notify(ID_RELOCK,
            Notification.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.relock_failed_title))
                .setContentText(tr(R.string.relock_failed_body))
                .setStyle(Notification.BigTextStyle().bigText(tr(R.string.relock_failed_body)))
                .setContentIntent(openApp()).setOngoing(true).build())
    }
}

/** 解锁失败后转为等用户手动解锁:发一条可点回对话的提醒([status] 是不含凭据的状态码)。 */
internal fun TaskService.notifyUnlockRecoveryNeeded(conversationId: String, status: String) {
    ensureChannels(this)
    val safeStatus = status.takeIf { it.matches(Regex("[a-z_]{1,50}")) }.orEmpty()
    val message = tr(R.string.unlock_recovery_body) + if (safeStatus.isBlank()) "" else " ($safeStatus)"
    runCatching {
        getSystemService(NotificationManager::class.java).notify(nextResultId(),
            Notification.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.unlock_recovery_title)).setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp(conversationId)).setAutoCancel(true).build())
    }
}
