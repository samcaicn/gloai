package com.wangzan101.mika.core.learn.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskMemoryIndexTest {
    private fun task(
        id: String, goal: String = "目标$id", conclusion: String = "结论$id",
        apps: List<String> = emptyList(), sites: List<String> = emptyList(), createdAt: Long = 0L,
        labels: List<String> = emptyList(),
    ) = TaskMemory(id, goal, conclusion, apps, sites, createdAt, labels.map { TaskMemoryStep(0L, it, "原文$it") })

    @Test fun keywordMatchesGoalConclusionAndStepLabels() {
        val items = listOf(
            task("a", goal = "帮妈妈买药"),
            task("b", conclusion = "已帮妈妈买好药"),
            task("c", labels = listOf("打开买药应用")),
            task("d", goal = "订酒店"),
        )
        val idx = TaskMemoryIndex(items)
        assertEquals(listOf("a", "b", "c"), idx.search("药", null, null, 10).map { it.id })
        assertEquals(listOf("d"), idx.search("酒店", null, null, 10).map { it.id })
    }

    @Test fun appFilterMatchesPackage() {
        val items = listOf(task("a", apps = listOf("com.taobao")), task("b", apps = listOf("com.wechat")))
        assertEquals(listOf("a"), TaskMemoryIndex(items).search("", "com.taobao", null, 10).map { it.id })
        assertEquals(emptyList<String>(), TaskMemoryIndex(items).search("", "com.none", null, 10).map { it.id })
    }

    @Test fun sinceKeepsOnlyNewerAndSortedNewestFirst() {
        val items = listOf(
            task("old", createdAt = 1_000L),
            task("mid", createdAt = 2_000L),
            task("new", createdAt = 3_000L),
        )
        val idx = TaskMemoryIndex(items)
        assertEquals(listOf("new", "mid"), idx.search("", null, 2_000L, 10).map { it.id })
        assertEquals(listOf("new", "mid", "old"), idx.search("", null, null, 10).map { it.id })
        assertEquals(listOf("new"), idx.search("", null, null, 1).map { it.id })
    }

    @Test fun hitKeepsCompactFields() {
        val m = task("a", goal = "目标", apps = listOf("com.x"), createdAt = 9L, labels = listOf("一", "二"))
        val h = TaskMemoryIndex.hit(m)
        assertEquals("a", h.id)
        assertEquals(listOf("com.x"), h.apps)
        assertEquals(2, h.steps)
        assertEquals(9L, h.createdAt)
    }
}
