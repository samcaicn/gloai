package com.wangzan101.mika.cap.phone.a11y

import android.Manifest
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 真日历提供方契约：只清理本次测试唯一标记的事件，不读取或改动其它日程。 */
@RunWith(AndroidJUnit4::class)
class CalendarBulkWriterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val marker = "Mika calendar device test ${System.nanoTime()}"
    private val titles = listOf("$marker timed", "$marker all day")

    @Before fun grantCalendarAccess() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
    }

    @After fun deleteOnlyTestEvents() {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.TITLE} IN (?, ?) AND ${CalendarContract.Events.DESCRIPTION} = ?",
            arrayOf(titles[0], titles[1], marker),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                    .appendPath(cursor.getLong(0).toString()).build()
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    @Test fun providerBatchWritesTimedAndUtcAllDayEvents() {
        val zone = ZoneId.systemDefault()
        val timed = LocalDateTime.of(2031, 5, 10, 14, 30).atZone(zone).toInstant().toEpochMilli()
        val day = LocalDate.of(2031, 5, 11).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val result = CalendarBulkWriter.insert(context, listOf(
            SystemAction.AddCalendarEvent(titles[0], timed, timed + 3_600_000, description = marker),
            SystemAction.AddCalendarEvent(titles[1], day, day + DAY_MS, allDay = true, description = marker),
        ))
        val failure = result.result as? ActionResult.Failed
        assumeTrue("No writable calendar on this device: ${failure?.reason}", failure?.reason != "no_writable_calendar")
        assertTrue("Calendar batch failed: ${failure?.reason}", result.result is ActionResult.Ok)
        assertEquals(2, result.eventUris.size)

        result.eventUris.forEachIndexed { i, text ->
            val uri = Uri.parse(text)
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                assertTrue(cursor.moveToFirst())
                fun value(name: String) = cursor.getColumnIndexOrThrow(name).let(cursor::getString)
                assertEquals(titles[i], value(CalendarContract.Events.TITLE))
                assertEquals(marker, value(CalendarContract.Events.DESCRIPTION))
                if (i == 0) {
                    assertEquals(timed.toString(), value(CalendarContract.Events.DTSTART))
                    assertEquals("0", value(CalendarContract.Events.ALL_DAY))
                    assertEquals(zone.id, value(CalendarContract.Events.EVENT_TIMEZONE))
                } else {
                    assertEquals(day.toString(), value(CalendarContract.Events.DTSTART))
                    assertEquals((day + DAY_MS).toString(), value(CalendarContract.Events.DTEND))
                    assertEquals("1", value(CalendarContract.Events.ALL_DAY))
                    assertEquals("UTC", value(CalendarContract.Events.EVENT_TIMEZONE))
                }
            } ?: error("Inserted event was not readable: $text")
        }
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
