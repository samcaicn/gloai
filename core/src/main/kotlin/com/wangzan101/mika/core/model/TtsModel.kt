package com.wangzan101.mika.core.model

import java.io.ByteArrayOutputStream
import java.util.Base64

/** 把文字变成声音的模型。和对话模型分开：没有上下文、工具调用和思维链。 */
interface TtsModel {
    /** 模型名，只用于记录。 */
    val name: String

    val capabilities: TtsCapabilities

    /** 失败时抛 [ModelError] 或 [TtsError]。 */
    suspend fun synthesize(request: TtsRequest): TtsResponse
}

/**
 * 一次朗读请求。[voice] 为空时用模型/设置里的默认音色；[speed] 为空时用服务商默认；
 * [format] 是希望的输出格式（wav/mp3/opus/aac/flac），服务商不一定都支持。
 */
data class TtsRequest(
    val text: String,
    val voice: String? = null,
    val speed: Float? = null,
    val format: String = "wav",
)

/** 合成好的音频。原始 PCM 已由适配器包成 WAV；[sampleRate] 读不出来时为 null。 */
class TtsResponse(val bytes: ByteArray, val mime: String, val sampleRate: Int? = null, val durationMs: Long? = null)

/**
 * 一个声音模型能做什么。[voices] 为 null 表示不限制/未知（音色名由服务商校验）；
 * [maxChars] 是单次文本上限；[formats] 是能输出的容器格式。
 */
data class TtsCapabilities(
    val voices: Set<String>? = null,
    val maxChars: Int = 4000,
    val formats: Set<String> = setOf("wav", "mp3"),
)

/** 声音模型自己的限制：请求不合法、返回的不是音频、音频太大。 */
sealed class TtsError(message: String) : Exception(message) {
    class Unsupported(message: String) : TtsError(message)
    class BadAudio(message: String) : TtsError(message)
    class TooLarge(val bytes: Long) : TtsError("audio is too large")
}

/** 生成音频的最大字节数；超过不往播放器/工作区交。 */
internal const val MAX_TTS_BYTES = 20 * 1024 * 1024

/** 从文件头认音频容器；认不出为 null。只认 TTS 常见的几种。 */
internal fun sniffAudioMime(bytes: ByteArray): String? = when {
    bytes.size >= 4 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE" -> "audio/wav"
    bytes.size >= 3 && ascii(bytes, 0, 3) == "ID3" -> "audio/mpeg"
    bytes.size >= 2 && u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xF6) == 0xF0 -> "audio/aac"
    bytes.size >= 2 && u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xE0) == 0xE0 -> "audio/mpeg"
    bytes.size >= 4 && ascii(bytes, 0, 4) == "OggS" -> "audio/ogg"
    bytes.size >= 4 && ascii(bytes, 0, 4) == "fLaC" -> "audio/flac"
    else -> null
}

/** 原始 16 位单声道 PCM 包成 WAV；Gemini TTS 常见这种返回。 */
internal fun wrapPcm16Wav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val bits = 16
    val block = channels * bits / 8
    val out = ByteArrayOutputStream(44 + pcm.size)
    fun le32(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF) }
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    out.write("RIFF".toByteArray(Charsets.ISO_8859_1)); le32(36 + pcm.size); out.write("WAVE".toByteArray(Charsets.ISO_8859_1))
    out.write("fmt ".toByteArray(Charsets.ISO_8859_1)); le32(16); le16(1); le16(channels); le32(sampleRate)
    le32(sampleRate * block); le16(block); le16(bits)
    out.write("data".toByteArray(Charsets.ISO_8859_1)); le32(pcm.size); out.write(pcm)
    return out.toByteArray()
}

/** Gemini inlineData 的 mime 里常带 `rate=24000`；读不出来用 24000。 */
internal fun pcmRateOf(mime: String?): Int = mime?.let { Regex("rate=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    ?.takeIf { it in 8_000..192_000 } ?: 24_000

internal fun decodeBase64Audio(data: String, mime: String?): ByteArray = try {
    Base64.getDecoder().decode(data)
} catch (e: IllegalArgumentException) {
    throw TtsError.BadAudio("response is not valid base64")
}

internal fun checkTtsSize(bytes: ByteArray) {
    if (bytes.size > MAX_TTS_BYTES) throw TtsError.TooLarge(bytes.size.toLong())
}

private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
private fun ascii(b: ByteArray, at: Int, n: Int): String = String(b, at, n, Charsets.ISO_8859_1)
