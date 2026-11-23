package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.general.NOTICES_READ
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryRecorder
import com.wangzan101.mika.core.run.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeRunsTest {
    private val private = "妈妈说她周五在医院复查"

    private fun act(step: Int, kind: String, label: String, target: String? = null) =
        RunEvent.Activity(step * 100L, step, kind, label, target = target)

    private fun recorded(events: List<RunEvent>): TaskMemory =
        TaskMemoryRecorder.record("今天有什么要紧的消息", events, now = 9_000L, id = "r1")!!

    private fun everyWord(m: TaskMemory) = listOf(m.goal, m.conclusion) + m.steps.flatMap { listOf(it.label, it.detail) }

    @Test fun aRunThatCalledTheNoticeToolKeepsOnlyTheOutcomeWord() {
        val events: List<RunEvent> = listOf(
            act(1, "observe", NOTICES_READ), act(2, "observe", NOTICES_READ),
            RunEvent.TaskEnded(500L, "succeeded", "有一条要紧的:$private"),
        )
        val before = recorded(events)
        assertTrue("没处理之前回答在摘要里", before.conclusion.contains(private))
        val m = NoticeRuns.summary(before, events, noticeTurnInContext = false)
        assertEquals("完成", m.conclusion)
        assertFalse(everyWord(m).any { it.contains(private) })
        // 别的照留:目标是用户自己的话,步骤只有工具的名字
        assertEquals(before.goal, m.goal)
        assertEquals(before.steps, m.steps)
        assertEquals(before.id, m.id)
    }

    @Test fun theJudgedReasonGoesTooNotJustTheAnswer() {
        val events: List<RunEvent> = listOf(
            act(1, "observe", NOTICES_READ), act(2, "act", "tap", target = "com.example.chat"),
            RunEvent.TaskEnded(400L, "succeeded", "已回复:$private"),
            RunEvent.OutcomeJudged(500L, "done", "回答提到了$private"),
        )
        val m = NoticeRuns.summary(recorded(events), events, noticeTurnInContext = false)
        assertEquals("办成", m.conclusion)
        assertFalse(everyWord(m).any { it.contains(private) })
    }

    @Test fun aRunInAReminderConversationCountsAsHavingReadThem() {
        // 没调读通知的工具,但提醒那一轮(通知原文)就在上文里
        val events: List<RunEvent> = listOf(
            act(1, "act", "launch_app", target = "com.example.chat"), act(2, "act", "tap", target = "com.example.chat"),
            RunEvent.TaskEnded(500L, "failed", "没找到「$private」那条"),
        )
        assertTrue(NoticeRuns.readNotices(events, noticeTurnInContext = true))
        assertEquals("失败", NoticeRuns.summary(recorded(events), events, noticeTurnInContext = true).conclusion)
    }

    @Test fun carryingTheDigestBlockAloneDoesNotCount() {
        // 开工时带着「来自通知」的那一段归纳、但没读通知原文:照常记。不然非保守档下几乎每个任务的摘要都没了回答
        val events: List<RunEvent> = listOf(
            RunEvent.State(10L, ObservedContext.RUN_STATE, "420"),
            act(1, "act", "launch_app", target = "com.example.shop"), act(2, "act", "tap", target = "com.example.shop"),
            RunEvent.TaskEnded(500L, "succeeded", "已下单一杯美式"),
        )
        assertFalse(NoticeRuns.readNotices(events, noticeTurnInContext = false))
        val before = recorded(events)
        assertEquals("完成：已下单一杯美式", before.conclusion)
        assertEquals(before, NoticeRuns.summary(before, events, noticeTurnInContext = false))
    }

    @Test fun anOrdinaryRunIsLeftAsItWas() {
        val events: List<RunEvent> = listOf(
            act(1, "observe", "list_elements", target = "com.example.shop"), act(2, "act", "tap", target = "com.example.shop"),
            RunEvent.OutcomeJudged(500L, "partial", "只加了购物车"),
        )
        val before = recorded(events)
        assertEquals("只办成一部分：只加了购物车", before.conclusion)
        assertEquals(before, NoticeRuns.summary(before, events, noticeTurnInContext = false))
    }

    @Test fun outcomeAloneCoversEveryEndingAndAnEmptyOne() {
        fun ended(outcome: String) = TaskMemoryRecorder.outcome(listOf(RunEvent.TaskEnded(1L, outcome, private)))
        assertEquals(listOf("完成", "完成(未核实)", "失败", "已取消", "出错", ""),
            listOf("succeeded", "unverified", "failed", "cancelled", "error", "something_else").map(::ended))
        assertEquals("", TaskMemoryRecorder.outcome(emptyList()))
        assertEquals("没办成", TaskMemoryRecorder.outcome(listOf(RunEvent.OutcomeJudged(1L, "not_possible", private))))
    }
}
