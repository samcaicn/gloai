package com.wangzan101.mika.calls

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface CallRealtimeSession : AutoCloseable {
    val events: Flow<VolcRealtimeEvent>
    val playbackTruncation: CallPlaybackTruncation? get() = null
    val inputSampleRate: Int get() = VolcRealtimeClient.INPUT_SAMPLE_RATE
    val outputSampleRate: Int get() = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
    val inputChunkBytes: Int get() = VolcRealtimeClient.INPUT_CHUNK_BYTES
    fun sendPcm16(bytes: ByteArray, byteCount: Int = bytes.size): Boolean
    fun sendGreeting(text: String): Boolean
    fun setMuted(muted: Boolean): Boolean
    fun sendToolResult(callId: String, result: JsonObject): Boolean = false
    override fun close()
}

interface CallRealtimeProvider {
    /** Local validation only: invalid config must fail before an outgoing carrier call is placed. */
    fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean = false) {}

    suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean = false,
    ): CallRealtimeSession
}

class ProfileRealtimeProvider : CallRealtimeProvider {
    private fun config(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean): VolcRealtimeConfig {
        if (profile.protocol == CallVoiceProtocol.OPENAI_REALTIME) require(profile.endpoint == OpenAiCallProtocol.ENDPOINT)
        return VolcRealtimeConfig(systemPrompt = systemPrompt, speaker = profile.speaker, model = profile.model, enablePhoneDtmf = enablePhoneDtmf, protocol = profile.protocol)
    }

    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        config(profile, systemPrompt, enablePhoneDtmf)
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
    ): CallRealtimeSession {
        return VolcRealtimeClient(endpoint = profile.endpoint).connect(credentials, config(profile, systemPrompt, enablePhoneDtmf))
    }
}
