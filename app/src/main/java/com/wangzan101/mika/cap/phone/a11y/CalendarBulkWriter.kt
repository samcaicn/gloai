package com.wangzan101.mika.cap.phone.a11y

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import java.util.TimeZone

/**
 * 用安卓公开的 CalendarContract 在 App 进程里写日程。只选系统报告的可见、可写日历，
 * 不读已有日程；不弹权限框，也不使用 shell 或厂商接口。所有操作一次 applyBatch 提交。
 */
internal object CalendarBulkWriter {
    fun insert(context: Context, events: List<SystemAction.AddCalendarEvent>): SystemActionResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
        ) return failed("calendar_permission_required")

        val resolver = context.contentResolver
        val calendarId = try {
            writableCalendar(context)
        } catch (_: SecurityException) {
            return failed("calendar_permission_required")
        } catch (_: RuntimeException) {
            return failed("calendar_unavailable")
        } ?: return failed("no_writable_calendar")

        val zone = TimeZone.getDefault().id
        val operations = events.map { event ->
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, event.title)
                .withValue(CalendarContract.Events.DTSTART, event.beginMs)
                .withValue(
                    CalendarContract.Events.DTEND,
                    event.endMs ?: event.beginMs + if (event.allDay) DAY_MS else HOUR_MS,
                )
                .withValue(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, if (event.allDay) UTC else zone)
                .withValue(CalendarContract.Events.EVENT_LOCATION, event.location)
                .withValue(CalendarContract.Events.DESCRIPTION, event.description)
                .build()
        }
        val uris = try {
            resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(operations)).map { it.uri?.toString() }
        } catch (_: Exception) {
            // 提供方可能已提交前几条；这里既不能报零写入，也不能让上层自动重写整批。
            return failed("calendar_write_unconfirmed")
        }
        if (uris.size != events.size || uris.any { it == null || !validEventUri(it) }) {
            return failed("calendar_write_unconfirmed")
        }
        return SystemActionResult(ActionResult.Ok(), eventUris = uris.filterNotNull())
    }

    private fun writableCalendar(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, null, order,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val visible = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            while (cursor.moveToNext()) {
                if (cursor.getInt(visible) != 0) return cursor.getLong(id)
            }
        }
        return null
    }

    private fun validEventUri(uri: String): Boolean = runCatching {
        ContentUris.parseId(android.net.Uri.parse(uri)) > 0
    }.getOrDefault(false)

    private fun failed(reason: String) = SystemActionResult(ActionResult.Failed(reason))

    private const val UTC = "UTC"
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
}
