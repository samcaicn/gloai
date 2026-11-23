package com.wangzan101.mika.core.screen.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMapRenderTest {
    private fun graph(): ScreenGraph {
        val a = ScreenNode(id = "A", app = "x", anchors = mapOf("主页" to 1, "设置" to 1),
            transitions = mapOf("a-b" to Transition("tap", "设置", TransitionEffect.NAVIGATED, "B", 1)))
        val b = ScreenNode(id = "B", app = "x", anchors = mapOf("关于本机" to 1))
        return ScreenGraph("x", mapOf("A" to a, "B" to b))
    }

    @Test fun renderPositionShowsCurrentScreen() {
        assertEquals("你现在在「主页、设置」屏", ScreenMapRender.renderPosition(graph(), "A"))
    }

    @Test fun renderPositionUnknownIsEmpty() {
        assertEquals("", ScreenMapRender.renderPosition(graph(), null))
        assertEquals("", ScreenMapRender.renderPosition(graph(), "不存在的屏"))
    }

    @Test fun renderMapListsScreensAndOutgoingEdges() {
        val s = ScreenMapRender.renderMap(graph(), "A")
        assertTrue(s.contains("屏（2）"))
        assertTrue(s.contains("主页、设置"))
        assertTrue(s.contains("关于本机"))
        assertTrue(s.contains("现在在「主页、设置」屏"))
        assertTrue(s.contains("点「设置」→ 关于本机"))
    }

    @Test fun renderMapTruncatesToMaxChars() {
        assertTrue(ScreenMapRender.renderMap(graph(), "A", maxChars = 20).length <= 20)
    }

    @Test fun renderMapTruncatesScreenListToMaxScreens() {
        val nodes = (1..15).associate { i ->
            val id = "s$i"
            id to ScreenNode(id = id, app = "x", anchors = mapOf("页${i.toString().padStart(2, '0')}" to 1))
        }
        val g = ScreenGraph("x", nodes)
        val s = ScreenMapRender.renderMap(g, "s1", maxScreens = 12)
        assertTrue(s.contains("屏（15）")) // 总数仍报 15
        assertFalse(s.contains("页15")) // 但清单截到 12，第 15 个不在
    }

    @Test fun renderMapEmptyGraphIsEmpty() {
        assertEquals("", ScreenMapRender.renderMap(ScreenGraph("x", emptyMap()), null))
    }

    @Test fun renderMapForRecognizesCurrentScreen() {
        val s = ScreenMapRender.renderMapFor(graph(), setOf("主页", "设置"))
        assertTrue(s!!.contains("现在在「主页、设置」屏"))
        assertTrue(s.contains("点「设置」→ 关于本机"))
    }

    @Test fun renderMapForUnrecognizedGivesListOnly() {
        val s = ScreenMapRender.renderMapFor(graph(), setOf("别的字"))
        assertTrue(s!!.contains("屏（2）"))
        assertFalse(s.contains("现在在"))
    }

    @Test fun renderMapForNullGraphIsNull() {
        assertEquals(null, ScreenMapRender.renderMapFor(null, setOf("主页")))
        assertEquals(null, ScreenMapRender.renderMapFor(ScreenGraph("x", emptyMap()), setOf("主页")))
    }

    @Test fun renderMapForUnreadScreenIsNull() {
        assertEquals(null, ScreenMapRender.renderMapFor(graph(), null))
    }
}
