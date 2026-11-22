package com.wangzan101.mika.voice.model.adapters

import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.ProfileRealtimeProvider
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError

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

private class ConfiguredRealtimeProvider(
    private val resolved: ResolvedVoiceModel,
    private val binding: RealtimeBinding,
    private val delegate: CallRealtimeProvider,
) : CallRealtimeProvider {
    private val profile = CallVoiceProfile(
        id = resolved.model.id,
        name = resolved.model.displayName,
        protocol = when (binding.protocol) {
            RealtimeProtocol.VolcDuplex -> CallVoiceProtocol.VOLC_DUPLEX
            RealtimeProtocol.OpenAiRealtime -> CallVoiceProtocol.OPENAI_REALTIME
        },
        endpoint = binding.endpoint.ifBlank {
            when (binding.protocol) {
                RealtimeProtocol.VolcDuplex -> VolcRealtimeProtocol.ENDPOINT
                RealtimeProtocol.OpenAiRealtime -> OpenAiCallProtocol.ENDPOINT
            }
        },
        model = resolved.model.id,
        speaker = binding.speaker.ifBlank {
            when (binding.protocol) {
                RealtimeProtocol.VolcDuplex -> VolcRealtimeConfig.DEFAULT_SPEAKER
                RealtimeProtocol.OpenAiRealtime -> OpenAiCallProtocol.VOICE
            }
        },
        credentialsConfigured = true,
    )
    private val credentials = resolved.account.credentials.let {
        VolcRealtimeCredentials(it.apiKey, it.appId, it.accessKey)
    }

    init {
        if (!credentials.valid || (binding.protocol == RealtimeProtocol.OpenAiRealtime && resolved.account.credentials.apiKey.isBlank())) {
            throw VoiceModelError.MissingCredentials()
        }
    }

    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        delegate.validate(this.profile, systemPrompt, enablePhoneDtmf)
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
    ): CallRealtimeSession = delegate.connect(this.profile, this.credentials, systemPrompt, enablePhoneDtmf)
}

private fun ResolvedVoiceModel.realtimeBinding(protocol: RealtimeProtocol): RealtimeBinding {
    val binding = model.realtime ?: throw VoiceModelError.Unsupported(VoiceCapability.REALTIME)
    if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.REALTIME, binding.protocol.name)
    return binding
}
