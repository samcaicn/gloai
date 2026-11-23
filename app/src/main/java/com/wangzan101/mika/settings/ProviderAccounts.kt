package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.ChatGptSession
import com.wangzan101.mika.core.model.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.model.oauth.AccountSession
import com.wangzan101.mika.core.model.oauth.AccountTokens
import com.wangzan101.mika.core.model.oauth.OauthTokenResult
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.core.model.oauth.XaiAuth
import com.wangzan101.mika.core.model.ListedModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.wangzan101.mika.settings.ProviderStore.Companion.CHATGPT_NAME
import com.wangzan101.mika.settings.ProviderStore.Companion.XAI_NAME
import com.wangzan101.mika.settings.ProviderStore.Companion.OPENROUTER_NAME
import com.wangzan101.mika.settings.ProviderStore.Companion.OPENROUTER_SIGN_IN
import com.wangzan101.mika.settings.ProviderStore.Companion.XAI_ACCOUNT
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// 服务商存储:ChatGPT、xAI、OpenRouter 账号登录与令牌刷新
/** ChatGPT 服务商的登录(和 API Key 存在同一处、同样加密);没有或解不开为 null。 */
fun ProviderStore.chatGptSession(providerId: String): ChatGptSession? = ChatGptSession.fromJson(providerKey(providerId))

/** 已连接的 ChatGPT 服务商(只有一家)。 */
fun ProviderStore.chatGptProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }

/** 登录成功:存下登录和列出的模型;已有 ChatGPT 服务商就更新那一家。 */
fun ProviderStore.connectChatGpt(session: ChatGptSession, models: List<ListedModel>): Provider = synchronized(this) {
    connect(CHATGPT_NAME, ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.toJson(), models) }

/**
 * 退出登录:先在服务端注销刷新令牌(确认不了也照样清),再清掉本机的令牌;注册(client_id)和账号留着,再登录时沿用。
 * 返回服务端是否确认了注销。服务商和它的模型、用途都不动。
 */
suspend fun ProviderStore.signOutChatGpt(providerId: String): Boolean {
    val s = chatGptSession(providerId) ?: return true
    val revoked = s.refreshToken.isEmpty() || chatGptAuth.revoke(s.clientId, s.refreshToken)
    saveChatGptSession(providerId, s.signedOut())
    return revoked
}

/**
 * 这台设备在 ChatGPT 那边的本机标识和注册到的 client_id。标识第一次用时生成,之后一直不变;client_id 注册后记下,
 * 退出登录后留着(再登录沿用,不重复注册),删掉 ChatGPT 服务商时忘掉。两者都不是密钥,明文存。
 */
fun ProviderStore.chatGptRegistration(): Pair<String, String?> {
    return synchronized(this) {
        val saved = runCatching { json.parseToJsonElement(chatGptHostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }
}

/** 记下注册到的 client_id(换令牌前就记,换失败了下次也不用重新注册)。 */
fun ProviderStore.rememberChatGptClient(clientId: String) {
    synchronized(this) {
        val host = chatGptRegistration().first
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", host); put("clientId", clientId) }.toString())
    }
}

internal fun ProviderStore.saveChatGptSession(providerId: String, session: ChatGptSession) {
    synchronized(this) {
        if (provider(providerId) != null) setKey(providerId, session.toJson())
    }
}

internal fun ProviderStore.tokensFor(providerId: String): ChatGptTokens = synchronized(chatGptTokens) {
    chatGptTokens.getOrPut(providerId) {
        ChatGptTokens(chatGptAuth, { chatGptSession(providerId) }, { saveChatGptSession(providerId, it) })
    }
}

/** 订阅账号的登录(和 API Key 存在同一处、同样加密);没有或解不开为 null。 */
fun ProviderStore.accountSession(providerId: String): AccountSession? = AccountSession.fromJson(providerKey(providerId))

fun ProviderStore.xaiProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.Xai }

/**
 * 登录成功:存下登录和列出的模型。同一家账号登录只有一个服务商,再登录就更新它。
 */
fun ProviderStore.connectAccount(session: AccountSession, models: List<ListedModel>): Provider {
    return synchronized(this) {
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
}

/** 退出登录:只清这台手机上的令牌。xAI 账号里的授权要到 xAI 的设置里自己断开。 */
fun ProviderStore.signOutAccount(providerId: String) {
    synchronized(this) {
        val s = accountSession(providerId) ?: return
        saveAccount(providerId, s.signedOut())
    }
}

internal fun ProviderStore.saveAccount(providerId: String, session: AccountSession) {
    synchronized(this) {
        if (provider(providerId) == null) return
        setKey(providerId, session.toJson())
        val base = session.apiBase
        if (base.isNotBlank() && provider(providerId)?.baseUrl != base) {
            _providers.value = _providers.value.map { if (it.id == providerId) it.copy(baseUrl = base) else it }
            _configs.value = _configs.value.map { if (it.providerId == providerId) it.copy(baseUrl = base) else it }
            writeConfig()
        }
    }
}

/** 用 OpenRouter 账号授权连上的那一家;没有为 null。 */
fun ProviderStore.openRouterAccount(): Provider? = _providers.value.firstOrNull { it.signIn == OPENROUTER_SIGN_IN }

/**
 * 授权换到了 Key:按 OpenRouter 预设存成一家普通的 OpenAI 兼容服务商(Key 照常加密),标上是授权来的。
 * 已经有授权来的那一家就更新它;用户自己填 Key 的 OpenRouter 不动,两家并存。
 */
fun ProviderStore.connectOpenRouter(apiKey: String, models: List<ListedModel>): Provider = synchronized(this) {
    connect(OPENROUTER_NAME, ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, apiKey, models, signIn = OPENROUTER_SIGN_IN) }

/** 退出 OpenRouter 登录:删掉这台手机上的 Key;服务商、模型和用途留着。OpenRouter 那边的 Key 要用户到 Keys 页面删。 */
fun ProviderStore.signOutOpenRouter(providerId: String) {
    synchronized(this) {
        if (provider(providerId)?.signIn == OPENROUTER_SIGN_IN && providerId in keys) setKey(providerId, "")
    }
}

/**
 * 刷新换到的令牌写回去,前提是这期间存着的登录没变:换令牌要等网络,用户可能已经退出或重新登录,
 * 那样旧的刷新结果不能盖掉(否则退出了又被悄悄登录回来,或新登录被旧令牌顶掉)。
 */
internal fun ProviderStore.saveRefreshed(providerId: String, before: AccountSession, after: AccountSession) {
    synchronized(this) {
        val now = accountSession(providerId) ?: return
        if (now.refreshToken != before.refreshToken || now.accessToken != before.accessToken) return
        saveAccount(providerId, after)
    }
}

internal fun ProviderStore.accountTokensFor(providerId: String): AccountTokens = synchronized(accountTokens) {
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
