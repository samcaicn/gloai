package com.wangzan101.mika.voicecall

import android.media.MediaRecorder
import com.wangzan101.mika.calls.CallInputPacer
import com.wangzan101.mika.calls.CallPlaybackQueue
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 通话状态机:显式枚举,任何时刻必居其一,不用布尔拼接。 */
sealed interface VoiceCallState {
    data object Connecting : VoiceCallState
    data object MicCheck : VoiceCallState
    data class Active(val startedAt: Long) : VoiceCallState
    data class Ended(val reason: EndReason) : VoiceCallState
    data class Failed(val error: VoiceCallError, val attempt: Int) : VoiceCallState
}

enum class EndReason { Hangup, Notification, IncomingCall, TimeLimit, Background }

enum class VoiceCallError { Auth, ConnectTimeout, Disconnected, RateLimited, Provider, MicSilent, AudioFocus, Playback, Busy }

/**
 * 应用内实时语音通话编排。一次接通的全链:等前台 RESUMED → 电话忙检测 → 音频焦点 → 连接实时会话 →
 * 开麦校验(VOICE_COMMUNICATION 初始化失败在编排层回退 MIC)→ 起播 → 本地两音提示音 →
 * 云端问候语(每会话只播一次)→ Active。
 *
 * Active 后三路并行:事件消费(下行音频进 20 秒上限的 [CallPlaybackQueue];barge-in 代次作废 + 硬件 flush;
 * mika_request 先登记当轮转写再过安全闸)、采集上行(20ms 帧绝对 deadline pacing)、10 分钟上限。
 * 收尾幂等:挂断/结束/失败统一走 finally 释放焦点、采集、播放与实时会话;外部取消不算失败。
 *
 * 安全闸([requestGate])每会话新建、跨重试不重置:callId 一次性要扛住重连重放。
 */
