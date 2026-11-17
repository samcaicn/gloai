package com.wangzan101.mika.calls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Local Telecom submission only: neither success nor a completed pulse confirms a remote IVR. */
internal data class CallDtmfResult(val status: String, val sentDigits: Int, val requestedDigits: Int) {
    companion object {
        fun rejected(status: String, requestedDigits: Int = 0) = CallDtmfResult(status, 0, requestedDigits)
    }
}

/** Conservative per-carrier-call AI allowance; cancelled requests are never refunded/retried. */
internal class CallDtmfBudget(private val maxRequests: Int = 24, private val maxDigits: Int = 96) {
    private var requests = 0
    private var digits = 0

    fun reserve(count: Int): Boolean {
        if (count !in 1..CallDtmfExecutor.MAX_DIGITS || requests >= maxRequests || count > maxDigits - digits) return false
        requests++
        digits += count
        return true
    }
}

/** Main-dispatcher confined, one finite sequence at a time. Never queues or changes its captured target. */
internal class CallDtmfExecutor(private val onBusyChanged: () -> Unit = {}) {
    private val lock = Mutex()
    private var active: Sequence? = null
    val busy: Boolean get() = active != null

    /** Call/service/work callbacks stop the captured tone immediately if its ownership became invalid. */
    fun invalidate() {
        active?.let { sequence ->
            if (!runCatching(sequence.stillOwned).getOrDefault(false)) {
                sequence.invalidated = true
                sequence.stopTone()
            }
        }
    }

    /** Explicit hangup intent stops pending keys before any asynchronous AI audio cleanup. */
    fun cancelPending() {
        active?.let { sequence ->
            sequence.invalidated = true
            sequence.stopTone()
        }
    }

    suspend fun send(
        digits: String,
        stillOwned: () -> Boolean,
        play: (Char) -> Unit,
        stop: () -> Unit,
        budget: CallDtmfBudget? = null,
    ): CallDtmfResult {
        if (!validDigits(digits)) return CallDtmfResult.rejected("invalid_digits")
        if (!lock.tryLock()) return CallDtmfResult.rejected("busy", digits.length)
        val sequence = Sequence(stillOwned, stop)
        var sent = 0
        var completed = false
        try {
            currentCoroutineContext().ensureActive()
            if (!stillOwned()) return CallDtmfResult.rejected("unavailable", digits.length)
            if (budget?.reserve(digits.length) == false) return CallDtmfResult.rejected("budget_exceeded", digits.length)
            active = sequence
            onBusyChanged()
            for ((index, digit) in digits.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (sequence.invalidated || !stillOwned()) break
                // Mark before calling Telecom: even an exception may have reached its Binder peer.
                sequence.toneStarted = true
                play(digit)
                sent++
                try {
                    delay(TONE_MS)
                } finally {
                    sequence.stopTone()
                }
                if (sequence.stopFailed || sequence.invalidated || !stillOwned()) break
                if (index == digits.lastIndex) completed = true
                if (index != digits.lastIndex) delay(GAP_MS)
            }
            return CallDtmfResult(
                when {
                    sequence.stopFailed -> if (sent > 0) "partial" else "failed"
                    completed -> "submitted"
                    sent > 0 -> "partial"
                    else -> "unavailable"
                }, sent, digits.length,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CallDtmfResult(if (sent > 0) "partial" else "failed", sent, digits.length)
        } finally {
            // Non-suspending cleanup still executes in a cancelled coroutine and uses only captured Call.
            sequence.stopTone()
            active = null
            lock.unlock()
            onBusyChanged()
        }
    }

    private class Sequence(val stillOwned: () -> Boolean, val stop: () -> Unit) {
        var invalidated = false
        var toneStarted = false
        var stopFailed = false
        fun stopTone() {
            if (!toneStarted) return
            toneStarted = false
            if (runCatching(stop).isFailure) stopFailed = true
        }
    }

    companion object {
        const val MAX_DIGITS = 32
        const val TONE_MS = 150L
        const val GAP_MS = 100L
        fun validDigits(digits: String): Boolean = digits.length in 1..MAX_DIGITS &&
            digits.all { it in '0'..'9' || it == '*' || it == '#' }
    }
}
