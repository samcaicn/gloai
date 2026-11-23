package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.run.record.RunSummary
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopHistoryHintTest {
    private val records = object : RunRecords {
        override suspend fun summary(runId: String) = RunSummary(runId, "goal", startedAtMs = 0)
        override suspend fun events(runId: String) = emptyList<RunEvent>()
    }

    private fun firstMessage(prior: List<Turn>, withRecords: Boolean): String {
        val model = FakeModel(answerReply("好的"))
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("now"), "继续", "thread", priorTurns = prior),
            runRecords = records.takeIf { withRecords })
        runBlocking { AgentLoop(model, tools, "B").run("继续", prior) }
        return model.requests.first().withoutRuntime().first().plainText()
    }

    @Test fun earlierTurnsMentionTheHistoryToolWhenRecordsCanBeRead() {
        val prior = listOf(Turn("查三家店的价格", "已取消", "cancelled", runId = "run-a"))
        val hint = Feedback.format("history_hint", "turns" to "").trim()
        val text = firstMessage(prior, withRecords = true)
        assertTrue(text.startsWith(Conversation.HEADER))
        assertTrue(text.endsWith(hint))
        assertFalse(firstMessage(prior, withRecords = false).contains(hint))
        // 之前的轮次都没记过运行:不提
        assertFalse(firstMessage(listOf(Turn("你好", "你好!", "succeeded")), withRecords = true).contains(hint))
    }
}
