package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.KeyPoolManager

// 服务商存储:API Key 的增删、轮换与额度连接
/** 服务商的所有配置密钥 (KeyPool)。 */
fun ProviderStore.providerKeys(providerId: String): List<String> {
    val raw = providerKey(providerId) ?: return emptyList()
    return KeyPoolManager.shared.parseKeys(raw)
}

/** 向服务商密钥池中追加新密钥。 */
fun ProviderStore.addProviderKey(providerId: String, newKey: String) {
    synchronized(this) {
        val current = providerKeys(providerId)
        val added = KeyPoolManager.shared.parseKeys(newKey)
        val combined = (current + added).distinct()
        setKey(providerId, combined.joinToString(", "))
    }
}

/** 从服务商密钥池中移除指定密钥。 */
fun ProviderStore.removeProviderKey(providerId: String, keyToRemove: String) {
    synchronized(this) {
        val current = providerKeys(providerId)
        val remaining = current.filterNot { it == keyToRemove }
        setKey(providerId, remaining.joinToString(", "))
    }
}

/** 服务商当前活跃可用的密钥 (优先从 KeyPool 中自动轮询、避开限流密钥)。 */
fun ProviderStore.activeApiKey(providerId: String): String? {
    val raw = providerKey(providerId) ?: return null
    KeyPoolManager.shared.register(providerId, raw)
    return KeyPoolManager.shared.getActiveKey(providerId) ?: providerKeys(providerId).firstOrNull()
}

/** 这个模型配置所属服务商的 Key。动态优先从 KeyPool 中返回健康的活跃密钥。 */
fun ProviderStore.apiKey(configId: String): String? =
    _configs.value.firstOrNull { it.id == configId }?.let { activeApiKey(it.providerId) }

/** 服务商的 Key 原文。解不开(例如密钥库被系统清掉)时当作没有 Key。 */
fun ProviderStore.providerKey(providerId: String): String? {
    val enc = synchronized(this) { keys[providerId] } ?: return null
    return runCatching { String(cipher.decrypt(decode(enc))) }.getOrNull()
}

/** 只为仍匹配这次额度查询的服务商配置取 Key。 */
internal fun ProviderStore.allowanceKey(provider: Provider, revision: Long): String? = synchronized(this) {
    if (matchesAllowanceConnection(provider, revision)) providerKey(provider.id) else null }

internal fun ProviderStore.matchesAllowanceConnection(provider: Provider, revision: Long): Boolean {
    val current = provider(provider.id) ?: return false
    return current.baseUrl == provider.baseUrl && current.kind == provider.kind && current.signIn == provider.signIn &&
        (_providerKeyRevisions.value[provider.id] ?: 0L) == revision
}

/** 这个服务商存了 Key 没有(本地服务可以不填)。 */
fun ProviderStore.hasKey(providerId: String): Boolean = synchronized(this) { providerId in keys }

internal fun ProviderStore.setKey(providerId: String, apiKey: String) {
    keys = if (apiKey.isEmpty()) keys - providerId else keys + (providerId to encode(cipher.encrypt(apiKey.toByteArray())))
    writeAtomically(keyFile, keysJson())
    val revisions = _providerKeyRevisions.value
    _providerKeyRevisions.value = revisions + (providerId to ((revisions[providerId] ?: 0L) + 1))
    KeyPoolManager.shared.register(providerId, apiKey)
}

/** 换掉一个服务商的 Key(它下面的模型都用这一个)。 */
fun ProviderStore.updateProviderKey(providerId: String, apiKey: String) {
    synchronized(this) {
        if (provider(providerId) != null) setKey(providerId, apiKey)
    }
}
