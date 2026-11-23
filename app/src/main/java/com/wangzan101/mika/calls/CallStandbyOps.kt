package com.wangzan101.mika.calls

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.wangzan101.mika.calls.CallRuntime.Companion.STANDBY_START_MS

// 电话运行时:后台待命前台服务与自动接听暂停
/** Initial authorization comes only from this explicit action in the unlocked phone UI. */
suspend fun CallRuntime.startBackgroundStandby(): CallActionResult = onMain {
    val config = settings.state.value
    if (!interactiveForeground() || !userUnlocked()) return@onMain fail("请解锁手机并在电话页面启动后台待命")
    if (!CallStandbyPolicy.optedIn(config, userUnlocked())) return@onMain fail("请先开启自动接听并完成通话配置")
    autoAnswerBlocker(config)?.let { return@onMain fail(it) }
    if (!CallStandbyService.notificationsAvailable(app, create = true)) return@onMain fail("请允许后台待命通知")
    if (isIncomingStandbyRunning()) return@onMain ok("后台待命已启动")
    if (standbyStarting) return@onMain fail("Android 正在启动后台待命")
    if (!settings.authorizeBackgroundStandby()) return@onMain fail("无法保存后台接听授权，请重试")
    if (!requestCallForegroundService(incoming = true)) return@onMain fail(standbyStartError ?: "后台待命未启动")
    state.first { !it.backgroundStandbyStarting }
    if (isIncomingStandbyRunning()) ok("后台待命已启动") else fail(standbyStartError ?: "后台待命未启动")
}

internal fun CallRuntime.requestCallForegroundService(incoming: Boolean): Boolean {
    if (standbyStarting) return !incoming || standbyIncomingRequested
    standbyIncomingRequested = incoming
    standbyStarting = true
    standbyStartError = null
    val startId = UUID.randomUUID().toString()
    standbyStartId = startId
    publish()
    if (runCatching {
        val intent = CallStandbyService.startIntent(app, startId)
        if (standbyService != null) app.startService(intent) else app.startForegroundService(intent)
    }.getOrNull() == null) {
        onStandbyStartFailed("Android 未允许启动后台待命，请在应用中重试")
        return false
    }
    // This deadline belongs to the runtime, even if the initiating screen is dismissed.
    standbyStartTimeout = scope.launch {
        delay(STANDBY_START_MS)
        if (standbyStarting) {
            standbyStartTimeout = null
            onStandbyStartFailed("Android 尚未确认后台待命启动，请重试")
        }
    }
    return true
}

internal fun CallRuntime.isStandbyOptedIn(): Boolean = CallStandbyPolicy.optedIn(settings.state.value, userUnlocked())

internal fun CallRuntime.hasAuthorizedIncomingStandby(): Boolean = isStandbyOptedIn() && settings.state.value.backgroundStandbyAuthorized

internal fun CallRuntime.isIncomingStandbyRunning(): Boolean = standbyService?.stickyAutoAnswer == true && hasAuthorizedIncomingStandby()

internal fun CallRuntime.hasOutgoingForegroundWork(): Boolean = CallStandbyPolicy.foregroundOutgoing(
    userUnlocked(), settings.state.value.enabled, lockedDial != null, work?.direction,
)

internal fun CallRuntime.isStandbyStartRequested(requestId: String?): Boolean = standbyStarting && requestId != null && requestId == standbyStartId

internal fun CallRuntime.isIncomingStandbyStartRequested(): Boolean = standbyIncomingRequested

internal fun CallRuntime.onStandbyStarted(service: CallStandbyService) {
    standbyStartTimeout?.cancel()
    standbyStartTimeout = null
    standbyService = service
    standbyStarting = false
    standbyIncomingRequested = false
    standbyStartId = null
    standbyStartError = null
    standbyRunning.value = true
    publish()
}

internal fun CallRuntime.onStandbyStartFailed(reason: String) {
    standbyStartTimeout?.cancel()
    standbyStartTimeout = null
    standbyStarting = false
    standbyIncomingRequested = false
    standbyStartId = null
    standbyStartError = reason
    lockedDial?.let { finishLockedDial(it, "后台电话服务未启动，未执行锁屏拨出") }
    publish()
}