class VoiceCallSession(
    private val scope: CoroutineScope,
    private val audio: VoiceCallAudio,
    private val foregroundResumed: StateFlow<Boolean>,
    private val connect: suspend (attempt: Int) -> CallRealtimeSession,
    private val greeting: String,
    private val echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
    private val requestGate: VoiceCallRequest = VoiceCallRequest(echoWindow),
    private val onVoiceRequest: (callId: String, text: String) -> Unit = { _, _ -> },
    private val onBargeIn: () -> Unit = {},
    private val onUserTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> },
    private val onAssistantTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> },
    private val timeLimitMs: Long = MAX_CALL_DURATION_MS,
    private val resumedTimeoutMs: Long = RESUMED_TIMEOUT_MS,
    private val micCheckTimeoutMs: Long = MIC_CHECK_TIMEOUT_MS,
    private val clockNanos: () -> Long = System::nanoTime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<VoiceCallState>(VoiceCallState.Connecting)
    val state: StateFlow<VoiceCallState> = _state
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel
    private val _speakerLevel = MutableStateFlow(0f)
    val speakerLevel: StateFlow<Float> = _speakerLevel

    private val lock = Any()
    private var runJob: Job? = null
    private var terminal = false
    private var retryScheduled = false
    private var greeted = false

    init {
        startAttempt(1)
    }

    fun hangup() = end(EndReason.Hangup)

    private var typedCount = 0

    /**
     * 通话面板的文字输入:打字与说话同路——登记进转写(问),并以合成 callId([TYPED_CALL_PREFIX]+序号)
     * 直接派发给语音请求处理。打字本就是用户亲述,不过安全闸(闸防的是模型伪造);装配方对
     * 合成 callId 的结果回本地(进转写/面板),协议层没有它登记的 callId。仅 Active 态受理,空白忽略。
     */
    fun sendUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (_state.value !is VoiceCallState.Active) return
        val callId = TYPED_CALL_PREFIX + (++typedCount)
        onUserTranscript(clean, true)
        onVoiceRequest(callId, clean)
    }

    /**
     * 终态转换:已终态时再调是 no-op(收尾幂等)。唯一例外是 Failed → Ended:
     * 失败后用户不重试、直接离开时通话页负责收场,前台服务据此收工;落了 Ended 后 retry 不再有效。
     */
    fun end(reason: EndReason) {
        val job = synchronized(lock) {
            if (terminal) {
                if (_state.value is VoiceCallState.Failed) _state.value = VoiceCallState.Ended(reason)
                return
            }
            terminal = true
            _state.value = VoiceCallState.Ended(reason)
            runJob
        }
        job?.cancel()
    }

    /**
     * 等当前这次尝试的收尾跑完(挂断后仍有一路 finally:关实时会话、停采集与播放)。
     * 页面销毁时先等它,最后一段转写才不会丢;不能在本会话自己的协程域里调(会自等)。
     */
    suspend fun awaitFinished() {
        val job = synchronized(lock) { runJob }
        job?.join()
    }

    /** 就地重试:仅 Failed 态有效,attempt++ 后重跑整个连接流程;安全闸与回声窗不重置。 */
    fun retry() {
        val oldJob: Job?
        val attempt: Int
        synchronized(lock) {
            val failed = _state.value as? VoiceCallState.Failed ?: return
            if (!terminal || retryScheduled) return
            retryScheduled = true
            oldJob = runJob
            attempt = failed.attempt
        }
        val job = scope.launch {
            oldJob?.join() // 等上一次尝试的收尾跑完再重新占音频设备
            runAttempt(attempt + 1)
        }
        synchronized(lock) { runJob = job }
    }

    private fun startAttempt(attempt: Int) {
        val job = scope.launch { runAttempt(attempt) }
        synchronized(lock) { runJob = job }
    }

    private fun fail(error: VoiceCallError, attempt: Int) {
        val job = synchronized(lock) {
            if (terminal) return
            terminal = true
            _state.value = VoiceCallState.Failed(error, attempt)
            runJob
        }
        job?.cancel()
    }

    private suspend fun runAttempt(attempt: Int) {
        var realtime: CallRealtimeSession? = null
        try {
            synchronized(lock) {
                retryScheduled = false
                // 启动竞速:上一终态已被 end/hangup 落下时,本次尝试直接放弃
                if (terminal && _state.value !is VoiceCallState.Failed) return
                terminal = false
            }
            _state.value = VoiceCallState.Connecting
            if (!awaitResumed()) {
                end(EndReason.Background)
                return
            }
            if (audio.isTelephonyBusy()) throw VoiceCallFailure(VoiceCallError.Busy)
            if (!audio.requestCallAudio()) throw VoiceCallFailure(VoiceCallError.AudioFocus)
            audio.onFocusLost = { fail(VoiceCallError.AudioFocus, attempt) }
            realtime = connect(attempt)
            _state.value = VoiceCallState.MicCheck
            startCaptureWithFallback()
            checkMic()
            if (!audio.startPlayback()) throw VoiceCallFailure(VoiceCallError.Playback)
            if (!realtime.setMuted(false)) throw VoiceCallFailure(VoiceCallError.Provider)
            playTone()
            greetOnce(realtime)
            _state.value = VoiceCallState.Active(nowMillis())
            runActive(realtime)
        } catch (cancelled: CancellationException) {
            throw cancelled // 外部取消不是失败,终态由取消方决定
        } catch (failure: VoiceCallFailure) {
            fail(failure.error, attempt)
        } catch (error: VolcRealtimeException) {
            fail(error.kind.toVoiceCallError(), attempt)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            fail(VoiceCallError.Provider, attempt)
        } finally {
            audio.onFocusLost = null
            runCatching { realtime?.close() }
            audio.stopCapture()
            audio.stopPlayback()
            audio.abandonCallAudio()
            _micLevel.value = 0f
            _speakerLevel.value = 0f
        }
    }

    private suspend fun awaitResumed(): Boolean {
        if (foregroundResumed.value) return true
        return withTimeoutOrNull(resumedTimeoutMs) {
            foregroundResumed.first { it }
            true
        } ?: false
    }

    /** VOICE_COMMUNICATION 触发平台 AEC/NS;初始化失败的回退链放在编排层,不放采集实现里。 */
    private fun startCaptureWithFallback() {
        if (audio.startCapture(MediaRecorder.AudioSource.VOICE_COMMUNICATION)) return
        if (audio.startCapture(MediaRecorder.AudioSource.MIC)) return
        throw VoiceCallFailure(VoiceCallError.MicSilent)
    }

    /** 开麦校验:首块必须在限时内到达、非全零、且未被系统静音。 */
    private suspend fun checkMic() {
        val first = withTimeoutOrNull(micCheckTimeoutMs) { audio.captureChunks.receiveCatching().getOrNull() }
            ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
        if (audio.micSilenced.value || first.all { it == 0.toShort() }) {
            throw VoiceCallFailure(VoiceCallError.MicSilent)
        }
        _micLevel.value = levelOf(first)
    }

    /** 提示音直接写播放轨,不进播放队列、不计电平。 */
    private suspend fun playTone() {
        val tone = VoiceCallTone.connectTonePcm()
        try {
            audio.writePlayback(tone, tone.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw VoiceCallFailure(VoiceCallError.Playback)
        }
    }

    private fun greetOnce(realtime: CallRealtimeSession) {
        if (greeted || greeting.isBlank()) return
        if (!realtime.sendGreeting(greeting)) throw VoiceCallFailure(VoiceCallError.Provider)
        echoWindow.append(greeting) // 问候语也是助手已说内容,进回声窗
        greeted = true
    }

    private suspend fun runActive(realtime: CallRealtimeSession) {
        val queue = CallPlaybackQueue()
        try {
            coroutineScope {
                launch { writeLoop(queue) }
                launch { pumpMic(realtime) }
                launch {
                    delay(timeLimitMs)
                    end(EndReason.TimeLimit)
                }
                // 系统通话(来电/去电)会顶掉我们的通话:通话中轮询音频模式,进来就以「来电」收场
                launch {
                    while (true) {
                        delay(PHONE_CALL_POLL_MS)
                        if (audio.isTelephonyBusy()) {
                            end(EndReason.IncomingCall)
                            return@launch
                        }
                    }
                }
                readEvents(realtime, queue)
            }
        } finally {
            queue.close()
        }
    }

    private suspend fun readEvents(realtime: CallRealtimeSession, queue: CallPlaybackQueue) {
        val assistantText = StringBuilder()
        fun flushAssistantText(final: Boolean) {
            if (assistantText.isEmpty()) return
            val text = assistantText.toString()
            assistantText.clear()
            echoWindow.append(text)
            onAssistantTranscript(text, final)
        }
        realtime.events.collect { event ->
            when (event) {
                is VolcRealtimeEvent.Audio ->
                    if (queue.offer(event.pcm) == null) throw VoiceCallFailure(VoiceCallError.Playback)
                VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                    queue.invalidate()
                    audio.interruptPlayback()
                    _speakerLevel.value = 0f
                    flushAssistantText(final = false)
                    onBargeIn()
                }
                is VolcRealtimeEvent.UserTranscript -> {
                    onUserTranscript(event.text, event.isFinal)
                    // 先登记当轮 final+complete 转写,之后的 function_call 才有资格过闸
                    if (event.isFinal && event.isCompleteFinal && event.isCurrentTurn && !event.hasUnresolvedPriorTurn) {
                        requestGate.onUserTranscript(event.text)
                    }
                }
                is VolcRealtimeEvent.AssistantTranscript -> {
                    val room = MAX_ASSISTANT_TURN_CHARS - assistantText.length
                    if (room > 0) assistantText.append(event.delta.take(room))
                }
                VolcRealtimeEvent.AssistantTextEnded -> flushAssistantText(final = true)
                is VolcRealtimeEvent.FunctionCall -> handleFunctionCall(realtime, event)
                is VolcRealtimeEvent.Failed -> throw event.error
                VolcRealtimeEvent.Closed -> throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
                else -> Unit
            }
        }
        throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
    }

    private fun handleFunctionCall(realtime: CallRealtimeSession, event: VolcRealtimeEvent.FunctionCall) {
        if (event.name != VolcRealtimeProtocol.MIKA_REQUEST_FUNCTION) return
        val utterance = (event.arguments["utterance"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        when (val decision = requestGate.offer(event.callId, utterance)) {
            is VoiceCallDecision.Accept -> onVoiceRequest(event.callId, decision.text)
            VoiceCallDecision.Reject -> realtime.sendToolResult(event.callId, buildJsonObject {
                put("status", "rejected")
                put("reason", "utterance_not_verified")
                put("next", "Do not act on this request. Only if the user says it again as a new utterance, call mika_request once more with it; the retry must use the new call_id.")
            })
        }
    }

    private suspend fun pumpMic(realtime: CallRealtimeSession) {
        val frameBytes = realtime.inputChunkBytes
        val pacer = CallInputPacer(frameBytes, realtime.inputSampleRate, nowNanos = clockNanos)
        val frame = ByteArray(frameBytes)
        var position = 0
        while (true) {
            val chunk = audio.captureChunks.receiveCatching().getOrNull()
                ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
            _micLevel.value = levelOf(chunk)
            var index = 0
            while (index < chunk.size) {
                val count = minOf(chunk.size - index, (frameBytes - position) / 2)
                for (k in 0 until count) {
                    val sample = chunk[index + k].toInt()
                    frame[position + 2 * k] = (sample and 0xFF).toByte()
                    frame[position + 2 * k + 1] = (sample shr 8 and 0xFF).toByte()
                }
                position += count * 2
                index += count
                if (position == frameBytes) {
                    if (!pacer.send { realtime.sendPcm16(frame) }) {
                        throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
                    }
                    position = 0
                }
            }
        }
    }

    /** barge-in 作废代次后,迟到的旧代次块在写入前被 isCurrent 拦下,绝不入耳。 */
    private suspend fun writeLoop(queue: CallPlaybackQueue) {
        val pcm = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        while (true) {
            val block = queue.take(pcm) ?: break
            try {
                if (queue.isCurrent(block)) {
                    audio.writePlayback(pcm, block.byteCount)
                    _speakerLevel.value = levelOf(pcm, block.byteCount)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw VoiceCallFailure(VoiceCallError.Playback)
            } finally {
                queue.release(block)
            }
        }
    }

    private fun levelOf(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (sample in chunk) sum += sample.toDouble() * sample
        return (sqrt(sum / chunk.size) / LEVEL_FULL_SCALE).coerceIn(0.0, 1.0).toFloat()
    }

    private fun levelOf(pcm: ByteArray, byteCount: Int): Float {
        val samples = byteCount / 2
        if (samples <= 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
            sum += sample.toDouble() * sample
        }
        return (sqrt(sum / samples) / LEVEL_FULL_SCALE).coerceIn(0.0, 1.0).toFloat()
    }

    private class VoiceCallFailure(val error: VoiceCallError) : Exception()

    companion object {
        const val TYPED_CALL_PREFIX = "typed-"
        const val MAX_CALL_DURATION_MS = 10 * 60_000L
        const val RESUMED_TIMEOUT_MS = 3_000L
        const val MIC_CHECK_TIMEOUT_MS = 3_000L
        /** 通话中查系统音频模式的间隔:来电/去电抢走通话音频时尽快收场。 */
        const val PHONE_CALL_POLL_MS = 1_000L
        private const val MAX_ASSISTANT_TURN_CHARS = 1_024
        private const val LEVEL_FULL_SCALE = 8_000.0
    }
}

private fun VolcRealtimeException.Kind.toVoiceCallError(): VoiceCallError = when (this) {
    VolcRealtimeException.Kind.AUTH -> VoiceCallError.Auth
    VolcRealtimeException.Kind.TIMEOUT -> VoiceCallError.ConnectTimeout
    VolcRealtimeException.Kind.RATE_LIMITED -> VoiceCallError.RateLimited
    VolcRealtimeException.Kind.NETWORK -> VoiceCallError.Disconnected
    VolcRealtimeException.Kind.PROTOCOL,
    VolcRealtimeException.Kind.PROVIDER,
    VolcRealtimeException.Kind.BACKPRESSURE,
    -> VoiceCallError.Provider
}
