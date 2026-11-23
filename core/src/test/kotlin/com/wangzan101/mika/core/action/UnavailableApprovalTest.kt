package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class DeniedPaymentScreen(private val label: String) : ScreenPort by FakeScreen() {
    var taps = 0
    private val shown = ScreenSnapshot("s1", "com.example.shop", listOf(
        UiElement("@e1", "Button", label, 500, 500, listOf(100, 400, 900, 600), null, true, false, false),
    ), 1080, 2400)
    override suspend fun snapshot() = shown
    override suspend fun peek() = shown
    override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { taps++ }
    override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok(changed = true).also { taps++ }
}

class UnavailableApprovalTest {
    private val provenance = RunProvenance(RunOrigin.Mcp, Attendance.Unattended)
    private fun reply(id: String, name: String, args: String = "{}") =
        ModelReply(listOf(Part.ToolCall(id, name, args)), StopReason.ToolUse, null)

    @Test fun remoteDenialPreservesTargetAndReportsTheUnexecutedCall() = runTest {
        val screen = DeniedPaymentScreen("确认支付")
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("observe", "phone_observe", "{}"))
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {},
            recorder = ActionRecorder(sink), provenance = provenance)
        var reported: JsonObject? = null
        val result = executor.run(Part.ToolCall("tap", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""),
            Origin.Model(1), object : FollowUp { override fun refused(content: JsonObject) { reported = content } })
        assertTrue(result is ActionOutcome.Refused)
        val refusal = result as ActionOutcome.Refused
        assertEquals("approval_unavailable", refusal.code)
        assertEquals("确认支付", refusal.hit?.matched)
        assertEquals(refusal.content, reported)
        assertEquals("approval_unavailable", reported!!["error"]!!.jsonPrimitive.content)
        assertEquals("false", reported!!["executed"]!!.jsonPrimitive.content)
        assertEquals(0, screen.taps)
        assertEquals(1, events.filterIsInstance<RunEvent.Approval>().size)
        assertEquals(1, events.filterIsInstance<RunEvent.ToolResult>().size)
    }

    @Test fun remoteLoopCanExplainDenialWithoutCrashingOrExecutingIt() = runTest {
        // A combined accessibility label with an explicit total is still a paid action.
        // Preserve that decision here: this regression only repairs its delivery.
        for (label in listOf("确认支付", "Study notes\n合计 2.99元\n删除\n编辑")) {
            val screen = DeniedPaymentScreen(label)
            val message = "The action was not run; the phone owner must handle it."
            val model = FakeModel(
                reply("observe", "phone_observe"),
                reply("tap", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""),
                reply("stop", "end_not_done", """{"message":"$message"}"""),
            )
            val loop = AgentLoop(model, toolDispatcher(screen), "BASE", confirmations = ConfirmationGate(),
                reviewFinalAnswer = false)
            val state = loop.run(RunSpec(RunId("remote-denial"), "Inspect the target", "test", provenance = provenance), RunInbox())
            assertEquals(TaskState.Failed(message, FailKind.GaveUp), state)
            assertEquals(0, screen.taps)
            val returned = model.requests[2].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
            val content = Json.parseToJsonElement(returned.content).jsonObject
            assertEquals("approval_unavailable", content["error"]!!.jsonPrimitive.content)
            assertEquals("false", content["executed"]!!.jsonPrimitive.content)
            assertTrue(returned.isError)
        }
    }
}
