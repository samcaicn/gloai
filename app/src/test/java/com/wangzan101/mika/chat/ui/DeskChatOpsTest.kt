package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 聊天页走前台:打的字交给前台,回话流进那一问一答,派出的任务挂成同一轮下面的卡片,汇报在页面开着时由前台说。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeskChatOpsTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text" }
        override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean): Boolean {
            calls += "queue:$text"
            return true
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) { calls += "answer:$text" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    /** 后台任务端口:派出的任务各有一条可改的状态流,编号 child-1、child-2…… */
    private class FakePort : DeskTaskPort {
        val states = LinkedHashMap<String, MutableStateFlow<TaskState>>()
        val dispatched = mutableListOf<String>()
        val answered = mutableListOf<Pair<String, String>>()

        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome {
            val id = "child-${states.size + 1}"
            states[id] = MutableStateFlow(TaskState.Running(1, ""))
            dispatched += words
            return DispatchOutcome.Started(id)
        }

        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = null
        override fun steer(taskId: String, words: String) = true
        override fun answer(taskId: String, words: String): Boolean { answered += taskId to words; return true }
        override fun cancel(taskId: String) = true
    }

    /** 按脚本回话的前台大脑。 */
    private class ScriptBrain(
        val onReply: suspend (String, ReplyContext) -> Unit,
        val onNotice: suspend (String, ReplyContext) -> Unit = { _, _ -> },
    ) : DeskBrain {
        val replies = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val seeded = mutableListOf<List<Turn>>()
        override suspend fun reply(userText: String, ctx: ReplyContext) { replies += userText; onReply(userText, ctx) }
        override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {}
        override suspend fun notice(text: String, ctx: ReplyContext) { notices += text; onNotice(text, ctx) }
        override fun remember(assistantText: String) {}
        override fun seed(prior: List<Turn>) { seeded += prior }
    }

    private class Harness(
        val vm: ChatViewModel, val store: ConversationStore, val port: FakePort, val sunk: MutableList<Pair<String, DeskReport>>,
        val desk: (String) -> FrontDesk,
    )

    private fun TestScope.harness(brain: DeskBrain?, store: ConversationStore = ConversationStore(tmp.newFolder())): Harness {
        val port = FakePort()
        val sunk = mutableListOf<Pair<String, DeskReport>>()
        val sink = object : DeskSink {
            override suspend fun record(conversationId: String, report: DeskReport) { sunk += conversationId to report }
        }
        val desks = HashMap<String, Pair<FrontDesk, DeskLedger>>()
        fun entry(id: String) = desks.getOrPut(id) {
            val ledger = DeskLedger()
            FrontDesk(id, backgroundScope, port, ledger, ReportPolicy({ 0L }), sink) to ledger
        }
        val deps = DeskChatDeps(
            desk = { entry(it).first },
            executor = { id, verify ->
                val (desk, ledger) = entry(id)
                DeskToolExecutor(port, ledger, verify, { 0L }, id) { desk.track(it.taskId, it.title) }
            },
            brain = { brain },
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
            io = StandardTestDispatcher(testScheduler), desk = deps).also { runCurrent() }
        return Harness(vm, store, port, sunk) { entry(it).first }
    }

    private fun dispatch(words: String, title: String) = buildJsonObject { put("words", words); put("title", title) }

    @Test fun sendStreamsDeskReplyAndPersistsTurn() = runTest {
        val more = CompletableDeferred<Unit>()
        val brain = ScriptBrain({ _, ctx ->
            ctx.say("好,")
            more.await()
            ctx.say("我让后面去订。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        h.vm.input("帮我订明天的日料")
        assertTrue(h.vm.send(h.vm.ui.value.input, ok))
        // 发出去马上有用户气泡和「在想」的前台行,输入框清空
        val first = h.vm.ui.value.exchanges.single()
        assertEquals("帮我订明天的日料", first.goal)
        assertEquals("", first.deskReply)
        assertEquals("", h.vm.ui.value.input)
        runCurrent()
        // 回话一段一段流进同一行
        assertEquals("好,", h.vm.ui.value.exchanges.single().deskReply)
        assertEquals(ExchangeStatus.Working, h.vm.ui.value.exchanges.single().status)
        more.complete(Unit)
        runCurrent()
        val done = h.vm.ui.value.exchanges.single()
        assertEquals("好,我让后面去订。", done.deskReply)
        assertTrue(done.status is ExchangeStatus.Done)
        assertEquals(listOf("帮我订明天的日料"), brain.replies)
        // 存成前台的一轮,带同一个编号;重读对话时还是这一行
        val saved = h.store.turns("c").single()
        assertEquals(DeskTurn.STATUS, saved.status)
        assertEquals("帮我订明天的日料", saved.userText)
        assertEquals("好,我让后面去订。", saved.outcome)
        assertTrue(saved.runId!!.startsWith(DeskTurn.RUN_PREFIX))
        assertEquals(saved.runId, done.runId)
        assertNull(done.recordedRun)
        assertEquals(1, h.vm.ui.value.exchanges.size)
        // 没开任务、没插话
        assertTrue(calls.isEmpty())
    }

    @Test fun dispatchAddsCardToSameExchange() = runTest {
        val results = mutableListOf<JsonObject?>()
        val brain = ScriptBrain({ text, ctx ->
            // 前台编的话不是用户说的:派不出去,也不出卡片
            results += ctx.callTool(DeskTools.DISPATCH, dispatch("帮我买一张去上海的机票", "买机票"))
            results += ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
            ctx.say("好,我让后面去订。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        assertEquals("rejected", results[0]!!["status"].toString().trim('"'))
        assertEquals(listOf("帮我订明天的日料"), h.port.dispatched)
        val ex = h.vm.ui.value.exchanges.single()
        assertEquals(listOf("child-1"), ex.deskTasks)
        assertEquals("好,我让后面去订。", ex.deskReply)
        // 输入框上方的在办条和卡片看的是前台的任务板
        val view = h.vm.ui.value.deskTasks.single()
        assertEquals("child-1", view.taskId)
        assertEquals("订日料", view.title)
        assertEquals(DeskTaskPhase.Running, view.state)
        assertEquals(listOf("child-1"), h.store.turns("c").single().deskTasks)
        // 卡片是同一轮下面的一行
        assertEquals(listOf("u0", "r0", "c0-child-1"), chatItemKeys(h.vm.ui.value.exchanges, kept = null, phoneSetupRow = null, hasBlocked = false))
    }

    @Test fun composerNeverBusy() = runTest {
        val gate = CompletableDeferred<Unit>()
        val brain = ScriptBrain({ text, ctx ->
            if (text == "第一句") gate.await()
            ctx.say("收到:$text")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("第一句", ok))
        runCurrent()
        // 前台还在回第一句时,输入框照样能发,不变成「任务在跑」
        assertEquals(ComposerMode.Idle, h.vm.ui.value.composer)
        assertTrue(h.vm.send("第二句", ok))
        runCurrent()
        assertEquals(listOf("第一句", "第二句"), h.vm.ui.value.exchanges.map { it.goal })
        // 一句一句回:第二句等第一句回完
        assertEquals(listOf("第一句"), brain.replies)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("第一句", "第二句"), brain.replies)
        assertEquals(listOf("收到:第一句", "收到:第二句"), h.vm.ui.value.exchanges.map { it.deskReply })
        assertEquals(ComposerMode.Idle, h.vm.ui.value.composer)
        assertEquals(listOf("第一句", "第二句"), h.store.turns("c").map { it.userText })
        assertTrue(calls.none { it.startsWith("start:") || it.startsWith("steer:") || it.startsWith("queue:") })
    }

    // 通话正接着这条会话的前台:聊天页开着也不抢,汇报照样给通话;通话挂了、空出来了,聊天页接回来
    @Test fun chatLeavesTheDeskToACallAndTakesItBackAfter() = runTest {
        val brain = ScriptBrain(
            { text, ctx ->
                ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
                ctx.say("好。")
            },
            { _, ctx -> ctx.say("订好了。") },
        )
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        val heard = mutableListOf<String>()
        val call = object : DeskSurface {
            override suspend fun deliver(report: DeskReport): Boolean { heard += report.event.taskId; return true }
        }
        val handle = h.desk("c").attach(call)
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        h.port.states.getValue("child-1").value = TaskState.Succeeded("明早 8 点")
        runCurrent()
        // 开始、办好都给了通话
        assertEquals(listOf("child-1", "child-1"), heard)
        assertTrue(brain.notices.isEmpty())

        handle.close()
        runCurrent()
        assertTrue(h.vm.send("再订后天的", ok))
        runCurrent()
        h.port.states.getValue("child-2").value = TaskState.Succeeded("后天 9 点")
        runCurrent()
        assertEquals(1, brain.notices.size)
        assertEquals(listOf("child-1", "child-1"), heard)
        assertTrue(h.sunk.isEmpty())
    }

    @Test fun reportWhileOpenBecomesDeskRow() = runTest {
        val brain = ScriptBrain(
            { text, ctx ->
                ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
                ctx.say("好,我让后面去订。")
            },
            { _, ctx -> ctx.say("订好了,明早 8 点。") },
        )
        val h = harness(brain)
        h.vm.pageShown(true)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        // 开始了:卡片上看得到,前台不另说一句,也不写通知
        assertTrue(brain.notices.isEmpty())
        assertTrue(h.sunk.isEmpty())
        h.port.states.getValue("child-1").value = TaskState.Succeeded("明早 8 点")
        runCurrent()
        // 办好了:页面开着这条会话,由前台说一句,画成一行前台汇报,指向那张卡片
        assertEquals(1, brain.notices.size)
        assertTrue(brain.notices.single(), "child-1" in brain.notices.single() && "订日料" in brain.notices.single())
        val row = h.vm.ui.value.exchanges.last()
        assertEquals("", row.goal)
        assertEquals("订好了,明早 8 点。", row.deskReply)
        assertEquals(listOf("child-1"), row.deskTasks)
        val saved = h.store.turns("c").last()
        assertEquals(DeskTurn.REPORT_STATUS, saved.status)
        assertEquals("订好了,明早 8 点。", saved.outcome)
        assertEquals(listOf("child-1"), saved.deskTasks)
        assertTrue(h.sunk.isEmpty())
        assertEquals(DeskTaskPhase.Done, h.vm.ui.value.deskTasks.single().state)
        // 页面不在了:前台不说,交给兜底(写回会话、发通知)
        assertTrue(h.vm.send("再帮我订后天的", ok))
        runCurrent()
        h.vm.pageShown(false)
        runCurrent()
        h.port.states.getValue("child-2").value = TaskState.Succeeded("后天 9 点")
        runCurrent()
        assertEquals(1, brain.notices.size)
        assertEquals("child-2", h.sunk.single().second.event.taskId)
    }

    @Test fun noModelShowsBlockedWithGuide() = runTest {
        val h = harness(brain = null)
        h.vm.openConversation("c")
        runCurrent()
        h.vm.input("帮我订明天的日料")
        // 没配模型:入口照样在,发出去拦下,引导去模型设置;话留在输入框
        assertFalse(h.vm.send(h.vm.ui.value.input, ok))
        assertEquals(listOf<Problem>(Problem.NoModel), h.vm.ui.value.blocked)
        assertTrue(h.vm.ui.value.exchanges.isEmpty())
        assertEquals("帮我订明天的日料", h.vm.ui.value.input)
        assertTrue(calls.isEmpty())
    }

    @Test fun pendingCallQuestionTakesTheMessageBeforeTheDeskEvenWithAttachments() = runTest {
        val answered = mutableListOf<Pair<String, String>>()
        val brain = ScriptBrain({ _, ctx -> ctx.say("不该走到前台") })
        val port = FakePort()
        val deps = DeskChatDeps(
            desk = { FrontDesk(it, backgroundScope, port, DeskLedger(), ReportPolicy({ 0L }), object : DeskSink {
                override suspend fun record(conversationId: String, report: DeskReport) {}
            }) },
            executor = { id, verify -> DeskToolExecutor(port, DeskLedger(), verify, { 0L }, id) },
            brain = { brain },
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), io = StandardTestDispatcher(testScheduler), desk = deps,
            sandboxReady = MutableStateFlow(true),
            ownerAnswer = { id, text -> answered += id to text; true }).also { runCurrent() }
        vm.openConversation("a")
        runCurrent()
        vm.attach(listOf("content://x/a.pdf"))
        runCurrent()
        vm.input("周日也行")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(listOf("a" to "周日也行"), answered)
        assertTrue(brain.replies.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals("", vm.ui.value.input)
    }

    @Test fun teachingKeepsTheOldBlock() = runTest {
        var blocked = 0
        val brain = ScriptBrain({ _, ctx -> ctx.say("不该走到前台") })
        val port = FakePort()
        val deps = DeskChatDeps(
            desk = { FrontDesk(it, backgroundScope, port, DeskLedger(), ReportPolicy({ 0L }), object : DeskSink {
                override suspend fun record(conversationId: String, report: DeskReport) {}
            }) },
            executor = { id, verify -> DeskToolExecutor(port, DeskLedger(), verify, { 0L }, id) },
            brain = { brain },
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), io = StandardTestDispatcher(testScheduler), desk = deps,
            teaching = { true }, onTeachingBlocked = { blocked++ }).also { runCurrent() }
        vm.openConversation("a")
        assertFalse(vm.send("帮我订明天的日料", ok))
        runCurrent()
        assertEquals(1, blocked)
        assertTrue(brain.replies.isEmpty())
    }
}
