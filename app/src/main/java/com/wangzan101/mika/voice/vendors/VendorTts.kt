package com.wangzan101.mika.voice.vendors

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsError
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.vendors.VendorHttp.long
import com.wangzan101.mika.voice.vendors.VendorHttp.path
import com.wangzan101.mika.voice.vendors.VendorHttp.string
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/** Raw synthesized speech: 16-bit mono PCM at [sampleRate]. */
internal class PcmAudio(val pcm: ByteArray, val sampleRate: Int)

/** One vendor call: a piece of text (within the vendor's length limit) to PCM. */
internal fun interface PieceSynthesizer {
    suspend fun synthesize(text: String, voice: String, speed: Float): PcmAudio
}

/**
 * A [TtsModel] over a vendor's own API. Long text is cut at sentence marks into pieces the vendor accepts, each piece
 * is synthesized in turn and the PCM joined into one WAV.
 */
internal class VendorTtsModel(
    override val name: String,
    override val capabilities: TtsCapabilities,
    private val defaultVoice: String,
    private val pieceChars: Int,
    private val piece: PieceSynthesizer,
) : TtsModel {
    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val text = request.text.trim()
        if (text.isEmpty()) throw TtsError.Unsupported("text is empty")
        val voice = request.voice?.takeIf { it.isNotBlank() } ?: defaultVoice
        val speed = (request.speed ?: 1f).coerceIn(0.5f, 2f)
        val out = ByteArrayOutputStream()
        var rate = 0
        for (part in VendorTts.pieces(text, pieceChars)) {
            val audio = piece.synthesize(part, voice, speed)
            if (rate == 0) rate = audio.sampleRate
            val pcm = if (audio.sampleRate == rate) audio.pcm
            else Pcm16.toBytes(Pcm16.resample(Pcm16.toSamples(audio.pcm), audio.sampleRate, rate))
            out.write(pcm)
        }
        if (out.size() == 0) throw TtsError.BadAudio("no audio returned")
        return TtsResponse(Pcm16.wav(out.toByteArray(), rate), "audio/wav", rate)
    }
}

internal object VendorTts {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val BREAKS = "。！？；!?;\n，,、：:"

    /** Splits [text] into pieces of at most [limit] chars, preferring sentence and then clause marks. */
    fun pieces(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            val cut = window.indexOfLast { it in "。！？；!?;\n" }.takeIf { it >= limit / 3 }
                ?: window.indexOfLast { it in BREAKS || it == ' ' }.takeIf { it >= limit / 3 }
                ?: (limit - 1)
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out.filter { it.isNotEmpty() }
    }

    /** A WAV or headerless PCM body, normalized to [PcmAudio]. */
    fun pcmOf(bytes: ByteArray, assumedRate: Int): PcmAudio =
        Pcm16.parseWav(bytes)?.let { PcmAudio(Pcm16.toBytes(it.samples), it.sampleRate) } ?: PcmAudio(bytes, assumedRate)

    // ---- Volcano Engine speech synthesis (HTTP, chunked JSON lines) ----

    fun volcBody(text: String, voice: String, speed: Float): String = buildJsonObject {
        putJsonObject("user") { put("uid", "mika") }
        putJsonObject("req_params") {
            put("text", text)
            put("speaker", voice)
            putJsonObject("audio_params") {
                put("format", "pcm")
                put("sample_rate", 24_000)
                put("speech_rate", ((speed - 1f) * 100).toInt().coerceIn(-50, 100))
            }
        }
    }.toString()

    fun volc(endpoint: String, resourceId: String, apiKey: String, appId: String, accessKey: String) = PieceSynthesizer { text, voice, speed ->
        val request = Request.Builder().url(endpoint)
            .apply {
                if (apiKey.isNotBlank()) header("X-Api-Key", apiKey)
                else { header("X-Api-App-Id", appId); header("X-Api-Access-Key", accessKey) }
            }
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(volcBody(text, voice, speed).toRequestBody(JSON)).build()
        PcmAudio(parseVolc(VendorHttp.text(request)), 24_000)
    }

