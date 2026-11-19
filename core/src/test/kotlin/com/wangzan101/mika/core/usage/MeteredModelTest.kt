package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MeteredModelTest {
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private val source = UsageSource("p1", "服务商甲", "m1", UsagePurpose.of(ModelRole.Light))
    private val recorded = mutableListOf<Pair<UsageSource, Usage?>>()
    private val attempts = mutableListOf<Int>()
    private val sink = object : UsageSink {
        override fun record(s: UsageSource, u: Usage?) = record(s, u, 1)
        override fun record(s: UsageSource, u: Usage?, httpAttempts: Int) {
            recorded += s to u
            attempts += httpAttempts
        }
    }

    private class Plain(val reply: () -> ModelReply) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64000, reasoning = false)
        override val name = "plain-model"
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = reply()
    }

    private class Streaming(val reply: ModelReply) : StreamingChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 8000, reasoning = true)
        var calls = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            return reply
        }
        override suspend fun completeStreaming(
            system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
        ): ModelReply {
            calls++
            onText("一"); onText("一二")
            return reply
        }
    }

    private fun reply(usage: Usage?) = ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, usage)

    @Test fun recordsReportedUsageAndPassesReplyThrough() = runTest {
        val r = reply(Usage(100, 20, 60))
        val m = Plain { r }.metered(source, sink)
        assertSame(r, m.complete("s", user, emptyList()))
        assertEquals(listOf(source to Usage(100, 20, 60)), recorded)
    }

    @Test fun conversationUsesReportedCacheDenominatorAndLatestMainContext() = runTest {
        var totals = ConversationUsage()
        val meter = ConversationMeter { change -> totals = change(totals) }
        val main = Plain { reply(Usage(100, 12, 80)) }.metered(source, sink)
        val noCacheField = Plain { reply(Usage(900, 8)) }.metered(source, sink)
        val explicitMiss = Streaming(reply(Usage(300, 4, cacheReported = true))).metered(source, sink) as StreamingChatModel
        val ctx = com.wangzan101.mika.core.agent.ContextBuilder()
        val messages = ctx.start("hello", memory = "memory", taskImages = listOf(Part.Image("private", width = 30, height = 25))).messages
        withContext(meter) {
            withContext(ConversationMeter.MainRequest(1)) { main.complete("abcd", messages, emptyList()) }
            val first = totals.latest!!
            assertEquals(1L, first.instructions)
            assertTrue(first.references > 0 && first.dialogue > 0)
            assertEquals(1L, first.toolsAndMedia)
            noCacheField.complete("auxiliary", user, emptyList())
            assertEquals(first, totals.latest)
            withContext(ConversationMeter.MainRequest(2)) { explicitMiss.completeStreaming("system", user, emptyList()) {} }
        }
        assertEquals(1300L, totals.input)
        assertEquals(24L, totals.output)
        assertEquals(400L, totals.cacheReportedInput)
        assertEquals(0.2, totals.cacheRate!!, 0.00001)
        assertEquals(2, totals.latest!!.step)
        assertEquals(8000, totals.latest!!.window)
        assertEquals(0L, totals.latest!!.references)
        assertTrue(totals.add(null, 1).partial)
        assertFalse(totals.add(null, 0).partial)
    }

    @Test fun keepsNameAndCapabilities() {
        val inner = Plain { reply(null) }
        val m = inner.metered(source, sink)
        assertEquals("plain-model", m.name)
        assertEquals(inner.capabilities, m.capabilities)
        assertFalse("不支持逐段文字的套上后也不支持", m is StreamingChatModel)
        assertTrue("没调用就不记", recorded.isEmpty())
    }

    @Test fun nonHttpReplyDoesNotInventARequest() = runTest {
        Plain { reply(null) }.metered(source, sink).complete("s", user, emptyList())
        assertEquals(listOf<Pair<UsageSource, Usage?>>(source to null), recorded)
        assertEquals(listOf(0), attempts)
    }

    @Test fun localFailureIsRethrownWithoutAnHttpAttempt() = runTest {
        val err = ModelError.RateLimited(1000)
        val m = Plain { throw err }.metered(source, sink)
        try {
            m.complete("s", user, emptyList()); fail()
        } catch (e: ModelError.RateLimited) {
            assertSame(err, e)
        }
        assertEquals(listOf<Pair<UsageSource, Usage?>>(source to null), recorded)
        assertEquals(listOf(0), attempts)
    }

    @Test fun cancellationBeforeHttpIsRethrownWithoutAnAttempt() = runTest {
        val m = Plain { throw CancellationException("stop") }.metered(source, sink)
        try {
            m.complete("s", user, emptyList()); fail()
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(listOf<Pair<UsageSource, Usage?>>(source to null), recorded)
        assertEquals(listOf(0), attempts)
    }

    @Test fun exhaustedTaskBudgetRejectsPlainAndStreamingBeforeCallingOrRecording() = runTest {
        val tally = RunUsageTally(tokenBudget = 5)
        tally.add(Usage(3, 2), httpAttempts = 1)
        var plainCalls = 0
        val plain = Plain { plainCalls++; reply(Usage(1, 1)) }.metered(source, sink)
        val streamingInner = Streaming(reply(Usage(1, 1)))
        val streaming = streamingInner.metered(source, sink) as StreamingChatModel
        withContext(tally) {
            try {
                plain.complete("s", user, emptyList())
                fail("普通调用应在预算检查处停止")
            } catch (_: ModelError.TaskBudgetExceeded) {}
            try {
                streaming.completeStreaming("s", user, emptyList()) {}
                fail("流式调用应在预算检查处停止")
            } catch (_: ModelError.TaskBudgetExceeded) {}
        }
        assertEquals(0, plainCalls)
        assertEquals(0, streamingInner.calls)
        assertTrue("拒绝的调用不写总账", recorded.isEmpty())
        assertTrue("拒绝的调用不增加实际请求", attempts.isEmpty())
        assertEquals("拒绝的调用不增加逻辑调用", 1, tally.event(1).calls)
        assertEquals(5L, tally.totalTokens())
    }

    @Test fun dailyBudgetGuardRunsBeforeTheDelegateAndLedger() = runTest {
        var calls = 0
        val model = Plain { calls++; reply(Usage(1, 1)) }.metered(source, sink) {
            throw ModelError.DailyBudgetExceeded("服务商甲", 10_000, 10_000)
        }
        try {
            model.complete("s", user, emptyList())
            fail("每日预算应在请求前拒绝")
        } catch (_: ModelError.DailyBudgetExceeded) {}
        assertEquals(0, calls)
        assertTrue(recorded.isEmpty())
        assertTrue(attempts.isEmpty())
    }

    @Test fun streamingStaysStreamingAndForwardsText() = runTest {
        val m = Streaming(reply(Usage(5, 6))).metered(source, sink)
        assertTrue(m is StreamingChatModel)
        assertTrue(m.capabilities.vision)
        val texts = mutableListOf<String>()
        val r = (m as StreamingChatModel).completeStreaming("s", user, emptyList(), texts::add)
        assertEquals(listOf("一", "一二"), texts)
        assertEquals(Usage(5, 6), r.usage)
        assertEquals(listOf(source to Usage(5, 6)), recorded)
    }

    @Test fun brokenSinkDoesNotBreakTheCall() = runTest {
        val r = reply(Usage(1, 1))
        val m = Plain { r }.metered(source, sink = { _, _ -> error("disk full") })
        assertSame(r, m.complete("s", user, emptyList()))
    }

    // 非 HTTP 模型仍记逻辑调用和最终用量,但不能凭空增加实际请求;不在运行上下文里的调用不算
    @Test fun callsInsideARunAreTalliedForThatRun() = runTest {
        val tally = RunUsageTally()
        val ok = Plain { reply(Usage(100, 20, 60)) }.metered(source, sink)
        val silent = Plain { reply(null) }.metered(source, sink)
        val failing = Plain { throw ModelError.RateLimited(1000) }.metered(source, sink)
        kotlinx.coroutines.withContext(tally) {
            ok.complete("s", user, emptyList())
            silent.complete("s", user, emptyList())
            runCatching { failing.complete("s", user, emptyList()) }
        }
        ok.complete("s", user, emptyList())
        val e = tally.event(7)
        assertEquals(3, e.calls)
        assertEquals(0, e.httpCalls)
        assertEquals(2, e.unreported)
        assertEquals(0, e.httpUnreported)
        assertEquals(100L, e.inputTokens)
        assertEquals(20L, e.outputTokens)
        assertEquals(60L, e.cachedTokens)
        // 总账照记每一次
        assertEquals(4, recorded.size)
    }
}
