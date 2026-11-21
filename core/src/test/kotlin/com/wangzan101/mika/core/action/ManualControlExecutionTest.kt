package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.ManualControl
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlExecutionTest {
    @Test fun longScriptCanBeTakenOverBetweenStepsAndOldScriptStops() = runTest {
        val firstStep = kotlinx.coroutines.CompletableDeferred<Unit>()
        val nextStep = kotlinx.coroutines.CompletableDeferred<Unit>()
        var continued = false
        val web = com.wangzan101.mika.core.tool.ScriptWebPort { bridge ->
            bridge.call("snapshot", kotlinx.serialization.json.JsonObject(emptyMap()))
            firstStep.complete(Unit)
            nextStep.await()
            bridge.call("snapshot", kotlinx.serialization.json.JsonObject(emptyMap()))
            continued = true
            com.wangzan101.mika.core.cap.web.ScriptRun.Done(null)
        }
        val control = ManualControl()
        val tools = toolDispatcher(FakeScreen(), web = web).also { it.manualControl = control }
        tools.execute(Part.ToolCall("nav", "web_navigate", """{"url":"https://example.com"}"""))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {},
            recorder = ActionRecorder(com.wangzan101.mika.core.record.RunSink.NONE), autonomous = true)
        val script = async {
            executor.run(Part.ToolCall("script", com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL, """{"code":"wait"}"""), Origin.Model(1))
        }
        firstStep.await()
        assertTrue(control.begin(timeoutMs = 100))
        control.end()
        nextStep.complete(Unit)
        val result = script.await() as ActionOutcome.Ran
        assertEquals(kotlinx.serialization.json.JsonPrimitive(ManualControl.CHANGED), result.exec.content["error"])
        org.junit.Assert.assertFalse(continued)
        assertTrue(executor.run(Part.ToolCall("fresh", "web_observe", "{}"), Origin.Model(2)) is ActionOutcome.Ran)
    }

    @Test fun everyActionOriginNeedsAFreshScreenAfterManualControl() = runTest {
        for (origin in listOf(Origin.Model(1), Origin.Batch(1), Origin.Script(1), Origin.Replay)) {
            var taps = 0
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun tapXY(x: Int, y: Int): ActionResult {
                    taps++
                    return ActionResult.Ok(changed = true)
                }
            }
            val control = ManualControl()
            val tools = toolDispatcher(screen).also { it.manualControl = control }
            val recorder = ActionRecorder(object : com.wangzan101.mika.core.record.RunSink {
                override fun event(e: com.wangzan101.mika.core.record.RunEvent) = Unit
            })
            val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = recorder, autonomous = true)
            val tap = Part.ToolCall("tap", "phone_tap", """{"x":50,"y":50}""")
            assertTrue(control.begin())
            control.end()
            val refused = executor.run(tap, origin)
            assertTrue("$origin: $refused", refused is ActionOutcome.Refused)
            assertEquals(ManualControl.CHANGED, (refused as ActionOutcome.Refused).code)
            assertEquals(0, taps)
            tools.execute(Part.ToolCall("read", "phone_observe", "{}"))
            assertTrue(executor.run(tap, origin) is ActionOutcome.Ran)
            assertEquals(1, taps)
        }
    }
}
