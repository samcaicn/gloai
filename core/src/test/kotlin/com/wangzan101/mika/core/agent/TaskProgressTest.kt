package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskProgressTest {
    @Test fun progressBlockMergesQuantityAndBoundaryWithoutLeakingIntoNarration() {
        val first = CompletionPlanBlock.extract("""
            <task_progress>{"criteria":["36 条都已保存"],"metric":{"name":"已保存","done":12,"total":36,"boundary":"第 12 条"},"complete":false}</task_progress>
            继续保存
        """.trimIndent())
        assertEquals("继续保存", first.text)
        assertTrue(first.errors.isEmpty())
        val plan = CompletionPlan().apply(first.updates.single())
        assertEquals(listOf("36 条都已保存"), plan.criteria)
        assertEquals(12, plan.metric?.done)
        assertEquals("第 12 条", plan.metric?.boundary)
        assertFalse(plan.complete)

        val update = CompletionPlanBlock.extract("""<task_progress>{"metric":{"done":36,"boundary":"第 36 条"}}</task_progress>""")
        val done = plan.apply(update.updates.single())
        assertEquals(36, done.metric?.done)
        assertEquals(36, done.metric?.total)
        assertEquals("第 36 条", done.metric?.boundary)
        assertTrue(done.complete)
        assertEquals("已保存 · 36/36 · 第 36 条", done.metric?.state()?.display)

        val event = RunEvent.decode(RunEvent.encode(done.event(7))) as RunEvent.Criteria
        assertEquals(RunEvent.Progress("已保存", 36, 36, "第 36 条"), event.progress)
        assertTrue(event.complete)
    }

    @Test fun explicitCriteriaCompletionDoesNotRequireAMetric() {
        val parsed = CompletionPlanBlock.extract("""<task_progress>{"criteria":["已回答目标"],"complete":true}</task_progress>完成""")
        val plan = CompletionPlan().apply(parsed.updates.single())
        assertTrue(plan.complete)
        assertNull(plan.metric)
        assertEquals("完成", parsed.text)
    }

    @Test fun invalidConfirmationsAndDuplicatesCannotAdvanceOrRefreshTheChecklist() {
        val events = mutableListOf<RunEvent>()
        val tracker = TaskProgressTracker({ 0L }, events::add)
        fun update(step: Int, json: String) = tracker.accept("<task_progress>$json</task_progress>", step)
        update(1, """{"criteria":["A","B"]}""")
        update(2, """{"confirmed":[{"id":1,"evidence":"observed A"}]}""")
        update(3, """{"confirmed":[{"id":3,"evidence":"out of range"}]}""")
        update(4, """{"confirmed":[{"id":2,"evidence":"  "}]}""")
        update(4, """{"criteria":["B","A"]}""")
        update(5, """{"confirmed":[{"id":1,"evidence":"A again"}]}""")
        assertEquals(3, events.filterIsInstance<RunEvent.State>().count { it.name == "task_progress_invalid" })
        assertEquals(2, events.filterIsInstance<RunEvent.Criteria>().size)
        assertEquals(GoalChecklist(listOf("A", "B"), setOf(1), true), tracker.state(5)?.goals)
        update(6, """{"confirmed":[{"id":2,"evidence":"observed B"}]}""")
        assertEquals(GoalChecklist(listOf("A", "B"), setOf(1, 2), false), tracker.state(10)?.goals)
        assertNull(tracker.pending(10))
        val saved = RunEvent.decode(RunEvent.encode(events.last())) as RunEvent.Criteria
        assertEquals(setOf(1, 2), saved.confirmedIds)
    }

    @Test fun subquantityForSingleGoalDoesNotFinishTheTask() {
        val tracker = TaskProgressTracker({ 0L }) {}
        tracker.accept("""<task_progress>{"criteria":["总结五篇笔记"],"metric":{"name":"已读","scope":"part","done":4,"total":5}}</task_progress>""", 1)
        tracker.accept("""<task_progress>{"metric":{"done":5}}</task_progress>""", 2)
        assertFalse(tracker.plan.complete)
        tracker.accept("""总结好了<task_progress>{"confirmed":[{"id":1,"evidence":"已给出三点小结"}]}</task_progress>""", 3)
        assertTrue(tracker.plan.complete)
    }

    @Test fun malformedProgressIsRemovedAndReportedWithoutBreakingTheReply() {
        val parsed = CompletionPlanBlock.extract("""<task_progress>{"metric":{"done":-1}}</task_progress>继续""")
        assertEquals("继续", parsed.text)
        assertTrue(parsed.updates.isEmpty())
        assertTrue(parsed.errors.isNotEmpty())
        assertFalse(CompletionPlan().complete)
    }

    @Test fun incompleteProgressIsHiddenFromStreamingAndReportedAtTheEnd() {
        assertEquals("保存", CompletionPlanBlock.visible("保存<task_pro"))
        assertEquals("保存", CompletionPlanBlock.visible("保存<task_progress>{\"done\":"))
        assertEquals("前后", CompletionPlanBlock.visible("前<task_progress>{\"done\":1}</task_progress>后"))
        val parsed = CompletionPlanBlock.extract("继续<task_progress>{\"done\":")
        assertEquals("继续", parsed.text)
        assertTrue(parsed.errors.any { "unclosed" in it })
    }

    @Test fun onlyRealNewDataResetsTheStreak() {
        val tracker = NewDataTracker()
        fun seen(text: String, verb: Verb = Verb.Observe) = tracker.after(verb, exec(text))
        assertFalse(seen("第 1 条"))
        assertFalse(seen("第 1 条"))
        assertEquals(1, tracker.stale)
        assertFalse(seen("第 1 条", Verb.Tap)) // 非采集调用既不清零也不递增
        assertEquals(1, tracker.stale)
        assertFalse(seen("第 2 条", Verb.Scroll))
        assertEquals(0, tracker.stale)
        assertFalse(seen("第 2 条", Verb.Scroll))
        assertFalse(seen("第 2 条", Verb.Scroll))
        assertTrue(seen("第 2 条", Verb.Scroll))
        assertEquals(NewDataTracker.LIMIT, tracker.stale)
    }

    @Test fun overlappingScreensWithAnyNewContentAreProgress() {
        val tracker = NewDataTracker()
        assertFalse(tracker.after(Verb.Scroll, exec("旧条目\n新条目 1")))
        assertFalse(tracker.after(Verb.Scroll, exec("新条目 1\n新条目 2")))
        assertEquals(0, tracker.stale)
    }

    private fun exec(text: String) = ToolExecution(
        buildJsonObject { put("ok", true) },
        observations = listOf(Observation(ObsKind.Read, text, place = Place.app("example.app"))),
    )
}
