package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.agent.PoolMember
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import java.util.concurrent.TimeUnit
import com.wangzan101.mika.settings.ProviderStore.Companion.http
import com.wangzan101.mika.settings.ProviderStore.Companion.XAI_ACCOUNT
import com.wangzan101.mika.settings.ProviderStore.Companion.adapterFor
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// 服务商存储:按用途、配置与模型组造出可调用的模型及备用链
/**
 * 这个用途实际用的配置;没选、不用时为 null。看图标记按当下推出来,不看存着的列表数据有没有写:
 * 看图这一路总是 true(用户选了它来看图);别的用途按 [ModelOptions.seesImages]——用户在这个模型上说过的为准,
 * 没说过而看图也用这条配置(选了同一个模型,或「用主模型」)的,同样算能看,截图直接附给它,不必另调一次看图。
 */
fun ProviderStore.configFor(role: ModelRole): ProviderConfig? {
    val choices = _roles.value
    // 组按「排在最前的启用成员」给界面一个代表;真正的路由在 modelFor 里按成员走
    val id = choices.resolveConfig(role) ?: groupFor(role)?.enabledMembers?.firstOrNull()?.configId ?: return null
    val cfg = _configs.value.firstOrNull { it.id == id } ?: return null
    val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
        provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = choices.resolveConfig(ModelRole.Vision) == cfg.id)
    return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
}

/** 主模型在它那家还没测试通过时给出那一家(见 [Provider.untested]);任务因模型失败时据此提示去「重新测试」。 */
fun ProviderStore.untestedMainProvider(): Provider? {
    val cfg = configFor(ModelRole.Main) ?: return null
    val candidates = groupFor(ModelRole.Main)?.enabledMembers?.mapNotNull { m -> _configs.value.firstOrNull { it.id == m.configId } }
        ?: listOf(cfg)
    return candidates.firstNotNullOfOrNull { c -> provider(c.providerId)?.takeIf { it.untested(c.model) } }
}

/**
 * 这个用途用的模型,套好计量;没选、不用时为 null。用量记在 [role] 名下:「用主模型」的看图、轻量调用
 * 记作看图、轻量,服务商和模型是主模型那条配置的。这是 App 里建可调用模型的唯一入口(主动能力走 [proactiveModel])。
 * 选了模型组时返回 [GroupPool]:成员各自计量,组本身只负责选择和转移。
 * [session] 是这次调用属于的对话(同一段对话里建的模型都给同一个);服务商声明了会话头的,每个请求都带上它。
 */
fun ProviderStore.modelFor(role: ModelRole, session: ModelSession = ModelSession.fresh()): ChatModel? {
    groupFor(role)?.let { group -> return groupModelFor(role, group, session) }
    return meteredFor(role, session, UsagePurpose.of(role), ::checkDailyBudget)
}

/** 按组的成员顺序建出每个成员;成员的上下文设置、思考档位、请求头都按各自的配置来。 */
internal fun ProviderStore.groupModelFor(role: ModelRole, group: ModelGroup, session: ModelSession): ChatModel? {
    val members = group.members.filter { it.enabled }.mapNotNull { m ->
        val cfg = _configs.value.firstOrNull { it.id == m.configId } ?: return@mapNotNull null
        val p = provider(cfg.providerId) ?: return@mapNotNull null
        val effort = memberReasoningEffort(cfg, p, m.reasoningEffort ?: _roles.value.reasoningEffort(role))
        val model = meteredWith(cfg, role, session, UsagePurpose.of(role), ::checkDailyBudget, effortOverride = effort)
        PoolMember(
            key = MemberKey(p.id, cfg.model), model = model, priority = m.priority,
            weight = m.weight.takeIf { it > 0 } ?: 1, costRank = m.costRank, providerName = p.name,
        )
    }
    if (members.isEmpty()) return null
    return GroupPool(
        group, members, health, sticky,
        stickyKey = "${group.id}/${session.id}",
        weightedWeights = weights.forGroup(group.id),
    )
}

/** 成员不支持角色选的档位时传 null,免得把一个服务商不认的值发出去。 */
internal fun ProviderStore.memberReasoningEffort(cfg: ProviderConfig, p: Provider, effort: String?): String? {
    if (effort == null) return null
    val levels = ReasoningOptions.of(cfg, listedOf(cfg))?.levels ?: return null
    return effort.takeIf { it in levels }
}

