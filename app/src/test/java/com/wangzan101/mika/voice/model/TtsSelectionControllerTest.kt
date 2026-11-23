package com.wangzan101.mika.voice.model

import android.content.ContextWrapper
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.connect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsSelectionControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ContextWrapper(null)

    @Test fun `migrated TTS does not require a legacy chat role marker`() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No credentials in this fixture") })
        val p = store.connect("Speech", ProviderConfig.Kind.OpenAiCompat, "https://tts.test/v1", null, emptyList())
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:${p.id}", "Speech", VoiceAccount.ExistingProvider(p.id)))
        catalog.upsertModel(VoiceModel("provider:${p.id}", "voice-1", "Voice", tts = TtsBinding(TtsProtocol.OpenAiSpeech)))
        val ttsRef = VoiceModelRef("provider:${p.id}", "voice-1", VoiceCapability.TTS)
        val controller = voiceTestEnvironment(catalog, ProviderStoreVoiceAccountSource(store)).tts

        val model = controller.requireModel(ttsRef)
        assertTrue(model.account is ResolvedAccount.ExistingProvider)
        val created = controller.createTts(context, ttsRef, TtsCapabilities(formats = setOf("wav")))
        assertTrue(created is StreamingTtsModel)
        assertEquals("voice-1", created.name)
        assertFalse(store.provider(p.id)!!.ttsModels.contains("voice-1"))
    }

    @Test fun `gemini TTS creates a non-streaming model without any legacy marker`() {
        val provider = voiceTestProvider(kind = ProviderConfig.Kind.Gemini)
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Gemini", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "gemini-voice", "Gemini", tts = TtsBinding(TtsProtocol.GeminiTts)))
        val ref = VoiceModelRef("provider:p1", "gemini-voice", VoiceCapability.TTS)
        val controller = voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider)).tts

        val created = controller.createTts(context, ref, TtsCapabilities(formats = setOf("wav")))

        assertFalse(created is StreamingTtsModel)
        assertEquals("gemini-voice", created.name)
    }

    @Test fun `selecting a TTS model persists only its explicit ref`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "voice-1", "Voice", tts = TtsBinding(TtsProtocol.OpenAiSpeech)))
        val ref = VoiceModelRef("provider:p1", "voice-1", VoiceCapability.TTS)
        val controller = voiceTestEnvironment(catalog).tts

        controller.select(ref)
        assertEquals(ref, controller.current())

        controller.select(null)
        assertNull(controller.current())
    }

    @Test fun `requireModel rejects models without a TTS binding`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "whisper-1", "Whisper", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        val controller = voiceTestEnvironment(catalog).tts

        val error = assertThrows(VoiceModelError.Unsupported::class.java) {
            controller.requireModel(VoiceModelRef("provider:p1", "whisper-1", VoiceCapability.TTS))
        }
        assertEquals(VoiceCapability.TTS, error.requested)
    }

    @Test fun `environment shares its catalog and registry with the TTS controller`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val environment = voiceTestEnvironment(catalog)

        assertTrue(environment.tts.catalog === catalog)
    }
}
