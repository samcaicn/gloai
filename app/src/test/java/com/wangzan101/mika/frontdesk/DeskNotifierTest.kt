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

    // 排队的那件轮到它开始(或刚派出就排队)时页面不在:写回会话,但不发通知,免得前台念叨。
    @Test fun startAndQueueAreWrittenBackWithoutANotification() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        val notifier = DeskNotifier(store) { notices += it }
        notifier.record("p", DeskReport(DeskEvent.Queued("c1", 1), "查天气"))
        notifier.record("p", DeskReport(DeskEvent.Started("c1"), "查天气"))
        notifier.record("p", DeskReport(DeskEvent.Progress("c1", "打开天气"), "查天气"))
        assertEquals(3, store.turns("p").count { it.status == DeskNotifier.STATUS })
        assertTrue(notices.isEmpty())
    }

    // 通知正文是纯文字:任务回答里的 Markdown 记号不原样出现在通知里;写回会话的那行照旧保留(聊天页会渲染)。
    @Test fun notificationTextHasNoMarkdown() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        DeskNotifier(store) { notices += it }
            .record("p", DeskReport(DeskEvent.Succeeded("c1", "安卓版本号是 **14**,见 `关于手机`"), "查看安卓版本号"))
        val text = notices.single().text
        assertTrue(text, text.contains("安卓版本号是 14,见 关于手机"))
        assertFalse(text, text.contains("*") || text.contains("`"))
        assertTrue(store.turns("p").last().outcome.contains("**14**"))
    }

    // 前台派出去的那件(子会话挂在前台会话下)办完:前台兜底那一条就是唯一的通知,任务自己的「任务完成」不再发;
    // 不是前台派的任务照旧自己发。
    @Test fun aDeskChildFinishingNotifiesExactlyOnce() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        store.setDeskParent("child", "p")
        val notices = mutableListOf<DeskNotice>()
        DeskNotifier(store) { notices += it }.record("p", DeskReport(DeskEvent.Succeeded("child", "好了"), "订票"))
        val own = listOf("child").count { DeskNotifier.taskNotifiesItsOwnResult(store, it) }
        assertEquals(1, notices.size + own)
        assertTrue(DeskNotifier.taskNotifiesItsOwnResult(store, "plain"))
    }
}
