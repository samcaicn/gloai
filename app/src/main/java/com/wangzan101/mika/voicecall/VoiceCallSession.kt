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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 通话状态机:显式枚举,任何时刻必居其一,不用布尔拼接。 */
sealed interface VoiceCallState {
    data object Connecting : VoiceCallState
    data object MicCheck : VoiceCallState
    data class Active(val startedAt: Long) : VoiceCallState
    /** [idleMinutes] 只对 [EndReason.Idle] 有意义:静了几分钟挂的。 */
    data class Ended(val reason: EndReason, val idleMinutes: Int = 0) : VoiceCallState
    data class Failed(val error: VoiceCallError, val attempt: Int) : VoiceCallState
}

/**
 * 接通后这一刻轮到谁:听(等你开口/你在说)、想(你说完了,等回答)、说(Mika 的声音正在播)。
 * 只在 [VoiceCallState.Active] 里有意义;通话页据此给出「在听你说 / 在想 / Mika 在说」。
 */
enum class VoiceCallTurn { Listening, Thinking, Speaking }

/** [Idle]:通话里好一阵没有新内容(谁都没说话、没打字、任务也没进展),到了设置的时长自动挂断。 */
enum class EndReason { Hangup, Notification, IncomingCall, Idle, Background;

    /** 记进通话记录([com.wangzan101.mika.chat.ConversationCall.endReason])的线上值。 */
    val wire: String get() = when (this) {
        Idle -> com.wangzan101.mika.chat.ConversationCall.END_IDLE
        Hangup -> com.wangzan101.mika.chat.ConversationCall.END_HANGUP
        else -> name.lowercase()
    }
}

enum class VoiceCallError { Auth, ConnectTimeout, Disconnected, RateLimited, Provider, MicSilent, AudioFocus, Playback, Busy }

