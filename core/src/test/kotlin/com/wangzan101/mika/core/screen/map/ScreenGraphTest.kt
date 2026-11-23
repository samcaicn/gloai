package com.wangzan101.mika.core.screen.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenGraphTest {
    private fun edge(action: String, to: String) = Transition(action, null, TransitionEffect.NAVIGATED, to, count = 1)

    private fun graph(): ScreenGraph {
        val a = ScreenNode(id = "A", app = "x", anchors = mapOf("主页" to 1), transitions = mapOf("a-b" to edge("tap", "B")))
        val b = ScreenNode(id = "B", app = "x", anchors = mapOf("列表" to 1), transitions = mapOf("b-c" to edge("tap", "C")))
        val c = ScreenNode(id = "C", app = "x", anchors = mapOf("详情" to 1))
        return ScreenGraph("x", mapOf("A" to a, "B" to b, "C" to c))
    }

    @Test fun pathReturnsShortestSequence() {
        val g = graph()
        val p = g.path("A", "C")
        assertEquals(listOf("tap", "tap"), p!!.map { it.action })
        assertEquals(listOf("B", "C"), p.map { it.to })
    }

    @Test fun pathToSelfIsEmpty() {
        assertEquals(emptyList<Transition>(), graph().path("A", "A"))
    }

    @Test fun unreachableReturnsNull() {
        assertNull(graph().path("C", "A"))
        assertNull(graph().path("A", "不存在的屏"))
    }

    @Test fun commonPrefixCountsLeadingIdenticalEdges() {
        val t1 = Transition("tap", "设置", TransitionEffect.NAVIGATED, "B", 1)
        val t2 = Transition("tap", "更多", TransitionEffect.NAVIGATED, "C", 1)
        val t3 = Transition("tap", "完成", TransitionEffect.NAVIGATED, "D", 1)
        val t4 = Transition("swipe", null, TransitionEffect.NAVIGATED, "E", 1)
        assertEquals(2, ScreenGraph.commonPrefix(listOf(t1, t2, t3), listOf(t1, t2, t4)))
        assertEquals(0, ScreenGraph.commonPrefix(listOf(t1), listOf(t2)))
        assertEquals(3, ScreenGraph.commonPrefix(listOf(t1, t2, t3), listOf(t1, t2, t3)))
    }

    @Test fun mergedWithAccumulatesSameId() {
        val base = ScreenGraph("x", mapOf("A" to ScreenNode(id = "A", app = "x", anchors = mapOf("主页" to 2),
            transitions = mapOf("a-b" to edge("tap", "B")), visits = 2, status = "confirmed")))
        val frag = ScreenGraph("x", mapOf("A" to ScreenNode(id = "A", app = "x", anchors = mapOf("主页" to 1, "设置" to 1),
            transitions = mapOf("a-b" to edge("tap", "B"), "a-c" to edge("tap", "C")), visits = 1, status = "provisional")))
        val m = base.mergedWith(frag)
        val node = m.nodes["A"]!!
        assertEquals(mapOf("主页" to 3, "设置" to 1), node.anchors)
        assertEquals(3, node.visits)
        assertEquals("confirmed", node.status)
        assertEquals(2, node.transitions["a-b"]!!.count)
        assertEquals(1, node.transitions["a-c"]!!.count)
    }

    @Test fun mergedWithAppendsNewId() {
        val base = ScreenGraph("x", mapOf("A" to ScreenNode(id = "A", app = "x", anchors = mapOf("主页" to 1))))
        val frag = ScreenGraph("x", mapOf("B" to ScreenNode(id = "B", app = "x", anchors = mapOf("设置" to 1), status = "provisional")))
        val m = base.mergedWith(frag)
        assertEquals(setOf("A", "B"), m.nodes.keys)
        assertEquals("provisional", m.nodes["B"]!!.status)
    }

    @Test fun mergedWithDifferentAppIsNoop() {
        val base = ScreenGraph("x", mapOf("A" to ScreenNode(id = "A", app = "x", anchors = mapOf("主页" to 1))))
        val frag = ScreenGraph("y", mapOf("A" to ScreenNode(id = "A", app = "y", anchors = mapOf("主页" to 1))))
        assertEquals(base, base.mergedWith(frag))
    }
}
