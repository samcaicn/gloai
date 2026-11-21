package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.agent.ContextLimits
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.ChatGptSession
import com.wangzan101.mika.core.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.oauth.AccountSession
import com.wangzan101.mika.core.oauth.AccountTokens
import com.wangzan101.mika.core.oauth.OauthTokenResult
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.core.oauth.XaiAuth
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.OpenAiSpeechModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.GeminiImageModel
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageModel
import com.wangzan101.mika.core.model.OpenAiImagesModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.search.GoogleGroundingSearch
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.usage.ProactiveBudget
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.usage.TokenBudgetLimits
import com.wangzan101.mika.core.usage.DeepSeekPriceTable
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 一个模型接入:属于哪个服务商([providerId])、模型名、能力。接口类型、地址和名字照抄所属服务商(建模型时用);
 * Key 存在服务商上。这是存储内部的一层:用户只选「哪个用途用哪个模型」,配置随选择建、没人用就删。
 */
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val baseUrl: String,
    val model: String,
    val capabilities: Capabilities,
    /** 界面上显示的名字。 */
    val name: String = "",
    /** 所属 [Provider] 的 id;为空时由 [ProviderStore.save] 按接口类型、地址和名字归到对应服务商。 */
    val providerId: String = "",
) {
    /**
     * 接口类型。[OpenAiResponses] 用在模型列表给某个模型写明 Responses 协议时、预设「OpenAI」,以及添加自定义服务商时选了它;
     * [ChatGpt] 是用 ChatGPT 账号登录、用套餐额度调用的那一家:凭据是登录(见 [ChatGptSession]),不是 API Key。
     * [Xai] 同样是账号登录(设备码),凭据是登录不是 API Key,调用 xAI 自己的 Responses 接口。
     */
    enum class Kind {
        OpenAiCompat, Anthropic, Gemini, OpenAiResponses, ChatGpt, Xai;

        /**
         * 这个接口类型发请求走哪种协议。用它筛掉接口写明了不支持的模型,见 [com.wangzan101.mika.ui.ModelSetupViewModel]
         * 的试调用。账号登录的两家(ChatGpt、Xai)都走 Responses;这两家不走那张表单的试调用(登录时已经核对过),
         * 所以这里只求写对,不影响筛谁。
         */
        val protocol: ModelProtocol
            get() = when (this) {
                OpenAiCompat -> ModelProtocol.ChatCompletions
                Anthropic -> ModelProtocol.Messages
                Gemini -> ModelProtocol.GenerateContent
                OpenAiResponses, ChatGpt, Xai -> ModelProtocol.Responses
            }

        companion object {
            /** 模型单独指定的协议用哪种接口类型的适配器。 */
            fun of(protocol: ModelProtocol): Kind = when (protocol) {
                ModelProtocol.ChatCompletions -> OpenAiCompat
                ModelProtocol.Messages -> Anthropic
                ModelProtocol.GenerateContent -> Gemini
                ModelProtocol.Responses -> OpenAiResponses
            }

            /** 添加自定义服务商时可选的接口类型(填地址和 API Key 的)。Responses 排最后,和 README 里的次序一致。 */
            val withApiKey: List<Kind> = listOf(OpenAiCompat, Anthropic, Gemini, OpenAiResponses)
        }
    }
}

/**
 * 一个已连接的服务商:名字、接口类型、地址;Key 单独加密保存。
 * [models] 是最近一次列出的模型(「测试并保存」或刷新时记下,和配置存在同一个文件里);
 * 为 null 表示还没列过,空列表表示列过但服务不提供列表(或一个也没有)。
 * [sessionHeader] 是它要求放对话会话号的请求头名(来自预设,见 [ProviderPreset.sessionHeader]);不要的为 null。
 * [verifiedModels] 是在这家试调用(见 [ProviderStore.probe])得到过回复的模型名:不提供列表的服务商,模型名是手填的,
 * 没在这里的就没核实过(旧版本存下的都没有,那时不提供列表的服务商不试调用)。
 * [typedModels] 是用户手填、试调用通过后加进来的模型名(列表里没有、或这家列不出模型):刷新列表不丢,选模型时标「手填」。
 * [savedUntested] 是用户跳过测试直接存下的一家(见 [ProviderStore.connect]):既没列过模型、也没试调用过,
 * 所以 [models] 是空列表、[verifiedModels] 是空的,但那些「这家不提供列表」的说法对它不成立。
 * 之后成功列过一次模型或试通过一次就清掉(见 [ProviderStore.rememberModels]、[ProviderStore.markVerified])。
 */
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val models: List<ListedModel>? = null,
    val sessionHeader: String? = null,
    val verifiedModels: Set<String> = emptySet(),
    val typedModels: Set<String> = emptySet(),
    /**
     * Key 是怎么来的:用账号授权换来的(目前只有 [ProviderStore.OPENROUTER_SIGN_IN])为那一家的标记,用户自己填的为 null。
     * 标了的服务商详情里是账号登录的样子(退出登录、到对方页面删 Key),不是「更换 Key」。
     */
    val signIn: String? = null,
    val savedUntested: Boolean = false,
    /** 按模型名保留，用途切换后也能找回。 */
    val contextSettings: Map<String, ModelContextSettings> = emptyMap(),
    /** 用户说的某个模型能不能看图(按模型名):比列表和预设初值都算数,见 [ModelOptions.seesImages]。 */
    val visionOverrides: Map<String, Boolean> = emptyMap(),
    /** 这家每天所有模型、用途合计的已报告 Token 预算；null 不限制。 */
    val dailyTokenBudget: Long? = null,
    /** OpenAI 直连和 ChatGPT 登录的速度档位，按模型名保存。 */
    val serviceTiers: Map<String, OpenAiServiceTier> = emptyMap(),
) {
    val key: String get() = ProviderConnection.keyOf(kind, baseUrl, name)

    fun supportsServiceTier(model: String, endpoint: ModelEndpoint? = models?.firstOrNull { it.id == model }?.endpoint): Boolean {
        if (kind != ProviderConfig.Kind.OpenAiResponses && kind != ProviderConfig.Kind.ChatGpt) return false
        if (endpoint != null && endpoint.protocol != ModelProtocol.Responses) return false
        val url = (endpoint?.adapterBaseUrl ?: baseUrl).toHttpUrlOrNull()
        return url?.isHttps == true && url.host == "api.openai.com"
    }

    internal fun requestedServiceTier(model: String, endpoint: ModelEndpoint?): OpenAiServiceTier? =
        serviceTiers[model]?.takeIf { supportsServiceTier(model, endpoint) }

    /**
     * [model] 在这家还没测试通过:这家没有可用的列表(不提供,或跳过测试存下时没列过),模型名是手填的,又没在
     * [verifiedModels] 里。账号登录的那几家模型名是固定的,不算。服务商页对这样在用的模型给「重新测试」。
     */
    fun untested(model: String): Boolean =
        kind != ProviderConfig.Kind.ChatGpt && kind != ProviderConfig.Kind.Xai && models?.isEmpty() == true && model !in verifiedModels
}

/** OkHttp 把超时换算为 Int 毫秒，秒数必须在换算后仍能存下。 */
internal val MODEL_READ_TIMEOUT_SECONDS = 1..(Int.MAX_VALUE / 1_000)

/** 各用途备援链最长几位。支持多达 8 层瀑布级联（Waterfall Cascade）。 */
internal const val MAX_MAIN_FALLBACKS = 8
internal const val MAX_FALLBACKS = 8

/**
 * 临时:真机验证「折叠后任务还能干完」之前,给所有模型每 16 轮折一次历史(超出 keepRecent 后);
 * 验证通过后改为设置项或按模型调,再删掉这个常量。
 */
internal const val TEMP_COMPACTION_EVERY_ROUNDS = 16

/** 留空的窗口沿用所选模型配置，留空的触发点交给核心自动计算，留空的读超时用全局默认(120 秒)。 */
data class ModelContextSettings(val windowTokens: Int? = null, val triggerTokens: Int? = null, val readTimeoutSeconds: Int? = null) {
    fun limits(modelWindow: Int): ContextLimits = ContextLimits.of(windowTokens ?: modelWindow, triggerTokens)

    fun validFor(modelWindow: Int): Boolean = (windowTokens == null || windowTokens > 0) &&
        (triggerTokens == null || (triggerTokens > 0 && triggerTokens == limits(modelWindow).trigger)) &&
        (readTimeoutSeconds == null || readTimeoutSeconds in MODEL_READ_TIMEOUT_SECONDS)
}

/** 一个用途选了什么:一个具体模型、用主模型,或不用(只有看图可以不用)。 */
sealed interface RoleChoice {
    /** 用 [configId] 这条模型配置。 */
    data class Model(val configId: String) : RoleChoice
    data object SameAsMain : RoleChoice
    data object Off : RoleChoice
}

/**
 * 三个用途各选了什么,都是用户明说的,没有按能力推断的默认分配。
 * [main] 是主模型的配置 id,为 null 表示还没选;看图默认不用,轻量默认用主模型。
 * 现已支持所有角色 (Main, Vision, Light) 拥有独立的备援链 (Waterfall Chain)。
 */
