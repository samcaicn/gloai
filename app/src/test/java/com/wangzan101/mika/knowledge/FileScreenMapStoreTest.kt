package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.screenmap.ScreenGraph
import com.wangzan101.mika.core.screenmap.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileScreenMapStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(id: String) = ScreenNode(id = id, app = "com.example.app", anchors = mapOf(id to 1))
    private fun graph(vararg ids: String) = ScreenGraph("com.example.app", ids.associate { it to node(it) })

    @Test fun roundTrip() {
        val store = FileScreenMapStore(tmp.root)
        val g = graph("A", "B")
        store.save("com.example.app", g)
        assertEquals(g, store.load("com.example.app"))
    }

    @Test fun loadMissingReturnsNull() {
        assertNull(FileScreenMapStore(tmp.root).load("com.nope"))
    }

    @Test fun corruptFileReturnsNullNotThrow() {
        val store = FileScreenMapStore(tmp.root)
        store.save("com.example.app", graph("A"))
        val f = File(tmp.root, "knowledge/apps/com.example.app/screens.json")
        f.writeText("{ 不是 json")
        assertNull(store.load("com.example.app"))
    }

    @Test fun atomicWriteLeavesNoTempAndReplacesWholeFile() {
        val store = FileScreenMapStore(tmp.root)
        store.save("com.example.app", graph("A"))
        val dir = File(tmp.root, "knowledge/apps/com.example.app")
        // 写完整后不残留 .tmp
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        // 再存一个不同内容 → 读到的是完整的新图，不是半写
        store.save("com.example.app", graph("B"))
        assertEquals(setOf("B"), store.load("com.example.app")!!.nodes.keys)
    }

    @Test fun appsListsSavedApps() {
        val store = FileScreenMapStore(tmp.root)
        store.save("com.a", ScreenGraph("com.a", emptyMap()))
        store.save("com.b", ScreenGraph("com.b", emptyMap()))
        assertEquals(setOf("com.a", "com.b"), store.apps().toSet())
    }
}
