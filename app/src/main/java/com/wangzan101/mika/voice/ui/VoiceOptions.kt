package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.voice.model.ProviderVoiceOffers
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceVerification
import com.wangzan101.mika.settings.ui.models.use

/** What users care about when picking a voice model; never a protocol name. */
enum class VoiceTrait { Live, AfterSpeech, Free, StreamingRead, WholeRead, Interruptible, PhoneOnly }

/** Where a choice comes from, shown under its name. */
sealed interface VoiceSource {
    data object Device : VoiceSource
    data class ChatAccount(val name: String) : VoiceSource
    data class Service(val name: String) : VoiceSource
}

/**
 * One row on a voice use page. [ref] is the catalog reference; for a chat-account offer that is not in the catalog
 * yet, [provider] and [offer] are set and picking it adopts the offer first.
 */
data class VoiceOption(
    val ref: VoiceModelRef,
    val model: VoiceModel,
    val source: VoiceSource,
    val traits: List<VoiceTrait>,
    val verified: Boolean,
    val provider: Provider? = null,
    val offer: VoiceModel? = null,
)

/** Every model that can do [capability]: the catalog's first, then what connected chat accounts offer on their own. */
fun voiceOptions(capability: VoiceCapability, snapshot: VoiceCatalogSnapshot, providers: List<Provider>): List<VoiceOption> {
    val byProvider = providers.associateBy { it.id }
    val catalog = snapshot.models.filter { it.supports(capability) }.mapNotNull { model ->
        val service = snapshot.services.firstOrNull { it.id == model.serviceId } ?: return@mapNotNull null
        val ref = VoiceModelRef(model.serviceId, model.id, capability)
        val source = when (val account = service.account) {
            is VoiceAccount.ExistingProvider -> VoiceSource.ChatAccount(byProvider[account.providerId]?.name ?: service.name)
            else -> if (ref == SttSelectionController.SYSTEM_STT_REF || ref == TtsSelectionController.SYSTEM_TTS_REF) {
                VoiceSource.Device
            } else VoiceSource.Service(service.name)
        }
        val verified = snapshot.verifications.any { it.ref == ref && it.scope.isEmpty() && it.status == VoiceVerification.Verified }
        VoiceOption(ref, model, source, model.traits(capability), verified)
    }
    val offered = providers.flatMap { provider ->
        ProviderVoiceOffers.offers(provider).filter { offer ->
            offer.supports(capability) && catalog.none { it.ref.serviceId == offer.serviceId && it.ref.modelId == offer.id }
        }.map { offer ->
            VoiceOption(
                VoiceModelRef(offer.serviceId, offer.id, capability), offer, VoiceSource.ChatAccount(provider.name),
                offer.traits(capability), verified = false, provider = provider, offer = offer,
            )
        }
    }
    return (catalog + offered).sortedBy { if (it.source == VoiceSource.Device) 0 else 1 }
}

private fun VoiceModel.supports(capability: VoiceCapability) = capability in capabilities

internal fun VoiceModel.traits(capability: VoiceCapability): List<VoiceTrait> = when (capability) {
    VoiceCapability.STT -> when (stt?.protocol) {
        SttProtocol.AndroidSystem -> listOf(VoiceTrait.Live, VoiceTrait.Free)
        SttProtocol.DoubaoStreaming, SttProtocol.FunAsrStreaming, SttProtocol.BailianStreaming -> listOf(VoiceTrait.Live)
        SttProtocol.AudioTranscriptions, SttProtocol.ChatInputAudio, SttProtocol.GeminiInline,
        SttProtocol.ElevenLabsUpload, SttProtocol.DeepgramUpload, SttProtocol.AzureShortAudio,
        SttProtocol.TencentSentence, SttProtocol.XfyunDictation,
        -> listOf(VoiceTrait.AfterSpeech)
        null -> emptyList()
    }
    VoiceCapability.TTS -> when (tts?.protocol) {
        TtsProtocol.AndroidSystem -> listOf(VoiceTrait.Free)
        TtsProtocol.OpenAiSpeech -> listOf(VoiceTrait.StreamingRead)
        TtsProtocol.GeminiTts -> listOf(VoiceTrait.WholeRead)
        null -> emptyList()
        else -> listOf(VoiceTrait.WholeRead)
    }
    VoiceCapability.REALTIME -> when (realtime?.protocol) {
        // 应用内语音通话目前只接双向实时协议,其余的只能用于 AI 接打电话
        RealtimeProtocol.VolcDuplex -> listOf(VoiceTrait.Interruptible)
        RealtimeProtocol.OpenAiRealtime -> listOf(VoiceTrait.Interruptible, VoiceTrait.PhoneOnly)
        null -> emptyList()
    }
}
