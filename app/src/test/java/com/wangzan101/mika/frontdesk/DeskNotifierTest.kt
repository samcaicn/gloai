package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeskNotifierTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val texts = ChineseTexts()

    @Test fun sinkAppendsTurnAndNotifies() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        store.append("p", Turn("帮我订票", "好的", "desk", deskTasks = listOf("c1")))
        val notices = mutableListOf<DeskNotice>()
        val notifier = DeskNotifier(store) { notices += it }

        notifier.record("p", DeskReport(DeskEvent.Succeeded("c1", "订好了明早 8 点的票"), "订票"))

        val last = store.turns("p").last()
        assertEquals(DeskNotifier.STATUS, last.status)
        assertEquals("", last.userText)
        assertEquals(listOf("c1"), last.deskTasks)
        assertTrue(last.outcome.contains("订票"))
        assertTrue(last.outcome.contains("订好了明早 8 点的票"))
        val notice = notices.single()
        assertEquals("p", notice.conversationId)
        assertEquals("订票", notice.title)
        assertFalse(notice.needsYou)
    }

    @Test fun questionGoesIntoTheNotification() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        DeskNotifier(store) { notices += it }.record("p", DeskReport(DeskEvent.NeedsAnswer("c1", "要几点的车?", null), "订票"))
        assertTrue(notices.single().text.contains("要几点的车?"))
        assertTrue(notices.single().needsYou)
    }

    // 每种事件都有一句非空的话(包括排队位置)。
    @Test fun everyEventHasCopy() {
        val events = listOf(
            DeskEvent.Started("c"), DeskEvent.Queued("c", 2), DeskEvent.Progress("c", "打开日历"),
            DeskEvent.NeedsAnswer("c", "哪天?", listOf("今天", "明天")),
            DeskEvent.NeedsAction("c", com.wangzan101.mika.core.frontdesk.ActionKind.Confirm),
            DeskEvent.NeedsAction("c", com.wangzan101.mika.core.frontdesk.ActionKind.Takeover),
            DeskEvent.NeedsAction("c", com.wangzan101.mika.core.frontdesk.ActionKind.Prerequisite),
            DeskEvent.Succeeded("c", "好了"), DeskEvent.Failed("c", "没网"), DeskEvent.Cancelled("c"),
        )
        val lines = events.map(DeskNotifier::line)
        lines.forEach { assertTrue(it.isNotBlank()) }
        assertTrue(lines[1].contains("2"))
        assertEquals(events.size, lines.toSet().size)
    }
}
