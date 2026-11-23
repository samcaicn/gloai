package com.wangzan101.mika.voice.vendors

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.voice.model.ListedModel
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceListing
import com.wangzan101.mika.voice.model.VoiceModelLister
import com.wangzan101.mika.voice.vendors.VendorHttp.path
import com.wangzan101.mika.voice.vendors.VendorHttp.string
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.Locale

/**
 * Each vendor's own model / voice list endpoints. Parsing is split from the HTTP call so every response shape is
 * covered by plain unit tests. A capability the vendor states is used as is; one guessed from a name only orders
 * the list, a model nothing is known about comes back with no capability and stays selectable.
 */
internal object VendorModelLists {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val STT = setOf(VoiceCapability.STT)
    private val TTS = setOf(VoiceCapability.TTS)
    private val RT = setOf(VoiceCapability.REALTIME)

    private suspend fun get(url: String, vararg headers: Pair<String, String>): JsonElement {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        return parse(VendorHttp.text(request))
    }

    private suspend fun post(url: String, body: String, vararg headers: Pair<String, String>): JsonElement {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON)).build()
        return parse(VendorHttp.text(request))
    }

    private fun parse(text: String): JsonElement = runCatching { VendorHttp.json.parseToJsonElement(text) }
        .getOrElse { throw ModelError.BadResponse("list response is not JSON", it) }

    /** A part of a listing that may fail on its own (voices, an unconfirmed endpoint) without losing the rest. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun bearer(key: String) = "Authorization" to "Bearer $key"

    private fun JsonElement?.array(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.first(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it].string()?.takeIf(String::isNotBlank) }

    /** The object list of a `{data:[…]}` / `{models:[…]}` / bare `[…]` response. */
    fun entries(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.array()
        is JsonObject -> listOf("data", "models", "voices", "result").firstNotNullOfOrNull { key -> root[key].array().ifEmpty { null } }
            ?: root.path("output", "models").array()
        else -> emptyList()
    }

    // ---- OpenAI-shaped lists (OpenAI, Groq, MiniMax): ids only, capability from the name ----

    fun openAiShaped(root: JsonElement, caps: (String) -> Set<VoiceCapability>): List<ListedModel> =
        entries(root).mapNotNull { it.first("id", "model_id", "model") }.distinct().sorted().map { ListedModel(it, it, caps(it)) }

    fun openAiCaps(id: String): Set<VoiceCapability> {
        val n = id.lowercase()
        return when {
            "realtime" in n -> RT
            "transcribe" in n || n.startsWith("whisper") -> STT
            "tts" in n -> TTS
            else -> emptySet()
        }
    }

    fun groqCaps(id: String): Set<VoiceCapability> {
        val n = id.lowercase()
        return when {
            "whisper" in n -> STT
            "tts" in n || "orpheus" in n || "playai" in n -> TTS
            else -> emptySet()
        }
    }

    fun miniMaxCaps(id: String): Set<VoiceCapability> = if (id.lowercase().startsWith("speech-")) TTS else emptySet()

    fun openAi(base: String) = VoiceModelLister { _, credentials, _ ->
        VoiceListing(openAiShaped(get("${base.trimEnd('/')}/models", bearer(credentials.apiKey)), ::openAiCaps))
    }

    fun groq(base: String) = VoiceModelLister { _, credentials, _ ->
        VoiceListing(openAiShaped(get("${base.trimEnd('/')}/models", bearer(credentials.apiKey)), ::groqCaps))
    }

    // ---- Gemini: methods say what can run, the name says speak vs listen ----

    fun gemini(root: JsonElement): List<ListedModel> = entries(root).mapNotNull { model ->
        val id = model.first("name")?.removePrefix("models/") ?: return@mapNotNull null
        val methods = model["supportedGenerationMethods"].strings()
        // 只能 embed / predict 的模型走不了 generateContent,这是服务商自己说的,不是猜
        if (methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
        val n = id.lowercase()
        val caps = when {
            "tts" in n -> TTS
            "image" in n || n.startsWith("gemma") || "embedding" in n -> emptySet()
            n.startsWith("gemini") -> STT
            else -> emptySet()
        }
        ListedModel(id, model.first("displayName") ?: id, caps)
    }

    fun geminiVoices(root: JsonElement): List<String> = entries(root).mapNotNull { it.first("voiceName", "name", "id")?.substringAfterLast('/') }

    fun gemini(base: String) = VoiceModelLister { _, credentials, _ ->
        val key = "x-goog-api-key" to credentials.apiKey
        val b = base.trimEnd('/')
        val models = mutableListOf<ListedModel>()
        var token = ""
        for (page in 1..PAGES) {
            val root = get("$b/models?pageSize=1000" + if (token.isEmpty()) "" else "&pageToken=$token", key)
            models += gemini(root)
            token = (root as? JsonObject)?.get("nextPageToken").string().orEmpty()
            if (token.isEmpty()) break
        }
        VoiceListing(models, optional { geminiVoices(get("$b/voices", key)) }.orEmpty())
    }

    // ---- SiliconFlow: type / sub_type are the vendor's own classification ----

    fun siliconFlow(audio: JsonElement, stt: JsonElement): List<ListedModel> {
        val listen = entries(stt).mapNotNull { it.first("id") }.toSet()
        return entries(audio).mapNotNull { it.first("id") }.distinct().sorted().map { id ->
            ListedModel(id, id, if (id in listen) STT else TTS)
        }
    }

    /** The account's own cloned voices, `{result:[{uri, customName, model}]}`, grouped by the model they were made for. */
    fun siliconFlowVoices(root: JsonElement): Map<String, List<Pair<String, String>>> = entries(root).mapNotNull { v ->
        val uri = v.first("uri") ?: return@mapNotNull null
        Triple(v.first("model").orEmpty(), uri, v.first("customName", "name") ?: uri)
    }.groupBy({ it.first }, { it.second to it.third })

    /** System voices are `model:name`; the same eight names work on each speak model. */
    val SILICONFLOW_VOICES = listOf("alex", "anna", "bella", "benjamin", "charles", "claire", "david", "diana")

    fun siliconFlowModelVoices(model: String, custom: List<Pair<String, String>>): Pair<List<String>, Map<String, String>> {
        val system = SILICONFLOW_VOICES.map { "$model:$it" }
        return (custom.map { it.first } + system) to (custom.toMap() + SILICONFLOW_VOICES.associateBy({ "$model:$it" }, { it }))
    }

    fun siliconFlow(base: String) = VoiceModelLister { _, credentials, _ ->
        val b = base.trimEnd('/')
        val auth = bearer(credentials.apiKey)
        val models = siliconFlow(get("$b/models?type=audio", auth), get("$b/models?type=audio&sub_type=speech-to-text", auth))
        val custom = optional { siliconFlowVoices(get("$b/audio/voice/list", auth)) }.orEmpty()
        VoiceListing(models.map { m ->
            if (VoiceCapability.TTS !in m.caps) m
            else siliconFlowModelVoices(m.id, custom[m.id].orEmpty()).let { (voices, names) -> m.copy(voices = voices, voiceNames = names) }
        })
    }

    // ---- ElevenLabs ----

    fun elevenLabsModels(root: JsonElement): List<ListedModel> = entries(root).mapNotNull { m ->
        val id = m.first("model_id") ?: return@mapNotNull null
        // 服务商标了不能合成的(只做变声等)不放进说话列表
        if (m["can_do_text_to_speech"].bool() == false) return@mapNotNull null
        ListedModel(id, m.first("name") ?: id, TTS)
    }

    fun elevenLabsVoices(root: JsonElement): List<Pair<String, String>> = entries(root).mapNotNull { v ->
        val id = v.first("voice_id") ?: return@mapNotNull null
        id to (v.first("name") ?: id)
    }

    fun elevenLabs(base: String = "https://api.elevenlabs.io") = VoiceModelLister { _, credentials, _ ->
        val key = "xi-api-key" to credentials.apiKey
        val models = elevenLabsModels(get("$base/v1/models", key))
        val voices = mutableListOf<Pair<String, String>>()
        optional {
            var token = ""
            repeat(PAGES) {
                val page = get("$base/v2/voices?page_size=100" + if (token.isEmpty()) "" else "&next_page_token=$token", key)
                voices += elevenLabsVoices(page)
                token = if ((page as? JsonObject)?.get("has_more").bool() == true) page.path("next_page_token").string().orEmpty() else ""
                if (token.isEmpty()) return@optional
            }
        }
        VoiceListing(models, voices.map { it.first }, voices.toMap())
    }

    // ---- Deepgram: speak models are voices, grouped under their architecture ----

    fun deepgram(root: JsonElement): List<ListedModel> {
        val listen = root.path("stt").array().filter { it["batch"].bool() != false }
            .mapNotNull { it.first("canonical_name", "name") }.distinct().sorted().map { ListedModel(it, it, STT) }
        val speak = root.path("tts").array().mapNotNull { v ->
            val id = v.first("canonical_name") ?: return@mapNotNull null
            val family = v.first("architecture") ?: DEEPGRAM_VOICE.matchEntire(id)?.groupValues?.get(1) ?: id
            val lang = v["languages"].strings().firstOrNull()
            Triple(family, id, listOfNotNull(v.first("name")?.replaceFirstChar { it.uppercase() }, lang).joinToString(" · ").ifEmpty { id })
        }.groupBy { it.first }.map { (family, voices) ->
            ListedModel(family, family, TTS, voices.map { it.second }, voices.associate { it.second to it.third })
        }
        return listen + speak
    }

    private val DEEPGRAM_VOICE = Regex("""(.+)-[a-z]+-[a-z]{2}(?:-[a-z]+)?""")

    fun deepgram(base: String = "https://api.deepgram.com") = VoiceModelLister { _, credentials, _ ->
        VoiceListing(deepgram(get("$base/v1/models", "Authorization" to "Token ${credentials.apiKey}")))
    }

    // ---- MiniMax: the models list is not confirmed to carry speech models; the voice list is ----

    fun miniMaxVoices(root: JsonElement): List<Pair<String, String>> = listOf("system_voice", "voice_cloning", "voice_generation")
        .flatMap { group -> (root as? JsonObject)?.get(group).array() }
        .mapNotNull { v -> v.first("voice_id")?.let { it to (v.first("voice_name") ?: it) } }

    /** The API host behind a T2A endpoint (`https://api.minimaxi.com/v1/t2a_v2` → `https://api.minimaxi.com`). */
    fun hostOf(endpoint: String): String = runCatching { URI(endpoint).let { "${it.scheme}://${it.host}" } }.getOrDefault(endpoint)

    fun miniMax() = VoiceModelLister { _, credentials, models ->
        val host = hostOf(models.firstNotNullOfOrNull { it.tts?.endpoint?.takeIf(String::isNotBlank) } ?: "https://api.minimaxi.com")
        val auth = bearer(credentials.apiKey)
        val listed = optional { openAiShaped(get("$host/v1/models", auth), ::miniMaxCaps) }.orEmpty()
        val voices = miniMaxVoices(post("$host/v1/get_voice", buildJsonObject { put("voice_type", "all") }.toString(), auth))
        VoiceListing(listed, voices.map { it.first }, voices.toMap())
    }

    // ---- Bailian: the model list states capabilities ----

    fun bailian(root: JsonElement): List<ListedModel> = entries(root).mapNotNull { m ->
        val id = m.first("model", "model_id", "id", "name") ?: return@mapNotNull null
        val stated = (m["capabilities"] ?: m["capability"]).let { it.strings().ifEmpty { listOfNotNull(it.string()) } }.map { it.lowercase() }
        val caps = buildSet {
            if (stated.any { "asr" in it || "speech-recognition" in it }) add(VoiceCapability.STT)
            if (stated.any { "tts" in it || "text-to-speech" in it }) add(VoiceCapability.TTS)
        }
        // 服务商标了能力、却没有任何语音能力的(文本、图像模型)不进语音列表
        if (stated.isNotEmpty() && caps.isEmpty()) return@mapNotNull null
        ListedModel(id, m.first("name", "display_name") ?: id, caps)
    }

    fun bailian(host: String) = VoiceModelLister { _, credentials, _ ->
        val out = mutableListOf<ListedModel>()
        for (page in 1..PAGES) {
            val root = get("$host/api/v1/models?page_no=$page&page_size=$PAGE_SIZE", bearer(credentials.apiKey))
            val rows = entries(root)
            out += bailian(root)
            if (rows.size < PAGE_SIZE) break
        }
        VoiceListing(out)
    }

    // ---- Azure: no model list, a full voice list per region ----

    fun azureVoices(root: JsonElement, locale: Locale = Locale.getDefault()): List<Pair<String, String>> {
        val rows = entries(root).mapNotNull { v ->
            val id = v.first("ShortName") ?: return@mapNotNull null
            Triple(id, v.first("Locale").orEmpty(), listOfNotNull(v.first("LocalName", "DisplayName"), v.first("Locale")).joinToString(" · "))
        }
        // 手机语言的音色排前面,其余按地区
        val lang = locale.language.lowercase()
        return rows.sortedWith(compareBy({ !it.second.lowercase().startsWith("$lang-") }, { it.second }))
            .map { it.first to it.third.ifEmpty { it.first } }
    }

    fun azure() = VoiceModelLister { _, credentials, models ->
        val region = models.firstNotNullOfOrNull { it.tts?.region?.takeIf(String::isNotBlank) } ?: return@VoiceModelLister VoiceListing()
        val voices = azureVoices(get("https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list",
            "Ocp-Apim-Subscription-Key" to credentials.apiKey))
        VoiceListing(voices = voices.map { it.first }, voiceNames = voices.toMap())
    }

    private const val PAGES = 10
    private const val PAGE_SIZE = 100
}
