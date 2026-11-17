package com.wangzan101.mika.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDtmfExecutorTest {
    @Test fun `each Telecom pulse stops before next with bounded duration and gap`() = runTest {
        val events = mutableListOf<Pair<Long, String>>()
        val busyStates = mutableListOf<Boolean>()
        lateinit var executor: CallDtmfExecutor
        executor = CallDtmfExecutor { busyStates += executor.busy }
        val result = executor.send("1*#", { true },
            { events += currentTime to "play:$it" }, { events += currentTime to "stop" })

        assertEquals(CallDtmfResult("submitted", 3, 3), result)
        assertEquals(listOf(0L to "play:1", 150L to "stop", 250L to "play:*", 400L to "stop",
            500L to "play:#", 650L to "stop"), events)
        assertEquals(listOf(true, false), busyStates)
        assertFalse(executor.busy)
    }

    @Test fun `empty whitespace unicode unsupported and oversized keys never reach Telecom`() = runTest {
        val executor = CallDtmfExecutor()
        for (invalid in listOf("", " ", "1 2", "1,2", "1;2", "A", "１２", "1\n", "1".repeat(33))) {
            val result = executor.send(invalid, { true }, { error("must not play") }, { error("must not stop") })
            assertEquals("invalid_digits", result.status)
            assertEquals(0, result.sentDigits)
            assertFalse(executor.busy)
        }
        assertTrue(CallDtmfExecutor.validDigits("0123456789*#"))
        assertTrue(CallDtmfExecutor.validDigits("1".repeat(32)))
    }

    @Test fun `already lost ownership does not submit or consume AI budget`() = runTest {
        val executor = CallDtmfExecutor()
        val budget = CallDtmfBudget(maxRequests = 1, maxDigits = 1)
        assertEquals("unavailable", executor.send("1", { false }, { error("must not play") }, {}, budget).status)
        assertEquals("submitted", executor.send("1", { true }, {}, {}, budget).status)
        assertEquals("budget_exceeded", executor.send("1", { true }, { error("must not play") }, {}, budget).status)
    }

    @Test fun `call replacement stops captured tone immediately and never retargets even after return`() = runTest {
        val first = Any()
        val second = Any()
        var current = first
        val events = mutableListOf<Pair<Long, String>>()
        val executor = CallDtmfExecutor()
        val sending = async {
            executor.send("12", { current === first },
                { events += currentTime to "first:play:$it" }, { events += currentTime to "first:stop" })
        }
        runCurrent()
        advanceTimeBy(40)
        current = second
        executor.invalidate()
        assertEquals(listOf(0L to "first:play:1", 40L to "first:stop"), events)
        current = first // A transient replacement must still terminate the original sequence.
        assertEquals(CallDtmfResult("partial", 1, 2), sending.await())
        assertEquals(2, events.size)
    }

    @Test fun `AI work ownership loss stops automatic keys without requiring call disconnect`() = runTest {
        val originalOwner = Any()
        var owner: Any? = originalOwner
        var stops = 0
        var plays = 0
        val executor = CallDtmfExecutor()
        val sending = async { executor.send("123", { owner === originalOwner }, { plays++ }, { stops++ }) }
        runCurrent()
        advanceTimeBy(25)
        owner = null // Represents takeover/cancellation with the same carrier Call still ACTIVE.
        executor.invalidate()
        assertEquals(1, stops)
        assertEquals(CallDtmfResult("partial", 1, 3), sending.await())
        assertEquals(1, plays)
        // A separately bound manual request can use that carrier call after AI handoff.
        assertEquals("submitted", executor.send("#", { true }, { plays++ }, { stops++ }).status)
        assertEquals(2, plays)
    }

    @Test fun `ownership is checked again after gap even without a callback`() = runTest {
        var active = true
        var plays = 0
        var stops = 0
        val executor = CallDtmfExecutor()
        val sending = async { executor.send("12", { active }, { plays++ }, { stops++ }) }
        runCurrent()
        advanceTimeBy(175)
        active = false
        assertEquals(CallDtmfResult("partial", 1, 2), sending.await())
        assertEquals(1, plays)
        assertEquals(1, stops)
    }

    @Test fun `explicit hangup intent stops keys immediately without revoking later manual operation`() = runTest {
        var plays = 0
        val stopTimes = mutableListOf<Long>()
        val executor = CallDtmfExecutor()
        val sending = async { executor.send("12", { true }, { plays++ }, { stopTimes += currentTime }) }
        runCurrent()
        advanceTimeBy(20)
        executor.cancelPending()
        assertEquals(listOf(20L), stopTimes)
        assertEquals(CallDtmfResult("partial", 1, 2), sending.await())
        assertEquals(1, plays)
        // If Android could not hang up, an explicit later request may use the still-active call.
        assertEquals("submitted", executor.send("#", { true }, { plays++ }, {}).status)
        assertEquals(2, plays)
    }

    @Test fun `cancellation during tone always stops captured call and releases collision guard`() = runTest {
        var plays = 0
        var stops = 0
        val executor = CallDtmfExecutor()
        val sending = launch { executor.send("12", { true }, { plays++ }, { stops++ }) }
        runCurrent()
        assertTrue(executor.busy)
        sending.cancelAndJoin()
        assertEquals(1, plays)
        assertEquals(1, stops)
        assertFalse(executor.busy)
        assertEquals("submitted", executor.send("#", { true }, {}, {}).status)
    }

    @Test fun `overlapping manual or AI sequence is rejected immediately without queue or pulse`() = runTest {
        val executor = CallDtmfExecutor()
        var plays = 0
        val sending = async { executor.send("12", { true }, { plays++ }, {}) }
        runCurrent()
        assertEquals(CallDtmfResult("busy", 0, 1),
            executor.send("#", { true }, { error("must not play overlapping keys") }, {}))
        assertEquals(0L, currentTime)
        assertEquals(CallDtmfResult("submitted", 2, 2), sending.await())
        assertEquals(2, plays)
    }

    @Test fun `Telecom play exception still stops exact captured tone and never retries`() = runTest {
        var attempts = 0
        var stops = 0
        val result = CallDtmfExecutor().send("12", { true }, { attempts++; error("binder failed") }, { stops++ })
        assertEquals(CallDtmfResult("failed", 0, 2), result)
        assertEquals(1, attempts)
        assertEquals(1, stops)
    }

    @Test fun `Telecom stop failure prevents next pulse and reports partial local submission`() = runTest {
        var plays = 0
        var stops = 0
        val result = CallDtmfExecutor().send("12", { true }, { plays++ }, { stops++; error("stop failed") })
        assertEquals(CallDtmfResult("partial", 1, 2), result)
        assertEquals(1, plays)
        assertEquals(1, stops)
    }

    @Test fun `per call AI budget bounds characters and cancellation does not reset reserved allowance`() = runTest {
        val executor = CallDtmfExecutor()
        val budget = CallDtmfBudget(maxRequests = 3, maxDigits = 4)
        val sending = launch { executor.send("123", { true }, {}, {}, budget) }
        runCurrent()
        sending.cancelAndJoin()
        assertEquals("budget_exceeded", executor.send("12", { true }, { error("must not play") }, {}, budget).status)
        assertEquals("submitted", executor.send("#", { true }, {}, {}, budget).status)
        assertEquals("budget_exceeded", executor.send("1", { true }, { error("must not play") }, {}, budget).status)
    }

    @Test fun `callback predicate failure safely invalidates the current tone`() = runTest {
        var destroyed = false
        var stops = 0
        val executor = CallDtmfExecutor()
        val sending = async {
            executor.send("12", { if (destroyed) error("service lost") else true }, {}, { stops++ })
        }
        runCurrent()
        destroyed = true
        executor.invalidate()
        assertEquals(1, stops)
        assertEquals(CallDtmfResult("partial", 1, 2), sending.await())
    }
}
