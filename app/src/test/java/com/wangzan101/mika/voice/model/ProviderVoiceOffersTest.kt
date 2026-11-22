package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderVoiceOffersTest {
    private fun provider(kind: ProviderConfig.Kind, baseUrl: String, id: String = "p1") =
        Provider(id = id, name = "Acct", kind = kind, baseUrl = baseUrl)

    @Test fun `official openai account offers transcription, speech and realtime`() {
        val offers = ProviderVoiceOffers.offers(provider(ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1"))
        assertEquals(setOf(VoiceCapability.STT, VoiceCapability.TTS, VoiceCapability.REALTIME), offers.flatMap { it.capabilities }.toSet())
        assertTrue(offers.all { it.serviceId == "provider:p1" })
        assertEquals(SttProtocol.AudioTranscriptions, offers.single { it.stt != null }.stt?.protocol)
        assertEquals(TtsProtocol.OpenAiSpeech, offers.single { it.tts != null }.tts?.protocol)
        assertEquals(RealtimeProtocol.OpenAiRealtime, offers.single { it.realtime != null }.realtime?.protocol)
    }

    @Test fun `gemini account offers inline recognition and tts`() {
        val offers = ProviderVoiceOffers.offers(provider(ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com"))
        assertEquals(SttProtocol.GeminiInline, offers.single { it.stt != null }.stt?.protocol)
        assertEquals(TtsProtocol.GeminiTts, offers.single { it.tts != null }.tts?.protocol)
        assertTrue(offers.none { it.realtime != null })
    }

    @Test fun `unknown hosts and chat-only kinds offer nothing`() {
        assertEquals(emptyList<VoiceModel>(), ProviderVoiceOffers.offers(provider(ProviderConfig.Kind.OpenAiCompat, "https://relay.example.com/v1")))
        assertEquals(emptyList<VoiceModel>(), ProviderVoiceOffers.offers(provider(ProviderConfig.Kind.Anthropic, "https://api.anthropic.com")))
        assertEquals(emptyList<VoiceModel>(), ProviderVoiceOffers.offers(provider(ProviderConfig.Kind.ChatGpt, "https://api.openai.com/v1")))
        assertEquals(emptyList<VoiceModel>(), ProviderVoiceOffers.offers(provider(ProviderConfig.Kind.OpenAiCompat, "http://api.openai.com/v1")))
    }

    @Test fun `adopt adds the provider service and model once and keeps existing bindings`() {
        val store = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val p = provider(ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1")
        val offer = ProviderVoiceOffers.offers(p).single { it.tts != null }
        val ref = ProviderVoiceOffers.adopt(store, p, offer, VoiceCapability.TTS)
        assertEquals(VoiceModelRef("provider:p1", offer.id, VoiceCapability.TTS), ref)
        val service = store.state.value.services.single { it.id == "provider:p1" }
        assertEquals(VoiceAccount.ExistingProvider("p1"), service.account)
        assertEquals("Acct", service.name)
        ProviderVoiceOffers.adopt(store, p, offer, VoiceCapability.TTS)
        assertEquals(1, store.state.value.models.count { it.serviceId == "provider:p1" && it.id == offer.id })
    }

    @Test fun `offers already in the catalog are not offered again`() {
        val store = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val p = provider(ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com")
        val stt = ProviderVoiceOffers.offers(p).single { it.stt != null }
        ProviderVoiceOffers.adopt(store, p, stt, VoiceCapability.STT)
        val pending = ProviderVoiceOffers.pending(listOf(p), store.state.value)
        assertTrue(pending.none { it.model.id == stt.id && it.model.stt != null })
        assertTrue(pending.any { it.model.tts != null && it.provider.id == "p1" })
    }
}
