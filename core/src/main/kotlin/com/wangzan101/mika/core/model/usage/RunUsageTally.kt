package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.run.record.RunEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次运行里经计量的模型调用与实际 HTTP 请求合计。运行把它放进自己的协程上下文,[metered] 套上的模型每次调用结束顺手加一笔,
 * 所以主循环之外的旁路调用(意图判断、核查、看图、压缩……)和失败重试的每一次都算在这次运行名下;
 * 适配器内部的兼容重发增加实际请求数,但不增加逻辑调用数。不在这个上下文里的调用(交出回答后的结局判定、记忆提取)不算,
 * Token 用量页照样都记。只在内存里加,多线程安全。
 */
class RunUsageTally(val tokenBudget: Long? = null, val deepSeekCost: DeepSeekRunCost? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunUsageTally>

    init { require(tokenBudget == null || tokenBudget > 0) }

    private val logicalCalls = AtomicInteger()
    private val calls = AtomicInteger()
    private val unreported = AtomicInteger()
    private val httpUnreported = AtomicInteger()
    private val input = AtomicLong()
    private val output = AtomicLong()
    private val cached = AtomicLong()

    /** 记一次逻辑调用及其 [httpAttempts] 次实际请求;只有最终成功回复的 [usage] 可记 token。 */
    fun add(usage: Usage?, httpAttempts: Int) {
        logicalCalls.incrementAndGet()
        if (usage == null) unreported.incrementAndGet()
        val attempts = httpAttempts.coerceAtLeast(0)
        calls.addAndGet(attempts)
        httpUnreported.addAndGet((attempts - if (usage == null) 0 else 1).coerceAtLeast(0))
        if (usage == null) return
        input.addAndGet(usage.inputTokens.toLong())
        output.addAndGet(usage.outputTokens.toLong())
        cached.addAndGet(usage.cachedInputTokens.toLong())
    }

    /** 已报告的输入加输出；缓存命中已包含在输入里，不能重复相加。 */
    fun totalTokens(): Long = input.get() + output.get()

    /** 在下一次实际请求前调用；达到预算时不允许收尾、重试或旁路调用继续消耗。 */
    fun checkBudget() {
        val limit = tokenBudget ?: return
        val used = totalTokens()
        if (used >= limit) throw ModelError.TaskBudgetExceeded(limit, used)
    }

    /** 此刻的合计,作为运行记录里的一条 [RunEvent.ModelCalls]。 */
    fun event(atMs: Long, cost: DeepSeekCost? = null) = RunEvent.ModelCalls(
        atMs, logicalCalls.get(), unreported.get(), input.get(), output.get(), cached.get(), calls.get(), httpUnreported.get(), deepSeekCost = cost,
    )
}
