package com.wangzan101.mika.core.trigger

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEditorTimeTest {
    private val now = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
    private val due = Instant.parse("2026-11-13T01:30:45.123Z").toEpochMilli()

    private fun plan(intervalMs: Long? = null) = TriggerDefinition(
        "plan", "thread", "old goal", due, intervalMs = intervalMs,
    )

    @Test fun contentEditsPreserveTheExactOneShotAndIntervalTimes() {
        for (interval in listOf(null, 60_000L)) {
            val original = plan(interval)
            val edited = edit(original, original.toPlanDraft().copy(content = "new goal"))

            assertEquals("new goal", edited.goal)
            assertEquals(due, edited.nextAtMs)
            assertEquals(interval, edited.intervalMs)
            assertEquals(due, upcoming(edited, now, 3).first())
        }
    }

    @Test fun aFutureTimeInTheCurrentMinuteCanStillBeEdited() {
        val original = plan()
        val edited = edit(original, original.toPlanDraft().copy(content = "new goal"), due - 1_000)

        assertEquals(due, edited.nextAtMs)
    }

    @Test fun editingAnOverduePlanKeepsItsExistingTimeAndLedgerWindow() {
        for (interval in listOf(null, 60_000L)) {
            val original = plan(interval).copy(enabled = false)
            val window = TriggerWindow(original.id, due, due, state = TriggerWindowState.Delivered)
            val store = MemoryTriggerStore(TriggerSnapshot(listOf(original), listOf(window)))
            val edited = edit(original, original.toPlanDraft().copy(content = "new goal"), due + 1_000)

            assertTrue(store.edit(edited))
            assertEquals(due, store.get(original.id)!!.nextAtMs)
            assertFalse(store.get(original.id)!!.enabled)
            assertEquals(listOf(window), store.windows())
            assertTrue(store.get(original.id)!!.slotTaken(store.windows()))
        }
    }

    @Test fun changingTheLocalTimeUsesTheNewMinute() {
        val original = plan()
        val draft = original.toPlanDraft()
        val newTime = Instant.parse("2026-11-13T02:15:00Z").toEpochMilli()
        val edited = edit(original, draft.copy(firstAt = formatPlanEditorTime(newTime, draft.zone)))

        assertEquals(newTime, edited.nextAtMs)
    }

    @Test fun changingTheZoneReinterpretsTheLocalTime() {
        val original = plan()
        val draft = original.toPlanDraft()
        val local = Instant.ofEpochMilli(due).atZone(ZoneId.of(draft.zone)).toLocalDateTime()
            .withSecond(0).withNano(0)
        val zone = if (ZoneId.of(draft.zone).rules == ZoneId.of("UTC").rules) "Asia/Shanghai" else "UTC"
        val edited = edit(original, draft.copy(zone = zone), now - 86_400_000)

        assertEquals(local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli(), edited.nextAtMs)
    }

    @Test fun anExplicitIsoTimeCanChangeTheSecondsWithinTheSameMinute() {
        val original = plan()
        val newTime = Instant.parse("2026-11-13T01:30:00Z")
        val edited = edit(original, original.toPlanDraft().copy(firstAt = newTime.toString()))

        assertEquals(newTime.toEpochMilli(), edited.nextAtMs)
    }

    /** 日期/时间选择器读编辑框:当地格式和 ISO 都要认,填错的返回空让用户继续改。 */
    @Test fun pickersReadLocalAndIsoEditorTimes() {
        assertEquals(
            LocalDateTime.of(2026, 11, 13, 9, 30),
            parsePlanEditorTime("2026-11-13 09:30", "Asia/Shanghai"),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 13, 9, 30, 45),
            parsePlanEditorTime("2026-11-13T01:30:45Z", "Asia/Shanghai"),
        )
        assertNull(parsePlanEditorTime("2026-02-30 08:30", "Asia/Shanghai"))
        assertNull(parsePlanEditorTime("", "Asia/Shanghai"))
        assertNull(parsePlanEditorTime("2026-11-13 09:30", "not/a zone"))
    }

    /** 时刻芯片的解析和写回与保存规则一致:排序、去重、补零;有坏条目就不出芯片。 */
    @Test fun clockChipsRoundTripTheSameTextTheEditorSaves() {
        assertEquals(
            listOf(LocalTime.of(8, 0), LocalTime.of(12, 30), LocalTime.of(20, 0)),
            planClockEntries("12:30，08:00、20:00"),
        )
        assertEquals("08:00, 09:30", normalizedClockText(listOf(LocalTime.of(9, 30), LocalTime.of(8, 0), LocalTime.of(9, 30))))
        assertEquals("00:05", normalizedClockText(listOf(LocalTime.of(0, 5))))
        assertNull(planClockEntries("25:00"))
        assertNull(planClockEntries("08:00, later"))
        assertNull(planClockEntries(""))
    }

    @Test fun preservingTheTimeDoesNotAllowResumingAnOverduePlan() {
        val original = plan().copy(enabled = false)
        val result = runCatching {
            edit(original, original.toPlanDraft().copy(enabled = true), due + 1_000)
        }

        assertTrue(result.exceptionOrNull() is PlanValidationException)
        assertEquals(PlanValidation.TimeNotFuture, (result.exceptionOrNull() as PlanValidationException).reason)
    }

    private fun edit(original: TriggerDefinition, draft: PlanDraft, at: Long = now) =
        editedTrigger(draft, original, "unused", "unused", at)
}
