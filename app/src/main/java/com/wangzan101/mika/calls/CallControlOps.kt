package com.wangzan101.mika.calls

import com.wangzan101.mika.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import com.wangzan101.mika.calls.CallRuntime.Tracked
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.CALL_CHANGED

// 电话运行时:拨号、接听、按键、挂断与接管
/** [ownerConversationId]:发起这通电话的任务所在的对话;这时就认定这件任务,电话里的前台读它的状态。 */
suspend fun CallRuntime.dial(
    number: String,
    simId: String? = null,
    task: CallTaskBrief? = null,
    ownerConversationId: String? = null,
): CallActionResult = onMain {
    val taskSnapshot = try {
        task?.validatedSnapshot()
    } catch (e: IllegalArgumentException) {
        return@onMain report(e.message ?: "本次拨打交代无效")
    }
    prerequisite()?.let { return@onMain report(it) }
    if (lockedDial != null) return@onMain report("请先取消已安排的锁屏拨出")
    val normalized = CallPolicy.outgoingNumber(number) ?: return@onMain report("仅支持普通号码，不支持紧急号码、短号或控制代码")
    if (isEmergency(normalized) != false) return@onMain report("紧急号码或无法确认安全的号码不能拨打")
    if (liveCalls().isNotEmpty() || work != null || managedCallPresent() != false) {
        return@onMain report("已有通话或无法确认空闲，未拨号")
    }
    readSims()
    prerequisite()?.let { return@onMain report(it) }
    if (lockedDial != null) return@onMain report("请先取消已安排的锁屏拨出")
    if (liveCalls().isNotEmpty() || work != null || managedCallPresent() != false) {
        return@onMain report("已有通话或无法确认空闲，未拨号")
    }
    val config = settings.state.value
    val picked = CallPolicy.chooseOutgoingSim(sims.map { it.info }, simId, config.defaultOutgoingSimId)
        ?: return@onMain report(when {
            simId == null && config.defaultOutgoingSimId != null -> "保存的默认拨号 SIM 已不可用，请重新选择"
            sims.isEmpty() -> "没有可用于运营商通话的 SIM"
            else -> "请选择一张有效的 SIM，或明确选择系统默认 SIM"
        })
    val sim = sims.single { it.info.id == picked.id }
    val ownerTask = ownerConversationId?.let { app.taskState.run(it)?.state }
    // 前台派出的任务跑在藏起来的子会话里,主人看的是它的前台会话
    val ownerConversation = ownerConversationId?.let { app.conversations.deskParent(it) ?: it }
    val attempt = Work(CallDirection.OUTGOING, config, requestedNumber = normalized, sim = sim, task = taskSnapshot,
        ownerTask = ownerTask, ownerConversationId = ownerConversation)
    startWork(attempt)
    awaitResult(attempt)
}

suspend fun CallRuntime.answer(expectedCallId: String? = null): CallActionResult = onMain {
    prerequisite()?.let { return@onMain report(it) }
    val call = liveCalls().singleOrNull()
    if (expectedCallId != null && call?.id != expectedCallId) return@onMain report(CALL_CHANGED)
    if (call == null || call.direction != CallDirection.INCOMING || call.phase != CallPhase.RINGING ||
        call.suppressed || !carrierCall(call.call)) {
        return@onMain report("没有可接听的单路运营商来电")
    }
    work?.let {
        if (it.track === call && it.direction == CallDirection.INCOMING && it.stopReason.value == null) {
            // An explicit answer for this exact call takes priority over a scheduled answer.
            if (!it.answerSent) {
                it.manualAnswerRequested = true
                it.autoDelayEndsAt = null
                it.skipAutoDelay.complete(Unit)
                detail = app.getString(R.string.calls_notification_answer_preparing)
                publish()
            }
            return@onMain awaitResult(it)
        }
        return@onMain report("已有 AI 通话正在进行")
    }
    val attempt = Work(CallDirection.INCOMING, settings.state.value, track = call, expectedCallId = expectedCallId)
    startWork(attempt)
    awaitResult(attempt)
}

/** Explicitly bound to the displayed carrier call; remains usable after AI handoff. */
suspend fun CallRuntime.sendDtmf(expectedCallId: String, digits: String): CallActionResult = onMain {
    val target = liveCalls().singleOrNull()?.takeIf { it.id == expectedCallId }
        ?: return@onMain fail(CALL_CHANGED)
    val result = sendCallDtmf(target, digits)
    CallActionResult(result.status == "submitted", dtmfMessage(result))
}

