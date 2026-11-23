package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence
import com.wangzan101.mika.core.learn.memory.TaskMemoryStep
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMemoryToolsTest {
    private class FakePersistence(var stored: List<TaskMemory> = emptyList()) : TaskMemoryPersistence {
        override fun load() = stored
        override fun save(items: List<TaskMemory>) { stored = items }
    }

    private fun store(vararg tasks: TaskMemory) =
        TaskMemoryStore(FakePersistence(tasks.toList()), clock = { 0L }, ids = { "x" })

    private val sample = TaskMemory(
        id = "t1", goal = "帮妈妈买药", conclusion = "已下单",
        apps = listOf("com.taobao"), sites = listOf("example.com"), createdAt = 1_761_200_000_000L,
        steps = listOf(TaskMemoryStep(1_000L, "打开买药应用", "药房首页"), TaskMemoryStep(2_000L, "点结算", "确认订单")),
    )

    private fun call(name: String, args: String) = Part.ToolCall("c1", name, args)
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test fun offeredOnlyWithStore() {
        assertFalse(toolDispatcher(FakeScreen()).definitions().any { it.name == "task_memory_search" })
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        assertTrue(dispatcher.definitions().any { it.name == "task_memory_search" })
        assertTrue(dispatcher.definitions().any { it.name == "task_memory_timeline" })
        assertTrue(dispatcher.definitions().any { it.name == "task_memory_step" })
    }

    @Test fun searchReturnsCompactTasks() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        val result = dispatcher.execute(call("task_memory_search", """{"query":"买药"}"""), ActionPass()).content
        assertEquals(true, result.getValue("ok").jsonPrimitive.content.toBoolean())
        val hit = result.getValue("tasks").jsonArray.single().jsonObject
        assertEquals("t1", hit.text("id"))
        assertEquals("帮妈妈买药", hit.text("goal"))
        assertEquals("已下单", hit.text("conclusion"))
        assertEquals("com.taobao", hit.getValue("apps").jsonArray.single().jsonPrimitive.content)
        assertEquals("example.com", hit.getValue("sites").jsonArray.single().jsonPrimitive.content)
        assertEquals(2, hit.getValue("steps").jsonPrimitive.content.toInt())
    }

    @Test fun searchRejectsBadDate() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        val result = dispatcher.execute(call("task_memory_search", """{"query":"买药","since":"not-a-date"}"""), ActionPass()).content
        assertEquals(false, result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("since_invalid", result.text("error"))
    }

    @Test fun timelineGivesStepsWithoutDetail() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        val result = dispatcher.execute(call("task_memory_timeline", """{"id":"t1"}"""), ActionPass()).content
        assertEquals("t1", result.text("id"))
        val steps = result.getValue("steps").jsonArray
        assertEquals(2, steps.size)
        val first = steps[0].jsonObject
        assertEquals(1, first.getValue("n").jsonPrimitive.content.toInt())
        assertEquals("打开买药应用", first.text("label"))
        assertFalse(first.containsKey("detail"))
    }

    @Test fun stepReadReturnsDetailAndObservation() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        val result = dispatcher.execute(call("task_memory_step", """{"id":"t1","step":2}"""), ActionPass()).content
        assertEquals("确认订单", result.text("detail"))
        assertEquals("点结算", result.text("label"))
        val obs = dispatcher.ctx.evidence.ledger.all.single()
        assertEquals("task_memory", obs.source)
        assertEquals("确认订单", obs.text)
    }

    @Test fun stepReadOutOfRangeFails() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        val result = dispatcher.execute(call("task_memory_step", """{"id":"t1","step":9}"""), ActionPass()).content
        assertEquals(false, result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("step_not_found", result.text("error"))
    }

    @Test fun unknownIdFails() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), taskMemory = store(sample))
        val result = dispatcher.execute(call("task_memory_timeline", """{"id":"nope"}"""), ActionPass()).content
        assertEquals(false, result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("not_found", result.text("error"))
    }
}
