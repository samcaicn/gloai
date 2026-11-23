package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.InterruptibleStream
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插话撤掉正在流式输出的请求时,用户已经看到的那段字记进历史,接在插话前面;
 * 回复刚好先说完、插话随后才到时,说完的那段挂到插话上方(界面不丢)。
 */
class InterruptedReplyTest {
    private fun spec() = RunSpec(RunId("r1"), "查今天的天气", "thread-1")
    private val steerText = "改成查明天"

    private class Events : RunSink {
        val interrupted = mutableListOf<String>()
        override fun event(e: RunEvent) {
            if (e is RunEvent.State && e.name == "reply_interrupted") interrupted += e.detail
        }
    }

    /** 跑一次:第一次请求流出 [chunks] 后停住,插一句话,模型随后回答。返回第二次请求看到的消息。 */
    private suspend fun kotlinx.coroutines.test.TestScope.steerDuring(
        chunks: List<String>, events: Events = Events(), streamReplies: Boolean = true,
    ): List<Message> {
        val inner = fakeModel("main") { answer("明天多云。") }
        val model = InterruptibleStream(inner, chunks)
        val inbox = RunInbox()
        val running = async {
            AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = streamReplies, sink = events).run(spec(), inbox)
        }
        model.entered.await()
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("m1", steerText)))
        val end = running.await()
        assertTrue(model.cancelled)
        assertTrue(end is TaskState.Succeeded)
        assertEquals(1, inner.requests.size)
        return inner.requests[0].messages
    }

    private fun List<Message>.mikaTexts() =
        filter { it.role == Role.Assistant && it.author == Author.Mika }.map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text } }

    @Test fun keepsTheTextTheUserSawRightBeforeTheSteer() = runTest {
        val events = Events()
        val messages = steerDuring(listOf("好的,我先", "好的,我先查今天的天气 "), events)
        val kept = messages.indexOfLast { it.role == Role.Assistant }
        assertEquals(listOf("好的,我先查今天的天气"), messages.mikaTexts())
        assertEquals(listOf(Part.Text("好的,我先查今天的天气")), messages[kept].parts)
        val next = messages[kept + 1]
        assertEquals(Author.User, next.author)
        assertEquals("${AgentLoop.STEER_MARKER}\n$steerText", (next.parts.first() as Part.Text).text)
        assertEquals(listOf("好的,我先查今天的天气"), events.interrupted)
    }

    @Test fun nothingIsKeptWhenNoTextWasShown() = runTest {
        val events = Events()
        val messages = steerDuring(emptyList(), events)
        assertTrue(messages.mikaTexts().isEmpty())
        assertTrue(events.interrupted.isEmpty())
    }

    @Test fun textClearedByARetryIsNotKept() = runTest {
        val events = Events()
        val messages = steerDuring(listOf("先看一下", ""), events)
        assertTrue(messages.mikaTexts().isEmpty())
        assertTrue(events.interrupted.isEmpty())
    }

    @Test fun nothingIsKeptWhenRepliesAreNotStreamedToTheScreen() = runTest {
        val events = Events()
        val messages = steerDuring(listOf("好的,我先查今天的天气"), events, streamReplies = false)
        assertTrue(messages.mikaTexts().isEmpty())
        assertTrue(events.interrupted.isEmpty())
    }

    @Test fun aModelThatDoesNotStreamLeavesNothingBehind() = runTest {
        val entered = CompletableDeferred<Unit>()
        val requests = mutableListOf<List<Message>>()
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                requests += messages
                if (requests.size == 1) { entered.complete(Unit); awaitCancellation() }
                return answerReply("明天多云。")
            }
        }
        val inbox = RunInbox()
        val running = async { AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = true).run(spec(), inbox) }
        entered.await()
        inbox.send(RunInput.Steer("m1", steerText))
        running.await()
        assertTrue(requests[1].mikaTexts().isEmpty())
    }

    private class AllStates : RunSink {
        val states = mutableListOf<Pair<String, String>>()
        override fun event(e: RunEvent) { if (e is RunEvent.State) states += e.name to e.detail }
        fun detail(name: String) = states.filter { it.first == name }.map { it.second }
    }

    // 回复刚好先说完、插话随后才到:回复照常记进历史,说完的那段(全文,不截短)发给界面挂到插话上方,不算被打断
    @Test fun aReplyThatFinishedJustBeforeTheSteerIsKeptOnScreen() = runTest {
        val full = "今天晴,最高 25 度,傍晚有风。"
        val inbox = RunInbox()
        val all = AllStates()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                all.event(e)
                if (e is RunEvent.ModelReply && e.step == 1) runBlocking { inbox.send(RunInput.Steer("late", steerText)) }
            }
        }
        val model = FakeModel(answerReply(full), answerReply("明天多云。"))
        val end = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = true, sink = sink).run(spec(), inbox)
        assertEquals("明天多云。", (end as TaskState.Succeeded).answer)
        assertEquals(listOf(full), model.requests[1].mikaTexts())
        assertEquals(listOf(full), all.detail("reply_finished_before_steer"))
        assertTrue(all.detail("reply_interrupted").isEmpty())
        assertEquals(listOf("reply_finished_before_steer", "steer_received"),
            all.states.map { it.first }.filter { it == "reply_finished_before_steer" || it == "steer_received" })
    }

    // 没显示过字(不流式显示)时没有什么要挂的,插话照常接上
    @Test fun nothingIsAttachedWhenTheFinishedReplyWasNotShown() = runTest {
        val inbox = RunInbox()
        val all = AllStates()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                all.event(e)
                if (e is RunEvent.ModelReply && e.step == 1) runBlocking { inbox.send(RunInput.Steer("late", steerText)) }
            }
        }
        val model = FakeModel(answerReply("今天晴。"), answerReply("明天多云。"))
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", sink = sink).run(spec(), inbox)
        assertEquals(2, model.requests.size)
        assertTrue(all.detail("reply_finished_before_steer").isEmpty())
        assertEquals(1, all.detail("steer_received").size)
    }
}
