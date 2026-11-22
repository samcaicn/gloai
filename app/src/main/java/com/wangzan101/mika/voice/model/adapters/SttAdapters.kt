package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.AsrLanguage
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoCredentials
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.DoubaoSpeech
import com.wangzan101.mika.voice.DoubaoTransport
import com.wangzan101.mika.voice.FailedSession
import com.wangzan101.mika.voice.FunAsrProtocol
import com.wangzan101.mika.voice.LiveAsrTransport
import com.wangzan101.mika.voice.LiveSpeech
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.ProviderSpeech
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechServiceTarget
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.StreamingRecorder
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.SystemSpeechSession
import com.wangzan101.mika.voice.TranscribeMethod
import com.wangzan101.mika.voice.Transcriber
import com.wangzan101.mika.voice.model.ChatInputAudioData
import com.wangzan101.mika.voice.model.ResolvedAccount
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class SttHooks(
    val onNoResponse: () -> Unit = {},
    val onAnswered: (String?) -> Unit = {},
    val systemCapability: SystemCapability? = null,
    val rememberedService: String? = null,
    val attempts: List<String?>? = null,
    val compatibility: Boolean = false,
    val serviceWithoutMic: (String?, Boolean) -> SpeechServiceTarget? = { _, _ -> null },
    /** Audio fed from outside (a call) instead of the microphone; device recognition cannot use it. */
    val recorder: StreamingRecorder? = null,
)

typealias SttSessionFactory = (Context, ResolvedVoiceModel, String, SttHooks) -> SpeechSession

interface SttAdapter {
    val protocol: SttProtocol
    fun spec(model: ResolvedVoiceModel): SpeechEngineSpec
    fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks = SttHooks()): SpeechSession
}

private class CategorizedSpeechSession(
    private val delegate: SpeechSession,
) : SpeechSession {
    override val events: Flow<SpeechEvent> = delegate.events.map { event ->
        if (event is SpeechEvent.Failed && event.why is SpeechFailure.Provider) {
            SpeechEvent.Failed(SpeechFailure.Provider(VoiceModelError.from(event.why.error)))
        } else {
            event
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        throw VoiceModelError.from(error)
    }
    override val live: Boolean get() = delegate.live
    override val unlimited: Boolean get() = delegate.unlimited
    override fun release() = delegate.release()
    override fun cancel() = delegate.cancel()
}

internal fun categorizedSession(factory: () -> SpeechSession): SpeechSession = try {
    CategorizedSpeechSession(factory())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw VoiceModelError.from(error)
}

class AndroidSystemSttAdapter(
    private val sessionFactory: SttSessionFactory = defaultSystemSession,
) : SttAdapter {
    override val protocol = SttProtocol.AndroidSystem

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        model.sttBinding(protocol)
        return SpeechEngineSpec.System(onDevice = false)
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        if (hooks.recorder != null) return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone))
        val capability = hooks.systemCapability ?: SystemSpeech.capability(context)
        if (!capability.usable) {
            return FailedSession(SpeechFailure.Unavailable(
                if (capability.available) SpeechEngineSpec.Why.SystemNoResponse else SpeechEngineSpec.Why.SystemGone,
            ))
        }
        return categorizedSession { sessionFactory(context, model, languageTag(context, language), hooks) }
    }
}

