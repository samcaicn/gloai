package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ModelCaller.lastRetryWaitMs] 从第一次真正发出请求起算(失败的请求、退避才算重试等待);
 * 开工到第一次发出之间的准备(压缩、冷却等待)不算。钟每取一次走 10ms,模型调用按 [advanceMs] 走钟。
 */
class ModelCallerRetryWaitTest {
    private class Scripted(
        private val t: () -> Long,
        private val advanceMs: Long,
        private val failFirst: ModelError?,
    ) : ChatModel {
        override val name = "retry-wait-scripted"
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        val sentAt = mutableListOf<Long>()
        var calls = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            sentAt += t()
            failFirst?.takeIf { calls == 1 }?.let { advance(); throw it }
            return answerReply("好")
        }
        private fun advance() { repeat((advanceMs / 10).toInt()) { t() } }
    }

    private var t = 0L
    private val now = { t += 10; t }
    private val history = listOf(Message(Role.User, listOf(Part.Text("查一下")), Author.User))

    private fun caller(model: ChatModel, retry: RetryPolicy) =
        ModelCaller(model, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, retry, {}, now).also { it.reset("") }

    @Test fun retryWaitStartsAtTheFirstRequest() = runTest {
        val model = Scripted(now, 100, ModelError.Server(500))
        val caller = caller(model, RetryPolicy(maxRetries = 1, jitter = { 0.0 }))
        caller.ask(history, emptyList(), step = 1)
        assertEquals(2, model.calls)
        // 两次真正发出之间走过的时间(失败那次的耗时 + 其间的退避);不含第一次发出之前的准备
        assertEquals(model.sentAt[1] - model.sentAt[0], caller.lastRetryWaitMs)
    }

    @Test fun firstAttemptSuccessReportsNoRetryWait() = runTest {
        val model = Scripted(now, 100, null)
        val caller = caller(model, RetryPolicy(maxRetries = 1, jitter = { 0.0 }))
        caller.ask(history, emptyList(), step = 1)
        assertEquals(1, model.calls)
        assertEquals(0, caller.lastRetryWaitMs)
    }
}
