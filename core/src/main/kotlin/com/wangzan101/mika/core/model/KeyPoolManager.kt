package com.wangzan101.mika.core.model

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-Key Rotation and Cooldown Engine.
 *
 * Replaces static single-API-key bottlenecks with dynamic key-pooling:
 * - Parses single, comma-separated, semicolon-separated, or newline-delimited keys.
 * - Sanitizes invisible characters, trims, strips surrounding quotes, and verifies header legality.
 * - Rotates among active keys (round-robin / priority).
 * - Traps HTTP 429 rate-limits, marking the specific key as [KeyStatus.RateLimited] with cooldown,
 *   enabling immediate, sub-50ms rotation to the next active key.
 * - Automatically unfreezes keys once their cooldown timer expires.
 * - Distinguishes between temporary rate limits (429) and permanent authentication failures (401/403).
 */
class KeyPoolManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val defaultCooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    enum class KeyStatus {
        Active,
        RateLimited,
        Exhausted,
    }

    data class KeyEntry(
        val key: String,
        @Volatile var status: KeyStatus = KeyStatus.Active,
        @Volatile var resetAtMillis: Long = 0L,
        @Volatile var failureCount: Int = 0,
        @Volatile var lastUsedMillis: Long = 0L,
    ) {
        val isUsable: Boolean get() = status == KeyStatus.Active
    }

    private val providerPools = ConcurrentHashMap<String, ProviderPool>()

    private class ProviderPool(
        val keys: List<KeyEntry>,
        val counter: AtomicInteger = AtomicInteger(0),
    )

    /**
     * Parses raw input into a list of clean, unique, header-legal API keys.
     * Accepts:
     * - Single key: "gsk_123"
     * - Comma-delimited: "gsk_1, gsk_2, gsk_3"
     * - Newline-delimited: "gsk_1\ngsk_2"
     * - Semicolon-delimited: "gsk_1; gsk_2"
     * - Surrounding quotes or brackets: '["key1", "key2"]' or "\"key1\", \"key2\""
     */
    fun parseKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw
            .trim()
            .trimStart('[', '{', '(')
            .trimEnd(']', '}', ')')

        return cleaned
            .split(DELIMITER_REGEX)
            .map { it.trim().trim('"', '\'') }
            .map { ApiKeyText.accept(it).text.trim() }
            .filter { it.isNotBlank() && ApiKeyText.isHeaderLegal(it) }
            .distinct()
    }

    /**
     * Registers keys for a given [providerId].
     * Preserves existing status (e.g. cooldown timers) for keys that were already registered.
     */
    @Synchronized
    fun register(providerId: String, rawKeys: String) {
        register(providerId, parseKeys(rawKeys))
    }

    /**
     * Registers a list of sanitized keys for [providerId].
     */
    @Synchronized
    fun register(providerId: String, keys: List<String>) {
        if (keys.isEmpty()) {
            providerPools.remove(providerId)
            return
        }
        val previous = providerPools[providerId]
        // 请求时会反复注册同一份配置,不能重建池并把轮询计数归零。
        if (previous?.keys?.map { it.key } == keys) return
        val existing = previous?.keys?.associateBy { it.key }.orEmpty()
        val entries = keys.map { k ->
            existing[k]?.copy() ?: KeyEntry(key = k)
        }
        providerPools[providerId] = ProviderPool(entries)
    }

    /**
     * Returns the next active, usable API key for [providerId].
     * Automatically unfreezes any [KeyStatus.RateLimited] keys whose cooldown has expired.
     * Returns null if all keys in the pool are exhausted or in cooldown.
     */
    @Synchronized
    fun getActiveKey(providerId: String): String? {
        val pool = providerPools[providerId] ?: return null
        val now = clock()

        // 1. Auto-unfreeze any rate-limited keys whose cooldown has elapsed
        for (entry in pool.keys) {
            if (entry.status == KeyStatus.RateLimited && now >= entry.resetAtMillis) {
                entry.status = KeyStatus.Active
                entry.resetAtMillis = 0L
                entry.failureCount = 0
            }
        }

        // 2. Filter available active keys
        val active = pool.keys.filter { it.isUsable }
        if (active.isEmpty()) return null

        // 3. Round-robin rotation among active keys
        val index = (pool.counter.getAndIncrement() and Int.MAX_VALUE) % active.size
        val chosen = active[index]
        chosen.lastUsedMillis = now
        return chosen.key
    }

    /**
     * Traps HTTP 429 (Too Many Requests) or quota exceeded.
     * Marks [key] under [providerId] as [KeyStatus.RateLimited] until [retryAfterMs] (or default cooldown) passes.
     */
    @Synchronized
    fun markRateLimited(providerId: String, key: String, retryAfterMs: Long? = null) {
        val pool = providerPools[providerId] ?: return
        val entry = pool.keys.firstOrNull { it.key == key } ?: return
        val now = clock()
        val cooldown = (retryAfterMs ?: defaultCooldownMs).coerceAtLeast(MIN_COOLDOWN_MS)

        entry.status = KeyStatus.RateLimited
        entry.resetAtMillis = now + cooldown
        entry.failureCount++
    }

    /**
     * Marks [key] under [providerId] as permanently [KeyStatus.Exhausted] (e.g. 401 Unauthorized / Invalid Key).
     */
    @Synchronized
    fun markExhausted(providerId: String, key: String) {
        val pool = providerPools[providerId] ?: return
        val entry = pool.keys.firstOrNull { it.key == key } ?: return
        entry.status = KeyStatus.Exhausted
        entry.failureCount++
    }

    /**
     * 上游拒绝密钥(429/401/403)后的池侧处理:429 是临时限流,按 [retryAfterMs](或默认)冷却;
     * 401/403 是密钥无效或被封,标 [KeyStatus.Exhausted] 不再复活,免得坏密钥每轮冷却期满又被轮询选中。
     */
    @Synchronized
    fun markRejected(providerId: String, key: String, status: Int, retryAfterMs: Long? = null) {
        if (status == 429) markRateLimited(providerId, key, retryAfterMs)
        else markExhausted(providerId, key)
    }

    /**
     * Records a successful response, resetting failure count.
     */
    @Synchronized
    fun recordSuccess(providerId: String, key: String) {
        val pool = providerPools[providerId] ?: return
        val entry = pool.keys.firstOrNull { it.key == key } ?: return
        entry.failureCount = 0
        entry.lastUsedMillis = clock()
    }

    /**
     * Checks if at least one key for [providerId] is currently usable or about to recover.
     */
    @Synchronized
    fun hasAvailableKey(providerId: String): Boolean {
        val pool = providerPools[providerId] ?: return false
        val now = clock()
        return pool.keys.any { it.isUsable || (it.status == KeyStatus.RateLimited && now >= it.resetAtMillis) }
    }

    /**
     * Returns all registered keys for [providerId].
     */
    fun allKeys(providerId: String): List<String> =
        providerPools[providerId]?.keys?.map { it.key }.orEmpty()

    /**
     * Total number of keys in the pool for [providerId].
     */
    fun keyCount(providerId: String): Int =
        providerPools[providerId]?.keys?.size ?: 0

    /**
     * Number of currently usable keys for [providerId].
     */
    @Synchronized
    fun activeKeyCount(providerId: String): Int {
        val pool = providerPools[providerId] ?: return 0
        val now = clock()
        return pool.keys.count { it.isUsable || (it.status == KeyStatus.RateLimited && now >= it.resetAtMillis) }
    }

    /**
     * Resets status for all keys under [providerId] (or all providers if null).
     */
    @Synchronized
    fun reset(providerId: String? = null) {
        if (providerId != null) {
            providerPools[providerId]?.keys?.forEach {
                it.status = KeyStatus.Active
                it.resetAtMillis = 0L
                it.failureCount = 0
            }
        } else {
            providerPools.values.forEach { pool ->
                pool.keys.forEach {
                    it.status = KeyStatus.Active
                    it.resetAtMillis = 0L
                    it.failureCount = 0
                }
            }
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L // 60 seconds default backoff
        const val MIN_COOLDOWN_MS = 2_000L      // Minimum 2 seconds
        private val DELIMITER_REGEX = Regex("[,;\n\r]+")

        /** Global singleton instance shared across providers and models. */
        val shared by lazy { KeyPoolManager() }
    }
}
