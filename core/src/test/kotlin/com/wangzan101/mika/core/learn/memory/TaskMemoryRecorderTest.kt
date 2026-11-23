package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.run.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMemoryRecorderTest {
    private fun act(step: Int, kind: String, label: String, target: String? = null, outward: Boolean = false, atMs: Long = step * 100L) =
        RunEvent.Activity(atMs, step, kind, label, target = target, outward = outward)

    private fun read(step: Int, text: String, place: Place? = null) =
        RunEvent.Observed(step * 100L, step, text, kind = ObsKind.Read.wire, place = place)

    @Test fun skipsFragmentsThatNeverActed() {
        // 只读了一屏、没做动作:不记
        val events: List<RunEvent> = listOf(read(1, "一屏", Place.app("com.x")))
        assertNull(TaskMemoryRecorder.record("看看", events, 0L, "id"))
    }

    @Test fun recordsGoalConclusionAppsSitesAndSteps() {
        val events: List<RunEvent> = listOf(
            read(1, "订单页", Place.app("com.taobao")),
            act(1, "observe", "list_elements", target = "com.taobao"),
            read(2, "收货地址已填好", Place.site("example.com")),
            act(2, "click", "tap", target = "com.taobao", outward = true),
            RunEvent.OutcomeJudged(500L, "done", "订单已下"),
        )
        val m = TaskMemoryRecorder.record(
            "在淘宝下单", events, now = 9_000L, id = "r1",
            label = { "工具:$it" }, appLabel = { "应用:$it" },
        )!!
        assertEquals("r1", m.id)
        assertEquals("在淘宝下单", m.goal)
        assertEquals("办成：订单已下", m.conclusion)
        assertEquals(listOf("com.taobao"), m.apps)
        assertEquals(listOf("example.com"), m.sites)
        assertEquals(9_000L, m.createdAt)
        assertEquals(2, m.steps.size)
        assertTrue(m.steps[0].label.contains("工具:list_elements"))
        assertTrue(m.steps[0].label.contains("应用:com.taobao"))
        assertEquals("订单页", m.steps[0].detail)
        assertEquals("收货地址已填好", m.steps[1].detail)
    }

    @Test fun conclusionFallsBackToTaskEnded() {
        val events: List<RunEvent> = listOf(
            act(1, "click", "tap", target = "com.x"),
            RunEvent.TaskEnded(400L, "failed", "没找到按钮"),
        )
        assertEquals("失败：没找到按钮", TaskMemoryRecorder.record("试一下", events, 0L, "id")!!.conclusion)
    }

    @Test fun sitesFromSitePrefixedTargetsAndObservedPlace() {
        val events: List<RunEvent> = listOf(
            act(1, "observe", "browse", target = "网站 example.org"),
            read(1, "页面", Place.site("other.net")),
            act(2, "submit", "send", target = "网站 example.org"),
        )
        val m = TaskMemoryRecorder.record("查网站", events, 0L, "id")!!
        assertEquals(listOf("example.org", "other.net"), m.sites)
    }

    @Test fun goalAndConclusionAreClipped() {
        val longGoal = "字".repeat(500)
        val events: List<RunEvent> = listOf(act(1, "click", "tap", target = "com.x"), RunEvent.OutcomeJudged(1L, "done", "好"))
        val m = TaskMemoryRecorder.record(longGoal, events, 0L, "id")!!
        assertTrue(m.goal.length <= 121) // 120 + 省略号
    }
}
