package com.wangzan101.mika.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OpenAI Speech 兼容接口：`POST {base}/audio/speech`，返回二进制音频。
 * 也覆盖火山、硅基流动等按这个形状提供 TTS 的服务；音色和格式由能力表/服务商校验。
 */
class OpenAiSpeechModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: TtsCapabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) : TtsModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/audio/speech"
    private val json = "application/json".toMediaType()

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val text = request.text.trim()
        if (text.isEmpty()) throw TtsError.Unsupported("text is empty")
        if (text.length > capabilities.maxChars) throw TtsError.Unsupported("text is longer than ${capabilities.maxChars} characters")
        val format = request.format.lowercase()
        if (format !in capabilities.formats) throw TtsError.Unsupported("format $format is not supported")
        val voice = request.voice ?: capabilities.voices?.firstOrNull() ?: throw TtsError.Unsupported("voice is required")
        capabilities.voices?.let { if (voice !in it) throw TtsError.Unsupported("voice $voice is not supported") }
        val payload = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", format)
            request.speed?.let { put("speed", it) }
        }
        val req = Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody(json))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw ModelHttp.openAiError(resp.code, resp.header("retry-after"), resp.body?.string().orEmpty(), model)
                    }
                    val body = resp.body ?: throw TtsError.BadAudio("empty response")
                    val declared = body.contentLength()
                    if (declared > MAX_TTS_BYTES) throw TtsError.TooLarge(declared)
                    val bytes = body.bytes()
                    checkTtsSize(bytes)
                    if (format == "pcm") {
                        return@use TtsResponse(wrapPcm16Wav(bytes, 24_000), "audio/wav", 24_000)
                    }
                    val mime = sniffAudioMime(bytes)
                        ?: resp.header("Content-Type")?.substringBefore(';')?.takeIf { it.startsWith("audio/") }
                        ?: throw TtsError.BadAudio("response is not a known audio format")
                    TtsResponse(bytes, mime)
                }
            } catch (e: IOException) {
                throw HttpSupport.networkError(http, req, e)
            }
        }
    }
}
