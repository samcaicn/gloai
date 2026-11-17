package com.wangzan101.mika.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CallHistoryTest {
    private class MemoryStorage(var records: List<CallRecord> = emptyList()) : CallHistory.Storage {
        var failRead = false
        var failWrite = false

        override fun read(): List<CallRecord> {
            check(!failRead)
            return records
        }

        override fun write(records: List<CallRecord>): Boolean {
            if (failWrite) return false
            this.records = records.toList()
            return true
        }
    }

    private fun record(id: Int) = CallRecord(
        id = id.toString(), number = null, direction = CallDirection.INCOMING,
        startedAtMs = id.toLong(), endedAtMs = id + 1L, result = "通话已结束",
        transcript = listOf(CallTranscriptLine("caller", "留言。\n请于明日联系。"), CallTranscriptLine("assistant", "我会转告。")),
    )

    @Test fun handledChangesAndRemovalPersistWithoutRewritingTranscript() = runBlocking {
        val initial = record(1)
        val other = record(2)
        val storage = MemoryStorage(listOf(other, initial))
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.SAVED, history.setHandled(initial.id, true))
        assertEquals(listOf(other, initial.copy(handled = true)), storage.records)
        val reopened = CallHistory(storage)
        reopened.load()
        assertTrue(reopened.state.value.last().handled)
        assertEquals(CallHistory.Result.SAVED, reopened.setHandled(initial.id, false))
        assertEquals(initial, storage.records.last())
        assertEquals(CallHistory.Result.SAVED, reopened.delete(other.id))
        assertEquals(listOf(initial), reopened.state.value)
        assertEquals(CallHistory.Result.NOT_FOUND, reopened.delete(other.id))
        assertEquals(CallHistory.Result.SAVED, reopened.clear())
        assertTrue(storage.records.isEmpty())
        assertTrue(reopened.state.value.isEmpty())
    }

    @Test fun failedEditsKeepExistingRecordsAndNewCallsRemainVisible() = runBlocking {
        val initial = record(1)
        val newCall = record(2)
        val storage = MemoryStorage(listOf(initial)).apply { failWrite = true }
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.setHandled(initial.id, true))
        assertEquals(CallHistory.Result.WRITE_FAILED, history.delete(initial.id))
        assertEquals(CallHistory.Result.WRITE_FAILED, history.clear())
        assertEquals(listOf(initial), history.state.value)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.append(newCall))
        assertEquals(listOf(newCall, initial), history.state.value)
        assertEquals(listOf(initial), storage.records)
        assertTrue(history.writeFailed.value)
        storage.failWrite = false
        assertEquals(CallHistory.Result.SAVED, history.setHandled(newCall.id, true))
        assertEquals(listOf(newCall.copy(handled = true), initial), storage.records)
        assertFalse(history.writeFailed.value)
    }

    @Test fun unreadableHistoryIsNotSilentlyReplacedByAppend() = runBlocking {
        val old = record(1)
        val recent = record(2)
        val storage = MemoryStorage(listOf(old)).apply { failRead = true }
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.append(recent))
        assertEquals(listOf(old), storage.records)
        assertEquals(listOf(recent), history.state.value)
        assertTrue(history.writeFailed.value)
        assertEquals(CallHistory.Result.SAVED, history.clear())
        assertTrue(storage.records.isEmpty())
        assertFalse(history.writeFailed.value)
    }

    @Test fun keepsNewestFiftyCallsAndDecodesLegacyRecordsAsUnhandled() = runBlocking {
        val storage = MemoryStorage()
        val history = CallHistory(storage)
        for (id in 1..51) assertEquals(CallHistory.Result.SAVED, history.append(record(id)))
        assertEquals((51 downTo 2).map(Int::toString), storage.records.map(CallRecord::id))
        val legacy = Json.decodeFromString<CallRecord>(
            """{"id":"legacy","number":null,"direction":"INCOMING","startedAtMs":1,"endedAtMs":2,"result":"通话已结束","transcript":[]}""",
        )
        assertFalse(legacy.handled)
        assertNull(legacy.task)
        assertNull(legacy.audioReport)
        assertNull(legacy.summary)
        assertEquals(SummaryStatus.NOT_REQUESTED, legacy.summaryStatus)
    }

    @Test fun summaryAndFollowUpStatusesSurviveSerialization() {
        val original = record(1).copy(summary = CallSummary(
            purpose = "询问营业时间",
            followUpDraft = "关于刚才的电话，请问周五几点营业？",
            uncertainties = listOf("时间尚未确认"),
            actions = listOf(CallFollowUp("回复来电", CallFollowUp.Status.PENDING)),
        ))
        assertEquals(original, Json.decodeFromString<CallRecord>(Json.encodeToString(original)))
        assertEquals(CallFollowUp.Status.UNKNOWN, CallFollowUp("待确认").status)
    }

    @Test fun editedDraftSurvivesReopenAndFailedSavePreservesOriginal() = runBlocking {
        val original = record(1).copy(summary = CallSummary(purpose = "询价", followUpDraft = "原草稿"), summaryStatus = SummaryStatus.READY)
        val storage = MemoryStorage(listOf(original))
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.SAVED, history.saveFollowUpDraft(original.id, "  修改后的跟进消息  "))
        val reopened = CallHistory(storage)
        reopened.load()
        val expected = original.copy(summary = original.summary!!.copy(followUpDraft = "修改后的跟进消息"))
        assertEquals(expected, reopened.state.value.single())
        storage.failWrite = true
        assertEquals(CallHistory.Result.WRITE_FAILED, reopened.saveFollowUpDraft(original.id, "不能丢掉旧草稿"))
        assertEquals(expected, reopened.state.value.single())
        storage.failWrite = false
        reopened.delete(original.id)
        assertEquals(CallHistory.Result.NOT_FOUND, reopened.saveFollowUpDraft(original.id, "不复活已删除的通话"))
    }

    @Test fun manuallyWrittenDraftPreservesLegacySummaryAndHandlingState() = runBlocking {
        val original = record(1).copy(handled = true, summaryStatus = SummaryStatus.READY,
            summary = CallSummary(purpose = "确认床垫尺寸", actions = listOf(CallFollowUp("补充尺寸"))))
        val storage = MemoryStorage(listOf(original))
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.SAVED, history.saveFollowUpDraft(original.id, "请补充床垫尺寸。"))
        val reopened = CallHistory(storage).also { it.load() }
        assertEquals(original.copy(summary = original.summary!!.copy(followUpDraft = "请补充床垫尺寸。")),
            reopened.state.value.single())
    }

    @Test fun oldSummaryDecodesWithoutDraft() {
        val summary = Json.decodeFromString<CallSummary>("""{"purpose":"询价","actions":[]}""")
        assertNull(summary.followUpDraft)
    }

    @Test fun cancelledSummaryRetryRestoresFailedStatus() = runBlocking {
        val original = record(1).copy(summaryStatus = SummaryStatus.FAILED)
        val storage = MemoryStorage(listOf(original))
        val history = CallHistory(storage)
        val entered = CompletableDeferred<Unit>()
        val retry = launch {
            history.retryFailedSummary(original.id) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertEquals(SummaryStatus.PENDING, history.state.value.single().summaryStatus)
        retry.cancelAndJoin()
        assertEquals(original, history.state.value.single())
        assertEquals(listOf(original), storage.records)
    }

    @Test fun cancellationDoesNotReplaceAnAlreadySavedSummary() = runBlocking {
        val original = record(1).copy(summaryStatus = SummaryStatus.FAILED)
        val history = CallHistory(MemoryStorage(listOf(original)))
        val summary = CallSummary(purpose = "询问营业时间")
        val entered = CompletableDeferred<Unit>()
        val retry = launch {
            history.retryFailedSummary(original.id) {
                history.setSummary(original.id, summary, SummaryStatus.READY)
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        retry.cancelAndJoin()
        assertEquals(original.copy(summary = summary, summaryStatus = SummaryStatus.READY), history.state.value.single())
    }

    @Test fun outgoingTaskRoundTripsWithoutClaimingCompletionOrReusingItOnTheNextCall() = runBlocking {
        val task = CallTaskBrief("问清服务时间", listOf("周末开门吗？"), "门店", "不预约").validatedSnapshot()
        val report = CallAudioReport(3, 3, CallAudioReport.Turn.TRANSCRIPT_RECEIVED, 2880, 1920, 1920,
            inputFramesAfterLastReply = 50, inputSignalFramesAfterLastReply = 20, recognitionFailures = 1)
        val attempted = record(1).copy(direction = CallDirection.OUTGOING, result = "通话已结束", task = task, audioReport = report)
        val decoded = Json.decodeFromString<CallRecord>(Json.encodeToString(attempted))
        assertEquals(task, decoded.task)
        assertEquals(report, decoded.audioReport)
        assertEquals("通话已结束", decoded.result)
        assertFalse(decoded.handled)
        assertFalse(decoded.toString().contains(task.goal))

        val storage = MemoryStorage(listOf(decoded))
        val next = record(2).copy(direction = CallDirection.OUTGOING)
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.SAVED, history.append(next))
        val reopened = CallHistory(storage)
        reopened.load()
        assertNull(reopened.state.value.first().task)
        assertNull(reopened.state.value.first().audioReport)
        assertEquals(task, reopened.state.value.last().task)
    }
}
