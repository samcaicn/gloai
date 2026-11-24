package com.wangzan101.mika.voicecall

import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Said
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Speaker
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Task
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Earlier
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

    @Test fun `opening a call shows the conversation so far above a divider`() {
        val feed = VoiceCallFeed()
        feed.seed(
            listOf(
                ChatTurn("帮我订日料", "好，我让后面去订", "desk", deskTasks = listOf("t1")),
                ChatTurn("", "订好了，七点。", "desk_report"),
                ChatTurn("今天天气", "**晴**，25 度", "succeeded"),
                ChatTurn("打开相册", "没打开", "failed"),
            ),
            tasks = listOf(view("t1", DeskTaskPhase.Done, "订好了"), view("other", DeskTaskPhase.Done, "别的")),
            plain = { it.replace("**", "") },
        )
        val items = feed.items.value
        assertEquals(
            listOf(
                Triple(Speaker.User, "帮我订日料", false), Triple(Speaker.Mika, "好，我让后面去订", false),
                Triple(Speaker.Mika, "订好了，七点。", false), Triple(Speaker.User, "今天天气", false),
                Triple(Speaker.Mika, "晴，25 度", false), Triple(Speaker.User, "打开相册", false),
                Triple(Speaker.Mika, "没打开", false),
            ),
            feed.said(),
        )
        // 派过的任务在它那一轮的回话后面一张卡;没出现在这条会话里的任务不上卡
        assertEquals(listOf("t1"), items.filterIsInstance<Task>().map { it.taskId })
        assertTrue(items.indexOfFirst { it is Task } == 2)
        assertTrue(items.last() is Earlier)
        assertTrue(items.filterIsInstance<Said>().all { it.earlier })
        assertTrue(items.filterIsInstance<Task>().all { it.earlier })
        // 接着说的话在分隔线下面,不算之前的
        feed.onUser("几点到", isFinal = true)
        assertFalse((feed.items.value.last() as Said).earlier)
    }

    @Test fun `a seeded task that is still running keeps updating instead of getting a second card`() {
        val feed = VoiceCallFeed()
        feed.seed(listOf(ChatTurn("查天气", "好", "desk", deskTasks = listOf("t1"))), listOf(view("t1", DeskTaskPhase.Running, "打开天气")))
        feed.onTasks(listOf(view("t1", DeskTaskPhase.Done, "晴", listOf("打开天气"))))
        val cards = feed.items.value.filterIsInstance<Task>()
        assertEquals(1, cards.size)
        assertEquals(DeskTaskPhase.Done, cards.single().phase)
        assertEquals(listOf("打开天气", "晴"), cards.single().steps)
    }

    @Test fun `the whole conversation so far is shown as it was and an empty one adds nothing`() {
        val feed = VoiceCallFeed(maxItems = 3)
        feed.seed(emptyList(), emptyList())
        assertTrue(feed.items.value.isEmpty())
        val long = "字".repeat(1_000)
        feed.seed((1..30).map { ChatTurn("第$it 句", if (it == 30) long else "答$it", "desk") }, emptyList())
        // 不截轮次、不截字:之前的对话完整摆上来
        assertEquals(60, feed.said().size)
        assertEquals("第1 句", feed.said().first().second)
        assertEquals(long, feed.said().last().second)
        // 这通电话里新说的才按上限滚掉,之前的对话一条不少
        repeat(5) { feed.onUser("新$it", isFinal = true) }
        val said = feed.said().map { it.second }
        assertEquals(63, said.size)
        assertEquals(listOf("新2", "新3", "新4"), said.takeLast(3))
        assertTrue(feed.items.value[60] is Earlier)
    }

    @Test fun `the feed keeps only the latest items`() {
        val feed = VoiceCallFeed(maxItems = 3)
        repeat(5) { feed.onUser("第$it 句", isFinal = true) }
        assertEquals(listOf("第2 句", "第3 句", "第4 句"), feed.said().map { it.second })
    }
}
