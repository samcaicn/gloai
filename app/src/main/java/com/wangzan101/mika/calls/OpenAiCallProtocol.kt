package com.wangzan101.mika.calls

import com.wangzan101.mika.calls.VolcRealtimeProtocol.string
import kotlinx.serialization.json.*

/** OpenAI GA Realtime WebSocket messages. The phone audio bridge supplies 16 kHz PCM. */
internal object OpenAiCallProtocol {
    const val ENDPOINT = "wss://api.openai.com/v1/realtime"
    const val MODEL = "gpt-realtime"
    const val VOICE = "marin"

    fun configure(config: VolcRealtimeConfig) = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("instructions", config.systemPrompt)
            val format = buildJsonObject { put("type", "audio/pcm"); put("rate", 24000) }
            putJsonObject("audio") {
                putJsonObject("input") {
                    put("format", format)
                    putJsonObject("transcription") { put("model", "gpt-4o-mini-transcribe") }
                    putJsonObject("turn_detection") { put("type", "server_vad") }
                }
                putJsonObject("output") { put("format", format); put("voice", config.speaker) }
            }
            if (config.enablePhoneDtmf) put("tools", VolcRealtimeProtocol.phoneTools())
        }
    }.toString()

    fun errorKind(error: JsonObject?): VolcRealtimeException.Kind = when (error?.string("code")) {
        "invalid_api_key", "insufficient_permissions" -> VolcRealtimeException.Kind.AUTH
        "rate_limit_exceeded", "insufficient_quota" -> VolcRealtimeException.Kind.RATE_LIMITED
        else -> VolcRealtimeException.Kind.PROVIDER
    }

    fun truncate(itemId: String, index: Int, playedMs: Int) = buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", itemId)
        put("content_index", index)
        put("audio_end_ms", playedMs)
    }.toString()

    fun greeting(text: String) = buildJsonObject {
        put("type", "response.create")
        putJsonObject("response") {
            put("instructions", "Speak this greeting verbatim: " + text)
            put("tool_choice", "none")
        }
    }.toString()

    fun toolResult(id: String, result: JsonObject) = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", id)
            put("output", result.toString())
        }
    }.toString()
}

/** Stateful linear interpolation, three output samples per two input samples. */
internal class CallPcm16To24 {
    private var previous = 0
    private var first: Int? = null

    fun convert(bytes: ByteArray, count: Int): ByteArray {
        val pairs = (count / 2 + if (first == null) 0 else 1) / 2
        val output = ByteArray(pairs * 6)
        var offset = 0
        fun write(value: Int) {
            output[offset++] = value.toByte()
            output[offset++] = (value shr 8).toByte()
        }
        for (i in 0 until count step 2) {
            val sample = (bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)
            val a = first
            if (a == null) first = sample else {
                write((previous * 2 + a) / 3)
                write(a)
                write((a + 2 * sample) / 3)
                previous = sample
                first = null
            }
        }
        return output
    }
}