/**
 * 应用内实时语音通话编排。一次接通的全链:等前台 RESUMED → 电话忙检测 → 音频焦点 → 连接实时会话 →
 * 开麦校验(VOICE_COMMUNICATION 初始化失败在编排层回退 MIC)→ 起播 → 本地两音提示音 →
 * 云端问候语(每会话只播一次)→ Active。
 *
 * Active 后三路并行:事件消费(下行音频进 20 秒上限的 [CallPlaybackQueue];barge-in 代次作废 + 硬件 flush;
 * 前台工具调用凡带用户原话的,先登记当轮转写再过安全闸)、采集上行(20ms 帧绝对 deadline pacing)、静默挂断(没有总时长上限,见 [idleMs])。
 * 用户插话只打断助手正在说的话,不碰后台任务。
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
    /** 模型调的前台工具(带原话的已过安全闸,原话换成放行的当轮转写);装配方执行后用同一 callId 回结果。 */
    private val onDeskCall: (callId: String, name: String, args: JsonObject) -> Unit = { _, _, _ -> },
    /** 打的字会话接不了(实时会话不收文字)时交给装配方直接派单,结果在本地显示。 */
    private val onTypedText: (text: String) -> Unit = {},
    private val onUserTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> },
    private val onAssistantTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> },
    /** 助手这一轮到目前为止的文字(逐段累积),供通话页实时显示;落定仍走 [onAssistantTranscript]。 */
    private val onAssistantDraft: (text: String) -> Unit = {},
    /**
     * 通话里多久没有新内容就挂断;null 不自动挂。新内容:你开口(说到一半也算)、打字、Mika 说话、任务有进展([onActivity])。
     * 到点前 [IDLE_WARNING_MS] 先问一句还在不在:能让模型接着回话的会话交 [idleCue] 由它转述,不能的照念 [idleLine];
     * 这句提醒本身不算新内容。
     */
    private val idleMs: Long? = DEFAULT_IDLE_MS,
    private val idleCue: String = "",
    private val idleLine: String = "",
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
    private val _turn = MutableStateFlow(VoiceCallTurn.Listening)
    val turn: StateFlow<VoiceCallTurn> = _turn
    private val _muted = MutableStateFlow(false)

    /** 静音:麦克风照常采集,上行改送静音帧(保持节奏,服务端只当你没说话),电平归零。跨重试保留。 */
    val muted: StateFlow<Boolean> = _muted

    /** 最近一次真正写进播放轨的云端音频时刻(clockNanos);播放停下一阵后轮回到听。 */
    @Volatile private var lastSpokeNanos = 0L

    /** 最近一次有新内容的时刻(clockNanos);静默计时从这里算。 */
    @Volatile private var lastActivityNanos = 0L

    /** 已经问过「还在吗」、还没等到你或任务的新动静:这期间 Mika 自己说的话不重新计时(那就是在念提醒)。 */
    @Volatile private var idleWarned = false

    private val lock = Any()
    private var runJob: Job? = null
    private var terminal = false
    private var retryScheduled = false
    private var greeted = false

    init {
        startAttempt(1)
    }

    fun hangup() = end(EndReason.Hangup)

    /** 通话外来的新内容(派出去的任务有了进展):静默计时重来。 */
    fun onActivity() = markActivity(fromMika = false)

    private fun markActivity(fromMika: Boolean) {
        if (fromMika && idleWarned) return
        if (!fromMika) idleWarned = false
        lastActivityNanos = clockNanos()
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        if (muted) _micLevel.value = 0f
    }

    /**
     * 回复已在本地显示(打字请求的结果只进转写/面板,云端不会播报):不会再有播放把「在想」带回「在听」,
     * 由装配方显示后告诉会话。正在播报时不动,播完自然回到听。
     */
    fun onLocalReply() {
        _turn.compareAndSet(VoiceCallTurn.Thinking, VoiceCallTurn.Listening)
    }

    /** 此刻接通的会话;没接通时为 null。 */
    @Volatile private var live: CallRealtimeSession? = null

    /**
     * 通话面板的文字输入:打字与说话同路——登记进转写(问),也登记成当轮转写(之后模型据此调工具照样过闸)。
     * 会话能接文字(标准模式)就当一句话交给它回;接不了(实时模式)就把这段字记成用户亲述的原话,
     * 交给 [onTypedText] 直接派单。仅 Active 态受理,空白忽略。
     */
    fun sendUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (_state.value !is VoiceCallState.Active) return
        markActivity(fromMika = false)
        onUserTranscript(clean, true)
        _turn.value = VoiceCallTurn.Thinking
        requestGate.onUserTranscript(clean)
        if (live?.sendUserText(clean) == true) return
        requestGate.acceptTyped(clean)
        onTypedText(clean)
    }

    /**
     * 终态转换:已终态时再调是 no-op(收尾幂等)。唯一例外是 Failed → Ended:
     * 失败后用户不重试、直接离开时通话页负责收场,前台服务据此收工;落了 Ended 后 retry 不再有效。
     */
    fun end(reason: EndReason) {
        val job = synchronized(lock) {
            if (terminal) {
                if (_state.value is VoiceCallState.Failed) _state.value = endedFor(reason)
                return
            }
            terminal = true
            _state.value = endedFor(reason)
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
            _turn.value = VoiceCallTurn.Listening
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
            live = realtime
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
            live = null
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

    /**
     * 开麦校验:限时内要收到一块有声音的音频,且未被系统静音。
     * 刚切进通话模式时不少音频硬件会先给几块全零(预热),所以全零块跳过、只在整个窗口都没声音时才判失败。
     */
    private suspend fun checkMic() {
        val heard = withTimeoutOrNull(micCheckTimeoutMs) {
            var chunk: ShortArray
            do {
                chunk = audio.captureChunks.receiveCatching().getOrNull()
                    ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
                if (audio.micSilenced.value) throw VoiceCallFailure(VoiceCallError.MicSilent)
            } while (chunk.all { it == 0.toShort() })
            chunk
        } ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
        _micLevel.value = levelOf(heard)
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
                launch { watchSpeaking() }
                idleMs?.let { limit -> launch { watchIdle(realtime, limit) } }
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

    /** 静默挂断:到 [IDLE_WARNING_MS] 前问一句,一直没动静到点就挂;短于一分钟的提醒时长按一半算。 */
    private suspend fun watchIdle(realtime: CallRealtimeSession, limit: Long) {
        val warnAfter = (limit - IDLE_WARNING_MS).coerceAtLeast(limit / 2)
        idleWarned = false
        lastActivityNanos = clockNanos()
        while (true) {
            delay(IDLE_POLL_MS)
            val quietMs = (clockNanos() - lastActivityNanos) / 1_000_000L
            if (quietMs >= limit) {
                end(EndReason.Idle)
                return
            }
            if (quietMs >= warnAfter && !idleWarned) {
                idleWarned = true
                if (idleCue.isNotBlank() && realtime.sendCue(idleCue)) continue
                if (idleLine.isNotBlank() && realtime.sendNotice(idleLine)) echoWindow.append(idleLine)
            }
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
        fun listen() {
            _turn.value = VoiceCallTurn.Listening
        }
        realtime.events.collect { event ->
            when (event) {
                is VolcRealtimeEvent.Audio -> {
                    markActivity(fromMika = true)
                    if (queue.offer(event.pcm) == null) throw VoiceCallFailure(VoiceCallError.Playback)
                }
                VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                    if (event == VolcRealtimeEvent.UserSpeaking) markActivity(fromMika = false)
                    // 用户又开口:上一句话授权的调用到此为止
                    if (event == VolcRealtimeEvent.UserSpeaking) requestGate.onUserTurnStart()
                    queue.invalidate()
                    audio.interruptPlayback()
                    _speakerLevel.value = 0f
                    flushAssistantText(final = false)
                    listen()
                }
                is VolcRealtimeEvent.UserTranscript -> {
                    markActivity(fromMika = false)
                    onUserTranscript(event.text, event.isFinal)
                    if (event.isFinal && event.isCurrentTurn) _turn.value = VoiceCallTurn.Thinking
                    // 先登记当轮 final+complete 转写,之后的 function_call 才有资格过闸
                    if (event.isFinal && event.isCompleteFinal && event.isCurrentTurn && !event.hasUnresolvedPriorTurn) {
                        requestGate.onUserTranscript(event.text)
                    }
                }
                is VolcRealtimeEvent.AssistantTranscript -> {
                    markActivity(fromMika = true)
                    val room = MAX_ASSISTANT_TURN_CHARS - assistantText.length
                    if (room > 0) {
                        assistantText.append(event.delta.take(room))
                        onAssistantDraft(assistantText.toString())
                    }
                }
                VolcRealtimeEvent.AssistantTextEnded -> flushAssistantText(final = true)
                VolcRealtimeEvent.UserTranscriptionEmpty, VolcRealtimeEvent.UserTranscriptionFailed -> listen()
                is VolcRealtimeEvent.FunctionCall -> handleFunctionCall(realtime, event)
                is VolcRealtimeEvent.Failed -> throw event.error
                VolcRealtimeEvent.Closed -> throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
                else -> Unit
            }
        }
        throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
    }

    /**
     * 带原话(words)的前台工具先过安全闸,放行的原话核对过落在当轮转写里,换成整句交出去;
     * 不带原话的照样交出去,由前台执行器拒掉(四个工具都要原话)。
     */
    private fun handleFunctionCall(realtime: CallRealtimeSession, event: VolcRealtimeEvent.FunctionCall) {
        if (event.name !in VolcRealtimeProtocol.deskToolNames) return
        val words = event.arguments["words"] ?: return onDeskCall(event.callId, event.name, event.arguments)
        val utterance = (words as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        when (val decision = requestGate.offer(event.callId, utterance)) {
            is VoiceCallDecision.Accept ->
                onDeskCall(event.callId, event.name, JsonObject(event.arguments + ("words" to JsonPrimitive(decision.text))))
            VoiceCallDecision.Reject -> realtime.sendToolResult(event.callId, buildJsonObject {
                put("status", "rejected")
                put("reason", "words_not_verified")
                put("next", "Do not act on this. Only if the user says it again as a new utterance, call the tool once more with their words; the retry must use the new call_id.")
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
            val muted = _muted.value
            _micLevel.value = if (muted) 0f else levelOf(chunk)
            var index = 0
            while (index < chunk.size) {
                val count = minOf(chunk.size - index, (frameBytes - position) / 2)
                for (k in 0 until count) {
                    val sample = if (muted) 0 else chunk[index + k].toInt()
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
                    lastSpokeNanos = clockNanos()
                    markActivity(fromMika = true)
                    _turn.value = VoiceCallTurn.Speaking
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

    /** 播放停下 [SPEAKING_IDLE_MS] 还没新音频,就当这轮说完了,轮回到听。 */
    private suspend fun watchSpeaking() {
        while (true) {
            delay(TURN_POLL_MS)
            if (_turn.value == VoiceCallTurn.Speaking && clockNanos() - lastSpokeNanos >= SPEAKING_IDLE_MS * 1_000_000L) {
                _turn.value = VoiceCallTurn.Listening
                _speakerLevel.value = 0f
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

    private fun endedFor(reason: EndReason) = VoiceCallState.Ended(
        reason,
        idleMinutes = if (reason == EndReason.Idle) idleMs?.let { ((it + 30_000L) / 60_000L).toInt().coerceAtLeast(1) } ?: 0 else 0,
    )

    private class VoiceCallFailure(val error: VoiceCallError) : Exception()

    companion object {
        /** 默认静了 5 分钟挂断。 */
        const val DEFAULT_IDLE_MS = 5 * 60_000L
        /** 挂断前多久先问一句还在不在。 */
        const val IDLE_WARNING_MS = 30_000L
        const val IDLE_POLL_MS = 1_000L
        const val RESUMED_TIMEOUT_MS = 3_000L
        const val MIC_CHECK_TIMEOUT_MS = 3_000L
        /** 通话中查系统音频模式的间隔:来电/去电抢走通话音频时尽快收场。 */
        const val PHONE_CALL_POLL_MS = 1_000L
        /** 播放停了多久算这轮说完。 */
        const val SPEAKING_IDLE_MS = 700L
        const val TURN_POLL_MS = 100L
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
