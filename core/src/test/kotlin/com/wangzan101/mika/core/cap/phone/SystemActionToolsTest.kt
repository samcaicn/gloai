package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SystemActionToolsTest {
    private val zone = ZoneId.systemDefault()
    private fun at(h: Int, m: Int, plusDays: Long = 0): Long =
        LocalDateTime.now(zone).withHour(h).withMinute(m).withSecond(0).withNano(0).plusDays(plusDays).atZone(zone).toInstant().toEpochMilli()

    private class SystemScreen(var answer: (SystemAction) -> SystemActionResult) : ScreenPort by FakeScreen() {
        val asked = mutableListOf<SystemAction>()
        val bulkAsked = mutableListOf<List<SystemAction.AddCalendarEvent>>()
        var bulkAnswer: (List<SystemAction.AddCalendarEvent>) -> SystemActionResult = {
            SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
        }
        override suspend fun systemAction(action: SystemAction): SystemActionResult { asked += action; return answer(action) }
        override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult {
            bulkAsked += events
            return bulkAnswer(events)
        }
    }

    private fun call(name: String, args: String) = Part.ToolCall("c", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun alarmIsHandedToTheClockWithAllItsFields() = runTest {
        val screen = SystemScreen { SystemActionResult(ActionResult.Ok(), "com.example.clock", alarmReadable = true, nextAlarmAfter = at(9, 40, 1)) }
        val r = toolDispatcher(screen).execute(call("phone_set_alarm", """{"hour":9,"minute":40,"label":"开会","days":["mon","FRI","Mon"],"vibrate":true}""")).content
        assertEquals(listOf(SystemAction.SetAlarm(9, 40, "开会", listOf(2, 6), true)), screen.asked)
        assertEquals("true", r.s("ok"))
        assertEquals("com.example.clock", r.s("handledBy"))
        assertEquals("09:40", r["requested"]!!.jsonObject.s("time"))
        assertEquals(listOf("MON", "FRI"), r["requested"]!!.jsonObject["days"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("verified", r.s("check"))
        assertNull(r["hint"])
    }

    @Test fun earlierAlarmHidesTheNewOne() = runTest {
        // 一分钟后就有一个闹钟;要设的是两小时后的
        val earlier = System.currentTimeMillis() + 60_000
        val later = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 2 * 3_600_000).atZone(zone)
        val screen = SystemScreen { SystemActionResult(ActionResult.Ok(), "com.example.clock", alarmReadable = true, nextAlarmBefore = earlier, nextAlarmAfter = earlier) }
        val r = toolDispatcher(screen).execute(call("phone_set_alarm", """{"hour":${later.hour},"minute":${later.minute}}""")).content
        assertEquals("earlier_alarm_first", r.s("check"))
        assertTrue(r.s("hint"), "phone_show_alarms" in r.s("hint"))
    }

    @Test fun unreadableOrMissingAlarmAsksForOneCheck() = runTest {
        for ((res, check) in listOf(
            SystemActionResult(ActionResult.Ok(), "com.example.clock", alarmReadable = false) to "unknown",
            SystemActionResult(ActionResult.Ok(), "com.example.clock", alarmReadable = true, nextAlarmAfter = null) to "not_seen",
        )) {
            val r = toolDispatcher(SystemScreen { res }).execute(call("phone_set_alarm", """{"hour":7,"minute":0}""")).content
            assertEquals(check, r.s("check"))
            assertEquals(Feedback["alarm.unconfirmed"], r.s("hint"))
        }
    }

    @Test fun noClockAppIsNotSupportedWithTheFallback() = runTest {
        val screen = SystemScreen { SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED)) }
        for (c in listOf(
            call("phone_set_alarm", """{"hour":7,"minute":0}"""), call("phone_set_timer", """{"seconds":60}"""),
            call("phone_show_alarms", "{}"), call("phone_add_calendar_event", """{"title":"t","start":"2026-11-02T14:30"}"""),
        )) {
            val r = toolDispatcher(screen).execute(c).content
            assertEquals("false", r.s("ok"))
            assertEquals("not_supported", r.s("error"))
            assertEquals(FAILURE_MESSAGES.getValue("not_supported"), r.s("message"))
            assertTrue("phone_launch_app" in r.s("message"))
        }
    }

    @Test fun badArgumentsNeverReachThePhone() = runTest {
        val screen = SystemScreen { error("must not be called") }
        val d = toolDispatcher(screen)
        for (c in listOf(
            call("phone_set_alarm", """{"hour":24,"minute":0}"""),
            call("phone_set_alarm", """{"hour":9}"""),
            call("phone_set_alarm", """{"hour":9,"minute":60}"""),
            call("phone_set_alarm", """{"hour":9,"minute":0,"days":["someday"]}"""),
            call("phone_set_timer", """{"seconds":0}"""),
            call("phone_set_timer", """{"seconds":90000}"""),
            call("phone_add_calendar_event", """{"title":"t","start":"tomorrow 3pm"}"""),
            call("phone_add_calendar_event", """{"title":"t","start":"2026-11-02T14:30","end":"2026-11-02T13:00"}"""),
            call("phone_add_calendar_event", """{"title":"t","start":"2026-11-02"}"""),
        )) {
            val r = d.execute(c).content
            assertEquals(c.argsJson, "false", r.s("ok"))
            assertEquals(c.argsJson, "invalid_arguments", r.s("error"))
        }
        assertTrue(screen.asked.isEmpty())
    }

    @Test fun timerAndCalendarEvent() = runTest {
        val screen = SystemScreen { SystemActionResult(ActionResult.Ok(), "com.example.app") }
        val d = toolDispatcher(screen)
        val t = d.execute(call("phone_set_timer", """{"seconds":600,"label":"泡面"}""")).content
        assertEquals("true", t.s("ok"))
        assertEquals(SystemAction.SetTimer(600, "泡面"), screen.asked.last())

        val e = d.execute(call("phone_add_calendar_event", """{"title":"体检","start":"2026-11-02T14:30","location":"医院"}""")).content
        val begin = LocalDateTime.of(2026, 11, 2, 14, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(SystemAction.AddCalendarEvent("体检", begin, begin + 3_600_000, false, "医院", null), screen.asked.last())
        assertEquals(Feedback["calendar.opened"], e.s("hint"))

        d.execute(call("phone_add_calendar_event", """{"title":"假期","start":"2026-11-02","allDay":true}"""))
        val day = LocalDateTime.of(2026, 11, 2, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(SystemAction.AddCalendarEvent("假期", day, day + 24 * 3_600_000L, true, null, null), screen.asked.last())

        val s = d.execute(call("phone_show_alarms", "{}")).content
        assertEquals(Feedback["alarms.opened"], s.s("hint"))
        assertEquals(SystemAction.ShowAlarms, screen.asked.last())
    }

    @Test fun bulkCalendarValidatesEverythingBeforeWriting() = runTest {
        val screen = SystemScreen { error("single-event UI must not open") }
        screen.bulkAnswer = { error("no write may start") }
        val d = toolDispatcher(screen)
        val bad = listOf(
            """{"events":[]}""",
            """{"events":"not an array"}""",
            """{"events":[42]}""",
            """{"events":[{"title":"ok","start":"2026-11-02T14:30"},{"title":"bad","start":"tomorrow"}]}""",
            """{"events":[{"title":"ok","start":"2026-11-02T14:30"},{"title":"bad","start":"2026-11-02T15:00","end":"2026-11-02T14:00"}]}""",
            """{"events":[{"title":"ok","start":"2026-11-02T14:30","surprise":true}]}""",
            List(101) { """{"title":"t","start":"2026-11-02T14:30"}""" }.joinToString(",", """{"events":[""", "]}"),
        )
        for (args in bad) {
            val r = d.execute(call("phone_calendar_bulk_insert", args)).content
            assertEquals(args, "false", r.s("ok"))
            assertEquals(args, "invalid_arguments", r.s("error"))
        }
        assertTrue(screen.bulkAsked.isEmpty())
    }

    @Test fun bulkCalendarWritesLocalAndUtcAllDayEvents() = runTest {
        val screen = SystemScreen { error("single-event UI must not open") }
        screen.bulkAnswer = { events ->
            SystemActionResult(
                ActionResult.Ok(),
                eventUris = events.mapIndexed { i, _ -> "content://com.android.calendar/events/${101 + i}" },
            )
        }
        val r = toolDispatcher(screen).execute(call("phone_calendar_bulk_insert", """
            {"events":[
              {"title":"timed","start":"2026-11-02T14:30","location":"room"},
              {"title":"holiday","start":"2026-11-03","allDay":true}
            ]}
        """.trimIndent())).content
        assertEquals("true", r.s("ok"))
        assertEquals("2", r.s("inserted"))
        assertEquals(listOf("101", "102"), r["eventIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val begin = LocalDateTime.of(2026, 11, 2, 14, 30).atZone(zone).toInstant().toEpochMilli()
        val day = LocalDate.of(2026, 11, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(listOf(
            SystemAction.AddCalendarEvent("timed", begin, begin + 3_600_000, false, "room", null),
            SystemAction.AddCalendarEvent("holiday", day, day + 24 * 3_600_000, true, null, null),
        ), screen.bulkAsked.single())
    }

    @Test fun bulkCalendarExplainsSafeFallbacks() = runTest {
        val screen = SystemScreen { error("single-event UI must not open") }
        val d = toolDispatcher(screen)
        for (code in listOf("calendar_permission_required", "no_writable_calendar", "calendar_unavailable", "calendar_write_unconfirmed")) {
            screen.bulkAnswer = { SystemActionResult(ActionResult.Failed(code)) }
            val r = d.execute(call("phone_calendar_bulk_insert", """{"events":[{"title":"t","start":"2026-11-02T14:30"}]}""")).content
            assertEquals(code, "false", r.s("ok"))
            assertEquals(code, code, r.s("error"))
            assertEquals(code, FAILURE_MESSAGES.getValue(code), r.s("message"))
        }
        for (uris in listOf(
            emptyList(),
            listOf("content://com.android.calendar/events/not-an-id"),
        )) {
            screen.bulkAnswer = { SystemActionResult(ActionResult.Ok(), eventUris = uris) }
            val r = d.execute(call("phone_calendar_bulk_insert", """{"events":[{"title":"t","start":"2026-11-02T14:30"}]}""")).content
            assertEquals("calendar_write_unconfirmed", r.s("error"))
        }
    }

    @Test fun silentActionThatOpenedAScreenSaysSo() = runTest {
        val screen = SystemScreen { SystemActionResult(ActionResult.Ok(), "com.example.clock", showing = true) }
        val r = toolDispatcher(screen).execute(call("phone_set_timer", """{"seconds":60}""")).content
        assertEquals("true", r.s("screenOpened"))
        assertEquals(Feedback["system_action.screen_opened"], r.s("hint"))
    }

    @Test fun alarmCheckRules() {
        val ok = { next: Long? -> SystemActionResult(ActionResult.Ok(), alarmReadable = true, nextAlarmAfter = next) }
        val now = at(8, 0)
        assertEquals(AlarmCheck.Status.VERIFIED, AlarmCheck.of(9, 40, ok(at(9, 40)), zone, now))
        assertEquals(AlarmCheck.Status.EARLIER_FIRST, AlarmCheck.of(9, 40, ok(at(9, 0)), zone, now))
        assertEquals(AlarmCheck.Status.NOT_SEEN, AlarmCheck.of(9, 40, ok(at(10, 0)), zone, now))
        assertEquals(AlarmCheck.Status.NOT_SEEN, AlarmCheck.of(9, 40, ok(null), zone, now))
        assertEquals(AlarmCheck.Status.UNKNOWN, AlarmCheck.of(9, 40, SystemActionResult(ActionResult.Ok()), zone, now))
        // 7 点的闹钟在早上 8 点设:下一次是明天 7 点,今天 23 点那个在它前面
        assertEquals(AlarmCheck.Status.EARLIER_FIRST, AlarmCheck.of(7, 0, ok(at(23, 0)), zone, now))
    }

    @Test fun calendarTimes() {
        val z = ZoneId.of("Asia/Shanghai")
        assertEquals(1793601000000L, CalendarTime.parse("2026-11-02T14:30", false, z))
        assertEquals(1793601000000L, CalendarTime.parse("2026-11-02T14:30:00", false, z))
        assertEquals(1793601000000L, CalendarTime.parse("2026-11-02T06:30:00Z", false, z))
        assertEquals(1793548800000L, CalendarTime.parse("2026-11-02", true, z))
        assertEquals(1793577600000L, CalendarTime.parse("2026-11-02", true, z, utcAllDay = true))
        assertNull(CalendarTime.parse("2026-11-02", false, z))
        assertNull(CalendarTime.parse("next friday", false, z))
    }
}
