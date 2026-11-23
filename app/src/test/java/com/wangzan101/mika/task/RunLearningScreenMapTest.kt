package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.screen.map.ScreenGraph
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.core.assembly.knownTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 内存里的屏图仓库,可配置 save 抛错。 */
private class MemStore(private val graphs: MutableMap<String, ScreenGraph> = mutableMapOf()) : ScreenMapStore {
    var failSave = false
    override fun load(app: String) = graphs[app]
    override fun save(app: String, graph: ScreenGraph) {
        if (failSave) throw RuntimeException("write failed")
        graphs[app] = graph
    }
    override fun apps() = graphs.keys.toList()
}

class RunLearningScreenMapTest {
    private val catalog = GuideCatalog.loadBundled()

    private fun tapEvent(before: List<String>, after: List<String>, pkg: String = "p.a") = RunEvent.ActionTarget(
        atMs = 0, step = 1, tool = "tap", argsJson = "{}", target = null,
        before = Anchors(pkg, before), after = Anchors(pkg, after), effective = true,
    )

    private fun aftermath(events: () -> List<RunEvent>) = RunAftermath(
        "conv-1", "run-1", "点外卖", TaskState.Succeeded("ok"), emptyList(), emptyList(), listOf("x"), emptySet(), knownTools(),
        events = events,
    )

    private fun learn(store: ScreenMapStore, events: List<RunEvent>) = runBlocking {
        RunLearning(MemorySwitches(), null, MapSkills(), catalog, null, null, { "id" }, screenMaps = store)
            .learn(aftermath({ events }))
    }

    @Test fun growsGraphFromRunEvents() {
        val store = MemStore()
        learn(store, listOf(tapEvent(listOf("首页"), listOf("设置"))))
        val g = store.load("p.a")!!
        assertEquals(2, g.nodes.size)
        // 认得出两屏各带骨架锚点
        assertNotNull(g.nodes.values.firstOrNull { it.anchors.containsKey("首页") })
        assertNotNull(g.nodes.values.firstOrNull { it.anchors.containsKey("设置") })
    }

    @Test fun emptyEventsNoGraphNoThrow() {
        val store = MemStore()
        learn(store, emptyList())
        assertNull(store.load("p.a"))
    }

    @Test fun writeFailureDoesNotThrow() {
        val store = MemStore().apply { failSave = true }
        // 不抛、结局照旧(learn 返回 notes)
        val notes = learn(store, listOf(tapEvent(listOf("首页"), listOf("设置"))))
        assertEquals("run-1", notes.runId)
    }
}
