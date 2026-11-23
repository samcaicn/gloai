package com.wangzan101.mika.calls

import android.media.AudioManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.calls.CallRuntime.CallProblem
import com.wangzan101.mika.calls.CallRuntime.MediaStage
import com.wangzan101.mika.calls.CallRuntime.Tracked
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.MAX_MEDIA_DIAGNOSTICS
import com.wangzan101.mika.calls.CallRuntime.Companion.MUTE_ACK_MS
import com.wangzan101.mika.calls.CallRuntime.Companion.FAREWELL_ENDING

// 电话运行时:通话音频桥接、静音租约与恢复
internal suspend fun CallRuntime.probeAudio(): Boolean {
    val helper = shell ?: return false
    val mode = settings.state.value.audioMode
    val epoch = ++probeEpoch
    val probe = withContext(Dispatchers.IO) { runCatching { helper.probeCallAudio(mode.wire) }.getOrNull() }
    if (helper !== shell || mode != settings.state.value.audioMode) return false
    val ready = probe?.getBoolean("ready") == true
    if (epoch == probeEpoch) {
        audioReady = ready
        audioDetail = if (ready) "音频路径具备运行条件，仍需双向实机验证"
            else probe?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "内置助手无法检测电话音频"
        publish()
    }
    return ready
}