class ProviderSttAdapter(
    override val protocol: SttProtocol,
    private val sessionFactory: SttSessionFactory = defaultProviderSession,
) : SttAdapter {
    init {
        require(protocol in PROVIDER_PROTOCOLS) { "Not a provider STT protocol" }
    }

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        val binding = model.sttBinding(protocol)
        val method = when (protocol) {
            SttProtocol.AudioTranscriptions -> TranscribeMethod.AudioTranscriptions
            SttProtocol.GeminiInline -> TranscribeMethod.GeminiInline
            SttProtocol.ChatInputAudio -> when {
                binding.chatInputData == ChatInputAudioData.Base64 && binding.includeTranscriptionInstruction ->
                    TranscribeMethod.ChatInputAudio
                binding.chatInputData == ChatInputAudioData.DataUri && binding.includeTranscriptionInstruction ->
                    TranscribeMethod.ChatInputAudioUri
                binding.chatInputData == ChatInputAudioData.DataUri && !binding.includeTranscriptionInstruction ->
                    TranscribeMethod.ChatInputAudioOnly
                else -> throw VoiceModelError.IncompleteConfig(
                    "Base64 chat input audio requires the transcription instruction",
                )
            }
            else -> throw VoiceModelError.Unsupported(VoiceCapability.STT, protocol.name)
        }
        return SpeechEngineSpec.Provider(
            provider = model.legacyProvider(binding.endpoint, forceGemini = protocol == SttProtocol.GeminiInline),
            model = model.model.id,
            method = method,
        )
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        return categorizedSession { sessionFactory(context, model, languageTag(context, language), hooks) }
    }

    companion object {
        private val PROVIDER_PROTOCOLS = setOf(
            SttProtocol.AudioTranscriptions,
            SttProtocol.ChatInputAudio,
            SttProtocol.GeminiInline,
        )
    }
}

class DoubaoSttAdapter(
    private val sessionFactory: SttSessionFactory = defaultDoubaoSession,
) : SttAdapter {
    override val protocol = SttProtocol.DoubaoStreaming

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        val binding = model.sttBinding(protocol)
        return SpeechEngineSpec.Doubao(binding.resourceId.ifBlank { DoubaoProtocol.DURATION })
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        model.doubaoCredentials()
        return categorizedSession { sessionFactory(context, model, languageTag(context, language), hooks) }
    }
}

class FunAsrSttAdapter(
    private val sessionFactory: SttSessionFactory = defaultFunAsrSession,
) : SttAdapter {
    override val protocol = SttProtocol.FunAsrStreaming

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        val binding = model.sttBinding(protocol)
        val endpoint = FunAsrProtocol.normalize(binding.endpoint)
            ?: throw VoiceModelError.IncompleteConfig("Fun-ASR requires a valid WebSocket endpoint")
        return SpeechEngineSpec.FunAsr(endpoint)
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        return categorizedSession { sessionFactory(context, model, languageTag(context, language), hooks) }
    }
}

class BailianSttAdapter(
    private val sessionFactory: SttSessionFactory = defaultBailianSession,
) : SttAdapter {
    override val protocol = SttProtocol.BailianStreaming

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        val binding = model.sttBinding(protocol)
        val region = bailianRegion(binding)
        val workspace = BailianProtocol.workspace(binding.businessSpace)
            ?: throw VoiceModelError.IncompleteConfig("Bailian business space is invalid")
        return SpeechEngineSpec.Bailian(model.model.id, region, workspace)
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        if (model.account.credentials.apiKey.isBlank()) throw VoiceModelError.MissingCredentials()
        return categorizedSession { sessionFactory(context, model, languageTag(context, language), hooks) }
    }
}

private val defaultSystemSession: SttSessionFactory = { context, _, tag, hooks ->
    val capability = hooks.systemCapability ?: SystemSpeech.capability(context)
    SystemSpeechSession(
        context = context,
        onDevice = capability.onDevice,
        languageTag = tag,
        onNoResponse = hooks.onNoResponse,
        onAnswered = hooks.onAnswered,
        attempts = hooks.attempts ?: SystemSpeech.attempts(
            SystemSpeech.services(context), hooks.rememberedService, hooks.compatibility,
        ),
        compatibility = hooks.compatibility,
        serviceWithoutMic = hooks.serviceWithoutMic,
    )
}

private val defaultProviderSession: SttSessionFactory = { _, model, tag, hooks ->
    val spec = model.speechSpec<SpeechEngineSpec.Provider>()
    val headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty()
    val key = model.account.credentials.apiKey.ifBlank { null }
    val transcriber = Transcriber(ProviderStore.modelHttp, headers)
    ProviderSpeech(hooks.recorder ?: PcmRecorder(), { wav ->
        transcriber.transcribe(
            spec.provider,
            key,
            model.model.id,
            spec.method,
            wav,
            SystemSpeech.isoCode(tag),
        )
    })
}

