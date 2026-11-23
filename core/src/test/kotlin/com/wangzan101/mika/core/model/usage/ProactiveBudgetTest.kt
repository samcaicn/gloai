package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProactiveBudgetTest {
    @Test fun defaultIsAValidBudget() {
        assertEquals(200_000L, ProactiveBudget.DEFAULT_DAILY_LIMIT)
        assertTrue(TokenBudgetLimits.valid(ProactiveBudget.DEFAULT_DAILY_LIMIT))
    }

    @Test fun missingOrInvalidStoredLimitFallsBackToDefaultNeverUnlimited() {
        for (bad in listOf(null, 0L, -1L, TokenBudgetLimits.MIN - 1, TokenBudgetLimits.MAX + 1)) {
            assertEquals("$bad", ProactiveBudget.DEFAULT_DAILY_LIMIT, ProactiveBudget.limitOf(bad))
        }
        assertEquals(TokenBudgetLimits.MIN, ProactiveBudget.limitOf(TokenBudgetLimits.MIN))
        assertEquals(TokenBudgetLimits.MAX, ProactiveBudget.limitOf(TokenBudgetLimits.MAX))
    }

    @Test fun stopsOnceUsageReachesTheLimit() {
        assertTrue(ProactiveBudget.allowed(0, 20_000))
        assertTrue(ProactiveBudget.allowed(19_999, 20_000))
        assertFalse(ProactiveBudget.allowed(20_000, 20_000))
        assertFalse(ProactiveBudget.allowed(25_000, 20_000))
        ProactiveBudget.check(19_999, 20_000)
        try {
            ProactiveBudget.check(20_001, 20_000)
            fail("用到上限应拒绝")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertEquals(20_000L, e.limit)
            assertEquals(20_001L, e.used)
            assertFalse(e.byCalls)
        }
    }

    /** 服务商不报用量时 Token 一直是 0:请求次数到了上限也要停。上限跟着 Token 上限算(2 万 Token 时是下限 200 次)。 */
    @Test fun stopsOnceTheDailyCallCountIsReachedEvenWithNoReportedTokens() {
        assertEquals(200L, ProactiveBudget.callLimit(20_000))
        ProactiveBudget.check(0, 20_000, callsToday = 199)
        try {
            ProactiveBudget.check(0, 20_000, callsToday = 200)
            fail("请求次数到上限应拒绝")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertTrue(e.byCalls)
            assertEquals(200L, e.limit)
            assertEquals(200L, e.used)
        }
    }

    @Test fun callLimitFollowsTheTokenLimitWithAFloor() {
        assertEquals("下限", 200L, ProactiveBudget.callLimit(TokenBudgetLimits.MIN))
        assertEquals("下限", 200L, ProactiveBudget.callLimit(50_000))
        assertEquals(200L, ProactiveBudget.callLimit(50_249))
        assertEquals("超过下限后按比例", 201L, ProactiveBudget.callLimit(50_250))
        assertEquals("默认 20 万", 800L, ProactiveBudget.callLimit(ProactiveBudget.DEFAULT_DAILY_LIMIT))
        assertEquals(8_000L, ProactiveBudget.callLimit(2_000_000))
        assertEquals(400_000L, ProactiveBudget.callLimit(TokenBudgetLimits.MAX))
        // 每次约 500 Token 时先到的是 Token 上限
        assertTrue(ProactiveBudget.callLimit(ProactiveBudget.DEFAULT_DAILY_LIMIT) * 500 > ProactiveBudget.DEFAULT_DAILY_LIMIT)
    }

    @Test fun checkUsesTheDerivedCallLimit() {
        ProactiveBudget.check(0, 200_000, callsToday = 799)
        try {
            ProactiveBudget.check(0, 200_000, callsToday = 800)
            fail("20 万 Token 时请求上限是 800 次")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertTrue(e.byCalls)
            assertEquals(800L, e.limit)
        }
    }

    @Test fun triageStopsAtItsShareOfEachLimitOthersUseTheWhole() {
        assertEquals(80L, ProactiveBudget.TRIAGE_SHARE_PERCENT)
        assertEquals(160_000L, ProactiveBudget.triageShare(200_000))
        assertEquals(640L, ProactiveBudget.triageShare(800))
        val limit = 200_000L
        assertEquals(null, ProactiveBudget.triageExhausted(159_999, 639, limit))
        assertEquals(ProactiveBudget.Limit.Tokens, ProactiveBudget.triageExhausted(160_000, 0, limit))
        assertEquals(ProactiveBudget.Limit.Calls, ProactiveBudget.triageExhausted(0, 640, limit))
        // 整份还有
        assertEquals(null, ProactiveBudget.exhausted(160_000, 640, limit))
        ProactiveBudget.check(160_000, limit, 640)
        assertEquals(ProactiveBudget.Limit.Tokens, ProactiveBudget.exhausted(200_000, 0, limit))
        assertEquals(ProactiveBudget.Limit.Calls, ProactiveBudget.exhausted(0, 800, limit))
    }
}