/** [pushOwnerTask]:实时通话里所属任务的状态变了要推一句(标准通话每轮附注里就有,不用推);[canCue]:见 [PhoneDesk.canCue]。 */
internal suspend fun CallRuntime.runAudio(w: Work, helper: IMikaShell, session: CallRealtimeSession, pushOwnerTask: Boolean = false,
                                         canCue: Boolean = true) {
    val greeting = CallPolicy.spokenGreeting(w.config.greeting, hasTask = w.task != null)
    val notification = app.getString(
        if (greeting.isBlank()) R.string.calls_notification_connected_waiting else R.string.calls_notification_connected,
    )
    if (!notifications.show(w.id, app.getString(R.string.calls_notification_connecting_audio), ringing = false)) {
        throw CallProblem("电话通知不可用，已交还系统")
    }
    // Capture is never started while ringing, dialing, held, or in a different call.
    val target = w.track ?: throw CallProblem("通话已结束")
    if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper) {
        throw CallProblem("通话状态已变更，AI 未接入音频")
    }
    w.mediaStage = MediaStage.MUTE_ACQUIRE
    val mute = acquireTelecomMute(w, target, helper)
    if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper ||
        !settings.state.value.enabled || !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) {
        throw CallProblem("通话状态或静音已变更，AI 未接入音频")
    }
    w.mediaStage = MediaStage.SHELL_OPEN
    w.audioHelper = helper
    val audioAccount = target.call.details?.accountHandle
    val channel = awaitCallAudioOpen(
        isCurrent = {
            sameActiveMuteTarget(w, target) && !target.suppressed && shell === helper &&
                target.call.details?.accountHandle == audioAccount &&
                (w.direction != CallDirection.OUTGOING || (audioAccount != null && audioAccount == w.sim?.handle)) &&
                settings.state.value.enabled && w.config.audioMode == settings.state.value.audioMode &&
                mute.isConfirmed(target.service.telecomMuted, microphoneMuted())
        },
        nowMs = SystemClock::elapsedRealtime,
    ) {
        // A successful attempt transfers every descriptor to Work before cancellation can run cleanup.
        // Only the helper's resource-free readiness rejection can request another attempt.
        withContext(NonCancellable + Dispatchers.IO) {
            val result = helper.openCallAudio(w.config.audioMode.wire, w.audioOwner)
            if (result?.getBoolean("ready") != true) {
                if (result?.getString("errorCode") == "cellular_audio_not_ready") return@withContext null
                throw CallProblem(result?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "此设备未建立双向电话音频")
            }
            val down = result.fd("downlink")
            val up = result.fd("uplink")
            if (down == null || up == null ||
                result.getInt("captureRate") != session.inputSampleRate ||
                result.getInt("playRate") != session.outputSampleRate) {
                runCatching { down?.close() }
                runCatching { up?.close() }
                throw CallProblem("内置助手的电话音频格式不兼容")
            }
            val capture = ParcelFileDescriptor.AutoCloseInputStream(down)
            val playback = ParcelFileDescriptor.AutoCloseOutputStream(up)
            w.capture = capture
            w.playback = playback
            CallPcmBridge(capture, playback) {
                withContext(Dispatchers.IO) { runCatching { helper.flushCallAudio(w.audioOwner) }.getOrDefault(false) }
            }
        }
    }
    if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper ||
        !settings.state.value.enabled || !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) {
        throw CallProblem("通话状态或静音已变更，未发送语音")
    }
    // 0 = 不限时长:超时长按「永不触发」给(库对 Long.MAX_VALUE 的等待有特判,不会排闹钟)
    val remaining = w.config.maxCallSeconds.takeIf { it > 0 }
        ?.let { (it * 1_000L - (SystemClock.elapsedRealtime() - w.activeElapsed)).coerceAtLeast(1) }
    w.mediaStage = MediaStage.PCM_BRIDGE
    // 电话前台:只能请示主人;主人的回复和任务状态只当提示交给模型转述,不原样念给对方;
    // 到挂断还没交给模型的回复记进通话记录(回调可能来自别的线程,回主线程写)
    val desk = PhoneDesk(
        ownerTask = { w.ownerTask },
        ask = OwnerAskNotifier(app, app.ownerQuestions, w.ownerConversationId),
        cue = { text, ended -> session.sendCue(text, ended) },
        unrelayed = { reply -> scope.launch { appendTranscript(w.transcript, CallTranscriptLine.OWNER_UNRELAYED, reply) } },
        context = { text -> session.sendContext(text) },
        canCue = canCue,
    )
    try {
        withTimeoutOrNull(remaining ?: Long.MAX_VALUE) {
            coroutineScope {
                val ownerTaskPush = if (pushOwnerTask) launch { desk.pushOwnerTaskChanges() } else null
                // 到点前让 AI 先说句道别(两家供应商都照字面播);道别走自然挂断时这里提前收尾
                val closingJob = CallPolicy.closingDelayMs(remaining)?.let { closingAt ->
                    launch {
                        delay(closingAt)
                        if (work === w && w.stopReason.value == null && w.aiActive &&
                            !target.suppressed && sameActiveMuteTarget(w, target)) {
                            runCatching { session.sendGreeting(CallPolicy.CLOSING_LINE) }
                        }
                    }
                }
                val watchdog = launch {
                    var lastBytes = -1L
                    var stalled = 0
                    while (true) {
                        delay(if (w.aiActive) 1_500 else 350)
                        // 锁有自然到期(尤其不限时长按时长档拿的预算),到期就重新拿,通话没完不能放
                        if (w.wakeLock?.isHeld == false) acquireCallWakeLock(w)
                        val stats = withContext(Dispatchers.IO) {
                            runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull()
                        }
                        if (stats?.getBoolean("active") != true) {
                            throw CallMediaFailure(shellAudioStopDiagnostic(stats?.getString("reason"))
                                ?: "设备已停止双向音频，电话由系统处理")
                        }
                        val bytes = stats.getLong("downBytes")
                        if (!w.aiActive) {
                            if (callAudioHasStarted(greeting, bytes, stats.getLong("upBytes"))) {
                                if (!notifications.show(w.id, notification, ringing = false)) {
                                    throw CallProblem("电话通知不可用，已交还系统")
                                }
                                w.aiActive = true
                                detail = notification
                                if (w.direction == CallDirection.INCOMING) {
                                    w.result.complete(ok(if (greeting.isBlank()) "Android 已接听，AI 正在等待对方说话"
                                        else "Android 已接听，双向音频桥已开始传输"))
                                }
                                publish()
                            } else if (SystemClock.elapsedRealtime() - w.activeElapsed >= 7_000) {
                                throw CallMediaFailure("双向音频未能及时建立，电话由系统处理")
                            }
                        } else {
                            stalled = if (lastBytes == bytes) stalled + 1 else 0
                            if (stalled >= 2) throw CallMediaFailure("运营商音频输入无进展，电话由系统处理")
                        }
                        lastBytes = bytes
                    }
                }
                try {
                    channel.run(
                        session, greeting, counters = w.mediaCounters,
                        onTranscript = { role, text -> appendTranscript(w.transcript, role, text) },
                        onStage = { stage ->
                            if (work === w && w.voiceStage != stage) {
                                w.voiceStage = stage
                                publish()
                            }
                        },
                        sendDtmf = { digits -> onMain { sendCallDtmf(target, digits, owner = w) } },
                        desk = desk::execute,
                        onFinalTranscript = { role, text ->
                            if (work === w && w.stopReason.value == null && target.phase == CallPhase.ACTIVE &&
                                liveCalls().singleOrNull() === target && liveTranscriptCallId == target.id &&
                                appendTranscript(liveTranscript, role, text)) {
                                publish()
                            }
                        },
                        playedOutputFrames = {
                            withContext(Dispatchers.IO) {
                                val stats = helper.callAudioStats(w.audioOwner)
                                if (stats?.getBoolean("upPlaybackHeadFresh") == true && stats.getBoolean("upPlaybackRouteReady")) {
                                    (stats.getLong("upPlaybackHeadFrames") - stats.getLong("upPlaybackPrimeFrames"))
                                        .coerceIn(0L, stats.getLong("upPlaybackEpochDataBytes") / 2L)
                                } else 0L
                            }
                        },
                        awaitFarewellDrain = { fence ->
                            if (!sameActiveMuteTarget(w, target) || target.suppressed || work !== w) false
                            else awaitFarewellPlayback(helper, w, fence)
                        },
                        commitFarewell = {
                            // No suspension: speech can still cancel the waiter up to this point.
                            if (target.suppressed || w.stopReason.value != null || shell !== helper ||
                                !settings.state.value.enabled || !sameActiveMuteTarget(w, target) ||
                                !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) false
                            else if (runCatching { target.call.disconnect() }.isSuccess) w.stopReason.record(FAREWELL_ENDING)
                            else false
                        },
                    )
                } finally {
                    watchdog.cancel()
                    closingJob?.cancel()
                    ownerTaskPush?.cancel()
                }
            }
        }
    } finally {
        // 通话结束:撤掉待回的问题和通知,迟到的回复不再转进来
        desk.close()
    }
    // A natural ending already issued the owner-bound disconnect with media still running.
    if (w.stopReason.value == FAREWELL_ENDING) return
    // 不限时长:桥正常返回只可能是自然结束(上面已接住),到这里就是异常之外的兜底,别谎报时长上限
    if (w.config.maxCallSeconds <= 0) return
    val ending = "已达到 AI 通话时长上限"
    if (!w.stopReason.record(ending)) return
    w.mediaStage = MediaStage.RELEASE
    w.session?.close()
    val restored = restoreAudio(w)
    w.aiActive = false
    w.bridging = false
    if (!restored || work !== w || target.suppressed || !sameActiveMuteTarget(w, target)) return
    // 挂断可能没被确认:多试几次,别头一次没确认就把活电话塞回用户手里
    var ended = false
    repeat(CallPolicy.HANGUP_ATTEMPTS) {
        if (ended || work !== w || target.suppressed || !sameActiveMuteTarget(w, target)) return@repeat
        ended = runCatching { target.call.disconnect() }.isSuccess &&
            (waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))
    }
    w.stopReason.refine(ending, if (ended) "达到时长上限，Android 已挂断"
        else "达到时长上限，Android 未确认挂断，已交还系统")
}

