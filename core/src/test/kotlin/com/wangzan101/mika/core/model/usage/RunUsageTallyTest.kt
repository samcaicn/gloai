package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RunUsageTallyTest {
    @Test fun inputPlusOutputReachesTheBudgetWithoutCountingCachedInputTwice() {
        val tally = RunUsageTally(tokenBudget = 5)
        tally.add(Usage(inputTokens = 3, outputTokens = 2, cachedInputTokens = 2), httpAttempts = 1)
        assertEquals(5L, tally.totalTokens())
        try {
            tally.checkBudget()
            fail("达到上限后必须拒绝下一次请求")
        } catch (e: ModelError.TaskBudgetExceeded) {
            assertEquals(5L, e.limit)
            assertEquals(5L, e.used)
        }
    }

    @Test fun unreportedUsageDoesNotAdvanceTheTokenBudget() {
        val tally = RunUsageTally(tokenBudget = 5)
        tally.add(Usage(2, 1), httpAttempts = 1)
        tally.checkBudget()
        tally.add(null, httpAttempts = 1)
        tally.add(null, httpAttempts = 0)
        assertEquals(3L, tally.totalTokens())
        tally.checkBudget()
        val event = tally.event(1)
        assertEquals(3, event.calls)
        assertEquals(2, event.unreported)
        assertEquals(2L, event.inputTokens)
        assertEquals(1L, event.outputTokens)
    }

    @Test fun noBudgetNeverRejects() {
        val tally = RunUsageTally()
        tally.add(Usage(Int.MAX_VALUE, Int.MAX_VALUE), httpAttempts = 1)
        tally.checkBudget()
    }
}
