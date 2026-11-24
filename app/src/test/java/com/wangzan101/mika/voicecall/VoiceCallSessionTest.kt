package com.wangzan101.mika.voicecall

import android.media.MediaRecorder
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallSessionTest {

    private class FakeAudio : VoiceCallAudio {
        override val micSilenced = MutableStateFlow(false)
        private val capture = Channel<ShortArray>(100)
        override val captureChunks: ReceiveChannel<ShortArray> get() = capture
        override var onFocusLost: (() -> Unit)? = null
        var telephonyBusy = false
        var focusGranted = true
        val attemptedSources = mutableListOf<Int>()
        val failingSources = mutableSetOf<Int>()
        var failPlaybackStart = false
        var writeFailure: IOException? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val written = mutableListOf<ByteArray>()
        var interruptCount = 0
        var captureStops = 0
        var playbackStarts = 0
        var playbackStops = 0
        var focusAbandons = 0

        fun offerChunk(chunk: ShortArray = ShortArray(320) { 500 }) {
            check(capture.trySend(chunk).isSuccess)
        }

        override fun isTelephonyBusy() = telephonyBusy
        override fun requestCallAudio() = focusGranted
        override fun abandonCallAudio() { focusAbandons++ }
        override fun startCapture(inputSource: Int): Boolean {
            attemptedSources += inputSource
            return inputSource !in failingSources
        }
        override fun stopCapture() { captureStops++ }
        override fun startPlayback(): Boolean {
            if (failPlaybackStart) return false
            playbackStarts++
            return true
        }
        override suspend fun writePlayback(pcm: ByteArray, byteCount: Int) {
            writeFailure?.let { throw it }
            writeGate?.await()
            written += pcm.copyOf(byteCount)
        }
        override fun interruptPlayback() { interruptCount++ }
        override fun stopPlayback() { playbackStops++ }
    }

    private class FakeRealtime : CallRealtimeSession {
        private val incoming = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
        override val events: Flow<VolcRealtimeEvent> = flow { for (event in incoming) emit(event) }
        val sentPcm = mutableListOf<ByteArray>()
        val greetings = mutableListOf<String>()
        val toolResults = mutableListOf<Pair<String, JsonObject>>()
        val mutes = mutableListOf<Boolean>()
        var failSendPcm = false
        var closed = false

        fun emit(event: VolcRealtimeEvent) { check(incoming.trySend(event).isSuccess) }
        override fun sendPcm16(bytes: ByteArray, byteCount: Int): Boolean {
            if (failSendPcm) return false
            sentPcm += bytes.copyOf(byteCount)
            return true
        }
        override fun sendGreeting(text: String): Boolean { greetings += text; return true }
        override fun setMuted(muted: Boolean): Boolean { mutes += muted; return true }
        override fun sendToolResult(callId: String, result: JsonObject): Boolean {
            toolResults += callId to result
            return true
        }
        var acceptsText = false
        val texts = mutableListOf<String>()
        /** 能让模型接着回话的提示(标准模式、能加 system 项的实时协议)收不收;不收时会话改成照念 [notices]。 */
        var acceptsCue = true
        val cues = mutableListOf<String>()
        val notices = mutableListOf<String>()
        override fun sendCue(text: String, ended: (spoken: Boolean) -> Unit): Boolean {
            if (!acceptsCue) return false
            cues += text
            return true
        }
        override fun sendNotice(text: String, ended: (spoken: Boolean) -> Unit): Boolean {
            notices += text
            return true
        }
        override fun sendUserText(text: String): Boolean {
            if (!acceptsText) return false
            texts += text
            return true
        }
        override fun close() { closed = true }
    }

    private class Harness(
        scope: CoroutineScope,
        greeting: String = GREETING,
        idleMs: Long? = 600_000L,
        connect: suspend (Int) -> CallRealtimeSession,
        onDeskCall: (String, String, JsonObject) -> Unit = { _, _, _ -> },
        onTypedText: (String) -> Unit = {},
        clockNanos: () -> Long,
        nowMillis: () -> Long,
        echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
        requestGate: VoiceCallRequest = VoiceCallRequest(echoWindow),
    ) {
        val audio = FakeAudio()
        val rt = FakeRealtime()
        val foreground = MutableStateFlow(true)
        val userTranscripts = mutableListOf<Pair<String, Boolean>>()
        val assistantTranscripts = mutableListOf<Pair<String, Boolean>>()
        val assistantDrafts = mutableListOf<String>()
        val session = VoiceCallSession(
            scope = scope,
            audio = audio,
            foregroundResumed = foreground,
            connect = connect,
            greeting = greeting,
            echoWindow = echoWindow,
            requestGate = requestGate,
            onDeskCall = onDeskCall,
            onTypedText = onTypedText,
            onUserTranscript = { text, isFinal -> userTranscripts += text to isFinal },
            onAssistantTranscript = { text, isFinal -> assistantTranscripts += text to isFinal },
            onAssistantDraft = { assistantDrafts += it },
            idleMs = idleMs,
            idleCue = IDLE_CUE,
            idleLine = IDLE_LINE,
            clockNanos = clockNanos,
            nowMillis = nowMillis,
        )
    }

    private fun harness(
        scope: CoroutineScope,
        greeting: String = GREETING,
        idleMs: Long? = 600_000L,
        connect: suspend (Int) -> CallRealtimeSession,
        onDeskCall: (String, String, JsonObject) -> Unit = { _, _, _ -> },
        onTypedText: (String) -> Unit = {},
        clockNanos: () -> Long,
        nowMillis: () -> Long,
    ) = Harness(scope, greeting, idleMs, connect, onDeskCall, onTypedText, clockNanos, nowMillis)

    private fun reachActive(h: Harness, scope: kotlinx.coroutines.test.TestScope) {
        h.audio.offerChunk()
        scope.runCurrent()
        assertTrue("expected Active but was ${h.session.state.value}", h.session.state.value is VoiceCallState.Active)
    }

    private fun requestCall(callId: String, utterance: String) = VolcRealtimeEvent.FunctionCall(
        callId, DeskTools.DISPATCH, buildJsonObject { put("words", utterance); put("title", "t") },
    )

    private fun JsonObject.words() = getValue("words").jsonPrimitive.content

    @Test fun `starts in connecting state`() = runTest {
        val h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { throw AssertionError("unused") })
        assertTrue(h.session.state.value is VoiceCallState.Connecting)
    }

    @Test fun `happy path connects checks mic plays tone and greets once`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        reachActive(h, this)
        assertEquals(listOf(false), h.rt.mutes)
        assertEquals(listOf(GREETING), h.rt.greetings)
        assertEquals(1, h.audio.playbackStarts)
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), h.audio.attemptedSources)
        // 起播后第一笔写入是本地提示音,先于任何云端音频
        assertTrue(h.audio.written.single().contentEquals(VoiceCallTone.connectTonePcm()))
        assertEquals(0f, h.session.speakerLevel.value, 0.001f)
    }

    @Test fun `foreground timeout ends as background`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.foreground.value = false
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.Connecting)
        advanceTimeBy(2_999L)
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.Connecting)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.Background), h.session.state.value)
    }

    @Test fun `foreground arriving inside the window continues the call`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.foreground.value = false
        runCurrent()
        advanceTimeBy(2_000L)
        h.foreground.value = true
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        reachActive(h, this)
    }

    @Test fun `telephony busy fails with busy`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.audio.telephonyBusy = true
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Busy, 1), h.session.state.value)
    }

    @Test fun `focus denial fails with audio focus`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.audio.focusGranted = false
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.AudioFocus, 1), h.session.state.value)
    }

    @Test fun `focus loss during the call fails with audio focus`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.audio.onFocusLost?.invoke()
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.AudioFocus, 1), h.session.state.value)
        assertEquals(1, h.audio.focusAbandons)
    }

    @Test fun `silent mic within three seconds fails mic silent`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        advanceTimeBy(2_999L)
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.MicSilent, 1), h.session.state.value)
    }

    @Test fun `system silenced mic fails mic silent`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        h.audio.micSilenced.value = true
        h.audio.offerChunk()
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.MicSilent, 1), h.session.state.value)
    }

    @Test fun `zero chunks while the mic warms up are not a silent mic`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        // 刚切通话模式时有的音频硬件先吐几块全零,这是预热,不是麦克风坏了
        repeat(3) { h.audio.offerChunk(ShortArray(320)) }
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        h.audio.offerChunk()
        runCurrent()
        assertTrue(h.session.state.value !is VoiceCallState.Failed)
    }

    @Test fun `only zero chunks for three seconds fails mic silent`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        h.audio.offerChunk(ShortArray(320))
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        advanceTimeBy(1_500L)
        h.audio.offerChunk(ShortArray(320))
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        advanceTimeBy(1_501L)
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.MicSilent, 1), h.session.state.value)
    }

    @Test fun `voice communication failure falls back to plain mic`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.audio.failingSources += MediaRecorder.AudioSource.VOICE_COMMUNICATION
        runCurrent()
        reachActive(h, this)
        assertEquals(
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC),
            h.audio.attemptedSources,
        )
    }

    @Test fun `all capture sources failing is mic silent`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.audio.failingSources += MediaRecorder.AudioSource.VOICE_COMMUNICATION
        h.audio.failingSources += MediaRecorder.AudioSource.MIC
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.MicSilent, 1), h.session.state.value)
        assertEquals(2, h.audio.attemptedSources.size)
    }

    @Test fun `connect auth failure maps to auth error`() = runTest {
        val h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH) })
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Auth, 1), h.session.state.value)
    }

    @Test fun `connect timeout maps to connect timeout`() = runTest {
        val h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT) })
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.ConnectTimeout, 1), h.session.state.value)
    }

    @Test fun `session closed mid call is disconnected`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.Closed)
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Disconnected, 1), h.session.state.value)
    }

    @Test fun `provider failure mid call maps error kind`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.Failed(VolcRealtimeException(VolcRealtimeException.Kind.RATE_LIMITED)))
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.RateLimited, 1), h.session.state.value)
    }

    @Test fun `retry reruns the whole flow and increments the attempt`() = runTest {
        lateinit var h: Harness
        val attempts = mutableListOf<Int>()
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { attempt ->
            attempts += attempt
            if (attempt < 3) throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH) else h.rt
        })
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Auth, 1), h.session.state.value)
        h.session.retry()
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Auth, 2), h.session.state.value)
        h.session.retry()
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.MicCheck)
        reachActive(h, this)
        assertEquals(listOf(1, 2, 3), attempts)
    }

    @Test fun `retry is ignored outside the failed state`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        val active = h.session.state.value
        h.session.retry()
        runCurrent()
        assertEquals(active, h.session.state.value)
        h.session.hangup()
        runCurrent()
        h.session.retry()
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.Hangup), h.session.state.value)
    }

    @Test fun `hangup ends with full cleanup and is idempotent`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.session.hangup()
        h.session.hangup()
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.Hangup), h.session.state.value)
        assertEquals(1, h.audio.captureStops)
        assertEquals(1, h.audio.playbackStops)
        assertEquals(1, h.audio.focusAbandons)
        assertTrue(h.rt.closed)
        h.session.hangup()
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.Hangup), h.session.state.value)
        assertEquals(1, h.audio.captureStops)
    }

    @Test fun `hangup while connecting cancels the attempt`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.foreground.value = false
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.Connecting)
        h.session.hangup()
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.Hangup), h.session.state.value)
    }

    @Test fun `phone call arriving mid-call ends it as incoming call`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.audio.telephonyBusy = true
        advanceTimeBy(VoiceCallSession.PHONE_CALL_POLL_MS + 1)
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.IncomingCall), h.session.state.value)
    }

    /** 静了这么久挂断;两分钟,提醒在第 90 秒。 */
    private fun quietCall(scope: kotlinx.coroutines.test.TestScope, idleMs: Long? = 120_000L): Harness {
        lateinit var h: Harness
        h = harness(scope.backgroundScope, idleMs = idleMs, clockNanos = { scope.currentTime * 1_000_000L }, nowMillis = { scope.currentTime }, connect = { h.rt })
        scope.runCurrent()
        reachActive(h, scope)
        return h
    }

    private fun kotlinx.coroutines.test.TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun `a quiet call is asked about 30 seconds before and hangs up at the idle limit`() = runTest {
        val h = quietCall(this)
        wait(89_000L)
        assertTrue(h.rt.cues.isEmpty())
        wait(2_000L)
        assertEquals(listOf(IDLE_CUE), h.rt.cues)
        wait(27_000L)
        assertTrue(h.session.state.value is VoiceCallState.Active)
        assertEquals("只提醒一次", 1, h.rt.cues.size)
        wait(2_000L)
        assertEquals(VoiceCallState.Ended(EndReason.Idle, idleMinutes = 2), h.session.state.value)
    }

    @Test fun `there is no overall call limit any more, only quiet ends a call`() = runTest {
        val h = quietCall(this)
        repeat(30) {
            wait(60_000L)
            h.rt.emit(VolcRealtimeEvent.UserTranscript("嗯", isFinal = true, isCompleteFinal = true, isCurrentTurn = true))
            runCurrent()
        }
        assertTrue("聊了半小时还在", h.session.state.value is VoiceCallState.Active)
        assertTrue(h.rt.cues.isEmpty())
    }

    @Test fun `the user speaking, even half a sentence, starts the quiet clock again`() = runTest {
        val h = quietCall(this)
        wait(80_000L)
        h.rt.emit(VolcRealtimeEvent.UserSpeaking)
        runCurrent()
        wait(80_000L)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("我想", isFinal = false, isCompleteFinal = false, isCurrentTurn = true))
        runCurrent()
        wait(89_000L)
        assertTrue(h.rt.cues.isEmpty())
        assertTrue(h.session.state.value is VoiceCallState.Active)
        wait(32_000L)
        assertEquals(1, h.rt.cues.size)
    }

    @Test fun `typing, Mika speaking and task progress each start the quiet clock again`() = runTest {
        val h = quietCall(this)
        wait(80_000L)
        h.session.sendUserText("在吗")
        runCurrent()
        wait(80_000L)
        h.rt.emit(VolcRealtimeEvent.AssistantTranscript("在的"))
        runCurrent()
        wait(80_000L)
        h.rt.emit(VolcRealtimeEvent.Audio(ByteArray(960) { 1 }))
        runCurrent()
        wait(80_000L)
        h.session.onActivity()
        wait(80_000L)
        assertTrue(h.rt.cues.isEmpty())
        assertTrue(h.session.state.value is VoiceCallState.Active)
    }

    @Test fun `Mika asking whether you are still there does not itself keep the call open`() = runTest {
        val h = quietCall(this)
        wait(91_000L)
        assertEquals(1, h.rt.cues.size)
        h.rt.emit(VolcRealtimeEvent.AssistantTranscript("还在吗?"))
        h.rt.emit(VolcRealtimeEvent.Audio(ByteArray(960) { 1 }))
        h.rt.emit(VolcRealtimeEvent.AssistantTextEnded)
        runCurrent()
        wait(30_000L)
        assertEquals(VoiceCallState.Ended(EndReason.Idle, idleMinutes = 2), h.session.state.value)
    }

    @Test fun `answering the warning keeps the call, and a later quiet spell is asked about again`() = runTest {
        val h = quietCall(this)
        wait(91_000L)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("在", isFinal = true, isCompleteFinal = true, isCurrentTurn = true))
        runCurrent()
        wait(60_000L)
        assertTrue(h.session.state.value is VoiceCallState.Active)
        assertEquals(1, h.rt.cues.size)
        wait(31_000L)
        assertEquals(2, h.rt.cues.size)
    }

    @Test fun `a call set never to hang up stays open however quiet`() = runTest {
        val h = quietCall(this, idleMs = null)
        wait(3_600_000L)
        assertTrue(h.session.state.value is VoiceCallState.Active)
        assertTrue(h.rt.cues.isEmpty() && h.rt.notices.isEmpty())
    }

    @Test fun `a protocol that cannot take a cue says the warning line as it is`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, idleMs = 120_000L, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.rt.acceptsCue = false
        runCurrent()
        reachActive(h, this)
        wait(91_000L)
        assertEquals(listOf(IDLE_LINE), h.rt.notices)
    }

    @Test fun `a short custom limit still warns before it ends`() = runTest {
        val h = quietCall(this, idleMs = 60_000L)
        wait(31_000L)
        assertEquals(1, h.rt.cues.size)
        wait(30_000L)
        assertEquals(VoiceCallState.Ended(EndReason.Idle, idleMinutes = 1), h.session.state.value)
    }

    @Test fun `external cancellation cleans up without becoming a failure`() = runTest {
        val sessionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        lateinit var h: Harness
        h = harness(sessionScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        sessionScope.cancel()
        runCurrent()
        assertFalse(h.session.state.value is VoiceCallState.Failed)
        assertEquals(1, h.audio.captureStops)
        assertEquals(1, h.audio.playbackStops)
        assertEquals(1, h.audio.focusAbandons)
        assertTrue(h.rt.closed)
    }

    @Test fun `barge in invalidates queued audio and interrupts playback`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.audio.writeGate = CompletableDeferred()
        val pcm1 = ByteArray(480) { 0x10 }
        val pcm2 = ByteArray(480) { 0x20 }
        val pcm3 = ByteArray(480) { 0x30 }
        h.rt.emit(VolcRealtimeEvent.Audio(pcm1))
        runCurrent()
        h.rt.emit(VolcRealtimeEvent.Audio(pcm2))
        runCurrent()
        h.rt.emit(VolcRealtimeEvent.UserSpeaking)
        runCurrent()
        assertEquals(1, h.audio.interruptCount)
        assertEquals(0f, h.session.speakerLevel.value, 0.001f)
        h.audio.writeGate?.complete(Unit)
        runCurrent()
        h.rt.emit(VolcRealtimeEvent.Audio(pcm3))
        runCurrent()
        // 提示音 + 打断瞬间已在写入的 pcm1 + 新代次 pcm3;被打断时仍在队列里的 pcm2 绝不入耳
        val bodies = h.audio.written.drop(1)
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contentEquals(pcm1))
        assertTrue(bodies[1].contentEquals(pcm3))
    }

    @Test fun `playback queue overflow fails with playback`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.audio.writeGate = CompletableDeferred()
        val oneSecond = ByteArray(48_000) { 1 }
        repeat(25) {
            h.rt.emit(VolcRealtimeEvent.Audio(oneSecond))
            runCurrent()
        }
        assertEquals(VoiceCallState.Failed(VoiceCallError.Playback, 1), h.session.state.value)
    }

    @Test fun `playback start failure is playback error`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        h.audio.failPlaybackStart = true
        runCurrent()
        h.audio.offerChunk()
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Playback, 1), h.session.state.value)
    }

    @Test fun `playback write failure is playback error`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.audio.writeFailure = IOException("track dead")
        h.rt.emit(VolcRealtimeEvent.Audio(ByteArray(480) { 1 }))
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Playback, 1), h.session.state.value)
    }

    @Test fun `tone does not count into speaker level but cloud audio does`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        assertTrue(h.audio.written.single().contentEquals(VoiceCallTone.connectTonePcm()))
        assertEquals(0f, h.session.speakerLevel.value, 0.001f)
        val loud = ByteArray(480) { i -> if (i % 2 == 0) 0xA0.toByte() else 0x0F.toByte() }
        h.rt.emit(VolcRealtimeEvent.Audio(loud))
        runCurrent()
        assertEquals(0.5f, h.session.speakerLevel.value, 0.02f)
    }

    @Test fun `captured chunks are paced upstream and drive the mic level`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        assertTrue(h.rt.sentPcm.isEmpty())
        h.audio.offerChunk(ShortArray(320) { 5_000 })
        runCurrent()
        assertEquals(1, h.rt.sentPcm.size)
        assertEquals(640, h.rt.sentPcm.single().size)
        assertEquals(0.625f, h.session.micLevel.value, 0.01f)
        h.audio.offerChunk(ShortArray(320) { 5_000 })
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(2, h.rt.sentPcm.size)
    }

    @Test fun `upstream send failure is disconnected`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.rt.failSendPcm = true
        h.audio.offerChunk()
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Disconnected, 1), h.session.state.value)
    }

    @Test fun `matching final transcript authorizes the voice request`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("打开微信", isFinal = true))
        h.rt.emit(requestCall("c1", "打开微信"))
        runCurrent()
        assertEquals(listOf("c1" to "打开微信"), requests)
        assertTrue(h.rt.toolResults.isEmpty())
        assertEquals(listOf("打开微信" to true), h.userTranscripts)
    }

    @Test fun `one utterance authorizes several desk calls until the user speaks again`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("帮我订晚饭,再叫个车", isFinal = true))
        h.rt.emit(requestCall("c1", "帮我订晚饭"))
        h.rt.emit(requestCall("c2", "再叫个车"))
        runCurrent()
        assertEquals(listOf("c1" to "帮我订晚饭,再叫个车", "c2" to "帮我订晚饭,再叫个车"), requests)
        // 用户又开口了:上一句不再能授权
        h.rt.emit(VolcRealtimeEvent.UserSpeaking)
        h.rt.emit(requestCall("c3", "再叫个车"))
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals("c3", h.rt.toolResults.single().first)
    }

    @Test fun `function call without a transcript is rejected with retry guidance`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(requestCall("c1", "打开微信"))
        runCurrent()
        assertTrue(requests.isEmpty())
        val (callId, result) = h.rt.toolResults.single()
        assertEquals("c1", callId)
        assertEquals("rejected", result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("next").jsonPrimitive.content.contains("call_id"))
    }

    @Test fun `partial or incomplete transcripts never authorize`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("打开微信", isFinal = false))
        h.rt.emit(requestCall("c1", "打开微信"))
        runCurrent()
        h.rt.emit(VolcRealtimeEvent.UserTranscript("打开微信", isFinal = true, isCompleteFinal = false))
        h.rt.emit(requestCall("c2", "打开微信"))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(2, h.rt.toolResults.size)
        h.rt.toolResults.forEach { (_, result) ->
            assertEquals("rejected", result.getValue("status").jsonPrimitive.content)
        }
    }

    @Test fun `assistant speech echoed back as a user request is rejected`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.AssistantTranscript("今天天气不错"))
        h.rt.emit(VolcRealtimeEvent.AssistantTextEnded)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("今天天气不错", isFinal = true))
        h.rt.emit(requestCall("c1", "今天天气不错"))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals("rejected", h.rt.toolResults.single().second.getValue("status").jsonPrimitive.content)
    }

    @Test fun `the greeting feeds the echo window`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript(GREETING, isFinal = true))
        h.rt.emit(requestCall("c1", GREETING))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(1, h.rt.toolResults.size)
    }

    @Test fun `the gate survives a retry and replays stay rejected`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("打开微信", isFinal = true))
        h.rt.emit(requestCall("c9", "打开微信"))
        runCurrent()
        assertEquals(listOf("c9" to "打开微信"), requests)
        h.rt.emit(VolcRealtimeEvent.Failed(VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)))
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Disconnected, 1), h.session.state.value)
        h.session.retry()
        runCurrent()
        h.audio.offerChunk()
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.Active)
        // 问候语每会话只播一次;提示音每次接通都播
        assertEquals(1, h.rt.greetings.size)
        assertEquals(2, h.audio.written.count { it.contentEquals(VoiceCallTone.connectTonePcm()) })
        // 重连后同一 callId 重放依旧拒绝
        h.rt.emit(VolcRealtimeEvent.UserTranscript("打开微信", isFinal = true))
        h.rt.emit(requestCall("c9", "打开微信"))
        runCurrent()
        assertEquals(listOf("c9" to "打开微信"), requests)
        assertEquals("rejected", h.rt.toolResults.single().second.getValue("status").jsonPrimitive.content)
    }

    @Test fun `a failed call can be ended without a retry and stays ended`() = runTest {
        val h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH) })
        runCurrent()
        assertEquals(VoiceCallState.Failed(VoiceCallError.Auth, 1), h.session.state.value)
        // 用户不重试、直接离开:通话页负责把 Failed 落进 Ended,前台服务据此收工
        h.session.end(EndReason.Hangup)
        assertEquals(VoiceCallState.Ended(EndReason.Hangup), h.session.state.value)
        h.session.retry() // 落了 Ended 之后重试不再有效
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.Hangup), h.session.state.value)
    }

    @Test fun `typed text goes to a session that takes text, else to the typed fallback`() = runTest {
        val typed = mutableListOf<String>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onTypedText = { typed += it })
        runCurrent()
        reachActive(h, this)
        h.session.sendUserText(" 查一下天气 ")
        // 会话不收文字(实时模式):交给装配方直接派单
        assertEquals(listOf("查一下天气"), typed)
        assertEquals(listOf("查一下天气" to true), h.userTranscripts)
        h.session.sendUserText("   ")
        h.session.sendUserText("")
        assertEquals("空白不派发", listOf("查一下天气"), typed)
        // 会话收文字(标准模式):当一句话交给它回,不走兜底
        h.rt.acceptsText = true
        h.session.sendUserText("再定个闹钟")
        assertEquals(listOf("再定个闹钟"), h.rt.texts)
        assertEquals(listOf("查一下天气"), typed)
    }

    @Test fun `a tool call about typed text passes the gate`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent()
        reachActive(h, this)
        h.rt.acceptsText = true
        h.session.sendUserText("帮我订明晚的日料")
        h.rt.emit(requestCall("c1", "订明晚的日料"))
        runCurrent()
        // 放行后原话换成当轮完整的话
        assertEquals(listOf("c1" to "帮我订明晚的日料"), requests)
    }

    @Test fun `a tool without words skips the gate`() = runTest {
        val calls = mutableListOf<String>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { _, name, _ -> calls += name })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.FunctionCall("c1", DeskTools.CANCEL, buildJsonObject { put("task_id", "t1") }))
        h.rt.emit(VolcRealtimeEvent.FunctionCall("c2", "send_dtmf", buildJsonObject { put("digits", "1") }))
        runCurrent()
        assertEquals("不认识的工具不交出去", listOf(DeskTools.CANCEL), calls)
    }

    @Test fun `barge in interrupts speech but never cancels a desk task`() = runTest {
        val port = FakeDeskPort()
        val ledger = DeskLedger()
        val desk = FrontDesk("p", backgroundScope, port, ledger, ReportPolicy({ currentTime }), FakeDeskSink())
        val echo = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(echo)
        val executor = DeskToolExecutor(port, ledger, gate, { currentTime }, "p") { desk.track(it.taskId, it.title) }
        lateinit var h: Harness
        lateinit var bridge: VoiceDeskBridge
        h = Harness(
            backgroundScope, connect = { h.rt }, echoWindow = echo, requestGate = gate,
            onDeskCall = { id, name, args -> backgroundScope.launch { bridge.onFunctionCall(id, name, args) } },
            clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime },
        )
        bridge = VoiceDeskBridge(desk, executor, { h.rt }, { it.title })
        bridge.attach()
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("帮我订明晚的日料", isFinal = true))
        h.rt.emit(requestCall("c1", "帮我订明晚的日料"))
        runCurrent()
        assertEquals("started", h.rt.toolResults.single().second.getValue("status").jsonPrimitive.content)
        h.rt.emit(VolcRealtimeEvent.Audio(ByteArray(480)))
        h.rt.emit(VolcRealtimeEvent.UserSpeaking)
        h.rt.emit(VolcRealtimeEvent.AssistantInterrupted)
        runCurrent()
        assertTrue(h.audio.interruptCount >= 1)
        assertTrue("插话不取消后台任务", port.cancels.isEmpty())
        assertTrue(port.states.getValue("t1").value is TaskState.Running)
    }

    @Test fun `answering a spoken question with one of its options reaches the task`() = runTest {
        val port = FakeDeskPort()
        val ledger = DeskLedger()
        val desk = FrontDesk("p", backgroundScope, port, ledger, ReportPolicy({ currentTime }), FakeDeskSink())
        val echo = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(echo)
        val executor = DeskToolExecutor(port, ledger, gate, { currentTime }, "p") { desk.track(it.taskId, it.title) }
        lateinit var h: Harness
        lateinit var bridge: VoiceDeskBridge
        h = Harness(
            backgroundScope, connect = { h.rt }, echoWindow = echo, requestGate = gate,
            onDeskCall = { id, name, args -> backgroundScope.launch { bridge.onFunctionCall(id, name, args) } },
            clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime },
        )
        bridge = VoiceDeskBridge(desk, executor, { h.rt }, { it.title }, echo::append)
        bridge.attach()
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("帮我订明晚的日料", isFinal = true))
        h.rt.emit(requestCall("c1", "帮我订明晚的日料"))
        runCurrent()
        port.states.getValue("t1").value = TaskState.WaitingInput("七点还是八点?")
        runCurrent()
        // 问题念出来了(进回声窗),用户挑了一个选项回答
        h.rt.emit(VolcRealtimeEvent.AssistantTranscript("订日料那件,在问你:七点还是八点?"))
        h.rt.emit(VolcRealtimeEvent.AssistantTextEnded)
        h.rt.emit(VolcRealtimeEvent.UserSpeaking)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("七点吧", isFinal = true))
        h.rt.emit(VolcRealtimeEvent.FunctionCall("c2", DeskTools.ANSWER, buildJsonObject { put("task_id", "t1"); put("words", "七点") }))
        runCurrent()
        assertEquals(listOf("t1" to "七点吧"), port.answers)
        assertEquals("ok", h.rt.toolResults.last().second.getValue("status").jsonPrimitive.content)
    }

    @Test fun `typed text is ignored outside the active state`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onDeskCall = { id, _, args -> requests += id to args.words() })
        runCurrent() // MicCheck,还没接通
        h.session.sendUserText("喂")
        reachActive(h, this)
        h.session.hangup()
        h.session.sendUserText("挂了也算")
        runCurrent()
        assertTrue(requests.isEmpty())
        assertTrue(h.userTranscripts.isEmpty())
    }

    @Test fun `turn listens once active and speaks while cloud audio plays`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        assertEquals(VoiceCallTurn.Listening, h.session.turn.value)
        reachActive(h, this)
        assertEquals(VoiceCallTurn.Listening, h.session.turn.value)
        h.rt.emit(VolcRealtimeEvent.Audio(ByteArray(480) { 1 }))
        runCurrent()
        assertEquals(VoiceCallTurn.Speaking, h.session.turn.value)
        advanceTimeBy(VoiceCallSession.SPEAKING_IDLE_MS / 2)
        runCurrent()
        assertEquals("刚播完一小段还算在说", VoiceCallTurn.Speaking, h.session.turn.value)
        advanceTimeBy(VoiceCallSession.SPEAKING_IDLE_MS + VoiceCallSession.TURN_POLL_MS)
        runCurrent()
        assertEquals("播放停了一阵就轮回到听", VoiceCallTurn.Listening, h.session.turn.value)
    }

    @Test fun `a final user transcript thinks and new speech listens again`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("查天气", isFinal = false))
        runCurrent()
        assertEquals(VoiceCallTurn.Listening, h.session.turn.value)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("查天气", isFinal = true))
        runCurrent()
        assertEquals(VoiceCallTurn.Thinking, h.session.turn.value)
        h.rt.emit(VolcRealtimeEvent.UserSpeaking)
        runCurrent()
        assertEquals(VoiceCallTurn.Listening, h.session.turn.value)
        h.session.sendUserText("打字也算问完")
        assertEquals(VoiceCallTurn.Thinking, h.session.turn.value)
        h.rt.emit(VolcRealtimeEvent.UserTranscriptionEmpty)
        runCurrent()
        assertEquals("没听清就回到听", VoiceCallTurn.Listening, h.session.turn.value)
    }

    @Test fun `a reply shown locally ends thinking without waiting for speech`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.session.sendUserText("现在几点")
        assertEquals(VoiceCallTurn.Thinking, h.session.turn.value)
        h.session.onLocalReply()
        assertEquals("打字的结果只在本地显示、不会播出来,显示了就轮回到听", VoiceCallTurn.Listening, h.session.turn.value)
    }

    @Test fun `a local reply does not cut off mika while she is talking`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.Audio(ByteArray(3200)))
        runCurrent()
        assertEquals(VoiceCallTurn.Speaking, h.session.turn.value)
        h.session.onLocalReply()
        assertEquals(VoiceCallTurn.Speaking, h.session.turn.value)
    }

    @Test fun `mute sends silence upstream and zeroes the mic level until unmuted`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        assertFalse(h.session.muted.value)
        h.session.setMuted(true)
        assertTrue(h.session.muted.value)
        h.audio.offerChunk(ShortArray(320) { 5_000 })
        runCurrent()
        assertEquals(1, h.rt.sentPcm.size)
        assertTrue("静音时上行全零(保持节奏)", h.rt.sentPcm.last().all { it == 0.toByte() })
        assertEquals(0f, h.session.micLevel.value, 0.001f)
        h.session.setMuted(false)
        h.audio.offerChunk(ShortArray(320) { 5_000 })
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(2, h.rt.sentPcm.size)
        assertFalse(h.rt.sentPcm.last().all { it == 0.toByte() })
        assertEquals(0.625f, h.session.micLevel.value, 0.01f)
    }

    @Test fun `assistant text streams as a draft before the final transcript`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.AssistantTranscript("好的,"))
        h.rt.emit(VolcRealtimeEvent.AssistantTranscript("我查一下"))
        runCurrent()
        assertEquals(listOf("好的,", "好的,我查一下"), h.assistantDrafts)
        h.rt.emit(VolcRealtimeEvent.AssistantTextEnded)
        runCurrent()
        assertEquals(listOf("好的,我查一下" to true), h.assistantTranscripts)
    }

    private companion object {
        const val GREETING = "你好,我是助手"
        const val IDLE_CUE = "问一句还在不在"
        const val IDLE_LINE = "还在吗?没动静我就先挂了"
    }
}
