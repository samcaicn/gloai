package com.wangzan101.mika.media

import android.content.ContextWrapper
import com.wangzan101.mika.core.cap.transcribe.TranscribeError
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.model.FakeVoiceProviderAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import com.wangzan101.mika.voice.model.voiceTestProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidTranscribePortTest {
    private data class ProtocolCase(
        val protocol: SttProtocol,
        val kind: ProviderConfig.Kind,
        val expectedPath: String,
        val response: String,
    )

    @Test fun `file transcription sends only the explicitly selected batch protocol`() = runTest {
        val cases = listOf(
            ProtocolCase(SttProtocol.AudioTranscriptions, ProviderConfig.Kind.OpenAiCompat, "/v1/audio/transcriptions", """{"text":"hello"}"""),
            ProtocolCase(
                SttProtocol.ChatInputAudio,
                ProviderConfig.Kind.OpenAiCompat,
                "/v1/chat/completions",
                """{"choices":[{"message":{"content":"hello"}}]}""",
            ),
            ProtocolCase(
                SttProtocol.GeminiInline,
                ProviderConfig.Kind.Gemini,
                "/v1beta/models/gemini:generateContent",
                """{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}""",
            ),
        )
        cases.forEach { case ->
            val server = MockWebServer().apply { start() }
            try {
                server.enqueue(MockResponse().setResponseCode(200).setBody(case.response))
                val baseUrl = if (case.kind == ProviderConfig.Kind.Gemini) server.url("").toString().trimEnd('/')
                else server.url("/v1").toString().trimEnd('/')
                val provider = voiceTestProvider(baseUrl = baseUrl, kind = case.kind)
                val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
                catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
                val modelId = if (case.protocol == SttProtocol.GeminiInline) "gemini" else case.protocol.name
                catalog.upsertModel(VoiceModel("provider:p1", modelId, modelId, stt = SttBinding(case.protocol)))
                val ref = VoiceModelRef("provider:p1", modelId, VoiceCapability.STT)
                val controller = voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider)).stt
                val port = AndroidTranscribePort(
                    context = ContextWrapper(null),
                    conversationId = "conversation",
                    choice = null,
                    stt = controller,
                    readFile = { path ->
                        assertEquals("/workspace/audio.wav", path)
                        byteArrayOf(1, 2, 3)
                    },
                )

                controller.select(ref)
                assertEquals(case.protocol.name, 0, server.requestCount)
                val transcript = port.transcribe("/workspace/audio.wav", "en")

                assertEquals("hello", transcript.text)
                assertEquals("en", transcript.language)
                assertEquals(case.protocol.name, case.expectedPath, server.takeRequest().path)
                assertEquals(case.protocol.name, 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun `late batch selection changes use the new binding without protocol rotation`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"new"}}]}"""))
            val provider = voiceTestProvider(baseUrl = server.url("/v1").toString().trimEnd('/'))
            val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
            catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
            catalog.upsertModel(VoiceModel("provider:p1", "batch", "Batch", stt = SttBinding(SttProtocol.AudioTranscriptions)))
            catalog.upsertModel(VoiceModel("provider:p1", "chat", "Chat", stt = SttBinding(SttProtocol.ChatInputAudio)))
            val audioRef = VoiceModelRef("provider:p1", "batch", VoiceCapability.STT)
            val chatRef = VoiceModelRef("provider:p1", "chat", VoiceCapability.STT)
            val controller = voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider)).stt
            controller.select(audioRef)
            val port = AndroidTranscribePort(
                ContextWrapper(null), "conversation", null, controller,
                readFile = { byteArrayOf(1, 2, 3) },
            )

            controller.select(chatRef)
            assertEquals(0, server.requestCount)
            assertEquals("new", port.transcribe("/workspace/audio.wav", null).text)

            assertEquals("/v1/chat/completions", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `system streaming auto and off never expose file transcription`() = runTest {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("funasr", "Fun-ASR", VoiceAccount.None))
        catalog.upsertModel(VoiceModel(
            "funasr", "streaming", "Streaming",
            stt = SttBinding(SttProtocol.FunAsrStreaming, endpoint = "ws://localhost:10095"),
        ))
        val streamingRef = VoiceModelRef("funasr", "streaming", VoiceCapability.STT)
        val controller = voiceTestEnvironment(catalog).stt
        var reads = 0
        val port = AndroidTranscribePort(
            ContextWrapper(null), "conversation", null, controller,
            readFile = { reads++; byteArrayOf(1) },
        )
        val selections = listOf(
            SpeechChoice.System to SttSelectionController.SYSTEM_STT_REF,
            null to streamingRef,
        )

        selections.forEach { (legacy, ref) ->
            if (legacy != null) controller.select(legacy) else controller.select(ref)
            assertThrows(TranscribeError.Unsupported::class.java) {
                kotlinx.coroutines.runBlocking { port.transcribe("/workspace/audio.wav", null) }
            }
        }
        controller.select(SpeechChoice.Auto)
        assertThrows(TranscribeError.Unsupported::class.java) {
            kotlinx.coroutines.runBlocking { port.transcribe("/workspace/audio.wav", null) }
        }
        controller.select(SpeechChoice.Off)
        assertThrows(TranscribeError.Unsupported::class.java) {
            kotlinx.coroutines.runBlocking { port.transcribe("/workspace/audio.wav", null) }
        }
        assertEquals(0, reads)
    }
}
