package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.ContextLimits
import com.wangzan101.mika.core.model.chatgpt.ChatGptSession
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 一个模型接入:属于哪个服务商([providerId])、模型名、能力。接口类型、地址和名字照抄所属服务商(建模型时用);
 * Key 存在服务商上。这是存储内部的一层:用户选「哪个用途用哪个模型」或把模型加进组,配置随选择建;没人用、也不在任何组里就删。
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
         * 这个接口类型发请求走哪种协议。用它筛掉接口写明了不支持的模型,见 [com.wangzan101.mika.settings.ui.models.ModelSetupViewModel]
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
    /** 用户标为 TTS 的模型名，独立于对话用途和服务商模型列表。 */
    val ttsModels: Set<String> = emptySet(),
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

/** 一个用途选了什么:一个具体模型、一个模型组、用主模型,或不用(只有看图可以不用)。 */
sealed interface RoleChoice {
    /** 用 [configId] 这条模型配置。 */
    data class Model(val configId: String) : RoleChoice
    /** 用 [groupId] 这个模型组(按组成员和策略路由)。 */
    data class Group(val groupId: String) : RoleChoice
    data object SameAsMain : RoleChoice
    data object Off : RoleChoice
}

/**
 * 三个用途各选了什么,都是用户明说的,没有按能力推断的默认分配。
 * [main] 是主模型的配置 id,[mainGroup] 是主模型组的 id,最多一个非空;都为 null 表示还没选。
 * 看图默认不用,轻量默认用主模型。所有角色 (Main, Vision, Light) 都有独立的备援链 (Waterfall Chain)。
 */
data class RoleChoices(
    val main: String? = null,
    val mainGroup: String? = null,
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

    /** 主模型选了没有:具体模型或模型组都算。 */
    val hasMain: Boolean get() = main != null || mainGroup != null

    /** 这个用途的选择;主模型没选时为 null。 */
    operator fun get(role: ModelRole): RoleChoice? = when (role) {
        ModelRole.Main -> mainGroup?.let(RoleChoice::Group) ?: main?.let(RoleChoice::Model)
        ModelRole.Vision -> vision
        ModelRole.Light -> light
    }

    /** 这个用途实际用的配置 id:「用主模型」取主模型的;组、不用、或主模型还没选时为 null。 */
    fun resolveConfig(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Model -> c.configId
        RoleChoice.SameAsMain -> resolveConfig(ModelRole.Main)
        is RoleChoice.Group, RoleChoice.Off, null -> null
    }

    /** 两个用途是否解析到同一条配置;任一用途没配时不算相同。 */
    fun sameModel(first: ModelRole, second: ModelRole): Boolean {
        val a = resolve(first) ?: return false
        return a == resolve(second)
    }

    /** 这个用途实际用的组 id:「用主模型」取主模型的;具体模型、不用、或主模型还没选时为 null。 */
    fun resolveGroup(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Group -> c.groupId
        RoleChoice.SameAsMain -> resolveGroup(ModelRole.Main)
        is RoleChoice.Model, RoleChoice.Off, null -> null
    }

    /** 有用途在用的配置 id;其余的配置没人用,会被删掉。 */
    val referenced: Set<String> get() = ModelRole.entries.mapNotNull(::resolve).toSet() + mainFallbacks + visionFallbacks + lightFallbacks

    /** 兼容旧调用方:这个用途实际用的配置 id。 */
    fun resolve(role: ModelRole): String? = resolveConfig(role)

    /** 改一个用途;这个用途不接受的选择(主模型只能是具体模型或组,轻量不能不用)返回 null。 */
    fun with(role: ModelRole, choice: RoleChoice): RoleChoices? = (when (role) {
        ModelRole.Main -> when (choice) {
            is RoleChoice.Model -> copy(main = choice.configId, mainGroup = null)
            is RoleChoice.Group -> copy(main = null, mainGroup = choice.groupId)
            RoleChoice.SameAsMain, RoleChoice.Off -> null
        }
        ModelRole.Vision -> copy(vision = choice)
        ModelRole.Light -> if (choice == RoleChoice.Off) null else copy(light = choice)
    })?.let { next ->
        if (get(role) == choice) next else next.copy(reasoningEfforts = reasoningEfforts - role)
    }

    fun reasoningEffort(role: ModelRole): String? = when (get(role)) {
        is RoleChoice.Model, is RoleChoice.Group -> reasoningEfforts[role]
        RoleChoice.SameAsMain -> reasoningEfforts[ModelRole.Main]
        else -> null
    }

    /**
     * 指向 [validConfigs] / [validGroups] 之外的选择退回默认:主模型变成没选,看图不用,轻量用主模型。
     * 组为空、或组里没有可用成员时由调用方把它从 [validGroups] 里拿掉。
     */
    fun keepOnly(validConfigs: Set<String>, validGroups: Set<String> = emptySet()): RoleChoices {
        fun RoleChoice.gone() = when (this) {
            is RoleChoice.Model -> configId !in validConfigs
            is RoleChoice.Group -> groupId !in validGroups
            else -> false
        }
        val mainModel = main?.takeIf { it in validConfigs }
        val mainGroupId = mainGroup?.takeIf { it in validGroups }
        return RoleChoices(
            main = mainModel,
            mainGroup = if (mainModel == null) mainGroupId else null,
            vision = if (vision.gone()) RoleChoice.Off else vision,
            light = if (light.gone()) RoleChoice.SameAsMain else light,
            mainFallbacks = mainFallbacks.filter { it in validConfigs },
            visionFallbacks = visionFallbacks.filter { it in validConfigs },
            lightFallbacks = lightFallbacks.filter { it in validConfigs },
            reasoningEfforts = reasoningEfforts.filterKeys { role ->
                when (val c = get(role)) {
                    is RoleChoice.Model -> c.configId in validConfigs
                    is RoleChoice.Group -> c.groupId in validGroups
                    else -> false
                }
            },
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
