package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeSpeechSession(flow: Flow<SpeechEvent>) : SpeechSession {
    var released = false
        private set
    override val events: Flow<SpeechEvent> = flow
    override val live = true
    override fun release() { released = true }
    override fun cancel() {}
}

class VoiceModelProbeTest {
    private val provider = voiceTestProvider()

    private fun environment(): VoiceModelEnvironment {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "m1", "M1", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        return voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider))
    }

    private val ref = VoiceModelRef("provider:p1", "m1", VoiceCapability.STT)

    private fun probe(
        startStt: (VoiceModelRef) -> SpeechSession = { FakeSpeechSession(flowOf(SpeechEvent.Final("hello"))) },
        synthesize: suspend (VoiceModelRef, TtsRequest) -> TtsResponse = { _, _ -> TtsResponse(byteArrayOf(1, 2, 3), "audio/wav") },
        connectRealtime: suspend (VoiceModelRef) -> Unit = {},
    ) = VoiceModelProbe(environment(), startStt, synthesize, connectRealtime)

    @Test fun `connection resolves a fingerprint and invalidates on endpoint change`() {
        val probe = probe()
        val first = probe.connection(ref)
        val env = environment()
        env.catalog.upsertModel(
            VoiceModel("provider:p1", "m1", "M1", stt = SttBinding(SttProtocol.AudioTranscriptions, endpoint = "https://new.example")),
        )
        assertNotEquals(first, VoiceModelProbe(env, { FakeSpeechSession(flowOf()) }, { _, _ -> TtsResponse(byteArrayOf(), "audio/wav") }, {}).connection(ref))
    }

    @Test fun `connection fails for an unregistered model`() {
        val error = org.junit.Assert.assertThrows(VoiceModelError.IncompleteConfig::class.java) {
            probe().connection(VoiceModelRef("provider:p1", "missing", VoiceCapability.STT))
        }
        assertEquals("Voice model is not registered", error.detail)
    }

    @Test fun `verifyStt emits capturing transcribing and the verified transcript`() = runTest {
        val session = FakeSpeechSession(
            flowOf(
                SpeechEvent.Level(0.5f),
                SpeechEvent.Partial(stable = "hel", tail = "lo"),
                SpeechEvent.Final("hello"),
            ),
        )
        val probe = probe(startStt = { session })

        val states = probe.verifyStt(ref).toList()

        assertEquals(
            listOf(
                VoiceProbeState.Capturing,
                VoiceProbeState.Transcribing("hello"),
                VoiceProbeState.Verified("hello"),
            ),
            states,
        )
        assertTrue(session.released)
    }

    @Test fun `verifyStt maps a session failure and always releases`() = runTest {
        val session = FakeSpeechSession(flowOf(SpeechEvent.Failed(SpeechFailure.NoPermission)))
        val probe = probe(startStt = { session })

        val states = probe.verifyStt(ref).toList()

        assertEquals(1, states.size)
        val failed = states.single() as VoiceProbeState.Failed
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, failed.error.category)
        assertTrue(session.released)
    }

    @Test fun `verifyStt reports a start failure as a failed probe`() = runTest {
        val probe = probe(startStt = { throw IllegalStateException("microphone busy") })

        val states = probe.verifyStt(ref).toList()

        assertEquals(1, states.size)
        assertTrue(states.single() is VoiceProbeState.Failed)
    }

    @Test fun `verifyTts returns the synthesized audio`() = runTest {
        val response = TtsResponse(byteArrayOf(9, 8, 7), "audio/mp3")
        val probe = probe(synthesize = { _, _ -> response })

        assertSame(response, probe.verifyTts(ref, TtsRequest("hi")))
    }

    @Test fun `verifyRealtimeConnection records a successful handshake`() = runTest {
        var connected = false
        val probe = probe(connectRealtime = { connected = true })

        probe.verifyRealtimeConnection(ref)

        assertTrue(connected)
    }

    @Test fun `verifyRealtimeConnection propagates the raw handshake failure`() = runTest {
        val probe = probe(connectRealtime = { throw IOException("connection refused") })

        val error = org.junit.Assert.assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { probe.verifyRealtimeConnection(ref) }
        }
        assertEquals("connection refused", error.message)
    }
}
