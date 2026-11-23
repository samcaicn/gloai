package com.wangzan101.mika.core.model.usage

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次逻辑模型调用里实际交给 HTTP 客户端的推理请求数。
 * 适配器内部的兼容重试和外层重试都经过同一计数点；只存在于当前调用的协程上下文，不跨调用累计。
 */
internal class ModelHttpAttempts(
    /** 同一次逻辑调用里第二个及以后的请求发出之前调(参数:这次调用已经发了几个);抛出就不发。 */
    private val beforeRetry: ((started: Int) -> Unit)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelHttpAttempts>

    private val count = AtomicInteger()
    val started: Int get() = count.get()

    fun start() {
        count.get().let { if (it > 0) beforeRetry?.invoke(it) }
        count.incrementAndGet()
    }
}