    fun parseVolc(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (line in VendorHttp.jsonObjects(body)) {
            val code = line.path("code").long() ?: 0
            if (code != 0L && code != 20_000_000L) {
                val message = line.path("message").string().orEmpty()
                throw if (code in 45_000_000L..45_000_099L || message.contains("auth", true)) ModelError.Auth()
                else ModelError.Server(400, "volc $code $message".trim())
            }
            line.path("data").string()?.takeIf { it.isNotEmpty() }?.let { out.write(Base64.getDecoder().decode(it)) }
        }
        return out.toByteArray()
    }

    // ---- Bailian Qwen TTS ----

    fun qwenBody(model: String, text: String, voice: String): String = buildJsonObject {
        put("model", model)
        putJsonObject("input") {
            put("text", text)
            put("voice", voice)
        }
    }.toString()

    fun qwen(endpoint: String, model: String, apiKey: String) = PieceSynthesizer { text, voice, _ ->
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey")
            .post(qwenBody(model, text, voice).toRequestBody(JSON)).build()
        val root = VendorHttp.jsonObject(request)
        root.path("code").string()?.takeIf { it.isNotEmpty() }?.let { code ->
            throw if (code.contains("Auth", true) || code.contains("ApiKey", true)) ModelError.Auth()
            else ModelError.Server(400, "$code ${root.path("message").string().orEmpty()}".trim())
        }
        val data = root.path("output", "audio", "data").string()?.takeIf { it.isNotEmpty() }
        val bytes = if (data != null) Base64.getDecoder().decode(data) else {
            val url = root.path("output", "audio", "url").string() ?: throw ModelError.BadResponse("no audio in response")
            VendorHttp.bytes(Request.Builder().url(url).build())
        }
        pcmOf(bytes, 24_000)
    }

    // ---- MiniMax T2A ----

    fun miniMaxBody(model: String, text: String, voice: String, speed: Float): String = buildJsonObject {
        put("model", model)
        put("text", text)
        put("stream", false)
        putJsonObject("voice_setting") {
            put("voice_id", voice)
            put("speed", speed)
        }
        putJsonObject("audio_setting") {
            put("sample_rate", 24_000)
            put("format", "pcm")
            put("channel", 1)
        }
        put("output_format", "hex")
    }.toString()

    fun miniMax(endpoint: String, model: String, apiKey: String) = PieceSynthesizer { text, voice, speed ->
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey")
            .post(miniMaxBody(model, text, voice, speed).toRequestBody(JSON)).build()
        parseMiniMax(VendorHttp.jsonObject(request))
    }

    fun parseMiniMax(root: JsonObject): PcmAudio {
        val status = root.path("base_resp", "status_code").long() ?: 0
        if (status != 0L) {
            val message = root.path("base_resp", "status_msg").string().orEmpty()
            throw when (status) {
                1004L, 2049L -> ModelError.Auth()
                1002L, 1039L -> ModelError.RateLimited(null)
                else -> ModelError.Server(400, "minimax $status $message".trim())
            }
        }
        val hex = root.path("data", "audio").string() ?: throw ModelError.BadResponse("no audio in response")
        val rate = root.path("extra_info", "audio_sample_rate").long()?.toInt() ?: 24_000
        return pcmOf(VendorHttp.hexToBytes(hex), rate)
    }

    // ---- ElevenLabs ----

    fun elevenLabs(model: String, apiKey: String) = PieceSynthesizer { text, voice, speed ->
        val body = buildJsonObject {
            put("text", text)
            put("model_id", model)
            putJsonObject("voice_settings") { put("speed", speed.coerceIn(0.7f, 1.2f)) }
        }.toString()
        val request = Request.Builder().url("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=pcm_24000")
            .header("xi-api-key", apiKey).post(body.toRequestBody(JSON)).build()
        PcmAudio(VendorHttp.bytes(request), 24_000)
    }

    // ---- Deepgram ----

