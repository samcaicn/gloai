package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val active = MutableStateFlow<List<String>>(emptyList())
    private val calls = mutableListOf<String>()
    /** 定时任务编号 → 绑着的对话。 */
    private val schedules = mutableMapOf("t1" to "a", "t2" to "a", "t3" to "b")
    private val workspaces = mutableSetOf("a", "b")

    private fun deleter(store: ConversationStore) = ConversationDeleter(
        store, active,
        cancelTasks = { calls += "cancel:$it" },
        schedulesOf = { id -> schedules.filterValues { it == id }.keys.sorted() },
        deleteWorkspace = { calls += "workspace:$it"; workspaces.remove(it) },
        stopWaitMs = 5_000,
        io = EmptyCoroutineContext,
    )

    private fun store(): ConversationStore = ConversationStore(tmp.newFolder()).also {
        it.append("a", Turn("查天气", "晴", "succeeded"))
        it.setTitle("a", "天气")
        it.append("b", Turn("订票", "好了", "succeeded"))
    }

    @Test fun impactCountsRunningTaskAndSchedules() {
        val d = deleter(store())
        assertEquals(DeleteImpact(runningTask = false, schedules = 2), d.impact("a"))
        active.value = listOf("b")
        assertEquals(DeleteImpact(runningTask = true, schedules = 1), d.impact("b"))
        assertEquals(DeleteImpact(runningTask = false, schedules = 0), d.impact("c"))
    }

    /** 没有任务在跑:删它的工作区和记录,保留定时任务;别的对话不动;不去停任务。 */
    @Test fun deletesRecordsAndWorkspaceButKeepsSchedules() = runTest {
        val store = store()
        store.setPinned("a", true, at = 1L)
        deleter(store).delete("a")
        assertEquals(listOf("workspace:a"), calls)
        assertEquals(mapOf("t1" to "a", "t2" to "a", "t3" to "b"), schedules)
        assertEquals(setOf("b"), workspaces)
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(null, store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** 有任务在跑:先停任务并等它收尾(收尾时存进的那一轮也删掉),然后才删文件。 */
    @Test fun stopsTheRunningTaskAndWaitsForItToEnd() = runTest {
        val store = store()
        active.value = listOf("a", "b")
        val job = launch { deleter(store).delete("a") }
        runCurrent()
        assertEquals(listOf("cancel:a"), calls)
        assertTrue("a" in workspaces)
        // 任务收尾:存进停止的那一轮,再从进行中拿掉
        store.append("a", Turn("查天气", "已停止", "cancelled"))
        active.value = listOf("b")
        job.join()
        assertEquals("workspace:a", calls.last())
        assertEquals(listOf("b"), store.list().map { it.id })
        assertFalse("a" in workspaces)
    }

    /** 任务迟迟不收尾:等到上限照样删。 */
    @Test fun givesUpWaitingAfterTheLimit() = runTest {
        val store = store()
        active.value = listOf("a")
        val job = launch { deleter(store).delete("a") }
        advanceTimeBy(4_000)
        assertTrue("a" in workspaces)
        advanceTimeBy(2_000)
        job.join()
        assertEquals(emptyList<Turn>(), store.turns("a"))
        assertFalse("a" in workspaces)
    }

    /** 保留下来的计划以后触发时,在原编号下只留下新的结果,不复活已删除的历史。 */
    @Test fun futureScheduledResultStartsAFreshConversation() = runTest {
        val store = store()
        deleter(store).delete("a")
        store.append("a", Turn("每天查天气", "今天晴", "succeeded"))
        assertEquals(listOf("每天查天气"), store.turns("a").map { it.userText })
        assertEquals(mapOf("t1" to "a", "t2" to "a", "t3" to "b"), schedules)
    }

    /** 工作区删不掉(设备不支持运行环境等)也照样删记录。 */
    @Test fun recordsAreDeletedEvenIfTheWorkspaceFails() = runTest {
        val store = store()
        ConversationDeleter(store, active, {}, { emptyList() }, { error("no sandbox") }, io = EmptyCoroutineContext).delete("a")
        assertEquals(listOf("b"), store.list().map { it.id })
    }

    @Test fun failedHistoryDeletionLeavesTheWorkspaceForRetry() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        assertTrue(File(dir, "a.json.tmp").mkdir())

        assertTrue(runCatching { deleter(store).delete("a") }.exceptionOrNull() is IOException)
        assertTrue("a" in workspaces)
        assertTrue(calls.isEmpty())
    }

    @Test fun deletingProjectConversationDeletesHistoryButKeepsSharedWorkspace() = runTest {
        val store = store()
        store.setProject("a", "proj-shared")
        var removedImportFor: String? = null
        ConversationDeleter(
            store, active,
            cancelTasks = { calls += "cancel:$it" },
            schedulesOf = { emptyList() },
            deleteWorkspace = { calls += "workspace:$it"; workspaces.remove(it) },
            stopWaitMs = 5_000,
            io = EmptyCoroutineContext,
            onDeleted = { removedImportFor = it },
        ).delete("a")

        assertEquals(listOf("b"), store.list().map { it.id })
        assertNull(store.projectId("a"))
        assertTrue("项目成员删对话时不能删共享工作区", "a" in workspaces)
        assertFalse(calls.any { it.startsWith("workspace:") })
        assertEquals("a", removedImportFor)
    }
}
