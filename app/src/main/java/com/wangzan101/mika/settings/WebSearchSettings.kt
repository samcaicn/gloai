package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.search.DeepSeekSearch
import com.wangzan101.mika.core.search.ExaSearch
import com.wangzan101.mika.core.search.JinaSearch
import com.wangzan101.mika.core.search.SearxngSearch
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.core.search.YouSearch
import java.util.Base64

/** 一家搜索服务自己的设置:地址(只有要填地址的那几家用)和密钥。 */
data class WebSearchEntry(val endpoint: String = "", val apiKey: String = "")

/** Whether Google Search grounding is available for the current main model. */
data class GroundingStatus(
    val available: Boolean = false,
    val providerName: String? = null,
)

/**
 * 网页搜索设置。默认关闭;只有用户明确选好服务商、填好它要的东西并启用后才创建服务。
 * 各家的地址和密钥分开存([entries]),切换服务商不会把一家的密钥发给另一家。
 */
data class WebSearchConfig(
    val enabled: Boolean = false,
    val provider: WebSearchProvider = WebSearchProvider.You,
    val entries: Map<WebSearchProvider, WebSearchEntry> = emptyMap(),
    val grounding: GroundingStatus = GroundingStatus(),
) {
    val current: WebSearchEntry get() = entries[provider] ?: WebSearchEntry()

    /**
     * 选中的那一家此刻的问题;能保存为 null。必须有密钥的那几家没填密钥一律不让保存(开没开都一样);
     * 地址只在启用时才查。
     */
    val problem: WebSearchProvider.Problem? get() = when {
        provider.keyRequired && current.apiKey.isBlank() -> WebSearchProvider.Problem.Key
        enabled -> provider.problem(current.endpoint, current.apiKey, grounding.available)
        else -> null
    }
}

class WebSearchSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-web-search-key"),
    private val groundingStatus: () -> GroundingStatus = { GroundingStatus() },
    private val googleSearch: () -> WebSearch? = { null },
) {
    fun get(): WebSearchConfig {
        val grounding = groundingStatus()
        val stored = WebSearchProvider.entries.associateWith { p ->
            WebSearchEntry(
                if (p.needsEndpoint) prefs.getString(endpointKey(p), null).orEmpty() else "",
                if (p.takesKey) decrypt(prefs.getString(apiKeyKey(p), null)) else "",
            )
        }
        val enabled = prefs.getBoolean(ENABLED, false)
        val saved = WebSearchProvider.of(prefs.getString(PROVIDER, null))
        if (saved != null) return WebSearchConfig(enabled, saved, stored, grounding)
        // 升级前的设置:那时只有一个「搜索端点网址 + 可选密钥」。网址是 Exa、Jina 的官方接口时归到那一家、密钥跟过去
        // (原来的请求方式它们不认,这样升级后直接能用)。别的网址没有对应的服务商:归到 You.com 但先关掉、旧密钥不要,
        // 免得用户不知道就把搜索词改发给另一家、把给别家的密钥发出去。下次保存时写成新的样子,旧的两个键随之删掉。
        val legacyEndpoint = prefs.getString(LEGACY_ENDPOINT, null).orEmpty()
        val official = legacyOfficial(legacyEndpoint)
        return when {
            official != null -> WebSearchConfig(
                enabled,
                official,
                stored + (official to WebSearchEntry(apiKey = decrypt(prefs.getString(LEGACY_API_KEY, null)))),
                grounding,
            )
            legacyEndpoint.isNotBlank() -> WebSearchConfig(false, WebSearchProvider.You, stored, grounding)
            grounding.available -> WebSearchConfig(false, WebSearchProvider.Google, stored, grounding)
            else -> WebSearchConfig(enabled, WebSearchProvider.You, stored, grounding)
        }
    }

    @Synchronized
    fun set(config: WebSearchConfig) {
        require(config.problem == null) { "Invalid web search configuration: ${config.problem}" }
        val edit = prefs.edit().putBoolean(ENABLED, config.enabled).putString(PROVIDER, config.provider.wire)
            .remove(LEGACY_ENDPOINT).remove(LEGACY_API_KEY)
        for (p in WebSearchProvider.entries) {
            val entry = config.entries[p] ?: continue
            if (p.needsEndpoint) edit.putString(endpointKey(p), entry.endpoint.trim())
            if (p.takesKey) {
                val key = entry.apiKey.trim()
                if (key.isEmpty()) edit.remove(apiKeyKey(p))
                else edit.putString(apiKeyKey(p), Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8))))
            }
        }
        check(edit.commit()) { "Cannot save web search settings" }
    }

    /** 每次任务重读设置;关闭、没填好或配置损坏时返回 null,任务只会走浏览器。 */
    fun service(): WebSearch? {
        val config = get()
        if (!config.enabled || config.problem != null) return null
        val entry = config.current
        return when (config.provider) {
            WebSearchProvider.Google -> if (config.grounding.available) googleSearch() else null
            WebSearchProvider.You -> YouSearch(entry.apiKey)
            WebSearchProvider.Jina -> JinaSearch(entry.apiKey)
            WebSearchProvider.Exa -> ExaSearch(entry.apiKey)
            WebSearchProvider.DeepSeek -> DeepSeekSearch(entry.apiKey)
            WebSearchProvider.SearXNG -> SearxngSearch(checkNotNull(WebSearch.parseEndpoint(entry.endpoint)))
        }
    }

    /** 升级前填的网址是不是 Exa、Jina 的官方接口。 */
    private fun legacyOfficial(endpoint: String): WebSearchProvider? = when (WebSearch.parseEndpoint(endpoint)?.host) {
        "api.exa.ai" -> WebSearchProvider.Exa
        "s.jina.ai" -> WebSearchProvider.Jina
        else -> null
    }

    private fun decrypt(encoded: String?): String = encoded?.let {
        runCatching { String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }.getOrDefault("")
    }.orEmpty()

    companion object {
        private const val PREFS = "web_search"
        private const val ENABLED = "enabled"
        private const val PROVIDER = "provider"
        // 升级前只有一个端点网址和一个密钥;只在迁移时读,保存时删掉
        private const val LEGACY_ENDPOINT = "endpoint"
        private const val LEGACY_API_KEY = "api_key_encrypted"

        private fun endpointKey(p: WebSearchProvider) = "endpoint_${p.wire}"
        private fun apiKeyKey(p: WebSearchProvider) = "api_key_encrypted_${p.wire}"

        fun get(context: Context): WebSearchSettings {
            val app = context.applicationContext
            val providers = { (app as? ProviderStore.Owner)?.providers }
            return WebSearchSettings(
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                groundingStatus = { providers()?.groundingStatus() ?: GroundingStatus() },
                googleSearch = { providers()?.googleGroundingSearch() },
            )
        }
    }
}
