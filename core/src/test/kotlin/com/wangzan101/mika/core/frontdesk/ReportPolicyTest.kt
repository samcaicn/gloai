package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
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
    fun customQuietWindowIsHonored() {
        val short = ReportPolicy(clock = { now }, progressQuietMs = 1_000)
        assertTrue(short.shouldReport(DeskEvent.Started("t1")))
        now = 1_000
        assertTrue(short.shouldReport(DeskEvent.Progress("t1", "在找")))
    }

    /** 没人在看时:开始、排队、进度不发通知;收尾和前置条件才发。 */
    @Test
    fun onlyEndingsAndPrerequisitesNotify() {
        // 回答、确认、接管任务自己会发带按钮的「需要你处理」通知,前台兜底只写回会话,不再发第二条;
        // 前置条件任务不另发提醒,由前台发这一条。
        val quiet = listOf(
            DeskEvent.Started("t1"), DeskEvent.Queued("t1", 2), DeskEvent.Progress("t1", "打开设置"),
            DeskEvent.NeedsAnswer("t1", "选哪家?", null),
            DeskEvent.NeedsAction("t1", ActionKind.Confirm),
            DeskEvent.NeedsAction("t1", ActionKind.Takeover),
        )
        val loud = listOf(
            DeskEvent.NeedsAction("t1", ActionKind.Prerequisite),
            DeskEvent.Succeeded("t1", "好了"),
            DeskEvent.Failed("t1", "没成"),
            DeskEvent.Cancelled("t1"),
        )
        quiet.forEach { assertFalse(it.toString(), ReportPolicy.notifies(it)) }
        loud.forEach { assertTrue(it.toString(), ReportPolicy.notifies(it)) }
    }

    /** [ReportPolicy.saysEnding] 和 [ReportPolicy.shouldReport] 对每种结局的判断一致。 */
    @Test
    fun saysEndingMatchesWhatTheDeskReports() {
        val ends = listOf(TaskState.Succeeded("好了"), TaskState.Failed("没成", FailKind.Environment), TaskState.Cancelled)
        for (end in ends) {
            val event = DeskEvents.classify("t1", running(), end)!!
            assertEquals(end.toString(), ReportPolicy(clock = { now }).shouldReport(event), ReportPolicy.saysEnding(end))
        }
        assertFalse(ReportPolicy.saysEnding(TaskState.Cancelled))
    }

    private fun running() = TaskState.Running(1, "打开设置")
}
