package com.wangzan101.mika.core.frontdesk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPolicyTest {
    private var now = 0L
    private val policy = ReportPolicy(clock = { now })

    @Test
    fun milestonesAlwaysReport() {
        val events = listOf(
            DeskEvent.Started("t1"),
            DeskEvent.Queued("t1", 1),
            DeskEvent.NeedsAnswer("t1", "选哪家?", listOf("A", "B")),
            DeskEvent.NeedsAction("t1", ActionKind.Confirm),
            DeskEvent.NeedsAction("t1", ActionKind.Takeover),
            DeskEvent.NeedsAction("t1", ActionKind.Prerequisite),
            DeskEvent.Succeeded("t1", "好了"),
            DeskEvent.Failed("t1", "没成"),
        )
        events.forEach { assertTrue(it.toString(), policy.shouldReport(it)) }
    }

    @Test
    fun cancelledIsNotReported() {
        assertFalse(policy.shouldReport(DeskEvent.Cancelled("t1")))
    }

    @Test
    fun progressIsQuietWithinWindowAfterLastReport() {
        assertTrue(policy.shouldReport(DeskEvent.Started("t1")))
        now = 89_999
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
        now = 90_000
        assertTrue(policy.shouldReport(DeskEvent.Progress("t1", "找到了")))
        now = 120_000
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "在比价")))
    }

    @Test
    fun firstProgressWithoutEarlierReportIsReported() {
        assertTrue(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
    }

    @Test
    fun quietWindowIsPerTask() {
        assertTrue(policy.shouldReport(DeskEvent.Started("t1")))
        now = 10_000
        assertTrue(policy.shouldReport(DeskEvent.Progress("t2", "在找")))
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
    }

    @Test
    fun userAskingLetsNextProgressThroughOnce() {
        assertTrue(policy.shouldReport(DeskEvent.Started("t1")))
        now = 5_000
        policy.noteUserAsked("t1")
        assertTrue(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
        now = 6_000
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "还在找")))
    }

    @Test
    fun userAskingOnlyAffectsThatTask() {
        assertTrue(policy.shouldReport(DeskEvent.Started("t1")))
        assertTrue(policy.shouldReport(DeskEvent.Started("t2")))
        policy.noteUserAsked("t1")
        assertFalse(policy.shouldReport(DeskEvent.Progress("t2", "在找")))
    }

    @Test
    fun customQuietWindowIsHonored() {
        val short = ReportPolicy(clock = { now }, progressQuietMs = 1_000)
        assertTrue(short.shouldReport(DeskEvent.Started("t1")))
        now = 1_000
        assertTrue(short.shouldReport(DeskEvent.Progress("t1", "在找")))
    }
}
