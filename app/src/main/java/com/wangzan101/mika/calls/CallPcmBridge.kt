package com.wangzan101.mika.calls

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Pipes carry 16 kHz input and 24 kHz output, PCM16 LE mono. Nothing is written to storage. */
internal class CallPcmBridge(
    private val capture: InputStream,
    private val playback: OutputStream,
    private val interruptPlayback: suspend () -> Boolean,
) {
    suspend fun run(
        session: CallRealtimeSession,
        greeting: String,
        counters: CallMediaCounters?,
        onTranscript: (String, String) -> Unit,
        onFinalTranscript: (String, String) -> Unit = { _, _ -> },
        playedOutputFrames: suspend () -> Long = { 0L },
        awaitFarewellDrain: suspend (CallFarewellFence) -> Boolean = { false },
        commitFarewell: () -> Boolean = { false },
        sendDtmf: suspend (String) -> CallDtmfResult = { CallDtmfResult.rejected("unavailable", it.length) },
        onStage: (CallVoiceStage) -> Unit = {},
        /** 拨号键以外的工具交给电话前台([PhoneDesk.execute]);没有前台时一律 unsupported。 */
        desk: (suspend (String, JsonObject) -> JsonObject)? = null,
    ): Unit = coroutineScope {
        var stage = CallVoiceStage.Listening
        val playbackQueue = CallPlaybackQueue()
        val writerLock = Mutex()
        val assistantText = StringBuilder()
        val farewell = CallFarewellPlayback()
        val finished = CompletableDeferred<Unit>()
        var toolJob: Job? = null
        var farewellWaiter: Job? = null
        fun cancelFarewellWaiter() { farewellWaiter?.cancel(); farewellWaiter = null }
        fun checkFarewell() {
            if (toolJob?.isActive == true) return
            val fence = farewell.takeFence() ?: return
            farewellWaiter = launch {
                if (awaitFarewellDrain(fence) && commitDrainedFarewell(commitFarewell)) finished.complete(Unit)
            }
        }

        fun flushAssistantText(completed: Boolean = false) {
            if (assistantText.isNotBlank()) {
                val text = assistantText.toString()
                onTranscript("assistant", text)
                if (completed) onFinalTranscript("assistant", text)
            }
            assistantText.clear()
        }
        // The separate writer leaves model event collection responsive when a pipe is full.
        val output = launch(Dispatchers.IO) {
            val pcm = ByteArray(CallPlaybackQueue.WRITE_BYTES)
            while (true) {
                val block = playbackQueue.take(pcm) ?: break
                try {
                    writerLock.withLock {
                        if (playbackQueue.isCurrent(block)) {
                            callPipeIo { playback.write(pcm, 0, block.byteCount) }
                            counters?.outputWritten(block.byteCount)
                        }
                    }
                } finally {
                    playbackQueue.release(block)
                }
            }
        }
        try {
            if (!session.setMuted(false) || (greeting.isNotBlank() && !session.sendGreeting(greeting))) {
                throw CallMediaFailure("语音服务未接受本次通话的开场白")
            }
            val input = launch(Dispatchers.IO) {
                val inputPacer = CallInputPacer(session.inputChunkBytes, session.inputSampleRate)
                val frame = ByteArray(session.inputChunkBytes)
                var position = 0
                while (true) {
                    val count = callPipeIo { capture.read(frame, position, frame.size - position) }
                    if (count < 0) throw CallMediaFailure("运营商音频输入已断开")
                    if (count == 0) continue
                    position += count
                    if (position == frame.size) {
                        if (!inputPacer.send { session.sendPcm16(frame) }) throw CallMediaFailure("语音服务音频输入已中断")
                        counters?.inputAccepted(frame, System.nanoTime())
                        position = 0
                    }
                }
            }
            val eventReader = launch {
                try {
                    session.events.collect { event ->
                        counters?.eventReceived(event)
                        CallVoiceStage.after(stage, event).takeIf { it != stage }?.let { stage = it; onStage(it) }
                        when (event) {
                            is VolcRealtimeEvent.Audio -> {
                                val queuedBytes = playbackQueue.offer(event.pcm)
                                if (queuedBytes == null) {
                                    counters?.outputRejected()
                                    throw CallMediaFailure("通话待播音频积压超过安全上限，已交还系统")
                                }
                                cancelFarewellWaiter()
                                farewell.audio(event.pcm.size)
                                counters?.outputQueued(queuedBytes)
                            }
                            VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                                cancelFarewellWaiter()
                                farewell.cancelCandidate()
                                if (farewell.hasPlaybackSinceFlush) {
                                    playbackQueue.invalidate()
                                    val truncation = session.playbackTruncation?.takeIf { it.hasAudio }
                                    val heardFrames = if (truncation != null) playedOutputFrames() else 0L
                                    // First release any writer blocked on a full pipe. Flush again under
                                    // the writer lock to remove the last chunk that raced the first flush.
                                    val cleared = interruptPlayback() && withTimeoutOrNull(750) {
                                        writerLock.withLock { interruptPlayback() }
                                    } == true
                                    if (!cleared) throw CallMediaFailure("通话音频无法及时停止，已交还系统")
                                    farewell.afterPairedFlush()
                                    if (truncation?.interrupt(heardFrames) == false) {
                                        throw CallMediaFailure("无法同步已播放的电话音频，已交还系统")
                                    }
                                }
                                // Some providers report a second cancellation after user speech starts.
                                // Without newly accepted PCM, another flush would only reset the route.
                                flushAssistantText()
                            }
                            is VolcRealtimeEvent.UserTranscript -> {
                                if (event.isCurrentTurn) cancelFarewellWaiter()
                                if (event.isFinal) {
                                    if (event.isCurrentTurn) farewell.callerFinal(event.text, event.isCompleteFinal && !event.hasUnresolvedPriorTurn)
                                    onTranscript("caller", event.text)
                                    onFinalTranscript("caller", event.text)
                                } else if (event.isCurrentTurn) farewell.cancelCandidate()
                            }
                            VolcRealtimeEvent.AssistantAudioStarted -> {
                                cancelFarewellWaiter()
                                farewell.audioStarted()
                            }
                            VolcRealtimeEvent.AssistantAudioEnded -> {
                                farewell.audioEnded()
                                checkFarewell()
                            }
                            is VolcRealtimeEvent.AssistantTranscript -> {
                                val room = CallHistory.MAX_LINE_LENGTH - assistantText.length
                                if (room > 0) assistantText.append(event.delta.take(room))
                            }
                            VolcRealtimeEvent.AssistantTextEnded -> {
                                farewell.assistantFinal(assistantText.toString())
                                flushAssistantText(completed = true)
                                checkFarewell()
                            }
                            is VolcRealtimeEvent.FunctionCall -> {
                                // Tool execution must not block the microphone or incoming audio/events.
                                // A function request invalidates any pending natural-hangup candidate.
                                cancelFarewellWaiter()
                                farewell.cancelCandidate()
                                if (callToolRoute(event.name) == CallToolRoute.Desk) {
                                    // 前台工具立刻返回(请示主人不等回复),也放在旁边跑,不挡音频
                                    launch {
                                        val result = try {
                                            desk?.invoke(event.name, event.arguments) ?: buildJsonObject { put("status", "unsupported") }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { buildJsonObject { put("status", "failed") } }
                                        if (!session.sendToolResult(event.callId, result)) throw CallMediaFailure("电话前台结果无法回传，已交还系统")
                                    }
                                    return@collect
                                }
                                val digits = event.arguments["digits"]?.jsonPrimitive?.content.orEmpty()
                                fun reply(result: CallDtmfResult) {
                                    if (!session.sendToolResult(event.callId, buildJsonObject {
                                        put("status", result.status)
                                        put("sent_digits", result.sentDigits)
                                        put("requested_digits", result.requestedDigits)
                                        put("remote_confirmed", false)
                                        put("next", "Wait for the next phone prompt; never retry uncertain or partial input automatically.")
                                    })) throw CallMediaFailure("电话按键结果无法回传，已交还系统")
                                }
                                if (digits.isEmpty() || digits.length > 32 ||
                                    digits.any { it !in "0123456789*#" }) {
                                    reply(CallDtmfResult.rejected("invalid_digits"))
                                } else if (toolJob?.isActive == true) {
                                    reply(CallDtmfResult.rejected("busy", digits.length))
                                } else {
                                    toolJob = launch {
                                        val result = try { sendDtmf(digits) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { CallDtmfResult.rejected("execution_uncertain", digits.length) }
                                        // No raw entered identifiers are copied into transcripts or diagnostics.
                                        reply(result)
                                        farewell.cancelCandidate()
                                    }
                                }
                            }
                            is VolcRealtimeEvent.Failed -> throw event.error
                            VolcRealtimeEvent.Closed -> throw CallMediaFailure("实时语音会话已结束，电话由系统处理")
                            else -> Unit
                        }
                    }
                    throw CallMediaFailure("实时语音连接已断开，电话由系统处理")
                } finally {
                    input.cancel()
                    flushAssistantText()
                }
            }
            try {
                // Keep the event reader active until the waiter actually issues the hangup.
                finished.await()
                eventReader.cancelAndJoin()
            } finally {
                cancelFarewellWaiter()
                toolJob?.cancel()
                eventReader.cancel()
            }
        } finally {
            // Closing both FDs is what releases a blocking read or write in a cancelled child.
            playbackQueue.close()
            output.cancel()
            runCatching { capture.close() }
            runCatching { playback.close() }
        }
    }
}

/** 通话里模型调的工具交给谁:拨号键由桥自己按,其余(请示主人、编造或不属于电话的名字)都交给电话前台。 */
internal enum class CallToolRoute { Dtmf, Desk }

internal fun callToolRoute(name: String): CallToolRoute =
    if (name == VolcRealtimeProtocol.DTMF_FUNCTION) CallToolRoute.Dtmf else CallToolRoute.Desk

/** No suspension between checking for cancellation and committing on the call dispatcher. */
internal suspend fun commitDrainedFarewell(commit: () -> Boolean): Boolean {
    currentCoroutineContext().ensureActive()
    return commit()
}

/** Bounded by time at 24 kHz PCM16 mono, independent of cloud packet boundaries. */
internal class CallPlaybackQueue(capacityBytes: Int = MAX_PENDING_BYTES) {
    data class Block(val generation: Int, val byteCount: Int)

    private val lock = Any()
    private val pcm = ByteArray(capacityBytes)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var head = 0
    private var pending = 0
    private var writing = 0
    @Volatile private var generation = 0
    private var closed = false

    init { require(capacityBytes > 0 && capacityBytes % 2 == 0) }

    /** Returns occupied bytes, including the in-flight block; null means the packet did not fit. */
    fun offer(bytes: ByteArray): Int? = synchronized(lock) {
        if (closed || bytes.size % 2 != 0 || bytes.size > pcm.size - pending - writing) return null
        if (bytes.isEmpty()) return pending + writing
        val tail = (head + pending) % pcm.size
        val first = minOf(pcm.size - tail, bytes.size)
        bytes.copyInto(pcm, tail, 0, first)
        if (first < bytes.size) bytes.copyInto(pcm, 0, first)
        pending += bytes.size
        signal.trySend(Unit)
        pending + writing
    }

    /** One writer, at most one 20 ms block outside the ring. */
    suspend fun take(into: ByteArray): Block? {
        require(into.isNotEmpty() && into.size % 2 == 0)
        while (true) {
            synchronized(lock) {
                if (closed) return null
                check(writing == 0)
                if (pending > 0) {
                    val count = minOf(pending, into.size, WRITE_BYTES)
                    val first = minOf(pcm.size - head, count)
                    pcm.copyInto(into, 0, head, head + first)
                    if (first < count) pcm.copyInto(into, first, 0, count - first)
                    head = (head + count) % pcm.size
                    pending -= count
                    writing = count
                    return Block(generation, count)
                }
            }
            if (signal.receiveCatching().isClosed) return null
        }
    }

    fun isCurrent(block: Block): Boolean = generation == block.generation

    fun release(block: Block) = synchronized(lock) {
        if (isCurrent(block)) writing = 0
    }

    fun invalidate() = synchronized(lock) {
        generation++
        head = 0
        pending = 0
        writing = 0
    }

    fun close() = synchronized(lock) {
        closed = true
        invalidate()
        signal.close()
        Unit
    }

    companion object {
        private const val BYTES_PER_SECOND = VolcRealtimeClient.OUTPUT_SAMPLE_RATE * 2
        const val WRITE_BYTES = BYTES_PER_SECOND / 50
        // 云端按 24kHz 实时生成，播放必须跟上。个别手机(见 #695)的电话 AudioTrack 会反复死/重建、
        // 播放线程欠载，消费阶段性落后于生成；8 秒太小，会把这可恢复的设备抖动升级成「积压超限」。
        // 放宽到 20 秒吸收累计缺口，同时保留硬上限：真卡死仍会交还系统，不会让音频无限延迟。
        const val MAX_PENDING_BYTES = BYTES_PER_SECOND * 20
    }
}

internal fun callAudioHasStarted(greeting: String, downBytes: Long, upBytes: Long): Boolean =
    downBytes > 0 && (greeting.isBlank() || upBytes > 0)

/** Closing a cancelled worker's pipe is expected; its IOException must not replace the cancellation. */
internal suspend inline fun <T> callPipeIo(action: () -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val value = try {
        action()
    } catch (closed: IOException) {
        // Still surface real I/O faults while the call is active.
        context.ensureActive()
        throw closed
    }
    // A concurrently closed stream may report EOF instead of throwing.
    context.ensureActive()
    return value
}

internal class CallMediaFailure(message: String) : Exception(message)
