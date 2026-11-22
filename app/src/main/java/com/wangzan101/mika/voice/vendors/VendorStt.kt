package com.wangzan101.mika.voice.vendors

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.voice.vendors.VendorHttp.long
import com.wangzan101.mika.voice.vendors.VendorHttp.path
import com.wangzan101.mika.voice.vendors.VendorHttp.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Upload-style recognition for vendors with their own APIs: one finished utterance (16 kHz mono PCM, wrapped as
 * WAV) in, its text out. [language] is a BCP-47 tag or null for automatic.
 */
internal fun interface UtteranceRecognizer {
    suspend fun recognize(wav: ByteArray, language: String?): String
}

internal object VendorStt {
    private val WAV = "audio/wav".toMediaType()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** ISO 639-1 part of a BCP-47 tag ("zh-CN" → "zh"). */
    fun iso(language: String?): String? = language?.substringBefore('-')?.lowercase()?.takeIf { it.length in 2..3 }

    fun elevenLabs(apiKey: String, model: String) = UtteranceRecognizer { wav, language ->
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", model)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV))
            .apply { iso(language)?.let { addFormDataPart("language_code", it) } }
            .build()
        val request = Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey).post(body).build()
        VendorHttp.jsonObject(request).path("text").string().orEmpty().trim()
    }

    fun deepgramUrl(model: String, language: String?): String {
        val lang = iso(language)
        return "https://api.deepgram.com/v1/listen?model=$model&smart_format=true" +
            if (lang != null) "&language=$lang" else "&detect_language=true"
    }

    fun deepgram(apiKey: String, model: String) = UtteranceRecognizer { wav, language ->
        val request = Request.Builder().url(deepgramUrl(model, language))
            .header("Authorization", "Token $apiKey").post(wav.toRequestBody(WAV)).build()
        parseDeepgram(VendorHttp.jsonObject(request))
    }

    fun parseDeepgram(root: JsonObject): String =
        root.path("results", "channels", 0, "alternatives", 0, "transcript").string().orEmpty().trim()

    /** Azure needs a full locale; a bare or missing tag falls back to Chinese (the default UI audience). */
    fun azureLocale(language: String?): String = when {
        language == null -> "zh-CN"
        language.contains('-') -> language
        language == "en" -> "en-US"
        language == "zh" -> "zh-CN"
        else -> "$language-${language.uppercase()}"
    }

    fun azure(apiKey: String, region: String) = UtteranceRecognizer { wav, language ->
        val url = "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1" +
            "?language=${azureLocale(language)}&format=simple"
        val request = Request.Builder().url(url)
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .post(wav.toRequestBody("audio/wav; codecs=audio/pcm; samplerate=${Wav.RATE}".toMediaType())).build()
        parseAzure(VendorHttp.jsonObject(request))
    }

    fun parseAzure(root: JsonObject): String = when (root.path("RecognitionStatus").string()) {
        "Success" -> root.path("DisplayText").string().orEmpty().trim()
        "NoMatch", "InitialSilenceTimeout", "BabbleTimeout" -> ""
        else -> throw ModelError.BadResponse("recognition failed: ${root.path("RecognitionStatus").string()}")
    }

    /** Tencent one-sentence recognition; the 8k engines get the audio resampled to 8 kHz. */
    fun tencent(secretId: String, secretKey: String, engine: String) = UtteranceRecognizer { wav, _ ->
        val audio = if (engine.startsWith("8k")) {
            val decoded = Pcm16.parseWav(wav) ?: throw ModelError.BadResponse("audio is not WAV")
            Pcm16.wav(Pcm16.toBytes(Pcm16.resample(decoded.samples, decoded.sampleRate, 8000)), 8000)
        } else wav
        val payload = tencentSentencePayload(engine, audio)
        val headers = TencentAuth.headers("asr", "SentenceRecognition", "2019-06-14", payload, secretId, secretKey)
        val request = Request.Builder().url("https://asr.tencentcloudapi.com").apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toRequestBody(JSON)).build()
        parseTencent(VendorHttp.jsonObject(request)).path("Result").string().orEmpty().trim()
    }

    fun tencentSentencePayload(engine: String, wav: ByteArray): String = buildJsonObject {
        put("EngSerViceType", engine)
        put("SourceType", 1)
        put("VoiceFormat", "wav")
        put("Data", Base64.getEncoder().encodeToString(wav))
        put("DataLen", wav.size)
    }.toString()

    /** Unwraps `Response`, turning a Tencent error object into a categorized error. */
    fun parseTencent(root: JsonObject): JsonObject {
        val response = root["Response"]?.jsonObject ?: throw ModelError.BadResponse("missing Response")
        val error = response["Error"] as? JsonObject ?: return response
        val code = error.path("Code").string().orEmpty()
        throw when {
            code.startsWith("AuthFailure") -> ModelError.Auth()
            code.startsWith("RequestLimitExceeded") -> ModelError.RateLimited(null)
            else -> ModelError.Server(400, code)
        }
    }

    /** iFlytek dictation: streams the utterance in 40 ms frames (sent faster than real time) and joins the words. */
    fun xfyun(appId: String, apiKey: String, apiSecret: String) = UtteranceRecognizer { wav, language ->
        val pcm = Pcm16.parseWav(wav)?.let { Pcm16.toBytes(Pcm16.resample(it.samples, it.sampleRate, 16_000)) }
            ?: throw ModelError.BadResponse("audio is not WAV")
        val socket = VendorHttp.openSocket(XfyunAuth.url("iat-api.xfyun.cn", "/v2/iat", apiKey, apiSecret))
        try {
            val frame = 1280
            var offset = 0
            var first = true
            while (offset < pcm.size || first) {
                val end = minOf(pcm.size, offset + frame)
                socket.ws.send(xfyunAudioFrame(appId, iso(language), if (first) 0 else 1, pcm.copyOfRange(offset, end)))
                first = false
                offset = end
                delay(10)
            }
            socket.ws.send(xfyunAudioFrame(appId, iso(language), 2, ByteArray(0)))
            val text = StringBuilder()
            withTimeout(20_000) {
                for (frameText in socket.frames) {
                    val root = VendorHttp.json.parseToJsonElement(frameText).jsonObject
                    val code = root.path("code").long() ?: 0
                    if (code != 0L) throw xfyunError(code)
                    text.append(xfyunWords(root))
                    if (root.path("data", "status").long() == 2L) break
                }
            }
            text.toString().trim()
        } finally {
            socket.ws.close(1000, null)
        }
    }

    fun xfyunAudioFrame(appId: String, iso: String?, status: Int, pcm: ByteArray): String = buildJsonObject {
        if (status == 0) {
            putJsonObject("common") { put("app_id", appId) }
            putJsonObject("business") {
                put("language", if (iso == "en") "en_us" else "zh_cn")
                put("domain", "iat")
                if (iso != "en") put("accent", "mandarin")
                put("vad_eos", 10_000)
            }
        }
        putJsonObject("data") {
            put("status", status)
            put("format", "audio/L16;rate=16000")
            put("encoding", "raw")
            put("audio", Base64.getEncoder().encodeToString(pcm))
        }
    }.toString()

    fun xfyunWords(root: JsonObject): String {
        val ws = root.path("data", "result", "ws") as? JsonArray ?: return ""
        return ws.joinToString("") { item -> item.path("cw", 0, "w").string().orEmpty() }
    }

    fun xfyunError(code: Long): ModelError = when (code) {
        10105L, 10106L, 10107L, 11200L, 11201L -> ModelError.Auth()
        10139L -> ModelError.RateLimited(null)
        else -> ModelError.Server(400, "xfyun $code")
    }
}
