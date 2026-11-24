package com.wangzan101.mika.calls

import com.wangzan101.mika.core.frontdesk.DeskPersona
import com.wangzan101.mika.core.frontdesk.DeskPrompt
import com.wangzan101.mika.core.frontdesk.DeskToolSet
import com.wangzan101.mika.core.frontdesk.DeskTools
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
    /** 应用内语音通话:模型当前台,带前台的用户工具(派单、补充、回答、取消),[systemPrompt] 只放开场时的任务板。 */
    val agentTools: Boolean = false,
    val protocol: CallVoiceProtocol = CallVoiceProtocol.VOLC_DUPLEX,
    /** [agentTools] 时前台说明用的人格(称呼和人格层),和聊天里的前台同一套。 */
    val deskPersona: DeskPersona = DeskPersona(),
) {
    init {
        require(systemPrompt.length <= MAX_PROMPT_CHARS) { "Realtime system prompt is too long" }
        require(speaker.isNotBlank() && speaker.length <= 128) { "Invalid realtime speaker" }
        require(model.isNotBlank() && model.length <= 128 && model.all { it.code in 33..126 }) { "Invalid realtime model" }
        // 能加 system 文字项的那套实时协议不注册前台工具:直接报错,免得每句请求都被悄悄丢掉
        require(!(agentTools && protocol == CallVoiceProtocol.OPENAI_REALTIME)) {
            "agentTools requires the VOLC_DUPLEX realtime protocol"
        }
    }

    companion object {
        const val MODEL = "1.2.6.1"
        const val DEFAULT_SPEAKER = "zh_female_vv_jupiter_bigtts"
        private const val MAX_PROMPT_CHARS = 16_384
    }
}

/** The realtime dialogue API uses JSON WebSocket text frames with Base64 PCM. */
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
    const val MAX_UTTERANCE_CHARS = 4_096

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
            // 前台说明在前,开场时的任务板(若有)接在后面。
            val instructions = if (config.agentTools) {
                listOf(DeskPrompt.user(spoken = true, persona = config.deskPersona), config.systemPrompt).filter { it.isNotBlank() }.joinToString("\n\n")
            } else {
                config.systemPrompt
            }
            if (instructions.isNotBlank()) put("instructions", instructions)
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

    /** tools is a complete replacement, not an incremental addition (server-side semantics), so one update carries every enabled tool. */
    fun enableTools(eventId: String, config: VolcRealtimeConfig): String = message("session.update", eventId) {
        putJsonObject("session") {
            put("tools", JsonArray(mutableListOf<kotlinx.serialization.json.JsonElement>().apply {
                if (config.enablePhoneDtmf) addAll(phoneCallTools())
                if (config.agentTools) addAll(DeskTools.specs(DeskToolSet.User))
            }))
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

    /** 运营商电话里的整套工具:拨号键 + 电话前台(只有请示主人)。 */
    fun phoneCallTools() = JsonArray(phoneTools() + DeskTools.specs(DeskToolSet.Phone))

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

    /** 前台的用户工具名;应用内语音通话只认这几个(加上按键)。 */
    val deskToolNames: Set<String> = DeskTools.specs(DeskToolSet.User).map { it.string("name")!! }.toSet()

    /** 电话前台的工具名(只有请示主人);运营商电话里只认这个(加上按键)。 */
    val phoneDeskToolNames: Set<String> = DeskTools.specs(DeskToolSet.Phone).map { it.string("name")!! }.toSet()

    /** 前台工具的参数:只能有说明里列出的字段,值都是字符串,必填的都在且不为空。字数上限在执行时管(原话不截,其余截断)。 */
    fun validDeskArguments(name: String, arguments: JsonObject): Boolean {
        val parameters = (DeskTools.specs(DeskToolSet.User) + DeskTools.specs(DeskToolSet.Phone)).firstOrNull { it.string("name") == name }
            ?.get("parameters") as? JsonObject ?: return false
        val properties = (parameters["properties"] as? JsonObject)?.keys ?: return false
        val required = (parameters["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return properties.containsAll(arguments.keys) && arguments.values.all { it is JsonPrimitive && it.isString } &&
            required.all { !arguments.string(it).isNullOrBlank() }
    }

    /**
     * Argument envelopes are tool-sized: a DTMF batch is tiny; tools carrying the user's words get a full utterance
     * plus the other fields (title, note), all possibly sent as `\uXXXX` escapes. The words alone are checked
     * against [MAX_UTTERANCE_CHARS] after parsing, so a near-limit utterance is never lost to the envelope.
     */
    fun maxArgumentCharsFor(name: String?): Int = when (name) {
        DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL, DeskTools.ASK_OWNER -> MAX_WORDS_ARGUMENT_CHARS
        else -> MAX_FUNCTION_ARGUMENT_CHARS
    }

    /** One escaped character takes at most six (`\uXXXX`); the rest covers keys, quotes and separators. */
    private const val MAX_WORDS_ARGUMENT_CHARS = (MAX_UTTERANCE_CHARS + 2 * MAX_FUNCTION_ARGUMENT_CHARS) * 6 + MAX_FUNCTION_ARGUMENT_CHARS

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