private val defaultDoubaoSession: SttSessionFactory = { _, model, _, hooks ->
    val spec = model.speechSpec<SpeechEngineSpec.Doubao>()
    val credentials = model.doubaoCredentials()
    val endpoint = model.model.stt!!.endpoint.ifBlank { DoubaoProtocol.ENDPOINT }
    DoubaoSpeech(hooks.recorder ?: PcmRecorder(streaming = true), { DoubaoTransport(endpoint = endpoint).connect(credentials, spec.resourceId) })
}

private val defaultFunAsrSession: SttSessionFactory = { _, model, tag, hooks ->
    val spec = model.speechSpec<SpeechEngineSpec.FunAsr>()
    LiveSpeech(
        hooks.recorder ?: PcmRecorder(streaming = true, unbounded = true),
        FunAsrProtocol(asrLanguage(tag)),
        { LiveAsrTransport().connect(spec.url) },
    )
}

private val defaultBailianSession: SttSessionFactory = { _, model, tag, hooks ->
    val spec = model.speechSpec<SpeechEngineSpec.Bailian>()
    val key = model.account.credentials.apiKey
    val endpoint = model.model.stt!!.endpoint.ifBlank { spec.region.endpoint(spec.workspace) }
    LiveSpeech(
        hooks.recorder ?: PcmRecorder(streaming = true, unbounded = true),
        BailianProtocol(model.model.id, asrLanguage(tag)),
        { LiveAsrTransport().connect(endpoint, mapOf("Authorization" to "Bearer $key")) },
    )
}

internal fun languageTag(context: Context, language: String?): String =
    language ?: SystemSpeech.speechTag(AppLanguage.current(context))

private fun asrLanguage(tag: String): AsrLanguage = AsrLanguage.of(SystemSpeech.isoCode(tag))

private fun bailianRegion(binding: SttBinding): BailianRegion =
    BailianRegion.entries.firstOrNull { it.name.equals(binding.region, ignoreCase = true) }
        ?: if (binding.region.isBlank()) BailianRegion.Beijing
        else throw VoiceModelError.IncompleteConfig("Bailian region is invalid")

internal fun ResolvedVoiceModel.sttBinding(protocol: SttProtocol): SttBinding {
    val binding = model.stt ?: throw VoiceModelError.Unsupported(VoiceCapability.STT)
    if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.STT, binding.protocol.name)
    return binding
}

private inline fun <reified T : SpeechEngineSpec> ResolvedVoiceModel.speechSpec(): T {
    val adapter = when (model.stt!!.protocol) {
        SttProtocol.AndroidSystem -> AndroidSystemSttAdapter()
        SttProtocol.AudioTranscriptions, SttProtocol.ChatInputAudio, SttProtocol.GeminiInline ->
            ProviderSttAdapter(model.stt!!.protocol)
        SttProtocol.DoubaoStreaming -> DoubaoSttAdapter()
        SttProtocol.FunAsrStreaming -> FunAsrSttAdapter()
        SttProtocol.BailianStreaming -> BailianSttAdapter()
        else -> VendorSttAdapter(model.stt!!.protocol)
    }
    return adapter.spec(this) as T
}

private fun ResolvedVoiceModel.doubaoCredentials(): DoubaoCredentials {
    val value = account.credentials
    return DoubaoCredentials(value.apiKey, value.appId, value.accessKey).also {
        if (!it.valid) throw VoiceModelError.MissingCredentials()
    }
}

internal fun ResolvedVoiceModel.legacyProvider(endpoint: String, forceGemini: Boolean = false): Provider {
    val existing = account as? ResolvedAccount.ExistingProvider
    val baseUrl = existing?.provider?.baseUrl ?: endpoint
    if (baseUrl.isBlank()) throw VoiceModelError.IncompleteConfig("Voice account endpoint is missing")
    val kind = if (forceGemini) ProviderConfig.Kind.Gemini else existing?.provider?.kind ?: ProviderConfig.Kind.OpenAiCompat
    return existing?.provider?.copy(kind = kind) ?: Provider(
        id = service.id,
        name = service.name,
        kind = kind,
        baseUrl = baseUrl,
    )
}
