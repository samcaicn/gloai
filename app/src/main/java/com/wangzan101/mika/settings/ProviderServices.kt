package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
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
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.search.GoogleGroundingSearch
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.DeepSeekPriceTable
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import com.wangzan101.mika.settings.ProviderStore.GroundingTarget
import com.wangzan101.mika.settings.ProviderStore.Companion.http
import com.wangzan101.mika.settings.ProviderStore.Companion.modelHttp
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_ID
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_SYSTEM
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_TEXT
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_TOOL
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_MAX_TOKENS
import com.wangzan101.mika.settings.ProviderStore.Companion.adapterFor
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// 服务商存储:主动任务计量、搜索接地、语音与图像模型、探测与列模型
/**
 * 主动能力用的模型:轻量模型,没有时用主模型;用量记在 [UsagePurpose.Proactive] 名下。
 * 每次请求前只查主动能力自己的每日预算(见 [ProactiveBudget]),不查也不占服务商的每日预算。
 */
fun ProviderStore.proactiveModel(session: ModelSession = ModelSession.fresh(), call: ProactiveCall = ProactiveCall.Triage): ChatModel? =
    proactiveTargeted(call, session)?.model

/** [proactiveModel] 实际会用的服务商和模型(同样先轻量、后主模型);不建模型、不读 Key。界面据此请用户确认。 */
fun ProviderStore.proactiveTarget(): TriageTarget? = proactiveConfig()?.let { (_, cfg) -> targetOf(cfg) }

/**
 * 主动能力的模型(同 [proactiveModel]),连同建它用的那条配置对应的服务商和模型:两样出自同一次读配置。
 * 发请求前对着这个目标查同意,中间换了模型也不会把通知文字发给没同意过的那个。
 * [call] 是这次要发哪一种请求:回复上限跟着它定(见 [ProactiveModelSetup])。没有默认值:漏了它,回复长的那几种就会悄悄用上短上限。
 */
fun ProviderStore.proactiveTargeted(call: ProactiveCall, session: ModelSession = ModelSession.fresh()): TargetedModel? =
    proactiveConfig()?.let { (role, cfg) ->
        TargetedModel(targetOf(cfg), meteredWith(cfg, role, session, UsagePurpose.Proactive, ::checkProactiveBudget, call, ::checkProactiveRetry))
    }

/** 主动能力用的那条配置和它的用途:轻量模型,没有时用主模型。只读一次配置。 */
internal fun ProviderStore.proactiveConfig(): Pair<ModelRole, ProviderConfig>? =
    configFor(ModelRole.Light)?.let { ModelRole.Light to it } ?: configFor(ModelRole.Main)?.let { ModelRole.Main to it }

internal fun ProviderStore.targetOf(cfg: ProviderConfig) = TriageTarget(cfg.providerId, provider(cfg.providerId)?.name ?: cfg.name, cfg.model)

internal fun ProviderStore.meteredFor(
    role: ModelRole, session: ModelSession, purpose: UsagePurpose, beforeCall: (UsageSource) -> Unit,
): ChatModel? = configFor(role)?.let { cfg -> meteredWith(cfg, role, session, purpose, beforeCall) }

