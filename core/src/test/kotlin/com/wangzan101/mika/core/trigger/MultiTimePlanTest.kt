package com.wangzan101.mika.core.trigger

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MultiTimePlanTest {
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun previewAndClaimsVisitEveryClockWithoutDuplicateSlots() {
        val now = at("2026-11-10T00:00:00Z")
        val plan = editedTrigger(newPlanDraft(now).copy(content = "check", repeat = PlanRepeat.Daily,
            clock = "20:00, 08:00, 12:30, 08:00", zone = "Asia/Shanghai"), null, "plan", "thread", now)
        assertEquals(listOf("08:00", "12:30", "20:00"), plan.calendarClocks())
        assertEquals(listOf(at("2026-11-10T04:30:00Z"), at("2026-11-10T12:00:00Z"), at("2026-11-11T00:00:00Z")), upcoming(plan, now, 3))
        val store = MemoryTriggerStore(TriggerSnapshot(listOf(plan), emptyList()))
        val first = store.claimDue(plan.nextAtMs).single()
        assertTrue(store.claimDue(plan.nextAtMs).isEmpty())
        store.mark(first.key, TriggerWindowState.Delivered)
        assertEquals(at("2026-11-10T12:00:00Z"), store.get("plan")!!.nextAtMs)
        assertEquals(1, store.claimDue(at("2026-11-10T12:00:00Z")).size)
    }

    @Test fun daylightSavingGapDoesNotDispatchTheSameInstantTwice() {
        val now = at("2026-03-08T06:00:00Z")
        val clocks = listOf("02:30", "03:30")
        val first = nextCalendarAt(clocks, "America/New_York", emptyList(), now)
        assertEquals(at("2026-03-08T07:30:00Z"), first)
        assertEquals(at("2026-03-09T06:30:00Z"), nextCalendarAt(clocks, "America/New_York", emptyList(), first))
    }

    @Test fun weeklyTimesAndRescheduleKeepTheirExplicitSemantics() {
        val now = at("2026-11-10T00:00:00Z")
        val plan = editedTrigger(newPlanDraft(now).copy(content = "check", repeat = PlanRepeat.Weekly,
            clock = "08:00,20:00", zone = "Asia/Shanghai", weekdays = setOf(1, 5)), null, "p", "t", now)
        assertEquals(at("2026-11-13T00:00:00Z"), plan.nextAtMs)
        assertEquals(at("2026-11-13T12:00:00Z"), upcoming(plan, now, 2)[1])
        val rescheduled = plan.rescheduledTo(at("2026-11-13T01:30:00Z"))
        assertEquals(listOf("09:30", "20:00"), rescheduled.calendarClocks())
        assertEquals(listOf(
            at("2026-11-13T01:30:00Z"),
            at("2026-11-13T12:00:00Z"),
            at("2026-11-16T01:30:00Z"),
        ), upcoming(rescheduled, at("2026-11-13T01:30:00Z"), 3))
    }
}
