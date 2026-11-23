package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.vendors.VendorModelLists

/** One field a vendor asks for. Labels live in the UI, keyed by [key]. */
data class VendorField(
    val key: String,
    val secret: Boolean = true,
    val optional: Boolean = false,
    /** Fixed choices (region); empty means free text. */
    val choices: List<String> = emptyList(),
)

/**
 * A built-in voice vendor: one account (the [fields]) and every listen / speak / realtime model it brings.
 * [plan] turns the filled fields into ordinary catalog entries, so a vendor never adds a code path of its own.
 */
data class VoiceVendor(
    val id: String,
    val name: String,
    val fields: List<VendorField>,
    /** Capability counts shown before the account exists. */
    val preview: List<VoiceModel>,
    private val build: (Map<String, String>) -> VoicePresetPlan,
    /** Asks the vendor for this account's full model (and voice) list; null when the vendor has no list endpoint. */
    val lister: VoiceModelLister? = null,
    /** Fixes up a model derived from another one's binding when the vendor ties a binding field to the model id. */
    val retarget: (VoiceModel) -> VoiceModel = { it },
    /** Service id prefixes earlier versions gave this vendor's accounts; such services are this vendor's too. */
    val legacyIds: List<String> = emptyList(),
    /** The vendor's built-in models as they would be under [serviceId]; region-bound vendors derive the region from the id. */
    private val builtins: ((String) -> List<VoiceModel>)? = null,
) {
    /** This vendor's built-in models for one of its services, whose bindings models it lacks can borrow. */
    fun models(serviceId: String): List<VoiceModel> = builtins?.invoke(serviceId) ?: preview.map { it.copy(serviceId = serviceId) }

    fun plan(values: Map<String, String>): VoicePresetPlan = build(values.mapValues { it.value.trim() })

    /** True when [serviceId] is one of this vendor's accounts, made now or by an earlier version (any region). */
    fun owns(serviceId: String): Boolean = (listOf(id) + legacyIds).any { matches(serviceId, it) }

    /** True when [serviceId] is what [plan] makes; re-entering the key of such a service replaces it in place. */
    fun builds(serviceId: String): Boolean = matches(serviceId, id)

    private fun matches(serviceId: String, prefix: String) = serviceId == prefix || serviceId.startsWith("$prefix:")
}

object VoiceVendors {
    const val F_API_KEY = "apiKey"
    const val F_APP_ID = "appId"
    const val F_ACCESS_TOKEN = "accessToken"
    const val F_SECRET_ID = "secretId"
    const val F_SECRET_KEY = "secretKey"
    const val F_API_SECRET = "apiSecret"
    const val F_REGION = "region"
    const val F_WORKSPACE = "workspace"

    const val VOLC_TTS_ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    const val VOLC_TTS_RESOURCE = "seed-tts-2.0"
    const val OPENAI_BASE = "https://api.openai.com/v1"
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val SILICONFLOW_BASE = "https://api.siliconflow.cn/v1"
    const val ZHIPU_BASE = "https://open.bigmodel.cn/api/paas/v4"
    const val GROQ_BASE = "https://api.groq.com/openai/v1"
    const val MINIMAX_CN = "https://api.minimaxi.com/v1/t2a_v2"
    const val MINIMAX_INTL = "https://api.minimax.io/v1/t2a_v2"
    const val QWEN_TTS_BEIJING = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    const val BAILIAN_CN = "https://dashscope.aliyuncs.com"
    const val BAILIAN_INTL = "https://dashscope-intl.aliyuncs.com"
    const val QWEN_TTS_SINGAPORE = "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

    const val REGION_CN = "cn"
    const val REGION_INTL = "intl"
    const val REGION_BEIJING = "beijing"
    const val REGION_SINGAPORE = "singapore"

    private val key = VendorField(F_API_KEY)

    private fun owned(id: String, name: String) = VoiceService(id, name, VoiceAccount.VoiceOwned("preset:$id"))

    private fun need(values: Map<String, String>, field: String): String =
        values[field].orEmpty().ifEmpty { throw VoiceModelError.IncompleteConfig("Missing $field") }

