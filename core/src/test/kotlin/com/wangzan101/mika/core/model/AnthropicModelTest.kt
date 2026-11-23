package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model(
        vision: Boolean = false, maxTokens: Int? = null, reasoningEffort: String? = null, thinkingTypes: List<String>? = null,
    ) = AnthropicModel(
        server.url("/").toString().trimEnd('/'), "sk-ant", "claude-x",
        Capabilities(true, vision, 200000, false), OkHttpClient(), maxTokens,
        reasoningEffort = reasoningEffort, thinkingTypes = thinkingTypes,
    )
    private val endpoint get() = server.url("/").toString().trimEnd('/') + "/v1/messages"
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun parsesToolUseBlocks() = runTest {
        server.enqueue(ok("""{"content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"t1","name":"phone_observe","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":3,"cache_read_input_tokens":7}}"""))
        val r = model().complete("sys", user, listOf(ToolSpec("phone_observe", "d", """{"type":"object"}""")))
        assertEquals(listOf(Part.Text("ok"), Part.ToolCall("t1", "phone_observe", "{}")), r.parts)
        assertEquals(StopReason.ToolUse, r.stopReason)
        assertEquals(Usage(17, 3, 7), r.usage)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val j = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("4096", j.str("max_tokens"))
        assertEquals("claude-x", j.str("model"))
    }

    @Test fun parsesThinkingBlocksAndStopReasons() = runTest {
        server.enqueue(ok("""{"content":[{"type":"thinking","thinking":"想","signature":"s"},{"type":"text","text":"好"}],"stop_reason":"end_turn"}"""))
        val r = model().complete("s", user, emptyList())
        assertEquals(listOf(Part.Thinking("想", SignedThinking.signed(endpoint, "s")), Part.Text("好")), r.parts)
        assertEquals(StopReason.EndTurn, r.stopReason)
        server.enqueue(ok("""{"content":[{"type":"redacted_thinking","data":"xyz"}],"stop_reason":"end_turn"}"""))
        assertEquals(listOf(Part.Thinking("", SignedThinking.redacted(endpoint, "xyz"))), model().complete("s", user, emptyList()).parts)
        server.enqueue(ok("""{"content":[{"type":"text","text":"x"}],"stop_reason":"max_tokens"}"""))
        assertEquals(StopReason.MaxTokens, model().complete("s", user, emptyList()).stopReason)
        server.enqueue(ok("""{"content":[{"type":"text","text":"x"}],"stop_reason":"refusal"}"""))
        assertEquals(StopReason.Other, model().complete("s", user, emptyList()).stopReason)
    }

    @Test fun sendsAdaptiveThinkingWithEffortOnlyWhenChosen() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(reasoningEffort = "high").complete("s", user, emptyList())
        val j = sentBody()
        assertEquals("adaptive", j["thinking"]!!.jsonObject.str("type"))
        assertEquals("summarized", j["thinking"]!!.jsonObject.str("display"))
        assertEquals("high", j["output_config"]!!.jsonObject.str("effort"))
        // 开思考时思考也占输出上限,默认上限放大
        assertEquals("16000", j.str("max_tokens"))

        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("s", user, emptyList())
        val plain = sentBody()
        assertFalse(plain.containsKey("thinking"))
        assertEquals("4096", plain.str("max_tokens"))

        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(reasoningEffort = "none").complete("s", user, emptyList())
        val off = sentBody()
        assertEquals("disabled", off["thinking"]!!.jsonObject.str("type"))
        assertFalse(off.containsKey("output_config"))
    }

    // 列表写明了思考方式的:不接受自适应思考的模型不发档位,不接受关掉思考的不发 disabled(什么都不发,按服务端默认)
    @Test fun sendsOnlyTheThinkingTypesTheModelAccepts() = runTest {
        repeat(4) { server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}""")) }
        model(reasoningEffort = "high", thinkingTypes = listOf("enabled", "disabled")).complete("s", user, emptyList())
        val budgetOnly = sentBody()
        assertFalse(budgetOnly.containsKey("thinking"))
        assertFalse(budgetOnly.containsKey("output_config"))
        assertEquals("4096", budgetOnly.str("max_tokens"))

        model(reasoningEffort = "none", thinkingTypes = listOf("adaptive")).complete("s", user, emptyList())
        assertFalse(sentBody().containsKey("thinking"))

        model(reasoningEffort = "high", thinkingTypes = listOf("adaptive", "disabled")).complete("s", user, emptyList())
        assertEquals("adaptive", sentBody()["thinking"]!!.jsonObject.str("type"))
        model(reasoningEffort = "none", thinkingTypes = listOf("adaptive", "disabled")).complete("s", user, emptyList())
        assertEquals("disabled", sentBody()["thinking"]!!.jsonObject.str("type"))
    }

    private fun signedHistory(endpoint: String) = listOf(
        Message(Role.User, listOf(Part.Text("go")), Author.Mika),
        Message(Role.Assistant, listOf(
            Part.Thinking("想", SignedThinking.signed(endpoint, "sig-1")), Part.Thinking("", SignedThinking.redacted(endpoint, "xyz")),
            // 别的服务商留下的思考不能被当成本家的签名发出去
            Part.Thinking("旧", """{"endpoint":"https://other.test","via":"reasoning"}"""),
            Part.Text("a"), Part.ToolCall("t1", "phone_tap", "{}"),
        ), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult("t1", "done", false)), Author.Mika),
    )

    private fun sentAssistantBlocks() = sentBody().arr("messages")[1].jsonObject.arr("content").map { it.jsonObject }

    // 带工具调用的回合要把签名思考块原样带回:选了档位和没选(服务端默认可能也在思考)都带;明确关掉思考时不带
    @Test fun replaysSignedThinkingBlocksUnlessThinkingIsTurnedOff() = runTest {
        val history = signedHistory(endpoint)
        repeat(3) { server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}""")) }
        model(reasoningEffort = "high").complete("s", history, emptyList())
        val asst = sentAssistantBlocks()
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), asst.map { it.str("type") })
        assertEquals("想", asst[0].str("thinking"))
        assertEquals("sig-1", asst[0].str("signature"))
        assertEquals("xyz", asst[1].str("data"))

        model(reasoningEffort = null).complete("s", history, emptyList())
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), sentAssistantBlocks().map { it.str("type") })

        model(reasoningEffort = "none").complete("s", history, emptyList())
        assertEquals(listOf("text", "tool_use"), sentAssistantBlocks().map { it.str("type") })
    }

    // 签名只有发它的服务认:另一个地址收到的思考块(换了 Anthropic 兼容服务)不回传
    @Test fun doesNotReplaySignaturesToAnotherEndpoint() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(reasoningEffort = "high").complete("s", signedHistory("https://other-gateway.test/v1/messages"), emptyList())
        assertEquals(listOf("text", "tool_use"), sentAssistantBlocks().map { it.str("type") })
    }

    // 工具调用前的进度说明是单独的思考块,排在文字后面:回传时照收到的顺序,不挪到最前面
    @Test fun replaysBlocksInTheOrderReceived() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        val history = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(
                Part.Thinking("想", SignedThinking.signed(endpoint, "sig-1")), Part.Text("先打开设置"),
                Part.Thinking("", SignedThinking.signed(endpoint, "sig-2")), Part.ToolCall("t1", "phone_launch_app", "{}"),
            ), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "done", false)), Author.Mika),
        )
        model().complete("s", history, emptyList())
        val asst = sentAssistantBlocks()
        assertEquals(listOf("thinking", "text", "thinking", "tool_use"), asst.map { it.str("type") })
        assertEquals(listOf("sig-1", "sig-2"), asst.filter { it.str("type") == "thinking" }.map { it.str("signature") })
    }

    private fun staleThinking() = MockResponse().setResponseCode(400).setBody(
        """{"type":"error","error":{"type":"invalid_request_error","message":"messages.1.content.0: Invalid `signature` in `thinking` block. The block is bound to a different conversation. Remove the block, or set `thinking.block_binding.prefix_mismatch_behavior` to \"drop_block\"."}}""",
    )

    // 展开一组工具、压缩历史以后,签名绑着之前请求前缀的思考块被拒:这次带的都去掉重发一次,之后也不再带;重发后收到的照常回传
    @Test fun dropsRejectedThinkingAndSendsAgainOnce() = runTest {
        val m = model(reasoningEffort = "high")
        server.enqueue(staleThinking())
        server.enqueue(ok("""{"content":[{"type":"thinking","thinking":"再想","signature":"sig-2"},{"type":"tool_use","id":"t2","name":"phone_tap","input":{}}],"stop_reason":"tool_use"}"""))
        val reply = m.complete("s", signedHistory(endpoint), emptyList())
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), sentAssistantBlocks().map { it.str("type") })
        assertEquals(listOf("text", "tool_use"), sentAssistantBlocks().map { it.str("type") })
        assertEquals(StopReason.ToolUse, reply.stopReason)

        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        val next = signedHistory(endpoint) + Message(Role.Assistant, reply.parts, Author.Mika) +
            Message(Role.Tool, listOf(Part.ToolResult("t2", "done", false)), Author.Mika)
        m.complete("s", next, emptyList())
        val sent = sentBody().arr("messages").map { msg -> msg.jsonObject.arr("content").map { it.jsonObject } }
        assertEquals(listOf("text", "tool_use"), sent[1].map { it.str("type") })
        assertEquals(listOf("thinking", "tool_use"), sent[3].map { it.str("type") })
        assertEquals("sig-2", sent[3][0].str("signature"))
        assertEquals(3, server.requestCount)
    }

    @Test fun streamingAlsoSendsAgainWithoutRejectedThinking() = runTest {
        val sse = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent() + "\n\n"
        server.enqueue(staleThinking())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val shown = mutableListOf<String>()
        // 没选思考强度(服务端默认可能也在思考)时同样回传、同样去掉重发
        val r = model().completeStreaming("s", signedHistory(endpoint), emptyList()) { shown += it }
        assertEquals(listOf(Part.Text("好")), r.parts)
        assertEquals(listOf("好"), shown)
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), sentAssistantBlocks().map { it.str("type") })
        assertEquals(listOf("text", "tool_use"), sentAssistantBlocks().map { it.str("type") })
    }

    // 别的 400 照常报错;签名本身坏了(只有前半句)不重发;这次没带思考块(关掉了思考)时不重发;去掉以后还被拒的不再发第三次
    @Test fun otherRejectionsAreNotSentAgain() = runTest {
        suspend fun rejected(m: AnthropicModel) =
            try { m.complete("s", signedHistory(endpoint), emptyList()); null } catch (e: ModelError.Server) { e }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"tools.0.name: invalid"}}"""))
        assertTrue(rejected(model(reasoningEffort = "high")) != null)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"messages.1.content.0: Invalid `signature` in `thinking` block."}}"""))
        assertTrue(rejected(model(reasoningEffort = "high")) != null)
        assertEquals(2, server.requestCount)
        server.enqueue(staleThinking())
        assertTrue(rejected(model(reasoningEffort = "none")) != null)
        assertEquals(3, server.requestCount)
        repeat(2) { server.enqueue(staleThinking()) }
        assertTrue(rejected(model(reasoningEffort = "high")) != null)
        assertEquals(5, server.requestCount)
    }

    @Test fun streamsThinkingSignatureForReplay() = runTest {
        val sse = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent() + "\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val r = model(reasoningEffort = "high").completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Thinking("想", SignedThinking.signed(endpoint, "sig"))), r.parts)
    }

    @Test fun streamingRateLimitAndOverloadErrorsAreClassified() = runTest {
        suspend fun streamError(type: String): ModelError {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"$type\",\"message\":\"busy\"}}\n\n",
            ))
            return try {
                model().completeStreaming("s", user, emptyList()) {}
                fail("expected ModelError")
                error("unreachable")
            } catch (e: ModelError) { e }
        }

        assertTrue(streamError("rate_limit_error") is ModelError.RateLimited)
        val overloaded = streamError("overloaded_error") as ModelError.Server
        assertEquals(529, overloaded.status)
        assertTrue(overloaded.message.orEmpty().contains("busy"))
    }

    @Test fun marksCacheOnSystemAndTools() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        val tools = listOf(ToolSpec("a", "d", """{"type":"object"}"""), ToolSpec("b", "d", """{"type":"object"}"""))
        model().complete("SYS", user, tools)
        val j = sentBody()
        val sys = j.arr("system")[0].jsonObject
        assertEquals("SYS", sys.str("text"))
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject.str("type"))
        val t = j.arr("tools")
        assertFalse(t[0].jsonObject.containsKey("cache_control"))
        assertEquals("ephemeral", t[1].jsonObject["cache_control"]!!.jsonObject.str("type"))
        assertEquals("object", t[0].jsonObject["input_schema"]!!.jsonObject.str("type"))
    }

    // 对话只在最后一条 user 消息的最后一块上标缓存;前面的消息原样,两次请求的开头逐字相同
    @Test fun marksCacheOnLastUserMessage() = runTest {
        val first = listOf(
            Message(Role.User, listOf(Part.Text("goal")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("t1", "a", "{}")), Author.Mika),
            Message(Role.User, listOf(Part.ToolResult("t1", "r1", false), Part.Text("state 1")), Author.Mika),
        )
        val second = first + listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("t2", "a", "{}")), Author.Mika),
            Message(Role.User, listOf(Part.ToolResult("t2", "r2", false), Part.Text("state 2")), Author.Mika),
        )
        repeat(2) { server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}""")) }
        model().complete("SYS", first, emptyList())
        val a = sentBody().arr("messages")
        model().complete("SYS", second, emptyList())
        val b = sentBody().arr("messages")

        fun marked(ms: JsonArray) = ms.flatMapIndexed { i, m ->
            m.jsonObject.arr("content").mapIndexedNotNull { j, c -> if ("cache_control" in c.jsonObject) i to j else null }
        }
        assertEquals(listOf(2 to 1), marked(a))
        assertEquals(listOf(4 to 1), marked(b))
        assertEquals("ephemeral", b[4].jsonObject.arr("content")[1].jsonObject["cache_control"]!!.jsonObject.str("type"))
        // 上一次标记的那块在下一次请求里不带标记,其余逐字相同
        assertEquals(a[0], b[0]); assertEquals(a[1], b[1])
        assertEquals(a[2].jsonObject.arr("content")[0], b[2].jsonObject.arr("content")[0])
    }

    // 没有 uncached 消息时请求逐字不变(锁住加 uncached 之前的请求形状)
    @Test fun requestShapeWithoutUncachedIsUnchanged() = runTest {
        val history = listOf(
            Message(Role.User, listOf(Part.Text("goal")), Author.User),
            Message(Role.Assistant, listOf(Part.Text("ok"), Part.ToolCall("t1", "a", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "r1", false)), Author.Mika),
            Message(Role.User, listOf(Part.Text("state 1")), Author.Mika),
            Message(Role.Assistant, listOf(Part.Text("done")), Author.Mika),
        )
        val tools = listOf(ToolSpec("a", "d", """{"type":"object"}"""), ToolSpec("b", "d", """{"type":"object"}"""))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("SYS", history, tools)
        val raw = server.takeRequest().body.readUtf8()
        assertEquals(GOLDEN_ANTHROPIC, raw)
    }

    // 末尾的 uncached 消息不带移动标记:标记落在它前面最后一块能标的上;uncached 的块原样跟在后面、合进同一条 user 消息
    @Test fun trailingUncachedMessageShiftsMarkToPrecedingBlock() = runTest {
        val history = listOf(
            Message(Role.User, listOf(Part.Text("goal")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("t1", "a", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "r1", false)), Author.Mika),
        )
        val note = Message(Role.User, listOf(Part.Text("board v1")), Author.Mika, uncached = true)
        val tools = listOf(ToolSpec("a", "d", """{"type":"object"}"""))
        repeat(2) { server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}""")) }
        model().complete("SYS", history + note, tools)
        val j = sentBody()
        val ms = j.arr("messages")
        fun marked(ms: JsonArray) = ms.flatMapIndexed { i, m ->
            m.jsonObject.arr("content").mapIndexedNotNull { k, c -> if ("cache_control" in c.jsonObject) i to k else null }
        }
        assertEquals(listOf(2 to 0), marked(ms))
        assertEquals("tool_result", ms[2].jsonObject.arr("content")[0].jsonObject.str("type"))
        assertEquals("board v1", ms[2].jsonObject.arr("content")[1].jsonObject.str("text"))
        // 一共的断点不超过 4 个:工具、system、对话各一个
        assertEquals(3, j.toString().split("cache_control").size - 1)

        // 只有 uncached 前面一条 user 时,标在那条上;多条末尾 uncached 都跳过
        val notes = listOf(note, Message(Role.User, listOf(Part.Text("memory")), Author.Mika, uncached = true))
        model().complete("SYS", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User)) + notes, emptyList())
        val b = sentBody().arr("messages")
        assertEquals(listOf(0 to 0), marked(b))
        assertEquals(3, b[0].jsonObject.arr("content").size)
    }

    @Test fun toolResultIsUserMessageAndRolesAlternate() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        val history = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(Part.Thinking("t"), Part.Text("a"), Part.ToolCall("t1", "phone_tap", """{"ref":"x"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "done", false)), Author.Mika),
            Message(Role.User, listOf(Part.Text("then")), Author.Mika),
        )
        model().complete("s", history, emptyList())
        val msgs = sentBody().arr("messages")
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.jsonObject.str("role") })
        val asst = msgs[1].jsonObject.arr("content")
        assertEquals(listOf("text", "tool_use"), asst.map { it.jsonObject.str("type") })
        assertEquals("x", asst[1].jsonObject["input"]!!.jsonObject.str("ref"))
        val u = msgs[2].jsonObject.arr("content")
        assertEquals("tool_result", u[0].jsonObject.str("type"))
        assertEquals("t1", u[0].jsonObject.str("tool_use_id"))
        assertEquals("done", u[0].jsonObject.str("content"))
        assertEquals("false", u[0].jsonObject.str("is_error"))
        assertEquals("then", u[1].jsonObject.str("text"))
    }

    @Test fun imagesOnlyWhenVision() = runTest {
        val m = listOf(Message(Role.User, listOf(Part.Text("look"), Part.Image("QUJD")), Author.Mika))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(vision = true).complete("s", m, emptyList())
        val img = sentBody().arr("messages")[0].jsonObject.arr("content")[1].jsonObject
        assertEquals("image", img.str("type"))
        assertEquals("QUJD", img["source"]!!.jsonObject.str("data"))
        assertEquals("image/png", img["source"]!!.jsonObject.str("media_type"))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(vision = false).complete("s", m, emptyList())
        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains("\"image\""))
        assertTrue(raw.contains("screenshot omitted"))
    }

    @Test fun sendsJpegMime() = runTest {
        val m = listOf(Message(Role.User, listOf(Part.Image("QUJD", mime = "image/jpeg")), Author.Mika))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(vision = true).complete("s", m, emptyList())
        val img = sentBody().arr("messages")[0].jsonObject.arr("content")[0].jsonObject
        assertEquals("image/jpeg", img["source"]!!.jsonObject.str("media_type"))
    }

    @Test fun systemMessagesMergeIntoSystemText() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("S1", listOf(Message(Role.System, listOf(Part.Text("S2")), Author.Mika), user[0]), emptyList())
        val j = sentBody()
        assertEquals("S1\n\nS2", j.arr("system")[0].jsonObject.str("text"))
        assertEquals(1, j.arr("messages").size)
    }

    private suspend fun errorOf(status: Int, body: String): ModelError {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        try { model().complete("s", user, emptyList()) } catch (e: ModelError) { return e }
        fail("expected ModelError"); throw IllegalStateException()
    }

    @Test fun mapsErrors() = runTest {
        assertTrue(errorOf(401, "{}") is ModelError.Auth)
        assertTrue(errorOf(403, "{}") is ModelError.Auth)
        assertTrue(errorOf(404, "{}") is ModelError.ModelNotFound)
        assertEquals("model: claude-x", (errorOf(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-x"}}""") as ModelError.ModelNotFound).detail)
        assertTrue(errorOf(403, """{"error":{"type":"permission_error","message":"Your organization does not have access to the model"}}""") is ModelError.ModelNotAvailable)
        assertTrue(errorOf(413, "{}") is ModelError.ContextTooLong)
        assertTrue(errorOf(400, """{"error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens"}}""") is ModelError.ContextTooLong)
        // 官方接口「输入 + max_tokens」超出窗口(原话取自第三方客户端里的记录),和本机跑的服务开的同形接口:和 OpenAI 兼容接口同一条规则
        assertTrue(errorOf(400, """{"type":"error","error":{"type":"invalid_request_error","message":"input length and max_tokens exceed context limit: 199926 + 21333 > 200000"}}""") is ModelError.ContextTooLong)
        assertTrue(errorOf(400, """{"error":{"code":400,"message":"request (25123 tokens) exceeds the available context size (16384 tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":25123,"n_ctx":16384}}""") is ModelError.ContextTooLong)
        assertTrue(errorOf(400, """{"error":{"message":"Your credit balance is too low"}}""") is ModelError.QuotaExceeded)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "2").setBody("{}"))
        val rl = try { model().complete("s", user, emptyList()); null } catch (e: ModelError.RateLimited) { e }
        assertEquals(2000L, rl!!.retryAfterMs)
        assertEquals(529, (errorOf(529, """{"error":{"type":"overloaded_error"}}""") as ModelError.Server).status)
        assertEquals(502, (errorOf(502, "x") as ModelError.Server).status)
    }

    @Test fun usageInputIsTotalIncludingCache() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2,"cache_read_input_tokens":100,"cache_creation_input_tokens":20}}"""))
        assertEquals(Usage(125, 2, 100), model().complete("s", user, emptyList()).usage)
    }

    @Test fun omitsSystemWhenBlank() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("", user, emptyList())
        assertFalse(sentBody().containsKey("system"))
    }

    // 插话打断后:半截回复是只有一个文字块的 assistant 消息(没有思考块);插话和运行时状态并进下一条 user 消息,角色交替
    @Test fun interruptedReplyIsATextOnlyAssistantTurnBeforeTheSteer() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("s", InterruptedHistory.messages, emptyList())
        val msgs = sentBody().arr("messages").map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.str("role") })
        val asst = msgs[1].arr("content").map { it.jsonObject }
        assertEquals(listOf("text"), asst.map { it.str("type") })
        assertEquals(InterruptedHistory.PARTIAL, asst[0].str("text"))
        val texts = msgs[2].arr("content").map { it.jsonObject.str("text") }
        assertEquals(listOf(InterruptedHistory.STEER, InterruptedHistory.RUNTIME), texts)
    }

    private companion object {
        const val GOLDEN_ANTHROPIC =
            """{"model":"claude-x","max_tokens":4096,"system":[{"type":"text","text":"SYS","cache_control":{"type":"ephemeral"}}],"tools":[{"name":"a","description":"d","input_schema":{"type":"object"}},{"name":"b","description":"d","input_schema":{"type":"object"},"cache_control":{"type":"ephemeral"}}],"messages":[{"role":"user","content":[{"type":"text","text":"goal"}]},{"role":"assistant","content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"t1","name":"a","input":{}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"r1","is_error":false},{"type":"text","text":"state 1","cache_control":{"type":"ephemeral"}}]},{"role":"assistant","content":[{"type":"text","text":"done"}]}]}"""
    }
}
