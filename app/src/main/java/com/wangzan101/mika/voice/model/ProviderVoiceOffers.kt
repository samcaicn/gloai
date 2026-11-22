package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A voice model a connected chat account can provide but that is not in the voice catalog yet. */
data class ProviderVoiceOffer(val provider: Provider, val model: VoiceModel)

/**
 * Voice models a chat account provides by itself, so users never register the same account twice.
 * Only official hosts with a published audio API are matched; anything else goes through the custom form.
 */
object ProviderVoiceOffers {
    fun serviceId(providerId: String) = "provider:$providerId"

    fun offers(provider: Provider): List<VoiceModel> {
        val url = provider.baseUrl.toHttpUrlOrNull() ?: return emptyList()
        if (!url.isHttps) return emptyList()
        val service = serviceId(provider.id)
        return when {
            url.host == OPENAI_HOST && provider.kind in OPENAI_KINDS -> listOf(
                VoiceModel(service, OPENAI_STT, OPENAI_STT, stt = SttBinding(SttProtocol.AudioTranscriptions)),
                VoiceModel(service, OPENAI_TTS, OPENAI_TTS, tts = TtsBinding(TtsProtocol.OpenAiSpeech)),
                VoiceModel(service, OPENAI_REALTIME, OPENAI_REALTIME, realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime)),
            )
            url.host == GEMINI_HOST && provider.kind == ProviderConfig.Kind.Gemini -> listOf(
                VoiceModel(service, GEMINI_STT, GEMINI_STT, stt = SttBinding(SttProtocol.GeminiInline)),
                VoiceModel(service, GEMINI_TTS, GEMINI_TTS, tts = TtsBinding(TtsProtocol.GeminiTts)),
            )
            else -> emptyList()
        }
    }

    /** Offers from [providers] whose capability is not already in the catalog for that model. */
    fun pending(providers: List<Provider>, snapshot: VoiceCatalogSnapshot): List<ProviderVoiceOffer> =
        providers.flatMap { provider ->
            offers(provider).filter { offer ->
                val existing = snapshot.models.firstOrNull { it.serviceId == offer.serviceId && it.id == offer.id }
                existing == null || !existing.capabilities.containsAll(offer.capabilities)
            }.map { ProviderVoiceOffer(provider, it) }
        }

    /**
     * Writes [offer] into the catalog under the account's provider service and returns the capability reference.
     * An existing service keeps its identity (so verifications survive); an existing model keeps its bindings.
     */
    fun adopt(catalog: VoiceCatalogStore, provider: Provider, offer: VoiceModel, capability: VoiceCapability): VoiceModelRef {
        require(capability in offer.capabilities) { "Offer does not provide $capability" }
        val service = serviceId(provider.id)
        val snapshot = catalog.state.value
        if (snapshot.services.none { it.id == service }) {
            catalog.upsertService(VoiceService(service, provider.name, VoiceAccount.ExistingProvider(provider.id)))
        }
        val existing = snapshot.models.firstOrNull { it.serviceId == service && it.id == offer.id }
        val merged = existing?.copy(
            stt = existing.stt ?: offer.stt,
            tts = existing.tts ?: offer.tts,
            realtime = existing.realtime ?: offer.realtime,
        ) ?: offer.copy(serviceId = service)
        if (merged != existing) catalog.upsertModel(merged)
        return VoiceModelRef(service, offer.id, capability)
    }

    private const val OPENAI_HOST = "api.openai.com"
    private const val GEMINI_HOST = "generativelanguage.googleapis.com"
    private val OPENAI_KINDS = setOf(ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses)
    private const val OPENAI_STT = "gpt-4o-transcribe"
    private const val OPENAI_TTS = "gpt-4o-mini-tts"
    private const val OPENAI_REALTIME = "gpt-realtime"
    private const val GEMINI_STT = "gemini-2.5-flash"
    private const val GEMINI_TTS = "gemini-2.5-flash-preview-tts"
}