    private fun stt(service: String, id: String, name: String, binding: SttBinding) = VoiceModel(service, id, name, stt = binding)
    private fun tts(service: String, id: String, name: String, binding: TtsBinding) = VoiceModel(service, id, name, tts = binding)

    /** A vendor whose account is one API key and whose models do not depend on a region. */
    private fun keyOnly(
        id: String, name: String, lister: VoiceModelLister? = null, retarget: (VoiceModel) -> VoiceModel = { it },
        models: (String) -> List<VoiceModel>,
    ) = VoiceVendor(
        id = id, name = name, fields = listOf(key), preview = models(id),
        build = { values -> VoicePresetPlan(owned(id, name), VoiceCredentials(apiKey = need(values, F_API_KEY)), models(id)) },
        lister = lister, retarget = retarget, builtins = models,
    )

    // ---- Volcano Engine ----

    private fun volcModels(service: String) = listOf(
        stt(service, VoicePresets.VOLC_STT_MODEL, "Doubao Streaming ASR 2.0",
            SttBinding(SttProtocol.DoubaoStreaming, resourceId = DoubaoProtocol.DURATION)),
        // 音色清单随 app 更新(VolcVoices),不写进存档
        tts(service, VOLC_TTS_RESOURCE, "Doubao TTS 2.0",
            TtsBinding(TtsProtocol.VolcSpeech, VOLC_TTS_ENDPOINT, region = VOLC_TTS_RESOURCE, voice = "zh_female_vv_uranus_bigtts")),
        tts(service, "seed-tts-1.0", "Doubao TTS 1.0",
            TtsBinding(TtsProtocol.VolcSpeech, VOLC_TTS_ENDPOINT, region = "seed-tts-1.0", voice = "zh_female_cancan_mars_bigtts")),
        VoiceModel(service, VolcRealtimeConfig.MODEL, "Doubao Realtime",
            realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex, VolcRealtimeProtocol.ENDPOINT, VolcRealtimeConfig.DEFAULT_SPEAKER)),
    )

    private val volc = VoiceVendor(
        id = VoicePresets.VOLC_SERVICE, name = "Volcano Engine",
        fields = listOf(
            VendorField(F_API_KEY, optional = true),
            VendorField(F_APP_ID, secret = false, optional = true),
            VendorField(F_ACCESS_TOKEN, optional = true),
        ),
        preview = volcModels(VoicePresets.VOLC_SERVICE),
        build = { v ->
            val apiKey = v[F_API_KEY].orEmpty()
            val appId = v[F_APP_ID].orEmpty()
            val token = v[F_ACCESS_TOKEN].orEmpty()
            val credentials = when {
                apiKey.isNotEmpty() -> VoiceCredentials(apiKey = apiKey)
                appId.isNotEmpty() && token.isNotEmpty() -> VoiceCredentials(appId = appId, accessKey = token)
                else -> throw VoiceModelError.IncompleteConfig("Enter an API key, or both App ID and access token")
            }
            VoicePresetPlan(owned(VoicePresets.VOLC_SERVICE, "Volcano Engine"), credentials, volcModels(VoicePresets.VOLC_SERVICE))
        },
        // 豆包合成的资源 ID 就是模型 ID,手填的模型要带上它自己的
        retarget = { m -> m.tts?.let { m.copy(tts = it.copy(region = m.id)) } ?: m },
        legacyIds = listOf("stt-doubao"),
        builtins = ::volcModels,
    )

    // ---- Alibaba Cloud Bailian ----

    private fun bailianModels(service: String, region: BailianRegion, workspace: String) = BailianProtocol.MODELS.take(2).map { model ->
        stt(service, model, if (model == BailianProtocol.DEFAULT_MODEL) "Fun-ASR Realtime" else "Paraformer Realtime v2",
            SttBinding(SttProtocol.BailianStreaming, endpoint = region.endpoint(workspace), region = region.name, businessSpace = workspace))
    } + tts(service, "qwen3-tts-flash", "Qwen TTS Flash",
        TtsBinding(TtsProtocol.BailianQwenTts, if (region == BailianRegion.Singapore) QWEN_TTS_SINGAPORE else QWEN_TTS_BEIJING,
            voice = "Cherry"))

    private val bailian = VoiceVendor(
        id = "bailian", name = "Alibaba Cloud Bailian",
        fields = listOf(key, VendorField(F_REGION, secret = false, choices = listOf(REGION_BEIJING, REGION_SINGAPORE)),
            VendorField(F_WORKSPACE, secret = false, optional = true)),
        preview = bailianModels("bailian:beijing", BailianRegion.Beijing, ""),
        build = { v ->
            val apiKey = need(v, F_API_KEY)
            val region = if (v[F_REGION] == REGION_SINGAPORE) BailianRegion.Singapore else BailianRegion.Beijing
            val workspace = BailianProtocol.workspace(v[F_WORKSPACE].orEmpty())
                ?: throw VoiceModelError.IncompleteConfig("Business space ID is not valid")
            val id = "bailian:${region.name.lowercase()}"
            VoicePresetPlan(owned(id, "Bailian ${region.name}"), VoiceCredentials(apiKey = apiKey), bailianModels(id, region, workspace))
        },
        lister = VoiceModelLister { service, credentials, models ->
            val host = if (service.id.endsWith(REGION_SINGAPORE)) BAILIAN_INTL else BAILIAN_CN
            VendorModelLists.bailian(host).list(service, credentials, models)
        },
        legacyIds = listOf("stt-bailian"),
        builtins = { id -> bailianModels(id, if (id.endsWith(REGION_SINGAPORE)) BailianRegion.Singapore else BailianRegion.Beijing, "") },
    )

    // ---- MiniMax ----

    private fun miniMaxModels(service: String, endpoint: String): List<VoiceModel> {
        val voices = listOf("male-qn-qingse", "female-shaonv", "female-yujie", "presenter_male", "presenter_female")
        return listOf(
            "speech-2.8-hd" to "Speech 2.8 HD", "speech-2.8-turbo" to "Speech 2.8 Turbo",
            "speech-2.6-hd" to "Speech 2.6 HD", "speech-2.6-turbo" to "Speech 2.6 Turbo",
            "speech-02-hd" to "Speech 02 HD", "speech-02-turbo" to "Speech 02 Turbo",
        ).map { (id, name) ->
            tts(service, id, name, TtsBinding(TtsProtocol.MiniMaxT2a, endpoint, voice = voices.first(), voices = voices))
        }
    }

    private val miniMax = VoiceVendor(
        id = "minimax", name = "MiniMax",
        fields = listOf(key, VendorField(F_REGION, secret = false, choices = listOf(REGION_CN, REGION_INTL))),
        preview = miniMaxModels("minimax:cn", MINIMAX_CN),
        build = { v ->
            val apiKey = need(v, F_API_KEY)
            val intl = v[F_REGION] == REGION_INTL
            val id = if (intl) "minimax:intl" else "minimax:cn"
            VoicePresetPlan(owned(id, if (intl) "MiniMax Global" else "MiniMax"), VoiceCredentials(apiKey = apiKey),
                miniMaxModels(id, if (intl) MINIMAX_INTL else MINIMAX_CN))
        },
        lister = VendorModelLists.miniMax(),
    )

    // ---- OpenAI-shaped vendors ----

    private val openAi = keyOnly("openai", "OpenAI", VendorModelLists.openAi(OPENAI_BASE)) { s ->
        val voices = VoiceVoices.tts(TtsProtocol.OpenAiSpeech)
        listOf("gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1").map {
            stt(s, it, it, SttBinding(SttProtocol.AudioTranscriptions, endpoint = OPENAI_BASE))
        } + listOf("gpt-4o-mini-tts", "tts-1").map {
            tts(s, it, it, TtsBinding(TtsProtocol.OpenAiSpeech, OPENAI_BASE, voice = "alloy", voices = voices))
        } + VoiceModel(s, "gpt-realtime", "gpt-realtime",
            realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime, OpenAiCallProtocol.ENDPOINT, OpenAiCallProtocol.VOICE))
    }

    private val gemini = keyOnly("gemini", "Google Gemini", VendorModelLists.gemini(GEMINI_BASE)) { s ->
        listOf(stt(s, "gemini-2.5-flash", "Gemini 2.5 Flash", SttBinding(SttProtocol.GeminiInline, endpoint = GEMINI_BASE))) +
            listOf("gemini-3.8-flash-tts" to "Gemini 3.8 Flash TTS", "gemini-3.8-flash-lite-tts" to "Gemini 3.8 Flash Lite TTS").map { (id, name) ->
                tts(s, id, name, TtsBinding(TtsProtocol.GeminiTts, GEMINI_BASE, voice = "Kore"))
            }
    }

    // 系统音色写作「模型:名字」,派生出来的说话模型换成它自己的前缀
    private val siliconFlow = keyOnly("siliconflow", "SiliconFlow", VendorModelLists.siliconFlow(SILICONFLOW_BASE), retarget = { m ->
        m.tts?.takeIf { it.voices.all { v -> !v.startsWith("${m.id}:") } }?.let { b ->
            val (voices, names) = VendorModelLists.siliconFlowModelVoices(m.id, emptyList())
            m.copy(tts = b.copy(voice = voices.first(), voices = voices, voiceNames = names))
        } ?: m
    }) { s ->
        val cosy = "FunAudioLLM/CosyVoice2-0.5B"
        val voices = VendorModelLists.SILICONFLOW_VOICES.map { "$cosy:$it" }
        listOf(
            stt(s, "FunAudioLLM/SenseVoiceSmall", "SenseVoice Small", SttBinding(SttProtocol.AudioTranscriptions, endpoint = SILICONFLOW_BASE)),
            tts(s, cosy, "CosyVoice2", TtsBinding(TtsProtocol.OpenAiSpeech, SILICONFLOW_BASE, voice = voices[1], voices = voices)),
        )
    }

    private val zhipu = keyOnly("zhipu", "Zhipu") { s ->
        val voices = listOf("tongtong", "chuichui", "xiaochen", "jam", "kazi", "douji", "luodo")
        listOf(
            stt(s, "glm-asr-2512", "GLM-ASR-2512", SttBinding(SttProtocol.AudioTranscriptions, endpoint = ZHIPU_BASE)),
            stt(s, "glm-asr", "GLM-ASR", SttBinding(SttProtocol.AudioTranscriptions, endpoint = ZHIPU_BASE)),
            tts(s, "glm-tts", "GLM-TTS", TtsBinding(TtsProtocol.OpenAiSpeech, ZHIPU_BASE, voice = voices.first(), voices = voices)),
        )
    }

    private val groq = keyOnly("groq", "Groq", VendorModelLists.groq(GROQ_BASE)) { s ->
        val voices = listOf("autumn", "diana", "hannah", "austin", "daniel", "troy")
        listOf("whisper-large-v3-turbo", "whisper-large-v3").map {
            stt(s, it, it, SttBinding(SttProtocol.AudioTranscriptions, endpoint = GROQ_BASE))
        } + tts(s, "canopylabs/orpheus-v1-english", "Orpheus English",
            TtsBinding(TtsProtocol.OpenAiSpeech, GROQ_BASE, voice = voices.first(), voices = voices))
    }

    // ---- Vendors with their own protocols ----

    private val elevenLabs = keyOnly("elevenlabs", "ElevenLabs", VendorModelLists.elevenLabs()) { s ->
        val voices = listOf("21m00Tcm4TlvDq8ikWAM", "EXAVITQu4vr4xnSDxMaL", "pNInz6obpgDQGcFmaJgB", "ErXwobaYiN019PkySvjV")
        listOf(stt(s, "scribe_v1", "Scribe", SttBinding(SttProtocol.ElevenLabsUpload))) +
            listOf("eleven_multilingual_v2" to "Multilingual v2", "eleven_flash_v2_5" to "Flash v2.5").map { (id, name) ->
                tts(s, id, name, TtsBinding(TtsProtocol.ElevenLabsSpeech, voice = voices.first(), voices = voices))
            }
    }

    private val deepgram = keyOnly("deepgram", "Deepgram", VendorModelLists.deepgram()) { s ->
        val voices = listOf("aura-2-thalia-en", "aura-2-andromeda-en", "aura-2-helena-en", "aura-2-apollo-en", "aura-2-arcas-en")
        listOf(
            stt(s, "nova-3", "Nova-3", SttBinding(SttProtocol.DeepgramUpload)),
            tts(s, "aura-2", "Aura 2", TtsBinding(TtsProtocol.DeepgramSpeak, voice = voices.first(), voices = voices)),
        )
    }

    private fun azureModels(service: String, region: String): List<VoiceModel> {
        val voices = listOf("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-XiaoyiNeural", "en-US-JennyNeural", "en-US-GuyNeural")
        return listOf(
            stt(service, "speech-to-text", "Speech to Text", SttBinding(SttProtocol.AzureShortAudio, region = region)),
            tts(service, "neural-tts", "Neural TTS", TtsBinding(TtsProtocol.AzureSpeech, region = region, voice = voices.first(), voices = voices)),
        )
    }

    private val azure = VoiceVendor(
        id = "azure", name = "Azure Speech",
        fields = listOf(key, VendorField(F_REGION, secret = false)),
        preview = azureModels("azure:eastasia", "eastasia"),
        build = { v ->
            val apiKey = need(v, F_API_KEY)
            val region = need(v, F_REGION).lowercase()
            if (!region.matches(Regex("[a-z0-9]{2,32}"))) throw VoiceModelError.IncompleteConfig("Region is not valid")
            val id = "azure:$region"
            VoicePresetPlan(owned(id, "Azure Speech $region"), VoiceCredentials(apiKey = apiKey), azureModels(id, region))
        },
        lister = VendorModelLists.azure(),
    )

    private fun tencentModels(service: String): List<VoiceModel> {
        val voices = listOf("101001", "101002", "101004", "101005", "101015")
        return listOf(
            stt(service, "16k_zh", "Sentence ASR 16k", SttBinding(SttProtocol.TencentSentence)),
            stt(service, "8k_zh", "Sentence ASR 8k (phone)", SttBinding(SttProtocol.TencentSentence)),
            tts(service, "tts", "Speech Synthesis", TtsBinding(TtsProtocol.TencentVoice, voice = voices.first(), voices = voices)),
        )
    }

    /** SecretId → appId and SecretKey → apiKey: the existing three secret slots, no storage change. */
    private val tencent = VoiceVendor(
        id = "tencent", name = "Tencent Cloud",
        fields = listOf(VendorField(F_SECRET_ID), VendorField(F_SECRET_KEY)),
        preview = tencentModels("tencent"),
        build = { v ->
            VoicePresetPlan(owned("tencent", "Tencent Cloud"),
                VoiceCredentials(apiKey = need(v, F_SECRET_KEY), appId = need(v, F_SECRET_ID)), tencentModels("tencent"))
        },
    )

    private fun xfyunModels(service: String): List<VoiceModel> {
        val voices = listOf("xiaoyan", "aisjiuxu", "aisxping", "aisjinger", "aisbabyxu")
        return listOf(
            stt(service, "iat", "Dictation", SttBinding(SttProtocol.XfyunDictation)),
            tts(service, "tts", "Online Synthesis", TtsBinding(TtsProtocol.XfyunSynthesis, voice = voices.first(), voices = voices)),
        )
    }

    /** APPID → appId, APIKey → apiKey, APISecret → accessKey. */
    private val xfyun = VoiceVendor(
        id = "xfyun", name = "iFlytek",
        fields = listOf(VendorField(F_APP_ID, secret = false), VendorField(F_API_KEY), VendorField(F_API_SECRET)),
        preview = xfyunModels("xfyun"),
        build = { v ->
            VoicePresetPlan(owned("xfyun", "iFlytek"),
                VoiceCredentials(apiKey = need(v, F_API_KEY), appId = need(v, F_APP_ID), accessKey = need(v, F_API_SECRET)),
                xfyunModels("xfyun"))
        },
    )

    /** Display order: domestic first, then global. */
    val all: List<VoiceVendor> = listOf(
        bailian, volc, miniMax, siliconFlow, zhipu, tencent, xfyun,
        openAi, gemini, azure, elevenLabs, deepgram, groq,
    )

    fun byId(id: String): VoiceVendor? = all.firstOrNull { it.id == id }

    /** The vendor that created [serviceId], if any. */
    fun forService(serviceId: String): VoiceVendor? = all.firstOrNull { it.owns(serviceId) }
}