internal fun CallRuntime.onStandbyStopped(service: CallStandbyService) {
    if (standbyService !== service) return
    standbyService = null
    standbyRunning.value = false
    lockedDial?.let { finishLockedDial(it, "后台电话服务停止，未执行锁屏拨出") }
    if (work?.let { (it.direction == CallDirection.OUTGOING) || (it.automatic && it.answerSent && !interactiveForeground()) } == true) {
        cancelWork("后台电话服务已停止，通话由系统处理")
    }
    cancelPendingIfStandbyLost()
    publish()
}

internal fun CallRuntime.stopBackgroundStandby() {
    val running = standbyService != null
    standbyStartTimeout?.cancel()
    standbyStartTimeout = null
    standbyStarting = false
    standbyIncomingRequested = false
    standbyStartId = null
    standbyService = null
    standbyRunning.value = false
    // Android can crash the process if a startForegroundService request is stopped before
    // it is acknowledged with startForeground. An in-flight request acknowledges then stops itself.
    if (running) runCatching { app.stopService(CallStandbyService.startIntent(app)) }
    cancelPendingIfStandbyLost()
}

internal fun CallRuntime.stopBackgroundStandbyIfUnneeded() {
    if (!hasOutgoingForegroundWork() && (standbyService != null || standbyStarting) &&
        !(hasAuthorizedIncomingStandby() && (standbyService?.stickyAutoAnswer == true || standbyIncomingRequested))) {
        stopBackgroundStandby()
    }
}

internal fun CallRuntime.cancelPendingIfStandbyLost() {
    if (!canAutomaticallyAnswerHere() && work?.let { it.automatic && !it.answerSent && it.stopReason.value == null } == true) {
        cancelWork("后台待命不可用，本次来电由系统电话处理")
    }
}

internal fun CallRuntime.userUnlocked(): Boolean =
    runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked == true }.getOrDefault(false)

internal fun CallRuntime.interactiveForeground(): Boolean = runCatching {
    CallStandbyPolicy.interactiveForeground(
        visible = app.foreground.visible.value,
        interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true,
        keyguardShowing = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
    )
}.getOrDefault(false)

internal fun CallRuntime.keyguardLocked(): Boolean =
    runCatching { app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }.getOrDefault(false)

internal fun CallRuntime.canAutomaticallyAnswerHere(): Boolean = CallStandbyPolicy.allowsAutomaticAnswer(
    userUnlocked = userUnlocked(), interactiveForeground = interactiveForeground(), serviceRunning = isIncomingStandbyRunning(),
    notificationsAllowed = standbyService != null && CallStandbyService.notificationsAvailable(app),
)

suspend fun CallRuntime.pauseAutoAnswer(minutes: Int): CallActionResult = onMain {
    if (minutes !in 1..CallPolicy.MAX_AUTO_ANSWER_PAUSE_MINUTES) {
        return@onMain fail("暂停时间须在 1 分钟到 24 小时之间")
    }
    if (!settings.state.value.enabled || !settings.state.value.autoAnswer) {
        return@onMain fail("自动接听尚未开启")
    }
    saveAutoPause(System.currentTimeMillis() + minutes * 60_000L)
}

suspend fun CallRuntime.resumeAutoAnswer(): CallActionResult = onMain { saveAutoPause(null) }

internal suspend fun CallRuntime.saveAutoPause(until: Long?): CallActionResult {
    if (autoPauseWriting) return fail("自动接听设置正在保存")
    autoPauseWriting = true
    if (until != null) {
        // Existing calls handled by AI stay connected; a pending auto-answer is left alone.
        if (work?.let { it.automatic && !it.answerSent } == true) {
            cancelWork("自动接听已暂停，本次来电由系统电话处理")
        }
        liveCalls().filter { it.phase == CallPhase.RINGING }.forEach { it.autoTried = true }
    }
    val saved = try {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                synchronized(settings) {
                    settings.save(settings.state.value.copy(autoAnswerPausedUntilMs = until))
                }
            }.isSuccess
        }
    } finally {
        autoPauseWriting = false
        publish()
    }
    return if (!saved) fail("无法保存自动接听暂停设置")
    else ok(if (until == null) "已恢复自动接听设置" else "已暂停自动接听")
}
