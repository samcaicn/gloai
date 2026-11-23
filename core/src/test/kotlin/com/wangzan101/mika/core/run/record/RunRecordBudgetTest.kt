package com.wangzan101.mika.core.run.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunRecordBudgetTest {
    @Test fun summaryKeepsTheStepLimit() {
        val summary = RunSummary("r", "goal", startedAtMs = 0)
            .with(RunEvent.TaskStarted(1, "goal", "model", stepLimit = 60))
        assertEquals(60, summary.stepLimit)
    }

    @Test fun oldSummaryWithoutStepLimitStillLoads() {
        val old = """{"id":"r","goal":"goal","startedAtMs":0}"""
        assertNull(RunEvent.json.decodeFromString(RunSummary.serializer(), old).stepLimit)
    }

    @Test fun taskStartedTokenBudgetRoundTripsAndOldRecordsDefaultToUnlimited() {
        val started = RunEvent.TaskStarted(1, "goal", "model", stepLimit = 60, tokenBudget = 500_000)
        val back = RunEvent.decode(RunEvent.encode(started)) as RunEvent.TaskStarted
        assertEquals(500_000L, back.tokenBudget)
        assertEquals(60, back.stepLimit)
        val old = """{"type":"task_started","atMs":1,"goal":"goal","model":"model"}"""
        assertNull((RunEvent.decode(old) as RunEvent.TaskStarted).tokenBudget)
    }

    @Test fun stepLimitRoundTrips() {
        val summary = RunSummary("r", "goal", startedAtMs = 0, stepLimit = 120)
        val back = RunEvent.json.decodeFromString(RunSummary.serializer(), RunEvent.json.encodeToString(RunSummary.serializer(), summary))
        assertEquals(120, back.stepLimit)
    }
}
