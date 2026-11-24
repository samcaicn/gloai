package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeskRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakePort : DeskTaskPort {
        val states = HashMap<String, MutableStateFlow<TaskState>>()
        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome {
            states["new"] = MutableStateFlow(TaskState.Running(1, "开始"))
            return DispatchOutcome.Started("new")
        }
        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = null
        override fun steer(taskId: String, words: String) = false
        override fun answer(taskId: String, words: String) = false
        override fun cancel(taskId: String) = false
    }

    private object NoSink : DeskSink {
        override suspend fun record(conversationId: String, report: DeskReport) {}
    }

    @Test fun registryRestoresFromTurns() {
        val store = ConversationStore(tmp.newFolder())
        store.append("p", Turn("帮我查天气", "好,交给后台了", "desk", deskTasks = listOf("c1")))
        store.append("c1", Turn("查明天上海的天气", "晴", "succeeded"))
        store.setDeskParent("c1", "p")
        val port = FakePort().apply { states["c1"] = MutableStateFlow(TaskState.Succeeded("晴")) }
        val registry = DeskRegistry(store, port, NoSink, { null })

        val desk = registry.desk("p")
        assertSame(desk, registry.desk("p"))
        val view = desk.active.value.single()
        assertEquals("c1", view.taskId)
        assertEquals("查明天上海的天气", view.title)
        assertEquals(DeskTaskPhase.Done, view.state)
    }

    @Test fun executorDispatchIsTrackedByTheDesk() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val registry = DeskRegistry(store, FakePort(), NoSink, { null })
        val result = registry.executor("p", AnyWords).execute(DeskTools.DISPATCH, buildJsonObject { put("words", "查天气") })
        assertEquals("started", result["status"].toString().trim('"'))
        assertEquals(listOf("new"), registry.desk("p").active.value.map { it.taskId })
    }

    @Test fun backgroundCarriesBoardAndMemory() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val registry = DeskRegistry(store, FakePort(), NoSink, { "- 喜欢靠窗" })
        registry.executor("p", AnyWords).execute(DeskTools.DISPATCH, buildJsonObject { put("words", "订票") })
        val text = registry.background("p").render("订票")!!
        assertTrue(text.contains("订票"))
        assertTrue(text.contains("喜欢靠窗"))
    }

    // 会话删掉后它的前台也收掉,同一个编号再来是新的前台。
    @Test fun deletedConversationDropsItsDesk() {
        val store = ConversationStore(tmp.newFolder())
        val registry = DeskRegistry(store, FakePort(), NoSink, { null })
        val desk = registry.desk("p")
        store.append("p", Turn("在吗", "在", "desk"))
        registry.forget("p")
        assertNotSame(desk, registry.desk("p"))
    }
}
