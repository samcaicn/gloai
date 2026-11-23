package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.SignedThinking
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 计量必须跟着适配器真实发出的请求走,不能只数最外层 complete 被调了几次。 */
class MeteredHttpTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = true)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private val source = UsageSource("p1", "服务商甲", "m1", UsagePurpose.Main)

    private data class Entry(val usage: Usage?, val attempts: Int)

    private class RecordingSink : UsageSink {
        val entries = mutableListOf<Entry>()
        override fun record(source: UsageSource, usage: Usage?) = record(source, usage, 1)
        override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) {
            entries += Entry(usage, httpAttempts)
        }
    }

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun openAi() = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
    private fun responses(token: BearerToken = BearerToken.fixed("k")) =
        ResponsesModel(server.url("/v1").toString().trimEnd('/'), token, "m", caps, OkHttpClient())

    private fun stream(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private fun openAiStream() = stream(
        """{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""",
        """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":4}}""",
        "[DONE]",
    )

    private fun openAiBody() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":2}}""",
    )

    private fun responsesStream() = stream(
        """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":12,"output_tokens":4}}}""",
    )

    private fun assertOneCall(
        sink: RecordingSink, tally: RunUsageTally, attempts: Int, unreported: Int, usage: Usage?,
    ) {
        assertEquals(listOf(Entry(usage, attempts)), sink.entries)
        val event = tally.event(0)
        assertEquals(1, event.calls)
        assertEquals(attempts, event.httpCalls)
        assertEquals(if (usage == null) 1 else 0, event.unreported)
        assertEquals(unreported, event.httpUnreported)
        assertEquals(usage?.inputTokens?.toLong() ?: 0, event.inputTokens)
        assertEquals(usage?.outputTokens?.toLong() ?: 0, event.outputTokens)
    }

    @Test fun streamOptionsRetryCountsBothRequestsAndFinalUsageOnce() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("unknown field stream_options"))
        server.enqueue(openAiStream())
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = openAi().metered(source, sink) as StreamingChatModel

        withContext(tally) { model.completeStreaming("s", user, emptyList()) {} }

        assertOneCall(sink, tally, attempts = 2, unreported = 1, Usage(12, 4))
        assertEquals(2, server.requestCount)
    }

    @Test fun nonStreamingFallbackCountsBothRequestsAndFinalUsageOnce() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("stream is not supported"))
        server.enqueue(openAiBody())
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = openAi().metered(source, sink) as StreamingChatModel

        withContext(tally) { model.completeStreaming("s", user, emptyList()) {} }

        assertOneCall(sink, tally, attempts = 2, unreported = 1, Usage(8, 2))
        assertEquals(2, server.requestCount)
    }

    @Test fun tokenRefreshRetryCountsBothRequests() = runTest {
        val token = object : BearerToken {
            override val refreshable = true
            override suspend fun get(fresh: Boolean): String = if (fresh) "new" else "old"
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(responsesStream())
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = responses(token).metered(source, sink)

        withContext(tally) { model.complete("s", user, emptyList()) }

        assertOneCall(sink, tally, attempts = 2, unreported = 1, Usage(12, 4))
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun reasoningCompatibilityRetryCountsBothRequests() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"unsupported_parameter","param":"include","message":"no"}}""",
        ))
        server.enqueue(responsesStream())
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = responses().metered(source, sink)

        withContext(tally) { model.complete("s", user, emptyList()) }

        assertOneCall(sink, tally, attempts = 2, unreported = 1, Usage(12, 4))
    }

    @Test fun rejectedThinkingRetryCountsBothRequests() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        val endpoint = "$base/v1/messages"
        val history = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(
                Part.Thinking("想", SignedThinking.signed(endpoint, "sig")), Part.ToolCall("t1", "phone_tap", "{}"),
            ), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "done", false)), Author.Mika),
        )
        server.enqueue(MockResponse().setResponseCode(400).setBody("bound to a different conversation"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"content":[{"type":"text","text":"好"}],"stop_reason":"end_turn","usage":{"input_tokens":9,"output_tokens":3}}""",
        ))
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = AnthropicModel(base, "k", "m", caps, OkHttpClient()).metered(source, sink)

        withContext(tally) { model.complete("s", history, emptyList()) }

        assertOneCall(sink, tally, attempts = 2, unreported = 1, Usage(9, 3))
    }

    @Test fun separateOuterRetriesAreNotDoubleCounted() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        server.enqueue(openAiBody())
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = openAi().metered(source, sink)

        withContext(tally) {
            runCatching { model.complete("s", user, emptyList()) }
            model.complete("s", user, emptyList())
        }

        assertEquals(listOf(1, 1), sink.entries.map { it.attempts })
        val event = tally.event(0)
        assertEquals(2, event.calls)
        assertEquals(2, event.httpCalls)
        assertEquals(1, event.unreported)
        assertEquals(1, event.httpUnreported)
        assertEquals(8L, event.inputTokens)
        assertEquals(2L, event.outputTokens)
    }

    @Test fun cancellationDuringHttpKeepsTheStartedAttempt() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val sink = RecordingSink()
        val tally = RunUsageTally()
        val model = openAi().metered(source, sink)

        val job = launch(Dispatchers.IO) {
            withContext(tally) { model.complete("s", user, emptyList()) }
        }
        val sent = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertNotNull("request did not reach the server", sent)
        job.cancelAndJoin()

        assertOneCall(sink, tally, attempts = 1, unreported = 1, usage = null)
    }
}
