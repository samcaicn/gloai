package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.GroupStrategy
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.testing.FakeChatModel
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.assembly.toolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun caps(toolCalls: Boolean = true, vision: Boolean = false, contextTokens: Int = 100_000) =
    Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = contextTokens, reasoning = false)

private fun reply(text: String) = ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)

private fun failing(name: String, error: ModelError, c: Capabilities = caps()) = FakeChatModel(name, c, listOf({ throw error }))

private fun answering(name: String, text: String = "ok", c: Capabilities = caps()) = FakeChatModel(name, c, listOf({ reply(text) }))

private fun group(vararg members: GroupMember, strategy: GroupStrategy = GroupStrategy.Failover, sticky: Boolean = true) =
    ModelGroup("g", "主力兜底", strategy = strategy, members = members.toList(), sticky = sticky)

private fun poolMember(id: String, model: ChatModel, priority: Int = 0, weight: Int = 1) =
    PoolMember(MemberKey("p-$id", model.name), model, priority = priority, weight = weight)

class ModelGroupTest {
    @Test
    fun failoverOrdersByPriorityThenListOrder() {
        val a = answering("a")
        val b = answering("b")
        val c = answering("c")
        val pool = GroupPool(
            group(GroupMember("a", priority = 5), GroupMember("b", priority = 1), GroupMember("c", priority = 1)),
            listOf(poolMember("a", a, 5), poolMember("b", b, 1), poolMember("c", c, 1)),
            ModelHealthRegistry(), StickyTable(), "g/s",
        )
        assertEquals(listOf("b", "c", "a"), pool.ordered(ModelNeed()).map { it.model.name })
    }

    @Test
    fun filtersByToolsVisionImagesAndContext() {
        val noTools = answering("no-tools", c = caps(toolCalls = false))
        val small = answering("small", c = caps(contextTokens = 1_000))
        val blind = answering("blind")
        val seeing = answering("seeing", c = caps(vision = true))
        val pool = GroupPool(
            group(GroupMember("a"), GroupMember("b"), GroupMember("c"), GroupMember("d")),
            listOf(poolMember("a", noTools), poolMember("b", small), poolMember("c", blind), poolMember("d", seeing)),
            ModelHealthRegistry(), StickyTable(), "g/s",
        )
        assertEquals(listOf("seeing"), pool.ordered(ModelNeed(toolCalls = true, vision = true, estimatedTokens = 50_000)).map { it.model.name })
        // 上下文不够时放宽窗口限制,交给压缩;工具/看图不够不能放宽
        assertEquals(setOf("no-tools", "blind", "seeing"), pool.ordered(ModelNeed(estimatedTokens = 50_000)).map { it.model.name }.toSet())
    }

