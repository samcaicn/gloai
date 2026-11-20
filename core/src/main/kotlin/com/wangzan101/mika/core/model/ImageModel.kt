package com.wangzan101.mika.core.model

import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * 图片生成模型。和对话模型分开：图片模型的请求不是一段对话，也没有工具调用、上下文、思维链。
 * 返回的图按 [GeneratedImage] 交给上层；HTTP/服务商错误仍用 [ModelError]，
 * 只有图片自己的限制（不支持这种比例、改图、解不开的图）用 [ImageError]。
 */
interface ImageModel {
    /** 模型名，只用于记录。 */
    val name: String

    /** 这个模型支持什么；上层在发请求前按它拒绝不支持的请求，避免先花钱再报错。 */
    val capabilities: ImageCapabilities

    /** 失败时抛 [ModelError] 或 [ImageError]。 */
    suspend fun generate(request: ImageRequest): ImageResponse
}

/** 比例。服务商叫法不同，[wire] 是 Gemini 的写法，OpenAI 一类按它换成尺寸。 */
enum class ImageAspect(val wire: String) {
    Square("1:1"),
    Portrait("2:3"),
    Landscape("3:2"),
    Tall("9:16"),
    Wide("16:9"),
}

/** 一张参考图。图片模型要的是原图，不经过 [ModelImage] 的读屏压缩。 */
class ImageReference(val bytes: ByteArray, val mime: String)

/** 一次生成请求。[count] 是希望要几张；服务商实际给几张以结果为准。 */
data class ImageRequest(
    val prompt: String,
    val aspect: ImageAspect = ImageAspect.Square,
    val count: Int = 1,
    val references: List<ImageReference> = emptyList(),
)

/** 服务商返回的一张图。[width]/[height] 读不出来时为 0，不猜。 */
class GeneratedImage(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    val revisedPrompt: String? = null,
)

class ImageResponse(val images: List<GeneratedImage>, val usage: Usage? = null)

/**
 * 一个图片模型能做什么。能力按实测写，不按模型名猜；上层只发这里写明的请求。
 * [aspects] 是能生成的比例；[maxReferences] 是改图时最多几张参考图；[maxCount] 是一次最多要几张。
 */
data class ImageCapabilities(
    val supportsEdit: Boolean,
    val maxReferences: Int = 0,
    val maxCount: Int = 1,
    val aspects: Set<ImageAspect> = setOf(ImageAspect.Square),
)

/** 图片自己的限制：不是服务商的 HTTP 错误，重试或换模型可能有用。 */
sealed class ImageError(message: String) : Exception(message) {
    /** 这个模型/接口不支持这次请求（比例、改图、张数、参考图）。 */
    class Unsupported(message: String) : ImageError(message)

    /** 返回的不是能识别的图片（不是 PNG/JPEG/WebP/GIF，或内容坏了）。 */
    class BadImage(message: String) : ImageError(message)

    /** 图太大，不往工作区写。 */
    class TooLarge(val bytes: Long) : ImageError("image is too large")
}

/** 从文件头认图片格式和尺寸；认不出为 null。只认图片服务商会返回的几种。 */
internal class ImageBytes(val mime: String, val width: Int, val height: Int)

internal fun sniffImage(bytes: ByteArray): ImageBytes? = when {
    bytes.size >= 24 && u8(bytes, 0) == 0x89 && u8(bytes, 1) == 'P'.code && u8(bytes, 2) == 'N'.code && u8(bytes, 3) == 'G'.code ->
        ImageBytes("image/png", be32(bytes, 16), be32(bytes, 20))
    bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF ->
        jpeg(bytes)
    bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" ->
        webp(bytes)
    bytes.size >= 10 && ascii(bytes, 0, 3) == "GIF" ->
        ImageBytes("image/gif", le16(bytes, 6), le16(bytes, 8))
    else -> null
}

