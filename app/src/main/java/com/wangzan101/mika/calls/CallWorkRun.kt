package com.wangzan101.mika.calls

import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.cascade.CascadeCallProvider
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.calls.CallRuntime.CallProblem
import com.wangzan101.mika.calls.CallRuntime.MediaStage
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.MAX_MEDIA_DIAGNOSTICS
import com.wangzan101.mika.calls.CallRuntime.Companion.PHONE_DTMF_PROMPT
import com.wangzan101.mika.calls.CallRuntime.Companion.FAREWELL_ENDING
import com.wangzan101.mika.calls.CallRuntime.Companion.TOKEN

// 电话运行时:一次 AI 通话的执行、唤醒锁与取消
internal fun CallRuntime.startWork(attempt: Work) {
    check(work == null)
    work = attempt
    detail = app.getString(
        if (attempt.automatic) R.string.calls_answer_pending else R.string.calls_notification_preparing,
    )
    if (attempt.automatic) {
        attempt.autoDelayEndsAt = SystemClock.elapsedRealtime() + attempt.config.autoAnswerDelaySeconds * 1_000L
    }
    attempt.job = scope.launch(start = CoroutineStart.LAZY) { runWork(attempt) }
    publish()
    attempt.job?.start()
}

internal suspend fun CallRuntime.waitAutoAnswerDelay(w: Work) {
    while (w.automatic) {
        val remaining = (w.autoDelayEndsAt ?: break) - SystemClock.elapsedRealtime()
        if (remaining <= 0L) break
        withTimeoutOrNull(minOf(1_000L, remaining)) { w.skipAutoDelay.await() }
        publish()
    }
    w.autoDelayEndsAt = null
    detail = app.getString(R.string.calls_notification_answer_preparing)
    publish()
}

/** A user-confirmed outgoing attempt owns a temporary lease, independent of auto-answer. */
internal suspend fun CallRuntime.ensureOutgoingForegroundService(w: Work) {
    if (standbyService == null) {
        if (!requestCallForegroundService(incoming = false)) {
            throw CallProblem(standbyStartError ?: "外呼后台电话服务未能启动，未拨号")
        }
        if (!awaitCallForegroundReady(
                isStarting = { standbyStarting },
                isReady = { work === w && standbyService?.leaseAvailable == true },
            )) {
            throw CallProblem(standbyStartError ?: "Android 未确认外呼后台电话服务启动，未拨号")
        }
    }
    requireOutgoingForegroundService(w)
}

internal fun CallRuntime.requireOutgoingForegroundService(w: Work) {
    val leaseReady = standbyService?.leaseAvailable == true && CallStandbyService.notificationsAvailable(app)
    if (work !== w || !leaseReady || !hasOutgoingForegroundWork()) {
        throw CallProblem(standbyStartError ?: "外呼后台电话服务不可用，AI 未执行后续操作")
    }
}