/** [configId] 这条配置本身(不按用途推看图标记);不存在为 null。 */
fun ProviderStore.configById(configId: String): ProviderConfig? = _configs.value.firstOrNull { it.id == configId }

/** 这条配置当指定角色用时的样子: 看图标记按角色规矩推导。 */
internal fun ProviderStore.asRole(cfg: ProviderConfig, role: ModelRole): ProviderConfig {
    val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
        provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = _roles.value.resolve(ModelRole.Vision) == cfg.id)
    return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
}

internal fun ProviderStore.asMain(cfg: ProviderConfig): ProviderConfig = asRole(cfg, ModelRole.Main)

/**
 * 按配置 id 建一个当指定角色用的模型(备援链用):计量、每日预算检查都和该角色同一条路。
 * 配置不存在为 null。
 */
fun ProviderStore.modelForRoleConfig(role: ModelRole, configId: String, session: ModelSession = ModelSession.fresh()): ChatModel? {
    val raw = configById(configId) ?: return null
    if (!raw.canUseForChat() && configId !in _roles.value.referenced) return null
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
fun ProviderStore.modelForConfig(configId: String, session: ModelSession = ModelSession.fresh()): ChatModel? =
    modelForRoleConfig(ModelRole.Main, configId, session)

/**
 * 按指定角色的备援链顺序返回备援模型及其服务商 ID，供故障转移判断预算归属。
 * 配置没了、建不出的位子跳过,链短一点照样能跑。
 */
fun ProviderStore.fallbackChain(role: ModelRole, session: ModelSession = ModelSession.fresh()): Pair<List<ChatModel>, List<String?>> {
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
fun ProviderStore.mainFallbackChain(session: ModelSession = ModelSession.fresh()): Pair<List<ChatModel>, List<String?>> =
    fallbackChain(ModelRole.Main, session)

/** 视觉模型备援链 */
fun ProviderStore.visionFallbackChain(session: ModelSession = ModelSession.fresh()): List<ChatModel> =
    fallbackChain(ModelRole.Vision, session).first

/** 轻量模型备援链 */
fun ProviderStore.lightFallbackChain(session: ModelSession = ModelSession.fresh()): List<ChatModel> =
    fallbackChain(ModelRole.Light, session).first

fun ProviderStore.mainProviderId(): String? = configFor(ModelRole.Main)?.providerId

/** 每次实际请求前重查配置和账本；达到预算时不发请求，也不记一笔虚假调用。 */
internal fun ProviderStore.checkDailyBudget(source: UsageSource) {
    val p = provider(source.providerId) ?: return
    val limit = p.dailyTokenBudget ?: return
    val used = dailyUsage(source.providerId)
    if (used >= limit) throw ModelError.DailyBudgetExceeded(p.name, limit, used, providerId = source.providerId)
}

internal fun ProviderStore.headers(sessionHeader: String?, session: ModelSession) = ClientHeaders.of(userAgent, sessionHeader, session)

/** 不属于任何对话的请求(如语音转写)给这家带的头:客户端标识,声明了会话头的用新的会话号。 */
fun ProviderStore.clientHeaders(p: Provider): Map<String, String> = headers(p.sessionHeader, ModelSession.fresh())

/** 模型单独指定了接口的,按它的协议选适配器、用它的地址;否则按配置的接口类型和地址。Key 都用服务商的。 */
/**
 * 请求头四种协议一样带。回复上限([ClientSetup.maxOutputTokens])只给 Messages(本来就必填)和 generateContent;
 * Chat Completions 不给(有的模型只认 `max_tokens`、有的只认 `max_completion_tokens`,给错了整个请求被拒,
 * 连接测试就会误报),Responses 也不给(ChatGPT 套餐那条路不收 `max_output_tokens`)。
 * 读超时([ClientSetup.readTimeoutSeconds])配置了的按它派生 client(共享连接池与代理),慢速本地推理可调大(#295);
 * 没配置用全局默认(120 秒)。派生只在建模型时发生一次,不影响别的模型。
 */
internal fun ProviderStore.buildModel(cfg: ProviderConfig, apiKey: String?, endpoint: ModelEndpoint?, setup: ClientSetup): ChatModel {
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
