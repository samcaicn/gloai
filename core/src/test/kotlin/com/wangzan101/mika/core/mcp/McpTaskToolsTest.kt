package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteStatus
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpTaskToolsTest {
    private val fake = FakeRemoteTasks()
    private val tools = McpTaskTools(fake)

    private fun args(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                null -> put(k, kotlinx.serialization.json.JsonNull)
            }
        }
    }

    private suspend fun call(name: String, a: JsonObject) = tools.call(name, a)

    private suspend fun rejects(name: String, a: JsonObject, contains: String) {
        try {
            call(name, a)
            fail("expected an error for $a")
        } catch (e: McpTaskTools.ToolError) {
            assertTrue("${e.message} should mention $contains", e.message!!.contains(contains))
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun toolListHasTheSevenHighLevelTools() {
        val names = tools.definitions().map { it.jsonObject.str("name") }
        assertEquals(listOf("mika_run_task", "mika_task_status", "mika_wait_task", "mika_answer", "mika_steer_task", "mika_cancel_task", "mika_list_tasks"), names)
        tools.definitions().forEach { t ->
            val schema = t.jsonObject["inputSchema"]!!.jsonObject
            assertEquals("object", schema.str("type"))
            assertTrue(schema["required"] is JsonArray)
        }
    }

    @Test fun runTaskValidatesArguments() = runTest {
        rejects(McpTaskTools.RUN, args("goal" to "hi", "request_id" to null), "request_id")
        rejects(McpTaskTools.RUN, args("goal" to "hi", "request_id" to "not-a-uuid"), "request_id")
        rejects(McpTaskTools.RUN, args(), "goal")
        rejects(McpTaskTools.RUN, args("goal" to "   "), "goal")
        rejects(McpTaskTools.RUN, args("goal" to 5), "goal must be a string")
        rejects(McpTaskTools.RUN, args("goal" to "x".repeat(McpTaskTools.MAX_GOAL + 1)), "too long")
        // 只能接着电脑自己开的对话,手机上的对话编号不收
        rejects(McpTaskTools.RUN, args("goal" to "hi", "conversation" to "3f2c1d7e-0000-4000-8000-000000000000"), "conversation")
        rejects(McpTaskTools.RUN, args("goal" to "hi", "conversation" to "mcp-../../x"), "earlier MCP task")
        assertTrue(fake.tasks.isEmpty())
    }

    @Test fun runTaskStartsAndReturnsIdRightAway() = runTest {
        val r = call(McpTaskTools.RUN, args("goal" to "open Settings and tell me the Android version"))
        assertEquals("t1", r.str("task_id"))
        assertEquals(RemoteStatus.STARTING, r.str("status"))
        assertTrue(McpTaskTools.isConversation(r.str("conversation")!!))
        val again = call(McpTaskTools.RUN, args("goal" to "and the build number", "conversation" to r.str("conversation")))
        assertEquals(r.str("conversation"), again.str("conversation"))
    }

    @Test fun refusedStartIsAToolError() = runTest {
        fake.refuse = "Computer access is off"
        rejects(McpTaskTools.RUN, args("goal" to "x"), "Computer access is off")
    }

    @Test fun statusNeedsAKnownTaskId() = runTest {
        rejects(McpTaskTools.STATUS, args(), "task_id")
        rejects(McpTaskTools.STATUS, args("task_id" to "nope"), "No MCP task")
    }

    @Test fun stateMapping() {
        fun status(s: TaskState?) = RemoteStatus.describe(RemoteTask("t", "mcp-x", "g", 0, s))
        assertEquals("starting", status(null).str("status"))
        status(TaskState.Running(3, "phone_tap", narration = "Opening About phone")).let {
            assertEquals("running", it.str("status")); assertEquals("Opening About phone", it.str("progress")); assertEquals("3", it.str("step"))
        }
        assertEquals("paused", status(TaskState.Held(GateReason("waiting_unlock"))).str("status"))
        // 排在开始队列里的不是暂停:报 queued,点继续也不会开始
        status(TaskState.Held(GateReason.queued("Queued: 1 task ahead"))).let {
            assertEquals("queued", it.str("status")); assertEquals("queued", it.str("waiting_for")); assertEquals("Queued: 1 task ahead", it.str("progress"))
        }
        assertEquals("waiting_on_phone", status(TaskState.Held(GateReason("waiting_user", "Pick the app", needsYou = true))).str("status"))
        status(TaskState.WaitingInput("Which account?", listOf("Work", "Home"))).let {
            assertEquals("waiting_for_answer", it.str("status"))
            assertEquals("Which account?", it.str("question"))
            assertEquals(2, it["options"]!!.jsonArray.size)
            assertTrue(it["answerable"]!!.jsonPrimitive.boolean)
        }
        status(TaskState.Succeeded("Android 15", verified = true)).let {
            assertEquals("done", it.str("status")); assertEquals("Android 15", it.str("answer")); assertEquals("true", it.str("verified"))
        }
        status(TaskState.Failed("No network", FailKind.Environment)).let {
            assertEquals("failed", it.str("status")); assertEquals("No network", it.str("reason")); assertEquals("environment", it.str("failure_kind"))
        }
        assertEquals("cancelled", status(TaskState.Cancelled).str("status"))
    }

    @Test fun confirmationAndHandoverMustBeDoneOnThePhone() {
        val confirm = RemoteStatus.describe(RemoteTask("t", "mcp-x", "g", 0,
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "Shop", "1 item", "¥25", "com.example.shop"))))
        assertEquals("waiting_on_phone", confirm.str("status"))
        assertEquals("confirmation", confirm.str("waiting_for"))
        assertFalse(confirm["answerable"]!!.jsonPrimitive.boolean)
        assertTrue(confirm.str("note")!!.contains("on the phone"))
        val takeover = RemoteStatus.describe(RemoteTask("t", "mcp-x", "g", 0, TaskState.WaitingTakeover("payment", "Please pay")))
        assertEquals("waiting_on_phone", takeover.str("status"))
        assertEquals("hand_to_user", takeover.str("waiting_for"))
        assertFalse(takeover["answerable"]!!.jsonPrimitive.boolean)
    }

    @Test fun answerOnlyGoesToAnOpenQuestion() = runTest {
        call(McpTaskTools.RUN, args("goal" to "buy it"))
        fake.set("t1", TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "Shop", "1 item", "¥25", "com.example.shop")))
        rejects(McpTaskTools.ANSWER, args("task_id" to "t1", "text" to "yes"), "cannot be answered over MCP")
        fake.set("t1", TaskState.WaitingTakeover("payment", "Please pay"))
        rejects(McpTaskTools.ANSWER, args("task_id" to "t1", "text" to "yes"), "mika_wait_task")
        fake.set("t1", TaskState.Running(1, "x"))
        rejects(McpTaskTools.ANSWER, args("task_id" to "t1", "text" to "yes"), "not waiting")
        assertTrue("nothing may reach the task", fake.answers.isEmpty())

        fake.set("t1", TaskState.WaitingInput("Which size?"))
        rejects(McpTaskTools.ANSWER, args("task_id" to "t1", "text" to " "), "text")
        val r = call(McpTaskTools.ANSWER, args("task_id" to "t1", "text" to " M "))
        assertEquals("true", r.str("accepted"))
        assertEquals(listOf("t1" to "M"), fake.answers)
    }

    @Test fun closedQuestionPointsToTheStatusTool() = runTest {
        val closing = object : com.wangzan101.mika.core.remote.RemoteTasks by fake {
            override fun answer(id: String, text: String) = false
        }
        val closingTools = McpTaskTools(closing)
        closingTools.call(McpTaskTools.RUN, args("goal" to "x"))
        fake.set("t1", TaskState.WaitingInput("Which size?"))
        try {
            closingTools.call(McpTaskTools.ANSWER, args("task_id" to "t1", "text" to "M"))
            fail("expected an error")
        } catch (e: McpTaskTools.ToolError) {
            assertEquals("The question is no longer open; check mika_task_status.", e.message)
        }
    }

    @Test fun cancelLeavesFinishedTasksAlone() = runTest {
        call(McpTaskTools.RUN, args("goal" to "x"))
        fake.set("t1", TaskState.Running(1, "x"))
        assertEquals("true", call(McpTaskTools.CANCEL, args("task_id" to "t1")).str("cancelled"))
        fake.set("t1", TaskState.Succeeded("ok"))
        assertEquals("false", call(McpTaskTools.CANCEL, args("task_id" to "t1")).str("cancelled"))
        assertEquals(listOf("t1"), fake.cancelled)
    }

    @Test fun listValidatesLimitAndPutsNewestFirst() = runTest {
        call(McpTaskTools.RUN, args("goal" to "a"))
        call(McpTaskTools.RUN, args("goal" to "b"))
        val list = call(McpTaskTools.LIST, args())["tasks"]!!.jsonArray
        assertEquals(listOf("b", "a"), list.map { it.jsonObject.str("goal") })
        assertEquals(1, call(McpTaskTools.LIST, args("limit" to 1))["tasks"]!!.jsonArray.size)
        rejects(McpTaskTools.LIST, args("limit" to 0), "limit")
        rejects(McpTaskTools.LIST, args("limit" to "3"), "limit must be an integer")
    }

    @Test fun waitReturnsOnStatusChangeNotOnProgressNotes() = runTest {
        call(McpTaskTools.RUN, args("goal" to "x"))
        fake.set("t1", TaskState.Running(1, "a"))
        var result: JsonObject? = null
        launch { result = call(McpTaskTools.WAIT, args("task_id" to "t1", "timeout_s" to 20)) }
        advanceTimeBy(2_000)
        fake.set("t1", TaskState.Running(2, "b"))
        advanceTimeBy(2_000)
        assertEquals(null, result)
        fake.set("t1", TaskState.WaitingInput("Which one?"))
        advanceTimeBy(1_000)
        assertEquals("waiting_for_answer", result!!.str("status"))
        assertEquals("true", result!!.str("changed"))
    }

    @Test fun waitIsBoundedAndValidated() = runTest {
        call(McpTaskTools.RUN, args("goal" to "x"))
        fake.set("t1", TaskState.Running(1, "a"))
        rejects(McpTaskTools.WAIT, args("task_id" to "t1", "timeout_s" to 0), "timeout_s")
        rejects(McpTaskTools.WAIT, args("task_id" to "t1", "timeout_s" to McpTaskTools.MAX_WAIT_S + 1), "timeout_s")
        val start = testScheduler.currentTime
        val r = call(McpTaskTools.WAIT, args("task_id" to "t1", "timeout_s" to 3))
        assertEquals("false", r.str("changed"))
        assertEquals("running", r.str("status"))
        assertTrue(testScheduler.currentTime - start in 3_000..3_500)
        // 已经结束的立刻返回
        fake.set("t1", TaskState.Succeeded("done"))
        val t0 = testScheduler.currentTime
        assertEquals("done", call(McpTaskTools.WAIT, args("task_id" to "t1")).str("status"))
        assertEquals(t0, testScheduler.currentTime)
    }

    @Test fun waitReturnsTheLastSeenStatusWhenTheTaskDisappears() = runTest {
        call(McpTaskTools.RUN, args("goal" to "x"))
        fake.set("t1", TaskState.Running(1, "a"))
        launch { kotlinx.coroutines.delay(1_000); fake.tasks.remove("t1") }
        val r = call(McpTaskTools.WAIT, args("task_id" to "t1", "timeout_s" to 10))
        assertEquals("running", r.str("status"))
        assertEquals("false", r.str("changed"))
        assertTrue(testScheduler.currentTime < 10_000)
    }

    @Test fun conversationIdsAreRecognisable() {
        assertTrue(McpTaskTools.isConversation(McpTaskTools.newConversation()))
        assertFalse(McpTaskTools.isConversation("mcp-"))
        assertFalse(McpTaskTools.isConversation("mcp-3F2C1D7E-0000-4000-8000-000000000000"))
        assertFalse(McpTaskTools.isConversation(JsonPrimitive("x").content))
    }

    @Test fun steeringIsAMutatingToolAndUsesTheSharedValidation() = runTest {
        val definition = tools.definitions().first { it.jsonObject.str("name") == McpTaskTools.STEER }.jsonObject
        assertFalse(definition["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertTrue(definition["annotations"]!!.jsonObject["openWorldHint"]!!.jsonPrimitive.boolean)
        call(McpTaskTools.RUN, args("goal" to "read"))
        fake.set("t1", TaskState.Running(1, "reading"))
        rejects(McpTaskTools.STEER, args("task_id" to "t1", "text" to 2), "text must be a string")
        rejects(McpTaskTools.STEER, args("task_id" to "t1", "text" to " "), "text")
        val r = call(McpTaskTools.STEER, args("task_id" to "t1", "text" to "only today"))
        assertEquals("t1", r.str("task_id"))
        assertEquals("s1", r.str("message_id"))
        assertEquals("true", r.str("accepted"))
        assertEquals("received", call(McpTaskTools.STATUS, args("task_id" to "t1"))["steers"]!!.jsonArray.single().jsonObject.str("state"))
        fake.set("t1", TaskState.WaitingTakeover("payment", "Use phone"))
        rejects(McpTaskTools.STEER, args("task_id" to "t1", "text" to "approve"), "cannot be answered over MCP")
        assertEquals(1, fake.steered.size)
    }
}
