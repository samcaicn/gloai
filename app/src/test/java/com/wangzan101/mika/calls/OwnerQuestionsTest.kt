package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.frontdesk.DeskNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerQuestionsTest {
    private val written = mutableListOf<Pair<String, Turn>>()
    private val questions = OwnerQuestions(
        write = { id, turn -> written += id to turn },
        askedLine = { question, notified -> "问：$question" + if (notified) "" else "（通知关了）" },
        answeredLine = { "已转告" },
    )

    @Test fun theQuestionLandsInTheOwnersConversation() {
        questions.open("chat-1", "能不能改到周日", notified = true) {}
        val (id, turn) = written.single()
        assertEquals("chat-1", id)
        assertEquals("", turn.userText)
        assertEquals("问：能不能改到周日", turn.outcome)
        assertEquals(DeskNotifier.STATUS, turn.status)
    }

    @Test fun theNextMessageInThatConversationAnswersAndTheNotificationReplyIsThenIgnored() {
        val answers = mutableListOf<String>()
        val handle = questions.open("chat-1", "几点到", notified = true) { answers += it }
        assertFalse(questions.answerIn("chat-2", "七点"))
        assertTrue(questions.answerIn("chat-1", "七点"))
        assertEquals(listOf("七点"), answers)
        assertEquals("七点", written.last().second.userText)
        assertEquals("已转告", written.last().second.outcome)
        // 先到的算数,问题已经清掉
        assertFalse(questions.answer(handle.token, "八点"))
        assertFalse(questions.answerIn("chat-1", "九点"))
        assertNull(questions.question(handle.token))
        assertEquals(listOf("七点"), answers)
    }

    @Test fun aNotificationReplyWinsAndIsRecordedInTheConversationToo() {
        val answers = mutableListOf<String>()
        val handle = questions.open("chat-1", "要包间吗", notified = true) { answers += it }
        assertTrue(questions.answer(handle.token, "不要"))
        assertEquals(listOf("不要"), answers)
        assertEquals("不要", written.last().second.userText)
        assertFalse(questions.answerIn("chat-1", "要"))
    }

    @Test fun aCallWithoutAnOwnerConversationOnlyAnswersThroughTheNotification() {
        val answers = mutableListOf<String>()
        val handle = questions.open(null, "几个人", notified = true) { answers += it }
        assertTrue(written.isEmpty())
        assertEquals("几个人", questions.question(handle.token))
        assertTrue(questions.answer(handle.token, "四个"))
        assertEquals(listOf("四个"), answers)
        assertTrue(written.isEmpty())
    }

    @Test fun aQuietQuestionSaysSoInTheConversation() {
        questions.open("chat-1", "几点到", notified = false) {}
        assertEquals("问：几点到（通知关了）", written.single().second.outcome)
    }

    @Test fun cancelledQuestionsTakeNoAnswer() {
        val handle = questions.open("chat-1", "几点到", notified = true) { error("no answer after cancel") }
        questions.cancel(handle.token)
        assertFalse(questions.answerIn("chat-1", "七点"))
        assertFalse(questions.answer(handle.token, "七点"))
        assertEquals(1, written.size)
    }

    @Test fun blankAnswersDoNotCount() {
        val handle = questions.open("chat-1", "几点到", notified = true) { error("blank is not an answer") }
        assertFalse(questions.answerIn("chat-1", "  "))
        assertFalse(questions.answer(handle.token, ""))
        assertEquals("几点到", questions.question(handle.token))
    }
}
