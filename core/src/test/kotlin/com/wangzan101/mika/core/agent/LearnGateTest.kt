package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.user.UserScreenMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnGateTest {
    private val unreviewed = TaskState.Succeeded("ok", verified = false, reviewed = false)

    @Test fun doneWithoutReviewCounts() {
        assertTrue(LearnGate.done(unreviewed, RunOutcome.Done))
        assertTrue(LearnGate.done(TaskState.Succeeded("ok", verified = true, reviewed = true), RunOutcome.Done))
    }

    @Test fun reviewedButNotVerifiedIsBlocked() {
        assertFalse(LearnGate.done(TaskState.Succeeded("ok", verified = false, reviewed = true, note = "x"), RunOutcome.Done))
        assertFalse(LearnGate.done(TaskState.Succeeded("ok", verified = false, unconfirmed = listOf("3 元"), reviewed = false), RunOutcome.Done))
    }

    @Test fun onlyDoneCounts() {
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) assertFalse(o.wire, LearnGate.done(unreviewed, o))
        assertFalse(LearnGate.done(TaskState.Failed("x", FailKind.GaveUp), RunOutcome.Done))
        assertFalse(LearnGate.done(TaskState.Cancelled, RunOutcome.Done))
    }

    @Test fun routeIsSavedOnlyForDoneRunsWithRoutesOn() {
        assertTrue(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Done, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Partial, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Unknown, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, TaskState.Succeeded("ok", verified = false, reviewed = true, note = "x"), RunOutcome.Done, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(false, unreviewed, RunOutcome.Done, "r1", "打开设置"))
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Done, null, "打开设置"))
        // 带着用户屏幕的话:意思在屏幕上,不按字记路线
        assertFalse(LearnGate.shouldSaveRoute(true, unreviewed, RunOutcome.Done, "r1", UserScreenMessage.compose("s1", "这个怎么关")))
    }
}
