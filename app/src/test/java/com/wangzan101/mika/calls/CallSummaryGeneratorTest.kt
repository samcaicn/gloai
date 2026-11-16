package com.wangzan101.mika.calls

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallSummaryGeneratorTest {
    private class FakeModel(private val response: String) : ChatModel {
        override val capabilities = Capabilities(false, false, 4096, false)
        var received = ""
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            received = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString { it.text }
            assertTrue(system.contains("请求不能写成已达成的承诺"))
            assertTrue(tools.isEmpty())
            return ModelReply(listOf(Part.Text(response)), StopReason.EndTurn, null)
        }
    }

    @Test fun parsesStructuredSummaryAndLimitsUntrustedOutput() = runBlocking {
        val model = FakeModel("""摘要如下：{"purpose":"确认维修时间","highlights":["周五上门"],"conclusion":"尚未预约","uncertainties":["具体时间待确认"],"actions":[{"text":"回复确认时间","status":"PENDING"}]}""")
        val result = CallSummaryGenerator.generate(model, listOf(CallTranscriptLine("caller", "请周五联系我，具体时间再确认。")))
        assertEquals("确认维修时间", result?.purpose)
        assertEquals(CallFollowUp.Status.PENDING, result?.actions?.single()?.status)
        assertTrue(model.received.contains("请周五联系我"))
    }

    @Test fun generatesSummaryAndDraftInOneRequestAndBoundsDraft() = runBlocking {
        var calls = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(false, false, 4096, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                assertTrue(tools.isEmpty())
                assertTrue(system.contains("不能将对方的承诺写成主人的承诺"))
                return ModelReply(listOf(Part.Text("""{"purpose":"确认报价需求","followUpDraft":"  ${"字".repeat(800)}  "}""")), StopReason.EndTurn, null)
            }
        }
        val result = CallSummaryGenerator.generate(model, listOf(CallTranscriptLine("caller", "请发报价，时间再确认")))!!
        assertEquals(1, calls)
        assertEquals("确认报价需求", result.purpose)
        assertEquals(CallHistory.MAX_DRAFT_LENGTH, result.followUpDraft!!.length)
    }

    @Test fun absentBlankOrMalformedDraftKeepsSummaryWithoutInventingMessage() = runBlocking {
        for (field in listOf("", ",\"followUpDraft\":null", ",\"followUpDraft\":\"  \"", ",\"followUpDraft\":{}")) {
            val result = CallSummaryGenerator.generate(FakeModel("""{"purpose":"寒暄"$field}"""), listOf(CallTranscriptLine("caller", "你好")))!!
            assertEquals("寒暄", result.purpose)
            assertNull(result.followUpDraft)
        }
    }

    @Test fun outgoingSummaryNamesTheOtherPartyAsRecipient() = runBlocking {
        val model = FakeModel("{}")
        CallSummaryGenerator.generate(model, listOf(CallTranscriptLine("caller", "你好")), CallDirection.OUTGOING)
        assertTrue(model.received.startsWith("接听方:"))
    }

    @Test fun malformedReplyFailsWithoutInventingFields() = runBlocking {
        assertNull(CallSummaryGenerator.generate(FakeModel("这通电话很好。"), listOf(CallTranscriptLine("caller", "你好"))))
    }

    @Test fun emptyTranscriptDoesNotCallModel() = runBlocking {
        val model = FakeModel("{}")
        assertNull(CallSummaryGenerator.generate(model, emptyList()))
        assertTrue(model.received.isEmpty())
    }

    @Test fun currentConsentIsRequiredBeforeSubmittingTranscript() = runBlocking {
        val model = FakeModel("""{"purpose":"维修"}""")
        val transcript = listOf(CallTranscriptLine("caller", "请安排维修"))
        var enabled = false
        assertNull(CallSummaryGenerator.generate(model, transcript, canSend = { enabled }))
        assertTrue(model.received.isEmpty())

        enabled = true
        assertEquals("维修", CallSummaryGenerator.generate(model, transcript, canSend = { enabled })?.purpose)
        assertTrue(model.received.contains("请安排维修"))
    }
}
