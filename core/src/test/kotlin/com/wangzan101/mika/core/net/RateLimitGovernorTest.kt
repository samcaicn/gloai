package com.wangzan101.mika.core.net

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class RateLimitGovernorTest {
    @Before fun clear() = RateLimitGovernor.reset()
    @After fun restoreClock() = RateLimitGovernor.reset()

    @Test fun retryAfterZeroHasAOneSecondMinimum() {
        assertEquals(1_000L, RateLimitGovernor.reportRateLimited("m", 0L))
    }

    @Test fun retryAfterIsUsedAndCapped() {
        assertEquals(5_000L, RateLimitGovernor.reportRateLimited("m", 5_000L))
        assertEquals(30_000L, RateLimitGovernor.reportRateLimited("m", 90_000L))
    }

    @Test fun backoffIncrementsWithoutRetryAfter() {
        assertEquals(1_000L, RateLimitGovernor.reportRateLimited("m", null))
        assertEquals(2_000L, RateLimitGovernor.reportRateLimited("m", null))
        assertEquals(4_000L, RateLimitGovernor.reportRateLimited("m", null))
    }

    @Test fun retryAfterResetsBackoffChain() {
        RateLimitGovernor.reportRateLimited("m", null)
        RateLimitGovernor.reportRateLimited("m", null)
        RateLimitGovernor.reportRateLimited("m", 5_000L)
        assertEquals(1_000L, RateLimitGovernor.reportRateLimited("m", null))
    }

    @Test fun successResetsConsecutive429() {
        RateLimitGovernor.reportRateLimited("m", null)
        RateLimitGovernor.reportRateLimited("m", null)
        RateLimitGovernor.reportSuccess("m")
        assertEquals(1_000L, RateLimitGovernor.reportRateLimited("m", null))
    }

    @Test fun backoffCappedAtMax() {
        repeat(20) { RateLimitGovernor.reportRateLimited("m", null) }
        assertTrue(RateLimitGovernor.reportRateLimited("m", null) <= 60_000L)
    }

    @Test fun awaitClearWaitsAndConsumes() = runTest {
        RateLimitGovernor.reset { currentTime }
        RateLimitGovernor.reportRateLimited("m", 5_000L)
        val start = currentTime
        RateLimitGovernor.awaitClear("m")
        assertEquals(5_000L, currentTime - start)
        // 消费掉了：第二次不再等
        RateLimitGovernor.awaitClear("m")
        assertEquals(5_000L, currentTime - start)
    }

    @Test fun expiredCooldownDoesNotBlockLaterTasks() = runTest {
        RateLimitGovernor.reset { currentTime }
        RateLimitGovernor.reportRateLimited("m", 5_000L)
        advanceTimeBy(5_000L)
        val start = currentTime
        RateLimitGovernor.awaitClear("m")
        assertEquals(start, currentTime)
    }

    @Test fun differentKeysAreIndependent() {
        RateLimitGovernor.reportRateLimited("a", 30_000L)
        assertEquals(1_000L, RateLimitGovernor.reportRateLimited("b", null))
    }
}
