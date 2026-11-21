package com.wangzan101.mika.voicecall

import android.media.MediaRecorder
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
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
        override fun close() { closed = true }
    }

    private class Harness(
        scope: CoroutineScope,
        greeting: String = GREETING,
        timeLimitMs: Long = 600_000L,
        connect: suspend (Int) -> CallRealtimeSession,
        onVoiceRequest: (String, String) -> Unit = { _, _ -> },
        onBargeIn: () -> Unit = {},
        clockNanos: () -> Long,
        nowMillis: () -> Long,
    ) {
        val audio = FakeAudio()
        val rt = FakeRealtime()
        val foreground = MutableStateFlow(true)
        val userTranscripts = mutableListOf<Pair<String, Boolean>>()
        val session = VoiceCallSession(
            scope = scope,
            audio = audio,
            foregroundResumed = foreground,
            connect = connect,
            greeting = greeting,
            onVoiceRequest = onVoiceRequest,
            onBargeIn = onBargeIn,
            onUserTranscript = { text, isFinal -> userTranscripts += text to isFinal },
            timeLimitMs = timeLimitMs,
            clockNanos = clockNanos,
            nowMillis = nowMillis,
        )
    }

    private fun harness(
        scope: CoroutineScope,
        greeting: String = GREETING,
        timeLimitMs: Long = 600_000L,
        connect: suspend (Int) -> CallRealtimeSession,
        onVoiceRequest: (String, String) -> Unit = { _, _ -> },
        onBargeIn: () -> Unit = {},
        clockNanos: () -> Long,
        nowMillis: () -> Long,
    ) = Harness(scope, greeting, timeLimitMs, connect, onVoiceRequest, onBargeIn, clockNanos, nowMillis)

    private fun reachActive(h: Harness, scope: kotlinx.coroutines.test.TestScope) {
        h.audio.offerChunk()
        scope.runCurrent()
        assertTrue("expected Active but was ${h.session.state.value}", h.session.state.value is VoiceCallState.Active)
    }

    private fun requestCall(callId: String, utterance: String) = VolcRealtimeEvent.FunctionCall(
        callId, "mika_request", buildJsonObject { put("utterance", utterance) },
    )

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

    @Test fun `zero energy first chunk fails mic silent`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        h.audio.offerChunk(ShortArray(320))
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

    @Test fun `ten minute limit ends the call`() = runTest {
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt })
        runCurrent()
        reachActive(h, this)
        advanceTimeBy(599_999L)
        runCurrent()
        assertTrue(h.session.state.value is VoiceCallState.Active)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(VoiceCallState.Ended(EndReason.TimeLimit), h.session.state.value)
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
        val bargeIns = mutableListOf<Unit>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onBargeIn = { bargeIns += Unit })
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
        assertEquals(1, bargeIns.size)
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
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
        runCurrent()
        reachActive(h, this)
        h.rt.emit(VolcRealtimeEvent.UserTranscript("打开微信", isFinal = true))
        h.rt.emit(requestCall("c1", "打开微信"))
        runCurrent()
        assertEquals(listOf("c1" to "打开微信"), requests)
        assertTrue(h.rt.toolResults.isEmpty())
        assertEquals(listOf("打开微信" to true), h.userTranscripts)
    }

    @Test fun `function call without a transcript is rejected with retry guidance`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
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
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
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
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
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
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
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
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
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

    @Test fun `typed text dispatches as a verified request and lands in the transcript`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
        runCurrent()
        reachActive(h, this)
        h.session.sendUserText(" 查一下天气 ")
        assertEquals(listOf("typed-1" to "查一下天气"), requests)
        assertEquals(listOf("查一下天气" to true), h.userTranscripts)
        h.session.sendUserText("   ")
        h.session.sendUserText("")
        assertEquals("空白不派发", listOf("typed-1" to "查一下天气"), requests)
        h.session.sendUserText("再定个闹钟")
        assertEquals(listOf("typed-1" to "查一下天气", "typed-2" to "再定个闹钟"), requests)
    }

    @Test fun `typed text is ignored outside the active state`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        lateinit var h: Harness
        h = harness(backgroundScope, clockNanos = { currentTime * 1_000_000L }, nowMillis = { currentTime }, connect = { h.rt }, onVoiceRequest = { id, text -> requests += id to text })
        runCurrent() // MicCheck,还没接通
        h.session.sendUserText("喂")
        reachActive(h, this)
        h.session.hangup()
        h.session.sendUserText("挂了也算")
        runCurrent()
        assertTrue(requests.isEmpty())
        assertTrue(h.userTranscripts.isEmpty())
    }

    private companion object {
        const val GREETING = "你好,我是助手"
    }
}
