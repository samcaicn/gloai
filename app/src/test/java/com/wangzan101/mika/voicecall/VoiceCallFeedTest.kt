package com.wangzan101.mika.voicecall

import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Said
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Speaker
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Task
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallFeedTest {

    private fun VoiceCallFeed.said() = items.value.filterIsInstance<Said>().map { Triple(it.speaker, it.text, it.partial) }

    @Test fun `partial user speech is replaced in place and settles on final`() {
        val feed = VoiceCallFeed()
        feed.onUser("帮我", isFinal = false)
        feed.onUser("帮我查天气", isFinal = false)
        assertEquals(listOf(Triple(Speaker.User, "帮我查天气", true)), feed.said())
        feed.onUser("帮我查天气。", isFinal = true)
        assertEquals(listOf(Triple(Speaker.User, "帮我查天气。", false)), feed.said())
        feed.onUser("再定个闹钟", isFinal = true)
        assertEquals(2, feed.items.value.size)
    }

    @Test fun `assistant draft grows in one bubble and an interruption keeps what was said`() {
        val feed = VoiceCallFeed()
        feed.onUser("天气", isFinal = true)
        feed.onAssistantDraft("好的,")
        feed.onAssistantDraft("好的,我看")
        assertEquals(Triple(Speaker.Mika, "好的,我看", true), feed.said().last())
        feed.onAssistant("好的,我看", isFinal = false)
        assertEquals(Triple(Speaker.Mika, "好的,我看", false), feed.said().last())
        feed.onAssistantDraft("明天多云")
        assertEquals("被打断后的新一段另起一条", 3, feed.items.value.size)
    }

    @Test fun `blank text is ignored`() {
        val feed = VoiceCallFeed()
        feed.onUser("  ", isFinal = true)
        feed.onAssistant("", isFinal = true)
        assertTrue(feed.items.value.isEmpty())
    }

    private fun view(id: String, phase: DeskTaskPhase, line: String?, recent: List<String> = emptyList()) =
        DeskTaskView(id, "标题$id", phase, line, null, recent)

    @Test fun `each desk task gets one card that collects its progress`() {
        val feed = VoiceCallFeed()
        // 打开通话时已经收尾的是之前派的,不上卡;还在做的照常上卡。
        feed.onTasks(listOf(view("old", DeskTaskPhase.Done, "上次办完了"), view("t0", DeskTaskPhase.Running, "打开日历")))
        assertEquals(listOf("t0"), feed.items.value.filterIsInstance<Task>().map { it.taskId })
        feed.onTasks(
            listOf(
                view("old", DeskTaskPhase.Done, "上次办完了"),
                view("t0", DeskTaskPhase.Running, "读日程", listOf("打开日历", "读日程")),
                view("t1", DeskTaskPhase.Queued, null),
            ),
        )
        var cards = feed.items.value.filterIsInstance<Task>()
        assertEquals(listOf("t0", "t1"), cards.map { it.taskId })
        assertEquals(listOf("打开日历", "读日程"), cards[0].steps)
        assertTrue(cards[1].running)
        feed.onTasks(listOf(view("t0", DeskTaskPhase.Done, "办完了", listOf("打开日历", "读日程")), view("t1", DeskTaskPhase.Running, "打开天气")))
        cards = feed.items.value.filterIsInstance<Task>()
        assertEquals(listOf("打开日历", "读日程", "办完了"), cards[0].steps)
        assertFalse(cards[0].running)
        assertEquals("标题t0", cards[0].title)
        assertEquals(listOf("打开天气"), cards[1].steps)
        assertEquals("同一件任务不重复开卡", 2, cards.size)
    }

    @Test fun `a new call starts empty and a task that ended before it gets no card`() {
        val feed = VoiceCallFeed()
        assertTrue(feed.items.value.isEmpty())
        // 打开通话时任务板上已收尾的是之前派的:不上卡;之后新派的才上卡
        feed.onTasks(listOf(view("old", DeskTaskPhase.Done, "订好了")))
        assertTrue(feed.items.value.isEmpty())
        feed.onTasks(listOf(view("old", DeskTaskPhase.Done, "订好了"), view("t1", DeskTaskPhase.Running, "打开天气")))
        assertEquals(listOf("t1"), feed.items.value.filterIsInstance<Task>().map { it.taskId })
    }

    @Test fun `the feed keeps only the latest items`() {
        val feed = VoiceCallFeed(maxItems = 3)
        repeat(5) { feed.onUser("第$it 句", isFinal = true) }
        assertEquals(listOf("第2 句", "第3 句", "第4 句"), feed.said().map { it.second })
    }
}