data class RoleChoices(
    val main: String? = null,
    val vision: RoleChoice = RoleChoice.Off,
    val light: RoleChoice = RoleChoice.SameAsMain,
    /** 主模型的备援配置 id(项目 3),按接手顺序;主模型失败时换不换由 Failover 的决策表定。 */
    val mainFallbacks: List<String> = emptyList(),
    /** 视觉模型的备援配置 id，按接手顺序。 */
    val visionFallbacks: List<String> = emptyList(),
    /** 轻量模型的备援配置 id，按接手顺序。 */
    val lightFallbacks: List<String> = emptyList(),
    /** 按用途保存,同一个模型可在不同用途选不同档位;「用主模型」连同它的档位一起跟随。 */
    val reasoningEfforts: Map<ModelRole, String> = emptyMap(),
) {
    /** 获取指定角色的备援配置列表 */
    fun fallbacks(role: ModelRole): List<String> = when (role) {
        ModelRole.Main -> mainFallbacks
        ModelRole.Vision -> visionFallbacks
        ModelRole.Light -> lightFallbacks
    }

    /** 更新指定角色的备援配置列表 */
    fun withFallbacks(role: ModelRole, list: List<String>): RoleChoices = when (role) {
        ModelRole.Main -> copy(mainFallbacks = list)
        ModelRole.Vision -> copy(visionFallbacks = list)
        ModelRole.Light -> copy(lightFallbacks = list)
    }

    /** 这个用途的选择;主模型没选时为 null。 */
    operator fun get(role: ModelRole): RoleChoice? = when (role) {
        ModelRole.Main -> main?.let(RoleChoice::Model)
        ModelRole.Vision -> vision
        ModelRole.Light -> light
    }

    /** 这个用途实际用的配置 id:「用主模型」取主模型的;不用、或主模型还没选时为 null。 */
    fun resolve(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Model -> c.configId
        RoleChoice.SameAsMain -> main
        RoleChoice.Off, null -> null
    }

    /** 两个用途是否解析到同一条配置;任一用途没配时不算相同。 */
    fun sameModel(first: ModelRole, second: ModelRole): Boolean {
        val a = resolve(first) ?: return false
        return a == resolve(second)
    }

    /** 有用途在用的配置 id;其余的配置没人用,会被删掉。 */
    val referenced: Set<String> get() = ModelRole.entries.mapNotNull(::resolve).toSet() + mainFallbacks + visionFallbacks + lightFallbacks

    /** 改一个用途;这个用途不接受的选择(主模型只能是具体模型,轻量不能不用)返回 null。 */
    fun with(role: ModelRole, choice: RoleChoice): RoleChoices? = (when (role) {
        ModelRole.Main -> (choice as? RoleChoice.Model)?.let { copy(main = it.configId) }
        ModelRole.Vision -> copy(vision = choice)
        ModelRole.Light -> if (choice == RoleChoice.Off) null else copy(light = choice)
    })?.let { next ->
        if (get(role) == choice) next else next.copy(reasoningEfforts = reasoningEfforts - role)
    }

    fun reasoningEffort(role: ModelRole): String? = when (get(role)) {
        is RoleChoice.Model -> reasoningEfforts[role]
        RoleChoice.SameAsMain -> main?.let { reasoningEfforts[ModelRole.Main] }
        else -> null
    }

    /** 指向 [valid] 之外的选择退回默认:主模型变成没选,看图不用,轻量用主模型。 */
    fun keepOnly(valid: Set<String>): RoleChoices {
        fun RoleChoice.gone() = this is RoleChoice.Model && configId !in valid
        return RoleChoices(
            main = main?.takeIf { it in valid },
            vision = if (vision.gone()) RoleChoice.Off else vision,
            light = if (light.gone()) RoleChoice.SameAsMain else light,
            mainFallbacks = mainFallbacks.filter { it in valid },
            visionFallbacks = visionFallbacks.filter { it in valid },
            lightFallbacks = lightFallbacks.filter { it in valid },
            reasoningEfforts = reasoningEfforts.filterKeys { role -> (get(role) as? RoleChoice.Model)?.configId in valid },
        )
    }
}

/**
 * 建一个模型客户端时额外给的:[headers] 每个请求都带(客户端标识、会话号,见 [ClientHeaders]);
 * [maxOutputTokens] 限制回复长度(连接测试用),null 按适配器默认;[reasoningEffort] 是这个用途选的思考强度;
 * [thinkingTypes] 是模型列表写明这个模型接受的思考方式(见 [ListedModel.thinkingTypes]),没说为 null;
 * [readTimeoutSeconds] 是这条配置单独的读超时,慢速本地推理(#295)可调大,null 用全局默认(120 秒)。
 */
data class ClientSetup(
    val headers: Map<String, String> = emptyMap(),
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
    val thinkingTypes: List<String>? = null,
    val readTimeoutSeconds: Int? = null,
    val serviceTier: OpenAiServiceTier? = null,
)

/** 「测试并保存」试调用(见 [ProviderStore.probe])的结果。 */
sealed interface ProbeResult {
    /** 模型调用了试用的工具:接口通,也会调用工具。 */
    data object ToolCalled : ProbeResult
    /** 接口通,模型只回了话、没调用工具:Mika 干活靠工具,这个模型当不了主模型。 */
    data object NoToolCall : ProbeResult
    /** 请求失败;[error] 带着服务端的报错。 */
    data class Failed(val error: ModelError) : ProbeResult
}

/**
 * 服务商、模型配置的存取与用途选择。配置存成 JSON 文件,API Key 经 [KeyCipher] 加密后另存一个文件,每个服务商一份。
 *
 * 用户只管两层:连了哪些服务商、每个用途([RoleChoices])用哪个模型。模型配置([ProviderConfig])是内部的一层:
 * 选模型时当场建,没有用途再用它就删掉(见 [prune];Key 按服务商存,删配置不动 Key)。
 * 用途选什么就是什么,不看能力标记;选具体模型时把对应标记记为 true(见 [choose])。
 *
 * 旧版本的文件:只有模型配置、Key 按配置 id 存的,读入时按 [ProviderConnection.keyOf] 归成服务商,
 * Key 取那组里任一条的,原来按配置 id 存的 Key 清掉(见 [migrate]);用途只记了显式指定的,
 * 读入时把那时实际生效的分配一次写明(见 [migrateRoles])。已迁移好的不再动,重复执行没有副作用。
 *
 * 拿来调用的模型只从 [modelFor] 出去,出去前一律套上计量(见 [metered]),每次调用都记到 [usage]:
 * 谁调用、调用几次都不用各自记账。
 */
