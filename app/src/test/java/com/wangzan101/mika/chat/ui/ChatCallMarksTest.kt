package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ConversationCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCallMarksTest {
    private fun turn(at: Long, callId: String? = null) =
        Exchange("问 $at", emptyList(), ExchangeStatus.Done("答 $at", null), live = false, callId = callId, atMs = at)

    private fun List<CallMark>.keys() = map { it.key }

    @Test
    fun `通话里的几轮前后各一行开头和结束`() {
        val call = ConversationCall("a", 150L, 400L, ConversationCall.END_HANGUP, spoke = true)
        val list = listOf(turn(100), turn(200, "a"), turn(300, "a"), turn(500))
        val marks = callMarks(list, listOf(call))
        assertEquals(listOf("call-a"), marks.before(1).keys())
        assertTrue(marks.before(2).isEmpty())
        assertEquals("结束那行排在通话最后一轮后面", listOf("call-end-a"), marks.before(3).keys())
        assertTrue(marks.tail.isEmpty())
    }

    @Test
    fun `还在通话里的不画结束,通话在最后时结束行在末尾`() {
        val list = listOf(turn(100), turn(200, "a"))
        val live = callMarks(list, listOf(ConversationCall("a", 150L)))
        assertEquals(listOf("call-a"), live.before(1).keys())
        assertTrue(live.tail.isEmpty())
        val ended = callMarks(list, listOf(ConversationCall("a", 150L, 300L, spoke = true)))
        assertEquals(listOf("call-end-a"), ended.tail.keys())
    }

    @Test
    fun `没说话的通话按接通时刻排进对话,后面没有轮次就排在最后`() {
        val list = listOf(turn(100), turn(300))
        val silent = ConversationCall("s", 200L, 205L)
        val marks = callMarks(list, listOf(silent))
        assertEquals(listOf(CallMark.Silent(silent)), marks.before(1))
        val late = ConversationCall("t", 400L, 405L)
        assertEquals(listOf(CallMark.Silent(late)), callMarks(list, listOf(late)).tail)
        val ongoing = ConversationCall("u", 400L)
        assertEquals("还没挂的空通话画成开头", listOf(CallMark.Start(ongoing)), callMarks(list, listOf(ongoing)).tail)
    }

    @Test
    fun `通话记录丢了也画开头,时刻取第一轮的`() {
        val marks = callMarks(listOf(turn(100), turn(200, "x")), emptyList())
        val start = marks.before(1).single()
        assertTrue(start is CallMark.Start)
        assertEquals(200L, start.call.startMs)
    }

    @Test
    fun `没有通话时什么都不画`() {
        assertTrue(callMarks(listOf(turn(100)), emptyList()).isEmpty())
    }

    @Test
    fun `通话行进列表的 key,跳到通话落在开头那行`() {
        val call = ConversationCall("a", 150L, 400L, spoke = true)
        val list = listOf(turn(100), turn(200, "a"))
        val marks = callMarks(list, listOf(call))
        val keys = chatItemKeys(list, kept = null, phoneSetupRow = null, hasBlocked = false, calls = marks)
        val start = keys.indexOf("call-a")
        assertTrue(start > keys.indexOf("u0") && start < keys.indexOf("u1"))
        assertEquals("call-end-a", keys.last())
        assertEquals("call-a", chatTargetKey(list, ChatTarget.Call("a")))
        assertEquals(keys.size, chatItemCount(list, kept = null, phoneSetupRow = null, hasBlocked = false, calls = marks))
    }

    @Test
    fun `只有一通没说话的通话时不显示空对话`() {
        val marks = callMarks(emptyList(), listOf(ConversationCall("s", 1L, 5L)))
        val keys = chatItemKeys(emptyList(), kept = null, phoneSetupRow = null, hasBlocked = false, calls = marks)
        assertEquals(listOf("call-s"), keys)
    }
}
