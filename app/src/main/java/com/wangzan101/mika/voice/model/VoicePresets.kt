package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.FunAsrProtocol
import java.net.URI

/**
 * A known kind of voice service and only the fields it needs. [VoicePresets.plan] turns it into ordinary catalog
 * entries, so a preset never adds a code path the custom form does not have.
 */
sealed interface VoicePreset {
    /** Volcano Engine speech: one account (an API key, or App ID + access token) for streaming input and realtime calls. */
    data class Volc(
        val apiKey: String = "",
        val appId: String = "",
        val accessToken: String = "",
        val stt: Boolean = true,
        val sttPlan: VolcSttPlan = VolcSttPlan.Duration,
        val realtime: Boolean = true,
    ) : VoicePreset

    /** Alibaba Cloud Bailian streaming recognition: one API key and a region. */
    data class Bailian(
        val apiKey: String,
        val region: BailianRegion = BailianRegion.Beijing,
        val workspace: String = "",
        val model: String = BailianProtocol.DEFAULT_MODEL,
    ) : VoicePreset

    /** Any service speaking the OpenAI audio APIs; each blank model is left out. */
    data class OpenAiCompatible(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val sttModel: String = "",
        val ttsModel: String = "",
        val realtimeModel: String = "",
    ) : VoicePreset

    /** A self-hosted streaming recognition server on the local network; no account. */
    data class SelfHostedStt(val url: String) : VoicePreset
}

enum class VolcSttPlan(val resourceId: String) {
    Duration(DoubaoProtocol.DURATION),
    Concurrent(DoubaoProtocol.CONCURRENT),
}

/** What a preset writes: one service, its secret if it has one, and the models it unlocks. */
data class VoicePresetPlan(
    val service: VoiceService,
    val credentials: VoiceCredentials?,
    val models: List<VoiceModel>,
) {
    val refs: List<VoiceModelRef>
        get() = models.flatMap { model -> model.capabilities.map { VoiceModelRef(model.serviceId, model.id, it) } }
}

object VoicePresets {
    const val VOLC_SERVICE = "volc"
    const val VOLC_STT_MODEL = "doubao-streaming"

    /** Builds the catalog entries; throws [VoiceModelError.IncompleteConfig] naming what is missing. */
    fun plan(preset: VoicePreset): VoicePresetPlan = when (preset) {
        is VoicePreset.Volc -> volc(preset)
        is VoicePreset.Bailian -> bailian(preset)
        is VoicePreset.OpenAiCompatible -> openAiCompatible(preset)
        is VoicePreset.SelfHostedStt -> selfHosted(preset)
    }

    /** Saves a plan; models are replaced in place so re-saving a preset edits it instead of adding copies. */
    fun apply(manager: VoiceAccountManager, catalog: VoiceCatalogStore, plan: VoicePresetPlan): List<VoiceModelRef> {
        manager.saveVoiceAccount(plan.service, plan.credentials)
        val keep = plan.models.map { it.id }.toSet()
        // 拉取到的、手填的模型不属于预设,换 key 时留着(下一次刷新会按新账号重拉)
        catalog.state.value.models.filter {
            it.serviceId == plan.service.id && it.id !in keep && it.origin == VoiceModelOrigin.Builtin
        }.forEach { stale ->
            stale.capabilities.forEach { catalog.deleteModel(VoiceModelRef(stale.serviceId, stale.id, it)) }
        }
        plan.models.forEach(catalog::upsertModel)
        return plan.refs
    }

