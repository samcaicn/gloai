package com.wangzan101.mika.calls

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.calls.CallRuntime.CallProblem
import com.wangzan101.mika.calls.CallRuntime.PendingLockedDial
import com.wangzan101.mika.calls.CallRuntime.Work

// 电话运行时:锁屏时排队拨出
/** Takes effect only after the owner confirms this specific task while the app is visible. */
suspend fun CallRuntime.queueLockedDial(number: String, simId: String? = null, task: CallTaskBrief): CallActionResult = onMain {
    if (!interactiveForeground() || !userUnlocked()) return@onMain fail("请解锁手机并在电话页面确认本次拨号")
    if (!secureDeviceLock()) return@onMain fail("请先为手机设置 PIN、图案或密码锁")
    if (lockedDial != null) return@onMain fail("已有一次锁屏拨出，请先取消")
    val snapshot = try { task.validatedSnapshot() } catch (e: IllegalArgumentException) {
        return@onMain fail(e.message ?: "本次拨打交代无效")
    }
    prerequisite()?.let { return@onMain fail(it) }
    val normalized = CallPolicy.outgoingNumber(number) ?: return@onMain fail("仅支持普通号码，不支持紧急号码、短号或控制代码")
    if (isEmergency(normalized) != false) return@onMain fail("紧急号码或无法确认安全的号码不能拨打")
    if (!lockedDialIdle()) return@onMain fail("已有通话或无法确认空闲，未安排拨号")
    readSims()
    if (!withContext(Dispatchers.IO) { access.ready() }) return@onMain fail("系统通话管理权限不可用")
    if (!interactiveForeground() || !userUnlocked() || !secureDeviceLock()) {
        return@onMain fail("请在手机解锁时确认安排锁屏拨出")
    }
    prerequisite()?.let { return@onMain fail(it) }
    if (lockedDial != null || !lockedDialIdle()) return@onMain fail("已有拨号安排或通话，未安排拨号")
    val config = settings.state.value
    val picked = CallPolicy.chooseOutgoingSim(sims.map { it.info }, simId, config.defaultOutgoingSimId)
        ?: return@onMain fail(if (simId == null && config.defaultOutgoingSimId != null) "保存的默认拨号 SIM 已不可用，请重新选择"
            else "请选择一张有效的 SIM，或明确选择可用的系统默认 SIM")
    val sim = sims.single { it.info.id == picked.id }
    if (!CallStandbyService.notificationsAvailable(app, create = true)) return@onMain fail("请允许后台待命通知，以便取消锁屏拨出")
    val ticket = lockedDialGate.arm(normalized, picked.id, snapshot) ?: return@onMain fail("未能安排本次锁屏拨出")
    val pending = PendingLockedDial(ticket, config, sim, settings.credentialsRevision.value)
    lockedDial = pending
    lockedDialDetail = "已安排锁屏拨出，请在 45 秒内锁屏"
    // A short one-shot lock keeps the confirmation window ticking as the screen turns off.
    // There is no indefinite standby wake lock and no attempt to wake or unlock the display.
    pending.wakeLock = runCatching {
        val power = checkNotNull(app.getSystemService(PowerManager::class.java))
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:LockedDial").apply {
            setReferenceCounted(false)
            acquire((ticket.expiresAtMs - SystemClock.elapsedRealtime() + 1_000L).coerceIn(1L, 46_000L))
        }
    }.getOrNull()
    if (pending.wakeLock == null) {
        finishLockedDial(pending, "无法保持锁屏拨号确认窗口，未安排拨号")
        return@onMain fail(lockedDialDetail.orEmpty())
    }
    if (standbyService == null && !requestCallForegroundService(incoming = false)) {
        return@onMain fail(lockedDialDetail ?: "后台电话服务未启动")
    }
    pending.job = scope.launch(start = CoroutineStart.LAZY) { observeLockedDial(pending) }
    pending.job?.start()
    publish()
    if (lockedDial === pending) ok("已安排一次锁屏拨出；Android 尚未拨号")
    else fail(lockedDialDetail ?: "本次锁屏拨出未安排")
}

