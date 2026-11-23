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
    /**
     * 后台任务来了消息:等手上这句说完、用户也没在说话时再说,不打断;说不了返回 false(调用方改走别的路)。
     * 收下的消息最后一定回一次 [ended]:说完了为 true;被插话打断、或没轮到说通话就断了为 false。
     */
    fun sendNotice(text: String, ended: (spoken: Boolean) -> Unit = {}): Boolean = false
    /**
     * 交给模型的一条提示(不是对方说的话,也不原样念):模型读了以后用自己的话接着说。排队、[ended] 的约定同 [sendNotice]。
     * 协议没有能让模型接着回话的文字输入时返回 false,调用方改走别的路。
     */
    fun sendCue(text: String, ended: (spoken: Boolean) -> Unit = {}): Boolean = false

    /** 只给模型当背景的一条提示:加进上下文,不让它为此开口;协议没有这种输入时返回 false。 */
    fun sendContext(text: String): Boolean = false

    /** 用户打字说的话,当成一句话来回;不支持返回 false。 */
    fun sendUserText(text: String): Boolean = false
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
