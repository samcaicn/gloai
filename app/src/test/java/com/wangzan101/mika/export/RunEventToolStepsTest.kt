package com.wangzan101.mika.export

import com.wangzan101.mika.core.run.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEventToolStepsTest {
    @Test fun finalAnswerIsNotAToolStep() {
        val events = listOf(
            RunEvent.ModelReply(1, 1, "look", "phone_read", "{}", 10, 2, 0, model = "gpt"),
            RunEvent.ToolResult(2, 1, "phone_read", "ok", true, null, 12, callId = "c1"),
            RunEvent.ModelReply(3, 2, "final answer", null, null, 10, 2, 0),
        )
        val steps = RunEventToolSteps.of(events)
        assertEquals(1, steps.size)
        assertEquals(1, steps.single().step)
        assertEquals("phone_read", steps.single().tool)
        assertEquals("ok", steps.single().result)
    }

    @Test fun resultWithoutModelReplyStillBecomesAStep() {
        val steps = RunEventToolSteps.of(listOf(RunEvent.ToolResult(1, 3, "web_read", "page", null, null, 5)))
        assertEquals(1, steps.size)
        assertEquals(3, steps.single().step)
        assertEquals("web_read", steps.single().tool)
        assertTrue(steps.single().argsJson == null)
    }
}