class ProviderStore(
    dir: File,
    private val cipher: KeyCipher,
    private val usage: UsageSink = UsageSink { _, _ -> },
    /** 每次请求前实时查询这家当前本地日的已报告 Token；默认不启用每日限制。 */
    private val dailyUsage: (String) -> Long = { 0L },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 发给模型服务的 User-Agent;App 给 `Mika/<版本>`。 */
    private val userAgent: String = "Mika",
    /** 列模型:接口类型、地址、Key、每个请求都带的头。 */
    private val modelLister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = ::listAvailable,
    /** 「用 ChatGPT 登录」的授权服务(换令牌、刷新、注销)。 */
    val chatGptAuth: ChatGptAuth = defaultChatGptAuth,
    /** xAI 订阅登录(设备码、刷新)。 */
    val xaiAuth: XaiAuth = defaultXaiAuth,
    /** OpenRouter 账号授权(换 Key)。 */
    val openRouterAuth: OpenRouterAuth = defaultOpenRouterAuth,
    /** 主动能力今天已报告的 Token(各服务商合计)、它的每日预算、今天已发出的请求数;每次请求前实时读。 */
    private val proactiveUsage: () -> Long = { 0L },
    private val proactiveLimit: () -> Long = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
    private val proactiveCalls: () -> Long = { 0L },
    /**
     * 建可调用的模型:配置、Key、服务商列表里给这个模型单独指定的接口(没有为 null,按配置的接口类型和地址)、请求头等。
     * 为 null 时用内置的适配器(测试里换成假的)。
     */
    modelFactory: ((ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel)? = null,
) {
    private val modelFactory: (ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel = modelFactory ?: ::buildModel
    /** 每个 ChatGPT 服务商一份:同一时刻只有一个在换令牌。 */
    private val chatGptTokens = mutableMapOf<String, ChatGptTokens>()
    /** 每个订阅账号一份,和 ChatGPT 一样同一时刻只有一个在换令牌。 */
    private val accountTokens = mutableMapOf<String, AccountTokens>()
    private val configFile = File(dir, CONFIG_FILE)
    private val keyFile = File(dir, KEY_FILE)
    private val chatGptHostFile = File(dir, CHATGPT_HOST_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    /** 已连接的服务商,按连接的先后。 */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    // 换 Key 时服务商列表可能没变，额度页用版本号作废旧结果。
    private val _providerKeyRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    internal val providerKeyRevisions = _providerKeyRevisions.asStateFlow()

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val _roles = MutableStateFlow(RoleChoices())
    /** 三个用途各选了什么;指向的配置都在 [configs] 里。 */
    val roles: StateFlow<RoleChoices> = _roles.asStateFlow()

    /** 服务商 id → 加密后的 Key。 */
    private var keys: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<ProviderConfig> = _configs.value

    fun provider(id: String): Provider? = _providers.value.firstOrNull { it.id == id }

    /**
     * 新增或替换同 id 的模型配置(新增的要再交给 [choose],否则下次读入时没人用会被删掉)。
     * 配置挂到 [ProviderConfig.providerId] 指的服务商下(接口类型、地址、名字以服务商的为准);
     * 没指或指的不存在,就按接口类型、地址和名字找对应的服务商,找不到新建一个。
     * [apiKey] 是那个服务商的 Key:null 表示不改,空串表示清除。
     */
    @Synchronized
    fun save(cfg: ProviderConfig, apiKey: String?) {
        val p = provider(cfg.providerId) ?: findOrAddProvider(ProviderConnection.displayName(cfg), cfg.kind, cfg.baseUrl)
        val attached = cfg.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name)
        val list = _configs.value
        val i = list.indexOfFirst { it.id == cfg.id }
        _configs.value = if (i >= 0) list.toMutableList().also { it[i] = attached } else list + attached
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
    }

    /** 服务商的所有配置密钥 (KeyPool)。 */
    fun providerKeys(providerId: String): List<String> {
        val raw = providerKey(providerId) ?: return emptyList()
        return KeyPoolManager.shared.parseKeys(raw)
    }

    /** 向服务商密钥池中追加新密钥。 */
    @Synchronized
    fun addProviderKey(providerId: String, newKey: String) {
        val current = providerKeys(providerId)
        val added = KeyPoolManager.shared.parseKeys(newKey)
        val combined = (current + added).distinct()
        setKey(providerId, combined.joinToString(", "))
    }

    /** 从服务商密钥池中移除指定密钥。 */
    @Synchronized
    fun removeProviderKey(providerId: String, keyToRemove: String) {
        val current = providerKeys(providerId)
        val remaining = current.filterNot { it == keyToRemove }
        setKey(providerId, remaining.joinToString(", "))
    }

    /** 服务商当前活跃可用的密钥 (优先从 KeyPool 中自动轮询、避开限流密钥)。 */
    fun activeApiKey(providerId: String): String? {
        val raw = providerKey(providerId) ?: return null
        KeyPoolManager.shared.register(providerId, raw)
        return KeyPoolManager.shared.getActiveKey(providerId) ?: providerKeys(providerId).firstOrNull()
    }

    /** 这个模型配置所属服务商的 Key。动态优先从 KeyPool 中返回健康的活跃密钥。 */
    fun apiKey(configId: String): String? =
        _configs.value.firstOrNull { it.id == configId }?.let { activeApiKey(it.providerId) }

    /** 服务商的 Key 原文。解不开(例如密钥库被系统清掉)时当作没有 Key。 */
    fun providerKey(providerId: String): String? {
        val enc = synchronized(this) { keys[providerId] } ?: return null
        return runCatching { String(cipher.decrypt(decode(enc))) }.getOrNull()
    }

    /** 只为仍匹配这次额度查询的服务商配置取 Key。 */
    @Synchronized
    internal fun allowanceKey(provider: Provider, revision: Long): String? =
        if (matchesAllowanceConnection(provider, revision)) providerKey(provider.id) else null

    internal fun matchesAllowanceConnection(provider: Provider, revision: Long): Boolean {
        val current = provider(provider.id) ?: return false
        return current.baseUrl == provider.baseUrl && current.kind == provider.kind && current.signIn == provider.signIn &&
            (_providerKeyRevisions.value[provider.id] ?: 0L) == revision
    }

    /** 这个服务商存了 Key 没有(本地服务可以不填)。 */
    fun hasKey(providerId: String): Boolean = synchronized(this) { providerId in keys }

    /**
     * 「测试并保存」连上了一个服务商:存下它(名字、接口类型、地址、Key)和列出的模型。
     * 接口类型、地址和名字都相同的服务商已经有了,就更新那一个(换 Key、换列表),不重复添加。
     * [apiKey] 为 null 时不改已存的 Key。[verified] 是这次试调用得到回复的模型名,记进 [Provider.verifiedModels];
     * [typed] 是其中用户手填的,记进 [Provider.typedModels]。
     * [signIn] 是 Key 的来历(见 [Provider.signIn]):用户自己填 Key 的为 null,授权来的为那一家的标记。
     * 两种来历各是各的服务商(地址、名字一样也不合并):授权不会顶掉用户自己填的 Key,退出登录也只删授权来的那把。
     * [untested] 为真是用户跳过测试直接存下的(见 [Provider.savedUntested]):一个模型都没列、也没试调用。
     * 已经有这一家、而且它列过模型或核实过时,不会因为这个参数被重新标成没测过。
     */
    @Synchronized
    fun connect(
        name: String, kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, models: List<ListedModel>,
        sessionHeader: String? = ProviderPresets.forBaseUrl(baseUrl)?.sessionHeader,
        verified: Set<String> = emptySet(),
        signIn: String? = null,
        typed: Set<String> = emptySet(),
        untested: Boolean = false,
    ): Provider {
        val found = findOrAddProvider(name, kind, baseUrl, signIn)
        // 跳过测试时没有列表可存:这家之前列过的留着,不要用空表把已有的列表盖掉
        val listed = if (untested && models.isEmpty()) found.models ?: models else models
        val p = found.copy(
            models = listed, sessionHeader = sessionHeader,
            verifiedModels = found.verifiedModels + verified, typedModels = found.typedModels + typed,
            // 这次没测、这家也没有核实过的模型、也没列过列表,才是「没测过」;别的情况一律按测过
            savedUntested = untested && verified.isEmpty() && found.verifiedModels.isEmpty() && listed.isEmpty(),
        )
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        repairUnavailableReasoningEfforts()
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
        return p
    }

    /** 记下服务商最近一次列出的模型。列出来了,就说明「没测过」不再成立(跳过测试存下的那家在这里清掉标记)。 */
    @Synchronized
    fun rememberModels(providerId: String, models: List<ListedModel>) {
        if (provider(providerId) == null) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(models = models, savedUntested = false) else it
        }
        repairUnavailableReasoningEfforts()
        writeConfig()
    }

    /**
     * 记下 [model] 在这家试调用得到了回复(见 [Provider.verifiedModels])。
     * 试通过了就说明「没测过」不再成立,跳过测试存下的那家在这里清掉标记——这个模型本来就在
     * [Provider.verifiedModels] 里时也要清,不能直接返回。
     */
    @Synchronized
    fun markVerified(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.verifiedModels && !p.savedUntested) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(verifiedModels = it.verifiedModels + model, savedUntested = false) else it
        }
        writeConfig()
    }

    /** 记下用户在这家手填的 [model](见 [Provider.typedModels])。 */
    @Synchronized
    fun markTyped(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.typedModels) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(typedModels = it.typedModels + model) else it }
        writeConfig()
    }

    /** 换掉一个服务商的 Key(它下面的模型都用这一个)。 */
    @Synchronized
    fun updateProviderKey(providerId: String, apiKey: String) {
        if (provider(providerId) != null) setKey(providerId, apiKey)
    }

    /**
     * 删掉一个服务商:它下面的模型配置和 Key 一起删。用着它的用途退回默认(主模型变成没选,看图不用,轻量用主模型);
     * 界面上删除前已经说明哪些用途要重新选。删的是 ChatGPT 的,连注册到的 client_id 一起忘掉(本机标识留着):
     * 下次登录重新注册,可以换一个账号。
     */
    @Synchronized
    fun removeProvider(providerId: String) {
        if (provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) {
            writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", chatGptRegistration().first) }.toString())
        }
        _providers.value = _providers.value.filterNot { it.id == providerId }
        _configs.value = _configs.value.filterNot { it.providerId == providerId }
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        prune()
        if (providerId in keys) setKey(providerId, "")
        _providerKeyRevisions.value -= providerId
        writeConfig()
    }

    /**
     * 把服务商 [providerId] 下的 [model] 用作 [role]:这个模型还没有配置就按 [caps] 建一条,再交给 [choose]。
     * 服务商不存在时返回 false。
     */
    @Synchronized
    fun use(role: ModelRole, providerId: String, model: String, caps: Capabilities): Boolean {
        val p = provider(providerId) ?: return false
        val id = _configs.value.firstOrNull { it.providerId == p.id && it.model == model }?.id
            ?: newId().also { save(ProviderConfig(it, p.kind, p.baseUrl, model, caps, p.name, p.id), null) }
        return choose(role, RoleChoice.Model(id))
    }

    /**
     * 定一个用途:用户选什么就是什么,不看能力标记。选具体模型当主模型时把工具调用标记记为 true(运行时据此发工具定义);
     * 看图标记不改存着的,在 [configFor] 里按当下的选择推出来,换走了就跟着回去。之后没人用的配置删掉。
     * 配置不存在、或这个用途不接受这种选择(见 [RoleChoices.with])时返回 false。
     */
    @Synchronized
    fun choose(role: ModelRole, choice: RoleChoice): Boolean {
        if (choice is RoleChoice.Model && _configs.value.none { it.id == choice.configId }) return false
        val next = _roles.value.with(role, choice) ?: return false
        if (choice is RoleChoice.Model) {
            _configs.value = _configs.value.map { c ->
                if (c.id != choice.configId) c
                else if (role == ModelRole.Main) c.copy(capabilities = c.capabilities.copy(toolCalls = true))
                else c
            }
        }
        _roles.value = next
        dropMainFromFallbacks()
        prune()
        writeConfig()
        return true
    }

    /**
     * 把服务商 [providerId] 下的 [model] 备成一条配置(没有就按 [caps] 建;工具调用标记记为 true,它要能当主模型用),
     * 返回配置 id;不派给任何用途。服务商不存在返回 null。
     */
    @Synchronized
    fun ensureConfigId(providerId: String, model: String, caps: Capabilities): String? {
        val p = provider(providerId) ?: return null
        val existing = _configs.value.firstOrNull { it.providerId == p.id && it.model == model }
        if (existing != null) {
            if (!existing.capabilities.toolCalls) {
                _configs.value = _configs.value.map { c ->
                    if (c.id == existing.id) c.copy(capabilities = c.capabilities.copy(toolCalls = true)) else c
                }
                writeConfig()
            }
            return existing.id
        }
        val id = newId()
        save(ProviderConfig(id, p.kind, p.baseUrl, model, caps.copy(toolCalls = true), p.name, p.id), null)
        return id
    }

    /** 备援链里混进的角色自身配置清掉。 */
    private fun dropRoleFromFallbacks(role: ModelRole) {
        val roleId = _roles.value.resolve(role) ?: return
        val current = _roles.value.fallbacks(role)
        if (roleId !in current) return
        _roles.value = _roles.value.withFallbacks(role, current.filter { it != roleId })
    }

    private fun dropMainFromFallbacks() {
        dropRoleFromFallbacks(ModelRole.Main)
    }

    /** [providerId] 下的 [model] 是不是当前主模型(备援手填时挡下用,MK-1 D1)。 */
    fun isMainModel(providerId: String, model: String): Boolean =
        configFor(ModelRole.Main)?.let { it.providerId == providerId && it.model == model } == true

    /**
     * 改指定角色备援链的第 [slot] 位: [configId] 为 null 是撤掉这一位(后面的往前补);
     * 已在链里的配置是移动到目标位(不重复占位);新配置在目标位有空位时替换、链未满时接在末尾
     * (中间有空槽也照接,不留空位)。链最长 [MAX_FALLBACKS]。
     */
    @Synchronized
    fun setFallback(role: ModelRole, slot: Int, configId: String?): Boolean {
        if (slot < 0 || slot >= MAX_FALLBACKS) return false
        if (configId != null && _configs.value.none { it.id == configId }) return false
        val current = _roles.value.fallbacks(role)
        val next = when {
            configId == null -> if (slot in current.indices) current.toMutableList().apply { removeAt(slot) } else return true
            configId in current -> {
                val rest = current.filter { it != configId }
                rest.toMutableList().apply { add(minOf(slot, rest.size), configId) }
            }
            slot in current.indices -> current.toMutableList().apply { this[slot] = configId }
            current.size < MAX_FALLBACKS -> current + configId
            else -> return false
        }
        if (next == current) return true
        _roles.value = _roles.value.withFallbacks(role, next)
        dropRoleFromFallbacks(role)
        prune()
        writeConfig()
        return true
    }

    /** 保持旧方法签名完全兼容 */
    @Synchronized
    fun setMainFallback(slot: Int, configId: String?): Boolean = setFallback(ModelRole.Main, slot, configId)

    /** 只改这个用途;不支持的协议、跟随主模型的用途或列表明确不支持的档位不接受。 */
    @Synchronized
    fun setReasoningEffort(role: ModelRole, effort: String?): Boolean {
        if (_roles.value[role] !is RoleChoice.Model) return false
        val cfg = configFor(role) ?: return false
        val listed = provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }
        val options = ReasoningOptions.of(cfg, listed)
        if (effort != null && (options == null || effort !in options.levels)) return false
        _roles.value = _roles.value.copy(reasoningEfforts =
            if (effort == null) _roles.value.reasoningEfforts - role else _roles.value.reasoningEfforts + (role to effort))
        writeConfig()
        return true
    }

    @Synchronized
    fun setModelContext(configId: String, settings: ModelContextSettings): Boolean {
        val cfg = _configs.value.firstOrNull { it.id == configId } ?: return false
        val p = provider(cfg.providerId) ?: return false
        if (!settings.validFor(cfg.capabilities.contextTokens)) return false
        val saved = if (settings == ModelContextSettings()) p.contextSettings - cfg.model
            else p.contextSettings + (cfg.model to settings)
        return saveProviderSettings(p.copy(contextSettings = saved))
    }

    @Synchronized
    fun setModelServiceTier(providerId: String, model: String, tier: OpenAiServiceTier?): Boolean {
        val p = provider(providerId) ?: return false
        if (model.isBlank() || !p.supportsServiceTier(model)) return false
        val tiers = if (tier == null) p.serviceTiers - model else p.serviceTiers + (model to tier)
        return saveProviderSettings(p.copy(serviceTiers = tiers))
    }

    /** 修改这家每日 Token 预算；写盘失败时保留内存和盘上原值。 */
    @Synchronized
    fun setDailyTokenBudget(providerId: String, limit: Long?): Boolean {
        if (limit != null && !TokenBudgetLimits.valid(limit)) return false
        val p = provider(providerId) ?: return false
        return saveProviderSettings(p.copy(dailyTokenBudget = limit))
    }

    private fun saveProviderSettings(p: Provider): Boolean {
        val updated = _providers.value.map { if (it.id == p.id) p else it }
        try {
            writeAtomically(configFile, configJson(updated))
        } catch (_: Exception) {
            return false
        }
        _providers.value = updated
        return true
    }

    /** 记下用户说的 [model] 能不能看图(见 [Provider.visionOverrides]);服务商不存在时返回 false。 */
    @Synchronized
    fun setModelVision(providerId: String, model: String, sees: Boolean): Boolean {
        val p = provider(providerId) ?: return false
        _providers.value = _providers.value.map { if (it.id == p.id) p.copy(visionOverrides = p.visionOverrides + (model to sees)) else it }
        writeConfig()
        return true
    }

    /** 看图模型的「看图定位已验证」(开发者选项里改);看图用主模型时改的是主模型那条配置。没有看图模型时不动。 */
    @Synchronized
    fun setVisionGroundingVerified(verified: Boolean) {
        val id = _roles.value.resolve(ModelRole.Vision) ?: return
        _configs.value = _configs.value.map { c ->
            if (c.id == id) c.copy(capabilities = c.capabilities.copy(groundingVerified = verified)) else c
        }
        writeConfig()
    }

    /**
     * 这个用途实际用的配置;没选、不用时为 null。看图标记按当下推出来,不看存着的列表数据有没有写:
     * 看图这一路总是 true(用户选了它来看图);别的用途按 [ModelOptions.seesImages]——用户在这个模型上说过的为准,
     * 没说过而看图也用这条配置(选了同一个模型,或「用主模型」)的,同样算能看,截图直接附给它,不必另调一次看图。
     */
    fun configFor(role: ModelRole): ProviderConfig? {
        val choices = _roles.value
        val cfg = choices.resolve(role)?.let { id -> _configs.value.firstOrNull { it.id == id } } ?: return null
        val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
            provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = choices.resolve(ModelRole.Vision) == cfg.id)
        return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
    }

    /** 主模型在它那家还没测试通过时给出那一家(见 [Provider.untested]);任务因模型失败时据此提示去「重新测试」。 */
    fun untestedMainProvider(): Provider? {
        val cfg = configFor(ModelRole.Main) ?: return null
        return provider(cfg.providerId)?.takeIf { it.untested(cfg.model) }
    }

    /**
     * 这个用途用的模型,套好计量;没选、不用时为 null。用量记在 [role] 名下:「用主模型」的看图、轻量调用
     * 记作看图、轻量,服务商和模型是主模型那条配置的。这是 App 里建可调用模型的唯一入口(主动能力走 [proactiveModel])。
     * [session] 是这次调用属于的对话(同一段对话里建的模型都给同一个);服务商声明了会话头的,每个请求都带上它。
     */
    fun modelFor(role: ModelRole, session: ModelSession = ModelSession.fresh()): ChatModel? =
        meteredFor(role, session, UsagePurpose.of(role), ::checkDailyBudget)

    /**
     * 主动能力用的模型:轻量模型,没有时用主模型;用量记在 [UsagePurpose.Proactive] 名下。
     * 每次请求前只查主动能力自己的每日预算(见 [ProactiveBudget]),不查也不占服务商的每日预算。
     */
    fun proactiveModel(session: ModelSession = ModelSession.fresh(), call: ProactiveCall = ProactiveCall.Triage): ChatModel? =
        proactiveTargeted(call, session)?.model

    /** [proactiveModel] 实际会用的服务商和模型(同样先轻量、后主模型);不建模型、不读 Key。界面据此请用户确认。 */
    fun proactiveTarget(): TriageTarget? = proactiveConfig()?.let { (_, cfg) -> targetOf(cfg) }

    /**
     * 主动能力的模型(同 [proactiveModel]),连同建它用的那条配置对应的服务商和模型:两样出自同一次读配置。
     * 发请求前对着这个目标查同意,中间换了模型也不会把通知文字发给没同意过的那个。
     * [call] 是这次要发哪一种请求:回复上限跟着它定(见 [ProactiveModelSetup])。没有默认值:漏了它,回复长的那几种就会悄悄用上短上限。
     */
    fun proactiveTargeted(call: ProactiveCall, session: ModelSession = ModelSession.fresh()): TargetedModel? =
        proactiveConfig()?.let { (role, cfg) ->
            TargetedModel(targetOf(cfg), meteredWith(cfg, role, session, UsagePurpose.Proactive, ::checkProactiveBudget, call, ::checkProactiveRetry))
        }

    /** 主动能力用的那条配置和它的用途:轻量模型,没有时用主模型。只读一次配置。 */
    private fun proactiveConfig(): Pair<ModelRole, ProviderConfig>? =
        configFor(ModelRole.Light)?.let { ModelRole.Light to it } ?: configFor(ModelRole.Main)?.let { ModelRole.Main to it }

    private fun targetOf(cfg: ProviderConfig) = TriageTarget(cfg.providerId, provider(cfg.providerId)?.name ?: cfg.name, cfg.model)

    private fun meteredFor(
        role: ModelRole, session: ModelSession, purpose: UsagePurpose, beforeCall: (UsageSource) -> Unit,
    ): ChatModel? = configFor(role)?.let { cfg -> meteredWith(cfg, role, session, purpose, beforeCall) }

    /** 用 [cfg](已按 [role] 取出的那条配置)建模型,套好计量。 */
    private fun meteredWith(
        cfg: ProviderConfig, role: ModelRole, session: ModelSession, purpose: UsagePurpose, beforeCall: (UsageSource) -> Unit,
        call: ProactiveCall = ProactiveCall.Triage,
        beforeRetry: (UsageSource, Int) -> Unit = { _, _ -> },
    ): ChatModel {
        val p = provider(cfg.providerId)
        val listed = listedOf(cfg)
        val source = UsageSource(cfg.providerId, p?.name ?: cfg.name, cfg.model, purpose,
            deepSeekDirect = DeepSeekPriceTable.isOfficialEndpoint(adapterFor(cfg, listed?.endpoint).second))
        val settings = p?.contextSettings?.get(cfg.model)
        val configured = if (settings == null) cfg.copy(capabilities = cfg.capabilities.copy(
            compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
        )) else cfg.copy(capabilities = cfg.capabilities.copy(
            contextTokens = settings.windowTokens ?: cfg.capabilities.contextTokens,
            compactionTriggerTokens = settings.triggerTokens,
            compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
        ))
        val roleEffort = _roles.value.reasoningEffort(role)
        // 主动能力:思考取服务商写明的最低档;回复短的那两种请求另设上限(见 ProactiveModelSetup)
        val (effort, cap) = if (purpose == UsagePurpose.Proactive) {
            ProactiveModelSetup.of(adapterFor(cfg, listed?.endpoint).first, ReasoningOptions.of(cfg, listed), roleEffort, call)
        } else roleEffort to null
        return modelFactory(configured, apiKey(cfg.id), listed?.endpoint, ClientSetup(
            headers(p?.sessionHeader, session), maxOutputTokens = cap, reasoningEffort = effort,
            thinkingTypes = listed?.thinkingTypes, readTimeoutSeconds = settings?.readTimeoutSeconds,
            serviceTier = p?.requestedServiceTier(cfg.model, listed?.endpoint),
        )).metered(source, usage, beforeRetry = beforeRetry, beforeCall = beforeCall)
    }

    /**
     * 主动能力的调用只查它自己的每日预算:整份的硬上限(Token 上限,和跟着它算的请求次数上限,见 [ProactiveBudget.check])。
     * 判定只用其中的一部分,那是 [com.wangzan101.mika.proactive.TriageRunner] 自己先停,不在这里。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun checkProactiveBudget(source: UsageSource) = ProactiveBudget.check(proactiveUsage(), proactiveLimit(), proactiveCalls())

    /**
     * 同一次调用里再发一个请求之前:这次调用已经发出的 [started] 个还没记进账本,加上再查。
     * 不然只剩最后一次额度时,一次调用里的兼容重试会把次数上限顶过去。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun checkProactiveRetry(source: UsageSource, started: Int) =
        ProactiveBudget.check(proactiveUsage(), proactiveLimit(), proactiveCalls() + started)

    /** [configId] 这条配置本身(不按用途推看图标记);不存在为 null。 */
    fun configById(configId: String): ProviderConfig? = _configs.value.firstOrNull { it.id == configId }

    /** 这条配置当指定角色用时的样子: 看图标记按角色规矩推导。 */
    private fun asRole(cfg: ProviderConfig, role: ModelRole): ProviderConfig {
        val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
            provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = _roles.value.resolve(ModelRole.Vision) == cfg.id)
        return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
    }

    private fun asMain(cfg: ProviderConfig): ProviderConfig = asRole(cfg, ModelRole.Main)

    /**
     * 按配置 id 建一个当指定角色用的模型(备援链用):计量、每日预算检查都和该角色同一条路。
     * 配置不存在为 null。
     */
    fun modelForRoleConfig(role: ModelRole, configId: String, session: ModelSession = ModelSession.fresh()): ChatModel? {
        val raw = configById(configId) ?: return null
        val p = provider(raw.providerId)
        val source = UsageSource(raw.providerId, p?.name ?: raw.name, raw.model, UsagePurpose.of(role))
        val listed = listedOf(raw)
        val settings = p?.contextSettings?.get(raw.model)
        val adjusted = asRole(raw, role)
        val configured = if (settings == null) adjusted.copy(capabilities = adjusted.capabilities.copy(
            compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
        )) else adjusted.copy(capabilities = adjusted.capabilities.copy(
            contextTokens = settings.windowTokens ?: adjusted.capabilities.contextTokens,
            compactionTriggerTokens = settings.triggerTokens,
            compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
        ))
        val roleEffort = _roles.value.reasoningEffort(role)
        val effort = roleEffort?.takeIf { ReasoningOptions.of(configured, listed)?.levels?.contains(it) == true }
        return modelFactory(configured, apiKey(raw.id), listed?.endpoint, ClientSetup(
            headers(p?.sessionHeader, session), reasoningEffort = effort, thinkingTypes = listed?.thinkingTypes,
            readTimeoutSeconds = settings?.readTimeoutSeconds,
            serviceTier = p?.requestedServiceTier(raw.model, listed?.endpoint),
        )).metered(source, usage, beforeCall = ::checkDailyBudget)
    }

    /**
     * 按配置 id 建一个当主模型用的模型(备援链用,见 [RoleChoices.mainFallbacks]):计量、每日预算检查都和主模型同一条路,
     * 用量记在主模型名下。配置不存在为 null。
     */
    fun modelForConfig(configId: String, session: ModelSession = ModelSession.fresh()): ChatModel? =
        modelForRoleConfig(ModelRole.Main, configId, session)

    /**
     * 按指定角色的备援链顺序返回备援模型及其服务商 ID，供故障转移判断预算归属。
     * 配置没了、建不出的位子跳过,链短一点照样能跑。
     */
    fun fallbackChain(role: ModelRole, session: ModelSession = ModelSession.fresh()): Pair<List<ChatModel>, List<String?>> {
        val models = ArrayList<ChatModel>()
        val providerIds = ArrayList<String>()
        val rolePrimaryId = _roles.value.resolve(role)
        for (id in _roles.value.fallbacks(role)) {
            if (id == rolePrimaryId) continue
            val cfg = configById(id) ?: continue
            val m = modelForRoleConfig(role, id, session) ?: continue
            models += m
            providerIds += cfg.providerId
        }
        return models to providerIds
    }

    /**
     * 按 [RoleChoices.mainFallbacks] 的顺序返回备援模型及其服务商 ID，供故障转移判断预算归属。
     */
    fun mainFallbackChain(session: ModelSession = ModelSession.fresh()): Pair<List<ChatModel>, List<String?>> =
        fallbackChain(ModelRole.Main, session)

    /** 视觉模型备援链 */
    fun visionFallbackChain(session: ModelSession = ModelSession.fresh()): List<ChatModel> =
        fallbackChain(ModelRole.Vision, session).first

    /** 轻量模型备援链 */
    fun lightFallbackChain(session: ModelSession = ModelSession.fresh()): List<ChatModel> =
        fallbackChain(ModelRole.Light, session).first

    fun mainProviderId(): String? = configFor(ModelRole.Main)?.providerId

    /** Returns a stored Gemini Developer API key (`Kind.Gemini` only). */
    @Synchronized
    fun geminiDeveloperApiKey(): String? {
        fun validKey(providerId: String): String? = providerKey(providerId)
            ?.trim()
            ?.takeIf { it.isNotBlank() && ApiKeyText.isHeaderLegal(it) && it.all { ch -> ch.code in 33..126 } }
        val mainCfg = configFor(ModelRole.Main)
        if (mainCfg?.kind == ProviderConfig.Kind.Gemini) {
            validKey(mainCfg.providerId)?.let { return it }
        }
        return _providers.value
            .asSequence()
            .filter { it.kind == ProviderConfig.Kind.Gemini }
            .firstNotNullOfOrNull { validKey(it.id) }
    }

    private sealed interface GroundingTarget {
        val providerName: String

        class Gemini(
            override val providerName: String,
            val baseUrl: String,
            val apiKey: String,
            val model: String,
            val sessionHeader: String?,
        ) : GroundingTarget

        class Unsupported(override val providerName: String) : GroundingTarget
    }

    private fun resolveGrounding(): GroundingTarget? {
        val cfg = configFor(ModelRole.Main) ?: return null
        val p = provider(cfg.providerId)
        val providerName = p?.name?.ifBlank { null } ?: ProviderConnection.displayName(cfg)
        val endpoint = listedOf(cfg)?.endpoint
        val (kind, baseUrl) = adapterFor(cfg, endpoint)
        return when (kind) {
            ProviderConfig.Kind.Gemini -> {
                val key = apiKey(cfg.id)?.trim().orEmpty()
                val cleanModel = cfg.model.trim().removePrefix("models/").trim()
                val cleanBase = baseUrl.trim()
                val validUrl = cleanBase.isNotEmpty() &&
                    cleanModel.isNotEmpty() &&
                    (cleanBase.trimEnd('/') + "/v1beta/models/$cleanModel:generateContent").toHttpUrlOrNull() != null
                if (key.isBlank() || !ApiKeyText.isHeaderLegal(key) || !validUrl) {
                    GroundingTarget.Unsupported(providerName)
                } else {
                    GroundingTarget.Gemini(providerName, cleanBase, key, cfg.model, p?.sessionHeader)
                }
            }
            else -> GroundingTarget.Unsupported(providerName)
        }
    }

    /** Whether Google Search grounding is available for the active main model. */
    fun groundingStatus(): GroundingStatus = when (val target = resolveGrounding()) {
        null -> GroundingStatus(available = false, providerName = null)
        is GroundingTarget.Unsupported -> GroundingStatus(available = false, providerName = target.providerName)
        is GroundingTarget.Gemini -> GroundingStatus(available = true, providerName = target.providerName)
    }

    /** Builds a Google Search grounding client for the main model, or null when unsupported/unconfigured. */
    fun googleGroundingSearch(session: ModelSession = ModelSession.fresh()): WebSearch? =
        when (val target = resolveGrounding()) {
            null, is GroundingTarget.Unsupported -> null
            is GroundingTarget.Gemini -> GoogleGroundingSearch.forGemini(
                baseUrl = target.baseUrl,
                apiKey = target.apiKey,
                model = target.model,
                client = http,
                headers = headers(target.sessionHeader, session),
            )
        }
    /** 每次实际请求前重查配置和账本；达到预算时不发请求，也不记一笔虚假调用。 */
    private fun checkDailyBudget(source: UsageSource) {
        val p = provider(source.providerId) ?: return
        val limit = p.dailyTokenBudget ?: return
        val used = dailyUsage(source.providerId)
        if (used >= limit) throw ModelError.DailyBudgetExceeded(p.name, limit, used, providerId = source.providerId)
    }

    /**
     * 声音模型:地址、Key、请求头取这家服务商;不套对话模型的计量(朗读用量由调用方另记)。
     * 服务商、模型或协议缺失时为 null。合成比一句话慢,读超时放到 120 秒。
     */
    fun ttsModel(choice: VoiceChoice, capabilities: TtsCapabilities): TtsModel? {
        val p = provider(choice.providerId) ?: return null
        val client = modelHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        val header = headers(p.sessionHeader, ModelSession.fresh())
        val key = activeApiKey(p.id) ?: providerKey(p.id)?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() }
        return when (choice.protocol) {
            TtsProtocol.OpenAiSpeech -> OpenAiSpeechModel(p.baseUrl, key, choice.model, capabilities, client, header)
            TtsProtocol.GeminiTts -> GeminiTtsModel(p.baseUrl, key.orEmpty(), choice.model, capabilities, client, header)
        }
    }

    /**
     * 图片生成用的模型:地址、Key、请求头都取这家服务商;不套对话模型的计量(图片用量由调用方另记)。
     * 服务商、模型或协议缺失时为 null。图比一句话慢,读超时放到 180 秒。
     */
    fun imageModel(choice: ImageChoice, capabilities: ImageCapabilities): ImageModel? {
        val p = provider(choice.providerId) ?: return null
        val client = modelHttp.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()
        val header = headers(p.sessionHeader, ModelSession.fresh())
        val key = providerKey(p.id)
        return when (choice.protocol) {
            ImageProtocol.OpenAiImages -> OpenAiImagesModel(p.baseUrl, key, choice.model, capabilities, client, header)
            ImageProtocol.GeminiImage -> GeminiImageModel(p.baseUrl, key.orEmpty(), choice.model, capabilities, client, header)
        }
    }

    /**
     * 「测试并保存」的试调用:还没存下的服务商,按真实任务同一条路([modelFactory]、同样的请求头)建出 [model],
     * 带一个无参数的工具([PROBE_TOOL])、请它调用;回复限得很短(协议允许的话,见 [buildModel])。
     * 回了工具调用为 [ProbeResult.ToolCalled],只回了话为 [ProbeResult.NoToolCall],请求失败带着服务端的报错原样返回。
     * 不计量:这几个 token 不属于任何用途。会话号用新的,不属于任何对话。超时由调用方管。
     */
    suspend fun probe(
        kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities,
    ): ProbeResult {
        val probeKey = apiKey?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() ?: it }
        val cfg = ProviderConfig(PROBE_ID, kind, baseUrl, model, caps.copy(toolCalls = true))
        val m = modelFactory(cfg, probeKey, endpoint, ClientSetup(headers(sessionHeader, ModelSession.fresh()), PROBE_MAX_TOKENS))
        return try {
            val reply = m.complete(PROBE_SYSTEM, listOf(Message(Role.User, listOf(Part.Text(PROBE_TEXT)), Author.Mika)), listOf(PROBE_TOOL))
            if (reply.parts.any { it is Part.ToolCall }) ProbeResult.ToolCalled else ProbeResult.NoToolCall
        } catch (e: ModelError) {
            ProbeResult.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.Failed(ModelError.BadResponse(e.message.orEmpty(), e))
        }
    }

    private fun headers(sessionHeader: String?, session: ModelSession) = ClientHeaders.of(userAgent, sessionHeader, session)

    /** 不属于任何对话的请求(如语音转写)给这家带的头:客户端标识,声明了会话头的用新的会话号。 */
    fun clientHeaders(p: Provider): Map<String, String> = headers(p.sessionHeader, ModelSession.fresh())

    /** 服务商最近一次列出的这个模型(单独指定的接口、写明的思考方式等);每次建模型时现查,重新列过就用新的。 */
    private fun listedOf(cfg: ProviderConfig): ListedModel? =
        provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }

    /** 列出这个服务商的可用模型(见 [ModelCatalog]);不属于任何对话,声明了会话头的用新的会话号。 */
    suspend fun listModels(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String? = null): ModelListResult {
        val probeKey = apiKey?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() ?: it }
        return modelLister(kind, baseUrl, probeKey, headers(sessionHeader, ModelSession.fresh()))
    }

    /** 重新列一个已连接服务商的模型:用存着的 Key;订阅登录用访问令牌(快过期先换新的)。 */
    suspend fun listModels(p: Provider): ModelListResult {
        if (p.kind != ProviderConfig.Kind.ChatGpt && p.kind != ProviderConfig.Kind.Xai) {
            val key = activeApiKey(p.id) ?: providerKey(p.id)?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() }
            return listModels(p.kind, p.baseUrl, key, p.sessionHeader)
        }
        val token = try {
            if (p.kind == ProviderConfig.Kind.ChatGpt) tokensFor(p.id).get(fresh = false) else accountTokensFor(p.id).get(fresh = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ModelError.SignInExpired) {
            return ModelListResult.Failed(ListFailure.Auth)
        } catch (e: ModelError) {
            return ModelListResult.Failed(if (e is ModelError.Network || e is ModelError.Proxy) ListFailure.of(e) else ListFailure.Server)
        }
        val base = provider(p.id)?.baseUrl ?: p.baseUrl
        return listModels(p.kind, base, token, p.sessionHeader)
    }

    // ---------- ChatGPT 登录 ----------

    /** ChatGPT 服务商的登录(和 API Key 存在同一处、同样加密);没有或解不开为 null。 */
    fun chatGptSession(providerId: String): ChatGptSession? = ChatGptSession.fromJson(providerKey(providerId))

    /** 已连接的 ChatGPT 服务商(只有一家)。 */
    fun chatGptProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }

    /** 登录成功:存下登录和列出的模型;已有 ChatGPT 服务商就更新那一家。 */
    @Synchronized
    fun connectChatGpt(session: ChatGptSession, models: List<ListedModel>): Provider =
        connect(CHATGPT_NAME, ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.toJson(), models)

    /**
     * 退出登录:先在服务端注销刷新令牌(确认不了也照样清),再清掉本机的令牌;注册(client_id)和账号留着,再登录时沿用。
     * 返回服务端是否确认了注销。服务商和它的模型、用途都不动。
     */
    suspend fun signOutChatGpt(providerId: String): Boolean {
        val s = chatGptSession(providerId) ?: return true
        val revoked = s.refreshToken.isEmpty() || chatGptAuth.revoke(s.clientId, s.refreshToken)
        saveChatGptSession(providerId, s.signedOut())
        return revoked
    }

    /**
     * 这台设备在 ChatGPT 那边的本机标识和注册到的 client_id。标识第一次用时生成,之后一直不变;client_id 注册后记下,
     * 退出登录后留着(再登录沿用,不重复注册),删掉 ChatGPT 服务商时忘掉。两者都不是密钥,明文存。
     */
    @Synchronized
    fun chatGptRegistration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(chatGptHostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    /** 记下注册到的 client_id(换令牌前就记,换失败了下次也不用重新注册)。 */
    @Synchronized
    fun rememberChatGptClient(clientId: String) {
        val host = chatGptRegistration().first
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", host); put("clientId", clientId) }.toString())
    }

    @Synchronized
    private fun saveChatGptSession(providerId: String, session: ChatGptSession) {
        if (provider(providerId) != null) setKey(providerId, session.toJson())
    }

    private fun tokensFor(providerId: String): ChatGptTokens = synchronized(chatGptTokens) {
        chatGptTokens.getOrPut(providerId) {
            ChatGptTokens(chatGptAuth, { chatGptSession(providerId) }, { saveChatGptSession(providerId, it) })
        }
    }

    // ---------- xAI 账号登录 ----------

    /** 订阅账号的登录(和 API Key 存在同一处、同样加密);没有或解不开为 null。 */
    fun accountSession(providerId: String): AccountSession? = AccountSession.fromJson(providerKey(providerId))

    fun xaiProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.Xai }

    /**
     * 登录成功:存下登录和列出的模型。同一家账号登录只有一个服务商,再登录就更新它。
     */
    @Synchronized
    fun connectAccount(session: AccountSession, models: List<ListedModel>): Provider {
        val (name, kind, base) = when (session.provider) {
            XaiAuth.PROVIDER -> Triple(XAI_NAME, ProviderConfig.Kind.Xai, XaiAuth.API_BASE)
            else -> throw IllegalArgumentException("unknown account provider")
        }
        val existing = _providers.value.firstOrNull { it.kind == kind }
        if (existing == null) return connect(name, kind, base, session.toJson(), models)
        val p = existing.copy(name = name, baseUrl = base, models = models)
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        _configs.value = _configs.value.map { if (it.providerId == p.id) it.copy(baseUrl = base, name = name) else it }
        setKey(p.id, session.toJson())
        writeConfig()
        return p
    }

    /** 退出登录:只清这台手机上的令牌。xAI 账号里的授权要到 xAI 的设置里自己断开。 */
    @Synchronized
    fun signOutAccount(providerId: String) {
        val s = accountSession(providerId) ?: return
        saveAccount(providerId, s.signedOut())
    }

    @Synchronized
    private fun saveAccount(providerId: String, session: AccountSession) {
        if (provider(providerId) == null) return
        setKey(providerId, session.toJson())
        val base = session.apiBase
        if (base.isNotBlank() && provider(providerId)?.baseUrl != base) {
            _providers.value = _providers.value.map { if (it.id == providerId) it.copy(baseUrl = base) else it }
            _configs.value = _configs.value.map { if (it.providerId == providerId) it.copy(baseUrl = base) else it }
            writeConfig()
        }
    }

    // ---------- 用 OpenRouter 账号登录 ----------

    /** 用 OpenRouter 账号授权连上的那一家;没有为 null。 */
    fun openRouterAccount(): Provider? = _providers.value.firstOrNull { it.signIn == OPENROUTER_SIGN_IN }

    /**
     * 授权换到了 Key:按 OpenRouter 预设存成一家普通的 OpenAI 兼容服务商(Key 照常加密),标上是授权来的。
     * 已经有授权来的那一家就更新它;用户自己填 Key 的 OpenRouter 不动,两家并存。
     */
    @Synchronized
    fun connectOpenRouter(apiKey: String, models: List<ListedModel>): Provider =
        connect(OPENROUTER_NAME, ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, apiKey, models, signIn = OPENROUTER_SIGN_IN)

    /** 退出 OpenRouter 登录:删掉这台手机上的 Key;服务商、模型和用途留着。OpenRouter 那边的 Key 要用户到 Keys 页面删。 */
    @Synchronized
    fun signOutOpenRouter(providerId: String) {
        if (provider(providerId)?.signIn == OPENROUTER_SIGN_IN && providerId in keys) setKey(providerId, "")
    }

    /**
     * 刷新换到的令牌写回去,前提是这期间存着的登录没变:换令牌要等网络,用户可能已经退出或重新登录,
     * 那样旧的刷新结果不能盖掉(否则退出了又被悄悄登录回来,或新登录被旧令牌顶掉)。
     */
    @Synchronized
    private fun saveRefreshed(providerId: String, before: AccountSession, after: AccountSession) {
        val now = accountSession(providerId) ?: return
        if (now.refreshToken != before.refreshToken || now.accessToken != before.accessToken) return
        saveAccount(providerId, after)
    }

    private fun accountTokensFor(providerId: String): AccountTokens = synchronized(accountTokens) {
        accountTokens.getOrPut(providerId) {
            AccountTokens(
                account = XAI_ACCOUNT,
                refresh = { session ->
                    when (session.provider) {
                        XaiAuth.PROVIDER -> xaiAuth.refresh(session)
                        else -> OauthTokenResult.Rejected("unknown", terminal = true)
                    }
                },
                load = { accountSession(providerId) },
                save = { before, after -> saveRefreshed(providerId, before, after) },
            )
        }
    }

    /** 模型单独指定了接口的,按它的协议选适配器、用它的地址;否则按配置的接口类型和地址。Key 都用服务商的。 */
    /**
     * 请求头四种协议一样带。回复上限([ClientSetup.maxOutputTokens])只给 Messages(本来就必填)和 generateContent;
     * Chat Completions 不给(有的模型只认 `max_tokens`、有的只认 `max_completion_tokens`,给错了整个请求被拒,
     * 连接测试就会误报),Responses 也不给(ChatGPT 套餐那条路不收 `max_output_tokens`)。
     * 读超时([ClientSetup.readTimeoutSeconds])配置了的按它派生 client(共享连接池与代理),慢速本地推理可调大(#295);
     * 没配置用全局默认(120 秒)。派生只在建模型时发生一次,不影响别的模型。
     */
    private fun buildModel(cfg: ProviderConfig, apiKey: String?, endpoint: ModelEndpoint?, setup: ClientSetup): ChatModel {
        val (kind, adapterBase) = adapterFor(cfg, endpoint)
        // 订阅登录用服务商当前的地址(登录时可能换过),配置里存的是添加时的
        val baseUrl = if (cfg.kind == ProviderConfig.Kind.Xai) {
            provider(cfg.providerId)?.baseUrl?.takeIf { it.isNotBlank() } ?: adapterBase
        } else adapterBase
        val defaultHttp = http
        val http = setup.readTimeoutSeconds
            ?.takeIf { it in MODEL_READ_TIMEOUT_SECONDS }
            ?.let { seconds -> defaultHttp.newBuilder().readTimeout(seconds.toLong(), TimeUnit.SECONDS).build() }
            ?: defaultHttp
        return when (kind) {
            ProviderConfig.Kind.OpenAiCompat ->
                OpenAiCompatModel(
                    baseUrl, apiKey, cfg.model, cfg.capabilities, http, endpoint?.reasoningField, setup.headers,
                    reasoningEffort = setup.reasoningEffort,
                    keySupplier = { activeApiKey(cfg.providerId) },
                    onKeyRejected = { key, retryMs, status ->
                        KeyPoolManager.shared.markRejected(cfg.providerId, key, status, retryMs)
                    }
                )
            ProviderConfig.Kind.Anthropic -> AnthropicModel(
                baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http,
                maxTokens = setup.maxOutputTokens,
                headers = setup.headers, reasoningEffort = setup.reasoningEffort, thinkingTypes = setup.thinkingTypes,
            )
            ProviderConfig.Kind.Gemini ->
                GeminiModel(
                    baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http, setup.headers, setup.maxOutputTokens,
                    reasoningEffort = setup.reasoningEffort,
                    keySupplier = { activeApiKey(cfg.providerId) },
                    onKeyRejected = { key, retryMs, status ->
                        KeyPoolManager.shared.markRejected(cfg.providerId, key, status, retryMs)
                    }
                )
            ProviderConfig.Kind.OpenAiResponses ->
                ResponsesModel(baseUrl, BearerToken.fixed(apiKey), cfg.model, cfg.capabilities, http, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort, serviceTier = setup.serviceTier)
            ProviderConfig.Kind.ChatGpt ->
                ResponsesModel(baseUrl, tokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, chatGptPlan = true, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort, serviceTier = setup.serviceTier)
            ProviderConfig.Kind.Xai ->
                ResponsesModel(baseUrl, accountTokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort, accountLogin = XAI_ACCOUNT)
        }
    }

    private fun findOrAddProvider(name: String, kind: ProviderConfig.Kind, baseUrl: String, signIn: String? = null): Provider {
        val key = ProviderConnection.keyOf(kind, baseUrl, name)
        _providers.value.firstOrNull { it.key == key && it.signIn == signIn }?.let { return it }
        val p = Provider(newId(), name.trim(), kind, baseUrl.trim(), signIn = signIn)
        _providers.value = _providers.value + p
        return p
    }

    private fun setKey(providerId: String, apiKey: String) {
        keys = if (apiKey.isEmpty()) keys - providerId else keys + (providerId to encode(cipher.encrypt(apiKey.toByteArray())))
        writeAtomically(keyFile, keysJson())
        val revisions = _providerKeyRevisions.value
        _providerKeyRevisions.value = revisions + (providerId to ((revisions[providerId] ?: 0L) + 1))
        KeyPoolManager.shared.register(providerId, apiKey)
    }

    /** 删掉没有用途在用的模型配置。Key 存在服务商上,不受影响。 */
    private fun prune() {
        val used = _roles.value.referenced
        _configs.value = _configs.value.filter { it.id in used }
    }

    private fun load() {
        if (configFile.isFile) runCatching {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            _providers.value = root["services"]?.jsonArray?.mapNotNull { runCatching { providerOf(it.jsonObject) }.getOrNull() }.orEmpty()
            _configs.value = root["providers"]?.jsonArray?.mapNotNull { runCatching { configOf(it.jsonObject) }.getOrNull() }.orEmpty()
            val uses = root["uses"]?.jsonObject
            _roles.value = if (uses != null) choicesOf(uses) else migrateRoles(root["roles"]?.jsonObject)
        }
        if (keyFile.isFile) runCatching {
            keys = json.parseToJsonElement(keyFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            keys.forEach { (pid, _) ->
                providerKey(pid)?.let { rawKey -> KeyPoolManager.shared.register(pid, rawKey) }
            }
        }
        migrate()
        repairStaleContextDefaults()
        // 指向不存在的配置的选择退回默认,没人用的配置删掉;和文件里的不一样(含刚迁移的用途)才写回
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        dropMainFromFallbacks()
        repairUnavailableReasoningEfforts()
        prune()
        if (configFile.isFile) {
            val text = configJson()
            if (runCatching { configFile.readText() }.getOrNull() != text) writeAtomically(configFile, text)
        }
    }

    /**
     * 旧版本的 `roles` 只记显式指定的配置 id,没指定的按默认规则分配:主模型取第一个配置,看图在主模型能看图时由它兼任,
     * 轻量跟随主模型。这里把那时实际生效的分配写明成 [RoleChoices],之后不再有隐含的规则。
     */
    private fun migrateRoles(old: JsonObject?): RoleChoices {
        val ids = _configs.value.map { it.id }.toSet()
        fun explicit(role: ModelRole) = old?.get(role.name)?.jsonPrimitive?.content?.takeIf { it in ids }
        val main = explicit(ModelRole.Main) ?: _configs.value.firstOrNull()?.id
        val mainSees = _configs.value.firstOrNull { it.id == main }?.capabilities?.vision == true
        return RoleChoices(
            main = main,
            vision = explicit(ModelRole.Vision)?.let(RoleChoice::Model) ?: if (mainSees) RoleChoice.SameAsMain else RoleChoice.Off,
            light = explicit(ModelRole.Light)?.let(RoleChoice::Model) ?: RoleChoice.SameAsMain,
        )
    }

    /**
     * 把没有归属的模型配置(旧版本的文件)归到服务商下:按 [ProviderConnection.keyOf] 分组,每组一个服务商,
     * Key 取组里任一条按配置 id 存的(密文原样搬过去)。先写含新旧两份的 Key 文件,再写配置,最后清掉不属于任何服务商的 Key;
     * 中途断掉的话,下次读入时没归属的配置还在,会重做。都归好了就只清理残留的 Key。
     */
    private fun migrate() {
        val known = _providers.value.map { it.id }.toSet()
        val orphans = _configs.value.filter { it.providerId !in known }
        if (orphans.isNotEmpty()) {
            val attachedTo = mutableMapOf<String, Provider>()
            orphans.groupBy { ProviderConnection.keyOf(it.kind, it.baseUrl, ProviderConnection.displayName(it)) }.values.forEach { group ->
                val first = group.first()
                val p = findOrAddProvider(ProviderConnection.displayName(first), first.kind, first.baseUrl)
                group.forEach { attachedTo[it.id] = p }
                if (p.id !in keys) group.firstNotNullOfOrNull { keys[it.id] }?.let { keys = keys + (p.id to it) }
            }
            writeAtomically(keyFile, keysJson())
            _configs.value = _configs.value.map { c ->
                attachedTo[c.id]?.let { p -> c.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name) } ?: c
            }
            writeConfig()
        }
        val ids = _providers.value.map { it.id }.toSet()
        if (keys.keys.any { it !in ids }) {
            keys = keys.filterKeys { it in ids }
            writeAtomically(keyFile, keysJson())
        }
    }

    /**
     * 模型目录或客户端认识的档位变化后,旧选择不能继续原样发给服务端:能确定当前选项时,失效项退回默认;
     * 没有档位元数据、协议本就不提供这项设置的旧配置不动,避免把仍可由服务端处理的值误删。
     */
    private fun repairUnavailableReasoningEfforts() {
        val repaired = _roles.value.reasoningEfforts.mapNotNull { (role, effort) ->
            val cfg = configFor(role) ?: return@mapNotNull null
            val listed = provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }
            val options = ReasoningOptions.of(cfg, listed)
            if (options == null || effort in options.levels) role to effort else null
        }.toMap()
        _roles.value = _roles.value.copy(reasoningEfforts = repaired)
    }

    /**
     * 预设的默认上下文更新后,把当年按旧默认存下的配置跟上去:仅当存着的正是旧默认、这家预设现在的默认已经不同、
     * 用户没给这个模型单独写过窗口时才改;用户改过的任何值一律不动。只在 [load] 读入后跑一次,
     * 改动随读入后的统一写回落盘。窗口按旧值 64k 时,压缩会频繁改写请求前缀,服务商的前缀缓存整段失效(见 [ProviderPresets])。
     */
    private fun repairStaleContextDefaults() {
        val fixed = _configs.value.mapNotNull { c ->
            val now = ProviderPresets.forBaseUrl(c.baseUrl)?.capabilities?.contextTokens
                ?.takeIf { it != LEGACY_DEFAULT_CONTEXT_TOKENS }
            if (now != null && c.capabilities.contextTokens == LEGACY_DEFAULT_CONTEXT_TOKENS &&
                provider(c.providerId)?.contextSettings?.get(c.model)?.windowTokens == null
            ) c.id to now else null
        }.toMap()
        if (fixed.isEmpty()) return
        _configs.value = _configs.value.map { c ->
            fixed[c.id]?.let { window -> c.copy(capabilities = c.capabilities.copy(contextTokens = window)) } ?: c
        }
    }

    private fun providerOf(o: JsonObject) = Provider(
        id = o.str("id"),
        name = o.str("name"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        // 旧版本存下的没有这一项:按地址对得上的预设补上(读入后写回文件时记进去)
        sessionHeader = o["sessionHeader"]?.jsonPrimitive?.content ?: ProviderPresets.forBaseUrl(o.str("baseUrl"))?.sessionHeader,
        signIn = o["signIn"]?.jsonPrimitive?.content,
        dailyTokenBudget = o.positiveLong("dailyTokenBudget")?.takeIf(TokenBudgetLimits::valid),
        serviceTiers = (o["serviceTiers"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            val value = (raw as? JsonPrimitive)?.contentOrNull
            OpenAiServiceTier.entries.firstOrNull { it.requestValue == value }?.let { model to it }
        }.toMap(),
        verifiedModels = o["verifiedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        contextSettings = (o["contextSettings"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val settings = ModelContextSettings(entry.positiveInt("windowTokens"), entry.positiveInt("triggerTokens"),
                entry.positiveInt("readTimeoutSeconds")?.takeIf { it in MODEL_READ_TIMEOUT_SECONDS })
            if (settings == ModelContextSettings()) null else model to settings
        }.toMap(),
        visionOverrides = (o["visionOverrides"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            (raw as? JsonPrimitive)?.booleanOrNull?.let { model to it }
        }.toMap(),
        typedModels = o["typedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        // 旧版本存下的没有这一项:那时每一家都走过「测试并保存」,按测过算
        savedUntested = o["untested"]?.jsonPrimitive?.booleanOrNull ?: false,
        models = o["models"]?.jsonArray?.map { e ->
            val m = e.jsonObject
            ListedModel(
                id = m.str("id"),
                displayName = m["displayName"]?.jsonPrimitive?.content.orEmpty(),
                toolCalls = m["toolCalls"]?.jsonPrimitive?.booleanOrNull,
                vision = m["vision"]?.jsonPrimitive?.booleanOrNull,
                contextTokens = m["contextTokens"]?.jsonPrimitive?.intOrNull,
                reasoning = m["reasoning"]?.jsonPrimitive?.booleanOrNull,
                reasoningEfforts = m["reasoningEfforts"]?.jsonArray?.map { it.jsonPrimitive.content },
                thinkingTypes = m["thinkingTypes"]?.jsonArray?.map { it.jsonPrimitive.content },
                chat = m["chat"]?.jsonPrimitive?.booleanOrNull,
                endpoint = (m["api"] as? JsonObject)?.let { a ->
                    ModelProtocol.of(a["protocol"]?.jsonPrimitive?.content)?.let { protocol ->
                        ModelEndpoint(protocol, a.str("baseUrl"), a["reasoningField"]?.jsonPrimitive?.content)
                    }
                },
                unsupportedApis = m["unsupportedApis"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                protocols = m["protocols"]?.jsonArray?.mapNotNull { ModelProtocol.of(it.jsonPrimitive.content) }?.takeIf { it.isNotEmpty() },
            )
        },
    )

    private fun configOf(o: JsonObject) = ProviderConfig(
        id = o.str("id"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        model = o.str("model"),
        capabilities = Capabilities(
            toolCalls = o["toolCalls"]!!.jsonPrimitive.boolean,
            vision = o["vision"]!!.jsonPrimitive.boolean,
            contextTokens = o["contextTokens"]!!.jsonPrimitive.int,
            reasoning = o["reasoning"]!!.jsonPrimitive.boolean,
            groundingVerified = o["groundingVerified"]?.jsonPrimitive?.boolean ?: false,
            promptCaching = PromptCaching.of(o["promptCaching"]?.jsonPrimitive?.content),
        ),
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        providerId = o["providerId"]?.jsonPrimitive?.content.orEmpty(),
    )

    /** `uses` 里的一个用途:`{"use":"model","config":id}`、`{"use":"main"}` 或 `{"use":"none"}`;认不出的按默认。 */
    private fun choicesOf(o: JsonObject): RoleChoices {
        fun choice(role: ModelRole): RoleChoice? {
            val c = runCatching { o[role.name]?.jsonObject }.getOrNull() ?: return null
            return when (c["use"]?.jsonPrimitive?.content) {
                USE_MODEL -> c["config"]?.jsonPrimitive?.content?.let(RoleChoice::Model)
                USE_MAIN -> RoleChoice.SameAsMain
                USE_NONE -> RoleChoice.Off
                else -> null
            }
        }
        var r = RoleChoices()
        ModelRole.entries.forEach { role -> choice(role)?.let { c -> r.with(role, c)?.let { r = it } } }
        r = r.copy(mainFallbacks = runCatching {
            o["MainFallbacks"]?.jsonArray?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
                ?.distinct()?.take(MAX_FALLBACKS)
        }.getOrNull().orEmpty())
        val vf = runCatching {
            o["VisionFallbacks"]?.jsonArray?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
                ?.distinct()?.take(MAX_FALLBACKS)
        }.getOrNull().orEmpty()
        val lf = runCatching {
            o["LightFallbacks"]?.jsonArray?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
                ?.distinct()?.take(MAX_FALLBACKS)
        }.getOrNull().orEmpty()
        r = r.copy(visionFallbacks = vf, lightFallbacks = lf)
        return r.copy(reasoningEfforts = ModelRole.entries.mapNotNull { role ->
            val effort = runCatching { o[role.name]?.jsonObject?.get("reasoningEffort")?.jsonPrimitive?.content }.getOrNull()
            if (r[role] is RoleChoice.Model && !effort.isNullOrBlank()) role to effort else null
        }.toMap())
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.positiveInt(k: String) = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    private fun JsonObject.positiveLong(k: String) = (this[k] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    private fun writeConfig() = writeAtomically(configFile, configJson())

    private fun configJson(providers: List<Provider> = _providers.value): String {
        val root = buildJsonObject {
            putJsonArray("services") {
                providers.forEach { p ->
                    addJsonObject {
                        put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("baseUrl", p.baseUrl)
                        p.sessionHeader?.let { put("sessionHeader", it) }
                        p.signIn?.let { put("signIn", it) }
                        p.dailyTokenBudget?.let { put("dailyTokenBudget", it) }
                        if (p.serviceTiers.isNotEmpty()) putJsonObject("serviceTiers") {
                            for ((model, tier) in p.serviceTiers) put(model, tier.requestValue)
                        }
                        if (p.savedUntested) put("untested", true)
                        if (p.verifiedModels.isNotEmpty()) putJsonArray("verifiedModels") { p.verifiedModels.forEach { add(it) } }
                        if (p.contextSettings.isNotEmpty()) putJsonObject("contextSettings") {
                            for ((model, settings) in p.contextSettings) putJsonObject(model) {
                                settings.windowTokens?.let { put("windowTokens", it) }
                                settings.triggerTokens?.let { put("triggerTokens", it) }
                                settings.readTimeoutSeconds?.let { put("readTimeoutSeconds", it) }
                            }
                        }
                        if (p.visionOverrides.isNotEmpty()) putJsonObject("visionOverrides") {
                            for ((model, sees) in p.visionOverrides) put(model, sees)
                        }
                        if (p.typedModels.isNotEmpty()) putJsonArray("typedModels") { p.typedModels.forEach { add(it) } }
                        p.models?.let { models ->
                            putJsonArray("models") {
                                models.forEach { m ->
                                    addJsonObject {
                                        put("id", m.id)
                                        if (m.displayName.isNotEmpty()) put("displayName", m.displayName)
                                        m.toolCalls?.let { put("toolCalls", it) }
                                        m.vision?.let { put("vision", it) }
                                        m.contextTokens?.let { put("contextTokens", it) }
                                        m.reasoning?.let { put("reasoning", it) }
                                        m.reasoningEfforts?.let { levels -> putJsonArray("reasoningEfforts") { levels.forEach { add(it) } } }
                                        m.thinkingTypes?.let { types -> putJsonArray("thinkingTypes") { types.forEach { add(it) } } }
                                        m.chat?.let { put("chat", it) }
                                        m.endpoint?.let { e ->
                                            putJsonObject("api") {
                                                put("protocol", e.protocol.wire); put("baseUrl", e.baseUrl)
                                                e.reasoningField?.let { put("reasoningField", it) }
                                            }
                                        }
                                        if (m.unsupportedApis.isNotEmpty()) putJsonArray("unsupportedApis") { m.unsupportedApis.forEach { add(it) } }
                                        m.protocols?.takeIf { it.isNotEmpty() }?.let { ps -> putJsonArray("protocols") { ps.forEach { add(it.wire) } } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("providers") {
                _configs.value.forEach { c ->
                    addJsonObject {
                        put("id", c.id); put("providerId", c.providerId); put("name", c.name); put("kind", c.kind.name)
                        put("baseUrl", c.baseUrl); put("model", c.model)
                        put("toolCalls", c.capabilities.toolCalls); put("vision", c.capabilities.vision)
                        put("contextTokens", c.capabilities.contextTokens); put("reasoning", c.capabilities.reasoning)
                        put("groundingVerified", c.capabilities.groundingVerified)
                        put("promptCaching", c.capabilities.promptCaching.wire)
                    }
                }
            }
            putJsonObject("uses") {
                ModelRole.entries.forEach { role ->
                    when (val c = _roles.value[role]) {
                        is RoleChoice.Model -> putJsonObject(role.name) {
                            put("use", USE_MODEL); put("config", c.configId)
                            _roles.value.reasoningEfforts[role]?.let { put("reasoningEffort", it) }
                        }
                        RoleChoice.SameAsMain -> putJsonObject(role.name) { put("use", USE_MAIN) }
                        RoleChoice.Off -> putJsonObject(role.name) { put("use", USE_NONE) }
                        null -> Unit
                    }
                }
                if (_roles.value.mainFallbacks.isNotEmpty()) putJsonArray("MainFallbacks") {
                    _roles.value.mainFallbacks.forEach { add(it) }
                }
                if (_roles.value.visionFallbacks.isNotEmpty()) putJsonArray("VisionFallbacks") {
                    _roles.value.visionFallbacks.forEach { add(it) }
                }
                if (_roles.value.lightFallbacks.isNotEmpty()) putJsonArray("LightFallbacks") {
                    _roles.value.lightFallbacks.forEach { add(it) }
                }
            }
        }
        return root.toString()
    }

    private fun keysJson() = buildJsonObject { keys.forEach { (id, v) -> put(id, v) } }.toString()

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "cannot write ${f.name}" }
        }
    }

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String) = Base64.getDecoder().decode(s)

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val providers: ProviderStore
    }

    companion object {
        private const val CONFIG_FILE = "providers.json"
        private const val KEY_FILE = "provider-keys.json"
        private const val CHATGPT_HOST_FILE = "chatgpt-host.json"

        /** 各家预设沿用的旧上下文默认值;预设改了默认后,存着这个值的配置由 [repairStaleContextDefaults] 跟上去。 */
        private const val LEGACY_DEFAULT_CONTEXT_TOKENS = 64_000

        /** `uses` 里每个用途的 `use`:具体模型(另记 `config`)、用主模型、不用。旧版本的文件里只有 `roles`。 */
        private const val USE_MODEL = "model"
        private const val USE_MAIN = "main"
        private const val USE_NONE = "none"

        // 连服务商的客户端都经 NetProxy 建:走设置 › 网络代理,本机、局域网地址(本地模型)直连
        private val http by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)) }

        /** 和对话模型共用的连接池;同一服务商的其他请求(如语音转写)也用它。 */
        val modelHttp: OkHttpClient get() = http

        /** 列模型、账号登录换令牌用的:读超时短一些,同样走网络代理。 */
        private val shortHttp by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)) }

        private val catalog by lazy { ModelCatalog(shortHttp) }

        private val defaultChatGptAuth by lazy { ChatGptAuth(shortHttp) }
        private val defaultOpenRouterAuth by lazy { OpenRouterAuth(shortHttp) }
        private val defaultXaiAuth by lazy { XaiAuth(shortHttp) }

        /** ChatGPT 服务商的名字(品牌名,不随界面语言变)。 */
        const val CHATGPT_NAME = "ChatGPT"
        /** 用 xAI 账号登录的服务商的名字(品牌名,不随界面语言变)。和填 Key 的「xAI」预设不是同一家。 */
        const val XAI_NAME = "xAI / Grok"
        /** 用 OpenRouter 账号授权的服务商:名字和预设一样,[Provider.signIn] 标成这个。 */
        const val OPENROUTER_NAME = "OpenRouter"
        const val OPENROUTER_SIGN_IN = "openrouter"
        /** 出错说明里怎么称呼这家的登录。 */
        private const val XAI_ACCOUNT = "xAI"

        /**
         * 试调用:临时配置的 id、系统提示、那句话、试用的工具、回复上限。
         * 上限留得够一次工具调用,连同必须先想一想的模型的思考(Messages、generateContent 把思考也算在上限里)。
         */
        private const val PROBE_ID = "probe"
        private const val PROBE_SYSTEM = "This is a connection test. Call the get_time tool now. Do not answer with text."
        private const val PROBE_TEXT = "What time is it? Use the get_time tool."
        private val PROBE_TOOL = ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{}}""")
        private const val PROBE_MAX_TOKENS = 1024

        /**
         * 列模型的接口:OpenAI 兼容、Responses、ChatGPT 和 xAI 都是 `GET {base}/models`(Bearer;订阅登录的 [apiKey] 是访问令牌)。
         * [headers] 每个请求都带(见 [ClientHeaders])。
         */
        suspend fun listAvailable(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, headers: Map<String, String>): ModelListResult =
            when (kind) {
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    catalog.openAiCompat(baseUrl, apiKey, headers)
                ProviderConfig.Kind.Anthropic -> catalog.anthropic(baseUrl, apiKey.orEmpty(), headers)
                ProviderConfig.Kind.Gemini -> catalog.gemini(baseUrl, apiKey.orEmpty(), headers)
            }

        /**
         * 调用这个模型用哪种适配器、交给它什么 Base URL。建模型([buildModel])和给思考档位([ReasoningOptions])都按它。
         * 订阅登录(xAI)固定走这一家的接口,不跟模型列表里写的别的协议走,免得把订阅转去另一个接口。
         */
        internal fun adapterFor(cfg: ProviderConfig, endpoint: ModelEndpoint?): Pair<ProviderConfig.Kind, String> =
            if (endpoint == null || cfg.kind == ProviderConfig.Kind.Xai) cfg.kind to cfg.baseUrl
            else ProviderConfig.Kind.of(endpoint.protocol) to endpoint.adapterBaseUrl

        /** 产品里的那份(App 建):配置在 files/settings 下,Key 用 Android 密钥库加密,用量记到 [usage]。 */
        fun create(
            context: Context, usage: UsageSink, userAgent: String, dailyUsage: (String) -> Long = { 0L },
            proactiveUsage: () -> Long = { 0L }, proactiveLimit: () -> Long = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
            proactiveCalls: () -> Long = { 0L },
        ): ProviderStore =
            ProviderStore(File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(), usage,
                dailyUsage = dailyUsage, userAgent = userAgent, proactiveUsage = proactiveUsage, proactiveLimit = proactiveLimit,
                proactiveCalls = proactiveCalls)

        /** App 内共用的那份(经 [Owner] 给出)。 */
        fun get(context: Context): ProviderStore = (context.applicationContext as? Owner)?.providers
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement ProviderStore.Owner to give the model settings")
    }
}
