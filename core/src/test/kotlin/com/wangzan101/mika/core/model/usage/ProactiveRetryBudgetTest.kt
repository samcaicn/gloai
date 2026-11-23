package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 主动能力的请求次数上限按实际发出的 HTTP 请求算。适配器在一次调用里会自己再发(这里用的是不认 include 参数的服务:
 * 第一个请求 400,去掉参数再发一个),这些要等调用结束才记进账本——再发之前得把它们加上再查一次上限。
 */
class ProactiveRetryBudgetTest {
    private val limit = ProactiveBudget.DEFAULT_DAILY_LIMIT
    private val cap = ProactiveBudget.callLimit(limit)
    private val ok = "data: " + """{"type":"response.output_text.delta","delta":"ok"}""" + "\n\n" +
        "data: " + """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}""" + "\n\n"
    private val rejectsInclude = """{"error":{"param":"include","message":"reasoning.encrypted_content unsupported"}}"""

    private class Run(val server: MockWebServer, var calls: Long, limit: Long) {
        var checks = 0
        var retryChecks = 0
        private val sink = object : UsageSink {
            override fun record(source: UsageSource, usage: Usage?) = Unit
            override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) { calls += httpAttempts }
        }
        val model = ResponsesModel(
            server.url("/v1").toString().trimEnd('/'), BearerToken.fixed("local-test-key"), "test-model",
            Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false), OkHttpClient(),
        ).metered(
            UsageSource("p", "local test", "test-model", UsagePurpose.Proactive), sink,
            beforeCall = { checks++; ProactiveBudget.check(0, limit, calls) },
            beforeRetry = { _, started -> retryChecks++; ProactiveBudget.check(0, limit, calls + started) },
        )
        suspend fun ask() = model.complete("s", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)), emptyList())
    }

    // 只剩最后一次额度:第一个请求发出去了,服务不认参数要再发一个——不发,次数停在上限上
    @Test fun aRetryInsideOneCallDoesNotGoPastTheLastRemainingRequest() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody(rejectsInclude))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ok))
            val run = Run(server, cap - 1, limit)
            try {
                run.ask()
                fail("should have stopped at the cap")
            } catch (e: ModelError.ProactiveBudgetExceeded) {
                assertTrue(e.byCalls)
            }
            assertEquals(1, server.requestCount)
            assertEquals("发出去的那一个照实记账", cap, run.calls)
            assertEquals(1, run.checks)
            assertEquals(1, run.retryChecks)
        } finally {
            server.shutdown()
        }
    }

    // 额度够的时候照常重发,两个请求都记账
    @Test fun aRetryStillGoesOutWhileThereIsRoom() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody(rejectsInclude))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ok))
            val run = Run(server, cap - 2, limit)
            run.ask()
            assertEquals(2, server.requestCount)
            assertEquals(cap, run.calls)
            assertEquals(1, run.retryChecks)
        } finally {
            server.shutdown()
        }
    }
}
