package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TriggerEngineTest {
    @Test fun recentWindowsAreLimitedToTheSelectedPlanAndNewestFirst() {
        val windows = (1L..12L).map { dueAt ->
            TriggerWindow("selected", dueAt, dueAt, state = TriggerWindowState.Delivered)
        }.reversed() + listOf(TriggerWindow("another", 13, 13), TriggerWindow("selected", 0, 14))
        val snapshot = TriggerSnapshot(emptyList(), windows)

        assertEquals(listOf(0L) + (12L downTo 4L), snapshot.recentWindows("selected", 10).map { it.dueAtMs })
        assertTrue(snapshot.recentWindows("never-run", 10).isEmpty())
    }

    @Test fun editingPlanInvalidatesOnlyThePendingConditionCheck() {
        val original = TriggerDefinition("check", "t", "old action", 1_000,
            conditionGoal = "old condition", intervalMs = 1_000)
        val store = MemoryTriggerStore()
        store.upsert(original)
        val engine = TriggerEngine(store, {}) { _, _ -> }
        engine.fire(1_000)
        val oldWindow = store.windows().single()
        assertEquals(TriggerWindowState.Dispatched, oldWindow.state)

        assertTrue(store.edit(original.copy(goal = "new action", conditionGoal = "new condition", nextAtMs = 2_000)))
        assertEquals(TriggerWindowState.Skipped, store.windows().single().state)
        assertFalse(store.mark(oldWindow.key, TriggerWindowState.ActionDispatched))

        engine.fire(2_000)
        val next = store.windows().single { it.dueAtMs == 2_000L }
        assertEquals(TriggerWindowState.Dispatched, next.state)
        assertTrue(store.mark(next.key, TriggerWindowState.ActionDispatched))
        assertTrue(store.edit(store.get(original.id)!!.copy(goal = "another action")))
        assertEquals(TriggerWindowState.ActionDispatched, store.windows().single { it.key == next.key }.state)
    }

    @Test fun editingOnlyTheScheduleKeepsTheLatestConditionHistory() {
        val original = TriggerDefinition("check", "t", "action", 1_000, conditionGoal = "condition")
        val current = original.copy(lastConditionMet = true, baselineValue = "new value", lastFiredAtMs = 1_000)
        val store = MemoryTriggerStore()
        store.upsert(current)
        assertTrue(store.edit(original.copy(nextAtMs = 2_000)))
        assertEquals(current.copy(nextAtMs = 2_000), store.get(original.id))
    }

    @Test fun idleOnlyDueWindowWaitsForWatchdogWithoutBeingClaimed() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("idle", "t", "do", 1_000, idleOnly = true))
        store.upsert(TriggerDefinition("normal", "t", "do", 1_000))
        var idle = false
        var armed: Long? = null
        val sent = mutableListOf<String>()
        val engine = TriggerEngine(store, { armed = it }, isIdle = { idle }, idleRetryMs = 500) { t, _ -> sent += t.id }
        engine.fire(1_000)
        assertEquals(listOf("normal"), sent)
        assertEquals(1_500L, armed)
        assertEquals(null, store.windows().firstOrNull { it.triggerId == "idle" })
        idle = true
        engine.fire(1_500)
        assertEquals(listOf("normal", "idle"), sent)
        assertEquals(1_000L, store.windows().first { it.triggerId == "idle" }.dueAtMs)
    }
    @Test fun claimsAndAdvancesBeforeDispatchAndCoalescesMissedIntervals() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("a", "t", "do", 1_000, intervalMs = 1_000))
        val seen = mutableListOf<TriggerWindow>()
        var armed: Long? = null
        val engine = TriggerEngine(store, { armed = it }) { trigger, window ->
            assertEquals("a", trigger.id)
            assertEquals(5_000L, store.get("a")!!.nextAtMs)
            assertEquals(TriggerWindowState.Claimed, store.windows().single().state)
            seen += window
        }
        engine.fire(4_100)
        assertEquals(1, seen.size)
        assertEquals(3L, seen.single().missedIntervals)
        assertEquals(64_100L, armed)
        engine.fire(4_100)
        assertEquals(1, seen.size)
    }

    @Test fun oneShotRetiresOnlyAfterConfirmedDelivery() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("once", "t", "do", 100))
        TriggerEngine(store, {}) { _, _ -> }.fire(100)
        assertFalse(store.get("once")!!.enabled)
        store.retireDeliveredOneShots()
        assertTrue(store.get("once") != null)
        store.mark("once:100", TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        assertEquals(null, store.get("once"))
    }

    @Test fun partialOutcomeAfterDeliveryKeepsOneShotAndStopsRetryAlarm() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("once", "t", "do", 100))
        var armed: Long? = null
        val engine = TriggerEngine(store, { armed = it }) { _, _ -> }

        engine.fire(100)
        assertEquals(TriggerWindowState.Dispatched, store.windows().single().state)
        assertTrue(store.mark("once:100", TriggerWindowState.Failed, "only part completed"))

        engine.rearm(200)
        assertEquals(null, armed)
        assertNotNull(store.get("once"))
    }

    @Test fun restartMarksUncertainClaimAndRearmsOverdueWindowOnce() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("a", "t", "do", 500))
        store.claimDue(500)
        var armed: Long? = null
        TriggerEngine(store, { armed = it }) { _, _ -> error("must not dispatch") }.rearm(1_000)
        assertEquals(TriggerWindowState.Unknown, store.windows().single().state)
        assertEquals(null, armed)
        assertTrue(store.claimDue(1_000).isEmpty())
    }

    // #259:跑过(这里是失败了)的一次性计划,恢复会被拒;就算已经是打开的(旧版本恢复过),也不再排「马上响」的闹钟空转
    @Test fun oneShotThatAlreadyRanCannotBeResumedAndNeverArmsAnAlarm() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("once", "t", "do", 1_000))
        TriggerEngine(store, {}) { _, _ -> }.fire(1_000)
        val armed = mutableListOf<Long?>()
        val sent = mutableListOf<String>()
        val engine = TriggerEngine(store, { armed += it }, idleRetryMs = 500) { t, _ -> sent += t.id }
        // 还在跑时(窗口进行中)照常隔一会儿重查,等它结束
        store.upsert(store.get("once")!!.copy(enabled = true))
        engine.rearm(2_000)
        assertEquals(2_500L, armed.single())
        store.upsert(store.get("once")!!.copy(enabled = false))
        armed.clear()
        store.mark("once:1000", TriggerWindowState.Failed)
        assertFalse(engine.setEnabled("once", true, 60_000))
        assertFalse(store.get("once")!!.enabled)
        assertTrue(armed.isEmpty())
        store.upsert(store.get("once")!!.copy(enabled = true))
        engine.fire(60_000)
        assertTrue(sent.isEmpty())
        assertEquals(listOf<Long?>(null), armed)
        // 改了时间就是新的时段,照常排上、到点照常跑
        store.upsert(store.get("once")!!.copy(nextAtMs = 120_000))
        engine.rearm(60_000)
        assertEquals(120_000L, armed.last())
        engine.fire(120_000)
        assertEquals(listOf("once"), sent)
    }

    @Test fun setEnabledPausesAndResumesRecurringPlansThatRanAndUnrunOneShotsAndReportsMissingOnes() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("once", "t", "do", 5_000))
        store.upsert(TriggerDefinition("every", "t", "do", 1_000, intervalMs = 1_000))
        var armed: Long? = null
        val engine = TriggerEngine(store, { armed = it }) { _, _ -> }
        engine.fire(1_000)
        store.mark("every:1000", TriggerWindowState.Delivered)
        assertTrue(engine.setEnabled("every", false, 1_500))
        assertEquals(5_000L, armed)
        assertTrue(engine.setEnabled("once", false, 1_500))
        assertEquals(null, armed)
        assertTrue(engine.setEnabled("once", true, 1_500))
        assertEquals(5_000L, armed)
        assertTrue(engine.setEnabled("every", true, 1_500))
        assertEquals(2_000L, armed)
        assertFalse(engine.setEnabled("missing", true, 1_500))
    }

    @Test fun dueWindowDoesNotOverlapUnfinishedPriorWindow() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("watch", "thread", "check", 1_000, 1_000))
        val engine = TriggerEngine(store, {}) { _, _ -> }
        engine.fire(1_000)
        engine.fire(2_000)
        assertEquals(1, store.windows().size)
        store.mark("watch:1000", TriggerWindowState.Delivered)
        engine.fire(2_000)
        assertEquals(2, store.windows().size)
    }

    @Test fun manualRunKeepsScheduledTime() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("watch", "thread", "check", 10_000, intervalMs = 5_000))
        val manual = store.claimNow("watch", 2_000)
        assertEquals("watch:2000", manual?.key)
        assertEquals(10_000L, store.get("watch")!!.nextAtMs)
        assertEquals(null, store.claimNow("watch", 2_001))
        store.mark(manual!!.key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        assertEquals(10_000L, store.get("watch")!!.nextAtMs)
    }

    @Test fun calendarRepeatKeepsLocalClockAcrossDaylightSavingChange() {
        val before = Instant.parse("2026-03-07T13:00:00Z").toEpochMilli()
        val first = nextCalendarAt("09:00", "America/New_York", emptyList(), before)
        val second = nextCalendarAt("09:00", "America/New_York", emptyList(), first)
        assertEquals("2026-03-07T14:00:00Z", Instant.ofEpochMilli(first).toString())
        assertEquals("2026-03-08T13:00:00Z", Instant.ofEpochMilli(second).toString())
    }

    @Test fun rezoneMovesFollowingLocalTimePlansToTheNewZone() {
        val store = MemoryTriggerStore()
        val created = Instant.parse("2026-11-01T01:00:00Z").toEpochMilli()
        store.upsert(
            TriggerDefinition(
                "local", "t", "do",
                nextCalendarAt("09:00", "Asia/Shanghai", emptyList(), created),
                calendarTime = "09:00", calendarZone = "Asia/Shanghai",
            )
        )
        val now = Instant.parse("2026-11-01T02:00:00Z").toEpochMilli()
        TriggerEngine(store, {}) { _, _ -> }.rezone(now, "Asia/Shanghai", ZoneId.of("America/New_York"))
        val zoned = store.get("local")!!
        assertEquals("America/New_York", zoned.calendarZone)
        assertEquals(
            nextCalendarAt("09:00", "America/New_York", emptyList(), now),
            zoned.nextAtMs,
        )
    }

    @Test fun rezoneLeavesPinnedIntervalAndDisabledTriggersAlone() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("tokyo", "t", "do", 5_000, calendarTime = "09:00", calendarZone = "Asia/Tokyo"))
        store.upsert(TriggerDefinition("interval", "t", "do", 5_000, intervalMs = 1_000))
        store.upsert(
            TriggerDefinition("disabled", "t", "do", 5_000, calendarTime = "09:00", calendarZone = "Asia/Shanghai", enabled = false)
        )
        TriggerEngine(store, {}) { _, _ -> }.rezone(6_000, "Asia/Shanghai", ZoneId.of("America/New_York"))
        assertEquals("Asia/Tokyo", store.get("tokyo")!!.calendarZone)
        assertEquals(5_000L, store.get("tokyo")!!.nextAtMs)
        assertEquals(5_000L, store.get("interval")!!.nextAtMs)
        assertEquals(5_000L, store.get("disabled")!!.nextAtMs)
    }

    @Test fun upcomingChainsCalendarRecurrencesInTheTriggersZone() {
        val created = Instant.parse("2026-11-01T01:00:00Z").toEpochMilli()
        val def = TriggerDefinition(
            "daily", "t", "do",
            nextCalendarAt("09:00", "Asia/Shanghai", emptyList(), created),
            calendarTime = "09:00", calendarZone = "Asia/Shanghai",
        )
        val after = Instant.parse("2026-11-02T02:00:00Z").toEpochMilli()
        val times = upcoming(def, after, 3)
        assertEquals(3, times.size)
        assertEquals(nextCalendarAt("09:00", "Asia/Shanghai", emptyList(), after), times[0])
        assertEquals(nextCalendarAt("09:00", "Asia/Shanghai", emptyList(), times[0]), times[1])
        assertEquals(nextCalendarAt("09:00", "Asia/Shanghai", emptyList(), times[1]), times[2])
    }

    @Test fun reschedulingIncludesEditedFirstTimeAndUsesItForCalendarRecurrences() {
        val edited = Instant.parse("2026-11-04T02:30:00Z").toEpochMilli()
        val def = TriggerDefinition(
            "daily", "t", "do", 0,
            calendarTime = "09:00", calendarZone = "Asia/Shanghai", enabled = false,
        ).rescheduledTo(edited)
        assertEquals("10:30", def.calendarTime)
        assertTrue(def.enabled)
        val times = upcoming(def, edited, 3)
        assertEquals(edited, times[0])
        assertEquals(nextCalendarAt("10:30", "Asia/Shanghai", emptyList(), edited), times[1])
        assertEquals(nextCalendarAt("10:30", "Asia/Shanghai", emptyList(), times[1]), times[2])
    }

    @Test fun upcomingSkipsMissedIntervalsAndLimitsOneShots() {
        val interval = TriggerDefinition("i", "t", "do", 1_000, intervalMs = 1_000)
        assertEquals(listOf(5_000L, 6_000L, 7_000L), upcoming(interval, 4_500, 3))
        assertEquals(listOf(5_000L, 6_000L, 7_000L), upcoming(interval.copy(nextAtMs = 5_000), 5_000, 3))
        val once = TriggerDefinition("once", "t", "do", 9_000)
        assertEquals(listOf(9_000L), upcoming(once, 8_000, 3))
    }
}
