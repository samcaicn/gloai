package com.wangzan101.mika.settings

/**
 * Per-config, per-field secrets, stored namespaced inside the encrypted MCP secrets file
 * (`mcp-server-secrets.bin`). Each field secret is keyed `field:<configId>:<fieldId>`, so it
 * never collides with the legacy Bearer token (bare `configId`) or OAuth tokens (`oauth:<configId>`).
 *
 * The encrypted map itself is owned by [McpServerStore]; this class only decides the namespace
 * and the get/set/all/clear semantics. Values are opaque plaintext here — they are encrypted at
 * rest by the store's [KeyCipher].
 */
class McpFieldSecrets(
    private val load: () -> Map<String, String>,
    private val save: (Map<String, String>) -> Unit,
) {
    private fun key(configId: String, fieldId: String) = "field:$configId:$fieldId"

    fun get(configId: String, fieldId: String): String? = load()[key(configId, fieldId)]

    fun set(configId: String, fieldId: String, value: String?) {
        val secrets = load().toMutableMap()
        val key = key(configId, fieldId)
        if (value.isNullOrBlank()) secrets.remove(key) else secrets[key] = value
        save(secrets)
    }

    /** All field secrets of one server, keyed by field id. */
    fun all(configId: String): Map<String, String> {
        val prefix = "field:$configId:"
        return load().filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    fun clear(configId: String) {
        val prefix = "field:$configId:"
        val secrets = load()
        if (secrets.keys.none { it.startsWith(prefix) }) return
        save(secrets.filterKeys { !it.startsWith(prefix) })
    }
}