internal suspend fun CallRuntime.awaitFarewellPlayback(helper: IMikaShell, w: Work, fence: CallFarewellFence): Boolean {
    suspend fun reached(): Boolean {
        val stats = withContext(Dispatchers.IO) { runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull() }
        return stats?.getBoolean("active") == true && fence.reached(
            playedEpoch = stats.getLong("upPlaybackEpoch", -1L),
            acceptedBytes = stats.getLong("upPlaybackEpochDataBytes", -1L),
            primeFrames = stats.getLong("upPlaybackPrimeFrames", -1L),
            playedFrames = stats.getLong("upPlaybackHeadFrames", -1L),
            fresh = stats.getBoolean("upPlaybackHeadFresh"),
            routed = stats.getBoolean("upPlaybackRouteReady"),
        )
    }
    return withTimeoutOrNull(10_000L) {
        while (!reached()) delay(100L)
        // Keep the collector live through the quiet tail; any new speech cancels this waiter.
        delay(if (fence.explicit) 1_000L else 3_500L)
        reached()
    } == true
}

internal fun CallRuntime.appendTranscript(lines: MutableList<CallTranscriptLine>, role: String, text: String): Boolean {
    val line = CallTranscriptLine(role, text.trim().take(CallHistory.MAX_LINE_LENGTH))
    if (line.text.isEmpty() || lines.lastOrNull() == line) return false
    if (lines.size >= CallHistory.MAX_LINES) lines.removeAt(0)
    lines.add(line)
    return true
}

