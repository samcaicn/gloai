package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.agent.GroupWeights
import com.wangzan101.mika.core.agent.ModelHealthRegistry
import com.wangzan101.mika.core.agent.StickyTable
import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.model.oauth.AccountTokens
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.core.model.oauth.XaiAuth
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.model.usage.UsageSink
import com.wangzan101.mika.core.model.usage.metered
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/**
 * 服务商、模型配置的存取与用途选择。配置存成 JSON 文件,API Key 经 [KeyCipher] 加密后另存一个文件,每个服务商一份。
 *
 * 用户管三层:连了哪些服务商、每个用途([RoleChoices])用哪个模型或模型组、模型组里有哪些模型。
 * 模型配置([ProviderConfig])是内部的一层:选模型或用进组时当场建,没有用途、也没有组再用它就删掉
 * (见 [prune];Key 按服务商存,删配置不动 Key)。选具体模型时把对应的能力标记记为 true(见 [choose])。
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
    internal val cipher: KeyCipher,
    internal val usage: UsageSink = UsageSink { _, _ -> },
    /** 每次请求前实时查询这家当前本地日的已报告 Token；默认不启用每日限制。 */
    internal val dailyUsage: (String) -> Long = { 0L },
    internal val newId: () -> String = { UUID.randomUUID().toString() },
    /** 发给模型服务的 User-Agent;App 给 `Mika/<版本>`。 */
    internal val userAgent: String = "Mika",
    /** 列模型:接口类型、地址、Key、每个请求都带的头。 */
    internal val modelLister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = ::listAvailable,
    /** 「用 ChatGPT 登录」的授权服务(换令牌、刷新、注销)。 */
    val chatGptAuth: ChatGptAuth = defaultChatGptAuth,
    /** xAI 订阅登录(设备码、刷新)。 */
    val xaiAuth: XaiAuth = defaultXaiAuth,
    /** OpenRouter 账号授权(换 Key)。 */
    val openRouterAuth: OpenRouterAuth = defaultOpenRouterAuth,
    /** 主动能力今天已报告的 Token(各服务商合计)、它的每日预算、今天已发出的请求数;每次请求前实时读。 */
    internal val proactiveUsage: () -> Long = { 0L },
    internal val proactiveLimit: () -> Long = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
    internal val proactiveCalls: () -> Long = { 0L },
    /**
     * 建可调用的模型:配置、Key、服务商列表里给这个模型单独指定的接口(没有为 null,按配置的接口类型和地址)、请求头等。
     * 为 null 时用内置的适配器(测试里换成假的)。
     */
    modelFactory: ((ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel)? = null,
) {
    internal val modelFactory: (ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel = modelFactory ?: ::buildModel
    /** 每个 ChatGPT 服务商一份:同一时刻只有一个在换令牌。 */
    internal val chatGptTokens = mutableMapOf<String, ChatGptTokens>()
    /** 每个订阅账号一份,和 ChatGPT 一样同一时刻只有一个在换令牌。 */
    internal val accountTokens = mutableMapOf<String, AccountTokens>()
    internal val configFile = File(dir, CONFIG_FILE)
    internal val keyFile = File(dir, KEY_FILE)
    internal val chatGptHostFile = File(dir, CHATGPT_HOST_FILE)
    internal val json = Json { ignoreUnknownKeys = true }

    internal val _providers = MutableStateFlow<List<Provider>>(emptyList())
    /** 已连接的服务商,按连接的先后。 */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    // 换 Key 时服务商列表可能没变，额度页用版本号作废旧结果。
    internal val _providerKeyRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    internal val providerKeyRevisions = _providerKeyRevisions.asStateFlow()

    internal val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    internal val _roles = MutableStateFlow(RoleChoices())
    /** 三个用途各选了什么;指向的配置都在 [configs] 里。 */
    val roles: StateFlow<RoleChoices> = _roles.asStateFlow()

    internal val _groups = MutableStateFlow<List<ModelGroup>>(emptyList())
    /** 模型组,按创建/保存的先后。 */
    val groups: StateFlow<List<ModelGroup>> = _groups.asStateFlow()

    /** 组成员健康状态,进程内共享:同一服务商的同一模型冷却一次就够。 */
    internal val health = ModelHealthRegistry()
    /** 同一对话固定成员,进程内;重启清空。 */
    internal val sticky = StickyTable()
    /** 加权轮询的累计值,按组共享;每个任务新建池时沿用。 */
    internal val weights = GroupWeights()

    /** 服务商 id → 加密后的 Key。 */
    internal var keys: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<ProviderConfig> = _configs.value

    fun provider(id: String): Provider? = _providers.value.firstOrNull { it.id == id }

    internal fun ProviderConfig.canUseForChat(): Boolean = model.isNotBlank()

    internal sealed interface GroundingTarget {
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

    internal fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    internal fun JsonObject.positiveInt(k: String) = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    internal fun JsonObject.positiveLong(k: String) = (this[k] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val providers: ProviderStore
    }

    /**
     * 保存用户选定的模型用途。
     */
    @Synchronized
    fun use(role: ModelRole, providerId: String, model: String, caps: Capabilities): Boolean {
        val p = provider(providerId) ?: return false
        val name = model.trim()
        if (name.isEmpty()) return false
        val existing = _configs.value.firstOrNull { it.providerId == p.id && it.model == name }
        val config = existing ?: ProviderConfig(newId(), p.kind, p.baseUrl, name, caps, p.name, p.id)
        val selected = if (role == ModelRole.Main) config.copy(capabilities = config.capabilities.copy(toolCalls = true)) else config
        var roles = _roles.value.with(role, RoleChoice.Model(config.id)) ?: return false
        roles.main?.let { roles = roles.copy(mainFallbacks = roles.mainFallbacks - it) }
        val configs = if (existing == null) _configs.value + selected else _configs.value.map {
            if (it.id == existing.id) selected else it
        }
        // 没有用途、也不在任何组里的配置删掉(和 [prune] 一样)
        val used = roles.referenced + _groups.value.flatMap { g -> g.members.map { it.configId } }
        return saveRoleSelection(_providers.value, configs.filter { it.id in used }, roles)
    }

    companion object {
        internal const val CONFIG_FILE = "providers.json"
        internal const val KEY_FILE = "provider-keys.json"
        internal const val CHATGPT_HOST_FILE = "chatgpt-host.json"

        /** 各家预设沿用的旧上下文默认值;预设改了默认后,存着这个值的配置由 [repairStaleContextDefaults] 跟上去。 */
        internal const val LEGACY_DEFAULT_CONTEXT_TOKENS = 64_000

        /** `uses` 里每个用途的 `use`:具体模型(另记 `config`)、模型组(另记 `group`)、用主模型、不用。旧版本的文件里只有 `roles`。 */
        internal const val USE_MODEL = "model"
        internal const val USE_GROUP = "group"
        internal const val USE_MAIN = "main"
        internal const val USE_NONE = "none"

        // 连服务商的客户端都经 NetProxy 建:走设置 › 网络代理,本机、局域网地址(本地模型)直连
        internal val http by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)) }

        /** 和对话模型共用的连接池;同一服务商的其他请求(如语音转写)也用它。 */
        val modelHttp: OkHttpClient get() = http

        /** 列模型、账号登录换令牌用的:读超时短一些,同样走网络代理。 */
        internal val shortHttp by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)) }

        internal val catalog by lazy { ModelCatalog(shortHttp) }

        internal val defaultChatGptAuth by lazy { ChatGptAuth(shortHttp) }
        internal val defaultOpenRouterAuth by lazy { OpenRouterAuth(shortHttp) }
        internal val defaultXaiAuth by lazy { XaiAuth(shortHttp) }

        /** ChatGPT 服务商的名字(品牌名,不随界面语言变)。 */
        const val CHATGPT_NAME = "ChatGPT"
        /** 用 xAI 账号登录的服务商的名字(品牌名,不随界面语言变)。和填 Key 的「xAI」预设不是同一家。 */
        const val XAI_NAME = "xAI / Grok"
        /** 用 OpenRouter 账号授权的服务商:名字和预设一样,[Provider.signIn] 标成这个。 */
        const val OPENROUTER_NAME = "OpenRouter"
        const val OPENROUTER_SIGN_IN = "openrouter"
        /** 出错说明里怎么称呼这家的登录。 */
        internal const val XAI_ACCOUNT = "xAI"

        /**
         * 试调用:临时配置的 id、系统提示、那句话、试用的工具、回复上限。
         * 上限留得够一次工具调用,连同必须先想一想的模型的思考(Messages、generateContent 把思考也算在上限里)。
         */
        internal const val PROBE_ID = "probe"
        internal const val PROBE_SYSTEM = "This is a connection test. Call the get_time tool now. Do not answer with text."
        internal const val PROBE_TEXT = "What time is it? Use the get_time tool."
        internal val PROBE_TOOL = ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{}}""")
        internal const val PROBE_MAX_TOKENS = 1024

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
