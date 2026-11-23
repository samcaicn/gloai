package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps(vision: Boolean = false) = Capabilities(true, vision, 32000, false)
    private fun model(vision: Boolean = false, key: String? = "sk-x") =
        OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), key, "m", caps(vision), OkHttpClient())
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private fun ok(body: String) = MockResponse().setResponseData(body)
    private fun MockResponse.setResponseData(b: String) = setHeader("Content-Type", "application/json").setBody(b)

    private fun body(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    /** Key 里的换行或中文不能在构造请求时把进程打掉,也不能把 Key 发出去。 */
    @Test fun illegalApiKeyFailsBeforeAnyRequest() = runTest {
        val newline = runCatching { model(key = "sk-\nabc").complete("s", user, emptyList()) }.exceptionOrNull()
        val chinese = runCatching { model(key = "密钥：sk").complete("s", user, emptyList()) }.exceptionOrNull()
        assertTrue(newline is ModelError.IllegalHeader)
        assertTrue(chinese is ModelError.IllegalHeader)
        assertEquals(0, server.requestCount)
    }

    /** 密钥被拒的回调要带上 HTTP 状态码(接线处据此区分 429 冷却与 401/403 耗尽),并就地换下一把 key 重试。 */
    @Test fun keyRejectionCallbackCarriesStatusAndRotates() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(ok("""{"choices":[{"message":{"content":"hi"}}]}"""))
        val keys = ArrayDeque(listOf("sk-bad", "sk-good"))
        val rejected = mutableListOf<Triple<String, Long?, Int>>()
        val m = OpenAiCompatModel(
            server.url("/v1").toString().trimEnd('/'), "sk-static", "m", caps(), OkHttpClient(),
            keySupplier = { keys.removeFirstOrNull() },
            onKeyRejected = { key, retryMs, status -> rejected += Triple(key, retryMs, status) },
        )
        val r = m.complete("sys", user, emptyList())
        assertEquals(listOf(Triple("sk-bad", null, 401)), rejected)
        assertEquals("Bearer sk-bad", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer sk-good", server.takeRequest().getHeader("Authorization"))
        assertEquals("hi", r.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }

    @Test fun keyRejectionCallbackReportsRateLimitStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "7"))
        server.enqueue(ok("""{"choices":[{"message":{"content":"hi"}}]}"""))
        val keys = ArrayDeque(listOf("sk-a", "sk-b"))
        val rejected = mutableListOf<Triple<String, Long?, Int>>()
        val m = OpenAiCompatModel(
            server.url("/v1").toString().trimEnd('/'), null, "m", caps(), OkHttpClient(),
            keySupplier = { keys.removeFirstOrNull() },
            onKeyRejected = { key, retryMs, status -> rejected += Triple(key, retryMs, status) },
        )
        m.complete("sys", user, emptyList())
        assertEquals(listOf(Triple("sk-a", 7_000L, 429)), rejected)
    }

    @Test fun parsesToolCalls() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{\"ref\":\"a1\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":4}}}"""))
        val r = model().complete("sys", user, listOf(ToolSpec("phone_tap", "d", """{"type":"object"}""")))
        assertTrue(r.parts.contains(Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")))
        assertEquals(StopReason.ToolUse, r.stopReason)
        assertEquals(Usage(10, 3, 4), r.usage)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-x", req.getHeader("Authorization"))
        val j = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("system", j["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function", j["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun omitsToolsAndAuthWhenEmpty() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"length","message":{"content":"x"}}]}"""))
        val r = model(key = null).complete("s", user, emptyList())
        assertEquals(StopReason.MaxTokens, r.stopReason)
        assertNull(r.usage)
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertFalse(req.body.readUtf8().contains("\"tools\""))
    }

    /**
     * 非流式请求写明 `stream:false`、要一份 JSON;流式请求要 SSE。省掉 `stream` 时有的兼容端点(本地、局域网上的
     * 自建服务一类)照样回 SSE,客户端按 JSON 解析就只看到 `data: …`。
     */
    @Test fun nonStreamRequestAsksForJsonInsteadOfEvents() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("s", user, emptyList())
        val req = server.takeRequest()
        assertTrue(req.body.readUtf8().contains("\"stream\":false"))
        assertEquals("application/json", req.getHeader("Accept"))
    }

    /** 服务端不听 `stream:false`、照样回 SSE 时:按流读完再拼,不当成坏 JSON。 */
    @Test fun readsEventStreamReplyToANonStreamRequest() = runTest {
        val events = listOf(
            """{"choices":[{"delta":{"content":"先看"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"phone_tap","arguments":"{\"ref\":\"a1\"}"}}]},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events.joinToString("") { "data: $it\n\n" }),
        )
        val r = model().complete("s", user, tools)
        assertEquals(listOf(Part.Text("先看"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), r.parts)
        assertEquals(StopReason.ToolUse, r.stopReason)
    }

    /** 流式请求超长:回 400 的照常认;回 200、在事件流里给一帧错误的,说的是超长也认成超长,别的错误照旧。 */
    @Test fun contextTooLongIsRecognizedWhenStreaming() = runTest {
        suspend fun streamError(resp: MockResponse): ModelError {
            server.enqueue(resp)
            return try { model().completeStreaming("s", user, emptyList()) {}; throw AssertionError("no error") } catch (e: ModelError) { e }
        }
        assertTrue(streamError(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"request (25123 tokens) exceeds the available context size (16384 tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":25123,"n_ctx":16384}}""")) is ModelError.ContextTooLong)
        fun frame(message: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"error\":{\"object\":\"error\",\"message\":\"$message\",\"type\":\"BadRequestError\",\"param\":null,\"code\":400}}\n\ndata: [DONE]\n\n")
        assertTrue(streamError(frame("The input (25123 tokens) is longer than the model's context length (16384 tokens).")) is ModelError.ContextTooLong)
        assertTrue(streamError(frame("bad input")) is ModelError.BadResponse)
    }

    @Test fun streamingRateLimitAndOverloadErrorsAreClassified() = runTest {
        suspend fun streamError(type: String): ModelError {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"error\":{\"type\":\"$type\",\"message\":\"busy\"}}\n\ndata: [DONE]\n\n",
            ))
            return try {
                model().completeStreaming("s", user, emptyList()) {}
                throw AssertionError("expected ModelError")
            } catch (e: ModelError) { e }
        }

        assertTrue(streamError("rate_limit_error") is ModelError.RateLimited)
        val overloaded = streamError("overloaded_error") as ModelError.Server
        assertEquals(529, overloaded.status)
        assertTrue(overloaded.message.orEmpty().contains("busy"))
    }

    /** 整份回复已经收全,只是没发 `[DONE]`:照常说完,不当成流提前断了。 */
    @Test fun eventStreamWithoutDoneStillFinishes() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n"),
        )
        val r = model().complete("s", user, emptyList())
        assertEquals(listOf(Part.Text("好")), r.parts)
        assertEquals(StopReason.EndTurn, r.stopReason)
    }

    /** 没有参数的工具,有的适配器给 `""`:当成 `{}`,免得调用方按 JSON 解析失败。 */
    @Test fun blankToolCallArgumentsBecomeEmptyObject() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_time","arguments":""}}]}}]}"""))
        val r = model().complete("s", user, tools)
        assertEquals(Part.ToolCall("c1", "get_time", "{}"), r.parts.filterIsInstance<Part.ToolCall>().single())
    }

    @Test fun readsReasoningContent() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok","reasoning_content":"想一想"}}]}"""))
        val r = model().complete("s", user, emptyList())
        assertEquals("想一想", r.parts.filterIsInstance<Part.Thinking>().single().text)
        assertTrue(r.parts.contains(Part.Text("ok")))
        assertEquals(StopReason.EndTurn, r.stopReason)
    }

    private val withImage = listOf(Message(Role.User, listOf(Part.Text("look"), Part.Image("QUJD")), Author.Mika))

    @Test fun sendsImagesOnlyWhenVision() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model(vision = false).complete("s", withImage, emptyList())
        val off = server.takeRequest().body.readUtf8()
        assertFalse(off.contains("image_url"))
        assertTrue(off.contains("[screenshot omitted: the current model cannot see images]"))

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model(vision = true).complete("s", withImage, emptyList())
        assertTrue(server.takeRequest().body.readUtf8().contains("data:image/png;base64,QUJD"))
    }

    @Test fun sendsJpegMime() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val jpeg = listOf(Message(Role.User, listOf(Part.Image("QUJD", mime = "image/jpeg")), Author.Mika))
        model(vision = true).complete("s", jpeg, emptyList())
        assertTrue(server.takeRequest().body.readUtf8().contains("data:image/jpeg;base64,QUJD"))
    }

    private suspend fun errorFor(resp: MockResponse): ModelError {
        server.enqueue(resp)
        return try { model().complete("s", user, emptyList()); throw AssertionError("no error") } catch (e: ModelError) { e }
    }

    @Test fun mapsErrors() = runTest {
        assertTrue(errorFor(MockResponse().setResponseCode(401)) is ModelError.Auth)
        assertTrue(errorFor(MockResponse().setResponseCode(402)) is ModelError.QuotaExceeded)
        assertTrue(errorFor(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"insufficient_quota"}}""")) is ModelError.QuotaExceeded)
        val rl = errorFor(MockResponse().setResponseCode(429).setHeader("retry-after", "2")) as ModelError.RateLimited
        assertEquals(2000L, rl.retryAfterMs)
        val rlBody = errorFor(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached. Please try again in 15s."}}""")) as ModelError.RateLimited
        assertEquals(15000L, rlBody.retryAfterMs)
        assertTrue(errorFor(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached, limit: 60"}}""")) is ModelError.RateLimited)
        // 这个模型额度为零(Gemini 的 OpenAI 兼容入口回数组):换模型才行
        val zero = errorFor(MockResponse().setResponseCode(429).setBody("""[{"error":{"code":429,"message":"Quota exceeded for metric: free_tier_requests, limit: 0","status":"RESOURCE_EXHAUSTED"}}]"""))
        assertTrue(zero is ModelError.ModelNotAvailable)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("context_length_exceeded")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("exceeds the maximum context")) is ModelError.ContextTooLong)
        // 本机跑的服务说超出上下文的原话(第二句是它不能切分的请求):说上下文的大小、长度,不说 context_length
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"request (25123 tokens) exceeds the available context size (16384 tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":25123,"n_ctx":16384}}""")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"input (25123 tokens) is larger than the max context size (16384 tokens). skipping","type":"exceed_context_size_error","n_prompt_tokens":25123,"n_ctx":16384}}""")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"object":"error","message":"The input (25123 tokens) is longer than the model's context length (16384 tokens).","type":"BadRequestError","param":null,"code":400}""")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Your input exceeds the context window of this model. Please adjust your input and try again."}}""")) is ModelError.ContextTooLong)
        // 带图的请求在服务端展开成图片 token 后才超:说的是模型长度
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"The decoder prompt (length 25123) is longer than the maximum model length of 16384. Make sure that `max_model_len` is no smaller than the number of text tokens plus multimodal tokens. For image inputs, the number of image tokens depends on the number of images, and possibly their aspect ratios as well.","type":"BadRequestError","param":null,"code":400}}""")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":"Trying to keep the first 15857 tokens when context the overflows. However, the model is loaded with context length of only 4096 tokens, which is not enough. Try to load the model with a larger context length, or provide a shorter input. Error Data: n/a, Additional Data: n/a"}""")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":"The number of tokens to keep from the initial prompt is greater than the context length. Try to load the model with a larger context length, or provide a shorter input. Error Data: n/a, Additional Data: n/a"}""")) is ModelError.ContextTooLong)
        // 提到 context、说的却不是超长:照旧;报错里回显了请求里的「context window」也不算
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid value for 'context': expected a string"}}""")) is ModelError.Server)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid type for 'messages[1].content': expected a string","input":"how big is your context window?"}}""")) is ModelError.Server)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid 'tools[3].function.name': string too long. Expected a string with maximum length 64, but got a string with length 67 instead."}}""")) is ModelError.Server)
        assertEquals("m", (errorFor(MockResponse().setResponseCode(404)) as ModelError.ModelNotFound).model)
        // 网关回的 404 不一定是模型名不对:带上它 JSON 里的原话;HTML 页不当原话
        val gateway = errorFor(MockResponse().setResponseCode(404).setBody("""{"error":{"type":"not_found_error","message":"none of group/rpa's models is ready"}}""")) as ModelError.ModelNotFound
        assertEquals("none of group/rpa's models is ready", gateway.detail)
        assertEquals("", (errorFor(MockResponse().setResponseCode(404).setBody("<html><body>404 Not Found</body></html>")) as ModelError.ModelNotFound).detail)
        assertEquals(503, (errorFor(MockResponse().setResponseCode(503)) as ModelError.Server).status)
        // 这个账号没开通这个模型:不当成 Key 或请求的问题
        val na = errorFor(MockResponse().setResponseCode(400).setBody("""{"code":"InvalidParameter","message":"The product is not activated."}""")) as ModelError.ModelNotAvailable
        assertEquals("m", na.model)
        assertEquals("The product is not activated.", na.detail)
        assertTrue(errorFor(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"not authorized to access this model"}}""")) is ModelError.ModelNotAvailable)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"m does not support tools"}}""")) is ModelError.ToolsUnsupported)
        assertTrue(errorFor(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) is ModelError.Network)
    }

    @Test fun badResponses() = runTest {
        assertTrue(errorFor(ok("not json")) is ModelError.BadResponse)
        assertTrue(errorFor(ok("""{"choices":[]}""")) is ModelError.BadResponse)
        assertTrue(errorFor(ok("""{"foo":1}""")) is ModelError.BadResponse)
    }

    @Test fun toolResultRoundTrip() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val hist = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(Part.Thinking("secret"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Mika),
        )
        model().complete("s", hist, emptyList())
        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains("secret"))
        val msgs: JsonArray = Json.parseToJsonElement(raw).jsonObject["messages"]!!.jsonArray
        val asst = msgs[2].jsonObject
        assertEquals("assistant", asst["role"]!!.jsonPrimitive.content)
        assertEquals("c1", asst["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("""{"ref":"a1"}""", asst["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val tool = msgs[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("c1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("done", tool["content"]!!.jsonPrimitive.content)
    }

    private val tools = listOf(ToolSpec("phone_tap", "d", """{"type":"object"}"""))
    private val endpoint get() = server.url("/v1/chat/completions").toString()
    private fun texts(parts: List<Part>) = parts.map { if (it is Part.Thinking) it.text else it }

    /** 任何地址收到的 reasoning_content:带工具的请求原样放回同一个字段;别处来的思考不发;不带工具不回传。 */
    @Test fun reasoningContentReceivedHereIsReplayedWithTools() = runTest {
        val m = model()
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"reasoning_content":"先找入口","tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{}"}}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertEquals(listOf<Any>("先找入口", Part.ToolCall("c1", "phone_tap", "{}")), texts(first.parts))
        server.takeRequest()

        val history = user + listOf(
            Message(Role.Assistant, first.parts, Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Mika),
            // 来历不明的(别的适配器给的、旧版本存下的)不发
            Message(Role.Assistant, listOf(Part.Thinking("别处想的"), Part.Text("已打开")), Author.Mika),
            Message(Role.User, listOf(Part.Text("再看一下")), Author.User),
        )
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", history, tools)
        val messages = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("先找入口", messages[2]["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse("reasoning" in messages[2])
        assertEquals(JsonNull, messages[2]["content"])
        assertEquals("c1", messages[2]["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content" in messages[4])

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", history, emptyList())
        assertFalse("reasoning_content" in body()["messages"]!!.jsonArray[2].jsonObject)
    }

    /** 思考在 `reasoning` 字段里收到的,放回 `reasoning`(不改成 reasoning_content)。 */
    @Test fun reasoningFieldIsReplayedUnderItsOwnName() = runTest {
        val m = model()
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":"","reasoning":"想想","tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{}"}}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertEquals("想想", (first.parts.first() as Part.Thinking).text)
        server.takeRequest()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", user + Message(Role.Assistant, first.parts, Author.Mika) +
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Mika), tools)
        val assistant = body()["messages"]!!.jsonArray[2].jsonObject
        assertEquals("想想", assistant["reasoning"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content" in assistant)
    }

    /** 另一个地址收到的思考(换了服务商)不发给这一家:它可能不认这个字段。 */
    @Test fun reasoningReceivedFromAnotherEndpointIsNotSent() = runTest {
        val elsewhere = Part.Thinking("那边想的", ReasoningVia.ReasoningContent.tag("https://other.example/v1/chat/completions"))
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("s", user + Message(Role.Assistant, listOf(elsewhere, Part.Text("next")), Author.Mika), tools)
        val assistant = body()["messages"]!!.jsonArray[2].jsonObject
        assertFalse("reasoning_content" in assistant)
        assertFalse("reasoning" in assistant)
        // 同一个地址收到的就发
        val here = Part.Thinking("这边想的", ReasoningVia.ReasoningContent.tag(endpoint))
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("s", user + Message(Role.Assistant, listOf(here, Part.Text("next")), Author.Mika), tools)
        assertEquals("这边想的", body()["messages"]!!.jsonArray[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    /** 模型列表写明了思考字段的,来历不明的思考(旧版本存下的历史)也按它回传。 */
    @Test fun modelsListedWithReasoningContentFieldReplayItWithTools() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "kimi-k3", caps(), OkHttpClient(), "reasoning_content")
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val history = user + Message(Role.Assistant, listOf(Part.Thinking("先想"), Part.Text("next")), Author.Mika)
        m.complete("s", history, tools)
        assertEquals("先想", body()["messages"]!!.jsonArray[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    /** `content` 是分块数组:文字块是回答、thinking 块是思考;回放时整条按分块放回,思考块在前。 */
    @Test fun chunkedContentIsParsedAndReplayedAsChunks() = runTest {
        val m = model()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"先想"},{"type":"text","text":"再想"}]},{"type":"text","text":"答案"}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertEquals(listOf<Any>("先想再想", Part.Text("答案")), texts(first.parts))
        server.takeRequest()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", user + Message(Role.Assistant, first.parts, Author.Mika) + Message(Role.User, listOf(Part.Text("接着")), Author.User), tools)
        val content = body()["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("thinking", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("先想再想", content[0]["thinking"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("text", content[1]["type"]!!.jsonPrimitive.content)
        assertEquals("答案", content[1]["text"]!!.jsonPrimitive.content)
    }

    @Test fun readsPromptCacheHitTokens() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":10,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 10, 640), model().complete("sys", user, emptyList()).usage)
        // 缺 prompt_tokens 时用命中 + 未命中之和
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"completion_tokens":10,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 10, 640), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun readsCachedTokensDetails() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":128}}}"""))
        assertEquals(Usage(500, 7, 128), model().complete("sys", user, emptyList()).usage)
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7}}"""))
        assertEquals(Usage(500, 7, 0), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun readsReasoningTokensDetails() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":70,"completion_tokens_details":{"reasoning_tokens":64}}}"""))
        assertEquals(Usage(500, 70, 0, reasoningTokens = 64), model().complete("sys", user, emptyList()).usage)
        // 报得比输出还多的按输出截
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7,"completion_tokens_details":{"reasoning_tokens":64}}}"""))
        assertEquals(Usage(500, 7, 0, reasoningTokens = 7), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun cachedTakesLargerFieldClampedToInput() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":100},"prompt_cache_hit_tokens":640}}"""))
        assertEquals(Usage(900, 1, 640), model().complete("sys", user, emptyList()).usage)
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":1000}}}"""))
        assertEquals(Usage(900, 1, 900), model().complete("sys", user, emptyList()).usage)
    }

    // 显式缓存:系统提示词和最后一条能标的消息上带 cache_control;前面的消息原样,两次请求的开头逐字相同
    @Test fun explicitCachingMarksSystemAndLastMessage() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m",
            Capabilities(true, true, 32000, false, promptCaching = PromptCaching.Explicit), OkHttpClient())
        val history = listOf(
            Message(Role.User, listOf(Part.Text("任务")), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "屏幕", false)), Author.Mika),
        )
        val reply = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":3000,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":2048,"cache_creation_input_tokens":900}}}"""
        server.enqueue(ok(reply)); server.enqueue(ok(reply))
        val r = m.complete("SYS", history, emptyList())
        assertEquals(Usage(3000, 5, 2048), r.usage)
        val first = body()["messages"]!!.jsonArray.map { it.jsonObject }
        fun marks(msgs: List<kotlinx.serialization.json.JsonObject>) = msgs.mapIndexedNotNull { i, msg ->
            (msg["content"] as? JsonArray)?.takeIf { c -> c.any { "cache_control" in it.jsonObject } }?.let { i }
        }
        assertEquals(listOf(0, 3), marks(first))
        assertEquals("ephemeral", first[0]["content"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("屏幕", first[3]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("tool", first[3]["role"]!!.jsonPrimitive.content)

        // 下一步:多了一轮;标记挪到新的最后一条,之前那条恢复原样(服务端按内容找开头,标记本身不算内容)
        m.complete("SYS", history + Message(Role.Assistant, listOf(Part.ToolCall("c2", "phone_tap", "{}")), Author.Mika) +
            Message(Role.Tool, listOf(Part.ToolResult("c2", "点了", false)), Author.Mika), emptyList())
        val second = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 5), marks(second))
        assertEquals(first.subList(1, 3), second.subList(1, 3))
    }

    // 没有 uncached 消息时请求逐字不变(锁住加 uncached 之前的请求形状),显式、隐式缓存都看
    @Test fun requestShapeWithoutUncachedIsUnchanged() = runTest {
        val history = listOf(
            Message(Role.User, listOf(Part.Text("goal")), Author.User),
            Message(Role.Assistant, listOf(Part.Text("ok"), Part.ToolCall("t1", "a", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "r1", false)), Author.Mika),
            Message(Role.User, listOf(Part.Text("state 1")), Author.Mika),
            Message(Role.Assistant, listOf(Part.Text("done")), Author.Mika),
        )
        val tools = listOf(ToolSpec("a", "d", """{"type":"object"}"""), ToolSpec("b", "d", """{"type":"object"}"""))
        val explicit = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m",
            Capabilities(true, true, 32000, false, promptCaching = PromptCaching.Explicit), OkHttpClient())
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        explicit.complete("SYS", history, tools)
        val rawExplicit = server.takeRequest().body.readUtf8()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("SYS", history, tools)
        val rawImplicit = server.takeRequest().body.readUtf8()
        assertEquals(GOLDEN_OPENAI_EXPLICIT, rawExplicit)
        assertEquals(GOLDEN_OPENAI_IMPLICIT, rawImplicit)
    }

    // 末尾的 uncached 消息不带移动标记:标记落在它前面最后一条能标的消息上,uncached 那条原样发
    @Test fun explicitCachingSkipsTrailingUncachedMessages() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m",
            Capabilities(true, true, 32000, false, promptCaching = PromptCaching.Explicit), OkHttpClient())
        val history = listOf(
            Message(Role.User, listOf(Part.Text("任务")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "a", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "屏幕", false)), Author.Mika),
        )
        val notes = listOf(
            Message(Role.User, listOf(Part.Text("board v1")), Author.Mika, uncached = true),
            Message(Role.User, listOf(Part.Text("memory")), Author.Mika, uncached = true),
        )
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("SYS", history + notes, emptyList())
        val sent = body()["messages"]!!.jsonArray.map { it.jsonObject }
        val marks = sent.mapIndexedNotNull { i, msg ->
            (msg["content"] as? JsonArray)?.takeIf { c -> c.any { "cache_control" in it.jsonObject } }?.let { i }
        }
        assertEquals(listOf(0, 3), marks)
        assertEquals("tool", sent[3]["role"]!!.jsonPrimitive.content)
        assertEquals(listOf("board v1", "memory"), sent.drop(4).map { it["content"]!!.jsonPrimitive.content })
    }

    @Test fun implicitCachingSendsNoMarks() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("SYS", user, emptyList())
        assertFalse(body().toString().contains("cache_control"))
    }

    @Test fun sendsReasoningEffortOnlyWhenChosen() = runTest {
        val reply = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
        server.enqueue(ok(reply))
        OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "sk-x", "m", caps(), OkHttpClient(), reasoningEffort = "high")
            .complete("s", user, emptyList())
        assertEquals("high", body()["reasoning_effort"]!!.jsonPrimitive.content)

        server.enqueue(ok(reply))
        model().complete("s", user, emptyList())
        assertFalse(body().containsKey("reasoning_effort"))
    }

    // 插话打断后:半截回复是一条只有文字的 assistant 消息,不带调用和思考字段;后面两条 user 消息各自原样发出
    @Test fun interruptedReplyIsAPlainAssistantMessageBeforeTheSteer() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"好"}}]}"""))
        model().complete("s", InterruptedHistory.messages, emptyList())
        val msgs = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(setOf("role", "content"), msgs[2].keys)
        assertEquals(InterruptedHistory.PARTIAL, msgs[2]["content"]!!.jsonPrimitive.content)
        assertEquals(InterruptedHistory.STEER, msgs[3]["content"]!!.jsonPrimitive.content)
        assertEquals(InterruptedHistory.RUNTIME, msgs[4]["content"]!!.jsonPrimitive.content)
    }

    private companion object {
        const val GOLDEN_OPENAI_EXPLICIT =
            """{"model":"m","messages":[{"role":"system","content":[{"type":"text","text":"SYS","cache_control":{"type":"ephemeral"}}]},{"role":"user","content":"goal"},{"role":"assistant","content":"ok","tool_calls":[{"id":"t1","type":"function","function":{"name":"a","arguments":"{}"}}]},{"role":"tool","tool_call_id":"t1","content":"r1"},{"role":"user","content":[{"type":"text","text":"state 1","cache_control":{"type":"ephemeral"}}]},{"role":"assistant","content":"done"}],"tools":[{"type":"function","function":{"name":"a","description":"d","parameters":{"type":"object"}}},{"type":"function","function":{"name":"b","description":"d","parameters":{"type":"object"}}}],"stream":false}"""
        const val GOLDEN_OPENAI_IMPLICIT =
            """{"model":"m","messages":[{"role":"system","content":"SYS"},{"role":"user","content":"goal"},{"role":"assistant","content":"ok","tool_calls":[{"id":"t1","type":"function","function":{"name":"a","arguments":"{}"}}]},{"role":"tool","tool_call_id":"t1","content":"r1"},{"role":"user","content":"state 1"},{"role":"assistant","content":"done"}],"tools":[{"type":"function","function":{"name":"a","description":"d","parameters":{"type":"object"}}},{"type":"function","function":{"name":"b","description":"d","parameters":{"type":"object"}}}],"stream":false}"""
    }
}
