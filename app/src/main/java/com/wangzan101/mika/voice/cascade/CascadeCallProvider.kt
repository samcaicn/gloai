package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.serialization.json.JsonObject

/**
 * A standard call behind the realtime provider seam. The phone call's prompt becomes the thinking model's
 * instructions, and its keypad tool is offered as a function; credentials come from the voice models themselves.
 */
class CascadeCallProvider(
    private val engine: CascadeEngine,
    private val use: CallUse,
    private val language: String? = null,
) : CallRealtimeProvider {
    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        engine.blocker(use)?.let { throw VoiceModelError.IncompleteConfig("Standard call is not ready: $it") }
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
    ): CallRealtimeSession {
        val tools = if (enablePhoneDtmf) VolcRealtimeProtocol.phoneTools().map { LlmBrain.toolSpec(it as JsonObject) } else emptyList()
        return engine.open(use, systemPrompt, tools, language)
    }
}
