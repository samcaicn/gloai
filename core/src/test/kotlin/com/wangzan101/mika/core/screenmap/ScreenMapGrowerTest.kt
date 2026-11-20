package com.wangzan101.mika.core.screenmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 内存里的屏图仓库,记录写入,可配置 save 抛错。 */
private class MemStore(private val graphs: MutableMap<String, ScreenGraph> = mutableMapOf()) : ScreenMapStore {
    var failSave = false
    override fun load(app: String) = graphs[app]
    override fun save(app: String, graph: ScreenGraph) {
        if (failSave) throw RuntimeException("write failed")
        graphs[app] = graph
    }
    override fun apps() = graphs.keys.toList()
}

class ScreenMapGrowerTest {
    private fun node(id: String, app: String, anchors: Map<String, Int>) =
        ScreenNode(id = id, app = app, anchors = anchors)

    @Test fun growMergesFragmentIntoStoredGraph() {
        val store = MemStore(mutableMapOf(
            "p.a" to ScreenGraph("p.a", mapOf("home" to node("home", "p.a", mapOf("首页" to 2)))),
        ))
        val fragment = ScreenGraph("p.a", mapOf(
            "home" to node("home", "p.a", mapOf("首页" to 1, "我的" to 1)),
            "editor" to node("editor", "p.a", mapOf("新笔记" to 1)),
        ))
        ScreenMapGrower.grow(store, mapOf("p.a" to fragment))
        val g = store.load("p.a")!!
        assertEquals(setOf("home", "editor"), g.nodes.keys)
        assertEquals(mapOf("首页" to 3, "我的" to 1), g.nodes["home"]!!.anchors)
    }

    @Test fun growCreatesGraphWhenNoneStored() {
        val store = MemStore()
        ScreenMapGrower.grow(store, mapOf("p.a" to ScreenGraph("p.a", mapOf("home" to node("home", "p.a", mapOf("首页" to 1))))))
        assertNotNull(store.load("p.a"))
    }

    @Test fun growSkipsEmptyFragment() {
        val store = MemStore()
        ScreenMapGrower.grow(store, mapOf("p.a" to ScreenGraph("p.a", emptyMap())))
        assertNull(store.load("p.a"))
    }

    @Test fun growLeavesOtherAppsUntouched() {
        val store = MemStore(mutableMapOf("p.other" to ScreenGraph("p.other", mapOf("x" to node("x", "p.other", mapOf("字" to 1))))))
        ScreenMapGrower.grow(store, mapOf("p.a" to ScreenGraph("p.a", mapOf("home" to node("home", "p.a", mapOf("首页" to 1))))))
        assertEquals(1, store.load("p.other")!!.nodes.size)
    }
}
