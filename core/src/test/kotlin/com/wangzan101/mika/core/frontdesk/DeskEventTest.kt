package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeskEventTest {
    private fun running(narration: String?) = TaskState.Running(step = 1, note = "tap", narration = narration)

    private fun classify(previous: TaskState?, next: TaskState) = DeskEvents.classify("t1", previous, next)

    @Test
    fun idleToRunningIsStarted() {
        assertEquals(DeskEvent.Started("t1"), classify(TaskState.Idle, running("打开设置")))
    }

    @Test
    fun firstSeenRunningIsStarted() {
        assertEquals(DeskEvent.Started("t1"), classify(null, running("打开设置")))
    }

    @Test
    fun queuedToRunningIsStarted() {
        assertEquals(DeskEvent.Started("t1"), classify(TaskState.Held(GateReason.queued("前面还有 1 件")), running(null)))
    }

    @Test
    fun narrationChangeIsCleanedProgress() {
        val event = classify(running("打开设置"), running("点了「张三的生日会」 [120,340] 那一行"))
        assertEquals(DeskEvent.Progress("t1", "点了 那一行"), event)
    }

    @Test
    fun progressLineIsCappedAtLineLimit() {
        val event = classify(running("a"), running("字".repeat(DESK_LINE_MAX_CHARS + 10))) as DeskEvent.Progress
        assertEquals(DESK_LINE_MAX_CHARS, event.line.length)
    }

    @Test
    fun narrationUnchangedAfterCleaningIsNull() {
        assertNull(classify(running("点击 [1,2]"), running("点击 [3,4]")))
    }

    @Test
    fun narrationBlankAfterCleaningIsNull() {
        assertNull(classify(running("打开设置"), running("「只有引号」")))
        assertNull(classify(running("打开设置"), running(null)))
    }

    @Test
    fun otherRunningFieldsChangingIsNull() {
        assertNull(classify(running("打开设置"), TaskState.Running(step = 2, note = "swipe", narration = "打开设置")))
    }

    @Test
    fun waitingInputIsNeedsAnswer() {
        assertEquals(
            DeskEvent.NeedsAnswer("t1", "选哪家?", listOf("A", "B")),
            classify(running("找店"), TaskState.WaitingInput("选哪家?", listOf("A", "B"))),
        )
    }

    @Test
    fun waitingConfirmationIsConfirmAction() {
        val next = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, target = "张三", content = "到了", amount = null, app = "chat"))
        assertEquals(DeskEvent.NeedsAction("t1", ActionKind.Confirm), classify(running("发消息"), next))
    }

    @Test
    fun waitingTakeoverIsTakeoverAction() {
        assertEquals(
            DeskEvent.NeedsAction("t1", ActionKind.Takeover),
            classify(running("登录"), TaskState.WaitingTakeover("login", "请登录")),
        )
    }

    @Test
    fun heldNeedingYouIsPrerequisiteAction() {
        val held = TaskState.Held(GateReason("unlock", needsYou = true))
        assertEquals(DeskEvent.NeedsAction("t1", ActionKind.Prerequisite), classify(running("打开"), held))
    }

    @Test
    fun heldNotNeedingYouIsNull() {
        assertNull(classify(running("打开"), TaskState.Held(GateReason("network"))))
    }

    @Test
    fun terminalStatesMapToTerminalEvents() {
        assertEquals(DeskEvent.Succeeded("t1", "订好了"), classify(running("下单"), TaskState.Succeeded("订好了")))
        assertEquals(
            DeskEvent.Failed("t1", "没找到"),
            classify(running("找"), TaskState.Failed("没找到", FailKind.GaveUp)),
        )
        assertEquals(DeskEvent.Cancelled("t1"), classify(running("找"), TaskState.Cancelled))
    }

    @Test
    fun succeededAnswerKeepsTheWholeResult() {
        val event = classify(running("读"), TaskState.Succeeded("x".repeat(DESK_RESULT_MAX_CHARS + 5))) as DeskEvent.Succeeded
        assertEquals(DESK_RESULT_MAX_CHARS + 5, event.answer.length)
    }

    @Test
    fun unchangedStateIsNull() {
        val waiting = TaskState.WaitingInput("选哪家?", listOf("A", "B"))
        assertNull(classify(waiting, waiting))
        assertNull(classify(TaskState.Cancelled, TaskState.Cancelled))
        assertNull(classify(running("打开设置"), running("打开设置")))
    }

    @Test
    fun idleIsNull() {
        assertNull(classify(null, TaskState.Idle))
    }

    @Test
    fun stripDetailsRemovesCoordinatesAndQuotedSegments() {
        assertEquals("点 再点", stripDetails("点 [1, 2]（3，4） 『标题』 \"x\" 再点"))
    }
}