/** JPEG 的尺寸在 SOF0/SOF1/SOF2 段里；挨个段跳过去找。 */
private fun jpeg(b: ByteArray): ImageBytes? {
    var i = 2
    while (i + 9 < b.size) {
        if (u8(b, i) != 0xFF) { i++; continue }
        val marker = u8(b, i + 1)
        if (marker == 0xD8 || marker == 0xD9) { i += 2; continue }
        if (marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
        if (i + 4 > b.size) return null
        val length = be16(b, i + 2)
        if (length < 2) return null
        if (marker in 0xC0..0xC2 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF) {
            if (i + 9 > b.size) return null
            return ImageBytes("image/jpeg", be16(b, i + 7), be16(b, i + 5))
        }
        i += 2 + length
    }
    return ImageBytes("image/jpeg", 0, 0)
}

/** WebP：VP8X 有扩展尺寸，VP8（有损）和 VP8L（无损）各按自己的头读。 */
private fun webp(b: ByteArray): ImageBytes? {
    var i = 12
    while (i + 8 <= b.size) {
        val fourcc = ascii(b, i, 4)
        val size = le32(b, i + 4).coerceAtLeast(0)
        val data = i + 8
        when (fourcc) {
            "VP8X" -> if (data + 10 <= b.size) {
                val w = 1 + (u8(b, data + 4) or (u8(b, data + 5) shl 8) or (u8(b, data + 6) shl 16))
                val h = 1 + (u8(b, data + 7) or (u8(b, data + 8) shl 8) or (u8(b, data + 9) shl 16))
                return ImageBytes("image/webp", w, h)
            }
            "VP8 " -> if (data + 10 <= b.size) {
                val w = le16(b, data + 6) and 0x3FFF
                val h = le16(b, data + 8) and 0x3FFF
                return ImageBytes("image/webp", w, h)
            }
            "VP8L" -> if (data + 5 <= b.size) {
                val bits = le32(b, data + 1)
                val w = 1 + (bits and 0x3FFF)
                val h = 1 + ((bits ushr 14) and 0x3FFF)
                return ImageBytes("image/webp", w, h)
            }
        }
        i = data + size + (size and 1)
    }
    return ImageBytes("image/webp", 0, 0)
}

private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
private fun be16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
private fun be32(b: ByteArray, at: Int): Int = (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
private fun le16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
private fun le32(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24)
private fun ascii(b: ByteArray, at: Int, n: Int): String = String(b, at, n, Charsets.ISO_8859_1)

/** 服务商返回的图按内容认格式；b64 和 URL 两条路共用。 */
internal fun decodeImage(bytes: ByteArray, revisedPrompt: String? = null): GeneratedImage {
    if (bytes.size > MAX_IMAGE_BYTES) throw ImageError.TooLarge(bytes.size.toLong())
    val info = sniffImage(bytes) ?: throw ImageError.BadImage("response is not a PNG, JPEG, WebP or GIF image")
    return GeneratedImage(bytes, info.mime, info.width, info.height, revisedPrompt)
}

internal fun decodeBase64Image(text: String, revisedPrompt: String? = null): GeneratedImage {
    val bytes = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) {
        throw ImageError.BadImage("response is not valid base64")
    }
    return decodeImage(bytes, revisedPrompt)
}

/** 一张生成图的最大字节数；超过不写进工作区。 */
internal const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

/** 给 multipart 的图片挑一个扩展名；不认识时按 png。 */
internal fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
    "jpeg", "jpg" -> "jpg"
    "webp" -> "webp"
    "gif" -> "gif"
    else -> "png"
}

/** 把图片请求的参数摘要成 tracing/用量用的 JSON（multipart 请求也能记一条）。 */
internal fun imageTracePayload(model: String, request: ImageRequest): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("model", kotlinx.serialization.json.JsonPrimitive(model))
    put("prompt", kotlinx.serialization.json.JsonPrimitive(request.prompt.take(200)))
    put("aspect", kotlinx.serialization.json.JsonPrimitive(request.aspect.wire))
    put("count", kotlinx.serialization.json.JsonPrimitive(request.count))
    if (request.references.isNotEmpty()) put("references", kotlinx.serialization.json.JsonPrimitive(request.references.size))
}
