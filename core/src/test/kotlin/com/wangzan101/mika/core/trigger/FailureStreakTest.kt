package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.trigger.TriggerWindowState.ActionDispatched
import com.wangzan101.mika.core.trigger.TriggerWindowState.Delivered
import com.wangzan101.mika.core.trigger.TriggerWindowState.Failed
import com.wangzan101.mika.core.trigger.TriggerWindowState.Skipped
import com.wangzan101.mika.core.trigger.TriggerWindowState.Unknown
import org.junit.Assert.assertEquals
import org.junit.Test

/** 定时任务连着失败了几次:按这个计划自己的记录往前数,送达过或结果不明就停,跳过的不算。 */
class FailureStreakTest {
    private fun w(due: Long, state: TriggerWindowState, plan: String = "plan") = TriggerWindow(plan, due, due, state = state)

    @Test fun countsFailuresRightBeforeThisOne() {
        val now = w(50, ActionDispatched)
        val windows = listOf(w(10, Delivered), w(20, Failed), w(30, Skipped), w(40, Failed), now)
        // 40、20 失败,中间 30 是条件没满足跳过,不打断;10 送达过,停
        assertEquals(2, failureStreakBefore(windows, now.key))
    }

    @Test fun successOrUnknownResultEndsTheStreak() {
        val now = w(40, ActionDispatched)
        assertEquals(0, failureStreakBefore(listOf(w(10, Failed), w(30, Delivered), now), now.key))
        assertEquals(1, failureStreakBefore(listOf(w(10, Failed), w(20, Unknown), w(30, Failed), now), now.key))
    }

    @Test fun onlyThisPlanAndOnlyEarlierRuns() {
        val now = w(30, ActionDispatched)
        val windows = listOf(w(10, Failed), w(20, Failed, plan = "other"), now, w(40, Failed))
        assertEquals(1, failureStreakBefore(windows, now.key))
        assertEquals(0, failureStreakBefore(windows, "missing:1"))
    }
}
