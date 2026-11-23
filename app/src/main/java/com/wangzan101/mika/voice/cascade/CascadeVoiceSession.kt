package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What one utterance turned into. */
private sealed interface Heard {
    data class Text(val text: String) : Heard
    data class Failed(val error: Throwable?) : Heard
}

/**
 * A standard (listen → think → speak) call behind the same seam as a realtime voice model, so the phone bridge and
 * the in-app voice call drive it unchanged.
 *
 * - Listen: incoming 16 kHz PCM is cut into utterances by loudness ([UtteranceDetector]); each utterance is fed to
 *   its own speech-to-text session through a [FeedRecorder] and closed after [silenceMs] of quiet.
 * - Think: finished utterances, in order, go to the [brain]. Utterances that finish while the user is already talking
 *   again are held and joined with the next one, so one thought gets one reply.
 * - Speak: reply text is cut at sentence marks and commas, each piece is synthesized and sent as 24 kHz PCM, paced
 *   so at most [aheadMs] of audio is queued ahead of real time.
 *
 * When the other side talks over the assistant ([bargeIn]) the reply stops; without barge-in, input is ignored while
 * the assistant is audible.
 */
class CascadeVoiceSession internal constructor(
    private val brain: DeskBrain,
    private val listen: (FeedRecorder) -> SpeechSession,
    private val speak: suspend (String) -> PcmAudioOut,
    silenceMs: Int,
    private val bargeIn: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val aheadMs: Long = 5_000,
    private val chunkMs: Int = 100,
    private val maxFailures: Int = 3,
) : CallRealtimeSession {
    /** Synthesized speech: 16-bit mono PCM at [sampleRate]. */
    class PcmAudioOut(val pcm: ByteArray, val sampleRate: Int)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val out = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
    override val events: Flow<VolcRealtimeEvent> = out.receiveAsFlow()
    override val inputSampleRate: Int get() = INPUT_RATE
    override val outputSampleRate: Int get() = OUTPUT_RATE
    override val inputChunkBytes: Int get() = INPUT_RATE / 50 * 2

    private val lock = Any()
    private val detector = UtteranceDetector(silenceMs, INPUT_RATE)
    @Volatile private var closed = false
    @Volatile private var muted = false

    // Listening state, guarded by [lock].
    private var recorder: FeedRecorder? = null
    private var speech: SpeechSession? = null
    private var turnSerial = 0
    private val turns = Channel<Pair<Int, CompletableDeferred<Heard>>>(Channel.UNLIMITED)
    private var held = ""
    /** 开了头、还没想完的用户话数:有这样的话在,说明用户在说或刚说完还没回。 */
    private var unheard = 0

    // Speaking state.
    @Volatile private var reply: Job? = null
    // Keep cancelled replies until they finish, even if a replacement is itself interrupted while waiting.
    private val replyJobs = mutableSetOf<Job>() // guarded by lock
    // 等着说的后台消息及各自的收尾回调:手上的回复说完、用户也没在说时才轮到;排着的用户插话也不丢。guarded by lock
    private val notices = ArrayDeque<Pair<String, (Boolean) -> Unit>>()
    @Volatile private var audibleUntil = 0L
    private val failures = AtomicInteger(0)
    private val callIds = AtomicInteger(0)
    /** Call ids stay unique across reconnects (the in-app call never accepts a replayed id). */
    private val sessionTag = java.util.UUID.randomUUID().toString().take(8)
    private val pendingTools = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val orphanedTools = ConcurrentHashMap<String, String>()

    init {
        scope.launch { thinkLoop() }
    }

    override fun sendPcm16(bytes: ByteArray, byteCount: Int): Boolean {
        if (closed) return false
        val samples = Pcm16.toSamples(bytes, byteCount)
        synchronized(lock) {
            if (muted) return true
            val audible = isAudible()
            if (audible && !bargeIn) {
                if (detector.inSpeech) endUtterance(cancel = true)
                return true
            }
            var at = 0
            while (at < samples.size) {
                val end = minOf(samples.size, at + FRAME)
                val frame = samples.copyOfRange(at, end)
                at = end
                when (detector.push(frame, strict = audible)) {
                    UtteranceDetector.Signal.Start -> startUtterance(detector.takePreroll())
                    UtteranceDetector.Signal.Speech -> recorder?.feed(frame)
                    UtteranceDetector.Signal.End -> endUtterance(cancel = false)
                    UtteranceDetector.Signal.None -> Unit
                }
            }
        }
        return true
    }

    override fun sendGreeting(text: String): Boolean {
        if (closed || text.isBlank()) return false
        startReply {
            brain.remember(text)
            it.say(text)
        }
        return true
    }

    override fun setMuted(muted: Boolean): Boolean {
        if (closed) return false
        synchronized(lock) {
            this.muted = muted
            if (muted && detector.inSpeech) endUtterance(cancel = true)
            if (muted) detector.reset()
        }
        return true
    }

    override fun sendToolResult(callId: String, result: JsonObject): Boolean {
        if (closed) return false
        pendingTools.remove(callId)?.let { return it.complete(result) }
        val name = orphanedTools.remove(callId) ?: return false
        // The reply that asked has been talked over; answer the result as a turn of its own.
        startReply { brain.lateResult(name, result, it) }
        return true
    }

    override fun sendNotice(text: String, ended: (spoken: Boolean) -> Unit): Boolean {
        if (closed || text.isBlank()) return false
        synchronized(lock) {
            notices.addLast(text to ended)
            nextNotice()
        }
        return true
    }

    /** 标准通话的汇报本来就是记进模型历史、由模型自己回的一轮(见 [DeskBrain.notice]),不是照念:提示走同一条路。 */
    override fun sendCue(text: String, ended: (spoken: Boolean) -> Unit): Boolean = sendNotice(text, ended)

    override fun sendUserText(text: String): Boolean {
        if (closed || text.isBlank()) return false
        startReply { brain.reply(text, it) }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        val unspoken = synchronized(lock) {
            speech?.cancel()
            recorder?.cancel()
            speech = null
            recorder = null
            notices.toList().also { notices.clear() }
        }
        scope.cancel()
        // 没轮到说的消息也要有交代:算没说出去,调用方改走别的路
        unspoken.forEach { (_, ended) -> ended(false) }
        pendingTools.values.forEach { it.cancel() }
        out.trySend(VolcRealtimeEvent.Closed)
        out.close()
    }

    // ---- listen ----

    private fun startUtterance(preroll: ShortArray) {
        interrupt()
        emit(VolcRealtimeEvent.UserSpeaking)
        val feed = FeedRecorder()
        val heard = CompletableDeferred<Heard>()
        val session = try {
            listen(feed)
        } catch (e: Exception) {
            heard.complete(Heard.Failed(e))
            null
        }
        recorder = feed
        speech = session
        feed.feed(preroll)
        unheard++
        turns.trySend(++turnSerial to heard)
        if (session != null) scope.launch {
            try {
                session.events.collect { event ->
                    when (event) {
                        is SpeechEvent.Final -> heard.complete(Heard.Text(event.text.trim()))
                        is SpeechEvent.Failed -> heard.complete(Heard.Failed(failureOf(event.why)))
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                heard.complete(Heard.Failed(e))
            }
            heard.complete(Heard.Text(""))
        }
    }

    private fun failureOf(why: SpeechFailure): Throwable? = when (why) {
        is SpeechFailure.Provider -> why.error
        is SpeechFailure.Unavailable -> VoiceModelError.IncompleteConfig("Speech recognition cannot listen to a call")
        else -> null
    }

    private fun endUtterance(cancel: Boolean) {
        val session = speech
        speech = null
        recorder = null
        if (cancel) {
            detector.reset()
            session?.cancel()
        } else {
            session?.release()
            emit(VolcRealtimeEvent.UserSpeechEnded)
        }
    }

    // ---- think ----

    private suspend fun thinkLoop() {
        for ((serial, heard) in turns) {
            val result = heard.await()
            val current = synchronized(lock) { serial == turnSerial && !detector.inSpeech }
            val stop = try {
                think(result, current)
            } finally {
                // 这句想完了(回复已经起头,或没听清):用户这会儿不在说话的话,轮到排着的消息
                synchronized(lock) {
                    unheard--
                    nextNotice()
                }
            }
            if (stop) return
        }
    }

    /** Answers one finished utterance; true when a failure ends the call. */
    private fun think(result: Heard, current: Boolean): Boolean {
        when (result) {
            is Heard.Failed -> {
                emit(VolcRealtimeEvent.UserTranscriptionFailed)
                return fatal(result.error)
            }
            is Heard.Text -> {
                failures.set(0)
                val text = listOf(held, result.text).filter { it.isNotBlank() }.joinToString(" ")
                when {
                    text.isBlank() -> emit(VolcRealtimeEvent.UserTranscriptionEmpty)
                    !current -> held = text
                    else -> {
                        held = ""
                        emit(VolcRealtimeEvent.UserTranscript(text, isFinal = true, isCompleteFinal = true, isCurrentTurn = true))
                        startReply { brain.reply(text, it) }
                    }
                }
                return false
            }
        }
    }

    /** Records one failure; true (and the call is failed) when it cannot recover. */
    private fun fatal(error: Throwable?): Boolean {
        val category = error?.let { VoiceModelError.from(it).category }
        val kind = when {
            category == VoiceErrorCategory.AUTH_FAILED || category == VoiceErrorCategory.MISSING_CREDENTIALS ||
                category == VoiceErrorCategory.INCOMPLETE_CONFIG || category == VoiceErrorCategory.UNSUPPORTED ->
                VolcRealtimeException.Kind.AUTH
            failures.incrementAndGet() >= maxFailures -> when (category) {
                VoiceErrorCategory.RATE_LIMITED -> VolcRealtimeException.Kind.RATE_LIMITED
                VoiceErrorCategory.TIMEOUT -> VolcRealtimeException.Kind.TIMEOUT
                else -> VolcRealtimeException.Kind.NETWORK
            }
            else -> return false
        }
        emit(VolcRealtimeEvent.Failed(VolcRealtimeException(kind)))
        return true
    }

    // ---- speak ----

    private fun isAudible(): Boolean = audibleUntil > clock()

    /** Stops the current reply; the bridge is told so it drops audio already queued. */
    private fun interrupt() {
        val job = reply ?: return
        reply = null
        job.cancel()
        if (isAudible()) emit(VolcRealtimeEvent.AssistantInterrupted)
        audibleUntil = 0
    }

    /**
     * 没在回复、用户也没在说(没有说到一半或刚说完还没回的话)时说下一条后台消息;
     * 不然就等(回复结束、用户这句想完时会再来叫)。Called under [lock].
     */
    private fun nextNotice() {
        if (closed || reply?.isActive == true || detector.inSpeech || unheard > 0) return
        val (text, ended) = notices.removeFirstOrNull() ?: return
        startReply(interruptCurrent = false, ended = ended) { brain.notice(text, it) }
    }

    /** [ended] 在这段回复收尾时回一次:完整说完为 true,被打断、出错或通话断了为 false。 */
    private fun startReply(
        interruptCurrent: Boolean = true,
        ended: ((Boolean) -> Unit)? = null,
        block: suspend (ReplyContext) -> Unit,
    ) {
        synchronized(lock) {
            // The new reply waits for the old one to unwind (it closes its tool calls in the brain's history).
            if (interruptCurrent) interrupt()
            val previous = replyJobs.toList()
            var spoken = false
            val next = scope.launch {
                previous.forEach { it.join() }
                try {
                    speakReply(block)
                    // 要交代说没说完的,等已经排出去的声音真的播完;这段里被插话打断照样算没说完
                    if (ended != null) while (isAudible()) delay(chunkMs.toLong())
                    spoken = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fatal(e)
                }
            }
            replyJobs += next
            next.invokeOnCompletion { cause ->
                synchronized(lock) {
                    replyJobs.remove(next)
                    nextNotice()
                }
                ended?.invoke(spoken && cause == null)
            }
            reply = next
        }
    }

    private suspend fun speakReply(block: suspend (ReplyContext) -> Unit) = coroutineScope {
        val pieces = Channel<String>(Channel.UNLIMITED)
        val splitter = SentenceSplitter()
        val player = launch { play(pieces) }
        val ctx = object : ReplyContext {
            override suspend fun say(delta: String) {
                splitter.push(delta).forEach { pieces.send(it) }
            }

            override suspend fun callTool(name: String, arguments: JsonObject): JsonObject? {
                val id = "cascade_${sessionTag}_${callIds.incrementAndGet()}"
                val result = CompletableDeferred<JsonObject>()
                pendingTools[id] = result
                emit(VolcRealtimeEvent.FunctionCall(id, name, arguments))
                return try {
                    result.await()
                } catch (e: CancellationException) {
                    if (pendingTools.remove(id) != null) orphanedTools[id] = name
                    throw e
                }
            }
        }
        try {
            block(ctx)
            splitter.flush()?.let { pieces.send(it) }
        } finally {
            pieces.close()
        }
        player.join()
    }

    private suspend fun play(pieces: Channel<String>) {
        var started = false
        for (piece in pieces) {
            val audio = try {
                speak(piece)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (fatal(e)) return
                continue
            }
            failures.set(0)
            val pcm = toOutput(audio)
            if (pcm.isEmpty()) continue
            if (!started) {
                started = true
                emit(VolcRealtimeEvent.AssistantAudioStarted)
            }
            emit(VolcRealtimeEvent.AssistantTranscript(piece))
            val chunk = OUTPUT_RATE * 2 * chunkMs / 1000
            var at = 0
            while (at < pcm.size) {
                while (audibleUntil - clock() > aheadMs) delay(chunkMs.toLong())
                val end = minOf(pcm.size, at + chunk)
                val bytes = pcm.copyOfRange(at, end)
                at = end
                val now = clock()
                audibleUntil = maxOf(audibleUntil, now) + bytes.size * 1000L / (OUTPUT_RATE * 2)
                emit(VolcRealtimeEvent.Audio(bytes))
            }
        }
        if (started) {
            emit(VolcRealtimeEvent.AssistantTextEnded)
            emit(VolcRealtimeEvent.AssistantAudioEnded)
        }
    }

    private fun toOutput(audio: PcmAudioOut): ByteArray {
        if (audio.sampleRate == OUTPUT_RATE) return audio.pcm
        val samples = Pcm16.toSamples(audio.pcm)
        return Pcm16.toBytes(Pcm16.resample(samples, audio.sampleRate, OUTPUT_RATE))
    }

    private fun emit(event: VolcRealtimeEvent) {
        if (!closed) out.trySend(event)
    }

    companion object {
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        private const val FRAME = INPUT_RATE / 50
    }
}
