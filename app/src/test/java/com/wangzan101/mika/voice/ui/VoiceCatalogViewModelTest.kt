package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.model.FakeVoiceProviderAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCredentialBackend
import com.wangzan101.mika.voice.model.VoiceCredentialStore
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelProbe
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceVerification
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import com.wangzan101.mika.voice.model.voiceTestProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeSession(flow: Flow<SpeechEvent>) : SpeechSession {
    override val events: Flow<SpeechEvent> = flow
    override val live = true
    override fun release() {}
    override fun cancel() {}
}

private class MemoryCredentialBackend : VoiceCredentialBackend {
    override fun read(secretId: String): String? = null
    override fun write(secretId: String, value: String): Boolean = true
    override fun delete(secretId: String) {}
}

private fun testStore(): VoiceCredentialStore = VoiceCredentialStore(
    MemoryCredentialBackend(),
    object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
    },
)

class VoiceCatalogViewModelTest {
    private val ref = VoiceModelRef("provider:p1", "m1", VoiceCapability.STT)

    private fun environment(): VoiceModelEnvironment {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(
            VoiceModel("provider:p1", "m1", "M1", stt = SttBinding(SttProtocol.AudioTranscriptions, endpoint = "https://old.example")),
        )
        return voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(voiceTestProvider()))
    }

    private fun viewModel(
        env: VoiceModelEnvironment,
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        probe: VoiceModelProbe = VoiceModelProbe(
            env,
            startStt = { FakeSession(flowOf()) },
            synthesize = { _, _ -> TtsResponse(byteArrayOf(), "audio/wav") },
            connectRealtime = {},
        ),
        playAudio: suspend (ByteArray, String) -> Unit = { _, _ -> },
    ) = VoiceCatalogViewModel(env, VoiceAccountManager(env.catalog, testStore()), probe, scope, playAudio, io)

    @Test fun `late probe cannot verify changed configuration`() = runTest {
        val env = environment()
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler))
        val pending = viewModel.probeAsync(ref)
        viewModel.updateEndpoint(ref, "https://new.example")
        pending.complete(ProbeResult.Success)
        assertEquals(VoiceVerification.Unverified, env.catalog.verification(ref))
    }

    @Test fun `a current probe success marks the model verified`() = runTest {
        val env = environment()
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler))
        val pending = viewModel.probeAsync(ref)
        pending.complete(ProbeResult.Success)
        assertEquals(VoiceVerification.Verified, env.catalog.verification(ref))
    }

    @Test fun `a cancelled probe leaves verification unchanged`() = runTest {
        val env = environment()
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler))
        val pending = viewModel.probeAsync(ref)
        pending.cancel()
        assertEquals(VoiceVerification.Unverified, env.catalog.verification(ref))
    }

    @Test fun `verifyTts maps a synthesis failure`() = runTest {
        val env = environment()
        val probe = VoiceModelProbe(
            env,
            startStt = { FakeSession(flowOf()) },
            synthesize = { _, _ -> throw IOException("no network") },
            connectRealtime = {},
        )
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler), probe)
        viewModel.verifyTts(ref, TtsRequest("hi"))
        runCurrent()
        val error = (viewModel.probeState(ref) as VoiceProbeUi.Failed).error
        assertEquals(VoiceErrorCategory.NETWORK, error.category)
    }

    @Test fun `verifyTts reports a playback failure distinctly from synthesis`() = runTest {
        val env = environment()
        val probe = VoiceModelProbe(
            env,
            startStt = { FakeSession(flowOf()) },
            synthesize = { _, _ -> TtsResponse(byteArrayOf(1), "audio/wav") },
            connectRealtime = {},
        )
        val viewModel = viewModel(
            env, backgroundScope, StandardTestDispatcher(testScheduler), probe,
            playAudio = { _, _ -> throw RuntimeException("boom") },
        )
        viewModel.verifyTts(ref, TtsRequest("hi"))
        runCurrent()
        val error = (viewModel.probeState(ref) as VoiceProbeUi.Failed).error
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, error.category)
        assertEquals("Playback failed: boom", error.detail)
    }

    @Test fun `verifyStt records listening and verified probe state`() = runTest {
        val env = environment()
        val session = FakeSession(flowOf(SpeechEvent.Partial(stable = "hel", tail = "lo"), SpeechEvent.Final("hello")))
        val probe = VoiceModelProbe(
            env,
            startStt = { session },
            synthesize = { _, _ -> TtsResponse(byteArrayOf(), "audio/wav") },
            connectRealtime = {},
        )
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler), probe)
        viewModel.verifyStt(ref)
        runCurrent()
        assertEquals(VoiceVerification.Verified, env.catalog.verification(ref))
        assertEquals(VoiceProbeUi.Verified, viewModel.probeState(ref))
    }

    @Test fun `probes flow publishes running and verified probe states`() = runTest {
        val env = environment()
        val session = FakeSession(flowOf(SpeechEvent.Partial(stable = "hel", tail = "lo"), SpeechEvent.Final("hello")))
        val probe = VoiceModelProbe(
            env,
            startStt = { session },
            synthesize = { _, _ -> TtsResponse(byteArrayOf(), "audio/wav") },
            connectRealtime = {},
        )
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler), probe)
        val fingerprint = viewModel.probeFingerprint(ref)!!
        viewModel.verifyStt(ref)
        assertEquals(VoiceProbeUi.Running, viewModel.probes.value[fingerprint])
        runCurrent()
        assertEquals(VoiceProbeUi.Verified, viewModel.probes.value[fingerprint])
    }

    @Test fun `saveService and saveUnverified upsert while system entries are protected`() = runTest {
        val env = environment()
        val viewModel = viewModel(env, backgroundScope, StandardTestDispatcher(testScheduler))
        viewModel.saveService(VoiceService("local:x", "Local", VoiceAccount.None))
        viewModel.saveUnverified(VoiceModel("local:x", "m2", "M2", stt = SttBinding(SttProtocol.FunAsrStreaming)))
        assertNotNull(env.catalog.state.value.models.firstOrNull { it.id == "m2" })

        viewModel.deleteModel(SttSelectionController.SYSTEM_STT_REF)
        assertTrue(env.catalog.state.value.models.any { it.id == "system-stt" })

        viewModel.deleteModel(TtsSelectionController.SYSTEM_TTS_REF)
        assertTrue(env.catalog.state.value.models.any { it.id == "system-tts" })

        viewModel.deleteService(SttSelectionController.SYSTEM_STT_SERVICE.id)
        assertTrue(env.catalog.state.value.services.any { it.id == "android-system" })
    }
}
