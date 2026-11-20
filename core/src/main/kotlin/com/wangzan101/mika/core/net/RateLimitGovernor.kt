package com.wangzan101.mika.core.net

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级共享的「限流降级」：正常零开销，只有撞到 HTTP 429 才让后续请求等冷却窗口，成功即恢复全速。
 * 把「逐请求退避、几次就放弃」升级成「跨任务、跨子代理统一等限流窗口」。
 *
 * 每个 key 存单调时钟下的绝对截止时间。任务被取消时截止时间仍然会自然过期，后来的任务不会继承一段已经过期的冷却时长。
 * 多任务各自等同一个截止时间，近似共享冷却，避免并发任务集体把服务商打爆。
 *
 * [providerKey] 先用模型名近似：同一服务商的不同模型会各自算冷却（偏保守）。要按服务商精确隔离，
 * 得把服务商身份一路传进来，首版先不做。
 */
internal object RateLimitGovernor {
    private class State {
        @Volatile var cooldownUntilMs = 0L
        @Volatile var consecutive429 = 0
    }

    private val states = ConcurrentHashMap<String, State>()
    private val systemMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L }
    @Volatile private var nowMs: () -> Long = systemMonotonicMs

    /** 发请求前调用：只等到当前绝对截止时间，过期后清零；不在冷却立即返回（零开销）。 */
    suspend fun awaitClear(providerKey: String) {
        val s = states[providerKey] ?: return
        while (true) {
            val until = s.cooldownUntilMs
            val wait = until - nowMs()
            if (wait <= 0) {
                s.cooldownUntilMs = 0
                return
            }
            delay(wait)
            // 正常 delay 会等到截止点。只在没有并发延长冷却时清零，避免覆盖另一条协程刚写入的更晚截止点。
            if (s.cooldownUntilMs == until) {
                s.cooldownUntilMs = 0
                return
            }
        }
    }

    /** 撞 429：记绝对冷却截止时间并返回本次等待(ms)。有 Retry-After 照它(上限 [RETRY_AFTER_CAP]，退避链归零)；没有按连续 429 指数退避。 */
    fun reportRateLimited(providerKey: String, retryAfterMs: Long?): Long {
        val s = states.computeIfAbsent(providerKey) { State() }
        val wait = if (retryAfterMs != null) {
            s.consecutive429 = 0
            retryAfterMs.coerceIn(MIN_RETRY_AFTER_MS, RETRY_AFTER_CAP)
        } else {
            val n = s.consecutive429 + 1
            s.consecutive429 = n
            backoff(n)
        }
        s.cooldownUntilMs = nowMs() + wait
        return wait
    }

    /** 成功(2xx)：清冷却、连续 429 归零，回到全速。 */
    fun reportSuccess(providerKey: String) {
        states[providerKey]?.let {
            it.cooldownUntilMs = 0
            it.consecutive429 = 0
        }
    }

    /** 测试用：清空所有冷却状态，并可注入单调时钟。 */
    internal fun reset(clock: () -> Long = systemMonotonicMs) {
        states.clear()
        nowMs = clock
    }

    /** 无 Retry-After 时的退避：1s→2s→4s→8s→16s→32s，再往上由 [MAX_BACKOFF_MS] 截断。 */
    private fun backoff(n: Int): Long {
        var ms = BASE_BACKOFF_MS
        repeat(minOf(n - 1, 6)) { ms *= 2 }
        return minOf(ms, MAX_BACKOFF_MS)
    }

    private const val MIN_RETRY_AFTER_MS = 1_000L
    private const val RETRY_AFTER_CAP = 30_000L
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
}
