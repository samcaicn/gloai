package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.ownResultNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 一件任务收尾时任务自己的「任务完成」和前台兜底通知加起来的条数:前台此刻正跟着这次运行时只有前台那一条;
 * 前台没在跟的(子会话里直接开的另一件、进程没了以后补送的、前台会话已删的)任务照旧自己发。
 */
class DeskResultNoticeTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val texts = ChineseTexts()

    private class FakeRun(override val conversationId: String, override val key: String, initial: TaskState) : DeskRun {
        val flow = MutableStateFlow(initial)
        override val state: StateFlow<TaskState> get() = flow
        override fun steer(words: String) = true
        override fun answer(words: String) {}
        override fun cancel() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private inner class Rig {
        val store = ConversationStore(tmp.newFolder())
        val runs = MutableStateFlow<List<DeskRun>>(emptyList())
        val notices = mutableListOf<DeskNotice>()
        val port = AppDeskTaskPort(store, runs, { runs.value }, { _, _, _ -> }, { 1 }, scope, { "没开始" }, { "中断了" })
        val registry = DeskRegistry(store, port, DeskNotifier(store) { synchronized(notices) { notices += it } }, { null })

        /** 页面不在时由前台派出一件,返回子会话编号。 */
        suspend fun dispatch(words: String): String =
            registry.executor("p", AnyWords).execute(DeskTools.DISPATCH, buildJsonObject { put("words", words) })["task_id"]!!.jsonPrimitive.content

        /** 运行 [run] 以 [end] 收尾,任务那边按 [ownResultNotice] 决定发不发,返回它发了几条(0 或 1)。 */
        fun taskNotices(run: FakeRun, end: TaskState, restored: Boolean = false): Int {
            val delivery = Delivery.Result("result:${run.key}", run.conversationId, "好了", restored = restored)
            return if (ownResultNotice(delivery, run.key, end, registry::reportsEnding)) 1 else 0
        }

        fun deskNotices() = synchronized(notices) { notices.size }
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!check()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    @Test fun aDeskFollowedRunNotifiesExactlyOnce() = runBlocking {
        val rig = Rig()
        val child = rig.dispatch("查天气")
        val run = FakeRun(child, "k1", TaskState.Running(1, "打开天气"))
        rig.runs.value = listOf(run)
        eventually("port binds the run") { rig.port.followedTaskOf("k1") == child }

        val end = TaskState.Succeeded("晴")
        run.flow.value = end
        val own = rig.taskNotices(run, end)
        eventually("desk report") { rig.deskNotices() == 1 }
        assertEquals(0, own)
        assertEquals(1, rig.deskNotices() + own)
    }

    // 子会话里收尾后用户直接打字又开了一件:前台不跟这次运行,任务自己发。
    @Test fun aRunTypedInTheChildNotifiesItself() = runBlocking {
        val rig = Rig()
        val child = rig.dispatch("查天气")
        val first = FakeRun(child, "k1", TaskState.Running(1, "打开天气"))
        rig.runs.value = listOf(first)
        eventually("port binds the run") { rig.port.followedTaskOf("k1") == child }
        first.flow.value = TaskState.Succeeded("晴")
        eventually("desk report") { rig.deskNotices() == 1 }
        rig.runs.value = emptyList()

        val second = FakeRun(child, "k2", TaskState.Running(1, "再查明天"))
        rig.runs.value = listOf(second)
        val end = TaskState.Succeeded("明天多云")
        second.flow.value = end
        delay(200)
        assertEquals(1, rig.taskNotices(second, end))
        assertEquals("desk stays quiet about a run it does not follow", 1, rig.deskNotices())
    }

    // 进程没了以后恢复、补送的结果:任务自己发,前台此刻跟不跟都一样。
    @Test fun aRecoveredResendNotifiesItself() = runBlocking {
        val rig = Rig()
        val child = rig.dispatch("查天气")
        val run = FakeRun(child, "k1", TaskState.Running(1, "打开天气"))
        rig.runs.value = listOf(run)
        eventually("port binds the run") { rig.port.followedTaskOf("k1") == child }
        assertEquals(1, rig.taskNotices(run, TaskState.Succeeded("晴"), restored = true))
        assertTrue(ownResultNotice(Delivery.Result("result:k1", child, "晴", restored = true), null, TaskState.Idle) { _, _ -> true })
    }

    // 前台会话删了(前台已收掉),或者用户自己取消的(前台不说),任务自己发。
    @Test fun noLiveDeskOrACancelMeansTheTaskNotifies() = runBlocking {
        val rig = Rig()
        val child = rig.dispatch("查天气")
        val run = FakeRun(child, "k1", TaskState.Running(1, "打开天气"))
        rig.runs.value = listOf(run)
        eventually("port binds the run") { rig.port.followedTaskOf("k1") == child }
        assertTrue(rig.registry.reportsEnding("k1", TaskState.Succeeded("晴")))
        assertFalse(rig.registry.reportsEnding("k1", TaskState.Cancelled))
        assertFalse(rig.registry.reportsEnding("k1", TaskState.Running(2, "还在做")))
        rig.registry.forget("p")
        assertFalse(rig.registry.reportsEnding("k1", TaskState.Succeeded("晴")))
    }

    // 前台跟着的那件停下来问用户、页面不在:只有任务自己那条带回复框的通知;前台不写行(聊天页有问题卡),也不另发。
    @Test fun aDeskFollowedQuestionWritesNoLineAndNoSecondNotification() = runBlocking {
        val rig = Rig()
        val child = rig.dispatch("订票")
        val run = FakeRun(child, "k1", TaskState.Running(1, "打开购票"))
        rig.runs.value = listOf(run)
        eventually("port binds the run") { rig.port.followedTaskOf("k1") == child }
        run.flow.value = TaskState.WaitingInput("要几点的车?")
        eventually("the desk sees the question") {
            rig.registry.desk("p").active.value.any { it.taskId == child && it.need?.prompt == "要几点的车?" }
        }
        assertTrue(rig.store.turns("p").none { it.status == DeskNotifier.STATUS })
        assertEquals(0, rig.deskNotices())
    }
}
