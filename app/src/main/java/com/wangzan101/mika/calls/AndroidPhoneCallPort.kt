package com.wangzan101.mika.calls

import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import kotlinx.serialization.json.*

/** One native runtime for the local agent, UI and authenticated MCP endpoint. */
class AndroidPhoneCallPort(private val runtime: CallRuntime) : PhoneCallPort {
    override suspend fun state(): JsonObject {
        runtime.refresh()
        return snapshot()
    }

    private fun snapshot() = buildJsonObject {
        val config = runtime.settings.state.value
        val state = runtime.state.value
        put("enabled", config.enabled)
        put("prepared", state.prepared)
        put("audio_ready", state.audioReady)
        put("audio_verified", config.audioVerified)
        put("helper_connected", state.shellConnected)
        put("notifications_available", state.notificationsAvailable)
        state.audioDetail?.let { put("audio_detail", it) }
        put("credentials_configured", config.credentialsConfigured)
        put("voice_profile_id", config.activeVoiceProfileId)
        put("voice_profile_name", runtime.activeVoiceProfile().name)
        put("voice_connection_tested", runtime.activeVoiceProfile().connectionTested)
        put("service_ready", state.serviceReady)
        put("manual_ready", state.manualReady)
        put("dial_ready", state.dialReady)
        put("auto_answer_ready", state.autoAnswerReady)
        put("phase", state.phase.name.lowercase())
        put("ai_active", state.aiActive)
        put("auto_answer", config.autoAnswer)
        put("auto_answer_pending", state.pendingAutoAnswer)
        put("max_call_seconds", config.maxCallSeconds)
        state.direction?.let { put("direction", it.name.lowercase()) }
        state.callId?.let { put("call_id", it) }
        state.number?.let { put("number", it) }
        state.detail?.let { put("detail", it) }
        putJsonArray("sims") {
            state.simOptions.forEach { sim -> add(buildJsonObject {
                put("id", sim.id); put("label", sim.label); put("default", sim.isDefault)
            }) }
        }
    }

    private fun result(result: CallActionResult) = buildJsonObject {
        put("ok", result.ok); put("message", result.message); put("call", snapshot())
    }
    override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?, ownerConversationId: String?) = result(runtime.dial(
        number, simId, task?.let { CallTaskBrief(it.goal, it.questions, it.context, it.constraints) }, ownerConversationId,
    ))
    override suspend fun answer(callId: String) = result(runtime.answer(expectedCallId = callId))
    override suspend fun hangup(callId: String) = result(runtime.hangup(expectedCallId = callId))
    override suspend fun takeover() = result(runtime.takeover())
}
