package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.headerOrReject
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * 用服务商的模型把一段录音转成文字,接口形态见 [TranscribeMethod]。
 * 报错和对话模型走同一套映射([ModelHttp]),界面可以直接复用现有的说法。
 * [headers] 是每个请求都带的头(客户端标识等,见 ClientHeaders),Key 的头由这里按接口类型加。
 */
class Transcriber(
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 转一段音频;失败抛 [ModelError](和对话模型同一套)。返回的文字可能是空串(没听到话)。
     * [mime]/[filename] 给服务商看文件类型;默认按录音的 WAV,文件转写传原文件的类型。
     */
    suspend fun transcribe(
        p: Provider, key: String?, model: String, method: TranscribeMethod, wav: ByteArray, language: String?,
        mime: String = "audio/wav", filename: String = "audio.wav",
    ): String {
        val base = p.baseUrl.trim().trimEnd('/')
        val gemini = p.kind == ProviderConfig.Kind.Gemini
        val format = formatOf(mime)
        val request = when (method) {
            TranscribeMethod.AudioTranscriptions -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, wav.toRequestBody(mime.toMediaType()))
                    .addFormDataPart("model", model)
                    .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
                    .build()
                builder("$base/audio/transcriptions", key, gemini).post(body).build()
            }
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val data = if (method == TranscribeMethod.ChatInputAudio) base64(wav) else "data:$mime;base64,${base64(wav)}"
                val payload = buildJsonObject {
                    put("model", model)
                    put("stream", false)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") { put("data", data); put("format", format) }
                                })
                                // 专做转写的模型不收文字块;通用模型没有这句指令会当成聊天回话
                                if (method != TranscribeMethod.ChatInputAudioOnly) {
                                    add(buildJsonObject { put("type", "text"); put("text", INSTRUCTION) })
                                }
                            })
                        })
                    })
                }
                builder("$base/chat/completions", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
            TranscribeMethod.GeminiInline -> {
                val payload = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    putJsonObject("inline_data") { put("mime_type", mime); put("data", base64(wav)) }
                                })
                                add(buildJsonObject { put("text", INSTRUCTION) })
                            })
                        })
                    })
                }
                // 和对话适配器一样:库存的地址不带 /v1beta,这里补上
                builder("$base/v1beta/models/$model:generateContent", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
        }
        val reply = ModelHttp.send(http, request)
        if (!reply.ok) {
            throw if (gemini) ModelHttp.geminiError(reply.status, reply.retryAfter, reply.body, model)
            else ModelHttp.openAiError(reply.status, reply.retryAfter, reply.body, model)
        }
        return parse(method, reply.body)
    }

    private fun builder(url: String, key: String?, gemini: Boolean): Request.Builder = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> headerOrReject(k, v) }
        if (!key.isNullOrEmpty()) {
            if (gemini) headerOrReject("x-goog-api-key", key) else headerOrReject("Authorization", "Bearer $key")
        }
    }

    private fun parse(method: TranscribeMethod, body: String): String {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("not json: ${body.take(120)}")
        val text = when (method) {
            TranscribeMethod.AudioTranscriptions -> root.str("text")
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val msg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                when (val c = msg?.get("content")) {
                    is JsonPrimitive -> c.contentOrNull
                    // 内容是分块数组的:把文字块拼起来
                    is JsonArray -> c.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
                    else -> null
                }
            }
            TranscribeMethod.GeminiInline -> {
                val cand = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                val parts = (cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                // 没有任何文字块(比如静音时只回结束原因)按空串;连候选都没有才算格式不对
                if (cand == null) null else parts.orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
            }
        }
        return (text ?: throw ModelError.BadResponse("no text field: ${body.take(120)}")).trim()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** OpenAI 的 input_audio 只认几种格式名;认不出按 wav。 */
    private fun formatOf(mime: String): String = when (mime.substringAfter('/', "").substringBefore(';').lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "mp4", "m4a", "x-m4a" -> "m4a"
        "webm" -> "webm"
        "ogg", "opus" -> "ogg"
        else -> "wav"
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** 发给模型的指令:只要原话,不要解释(模型面向英文)。 */
        private const val INSTRUCTION = "Transcribe this recording word for word. Output only the transcript text."

        /** 这家能不能做语音转文字:有 Key 的 OpenAI 兼容、Responses、Gemini 可以;Anthropic 和账号登录的不行。 */
        fun supports(p: Provider): Boolean = when (p.kind) {
            ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.Gemini -> p.signIn == null
            else -> false
        }
    }
}
