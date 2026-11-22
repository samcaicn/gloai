package com.wangzan101.mika.voice.model.adapters

import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.ProfileRealtimeProvider
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface RealtimeAdapter {
    val protocol: RealtimeProtocol
    fun create(model: ResolvedVoiceModel): CallRealtimeProvider
}

class VolcDuplexRealtimeAdapter(
    private val delegate: CallRealtimeProvider = ProfileRealtimeProvider(),
) : RealtimeAdapter {
    override val protocol = RealtimeProtocol.VolcDuplex
    override fun create(model: ResolvedVoiceModel): CallRealtimeProvider =
        ConfiguredRealtimeProvider(model, model.realtimeBinding(protocol), delegate)
}

class OpenAiRealtimeAdapter(
    private val delegate: CallRealtimeProvider = ProfileRealtimeProvider(),
) : RealtimeAdapter {
    override val protocol = RealtimeProtocol.OpenAiRealtime
    override fun create(model: ResolvedVoiceModel): CallRealtimeProvider =
        ConfiguredRealtimeProvider(model, model.realtimeBinding(protocol), delegate)
}

private class CategorizedRealtimeSession(
    private val delegate: CallRealtimeSession,
) : CallRealtimeSession by delegate {
    override val events: Flow<VolcRealtimeEvent> = delegate.events.map { event ->
        if (event is VolcRealtimeEvent.Failed) throw VoiceModelError.from(event.error)
        event
    }.catch { error ->
        if (error is CancellationException) throw error
        throw VoiceModelError.from(error)
    }
}

internal fun RealtimeBinding.callProtocol(): CallVoiceProtocol = when (protocol) {
    RealtimeProtocol.VolcDuplex -> CallVoiceProtocol.VOLC_DUPLEX
    RealtimeProtocol.OpenAiRealtime -> CallVoiceProtocol.OPENAI_REALTIME
}

internal fun RealtimeBinding.defaultEndpoint(): String = when (protocol) {
    RealtimeProtocol.VolcDuplex -> VolcRealtimeProtocol.ENDPOINT
    RealtimeProtocol.OpenAiRealtime -> OpenAiCallProtocol.ENDPOINT
}

internal fun RealtimeBinding.defaultSpeaker(): String = when (protocol) {
    RealtimeProtocol.VolcDuplex -> VolcRealtimeConfig.DEFAULT_SPEAKER
    RealtimeProtocol.OpenAiRealtime -> OpenAiCallProtocol.VOICE
}

/** The display/connection profile for a Realtime model; secrets never pass through it. */
internal fun VoiceModel.realtimeProfile(
    credentialsConfigured: Boolean,
    connectionTested: Boolean = false,
): CallVoiceProfile {
    val binding = realtime ?: throw VoiceModelError.Unsupported(VoiceCapability.REALTIME)
    return CallVoiceProfile(
        id = id,
        name = displayName,
        protocol = binding.callProtocol(),
        endpoint = binding.endpoint.ifBlank { binding.defaultEndpoint() },
        model = id,
        speaker = binding.speaker.ifBlank { binding.defaultSpeaker() },
        credentialsConfigured = credentialsConfigured,
        connectionTested = connectionTested,
    )
}

private class ConfiguredRealtimeProvider(
    private val resolved: ResolvedVoiceModel,
    private val binding: RealtimeBinding,
    private val delegate: CallRealtimeProvider,
) : CallRealtimeProvider {
    private val profile = resolved.model.realtimeProfile(credentialsConfigured = true)
    private val credentials = resolved.account.credentials.let {
        VolcRealtimeCredentials(it.apiKey, it.appId, it.accessKey)
    }

    init {
        if (binding.protocol == RealtimeProtocol.OpenAiRealtime && profile.endpoint != OpenAiCallProtocol.ENDPOINT) {
            throw VoiceModelError.IncompleteConfig("OpenAI Realtime endpoint must be ${OpenAiCallProtocol.ENDPOINT}")
        }
        if (!credentials.valid || (binding.protocol == RealtimeProtocol.OpenAiRealtime && resolved.account.credentials.apiKey.isBlank())) {
            throw VoiceModelError.MissingCredentials()
        }
    }

    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        try {
            delegate.validate(this.profile, systemPrompt, enablePhoneDtmf)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw VoiceModelError.from(error)
        }
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
    ): CallRealtimeSession = try {
        CategorizedRealtimeSession(delegate.connect(this.profile, this.credentials, systemPrompt, enablePhoneDtmf))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw VoiceModelError.from(error)
    }
}

private fun ResolvedVoiceModel.realtimeBinding(protocol: RealtimeProtocol): RealtimeBinding {
    val binding = model.realtime ?: throw VoiceModelError.Unsupported(VoiceCapability.REALTIME)
    if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.REALTIME, binding.protocol.name)
    return binding
}
