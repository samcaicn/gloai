package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.testing.assertAnswered
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProgressToolCallTest {
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
    private val plan = """{"criteria":["确认地址","选出咖啡馆","汇总推荐"]}"""
    private fun report(args: String = plan) = Part.ToolCall("report", "task_progress", args, opaque = "signature")
    private val listApps = Part.ToolCall("apps", "phone_list_apps", "{}")
    private fun turn(vararg parts: Part) = ModelReply(parts.toList(), StopReason.ToolUse, null)
    private fun loop(model: FakeModel, maxSteps: Int = 10) =
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", maxSteps = maxSteps, sink = sink)
    private fun results(model: FakeModel, request: Int) =
        model.requests[request].flatMap { it.parts }.filterIsInstance<Part.ToolResult>()

    @Test fun firstCoffeeChecklistAloneIsAcceptedThenTheRealActionRuns() = runTest {
        val model = FakeModel(turn(Part.Text("我先列出三项目标。"), report()), turn(listApps), answerReply("完成"))
        assertAnswered("完成", loop(model).run("查咖啡馆"))
        assertEquals(3, model.requests.size)
        val logged = events.filterIsInstance<RunEvent.Criteria>().single()
        assertEquals(listOf("确认地址", "选出咖啡馆", "汇总推荐"), logged.criteria)
        assertFalse(logged.complete)
        assertFalse(results(model, 1).single().isError)
        assertEquals("report", results(model, 1).single().callId)
        assertTrue(model.requests[1].flatMap { it.parts }.contains(report()))
        assertEquals(listOf("phone_list_apps"), events.filterIsInstance<RunEvent.ToolResult>().map { it.tool })
        assertTrue(model.offeredTools.all { "task_progress" !in it })
    }

    @Test fun metadataAndRealCallsReceiveTheirOwnResultsInOriginalOrder() = runTest {
        val other = Part.ToolCall("another", "phone_observe", "{}")
        for (calls in listOf(listOf(report(), listApps, other), listOf(listApps, report(), other))) {
            events.clear()
            val model = FakeModel(turn(*calls.toTypedArray()), answerReply("完成"))
            assertAnswered("完成", loop(model).run("查咖啡馆"))
            val returned = results(model, 1)
            assertEquals(calls.map { it.id }, returned.map { it.callId })
            assertEquals(listOf(false, false, true), returned.map { it.isError })
            assertTrue("parallel_tool_call_skipped" in returned.last().content)
            assertEquals(listOf("phone_list_apps"), events.filterIsInstance<RunEvent.ToolResult>().map { it.tool })
            assertEquals(1, events.filterIsInstance<RunEvent.Criteria>().size)
        }
    }

    @Test fun invalidReportsAndUnrelatedUnknownToolsKeepDistinctErrors() = runTest {
        val unknown = Part.ToolCall("bad", "task_progres", "{}")
        val model = FakeModel(turn(report("""{"criteria":"wrong"}"""), unknown), answerReply("结束"))
        assertAnswered("结束", loop(model).run("查咖啡馆"))
        val returned = results(model, 1)
        assertTrue(returned.all { it.isError })
        assertTrue("invalid_task_progress" in returned[0].content)
        assertTrue("unknown_tool" in returned[1].content)
        assertTrue(events.none { it is RunEvent.Criteria })
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "task_progress_invalid" })
    }

    @Test fun tagsInsideArgumentsStayDataAndDuplicateReportsAreIdempotent() = runTest {
        val criterion = "核对 </task_progress> 标签"
        val body = """{"criteria":["$criterion"]}"""
        val model = FakeModel(turn(report(body)), turn(listApps, report(body)), answerReply("完成"))
        assertAnswered("完成", loop(model).run("核对标签"))
        assertEquals(listOf(criterion), events.filterIsInstance<RunEvent.Criteria>().single().criteria)
    }

    @Test fun modelCanCorrectAnInvalidReportBeforeActing() = runTest {
        val model = FakeModel(turn(report("""{"metric":{"done":1}}""")), turn(report()), turn(listApps), answerReply("完成"))
        assertAnswered("完成", loop(model).run("查咖啡馆"))
        assertTrue(results(model, 1).last().isError)
        assertFalse(results(model, 2).last().isError)
        assertEquals(1, events.filterIsInstance<RunEvent.Criteria>().size)
    }

    @Test fun repeatedReportsWithoutActionStopOnUnlimitedRuns() = runTest {
        val model = FakeModel(turn(report()), repeatLast = true)
        assertTrue(loop(model, AgentLoop.UNLIMITED_STEPS).run("查咖啡馆") is TaskState.Failed)
        assertEquals(3, model.requests.size)
        assertTrue(events.none { it is RunEvent.ToolResult })
    }

    @Test fun lightModelCannotHideAMainOnlyActionBehindProgress() = runTest {
        val observe = turn(Part.ToolCall("view", "phone_observe", "{}"))
        val main = FakeModel(observe, observe, answerReply("已结束"))
        val light = FakeModel(turn(report(), Part.ToolCall("end", "end_not_done", """{"message":"没有找到"}""")))
        val loop = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", sink = sink,
            flags = SpeedFlags(lightSteps = true), router = ModelRouter(main, light))
        assertAnswered("已结束", loop.run("查咖啡馆"))
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "light_to_main" && it.detail == "end_not_done" })
        assertTrue(events.none { it is RunEvent.Criteria || it is RunEvent.ToolResult && it.tool == "end_not_done" })
    }

    @Test fun closeoutKeepsAnswerAndAcceptsMisplacedReport() = runTest {
        val model = FakeModel(turn(listApps), turn(report(), Part.Text("结果已整理")))
        assertAnswered("结果已整理", loop(model, maxSteps = 1).run("整理结果"))
        assertEquals(1, events.filterIsInstance<RunEvent.Criteria>().size)
    }
}