    @Test
    fun stickyMemberIsKeptEvenIfRawEstimateExceedsItsWindow() {
        val a = answering("a", c = caps(contextTokens = 64_000))
        val b = answering("b", c = caps(contextTokens = 200_000))
        val sticky = StickyTable().also { it.put("g/s", MemberKey("p-a", "a")) }
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            ModelHealthRegistry(), sticky, "g/s",
        )
        // 原始估算超过 a 的窗口,但压缩后可能放得下:粘性成员不能被静默换掉
        assertEquals("a", pool.ordered(ModelNeed(estimatedTokens = 100_000)).first().model.name)
    }

    @Test
    fun visionCapabilityFollowsThePrimaryMember() {
        val text = answering("text", c = caps(vision = false))
        val eye = answering("eye", c = caps(vision = true))
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", text, 0), poolMember("b", eye, 1)),
            ModelHealthRegistry(), StickyTable(), null,
        )
        // 主成员看不了图就不开截图,避免第一张图之后永久换到备用成员
        assertFalse(pool.capabilities.vision)
        // 真的需要看图时仍然只把图发给能看图的成员
        assertEquals(listOf("eye"), pool.ordered(ModelNeed(vision = true, images = 1)).map { it.model.name })
    }

    @Test
    fun contentRefusedDoesNotCountAsConsecutiveFailure() {
        var now = 0L
        val health = ModelHealthRegistry { now }
        val key = MemberKey("p", "m")
        health.noteFailure(key, ModelError.ContentRefused())
        assertEquals(0, health.snapshot(key).consecutiveFailures)
        health.noteFailure(key, ModelError.Server(503))
        assertEquals(1, health.snapshot(key).consecutiveFailures)
        assertEquals(30_000L, health.snapshot(key).cooldownUntilMs)
    }

    @Test
    fun stickyMemberComesFirst() {
        val a = answering("a")
        val b = answering("b")
        val sticky = StickyTable()
        sticky.put("g/s", MemberKey("p-b", "b"))
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            ModelHealthRegistry(), sticky, "g/s",
        )
        assertEquals(listOf("b", "a"), pool.ordered(ModelNeed()).map { it.model.name })
    }

    @Test
    fun unhealthyMemberIsSkippedAndStickyIsCleared() {
        val a = answering("a")
        val b = answering("b")
        val health = ModelHealthRegistry()
        health.noteFailure(MemberKey("p-a", "a"), ModelError.Auth())
        val sticky = StickyTable().also { it.put("g/s", MemberKey("p-a", "a")) }
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            health, sticky, "g/s",
        )
        assertEquals(listOf("b"), pool.ordered(ModelNeed()).map { it.model.name })
    }

    @Test
    fun weightedChoosesByConversationAndSticks() {
        val a = answering("a")
        val b = answering("b")
        val sticky = StickyTable()
        val pool = GroupPool(
            group(GroupMember("a", weight = 1), GroupMember("b", weight = 3), strategy = GroupStrategy.Weighted),
            listOf(poolMember("a", a, weight = 1), poolMember("b", b, weight = 3)),
            ModelHealthRegistry(), sticky, "g/s",
        )
        val first = pool.ordered(ModelNeed()).first()
        pool.noteResult(first, 100, null)
        // 选完就粘住:后续排序都以它开头
        assertEquals(first.model.name, pool.ordered(ModelNeed()).first().model.name)
    }

    @Test
    fun fallbackCompletesWithNextMemberAndRecordsHealth() = runTest {
        val a = failing("a", ModelError.Auth())
        val b = answering("b", "from b")
        val health = ModelHealthRegistry()
        val sticky = StickyTable()
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            health, sticky, "g/s",
        )
        val out = pool.complete("s", emptyList(), emptyList())
        assertEquals(listOf(Part.Text("from b")), out.parts)
        assertEquals(1, a.requests.size)
        assertEquals(1, b.requests.size)
        assertEquals(MemberKey("p-b", "b"), sticky.get("g/s"))
        assertTrue(health.available(MemberKey("p-b", "b")))
    }

    @Test
    fun contentRefusedIsNotRetriedElsewhere() = runTest {
        val a = failing("a", ModelError.ContentRefused())
        val b = answering("b")
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            ModelHealthRegistry(), StickyTable(), "g/s",
        )
        val error = runCatching { pool.complete("s", emptyList(), emptyList()) }.exceptionOrNull()
        assertTrue(error is ModelError.ContentRefused)
        assertEquals(0, b.requests.size)
    }

    @Test
    fun weightedCursorSurvivesNewPoolsForTheSameGroup() {
        val a = answering("a")
        val b = answering("b")
        val weights = GroupWeights()
        val g = ModelGroup("g", "g", strategy = GroupStrategy.Weighted, sticky = false, members = listOf(
            GroupMember("a", weight = 1), GroupMember("b", weight = 3),
        ))
        fun pool() = GroupPool(
            g, listOf(poolMember("a", a, weight = 1), poolMember("b", b, weight = 3)),
            ModelHealthRegistry(), StickyTable(), null, weights.forGroup("g"),
        )
        // 两个任务各建一个池:累计值不能重置,否则每次都先选同一个成员
        val first = pool().ordered(ModelNeed()).first().model.name
        val second = pool().ordered(ModelNeed()).first().model.name
        assertTrue(first != second)
    }

    @Test
    fun scoreUsesConsecutiveFailuresNotLifetimeRate() {
        val healthyAgain = MemberHealth(calls = 10, failures = 9, consecutiveFailures = 0, latencySamples = 1, ewmaLatencyMs = 100.0)
        assertEquals(100.0, healthyAgain.score(), 0.001)
        assertEquals(500.0, healthyAgain.copy(consecutiveFailures = 2).score(), 0.001)
    }

    @Test
    fun emptyGroupReportsUnavailable() = runTest {
        val pool = GroupPool(group(), emptyList(), ModelHealthRegistry(), StickyTable(), "g/s")
        val error = runCatching { pool.complete("s", emptyList(), emptyList()) }.exceptionOrNull()
        assertTrue(error is ModelError.GroupUnavailable)
    }
}