internal suspend fun CallRuntime.sendCallDtmf(target: Tracked, digits: String, owner: Work? = null): CallDtmfResult {
    fun owned(): Boolean = settings.state.value.enabled && tracked[target.call] === target &&
        target.phase == CallPhase.ACTIVE && liveCalls().singleOrNull() === target &&
        target.service.tracks(target.call) && carrierCall(target.call) &&
        (owner == null || (work === owner && owner.track === target && owner.bridging &&
            owner.stopReason.value == null && !target.suppressed && sameActiveMuteTarget(owner, target)))
    val result = try {
        dtmf.send(digits, stillOwned = ::owned,
            play = { digit -> target.call.playDtmfTone(digit) },
            stop = { target.call.stopDtmfTone() },
            budget = if (owner != null) target.dtmfBudget else null)
    } catch (cancelled: CancellationException) {
        recordDtmfStatus(target, owner, "按键发送已停止")
        throw cancelled
    }
    recordDtmfStatus(target, owner, dtmfMessage(result))
    return result
}

internal fun CallRuntime.recordDtmfStatus(target: Tracked, owner: Work?, message: String) {
    // Deliberately keep the keys out of transcript, history, notifications and logs.
    owner?.let { appendTranscript(it.transcript, "system", message) }
    if (tracked[target.call] === target && liveCalls().singleOrNull() === target) {
        if (liveTranscriptCallId == target.id) appendTranscript(liveTranscript, "system", message)
        if (target.phase == CallPhase.ACTIVE) detail = message
        publish()
    }
}

internal fun CallRuntime.dtmfMessage(result: CallDtmfResult): String = when (result.status) {
    "submitted" -> "已提交 ${result.sentDigits} 个按键，等待对方响应"
    "partial" -> "已提交 ${result.sentDigits}/${result.requestedDigits} 个按键，发送已中断"
    "invalid_digits" -> "请输入 1 至 32 个按键，仅限 0–9、*、#"
    "busy" -> "正在发送按键，请稍后再试"
    "budget_exceeded" -> "本通 AI 按键次数已达上限，可人工接管后使用键盘"
    "unavailable" -> "通话或 AI 控制权已变化，已停止发送按键"
    else -> "未能完成按键发送，请等待对方响应后再操作"
}

suspend fun CallRuntime.hangup(expectedCallId: String? = null): CallActionResult = onMain {
    if (!settings.state.value.enabled || !armed) return@onMain report("AI 电话尚未准备好")
    val call = liveCalls().singleOrNull()
    if (expectedCallId != null && call?.id != expectedCallId) return@onMain report(CALL_CHANGED)
    if (call == null || call.phase == CallPhase.DISCONNECTING || !carrierCall(call.call)) {
        return@onMain report("没有可挂断的单路运营商通话")
    }
    // Shared by local and validated remote hangup; stop keys before waiting for AI cleanup.
    dtmf.cancelPending()
    call.suppressed = true
    val active = cancelWork("用户挂断")
    val cleaned = awaitCleanup(active)
    val endedMessage = if (cleaned) "Android 已确认通话结束" else "Android 已确认通话结束；音频恢复尚未确认"
    if (confirmedEnded(call)) return@onMain ok(endedMessage)
    if (!isCurrentCall(call, expectedCallId) || call.phase == CallPhase.DISCONNECTING) return@onMain report(CALL_CHANGED)
    if (runCatching {
            if (call.phase == CallPhase.RINGING) call.call.reject(false, null) else call.call.disconnect()
        }.isFailure) return@onMain report("Android 未能发出挂断请求")
    val ended = waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(call) } || confirmedEnded(call)
    if (ended) ok(endedMessage) else report("Android 尚未确认通话结束，请查看系统电话界面")
}

suspend fun CallRuntime.cancelAutoAnswer(expectedCallId: String? = null): CallActionResult = onMain {
    val current = work
    val target = current?.track
    if (current == null || target == null || !isPendingAutoAnswer(current, target) ||
        !isCurrentCall(target, expectedCallId)) {
        return@onMain fail("没有可取消的这次自动接听")
    }
    val active = cancelWork("已取消本次 AI 接听")
    if (!awaitCleanup(active)) return@onMain fail("已取消 AI 接听，清理尚未确认")
    if (isCurrentCall(target, expectedCallId) && target.phase == CallPhase.RINGING) ok("已取消 AI 接听，电话继续响铃")
    else ok("已取消本次 AI 接听")
}

suspend fun CallRuntime.takeover(): CallActionResult = takeover(null)

internal suspend fun CallRuntime.takeover(callId: String?): CallActionResult = onMain {
    val current = work
    if (current == null || (callId != null && callId != current.id)) return@onMain fail("这次 AI 通话已结束")
    dtmf.cancelPending()
    current.track?.suppressed = true
    val active = cancelWork(if (current.pendingRing) "已取消本次 AI 接听" else "已停止 AI，通话交给系统拨号器")
    if (awaitCleanup(active)) ok(detail ?: "已停止 AI")
    else report("AI 已停止，通话音频恢复尚未确认，请查看系统电话界面")
}
