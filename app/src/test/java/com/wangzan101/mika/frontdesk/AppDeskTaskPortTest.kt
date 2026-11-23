package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppDeskTaskPortTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRun(override val conversationId: String, initial: TaskState) : DeskRun {
        val flow = MutableStateFlow(initial)
        override val state: StateFlow<TaskState> get() = flow
        val steered = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var cancelled = false
        override fun steer(words: String): Boolean { steered += words; return true }
        override fun answer(words: String) { answered += words }
        override fun cancel() { cancelled = true }
    }

    private data class Start(val words: String, val conversationId: String, val brief: String?)

    private inner class Rig(scope: TestScope, limit: Int = 2, timeoutMs: Long = 60_000) {
        val store = ConversationStore(tmp.newFolder())
        val runs = MutableStateFlow<List<DeskRun>>(emptyList())
        val starts = mutableListOf<Start>()
        val port = AppDeskTaskPort(store, runs, { runs.value }, { w, c, b -> starts += Start(w, c, b) }, { limit },
            scope.backgroundScope, { "没开始" }, timeoutMs)
    }

    @Test fun dispatchCreatesLinkedChildAndStartsWithBrief() = runTest {
        val rig = Rig(this)
        val outcome = rig.port.dispatch("帮我订明早的票", "用户偏好靠窗", "p")
        val child = (outcome as DispatchOutcome.Started).taskId
        assertEquals("p", rig.store.deskParent(child))
        assertEquals(listOf(Start("帮我订明早的票", child, "用户偏好靠窗")), rig.starts)
        // 每件活都进一个新建的空子会话,不会落进已有任务的会话被当成插话。
        val second = rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started
        assertTrue(second.taskId != child)
        assertNull(rig.starts.last().brief)
    }

    @Test fun noteIsCappedAtThisLayer() = runTest {
        val rig = Rig(this)
        rig.port.dispatch("查天气", "说".repeat(DESK_NOTE_MAX_CHARS + 50), "p")
        assertEquals(DESK_NOTE_MAX_CHARS, rig.starts.single().brief!!.length)
    }

    @Test fun queuedWhenParallelLimitReached() = runTest {
        val rig = Rig(this, limit = 1)
        rig.runs.value = listOf(FakeRun("other", TaskState.Running(1, "在做")))
        val outcome = rig.port.dispatch("查天气", "", "p")
        assertEquals(DispatchOutcome.Queued((outcome as DispatchOutcome.Queued).taskId, 1), outcome)
        assertEquals(1, rig.port.queuePosition(outcome.taskId))
    }

    // 刚派出、服务还没登记的也占着位置,接连派两件不会都当成开始了。
    @Test fun unregisteredDispatchCountsTowardLimit() = runTest {
        val rig = Rig(this, limit = 1)
        assertTrue(rig.port.dispatch("查天气", "", "p") is DispatchOutcome.Started)
        assertEquals(1, (rig.port.dispatch("订票", "", "p") as DispatchOutcome.Queued).position)
    }

    @Test fun stateFollowsRegisteredRunUntilTerminal() = runTest {
        val rig = Rig(this)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        val state = rig.port.state(child)!!
        assertEquals(TaskState.Idle, state.value)
        val run = FakeRun(child, TaskState.Running(1, "打开天气"))
        rig.runs.value = listOf(run)
        runCurrent()
        assertEquals(TaskState.Running(1, "打开天气"), state.value)

        run.flow.value = TaskState.WaitingInput("哪个城市?")
        assertTrue(rig.port.answer(child, "上海"))
        assertEquals(listOf("上海"), run.answered)
        assertFalse(rig.port.steer(child, "顺便看明天"))

        run.flow.value = TaskState.Running(2, "查上海")
        runCurrent()
        assertTrue(rig.port.steer(child, "顺便看明天"))
        assertTrue(rig.port.cancel(child))
        assertTrue(run.cancelled)

        run.flow.value = TaskState.Succeeded("晴")
        runCurrent()
        assertEquals(TaskState.Succeeded("晴"), state.value)
        rig.runs.value = emptyList()
        assertFalse(rig.port.cancel(child))
    }

    @Test fun queuePositionFollowsServiceQueue() = runTest {
        val rig = Rig(this, limit = 1)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        val queued = TaskState.Held(GateReason.queued("排队"))
        rig.runs.value = listOf(FakeRun("a", TaskState.Running(1, "x")), FakeRun("b", queued), FakeRun(child, queued))
        assertEquals(2, rig.port.queuePosition(child))
        assertNull(rig.port.queuePosition("a"))
    }

    @Test fun neverRegisteredBecomesFailed() = runTest {
        val rig = Rig(this, timeoutMs = 1_000)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        val state = rig.port.state(child)!!
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(TaskState.Failed("没开始", FailKind.Environment), state.value)
    }

    @Test fun startFailureReportsFailedAndLeavesNoChild() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val port = AppDeskTaskPort(store, MutableStateFlow(emptyList()), { emptyList() }, { _, _, _ -> error("blocked") }, { 2 },
            backgroundScope, { "没开始" })
        assertEquals(DispatchOutcome.Failed("blocked"), port.dispatch("查天气", "", "p"))
    }

    // 重开会话时接回:不认识的编号为 null;挂在前台会话下、任务已不在的按子会话记下的结局给终态。
    @Test fun restoredChildUsesRecordedOutcome() = runTest {
        val rig = Rig(this)
        assertNull(rig.port.state("stranger"))
        rig.store.append("c1", Turn("查天气", "晴", "succeeded"))
        rig.store.setDeskParent("c1", "p")
        assertEquals(TaskState.Succeeded("晴"), rig.port.state("c1")!!.value)
        rig.store.append("c2", Turn("订票", "", "cancelled"))
        rig.store.setDeskParent("c2", "p")
        assertEquals(TaskState.Cancelled, rig.port.state("c2")!!.value)
    }
}
