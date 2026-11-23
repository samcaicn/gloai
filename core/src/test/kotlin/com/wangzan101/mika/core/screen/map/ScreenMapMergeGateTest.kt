package com.wangzan101.mika.core.screen.map

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.replay.Anchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMapMergeGateTest {
    private fun at(beforePkg: String?, before: List<String>, afterPkg: String?, after: List<String>) = RunEvent.ActionTarget(
        atMs = 0, step = 1, tool = "tap", argsJson = "{}", target = null,
        before = Anchors(beforePkg, before), after = Anchors(afterPkg, after), effective = true,
    )

    private fun graph(app: String) = ScreenGraph(app, mapOf(
        "x" to ScreenNode(id = "x", app = app, anchors = mapOf("字" to 1)),
    ))

    @Test fun wrongMergeDetectsSameStructureWithDifferentLabels() {
        val a = ScreenNode(id = "A", app = "x", anchors = mapOf("首页" to 1, "设置" to 1))
        val b = ScreenNode(id = "B", app = "x", anchors = mapOf("首页" to 1, "设置" to 1))
        val pairs = ScreenMapMergeGate.wrongMerge(listOf(a, b), mapOf("A" to "甲", "B" to "乙"))
        assertTrue(pairs.isNotEmpty())
        // B 被认成了 A
        assertEquals("A", pairs.first().second)
    }

    @Test fun wrongMergeEmptyWhenLabelsMissing() {
        val a = ScreenNode(id = "A", app = "x", anchors = mapOf("首页" to 1, "设置" to 1))
        val b = ScreenNode(id = "B", app = "x", anchors = mapOf("首页" to 1, "设置" to 1))
        assertTrue(ScreenMapMergeGate.wrongMerge(listOf(a, b), emptyMap()).isEmpty())
    }

    @Test fun wrongMergeEmptyWhenScreensResolveToThemselves() {
        val a = ScreenNode(id = "A", app = "x", anchors = mapOf("首页" to 1))
        val b = ScreenNode(id = "B", app = "x", anchors = mapOf("设置" to 1))
        assertTrue(ScreenMapMergeGate.wrongMerge(listOf(a, b), mapOf("A" to "甲", "B" to "乙")).isEmpty())
    }

    @Test fun wrongOwnerDetectsSameScreenUnderTwoApps() {
        val events = listOf(
            at("com.a", listOf("首页"), "com.a", listOf("设置")),
            at("com.b", listOf("首页"), "com.b", listOf("详情")),
        )
        val graphs = mapOf("com.a" to graph("com.a"), "com.b" to graph("com.b"))
        assertEquals(listOf("首页"), ScreenMapMergeGate.wrongOwner(events, graphs))
    }

    @Test fun wrongOwnerEmptyWhenScreensWellOwned() {
        val events = listOf(
            at("com.a", listOf("首页"), "com.a", listOf("设置")),
            at("com.b", listOf("我的"), "com.b", listOf("详情")),
        )
        val graphs = mapOf("com.a" to graph("com.a"), "com.b" to graph("com.b"))
        assertTrue(ScreenMapMergeGate.wrongOwner(events, graphs).isEmpty())
    }

    @Test fun wrongOwnerAllowsNullPackageAndNoGraph() {
        // 归 system/unknown(null 包名)允许;没图的包名也不判
        val events = listOf(
            at(null, listOf("首页"), "com.a", listOf("设置")),
            at("com.noGraph", listOf("首页"), "com.noGraph", listOf("设置")),
        )
        val graphs = mapOf("com.a" to graph("com.a"))
        assertTrue(ScreenMapMergeGate.wrongOwner(events, graphs).isEmpty())
    }
}
