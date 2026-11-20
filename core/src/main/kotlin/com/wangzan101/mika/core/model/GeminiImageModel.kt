package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

/**
 * Gemini 图像模型（`gemini-*-image*`）的 generateContent 接口：要求 `responseModalities` 含 IMAGE，
 * 参考图用 inlineData 放在同一轮 parts 里，比例走 `generationConfig.imageConfig.aspectRatio`。
 * 文本模型不会走这个适配器；设置页单独选图片模型。
 */
class GeminiImageModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: ImageCapabilities,
    private val http: OkHttpClient,
    /** 每个请求都带的头（见 [ClientHeaders]）；Key 的头由这里自己加。 */
    private val headers: Map<String, String> = emptyMap(),
) : ImageModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:generateContent"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(request: ImageRequest): ImageResponse {
        checkRequest(request)
        val payload = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        request.references.forEach { ref ->
                            add(buildJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", ref.mime)
                                    put("data", Base64.getEncoder().encodeToString(ref.bytes))
                                }
                            })
                        }
                        add(buildJsonObject { put("text", request.prompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add("TEXT")
                    add("IMAGE")
                }
                if (request.count > 1) put("candidateCount", request.count)
                putJsonObject("imageConfig") { put("aspectRatio", request.aspect.wire) }
            }
        }
        val req = Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("x-goog-api-key", apiKey)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
        val response = HttpSupport.execute(http, req)
        if (response.status !in 200..299) {
            throw ModelHttp.geminiError(response.status, response.retryAfter, response.body, model)
        }
        return parse(response.body)
    }

    private fun checkRequest(request: ImageRequest) {
        if (request.prompt.isBlank()) throw ImageError.Unsupported("prompt is empty")
        if (request.count !in 1..capabilities.maxCount) {
            throw ImageError.Unsupported("count must be 1-${capabilities.maxCount}")
        }
        if (request.aspect !in capabilities.aspects) {
            throw ImageError.Unsupported("aspect ${request.aspect.wire} is not supported by this model")
        }
        if (request.references.size > capabilities.maxReferences) {
            throw ImageError.Unsupported("at most ${capabilities.maxReferences} reference image(s)")
        }
        if (request.references.isNotEmpty() && !capabilities.supportsEdit) {
            throw ImageError.Unsupported("this model cannot edit from reference images")
        }
    }

    private fun parse(body: String): ImageResponse {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
        ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull?.let {
            throw ModelError.ContentRefused()
        }
        val candidates = root["candidates"] as? JsonArray ?: throw ModelError.BadResponse("missing candidates")
        val images = mutableListOf<GeneratedImage>()
        val texts = mutableListOf<String>()
        for (candidateEl in candidates) {
            val candidate = candidateEl as? JsonObject ?: continue
            val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
            parts?.forEach { partEl ->
                val part = partEl as? JsonObject ?: return@forEach
                when {
                    part["inlineData"] is JsonObject -> {
                        val inline = part["inlineData"] as JsonObject
                        val data = (inline["data"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        images += decodeBase64Image(data, texts.joinToString("\n").takeIf { it.isNotBlank() })
                    }
                    (part["text"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ->
                        texts += (part["text"] as JsonPrimitive).content
                }
            }
            if (parts == null && finish in SAFETY_FINISHES) throw ModelError.ContentRefused()
        }
        if (images.isEmpty()) {
            val refused = candidates.any { c ->
                ((c as? JsonObject)?.get("finishReason") as? JsonPrimitive)?.contentOrNull in SAFETY_FINISHES
            }
            if (refused) throw ModelError.ContentRefused()
            throw ModelError.BadResponse("no image in response")
        }
        return ImageResponse(images, usageOf(root["usageMetadata"] as? JsonObject))
    }

    private fun usageOf(el: JsonObject?): Usage? {
        val u = el ?: return null
        fun n(key: String) = (u[key] as? JsonPrimitive)?.intOrNull
        val input = n("promptTokenCount") ?: return null
        val output = (n("candidatesTokenCount") ?: 0) + (n("thoughtsTokenCount") ?: 0)
        return Usage(input, output, n("cachedContentTokenCount") ?: 0, n("thoughtsTokenCount") ?: 0)
    }

    private companion object {
        val SAFETY_FINISHES = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
    }
}
