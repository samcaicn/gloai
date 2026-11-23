package com.wangzan101.mika.settings.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wangzan101.mika.settings.ChannelRow
import com.wangzan101.mika.settings.ScheduledQuietHours
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.notificationOverview
import com.wangzan101.mika.task.ParallelLimit
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.wangzan101.mika.memory.ui.Link

/**
 * 设置 › 通知:显示系统通知频道状态，并设置定时任务的免打扰时段。
 */
@Composable
fun NotificationsScreen(status: SystemStatus, onFix: (Fix) -> Unit, onBack: () -> Unit) {
    val o = status.notificationOverview()
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    var quietHours by remember(flags) { mutableStateOf(flags.scheduledQuietHours()) }
    fun updateQuietHours(hours: ScheduledQuietHours) {
        flags.setScheduledQuietHours(hours)
        quietHours = hours
    }
    PageScaffold(StatusCopy.NOTIFICATIONS, onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Bell, stringResource(R.string.settings_notif_master),
                first = true, tone = if (o.appAllowed) TileTone.Neutral else TileTone.Bad,
                onClick = if (o.appAllowed) null else ({ onFix(Fix.AllowNotifications) }),
            ) {
                if (o.appAllowed) RowValue(stringResource(R.string.settings_allowed), ValueTone.Ok) else RowValue(StatusCopy.HEADS_UP_ACTION, ValueTone.Link)
            }
        }
        GroupLabel(stringResource(R.string.settings_task_notifs))
        GroupCard {
            ChannelRowItem(StatusCopy.ATTENTION_CHANNEL, StatusCopy.NOTIF_ATTENTION_WHEN, o.attention, Fix.OpenAttentionChannel, onFix, first = true)
            ChannelRowItem(StatusCopy.RESULT_CHANNEL, StatusCopy.NOTIF_RESULT_WHEN, o.result, Fix.OpenResultChannel, onFix)
            ChannelRowItem(StatusCopy.PROGRESS_CHANNEL, StatusCopy.NOTIF_PROGRESS_WHEN, o.progress, Fix.OpenProgressChannel, onFix)
            if (status.sdkInt >= 36) {
                ListRow(MikaIcons.Bell, stringResource(R.string.settings_live_task_updates),
                    stringResource(R.string.settings_live_task_updates_detail), onClick = { onFix(Fix.OpenLiveTaskSettings) }) {
                    RowChevron()
                }
            }
        }
        FooterNote(StatusCopy.NOTIF_FOOTER)
        GroupCard {
            ListRow(
                MikaIcons.Mute, stringResource(R.string.settings_scheduled_quiet),
                first = true,
                onClick = { updateQuietHours(quietHours.copy(enabled = !quietHours.enabled)) },
            ) {
                MikaSwitch(quietHours.enabled, { updateQuietHours(quietHours.copy(enabled = it)) },
                    Modifier.testTag("scheduled-quiet-switch"))
            }
            if (quietHours.enabled) {
                QuietHoursTimeRow(R.string.settings_scheduled_quiet_start, quietHours.start) {
                    updateQuietHours(quietHours.copy(start = it))
                }
                QuietHoursTimeRow(R.string.settings_scheduled_quiet_end, quietHours.end) {
                    updateQuietHours(quietHours.copy(end = it))
                }
            }
        }
        if (quietHours.enabled && quietHours.start == quietHours.end) {
            FooterNote(stringResource(R.string.settings_scheduled_quiet_equal))
        }
    }
}

@Composable
private fun QuietHoursTimeRow(label: Int, time: LocalTime, onChange: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val title = stringResource(label)
    val use24HourClock = DateFormat.is24HourFormat(context)
    val locale = context.resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, if (use24HourClock) "Hm" else "hm")
    ListRow(MikaIcons.Clock, title, onClick = {
        TimePickerDialog(context, { _, hour, minute -> onChange(LocalTime.of(hour, minute)) },
            time.hour, time.minute, use24HourClock).apply {
            setTitle(title)
            show()
        }
    }) {
        RowValue(DateTimeFormatter.ofPattern(pattern, locale).format(time))
        RowChevron()
    }
}

@Composable
private fun ChannelRowItem(title: String, whenText: String, row: ChannelRow, fix: Fix, onFix: (Fix) -> Unit, first: Boolean = false) {
    val (text, tone) = StatusCopy.channelLevelValue(row)
    ListRow(
        MikaIcons.Bell, title, whenText, first = first,
        tone = if (row.warn) TileTone.Warn else TileTone.Neutral,
        onClick = { onFix(fix) },
    ) {
        RowValue(text, tone)
        RowChevron()
    }
}

// ---------- 任务运行 ----------

/** 并行任务的设置:用户设的上限 [max](1–3),和设备此刻允许的 [limit]。 */
data class ParallelSetting(val max: Int, val limit: ParallelLimit)
