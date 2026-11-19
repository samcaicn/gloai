package com.wangzan101.mika.calls

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class VolcRealtimeConfig(
    val systemPrompt: String = "",
    val speaker: String = DEFAULT_SPEAKER,
    val model: String = MODEL,
    /** Tools are enabled only for an owned carrier call, never a briefing or connection test. */
    val enablePhoneDtmf: Boolean = false,
    val protocol: CallVoiceProtocol = CallVoiceProtocol.VOLC_DUPLEX,
) {
    init {
        require(systemPrompt.length <= MAX_PROMPT_CHARS) { "Realtime system prompt is too long" }
        require(speaker.isNotBlank() && speaker.length <= 128) { "Invalid realtime speaker" }
        require(model.isNotBlank() && model.length <= 128 && model.all { it.code in 33..126 }) { "Invalid realtime model" }
    }

    companion object {
        const val MODEL = "1.2.6.1"
        const val DEFAULT_SPEAKER = "zh_female_vv_jupiter_bigtts"
        private const val MAX_PROMPT_CHARS = 16_384
    }
}

/** Volcengine Seeduplex 3.0 uses JSON WebSocket text frames with Base64 PCM. */
internal object VolcRealtimeProtocol {
    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
    const val RESOURCE = "volc.speech.dialog"
    const val LEGACY_APP_KEY = "PlgvMymc7f3tQnJ6" // Fixed protocol identifier, not a user credential.
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 24_000
    const val CHUNK_BYTES = 640 // 20 ms of 16 kHz mono s16le.
    const val MAX_INPUT_BYTES = 5 * CHUNK_BYTES
    const val MAX_FRAME_CHARS = 72 * 1024
    private const val MAX_OUTPUT_BYTES = OUTPUT_RATE * 2 // At most one second in one packet.

    const val SESSION_CREATED = "session.created"
    const val SESSION_UPDATED = "session.updated"
    const val FUNCTION_CALL = "response.function_call_arguments.done"
    const val DTMF_FUNCTION = "send_dtmf"
    const val MAX_DTMF_DIGITS = 32
    const val MAX_FUNCTION_CALLS = 128
    const val MAX_FUNCTION_BATCH = 8
    const val MAX_FUNCTION_ARGUMENT_CHARS = 256
    const val MAX_TOOL_RESULT_CHARS = 2048
    const val SESSION_CLOSED = "session.closed"
    const val INPUT_AUDIO_COMMITTED = "input_audio_buffer.committed"
    const val USER_STARTED = "conversation.item.input_audio_transcription.started"
    const val USER_DELTA = "conversation.item.input_audio_transcription.delta"
    const val USER_COMPLETED = "conversation.item.input_audio_transcription.completed"
    const val USER_FAILED = "conversation.item.input_audio_transcription.failed"
    const val TEXT_DELTA = "response.output_text.delta"
    const val TEXT_DONE = "response.output_text.done"
    const val AUDIO_STARTED = "response.output_audio.started"
    const val AUDIO_DELTA = "response.output_audio.delta"
    const val AUDIO_DONE = "response.output_audio.done"
    const val CANCELED = "response.canceled"
    const val ERROR = "error"

    fun create(eventId: String, config: VolcRealtimeConfig): String = message("session.create", eventId) {
        putJsonObject("session") {
            put("model", config.model)
            if (config.systemPrompt.isNotBlank()) put("instructions", config.systemPrompt)
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "pcm")
                        put("rate", INPUT_RATE)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "pcm_s16le")
                        put("rate", OUTPUT_RATE)
                    }
                    put("voice", config.speaker)
                }
            }
        }
    }

    fun audio(eventId: String, bytes: ByteArray, count: Int): String = message("input_audio_buffer.append", eventId) {
        put("audio", Base64.getEncoder().encodeToString(if (bytes.size == count) bytes else bytes.copyOf(count)))
    }

    /** tools is a complete replacement, not an incremental addition (Seeduplex API). */
    fun enablePhoneTools(eventId: String): String = message("session.update", eventId) {
        putJsonObject("session") {
            put("tools", phoneTools())
        }
    }

    fun phoneTools() = JsonArray(listOf(buildJsonObject {
        put("type", "function")
        put("name", DTMF_FUNCTION)
        put("description", "在电话语音菜单明确要求按键时，发送真正的电话DTMF按键；说出数字不能代替按键。仅按本次任务和听到的菜单选择，不猜测密码或验证码。等待工具结果，失败时不得声称按键成功。")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("digits") {
                    put("type", "string")
                    put("pattern", "^[0-9*#]{1,32}$")
                    put("minLength", 1)
                    put("maxLength", MAX_DTMF_DIGITS)
                    put("description", "按顺序发送的电话按键，只能含0-9、*、#，最多32个。")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("digits"))))
            put("additionalProperties", false)
        }
    }))

    fun toolResults(eventId: String, results: List<Pair<String, JsonObject>>): String =
        message("conversation.item.create", eventId) {
            put("items", JsonArray(results.map { (callId, result) -> buildJsonObject {
                put("call_id", callId)
                put("role", "tool")
                put("content", JsonArray(listOf(buildJsonObject {
                    put("type", "input_text")
                    put("text", result.toString())
                })))
            } }))
        }

    fun validCallId(id: String): Boolean = id.length in 1..128 && id.all { it in '!'..'~' }

    fun validDtmfArguments(arguments: JsonObject): Boolean {
        val digits = arguments.string("digits") ?: return false
        return arguments.keys == setOf("digits") && digits.length in 1..MAX_DTMF_DIGITS &&
            digits.all { it in '0'..'9' || it == '*' || it == '#' }
    }

    fun greeting(eventId: String, text: String): String = message("speech_text_buffer.commit", eventId) {
        put("text", text)
    }

    fun mute(eventId: String, muted: Boolean): String =
        message(if (muted) "input_audio_mute.commit" else "input_audio_unmute.commit", eventId)

    fun close(eventId: String): String = message("session.close", eventId)

    private fun message(
        type: String,
        eventId: String,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): String = buildJsonObject {
        put("type", type)
        put("event_id", eventId)
        payload()
    }.toString()

    fun parse(text: String): JsonObject? {
        if (text.length > MAX_FRAME_CHARS) return null
        return try {
            (Json.parseToJsonElement(text) as? JsonObject)?.takeIf { !it.string("type").isNullOrBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun pcm(frame: JsonObject): ByteArray? {
        val base64 = frame.string("delta") ?: return null
        if (base64.length > (MAX_OUTPUT_BYTES + 2) / 3 * 4) return null
        return try {
            Base64.getDecoder().decode(base64).takeIf { it.size <= MAX_OUTPUT_BYTES && it.size % 2 == 0 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun errorCode(frame: JsonObject): Long? {
        val error = frame["error"] as? JsonObject
        return (error?.get("code") as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (error?.get("status_code") as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (frame["code"] as? JsonPrimitive)?.content?.toLongOrNull()
    }
}