    private fun volc(p: VoicePreset.Volc): VoicePresetPlan {
        val key = p.apiKey.trim()
        val appId = p.appId.trim()
        val token = p.accessToken.trim()
        val credentials = when {
            key.isNotEmpty() -> VoiceCredentials(apiKey = key)
            appId.isNotEmpty() && token.isNotEmpty() -> VoiceCredentials(appId = appId, accessKey = token)
            else -> throw VoiceModelError.IncompleteConfig("Enter an API key, or both App ID and access token")
        }
        if (!p.stt && !p.realtime) throw VoiceModelError.IncompleteConfig("Choose at least one capability")
        val service = VoiceService(VOLC_SERVICE, "Volcano Engine", VoiceAccount.VoiceOwned("preset:$VOLC_SERVICE"))
        val models = buildList {
            if (p.stt) add(
                VoiceModel(
                    VOLC_SERVICE, VOLC_STT_MODEL, "Doubao Streaming",
                    stt = SttBinding(SttProtocol.DoubaoStreaming, resourceId = p.sttPlan.resourceId),
                ),
            )
            if (p.realtime) add(
                VoiceModel(
                    VOLC_SERVICE, VolcRealtimeConfig.MODEL, "Doubao Realtime",
                    realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex, VolcRealtimeProtocol.ENDPOINT, VolcRealtimeConfig.DEFAULT_SPEAKER),
                ),
            )
        }
        return VoicePresetPlan(service, credentials, models)
    }

    private fun bailian(p: VoicePreset.Bailian): VoicePresetPlan {
        val key = p.apiKey.trim().ifEmpty { throw VoiceModelError.IncompleteConfig("Enter the API key") }
        val workspace = BailianProtocol.workspace(p.workspace)
            ?: throw VoiceModelError.IncompleteConfig("Business space ID is not valid")
        val model = p.model.trim().ifEmpty { BailianProtocol.DEFAULT_MODEL }
        val id = "bailian:${p.region.name.lowercase()}"
        val service = VoiceService(id, "Bailian ${p.region.name}", VoiceAccount.VoiceOwned("preset:$id"))
        val stt = VoiceModel(
            id, model, model,
            stt = SttBinding(
                SttProtocol.BailianStreaming, endpoint = p.region.endpoint(workspace), region = p.region.name, businessSpace = workspace,
            ),
        )
        return VoicePresetPlan(service, VoiceCredentials(apiKey = key), listOf(stt))
    }

    private fun openAiCompatible(p: VoicePreset.OpenAiCompatible): VoicePresetPlan {
        val base = p.baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw VoiceModelError.IncompleteConfig("Address must start with https://")
        val key = p.apiKey.trim().ifEmpty { throw VoiceModelError.IncompleteConfig("Enter the API key") }
        val id = "compat:${uri.host}${uri.path.orEmpty().trimEnd('/')}"
        val name = p.name.trim().ifEmpty { uri.host }
        val service = VoiceService(id, name, VoiceAccount.VoiceOwned("preset:$id"))
        val stt = p.sttModel.trim()
        val tts = p.ttsModel.trim()
        val realtime = p.realtimeModel.trim()
        if (stt.isEmpty() && tts.isEmpty() && realtime.isEmpty()) throw VoiceModelError.IncompleteConfig("Enter at least one model")
        // 同名模型合并成一条,多种能力挂在同一个模型上
        val byId = linkedMapOf<String, VoiceModel>()
        fun put(modelId: String, change: (VoiceModel) -> VoiceModel) {
            byId[modelId] = change(byId[modelId] ?: VoiceModel(id, modelId, modelId))
        }
        if (stt.isNotEmpty()) put(stt) { it.copy(stt = SttBinding(SttProtocol.AudioTranscriptions, endpoint = base)) }
        if (tts.isNotEmpty()) put(tts) { it.copy(tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = base)) }
        if (realtime.isNotEmpty()) put(realtime) {
            it.copy(realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime, realtimeEndpoint(uri), OpenAiCallProtocol.VOICE))
        }
        return VoicePresetPlan(service, VoiceCredentials(apiKey = key), byId.values.toList())
    }

    /** The OpenAI realtime socket lives next to the REST API: same host and path, `wss` and `/realtime`. */
    internal fun realtimeEndpoint(base: URI): String =
        "wss://${base.authority}${base.path.orEmpty().trimEnd('/')}/realtime"

    private fun selfHosted(p: VoicePreset.SelfHostedStt): VoicePresetPlan {
        val endpoint = FunAsrProtocol.normalize(p.url) ?: throw VoiceModelError.IncompleteConfig("Address is not valid")
        val host = runCatching { URI(endpoint).authority }.getOrNull().orEmpty().ifEmpty { endpoint }
        val id = "selfhosted:$host"
        val service = VoiceService(id, host, VoiceAccount.None)
        val model = VoiceModel(id, "funasr", "Fun-ASR Streaming", stt = SttBinding(SttProtocol.FunAsrStreaming, endpoint = endpoint))
        return VoicePresetPlan(service, null, listOf(model))
    }
}
