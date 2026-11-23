package com.wangzan101.mika.calls

import android.os.SystemClock
import android.telecom.Call
import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import kotlinx.coroutines.launch
import com.wangzan101.mika.calls.CallRuntime.Tracked
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.disconnected

// 电话运行时:系统通话事件与自动接听触发
internal fun CallRuntime.onCallAdded(service: MikaInCallService, call: Call) {
    unobservedManagedCall = false
    tracked.getOrPut(call) { Tracked(call, service) }
    onCallChanged(service, call)
}

internal fun CallRuntime.onCallChanged(service: MikaInCallService, call: Call) {
    val t = tracked[call]?.takeIf { it.service === service } ?: return
    if (call.state == Call.STATE_DISCONNECTED) t.taskBinding.end()
    if (t.phase == CallPhase.RINGING && t.initialRingPolicy == null) {
        t.initialRingPolicy = settings.state.value
        t.ringObservedAt = SystemClock.elapsedRealtime()
        t.ringUptime = SystemClock.uptimeMillis()
        t.ringObservedWallMs = System.currentTimeMillis()
        if (autoPauseWriting || !canAutomaticallyAnswerHere()) t.autoTried = true
    }
    val calls = liveCalls()
    if (calls.isNotEmpty()) lockedDial?.takeUnless { work?.lockedDialTicket === it.ticket && work?.placed == true }
        ?.let { finishLockedDial(it, "检测到另一通电话，未执行锁屏拨出") }
    if (calls.size > 1) calls.filter { it.phase == CallPhase.RINGING }.forEach { it.autoTried = true }
    val active = work
    if (active != null) {
        if (active.track == null && active.placed && matchesOutgoing(active, t)) {
            active.track = t
            t.taskBinding.bind(active.task)
        }
        val target = active.track
        if (target === t && active.direction == CallDirection.OUTGOING &&
            call.state in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE)) {
            if (active.lockedDialTicket != null) lockedDialDetail = "Android 已确认锁屏拨出"
            active.result.complete(ok("Android 已确认拨号；是否接通请查看实时状态"))
        }
        when {
            calls.size > 1 -> cancelWork("检测到多路通话，已交还系统拨号器")
            target === t && disconnected(call) -> cancelWork(callEndMessage(t))
            target === t && active.direction == CallDirection.INCOMING && !active.answerSent && t.phase != CallPhase.RINGING ->
                cancelWork(if (t.phase == CallPhase.ACTIVE || t.phase == CallPhase.CONNECTING || t.phase == CallPhase.HOLDING)
                    "已由系统电话处理，AI 未介入" else "来电不再响铃，AI 未接听")
            target === t && active.direction == CallDirection.INCOMING && !active.answerSent &&
                (t.direction != CallDirection.INCOMING || !carrierCall(t.call)) ->
                cancelWork("来电已变化，AI 未接听")
            target === t && active.automatic && !active.answerSent &&
                !CallPolicy.callerAllowed(settings.state.value, t.number, type = t.type, isStranger = confirmedStranger(t)) -> cancelWork("来电已不符合自动接听规则")
            target === t && t.phase != CallPhase.ACTIVE && active.bridging ->
                cancelWork("通话已切换，AI 音频已停止")
        }
    }
    publish()
    maybeStartAutoAnswer()
}

internal fun CallRuntime.onCallerLabelEvent(service: ScreenAccessibility, event: AccessibilityEvent) {
    if (tracked.isEmpty()) return
    val config = settings.state.value
    if (!config.enabled || !config.autoAnswer || config.incomingScope != IncomingScope.TYPES) return
    when (event.eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> Unit
        else -> return
    }
    val t = liveCalls().singleOrNull() ?: return
    val initial = t.initialRingPolicy ?: return
    if (!initial.enabled || !initial.autoAnswer || initial.incomingScope != IncomingScope.TYPES ||
        t.phase != CallPhase.RINGING || t.direction != CallDirection.INCOMING || t.suppressed ||
        event.eventTime < t.ringUptime || t.number == null || !carrierCall(t.call) ||
        CallPolicy.isBypassed(config, t.number) || CallPolicy.isBypassed(initial, t.number)) return
    val pending = work?.let { it.automatic && it.track === t && !it.answerSent } == true
    if (!pending && (t.autoTried || SystemClock.elapsedRealtime() - t.ringObservedAt > CallPolicy.AUTO_START_WINDOW_MS)) return
    val changed = try {
        if (t.labelService !== service) {
            t.labelService = service
            t.evidence = IncomingTypeEvidence()
        }
        val reader = t.labelReader ?: IncomingCallLabels(app).also { t.labelReader = it }
        reader.read(service, event, t.number, t.evidence)
    } catch (_: RuntimeException) {
        // The dialer can remove its window while accessibility is reading it.
        false
    }
    if (changed) {
        onCallChanged(t.service, t.call)
    }
}

internal fun CallRuntime.maybeStartAutoAnswer() {
    if (work != null || lockedDial != null || autoPauseWriting || !canAutomaticallyAnswerHere()) return
    val t = liveCalls().singleOrNull() ?: return
    val initial = t.initialRingPolicy ?: return
    if (t.phase != CallPhase.RINGING || t.direction != CallDirection.INCOMING || t.suppressed || t.autoTried) return
    val config = settings.state.value
    val needsLookup = initial.incomingScope == IncomingScope.STRANGERS || config.incomingScope == IncomingScope.STRANGERS
    if (!CallPolicy.canStartAutoAnswer(initial, config, t.number,
            SystemClock.elapsedRealtime() - t.ringObservedAt,
            ringObservedWallMs = t.ringObservedWallMs, type = t.type, isStranger = needsLookup)) return
    if (autoAnswerBlocker(config) != null || !t.service.tracks(t.call) || !carrierCall(t.call)) return
    if (needsLookup && !confirmedStranger(t)) {
        val number = t.number ?: return
        if (t.lookupNumber != number && hasContactsAccess()) {
            t.contactLookup?.cancel()
            t.lookupNumber = number
            t.unsavedNumber = null
            t.contactLookup = scope.launch {
                if (isUnsavedNumber(number) && tracked[t.call] === t && t.number == number) {
                    t.unsavedNumber = number
                    maybeStartAutoAnswer()
                }
            }
        }
        return
    }
    t.autoTried = true
    startWork(Work(CallDirection.INCOMING, config, track = t, auto = true))
}

internal fun CallRuntime.onCallRemoved(service: MikaInCallService, call: Call) {
    val removed = tracked[call]?.takeIf { it.service === service } ?: return
    removed.taskBinding.end()
    removed.contactLookup?.cancel()
    tracked.remove(call)
    if (work?.track === removed) cancelWork(
        if (confirmedEnded(removed)) callEndMessage(removed) else "Android 通话状态已断开，电话由系统处理",
    )
    publish()
}

internal fun CallRuntime.onTelecomMuteChanged(service: MikaInCallService, muted: Boolean) {
    val current = work ?: return
    if (current.track?.service === service && current.muteLease?.observeTelecomMute(muted) == true) {
        cancelWork("系统通话静音已解除，AI 已交还通话")
    }
}
