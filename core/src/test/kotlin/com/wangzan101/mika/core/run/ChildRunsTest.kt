package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildRunsTest {
    @Test fun spawnReturnsBoundedObservationAndPersistsChildFirst() = runBlocking {
        val parent = RunSpec(RunId("parent"), "goal", "thread")
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(FakeModel(answerReply("子结论")),
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store, ownerAlive = { store.all().single().phase == ChildPhase.Completed })
        val tools = toolDispatcher(FakeScreen(), runSpec = parent, childHost = runner)
        val result = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"只查一条事实"}"""), ActionPass())
        val record = store.all().single()
        assertEquals(ChildPhase.Completed, record.phase)
        assertEquals("parent", record.parentId)
        assertEquals("子结论", record.result)
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals("子结论", result.observations.single().text)
    }

    @Test fun childRestrictionsCannotBeBypassedByRunSpecFlags() = runBlocking {
        val spec = RunSpec(RunId("child"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Present),
            capabilities = null, readOnly = false, parent = RunId("parent"))
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec,
            childHost = ChildRunHost { _, _ -> error("must not spawn") }, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertTrue("web_read_page" in names)
        assertFalse(names.any { it.startsWith("phone_") || it == "web_interact" || it == "write_file" ||
            it == "spawn_agent" || it == "memory_save" || it == "schedule_task" ||
            it == "ask_user" || it == "hand_to_user" || it == "request_confirmation" })
        assertEquals("unknown_tool", tools.execute(Part.ToolCall("w", "web_interact", "{}"), ActionPass())
            .content["error"].toString().trim('"'))
    }

    @Test fun absentOwnerLeavesUnknownResultWithoutRerun() = runBlocking {
        val store = MemoryChildRunStore()
        val spec = RunSpec(RunId("c"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended), parent = RunId("p"))
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply("answer")),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store,
            ownerAlive = { false })
        assertEquals(ChildPhase.Unknown, runner.spawn(spec, null).phase)
        assertEquals(ChildPhase.Unknown, store.get("c")?.phase)
    }

    private fun spawnWithRunner(text: String, args: String, ownerAlive: Boolean = true): Pair<MemoryChildRunStore, kotlinx.serialization.json.JsonObject> = runBlocking {
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(FakeModel(answerReply(text)),
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store, ownerAlive = { ownerAlive })
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = runner)
        store to tools.execute(Part.ToolCall("s", "spawn_agent", args), ActionPass()).content
    }

    @Test fun realRunnerReturnsResultsBeyondTheStoredPreview() {
        val text = "x".repeat(6000)
        val (store, content) = spawnWithRunner(text, """{"goal":"research","maxResultChars":8000}""")
        assertEquals(6000, resultLength(content))
        assertEquals(null, content["truncated"])
        assertEquals(null, content["fullLength"])
        assertEquals(text.take(ChildAgentRunner.MAX_RESULT_CHARS), store.all().single().result)
    }

    @Test fun realRunnerReportsOriginalLengthForDefaultAndMaximumLimits() {
        val text = "x".repeat(12_000)
        for (limit in listOf(null, 8000)) {
            val args = if (limit == null) """{"goal":"research"}"""
                else """{"goal":"research","maxResultChars":$limit}"""
            val (store, content) = spawnWithRunner(text, args)
            assertEquals(limit ?: 4000, resultLength(content))
            assertEquals("true", content["truncated"].toString())
            assertEquals("12000", content["fullLength"].toString())
            assertEquals(4000, store.all().single().result!!.length)
        }
    }

    @Test fun realRunnerValidatesJsonBeforeTruncatingTheStoredPreview() {
        val text = """{"data":"${"x".repeat(5000)}"}"""
        val (store, content) = spawnWithRunner(text, """{"goal":"research","format":"json","maxResultChars":8000}""")
        assertEquals(text.length, resultLength(content))
        assertEquals(text, (content["result"] as kotlinx.serialization.json.JsonPrimitive).content
            .removePrefix("<untrusted>\n").removeSuffix("\n</untrusted>"))
        assertEquals(null, content["truncated"])
        assertEquals(text.take(4000), store.all().single().result)
    }

    @Test fun realRunnerKeepsInvalidJsonWarningAndBoundsItsStoredPreview() {
        val text = "{\"data\":\"" + "x".repeat(5000)
        val (store, content) = spawnWithRunner(text, """{"goal":"research","format":"json","maxResultChars":8000}""")
        val result = (content["result"] as kotlinx.serialization.json.JsonPrimitive).content
        assertTrue(result.contains(text))
        assertTrue(result.contains("JSON did not pass validation"))
        assertEquals(null, content["truncated"])
        assertEquals(text.take(4000), store.all().single().result)
    }

    @Test fun absentOwnerAlsoKeepsOnlyABoundedPreview() {
        val (store, content) = spawnWithRunner("x".repeat(6000),
            """{"goal":"research","maxResultChars":8000}""", ownerAlive = false)
        assertEquals("false", content["ok"].toString())
        assertEquals("unknown", (content["status"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(6000, resultLength(content))
        assertEquals(ChildPhase.Unknown, store.all().single().phase)
        assertEquals(4000, store.all().single().result!!.length)
    }

    private fun spawnWith(args: String, text: String = "x".repeat(20_000)): Pair<RunSpec, kotlinx.serialization.json.JsonObject> = runBlocking {
        var seen: RunSpec? = null
        val host = ChildRunHost { spec, _ -> seen = spec; ChildResult(spec.id, ChildPhase.Completed, text) }
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = host)
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", args), ActionPass())
        seen!! to r.content
    }

    private fun resultLength(content: kotlinx.serialization.json.JsonObject) =
        (content["result"] as kotlinx.serialization.json.JsonPrimitive).content.removePrefix("<untrusted>\n").removeSuffix("\n</untrusted>").length

    @Test fun defaultBudgetsStayAsBefore() {
        val (spec, content) = spawnWith("""{"goal":"查一条"}""")
        assertEquals(15, spec.stepBudget)
        assertTrue(spec.readOnly)
        assertFalse(spec.parallelChild)
        assertEquals(setOf("web", "sandbox"), spec.capabilities)
        assertEquals(4000, resultLength(content))
        assertEquals("true", content["truncated"].toString())
    }

    @Test fun modelChosenBudgetsAreClampedToTheCaps() {
        val (spec, content) = spawnWith("""{"goal":"查三家","maxSteps":30,"maxResultChars":6000}""")
        assertEquals(30, spec.stepBudget)
        assertEquals(6000, resultLength(content))
        val (high, highContent) = spawnWith("""{"goal":"查三家","maxSteps":500,"maxResultChars":100000}""")
        assertEquals(40, high.stepBudget)
        assertEquals(8000, resultLength(highContent))
        val (low, lowContent) = spawnWith("""{"goal":"查三家","maxSteps":0,"maxResultChars":10}""")
        assertEquals(1, low.stepBudget)
        assertEquals(500, resultLength(lowContent))
        val (_, short) = spawnWith("""{"goal":"查三家"}""", text = "短结论")
        assertEquals(null, short["truncated"])
    }

    @Test fun nonIntegerBudgetIsRejected() = runBlocking {
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"),
            childHost = ChildRunHost { _, _ -> error("must not spawn") })
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"g","maxSteps":2.5}"""), ActionPass())
        assertEquals("invalid_arguments", r.content["error"].toString().trim('"'))
    }

    @Test fun tasksRunSeveralChildrenInParallelAndMerge() = runBlocking {
        val seen = mutableListOf<RunSpec>()
        val host = ChildRunHost { spec, _ ->
            seen += spec
            ChildResult(spec.id, ChildPhase.Completed, "结果-${spec.goal}")
        }
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = host)
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", """{"tasks":["查甲","查乙","查丙"]}"""), ActionPass())
        assertEquals(setOf("查甲", "查乙", "查丙"), seen.map { it.goal }.toSet())
        assertTrue(seen.all { it.parallelChild })
        assertEquals("3", r.content["ranInParallel"].toString())
        assertEquals("true", r.content["ok"].toString())
        val merged = (r.content["result"] as kotlinx.serialization.json.JsonPrimitive).content
        assertTrue("查甲" in merged && "查乙" in merged && "查丙" in merged)
        assertEquals(3, r.observations.size)
    }

    @Test fun failedChildIsRecordedAsFailedNotCompleted() = runBlocking {
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(fakeModel { serverError(401) },
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store)
        val parent = RunSpec(RunId("p"), "goal", "thread")
        val child = RunSpec(RunId("c"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended), parent = parent.id)
        val result = runner.spawn(child, null)
        assertEquals(ChildPhase.Failed, result.phase)
        assertEquals(ChildPhase.Failed, store.get("c")?.phase)
    }

    @Test fun batchReportsEachChildStatusAndStartupErrorInTaskOrder() = runBlocking {
        val host = ChildRunHost { spec, _ ->
            when (spec.goal) {
                "查甲" -> ChildResult(RunId("child-a"), ChildPhase.Completed, "x".repeat(750))
                "查乙" -> ChildResult(RunId("child-b"), ChildPhase.Failed, "网页未加载")
                else -> error("子任务存储不可用")
            }
        }
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = host)
        val result = tools.execute(Part.ToolCall("s", "spawn_agent",
            """{"tasks":["查甲","查乙","查丙"],"maxResultChars":500}"""), ActionPass())
        val entries = checkNotNull(result.content["results"]).jsonArray.map { it.jsonObject }

        assertEquals("false", result.content["ok"].toString())
        assertEquals(listOf("查甲", "查乙", "查丙"), entries.map { it.getValue("goal").jsonPrimitive.content })
        assertEquals(listOf("completed", "failed", "failed"), entries.map { it.getValue("status").jsonPrimitive.content })
        assertEquals("child-a", entries[0].getValue("childRunId").jsonPrimitive.content)
        assertEquals("true", entries[0]["truncated"].toString())
        assertEquals("750", entries[0]["fullLength"].toString())
        assertEquals("child-b", entries[1].getValue("childRunId").jsonPrimitive.content)
        assertTrue(entries[2].getValue("error").jsonPrimitive.content.contains("子任务存储不可用"))
        assertTrue(result.content.getValue("result").jsonPrimitive.content.contains("网页未加载"))
    }
}
