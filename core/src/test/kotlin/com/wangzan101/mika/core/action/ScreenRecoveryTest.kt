package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ScreenRecoveryRequired
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁屏周期后准备好但还没执行的动作不再执行:模型收到一条说明结果,先重读屏再决定下一步。 */
class ScreenRecoveryTest {
    @Test fun lockedMidRunDoesNotExecutePendingTap() = runTest {
        var taps = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                taps++
                return ActionResult.Ok(changed = true)
            }
        }
        val tools = toolDispatcher(screen)
        val executor = ActionExecutor(tools, OperationGuards(), null,
            gate = { throw ScreenRecoveryRequired() }, recorder = ActionRecorder(RunSink.NONE), autonomous = true)
        val result = executor.run(Part.ToolCall("tap", "phone_tap", """{"x":10,"y":20}"""), Origin.Model(1))
        assertTrue(result is ActionOutcome.Ran)
        val content = (result as ActionOutcome.Ran).content
        assertEquals(JsonPrimitive(false), content["executed"])
        assertEquals(JsonPrimitive("screen_changed_during_lock"), content["error"])
        assertEquals(0, taps)
    }
}
