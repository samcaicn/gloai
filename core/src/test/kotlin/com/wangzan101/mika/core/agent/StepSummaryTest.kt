package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折叠摘要的边界:事实去重、进展只留最近几行、结局种类不越上限。 */
class StepSummaryTest {
    private val result = buildJsonObject {
        put("currentApp", "com.tencent.mm")
        put("url", "https://a.example/")
        put("path", "/tmp/x")
    }

    @Test fun picksUpFactsFromToolResults() {
        val s = StepSummary("goal").next("step 1 → done", listOf(result), "plan")
        assertEquals("goal", s.goal)
        assertEquals(listOf("com.tencent.mm"), s.facts["apps visited"])
        assertEquals(listOf("https://a.example/"), s.facts["pages opened"])
        assertEquals(listOf("/tmp/x"), s.facts["files read or written"])
        assertEquals("plan", s.intent)
    }

    @Test fun repeatedFactsMoveToTheEndAndStayCapped() {
        var s = StepSummary("g")
        for (pkg in listOf("a", "b", "a", "c", "d", "e")) {
            s = s.next("x → done", listOf(buildJsonObject { put("currentApp", pkg) }), null)
        }
        assertEquals(listOf("b", "a", "c", "d", "e").takeLast(4), s.facts["apps visited"])
        assertEquals(4, s.facts["apps visited"]!!.size)
    }

    @Test fun earlierOutcomesNeverExceedTheKindCap() {
        var s = StepSummary("g")
        repeat(25) { i -> s = s.next("step $i → kind${i % 8}", emptyList(), null) }
        assertEquals(15, s.progress.size)
        assertEquals(10, s.earlier.values.sum())
        assertTrue("earlier kinds were ${s.earlier.size}: ${s.earlier}", s.earlier.size <= 5)
    }

    @Test fun aFewDistinctOutcomesAreKeptByName() {
        var s = StepSummary("g")
        repeat(25) { i -> s = s.next("step $i → kind${i % 2}", emptyList(), null) }
        assertEquals(2, s.earlier.size)
        assertEquals(10, s.earlier.values.sum())
    }

    @Test fun clipsLongValuesAndKeepsTheLastStatedIntent() {
        val long = "x".repeat(150)
        val s = StepSummary("g").next("step → done", listOf(buildJsonObject { put("currentApp", long) }), long)
        assertEquals(101, s.facts["apps visited"]!!.single().length)
        assertEquals(101, s.intent!!.length)
        val kept = s.next("step → done", emptyList(), "")
        assertEquals(s.intent, kept.intent)
    }

    @Test fun renderListsGoalFactsProgressAndIntent() {
        val text = StepSummary("goal").next("step 1 → done", listOf(result), "plan").render("HEADER")
        assertTrue(text.startsWith("HEADER\n"))
        assertTrue("Goal: goal" in text)
        assertTrue("apps visited: com.tencent.mm" in text)
        assertTrue("- step 1 → done" in text)
        assertTrue("last stated plan before folding: plan" in text)
    }
}
