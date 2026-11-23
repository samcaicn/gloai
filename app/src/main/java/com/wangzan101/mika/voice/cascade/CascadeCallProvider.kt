package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.frontdesk.DeskBackground
import com.wangzan101.mika.core.frontdesk.LlmBrain
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.serialization.json.JsonObject

/**
 * A standard call behind the realtime provider seam. The phone call's prompt becomes the thinking model's
 * instructions, and its keypad and ask-the-owner tools are offered as functions; credentials come from the voice models themselves.
 */
class CascadeCallProvider(
    private val engine: CascadeEngine,
    private val use: CallUse,
    private val language: String? = null,
    /** 电话前台的尾随附注(这通电话所属任务的状态),每次调模型前取一次;没有所属任务时为 null。 */
    private val background: DeskBackground? = null,
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
        // 运营商电话:拨号键 + 电话前台(只有请示主人)
        val tools = if (enablePhoneDtmf) VolcRealtimeProtocol.phoneCallTools().map { LlmBrain.toolSpec(it as JsonObject) } else emptyList()
        return engine.open(use, systemPrompt, tools, language, background = background)
    }
}
