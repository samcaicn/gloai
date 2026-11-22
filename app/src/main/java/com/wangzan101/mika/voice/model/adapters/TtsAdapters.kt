package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.OpenAiSpeechModel
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.model.ResolvedAccount
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface TtsAdapter {
    val protocol: TtsProtocol
    fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel
}

private class CategorizedTtsModel(
    private val delegate: TtsModel,
) : TtsModel {
    override val name: String get() = delegate.name
    override val capabilities: TtsCapabilities get() = delegate.capabilities
    override suspend fun synthesize(request: TtsRequest): TtsResponse = categorizedCall {
        delegate.synthesize(request)
    }
}

private class CategorizedStreamingTtsModel(
    private val delegate: StreamingTtsModel,
) : StreamingTtsModel {
    override val name: String get() = delegate.name
    override val capabilities: TtsCapabilities get() = delegate.capabilities
    override suspend fun synthesize(request: TtsRequest): TtsResponse = categorizedCall {
        delegate.synthesize(request)
    }

    override fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = try {
        delegate.streamAudio(request).catch { error ->
            if (error is CancellationException) throw error
            throw VoiceModelError.from(error)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw VoiceModelError.from(error)
    }
}

internal fun categorized(delegate: TtsModel): TtsModel = when (delegate) {
    is StreamingTtsModel -> CategorizedStreamingTtsModel(delegate)
    else -> CategorizedTtsModel(delegate)
}

private fun <T> categorizedCreation(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw VoiceModelError.from(error)
}

private suspend fun <T> categorizedCall(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw VoiceModelError.from(error)
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
        val delegate = categorizedCreation {
            OpenAiSpeechModel(
                baseUrl = provider.baseUrl,
                apiKey = model.account.credentials.apiKey.ifBlank { null },
                model = model.model.id,
                capabilities = capabilities,
                http = http,
                headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty(),
            )
        }
        return categorized(delegate)
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
        val delegate = categorizedCreation {
            GeminiTtsModel(
                baseUrl = provider.baseUrl,
                apiKey = key,
                model = model.model.id,
                capabilities = capabilities,
                http = http,
                headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty(),
            )
        }
        return categorized(delegate)
    }
}

private val ttsHttp: OkHttpClient
    get() = ProviderStore.modelHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

internal fun ResolvedVoiceModel.ttsBinding(protocol: TtsProtocol): TtsBinding {
    val binding = model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
    if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
    return binding
}
