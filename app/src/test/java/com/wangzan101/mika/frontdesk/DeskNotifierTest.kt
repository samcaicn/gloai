package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.ActionKind
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

    // 要用户回答、确认、接管的:任务自己发带按钮的「需要你处理」通知,前台兜底只把这句写回会话,不再发第二条。
    @Test fun needsYouIsWrittenBackWithoutASecondNotification() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        val notifier = DeskNotifier(store) { notices += it }
        notifier.record("p", DeskReport(DeskEvent.NeedsAnswer("c1", "要几点的车?", null), "订票"))
        notifier.record("p", DeskReport(DeskEvent.NeedsAction("c1", ActionKind.Confirm), "订票"))
        notifier.record("p", DeskReport(DeskEvent.NeedsAction("c1", ActionKind.Takeover), "订票"))
        val lines = store.turns("p").filter { it.status == DeskNotifier.STATUS }
        assertEquals(3, lines.size)
        assertTrue(lines.first().outcome.contains("要几点的车?"))
        assertTrue(notices.isEmpty())
    }

    // 前置条件不满足:任务不另发提醒,前台这一条就是「需要你处理」。
    @Test fun prerequisiteStillNotifiesAsNeedsYou() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        DeskNotifier(store) { notices += it }.record("p", DeskReport(DeskEvent.NeedsAction("c1", ActionKind.Prerequisite), "订票"))
        assertTrue(notices.single().needsYou)
    }

    // 每种事件都有一句非空的话(包括排队位置)。
    @Test fun everyEventHasCopy() {
        val events = listOf(
            DeskEvent.Started("c"), DeskEvent.Queued("c", 2), DeskEvent.Progress("c", "打开日历"),
            DeskEvent.NeedsAnswer("c", "哪天?", listOf("今天", "明天")),
            DeskEvent.NeedsAction("c", ActionKind.Confirm),
            DeskEvent.NeedsAction("c", ActionKind.Takeover),
            DeskEvent.NeedsAction("c", ActionKind.Prerequisite),
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
}
