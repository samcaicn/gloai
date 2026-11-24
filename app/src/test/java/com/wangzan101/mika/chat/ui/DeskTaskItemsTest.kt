package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「这个对话里的事」:前台派过的每件,新派的在上;汇报那一轮被汇报的那件不重复算。 */
class DeskTaskItemsTest {
    private val exchanges = listOf(
        Turn("帮我订日料,再查汇率", "好,两件都交给后面了。", DeskTurn.STATUS, deskTasks = listOf("c1", "c2")),
        Turn("", "订日料:办好了", DeskTurn.REPORT_STATUS, deskTasks = listOf("c1")),
        Turn("看下明天天气", "好。", DeskTurn.STATUS, deskTasks = listOf("c3")),
    ).map(ChatReduce::fromTurn)

    @Test fun listsEveryDispatchedTaskNewestFirstWithItsWords() {
        val items = deskTaskItems(exchanges, emptyList())
        assertEquals(listOf("c3", "c2", "c1"), items.map { it.taskId })
        assertEquals("看下明天天气", items[0].goal)
        assertEquals("帮我订日料,再查汇率", items[2].goal)
        assertNull(items[0].view)
    }

    @Test fun activeTasksCarryTheirViewAndOwnWords() {
        val view = DeskTaskView("c2", "查汇率", DeskTaskPhase.Running, line = null, position = null, goal = "查一下美元汇率")
        val late = DeskTaskView("c9", "别处派的", DeskTaskPhase.Queued, line = null, position = 1)
        val items = deskTaskItems(exchanges, listOf(view, late))
        assertEquals(listOf("c9", "c3", "c2", "c1"), items.map { it.taskId })
        assertEquals(view, items[2].view)
        assertEquals("查一下美元汇率", items[2].goal)
        assertEquals("", items[0].goal)
    }
}