class ModelCallerGroupTest {
    @Test
    fun groupOfFallsBackToTheMainPoolWithoutRouter() = runTest {
        val a = answering("a")
        val pool = GroupPool(
            group(GroupMember("a")), listOf(poolMember("a", a)),
            ModelHealthRegistry(), StickyTable(), null,
        )
        val caller = ModelCaller(pool, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("system")
        assertEquals("主力兜底", caller.groupOf(pool.members.single().model))
    }

    @Test
    fun mainGroupFailsOverToNextMember() = runTest {
        val a = failing("a", ModelError.Auth())
        val b = answering("b", "from b")
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            ModelHealthRegistry(), StickyTable(), "g/s",
        )
        val caller = ModelCaller(pool, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("system")
        val answer = caller.ask(emptyList(), emptyList(), 1)
        assertEquals(listOf(Part.Text("from b")), answer.parts)
        assertEquals(1, a.requests.size)
        assertEquals(1, b.requests.size)
    }

    @Test
    fun secondCallSkipsCoolingMember() = runTest {
        val a = failing("a", ModelError.Auth())
        val b = FakeChatModel("b", caps(), listOf({ reply("from b") }, { reply("from b") }))
        val health = ModelHealthRegistry()
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", b, 1)),
            health, StickyTable(), "g/s",
        )
        val caller = ModelCaller(pool, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("system")
        caller.ask(emptyList(), emptyList(), 1)
        caller.ask(emptyList(), emptyList(), 2)
        assertEquals(1, a.requests.size)
        assertEquals(2, b.requests.size)
    }

    @Test
    fun refusesToSendForeignPrivatePartsToAnotherMember() = runTest {
        val a = FakeChatModel("a", caps(), listOf({ reply("a1") }, { throw ModelError.Auth() }))
        val sent = mutableListOf<List<Message>>()
        val bModel = object : ChatModel {
            override val capabilities = caps()
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                sent += messages
                return reply("b1")
            }
        }
        val pool = GroupPool(
            group(GroupMember("a", priority = 0), GroupMember("b", priority = 1)),
            listOf(poolMember("a", a, 0), poolMember("b", bModel, 1)),
            ModelHealthRegistry(), StickyTable(), null,
        )
        val caller = ModelCaller(pool, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("system")
        val first = caller.ask(emptyList(), emptyList(), 1).parts
        val firstMessage = Message(Role.Assistant, first, com.wangzan101.mika.core.model.Author.Mika)
        caller.noteReply(firstMessage, a, Usage(1, 1))
        val privatePart = Part.Thinking("private")
        val withPrivate = Message(Role.Assistant, listOf(privatePart, Part.Text("a2")), com.wangzan101.mika.core.model.Author.Mika)
        caller.noteReply(withPrivate, a, null)
        // a 下一次失败,b 接答:b 收到的历史里不能有 a 的思考内容
        val answer = caller.ask(listOf(firstMessage, withPrivate), emptyList(), 2)
        assertEquals(reply("b1"), answer)
        assertTrue(sent.single().flatMap { it.parts }.none { it is Part.Thinking })
    }
}

class FailoverPolicyTest {
    @Test
    fun memberAndTransportErrorsFailOver() {
        listOf(
            ModelError.Auth(), ModelError.QuotaExceeded(), ModelError.RateLimited(null), ModelError.Network(),
            ModelError.StreamCut(), ModelError.Server(503), ModelError.ModelNotFound(), ModelError.ModelNotAvailable(),
            ModelError.ToolsUnsupported(), ModelError.ChatGptLimitReached(), ModelError.ChatGptNotEligible(),
            ModelError.SignInExpired(), ModelError.AccountPlanNotAllowed("xAI"),
        ).forEach { assertTrue(it::class.simpleName, FailoverPolicy.canFailover(it)) }
    }

    @Test
    fun contentAndRequestErrorsDoNotFailOver() {
        listOf(
            ModelError.ContentRefused(), ModelError.ContextTooLong(), ModelError.BadResponse(),
            ModelError.Server(400), ModelError.GroupUnavailable(), ModelError.GroupExhausted(),
        ).forEach { assertTrue(it::class.simpleName, !FailoverPolicy.canFailover(it)) }
    }

    @Test
    fun cooldownGrowsAndIsCapped() {
        assertEquals(30_000L, FailoverPolicy.cooldownMs(ModelError.Server(503), 1))
        assertEquals(60_000L, FailoverPolicy.cooldownMs(ModelError.Server(503), 2))
        assertEquals(10 * 60_000L, FailoverPolicy.cooldownMs(ModelError.Server(503), 9))
        assertEquals(5 * 60_000L, FailoverPolicy.cooldownMs(ModelError.Auth(), 1))
        assertEquals(12_000L, FailoverPolicy.cooldownMs(ModelError.RateLimited(12_000), 1))
    }

    @Test
    fun cancellationDoesNotFailOver() {
        assertTrue(!FailoverPolicy.canFailover(kotlinx.coroutines.CancellationException()))
    }
}

class StripForeignPrivateTest {
    @Test
    fun removesThinkingAndOpaqueButKeepsText() {
        val call = Part.ToolCall("id", "x", "{}", "opaque")
        val m = Message(Role.Assistant, listOf(Part.Thinking("t"), Part.Text("hello"), call), com.wangzan101.mika.core.model.Author.Mika)
        val stripped = stripForeignPrivate(listOf(m)).single()
        assertEquals(2, stripped.parts.size)
        assertTrue(stripped.parts.none { it is Part.Thinking })
        assertEquals(null, (stripped.parts.filterIsInstance<Part.ToolCall>().single()).opaque)
    }

    @Test
    fun userMessagesAreUntouched() {
        val m = Message(Role.User, listOf(Part.Thinking("keep")), com.wangzan101.mika.core.model.Author.User)
        assertSame(m, stripForeignPrivate(listOf(m)).single())
    }
}
