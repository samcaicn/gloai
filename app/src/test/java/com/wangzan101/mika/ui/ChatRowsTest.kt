package com.wangzan101.mika.ui

import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRowsTest {
    private fun count(
        exchanges: List<Exchange>,
        phoneSetupNeeded: Boolean = false,
        hasBlocked: Boolean = false,
    ) = chatItemCount(exchanges, kept = null, phoneSetupNeeded, hasBlocked)

    @Test
    fun emptyAndBlockedStatesHaveTheirActualRows() {
        assertEquals(1, count(emptyList()))
        assertEquals(1, count(emptyList(), hasBlocked = true))
        assertEquals(1, count(emptyList(), phoneSetupNeeded = true, hasBlocked = true))
    }

    @Test
    fun appendedTurnAdvancesTheTailBeforeLazyListMeasuresIt() {
        val previous = List(30) { Exchange("问题 $it", emptyList(), ExchangeStatus.Done("回答 $it", null), live = false) }
        val oldTail = count(previous) - 1
        val sent = previous + Exchange("刚发送的新一轮", emptyList(), ExchangeStatus.Working, live = true)
        // 新一轮是用户气泡、工作区、状态行三项。这个目标只依赖当前状态,与旧布局里有几项无关。
        assertEquals(oldTail + 3, count(sent) - 1)
    }

    @Test
    fun streamingAnswerAndOptionalRowsStayAlignedWithTheDsl() {
        val working = Exchange("问", emptyList(), ExchangeStatus.Working, live = true, draft = "正在回答")
        assertEquals(3, count(listOf(working)))
        val done = working.copy(status = ExchangeStatus.Done("回答", null), draft = null, runId = "run")
        assertEquals(2, count(listOf(done)))
        assertEquals(3, count(listOf(done), phoneSetupNeeded = true))
        assertEquals(4, count(listOf(done), phoneSetupNeeded = true, hasBlocked = true))
    }

    @Test
    fun keysListRowsInTheSameOrderTheDslEmitsThem() {
        val working = Exchange("问", emptyList(), ExchangeStatus.Working, live = true, draft = "正在回答")
        assertEquals(listOf("u0", "d0", "r0"), chatItemKeys(listOf(working), kept = null, phoneSetupNeeded = false, hasBlocked = false))

        val steer = SteerNote("s1", "改成靠窗", SteerState.Joined, interrupted = "被打断", finished = "正在改")
        val done = Exchange(
            "买票", emptyList(), ExchangeStatus.Done("完成", null), live = false, runId = "run",
            steers = listOf(steer),
            files = listOf(SharedFile("a.txt", 1L, "a.txt", "content://a")),
        )
        assertEquals(
            listOf("u0", "m0-s1", "r0", "f0"),
            chatItemKeys(listOf(done), kept = null, phoneSetupNeeded = false, hasBlocked = false),
        )
    }

    @Test
    fun targetKeysPointAtTheRenderedRow() {
        val steer = SteerNote("s1", "改成靠窗", SteerState.Joined, interrupted = "被打断", finished = "正在改")
        val done = Exchange(
            "买票", emptyList(), ExchangeStatus.Done("完成", null), live = false, runId = "run",
            steers = listOf(steer),
            files = listOf(SharedFile("a.txt", 1L, "a.txt", "content://a")),
        )
        val list = listOf(done)
        assertEquals("u0", chatTargetKey(list, ChatTarget.Message(0, null)))
        assertEquals("m0-s1", chatTargetKey(list, ChatTarget.Message(0, 0)))
        assertEquals("u0", chatTargetKey(list, ChatTarget.Reply(0, 0, interrupted = false)))
        assertEquals("u0", chatTargetKey(list, ChatTarget.Reply(0, 0, interrupted = true)))
        assertEquals("f0", chatTargetKey(list, ChatTarget.Files(0)))
        assertEquals("r0", chatTargetKey(list, ChatTarget.Status(0)))
        assertNull(chatTargetKey(list, ChatTarget.Message(1, null)))
    }

    @Test
    fun withdrawnSteerFallsBackToTheTurnStart() {
        val withdrawn = SteerNote("s1", "撤回的话", SteerState.Withdrawn)
        val done = Exchange("问", emptyList(), ExchangeStatus.Done("答", null), live = false, steers = listOf(withdrawn))
        assertEquals("u0", chatTargetKey(listOf(done), ChatTarget.Message(0, 0)))
    }
}
