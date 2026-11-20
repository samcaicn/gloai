package com.wangzan101.mika.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit Breaker for upstream AI providers and endpoints.
 *
 * Prevents the autonomous agent from stalling or freezing when an upstream provider
 * suffers degraded performance, 5xx outages, or connection timeouts:
 * - [State.Closed]: Normal healthy operation. Traffic flows freely.
 * - [State.Open]: Tripped after [failureThreshold] consecutive server errors / timeouts.
 *   Immediately bypasses this provider for [resetTimeoutMs], allowing instant waterfall cascade
 *   to backup models without waiting for repeated network timeouts.
 * - [State.HalfOpen]: Cooldown has elapsed. Sends a single trial probe to verify upstream health.
 *   If the probe succeeds, the circuit closes; if it fails, it returns to [State.Open].
 */
class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val resetTimeoutMs: Long = DEFAULT_RESET_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State {
        Closed,
        Open,
        HalfOpen,
    }

    private data class TargetEntry(
        @Volatile var state: State = State.Closed,
        @Volatile var consecutiveFailures: Int = 0,
        @Volatile var lastFailureTime: Long = 0L,
        @Volatile var probeInFlight: Boolean = false,
    )

    private val targets = ConcurrentHashMap<String, TargetEntry>()

    /**
     * Determines whether a request to [target] is allowed.
     * Returns false if the circuit is [State.Open], allowing callers to fail fast to the next fallback.
     */
    @Synchronized
    fun allowRequest(target: String): Boolean {
        val entry = targets.getOrPut(target) { TargetEntry() }
        val now = clock()

        return when (entry.state) {
            State.Closed -> true
            State.Open -> {
                if (now - entry.lastFailureTime >= resetTimeoutMs) {
                    entry.state = State.HalfOpen
                    entry.probeInFlight = true
                    true
                } else {
                    false
                }
            }
            State.HalfOpen -> {
                if (!entry.probeInFlight) {
                    entry.probeInFlight = true
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Records a successful response from [target], resetting the circuit to [State.Closed].
     */
    @Synchronized
    fun recordSuccess(target: String) {
        val entry = targets[target] ?: return
        entry.state = State.Closed
        entry.consecutiveFailures = 0
        entry.probeInFlight = false
    }

    /**
     * Records a failure for [target].
     * If the error qualifies as a server-side outage (5xx, network timeout, proxy crash)
     * and exceeds [failureThreshold], trips the circuit to [State.Open].
     */
    @Synchronized
    fun recordFailure(target: String, error: ModelError? = null) {
        if (!isTrippableError(error)) return

        val entry = targets.getOrPut(target) { TargetEntry() }
        entry.consecutiveFailures++
        entry.lastFailureTime = clock()
        entry.probeInFlight = false

        if (entry.state == State.HalfOpen || entry.consecutiveFailures >= failureThreshold) {
            entry.state = State.Open
        }
    }

    /**
     * Returns the current [State] of the circuit for [target].
     */
    @Synchronized
    fun getState(target: String): State {
        val entry = targets[target] ?: return State.Closed
        if (entry.state == State.Open && clock() - entry.lastFailureTime >= resetTimeoutMs) {
            return State.HalfOpen
        }
        return entry.state
    }

    /**
     * Resets circuit state for [target] (or all targets if null).
     */
    @Synchronized
    fun reset(target: String? = null) {
        if (target != null) {
            targets.remove(target)
        } else {
            targets.clear()
        }
    }

    /**
     * Identifies errors that indicate upstream infrastructure degradation rather than client error.
     */
    private fun isTrippableError(error: ModelError?): Boolean {
        if (error == null) return true
        return when (error) {
            is ModelError.Server -> error.status in 500..599
            is ModelError.Network,
            is ModelError.Proxy,
            is ModelError.StreamCut -> true
            else -> false
        }
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
        const val DEFAULT_RESET_TIMEOUT_MS = 30_000L // 30 seconds

        /** Global singleton instance. */
        val shared by lazy { CircuitBreaker() }
    }
}
