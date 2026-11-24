package com.wangzan101.mika.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyJoinTest {
    private val noCalls = CallMarks(emptyMap(), emptyList())

    private fun desk(goal: String, reply: String) =
        Exchange(goal, emptyList(), ExchangeStatus.Done(reply, null), live = false, deskReply = reply)

    @Test
    fun followUpReplyWithoutUserWordsJoinsThePreviousOne() {
        val list = listOf(desk("问", "第一条"), desk("", "第二条"), desk("", "第三条"))
        assertFalse(replyJoinsAbove(list, 0, noCalls))
        assertTrue(replyJoinsAbove(list, 1, noCalls))
        assertTrue(replyJoinsAbove(list, 2, noCalls))
        assertFalse(replyJoinsAbove(list, 3, noCalls))
    }

    @Test
    fun userWordsBreakTheGroup() {
        val list = listOf(desk("问", "答"), desk("再问", "再答"), desk("", "接着说"))
        assertFalse(replyJoinsAbove(list, 1, noCalls))
        assertTrue(replyJoinsAbove(list, 2, noCalls))
    }
}
