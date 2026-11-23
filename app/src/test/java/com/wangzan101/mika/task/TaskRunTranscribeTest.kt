package com.wangzan101.mika.task

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.transcribe.TranscribePort
import com.wangzan101.mika.core.cap.transcribe.Transcript
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.model.FakeVoiceProviderAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceCredentials
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceOwnedAccountSource
import com.wangzan101.mika.voice.model.VoiceProviderAccountSource
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import com.wangzan101.mika.voice.model.voiceTestProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunTranscribeTest {
    private object UnusedTranscribePort : TranscribePort {
        override suspend fun transcribe(path: String, language: String?) = Transcript("unused")
    }

    private class RecordingTranscribePort : TranscribePort {
        var calls = 0
            private set

        override suspend fun transcribe(path: String, language: String?): Transcript {
            calls++
            return Transcript("transcribed", language)
        }
    }

    private class EmptyScreen : ScreenPort {
        override suspend fun snapshot() = ScreenSnapshot("snapshot", "test", emptyList(), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok()
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
        override suspend fun back() = ActionResult.Ok()
        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = emptyList<Pair<String, String>>()
    }

    private data class Fixture(
        val backend: MemoryVoiceCatalogBackend,
        val catalog: VoiceCatalogStore,
        val batchRef: VoiceModelRef,
        val streamingRef: VoiceModelRef,
        val provider: Provider,
    )

    private fun fixture(account: VoiceAccount = VoiceAccount.ExistingProvider("p1")): Fixture {
        val provider = voiceTestProvider()
        val backend = MemoryVoiceCatalogBackend()
        val catalog = VoiceCatalogStore(backend, migrated = true)
        catalog.upsertService(VoiceService("batch-service", "Batch", account))
        catalog.upsertModel(VoiceModel(
            "batch-service", "batch", "Batch",
            stt = SttBinding(SttProtocol.AudioTranscriptions),
        ))
        catalog.upsertService(VoiceService("stream-service", "Stream", VoiceAccount.None))
        catalog.upsertModel(VoiceModel(
            "stream-service", "stream", "Stream",
            stt = SttBinding(SttProtocol.FunAsrStreaming, endpoint = "ws://localhost:10095"),
        ))
        return Fixture(
            backend,
            catalog,
            VoiceModelRef("batch-service", "batch", VoiceCapability.STT),
            VoiceModelRef("stream-service", "stream", VoiceCapability.STT),
            provider,
        )
    }

    private fun controller(
        fixture: Fixture,
        providers: VoiceProviderAccountSource = FakeVoiceProviderAccountSource(fixture.provider),
        owned: VoiceOwnedAccountSource? = null,
    ): SttSelectionController {
        val environment = if (owned == null) {
            voiceTestEnvironment(fixture.catalog, providers)
        } else {
            voiceTestEnvironment(fixture.catalog, providers, owned)
        }
        return environment.stt
    }

    private fun phoneTools(
        stt: SttSelectionController,
        port: TranscribePort = UnusedTranscribePort,
    ): ToolDispatcher = toolDispatcher(
        EmptyScreen(),
        transcribeProvider = {
            taskTranscribePort(
                RunOrigin.Ui,
                stt,
                legacyPort = { null },
                migratedPort = { port },
            )
        },
    )

    private fun ToolDispatcher.offersTranscription() = definitions().any { it.name == "audio_transcribe" }

    @Test fun `assembled phone runtime refreshes transcription definitions and execution`() = runTest {
        val fixture = fixture()
        val stt = controller(fixture)
        val port = RecordingTranscribePort()
        stt.select(SpeechChoice.Auto)
        val tools = phoneTools(stt, port)
        val executor = ActionExecutor(
            tools,
            OperationGuards(),
            null,
            gate = {},
            recorder = ActionRecorder(RunSink.NONE),
            autonomous = true,
        )
        var step = 0
        suspend fun transcribe() = executor.run(
            Part.ToolCall("transcribe-${++step}", "audio_transcribe", """{"path":"/workspace/audio.wav"}"""),
            Origin.Model(step),
        ).execution().content

        assertFalse(tools.offersTranscription())
        stt.select(fixture.batchRef)
        assertTrue(tools.offersTranscription())
        assertEquals("transcribed", transcribe()["text"]?.jsonPrimitive?.content)
        assertEquals(1, port.calls)

        stt.select(SpeechChoice.System)
        assertFalse(tools.offersTranscription())
        assertEquals("unknown_tool", transcribe()["error"]?.jsonPrimitive?.content)
        assertEquals(1, port.calls)

        stt.select(fixture.batchRef)
        assertTrue(tools.offersTranscription())
        assertEquals("transcribed", transcribe()["text"]?.jsonPrimitive?.content)
        assertEquals(2, port.calls)

        stt.select(fixture.streamingRef)
        assertFalse(tools.offersTranscription())
        stt.select(fixture.batchRef)
        assertTrue(tools.offersTranscription())
        stt.select(SpeechChoice.Off)
        assertFalse(tools.offersTranscription())
        assertEquals("unknown_tool", transcribe()["error"]?.jsonPrimitive?.content)
        assertEquals(2, port.calls)

        stt.select(fixture.batchRef)
        assertTrue(tools.offersTranscription())
        assertEquals("transcribed", transcribe()["text"]?.jsonPrimitive?.content)
        assertEquals(3, port.calls)
    }

    @Test fun `legacy migration gate keeps the original port decision`() {
        val legacy = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = false)
        val stt = voiceTestEnvironment(legacy).stt
        var legacyCalls = 0
        var migratedCalls = 0

        val tools = toolDispatcher(
            EmptyScreen(),
            transcribe = taskTranscribePort(
                RunOrigin.Ui,
                stt,
                legacyPort = { legacyCalls++; UnusedTranscribePort },
                migratedPort = { migratedCalls++; UnusedTranscribePort },
            ),
        )

        assertTrue(tools.offersTranscription())
        assertEquals(1, legacyCalls)
        assertEquals(0, migratedCalls)
    }

    @Test fun `removed provider makes transcription unavailable without aborting phone tools`() {
        val fixture = fixture()
        val stt = controller(fixture, providers = FakeVoiceProviderAccountSource())
        stt.select(fixture.batchRef)

        val tools = phoneTools(stt)

        assertFalse(tools.offersTranscription())
        assertTrue(tools.definitions().any { it.name == "phone_observe" })
    }

    @Test fun `missing owned credentials make transcription unavailable without aborting phone tools`() {
        val fixture = fixture(VoiceAccount.VoiceOwned("missing-secret"))
        val missing = object : VoiceOwnedAccountSource {
            override fun read(secretId: String): VoiceCredentials? = null
            override fun credentialsConfigured(secretId: String) = false
            override fun configurationToken(secretId: String) = "missing"
        }
        val stt = controller(fixture, providers = FakeVoiceProviderAccountSource(), owned = missing)
        stt.select(fixture.batchRef)

        val tools = phoneTools(stt)

        assertFalse(tools.offersTranscription())
        assertTrue(tools.definitions().any { it.name == "phone_observe" })
    }

    @Test fun `corrupt catalog makes transcription unavailable without aborting phone tools`() {
        val fixture = fixture()
        controller(fixture).select(fixture.batchRef)
        fixture.backend.value = fixture.backend.value!!.replace(
            "\"schemaVersion\":1",
            "\"schemaVersion\":2",
        )
        val corrupt = VoiceCatalogStore(fixture.backend, migrated = true)
        val stt = controller(fixture.copy(catalog = corrupt))

        val tools = phoneTools(stt)

        assertFalse(tools.offersTranscription())
        assertTrue(tools.definitions().any { it.name == "phone_observe" })
    }
}