internal suspend fun CallRuntime.runWork(w: Work) {
    try {
        if (!notifications.show(w.id, detail.orEmpty(), ringing = w.direction == CallDirection.INCOMING)) {
            throw CallProblem("需要打开 AI 电话通知，才能随时取消或接管")
        }
        if (!interactiveForeground()) acquireCallWakeLock(w, 20_000L)
        if (w.direction == CallDirection.OUTGOING) ensureOutgoingForegroundService(w)
        if (w.automatic) waitAutoAnswerDelay(w)
        prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
        validateTarget(w)
        val helper = shell ?: throw CallProblem("Mika 内置助手未连接")
        val migrated = realtimeController.migrated
        val standard = standardCalls
        // 标准通话:所属任务的状态每轮作为尾随附注给电话前台,系统提示保持字节不变
        val ownerBackground = w.ownerTask?.let(PhoneDesk::background)
        val provider = if (standard) CascadeCallProvider(cascade, CallUse.Phone, background = ownerBackground) else if (migrated) {
            try {
                realtimeController.callProvider()
            } catch (e: VoiceModelError) {
                throw CallProblem("实时语音配置不可用，请检查语音模型")
            }
        } else ProfileRealtimeProvider()
        val profile = if (migrated) {
            activeVoiceProfile()
        } else {
            voiceProfiles.get(w.config.activeVoiceProfileId)
                ?: throw CallProblem("实时语音配置不可用，请重新选择")
        }
        val credentials = if (standard) VolcRealtimeCredentials() else if (migrated) {
            try {
                realtimeController.credentials()
            } catch (e: VoiceModelError) {
                throw CallProblem("实时语音凭据不可用，请重新保存")
            }
        } else {
            withContext(Dispatchers.IO) { voiceProfiles.credentials(profile.id) }
                ?: throw CallProblem("实时语音凭据不可用，请重新保存")
        }
        if (!probeAudio()) throw CallProblem(audioDetail ?: "此设备的双向通话音频不可用")
        if (shell !== helper || w.config.audioMode != settings.state.value.audioMode) {
            throw CallProblem("通话音频设置已变化，请重试")
        }
        validateTarget(w)
        val ownerRules = withContext(Dispatchers.IO) {
            runCatching { instructions.promptSnapshot() }
                .getOrElse { throw CallProblem("无法读取电话交代，未由 AI 操作电话") }
        }
        validateTarget(w)
        // 现有电话提示在前,电话前台说明在后;实时通话把开场时所属任务的状态写进提示,之后变了再推
        val systemPrompt = PhoneDesk.systemPrompt(
            CallPolicy.prompt(w.config.systemPrompt, w.direction, ownerRules, task = w.task) + PHONE_DTMF_PROMPT,
            task = w.task.takeIf { w.direction == CallDirection.OUTGOING },
            openingOwnerTask = if (standard) null else PhoneDesk.renderOwnerTask(w.ownerTask?.value),
            canCue = PhoneDesk.canCue(standard, profile.protocol),
        )
        try {
            provider.validate(profile, systemPrompt, enablePhoneDtmf = true)
        } catch (_: IllegalArgumentException) {
            throw CallProblem("实时语音配置或本次交代无效，未由 AI 操作电话；请检查配置或缩短交代")
        } catch (_: VoiceModelError) {
            throw CallProblem("实时语音配置不可用，未由 AI 操作电话")
        }
        val session = startCallSession(
            direction = w.direction,
            connect = {
                validateSessionTarget(w, helper)
                w.mediaStage = MediaStage.VOLC_CONNECT
                val connected = withTimeoutOrNull(5_000L) {
                    provider.connect(profile, credentials, systemPrompt, enablePhoneDtmf = true)
                        .also { w.session = it } // Adopt before the timeout/cancellation boundary.
                } ?: throw CallProblem("实时语音连接超时，未由 AI 接管")
                if (!connected.setMuted(true)) throw CallProblem("实时语音服务未进入就绪状态")
                validateSessionTarget(w, helper)
                connected
            },
            awaitCarrier = {
                if (!withContext(Dispatchers.IO) { access.ready() }) {
                    armed = false
                    throw CallProblem("系统通话管理权限不可用，未由 AI 操作电话")
                }
                prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
                val ringingCall = w.track
                if (w.automatic && ringingCall != null && (ringingCall.initialRingPolicy?.incomingScope == IncomingScope.STRANGERS ||
                        settings.state.value.incomingScope == IncomingScope.STRANGERS)) {
                    val number = ringingCall.number
                    if (number == null || !isUnsavedNumber(number) || ringingCall.number != number) {
                        throw CallProblem(app.getString(R.string.calls_strangers_note))
                    }
                }
                validateTarget(w)
                w.mediaStage = MediaStage.TELECOM_CONNECT
                if (w.direction == CallDirection.INCOMING) {
                    val target = w.track ?: throw CallProblem("来电已结束")
                    w.answerSent = true
                    w.telecomActionIssued = true
                    detail = "正在等待 Android 确认接听"
                    if (runCatching { target.call.answer(VideoProfile.STATE_AUDIO_ONLY) }.isFailure) {
                        throw CallProblem("Android 未能发出接听请求")
                    }
                    publish()
                } else {
                    if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("已有通话或无法确认空闲，未拨号")
                    val sim = w.sim ?: throw CallProblem("没有可用的 SIM")
                    val number = w.requestedNumber ?: throw CallProblem("没有可用的电话号码")
                    // Recheck the chosen account before placing the call; Telecom must not fall
                    // back to another SIM if the selection disappeared during preparation.
                    readSims()
                    if (w.lockedDialTicket != null && !withContext(Dispatchers.IO) { access.ready() }) {
                        armed = false
                        throw CallProblem("系统通话管理权限不可用，未执行锁屏拨出")
                    }
                    if (sims.none { it.handle == sim.handle }) throw CallProblem("选定的拨号 SIM 已不可用，请重新选择")
                    prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
                    if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("已有通话或无法确认空闲，未拨号")
                    if (isEmergency(number) != false) throw CallProblem("无法确认此号码可安全拨打")
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
                        putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(TOKEN, w.id) })
                    }
                    validateLockedDial(w)
                    requireOutgoingForegroundService(w)
                    w.placed = true
                    w.telecomActionIssued = true
                    val issued = runCatching { checkNotNull(telecom).placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras) }.isSuccess
                    lockedDial?.takeIf { it.ticket === w.lockedDialTicket }?.let {
                        finishLockedDial(it, if (issued) "正在等待 Android 确认锁屏拨号" else "Android 拒绝锁屏拨号")
                    }
                    if (!issued) {
                        throw CallProblem("Android 拒绝拨号")
                    }
                    if (!waitFor(CallPolicy.DIAL_TIMEOUT_MS) { w.track != null }) {
                        throw CallProblem("未收到该号码的 Android 通话回调，请查看系统拨号器")
                    }
                }
                val limit = if (w.direction == CallDirection.INCOMING) CallPolicy.CONTROL_TIMEOUT_MS else 55_000L
                if (!waitFor(limit) { w.track?.phase == CallPhase.ACTIVE }) {
                    throw CallProblem("Android 未确认通话接通，电话由系统处理")
                }
                val target = w.track ?: throw CallProblem("通话已结束")
                if (!carrierCall(target.call) || liveCalls().singleOrNull() !== target) {
                    throw CallProblem("当前通话无法由 AI 安全接管")
                }
                w.activeAt = System.currentTimeMillis()
                w.activeElapsed = SystemClock.elapsedRealtime()
                acquireCallWakeLock(w)
                w.bridging = true
                detail = "已接通，正在建立双向音频"
                publish()
            },
        )
        runAudio(w, helper, session, pushOwnerTask = !standard, canCue = PhoneDesk.canCue(standard, profile.protocol))
    } catch (e: CancellationException) {
        // Coroutine cancellation may carry a secondary descriptor-close exception.
        if (BuildConfig.DEBUG) e.suppressed.take(MAX_MEDIA_DIAGNOSTICS).forEach { debugMediaException(w, it) }
        throw e
    } catch (e: CallProblem) {
        debugMediaException(w, e)
        w.stopReason.record(e.message ?: "AI 电话未就绪")
    } catch (e: CallMediaFailure) {
        debugMediaException(w, e)
        if (w.stopReason.value == null) {
            w.stopReason.record(shellAudioFailure(w) ?: e.message ?: "通话音频已断开")
        }
    } catch (e: VolcRealtimeException) {
        debugMediaException(w, e)
        w.stopReason.record(when (e.kind) {
            VolcRealtimeException.Kind.AUTH -> "实时语音服务拒绝认证，请检查电话语音凭据和服务开通状态"
            VolcRealtimeException.Kind.RATE_LIMITED -> "实时语音达到服务限额，电话由系统处理"
            VolcRealtimeException.Kind.TIMEOUT, VolcRealtimeException.Kind.NETWORK -> "实时语音连接不可用，电话由系统处理"
            VolcRealtimeException.Kind.BACKPRESSURE -> "实时音频拥塞，电话由系统处理"
            else -> "实时语音服务未能继续，请检查通话配置"
        })
    } catch (e: VoiceModelError) {
        debugMediaException(w, e)
        w.stopReason.record(when (e.category) {
            VoiceErrorCategory.AUTH_FAILED -> "实时语音服务拒绝认证，请检查电话语音凭据和服务开通状态"
            VoiceErrorCategory.RATE_LIMITED -> "实时语音达到服务限额，电话由系统处理"
            VoiceErrorCategory.TIMEOUT, VoiceErrorCategory.NETWORK -> "实时语音连接不可用，电话由系统处理"
            else -> "实时语音服务未能继续，请检查通话配置"
        })
    } catch (e: Exception) {
        debugMediaException(w, e)
        w.stopReason.record("AI 音频连接失败，电话由系统处理")
    } finally {
        withContext(NonCancellable) {
            w.mediaStage = MediaStage.RELEASE
            runCatching { w.session?.close() }.onFailure { debugMediaException(w, it) }
            val restored = try { restoreAudio(w) } finally { releaseCallWakeLock(w) }
            if (w.stopReason.value == FAREWELL_ENDING) {
                val target = w.track
                val ended = target != null && (confirmedEnded(target) ||
                    waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))
                w.stopReason.refine(FAREWELL_ENDING, if (ended) "通话已收尾，Android 已挂断"
                    else "通话已收尾，Android 未确认挂断，已交还系统")
            }
            val reason = w.stopReason.value ?: "AI 已退出，电话由系统处理"
            if (BuildConfig.DEBUG) w.mediaCounters.playbackDiagnostic()?.let { Log.d("MikaCallMedia", "playback $it") }
            w.track?.let {
                if (w.telecomActionIssued) {
                    val answeredByAi = w.aiActive && w.direction == CallDirection.INCOMING
                    val summaryRequested = settings.state.value.summaryEnabled && w.transcript.isNotEmpty()
                    val record = CallRecord(w.id, it.number, w.direction, w.activeAt ?: it.createdAt,
                        System.currentTimeMillis(), reason, w.transcript.toList(), task = w.task,
                        audioReport = w.mediaCounters.recordReport(),
                        summaryStatus = if (summaryRequested) SummaryStatus.PENDING else SummaryStatus.NOT_REQUESTED)
                    scope.launch {
                        records.append(record)
                        if (answeredByAi) notifications.completed(w.id, record.number, w.direction,
                            summaryPending = summaryRequested)
                        if (summaryRequested) summarizeCall(w.id, record.transcript)
                    }
                }
            }
            if (work === w) {
                lockedDial?.takeIf { it.ticket === w.lockedDialTicket }?.let {
                    finishLockedDial(it, reason, cancelUnplacedWork = false)
                }
                work = null
                detail = if (restored) reason else "$reason；音频恢复尚未确认"
                if (w.lockedDialTicket != null) lockedDialDetail = detail
                stopBackgroundStandbyIfUnneeded()
                if (w.telecomActionIssued && w.track?.phase == CallPhase.ACTIVE) notifications.handoff()
                else notifications.clear()
                publish()
            }
            w.result.complete(fail(detail ?: reason))
            w.cleaned.complete(restored)
        }
    }
}

