package com.wangzan101.mika.core.model

/** 每个逻辑请求中每把密钥最多尝试一次;预算、取消与网络错误仍由原 HTTP 调用处理。拒绝回调带上 HTTP 状态码,接线处据此区分 429 冷却与 401/403 耗尽。 */
internal suspend fun requestWithKeyRotation(
    nextKey: () -> String?,
    rejected: ((String, Long?, Int) -> Unit)?,
    send: suspend (String?) -> RawResponse,
): RawResponse {
    var key = nextKey()
    val attempted = mutableSetOf(key)
    while (true) {
        val response = send(key)
        if (response.status !in setOf(401, 403, 429) || key == null || rejected == null) return response
        rejected(key, HttpSupport.retryAfterMs(response.retryAfter, response.body), response.status)
        val next = nextKey()
        if (next.isNullOrBlank() || !attempted.add(next)) return response
        key = next
    }
}