/** 用 [cfg](已按 [role] 取出的那条配置)建模型,套好计量。[effortOverride] 为组里成员各自的思考档位。 */
internal fun ProviderStore.meteredWith(
    cfg: ProviderConfig, role: ModelRole, session: ModelSession, purpose: UsagePurpose, beforeCall: (UsageSource) -> Unit,
    call: ProactiveCall = ProactiveCall.Triage,
    beforeRetry: (UsageSource, Int) -> Unit = { _, _ -> },
    effortOverride: String? = null,
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
    val roleEffort = effortOverride ?: _roles.value.reasoningEffort(role)
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
internal fun ProviderStore.checkProactiveBudget(source: UsageSource) = ProactiveBudget.check(proactiveUsage(), proactiveLimit(), proactiveCalls())

/**
 * 同一次调用里再发一个请求之前:这次调用已经发出的 [started] 个还没记进账本,加上再查。
 * 不然只剩最后一次额度时,一次调用里的兼容重试会把次数上限顶过去。
 */
@Suppress("UNUSED_PARAMETER")
internal fun ProviderStore.checkProactiveRetry(source: UsageSource, started: Int) =
    ProactiveBudget.check(proactiveUsage(), proactiveLimit(), proactiveCalls() + started)

/** Returns a stored Gemini Developer API key (`Kind.Gemini` only). */
fun ProviderStore.geminiDeveloperApiKey(): String? {
    return synchronized(this) {
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
}

internal fun ProviderStore.resolveGrounding(): GroundingTarget? {
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
fun ProviderStore.groundingStatus(): GroundingStatus = when (val target = resolveGrounding()) {
    null -> GroundingStatus(available = false, providerName = null)
    is GroundingTarget.Unsupported -> GroundingStatus(available = false, providerName = target.providerName)
    is GroundingTarget.Gemini -> GroundingStatus(available = true, providerName = target.providerName)
}

/** Builds a Google Search grounding client for the main model, or null when unsupported/unconfigured. */
fun ProviderStore.googleGroundingSearch(session: ModelSession = ModelSession.fresh()): WebSearch? =
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

/**
 * 声音模型:地址、Key、请求头取这家服务商;不套对话模型的计量(朗读用量由调用方另记)。
 * 服务商不存在或地址不合法时为 null。合成比一句话慢，读超时放到 120 秒。
 */
fun ProviderStore.ttsModel(choice: VoiceChoice, capabilities: TtsCapabilities): TtsModel? {
    val p = provider(choice.providerId) ?: return null
    val model = choice.model.trim()
    if (p.baseUrl.trim().toHttpUrlOrNull() == null) return null
    val client = modelHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    val header = headers(p.sessionHeader, ModelSession.fresh())
    val key = activeApiKey(p.id) ?: providerKey(p.id)?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() }
    return when (choice.protocol) {
        TtsProtocol.OpenAiSpeech -> OpenAiSpeechModel(p.baseUrl, key, model, capabilities, client, header)
        TtsProtocol.GeminiTts -> GeminiTtsModel(p.baseUrl, key.orEmpty(), model, capabilities, client, header)
        TtsProtocol.AndroidSystem -> null
    }
}

/**
 * 图片生成用的模型:地址、Key、请求头都取这家服务商;不套对话模型的计量(图片用量由调用方另记)。
 * 服务商、模型或协议缺失时为 null。图比一句话慢,读超时放到 180 秒。
 */
fun ProviderStore.imageModel(choice: ImageChoice, capabilities: ImageCapabilities): ImageModel? {
    val p = provider(choice.providerId) ?: return null
    if (choice.model.isBlank()) return null
    val client = modelHttp.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()
    val header = headers(p.sessionHeader, ModelSession.fresh())
    val key = providerKey(p.id)
    return when (choice.protocol) {
        ImageProtocol.OpenAiImages -> OpenAiImagesModel(p.baseUrl, key, choice.model, capabilities, client, header)
        ImageProtocol.GeminiImage -> GeminiImageModel(p.baseUrl, key.orEmpty(), choice.model, capabilities, client, header)
    }
}

/**
 * 用户确认测试指定模型后，按真实任务的适配器和请求头建出 [model]，
 * 带一个无参数的工具([PROBE_TOOL])、请它调用;回复限得很短(协议允许的话,见 [buildModel])。
 * 回了工具调用为 [ProbeResult.ToolCalled],只回了话为 [ProbeResult.NoToolCall],请求失败带着服务端的报错原样返回。
 * 不计量:这几个 token 不属于任何用途。会话号用新的,不属于任何对话。超时由调用方管。
 */
suspend fun ProviderStore.probe(
    kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String?,
    model: String, endpoint: ModelEndpoint?, caps: Capabilities,
    providerId: String = "",
): ProbeResult {
    val probeKey = apiKey?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() ?: it }
    val cfg = ProviderConfig(PROBE_ID, kind, baseUrl, model, caps.copy(toolCalls = true), providerId = providerId)
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

/** 服务商最近一次列出的这个模型(单独指定的接口、写明的思考方式等);每次建模型时现查,重新列过就用新的。 */
internal fun ProviderStore.listedOf(cfg: ProviderConfig): ListedModel? =
    provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }

/** 列出这个服务商的可用模型(见 [ModelCatalog]);不属于任何对话,声明了会话头的用新的会话号。 */
suspend fun ProviderStore.listModels(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String? = null): ModelListResult {
    val probeKey = apiKey?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() ?: it }
    return modelLister(kind, baseUrl, probeKey, headers(sessionHeader, ModelSession.fresh()))
}

/** 重新列一个已连接服务商的模型:用存着的 Key;订阅登录用访问令牌(快过期先换新的)。 */
suspend fun ProviderStore.listModels(p: Provider): ModelListResult {
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
