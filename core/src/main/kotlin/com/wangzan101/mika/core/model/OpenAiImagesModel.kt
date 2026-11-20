package com.wangzan101.mika.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * OpenAI Images 兼容接口（`/images/generations` 与 `/images/edits`）。
 * 结果既认 `b64_json` 也认 `url`：有的服务商默认回链接，链接按同一个 [http] 在进程里下载，不交给 WebView/网页。
 * 能力（尺寸、能不能改图、一次几张）由调用方按实测填，不按模型名猜。
 */
class OpenAiImagesModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: ImageCapabilities,
    private val http: OkHttpClient,
    /** 每个请求都带的头（客户端标识、服务商会话号等，见 [ClientHeaders]）；Key 的头由这里自己加。 */
    private val headers: Map<String, String> = emptyMap(),
) : ImageModel {
    override val name: String get() = model
    private val root = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(request: ImageRequest): ImageResponse {
        checkRequest(request)
        val req = if (request.references.isEmpty()) generationRequest(request) else editRequest(request)
        val response = HttpSupport.execute(http, req)
        if (response.status !in 200..299) {
            throw ModelHttp.openAiError(response.status, response.retryAfter, response.body, model)
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

    private suspend fun generationRequest(request: ImageRequest): Request {
        val payload = buildJsonObject {
            put("model", model)
            put("prompt", request.prompt)
            put("n", request.count)
            openAiSize(request.aspect)?.let { put("size", it) }
        }
        return Request.Builder().url("$root/images/generations")
            .withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .postInference(InferenceProtocol.Chat, payload)
            .build()
    }

    private fun editRequest(request: ImageRequest): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("n", request.count.toString())
            .apply { openAiSize(request.aspect)?.let { addFormDataPart("size", it) } }
        val field = if (request.references.size == 1) "image" else "image[]"
        request.references.forEachIndexed { i, ref ->
            val type = ref.mime.toMediaTypeOrNull() ?: "image/png".toMediaTypeOrNull()
            body.addFormDataPart(field, "reference-${i + 1}.${extensionOf(ref.mime)}", ref.bytes.toRequestBody(type))
        }
        return Request.Builder().url("$root/images/edits")
            .withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .post(body.build())
            .build()
    }

    /** OpenAI 的尺寸枚举；标准三档。比例不在表里的模型能力里本来就不会有。 */
    private fun openAiSize(aspect: ImageAspect): String? = when (aspect) {
        ImageAspect.Square -> "1024x1024"
        ImageAspect.Portrait -> "1024x1536"
        ImageAspect.Landscape -> "1536x1024"
        ImageAspect.Tall, ImageAspect.Wide -> null
    }

    private suspend fun parse(body: String): ImageResponse {
        val rootJson = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
        val data = rootJson["data"]?.jsonArray ?: throw ModelError.BadResponse("missing data")
        val images = data.mapNotNull { el ->
            val item = el.jsonObject
            val revised = (item["revised_prompt"] as? JsonPrimitive)?.contentOrNull
            val b64 = (item["b64_json"] as? JsonPrimitive)?.contentOrNull
            val url = (item["url"] as? JsonPrimitive)?.contentOrNull
            when {
                !b64.isNullOrBlank() -> decodeBase64Image(b64, revised)
                !url.isNullOrBlank() -> downloadImage(url, revised)
                else -> null
            }
        }
        if (images.isEmpty()) throw ModelError.BadResponse("no image in response")
        return ImageResponse(images, usageOf(rootJson["usage"] as? JsonObject))
    }

    /** 有的服务商返回链接：按 http(s)、大小、文件头校验后读进内存。 */
    private suspend fun downloadImage(url: String, revisedPrompt: String?): GeneratedImage {
        val parsed = url.toHttpUrlOrNull() ?: throw ImageError.BadImage("image url is not http(s)")
        if (parsed.scheme != "http" && parsed.scheme != "https") throw ImageError.BadImage("image url is not http(s)")
        val req = Request.Builder().url(parsed).get().build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw ModelError.Server(resp.code)
                    val body = resp.body ?: throw ImageError.BadImage("empty image response")
                    val declared = body.contentLength()
                    if (declared > MAX_IMAGE_BYTES) throw ImageError.TooLarge(declared)
                    val out = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) throw ImageError.TooLarge(total)
                            out.write(buf, 0, n)
                        }
                    }
                    decodeImage(out.toByteArray(), revisedPrompt)
                }
            }
        } catch (e: IOException) {
            throw HttpSupport.networkError(http, req, e)
        }
    }

    /** 服务商报的用量口径不完全一样：OpenAI 是 input/output_tokens，兼容服务有时用 prompt/completion_tokens。 */
    private fun usageOf(el: JsonObject?): Usage? {
        val u = el ?: return null
        fun n(vararg keys: String): Int? = keys.firstNotNullOfOrNull { (u[it] as? JsonPrimitive)?.intOrNull }
        val input = n("input_tokens", "prompt_tokens") ?: return null
        val output = n("output_tokens", "completion_tokens") ?: return null
        val cached = ((u["input_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        return Usage(input, output, cached)
    }
}
