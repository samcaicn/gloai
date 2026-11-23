package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.learn.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.Surface
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.screen.map.ScreenGraph
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.core.screen.map.ScreenNode
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.assembly.toolDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 带固定元素的一屏,peek 也返回同一屏,认屏认得出。 */
private class MapScreen(private val els: List<UiElement>, private val pkg: String = "p.a") : ScreenPort by FakeScreen() {
    var peekCount = 0
    override suspend fun snapshot() = ScreenSnapshot("s", pkg, els, 1080, 2400)
    override suspend fun peek(): ScreenSnapshot {
        peekCount++
        return ScreenSnapshot("peek", pkg, els, 1080, 2400)
    }
}

private class MemMapStore(private val graphs: Map<String, ScreenGraph>) : ScreenMapStore {
    override fun load(app: String) = graphs[app]
    override fun save(app: String, graph: ScreenGraph) {}
    override fun apps() = graphs.keys.toList()
}

class AgentLoopScreenMapTest {
    private val graph = ScreenGraph("p.a", mapOf(
        "home" to ScreenNode("home", "p.a", anchors = mapOf("首页" to 1, "我的" to 1)),
        "search" to ScreenNode("search", "p.a", anchors = mapOf("搜索" to 1, "结果" to 1)),
    ))

    private fun el(id: String, name: String) =
        UiElement(id, "text", name, 100, 100, listOf(0, 0, 999, 60), null, true, false, false)

    private fun call(name: String, args: String = "{}") = Part.ToolCall("k$name${args.hashCode()}", name, args)
    private fun reply(vararg c: Part.ToolCall) = com.wangzan101.mika.core.model.ModelReply(
        c.toList(), com.wangzan101.mika.core.model.StopReason.ToolUse, null)

    private fun run(screen: ScreenPort, store: ScreenMapStore?, vararg script: Any): List<Message> {
        val knowledge = KnowledgeStore(listOf(KnowledgeEntry("alpha", "alpha", Surface.Android, listOf("p.a"),
            emptyList(), listOf("alpha"), null, "", "APP.md正文")))
        val model = FakeModel(*script)
        runBlocking { AgentLoop(model, toolDispatcher(screen), "B", knowledge = knowledge, screenMaps = store).run("随便") }
        return model.requests.last()
    }

    private fun List<Message>.screenMaps() = filter { ContextBuilder.kindOf(it) == ContextKind.ScreenMap }
        .map { "${ContextBuilder.sourceOf(it)}\n${ContextBuilder.bodyOf(it)}" }

    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")

    @Test fun injectsScreenMapAfterAppKnowledgeOnForeground() {
        val msgs = run(MapScreen(listOf(el("@h", "首页"), el("@m", "我的"))), MemMapStore(mapOf("p.a" to graph)),
            reply(call("phone_observe")), reply(call("phone_list_elements")), finish)
        assertTrue(msgs.refs().toString(), msgs.refs().any { it.contains("APP.md正文") })
        val maps = msgs.screenMaps()
        assertEquals(1, maps.size)
        val map = maps.single()
        assertTrue(map.contains("屏（2）"))
        assertTrue(map.contains("首页"))
        assertTrue(map.contains("我的"))
        assertTrue(map.contains("搜索"))
        assertTrue(map.contains("结果"))
        assertTrue(map.contains("现在在「")) // 认得出当前屏 → 带位置
    }

    @Test fun noScreenMapWhenNoGraph() {
        val msgs = run(MapScreen(listOf(el("@h", "首页"))), MemMapStore(emptyMap()),
            reply(call("phone_observe")), reply(call("phone_list_elements")), finish)
        assertTrue(msgs.refs().any { it.contains("APP.md正文") })
        assertEquals(0, msgs.screenMaps().size)
    }

    @Test fun noScreenMapWhenScreenUnreadable() {
        // 读不到屏（peek 抛异常 → ScreenSession 兜底为 null），不注入屏图，任务照常
        val unreadable = object : ScreenPort by FakeScreen() {
            override suspend fun peek(): ScreenSnapshot = throw RuntimeException("unreadable")
        }
        val msgs = run(unreadable, MemMapStore(mapOf("p.a" to graph)),
            reply(call("phone_observe")), reply(call("phone_list_elements")), finish)
        assertEquals(0, msgs.screenMaps().size)
    }

    @Test fun doesNotReReadScreenAfterInjection() {
        // 注过一次屏图之后,同 App 后续步骤不再读屏读盘(去重在前,别每步都 peek)
        val screen = MapScreen(listOf(el("@h", "首页"), el("@m", "我的")))
        val msgs = run(screen, MemMapStore(mapOf("p.a" to graph)),
            reply(call("phone_observe")), reply(call("phone_list_elements")), finish)
        assertEquals(1, msgs.screenMaps().size)
        assertEquals(1, screen.peekCount)
    }

    @Test fun screenMapSurvivesCompression() {
        val model = FakeModel(reply(call("phone_observe")),
            *(1..15).map { reply(call("wait", """{"seconds":${it / 10.0}}""")) }.toTypedArray(), finish, contextTokens = 100)
        runBlocking {
            AgentLoop(model, toolDispatcher(MapScreen(listOf(el("@h", "首页"), el("@m", "我的")))),
                "B", knowledge = KnowledgeStore(listOf(KnowledgeEntry("alpha", "alpha", Surface.Android, listOf("p.a"),
                    emptyList(), listOf("alpha"), null, "", "APP.md正文"))),
                screenMaps = MemMapStore(mapOf("p.a" to graph))).run("随便")
        }
        val last = model.requests.last()
        assertEquals(1, last.screenMaps().size)
        assertTrue(last.any { m -> m.plainText().startsWith(ContextCompressor.HEADER) })
    }

    private fun List<Message>.refs() = filter { ContextBuilder.kindOf(it) == ContextKind.AppKnowledge }
        .map { "${ContextBuilder.sourceOf(it)}\n${ContextBuilder.bodyOf(it)}" }
}