internal suspend fun CallRuntime.acquireTelecomMute(w: Work, target: Tracked, helper: IMikaShell): TelecomMuteLease {
    val service = target.service
    val lease = awaitTelecomMuteBaseline(
        isCurrent = { sameActiveMuteTarget(w, target) && !target.suppressed && settings.state.value.enabled &&
            shell === helper && runCatching { helper.asBinder().isBinderAlive }.getOrDefault(false) &&
            settings.state.value.credentialsConfigured && w.config.audioMode == settings.state.value.audioMode },
        nowMs = SystemClock::elapsedRealtime,
        sample = { TelecomMuteBaseline(
            cellularModeReady = runCatching { app.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL }.getOrDefault(false),
            telecomMuted = service.telecomMuted,
            hardwareMuted = microphoneMuted(),
            observationRevision = service.muteObservationRevision,
        ) },
    )
    w.muteLease = lease
    if (lease.needsMuteRequest) {
        // Mark before the Binder request: an in-flight mute still needs a compensating
        // unmute if the acknowledgement times out while this exact call remains active.
        lease.markMuteRequested()
        if (!service.requestMute(target.call, true)) {
            throw CallProblem("Android 未接受本次通话静音请求")
        }
    }
    val confirmed = awaitMute {
        sameActiveMuteTarget(w, target) && !target.suppressed &&
            lease.isConfirmed(service.telecomMuted, microphoneMuted())
    }
    if (!confirmed) throw CallProblem("Android 未确认通话麦克风静音，AI 未接入音频")
    return lease
}

internal fun CallRuntime.sameActiveMuteTarget(w: Work, target: Tracked): Boolean =
    target.phase == CallPhase.ACTIVE && sameMuteTarget(w, target)

internal fun CallRuntime.sameMuteTarget(w: Work, target: Tracked): Boolean =
    work === w && w.track === target && isCurrentCall(target, target.id) &&
        (target.phase == CallPhase.ACTIVE || target.phase == CallPhase.HOLDING) && target.service.tracks(target.call)

internal fun CallRuntime.microphoneMuted(): Boolean? = runCatching {
    app.getSystemService(AudioManager::class.java)?.isMicrophoneMute
}.getOrNull()

internal suspend fun CallRuntime.awaitMute(ready: () -> Boolean): Boolean = withTimeoutOrNull(MUTE_ACK_MS) {
    while (!ready()) delay(40)
    true
} == true

internal suspend fun CallRuntime.shellAudioFailure(w: Work): String? = withContext(Dispatchers.IO) {
    val helper = w.audioHelper ?: return@withContext null
    val stats = runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull() ?: return@withContext null
    if (stats.getBoolean("active")) null else shellAudioStopDiagnostic(stats.getString("reason"))
}

internal suspend fun CallRuntime.restoreAudio(w: Work): Boolean {
    // Release and flush hardware media before making the handset microphone available again.
    val shellRestored = restoreShellAudio(w)
    val muteRestored = restoreTelecomMute(w)
    return shellRestored && muteRestored
}

internal suspend fun CallRuntime.restoreTelecomMute(w: Work): Boolean {
    val lease = w.muteLease ?: return true
    val target = w.track
    fun sameCall() = target != null && sameMuteTarget(w, target)
    val plan = lease.beginRelease(sameCall())
    if (plan == TelecomMuteLease.Release.REQUEST_UNMUTE && target != null) {
        // No suspension since sameCall() above. Do not send a global mute command on a
        // different call, even if this service is still bound after the original call ended.
        target.service.requestMute(target.call, false)
    }
    val restored = awaitMute {
        plan.confirmed(sameCall(), target?.service?.telecomMuted, microphoneMuted())
    }
    if (restored) w.muteLease = null
    return restored
}