/** Only a pending AI attempt or active call can hold a timed partial wake lock. */
internal fun CallRuntime.acquireCallWakeLock(w: Work, timeoutMs: Long = CallStandbyPolicy.activeWakeBudgetMs(w.config.maxCallSeconds)) {
    releaseCallWakeLock(w)
    runCatching {
        val power = app.getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:ActiveCall")
        lock.setReferenceCounted(false)
        w.wakeLock = lock
        lock.acquire(timeoutMs)
    }.onFailure { debugMediaException(w, it) }
}

internal fun CallRuntime.releaseCallWakeLock(w: Work) {
    val lock = w.wakeLock ?: return
    w.wakeLock = null
    runCatching { if (lock.isHeld) lock.release() }.onFailure { debugMediaException(w, it) }
}

internal fun CallRuntime.cancelWork(reason: String): Work? {
    val active = work ?: return null
    active.stopReason.record(reason)
    active.track?.suppressed = true
    active.job?.cancel()
    publish()
    return active
}

internal suspend fun CallRuntime.awaitCleanup(w: Work?): Boolean =
    w == null || withTimeoutOrNull(CallPolicy.CONTROL_TIMEOUT_MS) { w.cleaned.await() } == true

internal suspend fun CallRuntime.awaitResult(w: Work): CallActionResult = try {
    w.result.await()
} catch (e: CancellationException) {
    if (!w.telecomActionIssued && !w.automatic && work === w) cancelWork("操作已取消，电话由系统处理")
    throw e
}

internal suspend fun CallRuntime.waitFor(timeout: Long, test: () -> Boolean): Boolean =
    withTimeoutOrNull(timeout) { changes.first { test() }; true } == true
