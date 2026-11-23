package com.wangzan101.mika.core.frontdesk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeskBoardTest {
    @Test
    fun rendersEachPhaseOnItsOwnLine() {
        val views = listOf(
            DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, "打开 找日程", null),
            DeskTaskView("t2", "查快递", DeskTaskPhase.Queued, null, 2),
            DeskTaskView("t3", "订餐", DeskTaskPhase.NeedsYou, "要订哪一间?", null),
            DeskTaskView("t4", "叫车", DeskTaskPhase.Done, "订好了 A 间", null),
            DeskTaskView("t5", "买票", DeskTaskPhase.Failed, "会议室都满了", null),
            DeskTaskView("t6", "发邮件", DeskTaskPhase.Cancelled, null, null),
        )
        assertEquals(
            listOf(
                "- task_id t1 · 订会议室 · running · 打开 找日程",
                "- task_id t2 · 查快递 · queued (position 2)",
                "- task_id t3 · 订餐 · needs_you · 要订哪一间?",
                "- task_id t4 · 叫车 · done · 订好了 A 间",
                "- task_id t5 · 买票 · failed · 会议室都满了",
                "- task_id t6 · 发邮件 · cancelled",
            ).joinToString("\n"),
            DeskBoard.render(views),
        )
    }

    @Test
    fun queuedWithoutKnownPositionSaysQueued() {
        assertEquals("- task_id t1 · 查快递 · queued", DeskBoard.render(listOf(DeskTaskView("t1", "查快递", DeskTaskPhase.Queued, null, null))))
    }

    @Test
    fun emptyBoardIsNull() {
        assertNull(DeskBoard.render(emptyList()))
    }

    @Test
    fun backgroundJoinsBoardAndMemory() = runBlocking {
        val views = listOf(DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, null, null))
        val asked = mutableListOf<String>()
        val both = deskBackground({ views }, { asked += it; "喜欢靠窗" }).render("帮我订位子")!!
        assertEquals(listOf("帮我订位子"), asked)
        assertEquals(
            DeskBackground.TASKS_HEADING + "\n- task_id t1 · 订会议室 · running\n\n" + DeskBackground.MEMORY_HEADING + "\n喜欢靠窗",
            both,
        )
        assertEquals(DeskBackground.MEMORY_HEADING + "\n喜欢靠窗", deskBackground({ emptyList() }, { "喜欢靠窗" }).render("x"))
        assertNull(deskBackground({ emptyList() }, { null }).render("x"))
        assertNull(deskBackground({ emptyList() }, { " " }).render("x"))
    }

    @Test
    fun failingMemoryLeavesBoard() = runBlocking {
        val views = listOf(DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, null, null))
        assertEquals(
            DeskBackground.TASKS_HEADING + "\n- task_id t1 · 订会议室 · running",
            deskBackground({ views }, { error("index broken") }).render("帮我订位子"),
        )
    }

    @Test
    fun noMemoryLookupWithoutUserWords() = runBlocking {
        var asked = false
        assertNull(deskBackground({ emptyList() }, { asked = true; "x" }).render(""))
        assertEquals(false, asked)
    }
}
