package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.OpenAiSpeechModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.model.ResolvedAccount
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface TtsAdapter {
    val protocol: TtsProtocol
    fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel
}

class OpenAiSpeechTtsAdapter(
    private val http: OkHttpClient = ttsHttp,
) : TtsAdapter {
    override val protocol = TtsProtocol.OpenAiSpeech

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.ttsBinding(protocol)
        val provider = model.legacyProvider(binding.endpoint)
        if (OpenAiSpeechModel.endpointOf(provider.baseUrl) == null) {
            throw VoiceModelError.IncompleteConfig("OpenAI Speech endpoint is invalid")
        }
        return OpenAiSpeechModel(
            baseUrl = provider.baseUrl,
            apiKey = model.account.credentials.apiKey.ifBlank { null },
            model = model.model.id,
            capabilities = capabilities,
            http = http,
            headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty(),
        )
    }
}

class GeminiTtsAdapter(
    private val http: OkHttpClient = ttsHttp,
) : TtsAdapter {
    override val protocol = TtsProtocol.GeminiTts

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.ttsBinding(protocol)
        val provider = model.legacyProvider(binding.endpoint, forceGemini = true)
        val key = model.account.credentials.apiKey
        if (key.isBlank()) throw VoiceModelError.MissingCredentials()
        return GeminiTtsModel(
            baseUrl = provider.baseUrl,
            apiKey = key,
            model = model.model.id,
            capabilities = capabilities,
            http = http,
            headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty(),
        )
    }
}

private val ttsHttp: OkHttpClient
    get() = ProviderStore.modelHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

private fun ResolvedVoiceModel.ttsBinding(protocol: TtsProtocol): TtsBinding {
    val binding = model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
    if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
    return binding
}