    fun deepgram(apiKey: String) = PieceSynthesizer { text, voice, _ ->
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/speak?model=$voice&encoding=linear16&sample_rate=24000&container=none")
            .header("Authorization", "Token $apiKey")
            .post(buildJsonObject { put("text", text) }.toString().toRequestBody(JSON)).build()
        PcmAudio(VendorHttp.bytes(request), 24_000)
    }

    // ---- Azure ----

    fun azureSsml(text: String, voice: String, speed: Float): String {
        val lang = voice.split('-').take(2).joinToString("-").ifBlank { "zh-CN" }
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val rate = ((speed - 1f) * 100).toInt()
        return "<speak version='1.0' xml:lang='$lang'><voice name='$voice'>" +
            "<prosody rate='${if (rate >= 0) "+" else ""}$rate%'>$escaped</prosody></voice></speak>"
    }

    fun azure(region: String, apiKey: String) = PieceSynthesizer { text, voice, speed ->
        val request = Request.Builder().url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            .header("User-Agent", "mika")
            .post(azureSsml(text, voice, speed).toRequestBody("application/ssml+xml".toMediaType())).build()
        PcmAudio(VendorHttp.bytes(request), 24_000)
    }

    // ---- Tencent Cloud ----

    fun tencentPayload(text: String, voice: String, speed: Float): String = buildJsonObject {
        put("Text", text)
        put("SessionId", UUID.randomUUID().toString())
        put("VoiceType", voice.toLongOrNull() ?: 101001L)
        put("Codec", "pcm")
        put("SampleRate", 16_000)
        // -2..6 where 0 is normal; map 0.5..2x roughly onto it.
        put("Speed", ((speed - 1f) * 2f).coerceIn(-2f, 6f))
    }.toString()

    fun tencent(secretId: String, secretKey: String) = PieceSynthesizer { text, voice, speed ->
        val payload = tencentPayload(text, voice, speed)
        val headers = TencentAuth.headers("tts", "TextToVoice", "2019-08-23", payload, secretId, secretKey, region = "ap-guangzhou")
        val request = Request.Builder().url("https://tts.tencentcloudapi.com")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toRequestBody(JSON)).build()
        val response = VendorStt.parseTencent(VendorHttp.jsonObject(request))
        val audio = response.path("Audio").string() ?: throw ModelError.BadResponse("no audio in response")
        PcmAudio(Base64.getDecoder().decode(audio), 16_000)
    }

    // ---- iFlytek online synthesis (WebSocket) ----

    fun xfyunFrame(appId: String, text: String, voice: String, speed: Float): String = buildJsonObject {
        putJsonObject("common") { put("app_id", appId) }
        putJsonObject("business") {
            put("aue", "raw")
            put("auf", "audio/L16;rate=16000")
            put("vcn", voice)
            put("speed", (speed * 50).toInt().coerceIn(0, 100))
            put("tte", "UTF8")
        }
        putJsonObject("data") {
            put("status", 2)
            put("text", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        }
    }.toString()

    fun xfyun(appId: String, apiKey: String, apiSecret: String) = PieceSynthesizer { text, voice, speed ->
        val socket = VendorHttp.openSocket(XfyunAuth.url("tts-api.xfyun.cn", "/v2/tts", apiKey, apiSecret))
        try {
            socket.ws.send(xfyunFrame(appId, text, voice, speed))
            val out = ByteArrayOutputStream()
            withTimeout(30_000) {
                for (frame in socket.frames) {
                    val root = VendorHttp.json.parseToJsonElement(frame).jsonObject
                    val code = root.path("code").long() ?: 0
                    if (code != 0L) throw VendorStt.xfyunError(code)
                    root.path("data", "audio").string()?.takeIf { it.isNotEmpty() }
                        ?.let { out.write(Base64.getDecoder().decode(it)) }
                    if (root.path("data", "status").long() == 2L) break
                }
            }
            PcmAudio(out.toByteArray(), 16_000)
        } finally {
            socket.ws.close(1000, null)
        }
    }
}
