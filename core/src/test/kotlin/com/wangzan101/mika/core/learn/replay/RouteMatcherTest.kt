package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.decision.DecisionChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MemRoutes(vararg r: Route) : RouteStore {
    val m = linkedMapOf<String, Route>().apply { r.forEach { put(it.id, it) } }
    override fun all() = m.values.toList()
    override fun put(r: Route) { m[r.id] = r }
    override fun remove(id: String) { m.remove(id) }
    override fun clear() = m.clear()
    override fun count() = m.size
}

class RouteMatcherTest {
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val memo = Route("m", "在备忘录里写{1}", 1, listOf("com.notes"), emptyMap(), emptyList(), lastUsedMs = 0)
    private val goal = "在备忘录里写今天天气不错"

    @Test fun decisionSelectsValidatedRouteWithoutCallingTextModel() = runTest {
        val m = FakeModel()
        val selected = RouteMatcher(m, MemRoutes(memo), null, DecisionModel { _, _, options, applicable ->
            assertEquals(memo.template, options.getValue("route_0"))
            assertTrue(!applicable.isNullOrBlank())
            DecisionChoice("route_0", 0.96, 0.98)
        }).choose(goal)
        assertEquals(RouteChoice(memo, listOf("今天天气不错")), selected)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun confidentSkipLeavesPlanningToMainModelWithoutCallingRouteFallback() = runTest {
        for (applicable in listOf(0.0, 0.99)) {
            val m = FakeModel(text("""{"route": 1, "slots": ["今天天气不错"]}"""))
            val decisions = mutableListOf<String>()
            val selected = RouteMatcher(m, MemRoutes(memo), null, DecisionModel { _, _, options, _ ->
                assertTrue("skip" in options)
                DecisionChoice("skip", 0.99, applicable)
            }, decisions::add).choose(goal)
            assertNull(selected)
            assertTrue(m.requests.isEmpty())
            assertEquals(listOf("skipped"), decisions)
        }
    }

    @Test fun decisionReceivesAndReturnsCompleteParameterWhitespace() = runTest {
        val content = "    indented\n"
        val route = memo.copy(template = "把以下内容写入备忘录：{1}")
        val m = FakeModel()
        val selected = RouteMatcher(m, MemRoutes(route), null, DecisionModel { state, _, _, _ ->
            assertEquals(JsonArray(listOf(JsonPrimitive(content))), state.jsonObject.getValue("routes")
                .jsonObject.getValue("route_0").jsonObject.getValue("boundParameters"))
            DecisionChoice("route_0", 0.99, 0.99)
        }).choose("把以下内容写入备忘录：$content")
        assertEquals(RouteChoice(route, listOf(content)), selected)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun uncertainOrInapplicableDecisionFallsBackToOriginalSlotValidation() = runTest {
        for (decision in listOf(
            DecisionModel { _, _, _, _ -> DecisionChoice("skip", 0.5, 0.0) },
            DecisionModel { _, _, _, _ -> DecisionChoice("skip", Double.NaN, 0.0) },
            DecisionModel { _, _, _, _ -> DecisionChoice("route_0", 0.5, 0.99) },
            DecisionModel { _, _, _, _ -> DecisionChoice("route_0", 0.99, 0.2) },
            DecisionModel { _, _, _, _ -> DecisionChoice("route_0", 0.99) },
            DecisionModel { _, _, _, _ -> DecisionChoice("not_a_route", 1.0, 1.0) },
            DecisionModel { _, _, _, _ -> throw IllegalStateException("down") },
            DecisionModel { _, _, _, _ -> awaitCancellation() },
        )) {
            val m = FakeModel(text("""{"route": 1, "slots": ["今天天气不错"]}"""))
            assertEquals(RouteChoice(memo, listOf("今天天气不错")), RouteMatcher(m, MemRoutes(memo), null, decision).choose(goal))
            assertEquals(1, m.requests.size)
        }
    }

    @Test fun ambiguousSlotsSkipDecisionAndCancellationDoesNotStartFallback() = runTest {
        val ambiguous = memo.copy(template = "给{1}发消息说{2}", slots = 2)
        var decisions = 0
        val m = FakeModel(text("""{"route":null}"""))
        assertNull(RouteMatcher(m, MemRoutes(ambiguous), null, DecisionModel { _, _, _, _ ->
            decisions++; DecisionChoice("route_0", 1.0, 1.0)
        }).choose("给张三发消息说李四发消息说你好"))
        assertEquals(0, decisions)
        val untouched = FakeModel()
        try {
            RouteMatcher(untouched, MemRoutes(memo), null, DecisionModel { _, _, _, _ -> throw CancellationException("stopped") }).choose(goal)
            org.junit.Assert.fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(untouched.requests.isEmpty())
        }
    }

    @Test fun noCandidatesNoModelCall() = runTest {
        val m = FakeModel()
        assertNull(RouteMatcher(m, MemRoutes(memo), null).choose("打开计算器算一下"))
        assertTrue(m.requests.isEmpty())
        assertNull(RouteMatcher(m, MemRoutes(), null).choose(goal))
        assertTrue(m.requests.isEmpty())
    }

    @Test fun choosesWithSlotsFromGoal() = runTest {
        val m = FakeModel(text("""好的 {"route": 1, "slots": ["今天天气不错"]}"""))
        assertEquals(RouteChoice(memo, listOf("今天天气不错")), RouteMatcher(m, MemRoutes(memo), null).choose(goal))
        assertEquals(1, m.requests.size)
    }

    @Test fun slotNotInGoalRejected() = runTest {
        val m = FakeModel(text("""{"route": 1, "slots": ["明天开会"]}"""))
        assertNull(RouteMatcher(m, MemRoutes(memo), null).choose(goal))
    }

    @Test fun slotsMustAlignWithTemplate() = runTest {
        val msg = memo.copy(id = "c", template = "给{1}发消息说{2}", slots = 2)
        // 「张三」只是「张三丰」的一部分:填回模板得不到原话
        assertNull(RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["张三", "晚上好"]}""")), MemRoutes(msg), null).choose("给张三丰发消息说晚上好"))
        // 原话多了模板里没有的要求
        assertNull(RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["今天天气不错"]}""")), MemRoutes(memo), null).choose("在备忘录里写今天天气不错并置顶"))
        // 槽值在原话里出现不止一次
        assertNull(RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["好", "好"]}""")), MemRoutes(msg), null).choose("给好发消息说好"))
        // 只差空白:算同一句
        assertEquals(RouteChoice(msg, listOf("张三丰", "晚上好")),
            RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["张三丰", "晚上好"]}""")), MemRoutes(msg), null).choose("给 张三丰 发消息说晚上好"))
    }

    @Test fun malformedJsonIsNull() = runTest {
        for (bad in listOf("不知道", """{"route": "一"}""", """{"route": 1, "slots": ["a", "b"]}""", """{"route": 7, "slots": []}""", """{"route": null}""")) {
            assertNull(bad, RouteMatcher(FakeModel(text(bad)), MemRoutes(memo), null).choose(goal))
        }
        assertNull(RouteMatcher(FakeModel(RuntimeException("down")), MemRoutes(memo), null).choose(goal))
    }

    @Test fun disabledRoutesExcluded() {
        val off = memo.copy(id = "off", disabled = true)
        assertEquals(listOf("m"), RouteMatcher(FakeModel(), MemRoutes(memo, off), null).candidates(goal).map { it.id })
        assertTrue(RouteMatcher(FakeModel(), MemRoutes(off), null).candidates(goal).isEmpty())
    }

    @Test fun candidatesRankedAndLimited() {
        val routes = (1..7).map { memo.copy(id = "r$it", template = "在备忘录里写{1}" + "很".repeat(it)) }
        val got = RouteMatcher(FakeModel(), MemRoutes(*routes.toTypedArray()), null).candidates(goal)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), got.map { it.id })
    }
}