internal suspend fun CallRuntime.restoreShellAudio(w: Work): Boolean = withContext(Dispatchers.IO) {
    val helper = w.audioHelper
    runCatching { w.capture?.close() }.onFailure { debugMediaException(w, it) }
    runCatching { w.playback?.close() }.onFailure { debugMediaException(w, it) }
    w.capture = null
    w.playback = null
    if (helper == null) return@withContext true
    runCatching {
        helper.stopCallAudio(w.audioOwner)
        val stats = helper.callAudioStats(w.audioOwner) ?: return@runCatching false
        w.mediaCounters.outputShellAccepted(
            bytes = stats.getLong("upBytes", -1L),
            zeroCount = stats.getLong("upWriteZeroCount", -1L),
            zeroMaxMs = stats.getLong("upWriteZeroMaxMs", -1L),
            lastWriteAgeMs = stats.getLong("upLastWriteAgeMs", -1L),
            playbackHeadFrames = stats.getLong("upPlaybackHeadFrames", -1L),
            flushMaxMs = stats.getLong("upFlushMaxMs", -1L),
            captureSilencedMs = if (stats.getBoolean("clientSilencedKnown")) stats.getLong("clientSilencedMaxMs", -1L) else -1L,
            playbackRebuilds = stats.getLong("upPlaybackRebuilds", -1L),
            rebuildDroppedBytes = stats.getLong("upRebuildDroppedBytes", -1L),
        )
        !stats.getBoolean("active") && stats.getBoolean("microphoneRestored")
    }.onFailure { debugMediaException(w, it) }.getOrDefault(false).also {
        if (it) w.audioHelper = null
    }
}

internal fun CallRuntime.debugMediaException(w: Work, error: Throwable) {
    if (!BuildConfig.DEBUG || w.mediaDiagnosticCount >= MAX_MEDIA_DIAGNOSTICS) return
    w.mediaDiagnosticCount++
    val type = error.javaClass.simpleName.take(64).ifBlank { "Exception" }
    Log.d("MikaCallMedia", "stage=${w.mediaStage.name} exception=$type")
}

/** Never spend the outgoing ringing interval with an unattended cloud session. */
internal fun CallRuntime.validateSessionTarget(w: Work, helper: IMikaShell) {
    prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
    if (shell !== helper || w.config.audioMode != settings.state.value.audioMode) {
        throw CallProblem("通话音频设置或内置助手已变化，AI 未接入")
    }
    if (w.direction == CallDirection.INCOMING) {
        validateTarget(w)
    } else {
        requireOutgoingForegroundService(w)
        val target = w.track ?: throw CallProblem("外呼通话已结束，AI 未接入")
        val account = target.call.details?.accountHandle
        if (!sameActiveMuteTarget(w, target) || target.suppressed || !carrierCall(target.call) ||
            target.direction != CallDirection.OUTGOING || account == null || account != w.sim?.handle) {
            throw CallProblem("外呼通话已变化，AI 未接入")
        }
    }
}

internal fun CallRuntime.validateTarget(w: Work) {
    if (w.direction == CallDirection.INCOMING) {
        val t = w.track
        if (t == null || !isCurrentCall(t, w.expectedCallId) || t.phase != CallPhase.RINGING ||
            t.direction != CallDirection.INCOMING || t.suppressed || !t.service.tracks(t.call) || !carrierCall(t.call)) {
            throw CallProblem("来电已变化，AI 未接听")
        }
        if (w.automatic) {
            if (!canAutomaticallyAnswerHere()) throw CallProblem("后台待命不可用，本次来电由系统电话处理")
            val config = settings.state.value
            val initial = t.initialRingPolicy
            if (autoPauseWriting || initial == null ||
                !CallPolicy.callerAllowed(initial, t.number, t.ringObservedWallMs, t.type, confirmedStranger(t)) ||
                !CallPolicy.callerAllowed(config, t.number, type = t.type, isStranger = confirmedStranger(t)) || config.audioMode != w.config.audioMode ||
                SystemClock.elapsedRealtime() - t.ringObservedAt > CallPolicy.AUTO_ANSWER_WINDOW_MS) {
                throw CallProblem("来电已超时或不再符合自动接听规则")
            }
        }
    } else {
        if (liveCalls().isNotEmpty()) throw CallProblem("已有通话，未拨号")
        validateLockedDial(w)
    }
}
