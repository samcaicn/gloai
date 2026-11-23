package com.wangzan101.mika.core.frontdesk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskBoardTest {
    @Test
    fun rendersEachPhaseOnItsOwnLine() {
        val views = listOf(
            DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, "找日程", null, listOf("打开日历", "找日程")),
            DeskTaskView("t2", "查快递", DeskTaskPhase.Queued, null, 2),
            DeskTaskView("t3", "订餐", DeskTaskPhase.NeedsYou, "要订哪一间?", null, listOf("a", "b", "要订哪一间?")),
            DeskTaskView("t4", "叫车", DeskTaskPhase.Done, "订好了 A 间", null, listOf("叫车中", "订好了 A 间")),
            DeskTaskView("t5", "买票", DeskTaskPhase.Failed, "会议室都满了", null),
            DeskTaskView("t6", "发邮件", DeskTaskPhase.Cancelled, null, null),
        )
        assertEquals(
            listOf(
                "- task_id t1 · 订会议室 · running · 打开日历 → 找日程",
                "- task_id t2 · 查快递 · queued (position 2)",
                "- task_id t3 · 订餐 · needs_you · a → b → 要订哪一间?",
                "- task_id t4 · 叫车 · done · 订好了 A 间",
                "- task_id t5 · 买票 · failed · 会议室都满了",
                "- task_id t6 · 发邮件 · cancelled",
            ).joinToString("\n"),
            DeskBoard.render(views),
        )
    }

    @Test
    fun runningWithoutRecentFallsBackToLine() {
        assertEquals(
            "- task_id t1 · 订会议室 · running · 打开日历",
            DeskBoard.render(listOf(DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, "打开日历", null))),
        )
    }

    @Test
    fun tasksHeadingPointsToTaskCard() {
        assertTrue(DeskBackground.TASKS_HEADING.contains("task card"))
    }

    @Test
    fun memoryLookedUpOncePerUserText() = runBlocking {
        var views = listOf(DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, null, null))
        val asked = mutableListOf<String>()
        val background = deskBackground({ views }, { asked += it; "记忆 ${asked.size}" })
        val first = background.render("帮我订位子")!!
        views = listOf(DeskTaskView("t1", "订会议室", DeskTaskPhase.Done, "订好了", null))
        val second = background.render("帮我订位子")!!
        assertEquals(listOf("帮我订位子"), asked)
        assertTrue(first.contains("记忆 1") && second.contains("记忆 1"))
        // 任务板每次重画:第二次已经是 done。
        assertTrue(second.contains("· done · 订好了"))
        assertTrue(background.render("几点？")!!.contains("记忆 2"))
        assertEquals(listOf("帮我订位子", "几点？"), asked)
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