suspend fun CallRuntime.cancelLockedDial(requestId: String): CallActionResult = onMain {
    val pending = lockedDial?.takeIf { it.ticket.id == requestId }
        ?: return@onMain fail("该锁屏拨出已结束或已提交系统")
    finishLockedDial(pending, "已取消本次锁屏拨出")
    ok("已取消本次锁屏拨出")
}

internal fun CallRuntime.secureDeviceLock(): Boolean =
    runCatching { app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

internal fun CallRuntime.lockedDialIdle(pending: PendingLockedDial? = null): Boolean =
    (work == null || pending != null && work?.lockedDialTicket === pending.ticket) &&
        liveCalls().isEmpty() && managedCallPresent() == false

internal fun CallRuntime.lockedDialEligible(pending: PendingLockedDial): Boolean = userUnlocked() && secureDeviceLock() &&
    (standbyService != null || standbyStarting) && settings.state.value.audioMode == pending.config.audioMode &&
    settings.credentialsRevision.value == pending.credentialsRevision &&
    prerequisite(ignoreWork = true) == null && CallStandbyService.notificationsAvailable(app) && lockedDialIdle(pending)

internal suspend fun CallRuntime.observeLockedDial(pending: PendingLockedDial) {
    while (lockedDial === pending) {
        val remaining = pending.ticket.expiresAtMs - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            finishLockedDial(pending, "锁屏拨出安排已过期，未拨号")
            return
        }
        val deviceLocked = keyguardLocked()
        val eligible = lockedDialEligible(pending)
        if (pending.claimed) {
            if (!deviceLocked || !eligible || standbyService == null) {
                finishLockedDial(pending, "手机已解锁或通话条件变化，未执行锁屏拨出")
                return
            }
        } else when (val decision = lockedDialGate.poll(pending.ticket, deviceLocked && standbyService != null, eligible)) {
            LockedDialGate.Decision.WaitingForLock -> Unit
            is LockedDialGate.Decision.Rejected -> {
                finishLockedDial(pending, if (decision.reason == LockedDialGate.Rejection.EXPIRED) "锁屏拨出安排已过期，未拨号"
                    else "电话条件变化，未执行锁屏拨出")
                return
            }
            is LockedDialGate.Decision.Claimed -> {
                pending.claimed = true
                lockedDialDetail = "手机已锁屏，正在准备拨号"
                val request = decision.request
                startWork(Work(CallDirection.OUTGOING, pending.config, requestedNumber = request.number,
                    sim = pending.sim, task = request.task, lockedDialTicket = pending.ticket))
            }
        }
        if (mutable.value.lockedDialRemainingSeconds != remainingSeconds(pending)) publish()
        delay(minOf(250L, remaining))
    }
}

internal fun CallRuntime.remainingSeconds(pending: PendingLockedDial): Int =
    ((pending.ticket.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L).div(1_000L).toInt()

internal fun CallRuntime.finishLockedDial(pending: PendingLockedDial, reason: String, cancelUnplacedWork: Boolean = true) {
    if (lockedDial !== pending) return
    lockedDial = null
    lockedDialGate.cancel(pending.ticket)
    pending.job?.cancel()
    runCatching { pending.wakeLock?.takeIf { it.isHeld }?.release() }
    pending.wakeLock = null
    lockedDialDetail = reason
    if (cancelUnplacedWork && work?.let { it.lockedDialTicket === pending.ticket && !it.placed } == true) cancelWork(reason)
    stopBackgroundStandbyIfUnneeded()
    publish()
}

internal fun CallRuntime.validateLockedDial(w: Work) {
    val ticket = w.lockedDialTicket ?: return
    val pending = lockedDial?.takeIf { it.ticket === ticket && it.claimed }
        ?: throw CallProblem("锁屏拨出已取消，未拨号")
    when (lockedDialGate.submissionRejection(ticket) {
        standbyService != null && lockedDialEligible(pending) && keyguardLocked()
    }) {
        LockedDialGate.Rejection.EXPIRED -> throw CallProblem("锁屏拨出安排已过期，未拨号")
        null -> Unit
        else -> throw CallProblem("手机已解锁或通话条件变化，未执行锁屏拨出")
    }
}
