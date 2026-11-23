package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import org.junit.Assert.assertEquals
import org.junit.Test

/** 运行记录的因果顺序:观察到的内容跟在本次调用的工具结果后面,模型回复与工具结果之间不插别的。 */
class RecordOrderTest {
    private class Sink : RunSink {
        val events = mutableListOf<RunEvent>()
        val media = mutableListOf<Int>()
        override fun event(e: RunEvent) { events += e }
        override fun media(mediaId: Int, png: ByteArray) { media += mediaId }
    }

    private fun reply(step: Int) = RunEvent.ModelReply(0L, step, "m", null, null, null, null, null)
    private fun result(step: Int) = RunEvent.ToolResult(0L, step, "t", "{}", null, null)
    private fun observed(id: Int) = RunEvent.Observed(0L, 0, "o$id", id)
    private fun other() = RunEvent.TaskStarted(0L, "goal", "model")
    private fun label(e: RunEvent) = when (e) {
        is RunEvent.ModelReply -> "ModelReply"
        is RunEvent.ToolResult -> "ToolResult"
        is RunEvent.Observed -> "Observed"
        is RunEvent.TaskStarted -> "TaskStarted"
        else -> "other"
    }
    private fun Sink.types() = events.map(::label)

    @Test fun observationsFollowTheToolResultInOrder() {
        val sink = Sink()
        val order = RecordOrder(sink)
        order.observed(observed(1))
        order.observed(observed(2))
        order.event(reply(1))
        assertEquals("nothing is inserted before the tool result", listOf("ModelReply"), sink.types())
        order.event(result(1))
        assertEquals(listOf("ModelReply", "ToolResult", "Observed", "Observed"), sink.types())
        assertEquals(listOf(1, 2), sink.events.filterIsInstance<RunEvent.Observed>().map { it.id })
    }

    @Test fun aReadWithoutAToolResultIsFlushedBeforeTheNextOtherEvent() {
        val sink = Sink()
        val order = RecordOrder(sink)
        order.observed(observed(7))
        order.event(other())
        assertEquals(listOf("Observed", "TaskStarted"), sink.types())
    }

    @Test fun pendingObservationsAreFlushedOnlyOnce() {
        val sink = Sink()
        val order = RecordOrder(sink)
        order.observed(observed(1))
        order.event(result(1))
        order.event(other())
        order.event(result(2))
        assertEquals(1, sink.events.filterIsInstance<RunEvent.Observed>().size)
        assertEquals(listOf("ToolResult", "Observed", "TaskStarted", "ToolResult"), sink.types())
    }

    @Test fun severalToolResultsKeepTheirOwnObservations() {
        val sink = Sink()
        val order = RecordOrder(sink)
        order.observed(observed(1))
        order.event(result(1))
        order.observed(observed(2))
        order.event(reply(2))
        order.event(result(2))
        assertEquals(listOf(1, 2), sink.events.filterIsInstance<RunEvent.Observed>().map { it.id })
        assertEquals(
            listOf("ToolResult", "Observed", "ModelReply", "ToolResult", "Observed"),
            sink.types(),
        )
    }

    @Test fun mediaIsForwardedUntouched() {
        val sink = Sink()
        val order = RecordOrder(sink)
        order.media(3, byteArrayOf(1, 2))
        order.media(4, byteArrayOf())
        assertEquals(listOf(3, 4), sink.media)
        assertEquals(emptyList<RunEvent>(), sink.events)
    }
}
