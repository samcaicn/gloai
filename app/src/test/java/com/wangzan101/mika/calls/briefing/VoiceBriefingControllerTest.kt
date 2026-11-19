package com.wangzan101.mika.calls.briefing

import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBriefingControllerTest {
    private class Connection : VoiceBriefingConnection {
        val input = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
        override val events = input.receiveAsFlow()
        var closeStarted = false
        var closed = false
        val allowClose = CompletableDeferred<Unit>()
        override fun send(bytes: ByteArray) = !closeStarted
        override fun greet() = true
        override suspend fun closeAndAwait() {
            closeStarted = true
            allowClose.await()
            closed = true
        }
        fun final(text: String) { input.trySend(VolcRealtimeEvent.UserTranscript(text, true)) }
    }
    private class Audio : VoiceBriefingAudio {
        var stopped = false
        var closed = false
        var interrupts = 0
        var onFailure: ((String) -> Unit)? = null
        override suspend fun start(
            scope: CoroutineScope,
            mayUseAudio: () -> Boolean,
            send: (ByteArray) -> Boolean,
            onFailure: (String) -> Unit,
        ) {
            this.onFailure = onFailure
            check(mayUseAudio())
        }
        fun loseFocus() { onFailure?.invoke("音频焦点已失去，未拨号") }
        override fun enqueue(pcm: ByteArray) = Unit
        override fun interrupt(): Long { interrupts++; return 0L }
        override fun stopImmediately() { stopped = true }
        override suspend fun close() { closed = true }
    }
    private val credentials = VolcRealtimeCredentials(apiKey = "fake-test-only")

    @Test fun `cancel before scheduled session body releases operation and permits retry`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        var connects = 0
        val controller = VoiceBriefingController(this, { _, _ -> connects++; connection }, { Audio() }, { true })
        assertTrue(controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) {})
        controller.cancel()
        runCurrent()
        assertEquals(0, connects)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
        assertTrue(controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) {})
        runCurrent()
        assertEquals(1, connects)
        controller.cancel()
        runCurrent()
        assertTrue(connection.closed)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `owner scope canceled during noncancellable shutdown cannot authorize`() = runTest {
        val owner = Job(coroutineContext[Job])
        val ownerScope = CoroutineScope(coroutineContext + owner)
        val connection = Connection()
        var dials = 0
        val controller = VoiceBriefingController(ownerScope, { _, _ -> connection }, { Audio() }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        connection.final("我说完了，你去打")
        runCurrent()
        assertTrue(connection.closeStarted)
        owner.cancel()
        connection.allowClose.complete(Unit)
        runCurrent()
        assertTrue(connection.closed)
        assertEquals(0, dials)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `precall conversation strips phone tools even from a reused call config`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        var actual: VolcRealtimeConfig? = null
        val controller = VoiceBriefingController(this, { _, config -> actual = config; connection }, { Audio() }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(speaker = "custom-speaker", model = "custom-model", enablePhoneDtmf = true), canContinue = { true }) {}
        runCurrent()
        assertEquals(false, actual?.enablePhoneDtmf)
        assertEquals("custom-speaker", actual?.speaker)
        assertEquals("custom-model", actual?.model)
        controller.cancel()
        runCurrent()
    }

    @Test fun `authorization waits for audio and provider close and is delivered only once`() = runTest {
        val connection = Connection()
        val audio = Audio()
        val briefs = mutableListOf<CallTaskBrief>()
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target+sim", credentials, VolcRealtimeConfig(), canContinue = { true }) {
            assertTrue(audio.closed)
            assertTrue(connection.closed)
            briefs.add(it)
        }
        runCurrent()
        connection.final("问营业时间，不要预约")
        connection.final("我说完了，你去打")
        connection.final("我说完了，你去打")
        runCurrent()
        assertTrue(audio.closed)
        assertTrue(connection.closeStarted)
        assertTrue(briefs.isEmpty())
        connection.allowClose.complete(Unit)
        runCurrent()
        assertEquals(1, briefs.size)
        assertEquals("问营业时间，不要预约", briefs.single().context)
        assertEquals(VoiceBriefingActivity.AUTHORIZED, controller.state.value.activity)
    }

    @Test fun `empty recognition and ordinary pauses keep three owner turns until explicit authorization`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        val briefs = mutableListOf<CallTaskBrief>()
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { briefs.add(it) }
        runCurrent()

        listOf("问明天的营业时间", "改成后天", "只问清楚，不要预约").forEach { instruction ->
            connection.input.trySend(VolcRealtimeEvent.UserSpeaking)
            connection.final(instruction)
            connection.input.trySend(VolcRealtimeEvent.UserSpeechEnded)
            connection.input.trySend(VolcRealtimeEvent.UserTranscriptionEmpty)
            connection.input.trySend(VolcRealtimeEvent.InputAudioCommitted)
            connection.input.trySend(VolcRealtimeEvent.AssistantTranscript("我在听。"))
            connection.input.trySend(VolcRealtimeEvent.AssistantTextEnded)
            connection.input.trySend(VolcRealtimeEvent.AssistantAudioEnded)
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(VoiceBriefingActivity.LISTENING, controller.state.value.activity)
            assertFalse(audio.stopped)
            assertFalse(connection.closeStarted)
            assertTrue(briefs.isEmpty())
        }
        connection.final("我说完了，你去打")
        runCurrent()
        assertEquals(1, briefs.size)
        assertEquals("问明天的营业时间\n改成后天\n只问清楚，不要预约", briefs.single().context)
        assertEquals(VoiceBriefingActivity.AUTHORIZED, controller.state.value.activity)
        assertTrue(audio.closed)
        assertTrue(connection.closed)
    }

    @Test fun `empty recognition after partial authorization closes without promoting or erasing it`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        connection.input.trySend(VolcRealtimeEvent.UserTranscript("我说完了，你去打", isFinal = false))
        connection.input.trySend(VolcRealtimeEvent.UserSpeechEnded)
        connection.input.trySend(VolcRealtimeEvent.UserTranscriptionEmpty)
        connection.final("我说完了，你去打")
        runCurrent()
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
        assertEquals("我说完了，你去打", controller.state.value.ownerPartial)
        assertEquals("", controller.state.value.ownerTranscript)
        assertEquals(0, dials)
        assertEquals("这句话未完整识别，未拨号；请重新完整交代", controller.state.value.message)
        assertTrue(audio.closed)
        assertTrue(connection.closed)
    }

    @Test fun `empty recognition after partial budget cannot discard constraint then accept queued authorization`() = runTest {
        val connection = Connection()
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { dials++ }
        runCurrent()
        connection.final("问价格")
        connection.input.trySend(VolcRealtimeEvent.UserTranscript("预算不能超过一百", isFinal = false))
        connection.input.trySend(VolcRealtimeEvent.UserTranscriptionEmpty)
        connection.final("我说完了，你去打")
        runCurrent()
        assertEquals(0, dials)
        assertEquals(VoiceBriefingActivity.CLOSING, controller.state.value.activity)
        assertEquals("这句话未完整识别，未拨号；请重新完整交代", controller.state.value.message)
        assertEquals("问价格", controller.state.value.ownerTranscript)
        assertEquals("预算不能超过一百", controller.state.value.ownerPartial)
        connection.allowClose.complete(Unit)
        runCurrent()
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
        assertEquals("预算不能超过一百", controller.state.value.ownerPartial)
        assertEquals(0, dials)
    }

    @Test fun `model declared completion and model authorization do not close or dial`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        val briefs = mutableListOf<CallTaskBrief>()
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { briefs.add(it) }
        runCurrent()
        connection.final("问价格")
        connection.input.trySend(VolcRealtimeEvent.AssistantTranscript("交代完毕。我说完了，你去打"))
        connection.input.trySend(VolcRealtimeEvent.AssistantTextEnded)
        connection.input.trySend(VolcRealtimeEvent.AssistantAudioEnded)
        connection.input.trySend(VolcRealtimeEvent.UserTranscriptionEmpty)
        runCurrent()
        assertEquals(VoiceBriefingActivity.LISTENING, controller.state.value.activity)
        assertFalse(connection.closeStarted)
        assertTrue(briefs.isEmpty())
        connection.final("不要付订金")
        connection.final("我说完了，你去打")
        runCurrent()
        assertEquals("问价格\n不要付订金", briefs.single().context)
    }

    @Test fun `failed recognition closes explicitly preserving earlier instructions and rejects queued authorization`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { dials++ }
        runCurrent()
        connection.final("问价格，不要预约")
        connection.input.trySend(VolcRealtimeEvent.UserTranscriptionFailed)
        connection.final("我说完了，你去打")
        runCurrent()
        assertEquals(0, dials)
        assertTrue(audio.closed)
        assertTrue(connection.closed)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
        assertEquals("问价格，不要预约", controller.state.value.ownerTranscript)
        assertEquals("本句未能完整识别，未拨号；请重新交代", controller.state.value.message)
    }

    @Test fun `recognition failure is visible while provider is still closing`() = runTest {
        val connection = Connection()
        val audio = Audio()
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { fail("must not authorize") }
        runCurrent()
        connection.final("问价格，不要预约")
        connection.input.trySend(VolcRealtimeEvent.UserTranscriptionFailed)
        runCurrent()
        assertTrue(connection.closeStarted)
        assertFalse(connection.closed)
        assertEquals(VoiceBriefingActivity.CLOSING, controller.state.value.activity)
        assertEquals("本句未能完整识别，未拨号；请重新交代", controller.state.value.message)
        assertEquals("问价格，不要预约", controller.state.value.ownerTranscript)
        connection.allowClose.complete(Unit)
        runCurrent()
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `incomplete and late finals cannot silently omit instructions then authorize`() = runTest {
        listOf(
            VolcRealtimeEvent.UserTranscript("预算一百", true, isCompleteFinal = false),
            VolcRealtimeEvent.UserTranscript("预算一百", true, isCurrentTurn = false),
        ).forEach { unsafeFinal ->
            val connection = Connection().also { it.allowClose.complete(Unit) }
            val audio = Audio()
            var dials = 0
            val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
            controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { dials++ }
            runCurrent()
            connection.final("问价格，不要预约")
            connection.input.trySend(unsafeFinal)
            connection.final("我说完了，你去打")
            runCurrent()
            assertEquals(0, dials)
            assertTrue(audio.closed)
            assertTrue(connection.closed)
            assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
            assertEquals("问价格，不要预约", controller.state.value.ownerTranscript)
            assertTrue(controller.state.value.message.orEmpty().isNotBlank())
        }
    }

    @Test fun `cancel after empty result rejects queued authorization and fully releases session`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        connection.input.trySend(VolcRealtimeEvent.UserTranscriptionEmpty)
        runCurrent()
        connection.final("我说完了，你去打")
        controller.cancel()
        runCurrent()
        assertEquals(0, dials)
        assertTrue(audio.closed)
        assertTrue(connection.closed)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `cancel during provider close prevents a late authorized callback`() = runTest {
        val connection = Connection()
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        connection.final("我说完了，你去打")
        runCurrent()
        assertTrue(connection.closeStarted)
        controller.cancel()
        connection.allowClose.complete(Unit)
        runCurrent()
        assertEquals(0, dials)
        assertTrue(audio.closed)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `model text never authorizes and owner speech interrupts playback`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        connection.input.trySend(VolcRealtimeEvent.AssistantTranscript("我说完了，你去打"))
        connection.input.trySend(VolcRealtimeEvent.UserSpeaking)
        connection.input.trySend(VolcRealtimeEvent.UserTranscript("我说完了，你去打", true, isCurrentTurn = false))
        runCurrent()
        assertEquals(0, dials)
        assertEquals(1, audio.interrupts)
        controller.cancel()
        runCurrent()
    }

    @Test fun `incoming call target change or background gate closes the session without dialing`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        var gate = true
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { gate }) { dials++ }
        runCurrent()
        gate = false
        advanceTimeBy(51)
        runCurrent()
        assertTrue(audio.closed)
        assertTrue(connection.closed)
        connection.final("我说完了，你去打")
        runCurrent()
        assertEquals(0, dials)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `handoff gate is rechecked after provider finishes closing`() = runTest {
        val connection = Connection()
        val audio = Audio()
        var gate = true
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { gate }) { dials++ }
        runCurrent()
        connection.final("我说完了，你去打")
        runCurrent()
        gate = false
        connection.allowClose.complete(Unit)
        runCurrent()
        assertEquals(0, dials)
    }

    @Test fun `session and close have fixed timeout and no reconnection`() = runTest {
        val connection = Connection()
        val audio = Audio()
        var connects = 0
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connects++; connection }, { audio }, { true }, 500)
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(1, connects)
        assertEquals(0, dials)
        assertTrue(audio.closed)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `focus loss while waiting immediately closes provider and rejects queued authorization`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        assertEquals(VoiceBriefingActivity.LISTENING, controller.state.value.activity)
        // Queue a final but revoke audio before the collector processes it. Do not advance the
        // 50ms gate watcher: focus loss must cancel the collector without waiting for polling.
        connection.final("我说完了，你去打")
        audio.loseFocus()
        runCurrent()
        assertTrue(audio.closed)
        assertTrue(connection.closed)
        assertEquals(0, dials)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
        assertEquals("音频焦点已失去，未拨号", controller.state.value.message)
    }

    @Test fun `focus loss closes idle provider without another event or watcher tick`() = runTest {
        val connection = Connection().also { it.allowClose.complete(Unit) }
        val audio = Audio()
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), canContinue = { true }) { fail("must not authorize") }
        runCurrent()
        audio.loseFocus()
        runCurrent()
        assertTrue(connection.closed)
        assertTrue(audio.closed)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
    }

    @Test fun `focus loss during authorization cleanup revokes already finalized task`() = runTest {
        val connection = Connection()
        val audio = Audio()
        var dials = 0
        val controller = VoiceBriefingController(this, { _, _ -> connection }, { audio }, { true })
        controller.start("target", credentials, VolcRealtimeConfig(), CallTaskBrief("问价格"), { true }) { dials++ }
        runCurrent()
        connection.final("我说完了，你去打")
        runCurrent()
        assertTrue(connection.closeStarted)
        assertTrue(audio.closed)
        assertEquals(0, dials)
        audio.loseFocus()
        connection.allowClose.complete(Unit)
        runCurrent()
        assertEquals(0, dials)
        assertEquals(VoiceBriefingActivity.IDLE, controller.state.value.activity)
        assertEquals("音频焦点已失去，未拨号", controller.state.value.message)
    }
}
