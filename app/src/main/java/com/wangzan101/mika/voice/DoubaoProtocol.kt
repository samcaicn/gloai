package com.wangzan101.mika.voice

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*

/** Volcengine's native v3 ASR protocol; independent of the Ark chat API. */
internal object DoubaoProtocol {
    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val DURATION = "volc.seedasr.sauc.duration"
    const val CONCURRENT = "volc.seedasr.sauc.concurrent"
    val resources = setOf(DURATION, CONCURRENT)
    const val CHUNK_SAMPLES = 3200 // 200 ms, 16 kHz mono PCM16
    private const val MAX_PAYLOAD = 1024 * 1024

    data class Response(val text: String?, val last: Boolean)
    class ServiceError(val code: Long) : IOException("Doubao ASR code $code")

    fun initial(): ByteArray = packet(0x10, 1, buildJsonObject {
        putJsonObject("audio") {
            put("format", "pcm"); put("codec", "raw")
            put("rate", 16000); put("bits", 16); put("channel", 1)
        }
        putJsonObject("request") {
            put("model_name", "bigmodel"); put("result_type", "full")
            put("enable_itn", true); put("enable_punc", true)
        }
    }.toString().toByteArray(Charsets.UTF_8))

    fun audio(pcm: ShortArray, last: Boolean): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach(data::putShort)
        // Flag 2 indicates the last audio packet without an optional sequence field.
        return packet(if (last) 0x22 else 0x20, 0, data.array())
    }

    private fun packet(typeAndFlags: Int, serialization: Int, payload: ByteArray): ByteArray {
        val zip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(payload) } }.toByteArray()
        return ByteBuffer.allocate(8 + zip.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11.toByte()).put(typeAndFlags.toByte()).put(((serialization shl 4) or 1).toByte()).put(0)
            .putInt(zip.size).put(zip).array()
    }

    fun response(bytes: ByteArray): Response {
        require(bytes.size in 8..MAX_PAYLOAD) { "Invalid ASR packet length" }
        val header = (bytes[0].toInt() and 15) * 4
        require((bytes[0].toInt() ushr 4) == 1 && header >= 4 && header <= bytes.size - 4) { "Invalid ASR header" }
        val type = (bytes[1].toInt() and 255) ushr 4
        val flags = bytes[1].toInt() and 15
        val serialization = (bytes[2].toInt() and 255) ushr 4
        val compression = bytes[2].toInt() and 15
        require(type == 9 || type == 15) { "Unexpected ASR message type" }
        require(flags in 0..3 && compression in 0..1) { "Unsupported ASR packet" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply { position(header) }
        var sequence = 0
        val error = if (type == 15) {
            require(buf.remaining() >= 8)
            buf.int.toLong() and 0xffffffffL
        } else {
            require(serialization == 1)
            if (flags and 1 != 0) { require(buf.remaining() >= 8); sequence = buf.int }
            null
        }
        require(buf.remaining() >= 4)
        val size = buf.int
        require(size >= 0 && size == buf.remaining()) { "Invalid ASR payload length" }
        // Never propagate server error text: it can contain credentials or user audio text.
        if (error != null) throw ServiceError(error)
        val raw = ByteArray(size).also(buf::get)
        val payload = if (compression == 1) GZIPInputStream(raw.inputStream()).use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                require(out.size() + n <= MAX_PAYLOAD) { "ASR payload too large" }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        } else raw
        val root = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        root["code"]?.jsonPrimitive?.longOrNull?.let { if (it != 0L && it != 20000000L) throw ServiceError(it) }
        val body = root["payload_msg"] as? JsonObject ?: root
        val text = when (val result = body["result"]) {
            is JsonObject -> result["text"]?.jsonPrimitive?.contentOrNull
            is JsonArray -> result.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> null // Initial acknowledgement or an empty final packet.
        }
        return Response(text, flags and 2 != 0 || sequence < 0 || root["is_last_package"]?.jsonPrimitive?.booleanOrNull == true)
    }
}

/** Deliberately not a data class: neither toString nor generated copy/component methods expose keys. */
internal class DoubaoCredentials(val apiKey: String = "", val appId: String = "", val accessKey: String = "") {
    val valid: Boolean get() = if (apiKey.isNotBlank()) appId.isBlank() && accessKey.isBlank() else appId.isNotBlank() && accessKey.isNotBlank()
    override fun toString() = "DoubaoCredentials([redacted])"
}
