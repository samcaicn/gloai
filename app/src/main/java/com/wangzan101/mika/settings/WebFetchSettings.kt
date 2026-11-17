package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.fetch.TinyFishFetch
import com.wangzan101.mika.core.fetch.WebFetch
import com.wangzan101.mika.core.fetch.WebFetchProvider
import java.util.Base64

/** 一家抓取服务自己的设置:密钥。 */
data class WebFetchEntry(val apiKey: String = "")

/**
 * 网页抓取设置。默认关闭;只有用户明确选好服务商、填好密钥并启用后才创建服务。
 * 各家的密钥分开存([entries]),切换服务商不会把一家的密钥发给另一家。
 */
data class WebFetchConfig(
    val enabled: Boolean = false,
    val provider: WebFetchProvider = WebFetchProvider.TinyFish,
    val entries: Map<WebFetchProvider, WebFetchEntry> = emptyMap(),
) {
    val current: WebFetchEntry get() = entries[provider] ?: WebFetchEntry()

    /** 选中的那一家此刻的问题;能保存为 null。只认有密钥的服务商,没填密钥一律不让保存(开没开都一样)。 */
    val problem: WebFetchProvider.Problem? get() = when {
        provider.keyRequired && current.apiKey.isBlank() -> WebFetchProvider.Problem.Key
        enabled -> provider.problem(current.apiKey)
        else -> null
    }
}

class WebFetchSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-web-fetch-key"),
) {
    fun get(): WebFetchConfig {
        val stored = WebFetchProvider.entries.associateWith { p -> WebFetchEntry(decrypt(prefs.getString(apiKeyKey(p), null))) }
        val enabled = prefs.getBoolean(ENABLED, false)
        val provider = WebFetchProvider.of(prefs.getString(PROVIDER, null)) ?: WebFetchProvider.TinyFish
        return WebFetchConfig(enabled, provider, stored)
    }

    @Synchronized
    fun set(config: WebFetchConfig) {
        require(config.problem == null) { "Invalid web fetch configuration: ${config.problem}" }
        val edit = prefs.edit().putBoolean(ENABLED, config.enabled).putString(PROVIDER, config.provider.wire)
        for (p in WebFetchProvider.entries) {
            val entry = config.entries[p] ?: continue
            val key = entry.apiKey.trim()
            if (key.isEmpty()) edit.remove(apiKeyKey(p))
            else edit.putString(apiKeyKey(p), Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8))))
        }
        check(edit.commit()) { "Cannot save web fetch settings" }
    }

    /** 每次任务重读设置;关闭、没填好或配置损坏时返回 null,任务只会走浏览器。 */
    fun service(): WebFetch? {
        val config = get()
        if (!config.enabled || config.problem != null) return null
        val entry = config.current
        return when (config.provider) {
            WebFetchProvider.TinyFish -> TinyFishFetch(entry.apiKey)
        }
    }

    private fun decrypt(encoded: String?): String = encoded?.let {
        runCatching { String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }.getOrDefault("")
    }.orEmpty()

    companion object {
        private const val PREFS = "web_fetch"
        private const val ENABLED = "enabled"
        private const val PROVIDER = "provider"

        private fun apiKeyKey(p: WebFetchProvider) = "api_key_encrypted_${p.wire}"

        fun get(context: Context) = WebFetchSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}