package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.PlanDraft
import com.wangzan101.mika.core.trigger.PlanRepeat
import com.wangzan101.mika.core.trigger.PlanValidation
import com.wangzan101.mika.core.trigger.PlanValidationException
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.editedTrigger
import com.wangzan101.mika.core.trigger.formatPlanClock
import com.wangzan101.mika.core.trigger.formatPlanEditorTime
import com.wangzan101.mika.core.trigger.newPlanDraft
import com.wangzan101.mika.core.trigger.normalizedClockText
import com.wangzan101.mika.core.trigger.parsePlanEditorTime
import com.wangzan101.mika.core.trigger.planClockEntries
import com.wangzan101.mika.core.trigger.toPlanDraft
import com.wangzan101.mika.core.trigger.upcoming
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.UUID
import kotlin.math.roundToInt

/** 新建和编辑共用一份草稿；预览不另猜时间，直接显示将要保存的计划。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditorDialog(
    original: TriggerDefinition?,
    newThreadId: String,
    onDismiss: () -> Unit,
    onSave: (TriggerDefinition) -> Unit,
    initialDraft: PlanDraft? = null,
) {
    val newId = remember(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    var draft by remember(original?.id) {
        mutableStateOf(original?.toPlanDraft() ?: initialDraft ?: newPlanDraft(System.currentTimeMillis()))
    }
    var attempted by remember(original?.id) { mutableStateOf(false) }
    // 点日历/时钟图标时打开的选择器;选完日期接着选时间,都确认后才写回草稿。
    var pickingDate by remember(original?.id) { mutableStateOf(false) }
    var pickingTime by remember(original?.id) { mutableStateOf<TimeTarget?>(null) }
    val pickerZone = runCatching { ZoneId.of(draft.zone.trim()) }.getOrElse { ZoneId.systemDefault() }
    val clockEntries = planClockEntries(draft.clock).orEmpty()
    val candidate = runCatching {
        editedTrigger(draft, original, newId, newThreadId, System.currentTimeMillis())
    }
    val preview = candidate.getOrNull()

    if (pickingDate) {
        PlanDatePickerDialog(
            initial = parsePlanEditorTime(draft.firstAt, pickerZone.id)?.toLocalDate()
                ?: Instant.now().atZone(pickerZone).toLocalDate(),
            onDismiss = { pickingDate = false },
            onPicked = { date ->
                pickingDate = false
                pickingTime = TimeTarget.FirstRun(date)
            },
        )
    }
    pickingTime?.let { target ->
        PlanTimePickerDialog(
            initial = when (target) {
                is TimeTarget.FirstRun ->
                    parsePlanEditorTime(draft.firstAt, pickerZone.id)?.toLocalTime() ?: LocalTime.of(9, 0)
                is TimeTarget.Clock ->
                    target.index?.let { clockEntries.getOrNull(it) }
                        ?: clockEntries.lastOrNull()?.plusHours(1)
                        ?: LocalTime.of(9, 0)
            },
            title = tr(R.string.plans_pick_time),
            onDismiss = { pickingTime = null },
            onPicked = { time ->
                when (target) {
                    is TimeTarget.FirstRun ->
                        draft = draft.copy(firstAt = formatPlanEditorTime(LocalDateTime.of(target.date, time)))
                    is TimeTarget.Clock -> {
                        val parsed = planClockEntries(draft.clock)
                        val index = target.index
                        val clock = if (parsed == null && draft.clock.isNotBlank()) {
                            "${draft.clock.trimEnd()}, ${formatPlanClock(time)}"
                        } else {
                            val updated = parsed.orEmpty().toMutableList()
                            if (index != null && index in updated.indices) updated[index] = time else updated.add(time)
                            normalizedClockText(updated)
                        }
                        draft = draft.copy(clock = clock)
                    }
                }
                pickingTime = null
            },
        )
    }

    MikaDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = when {
                original != null -> R.string.plans_edit_title
                initialDraft != null -> R.string.plans_copy
                else -> R.string.plans_create_title
            }
            Text(tr(title))
        },
        text = {
            KeyboardDismissArea {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedControl(
                        listOf(tr(R.string.plans_task_mode), tr(R.string.plans_reminder_mode)),
                        if (draft.reminder) 1 else 0,
                        onSelect = { selected ->
                            draft = draft.copy(
                                reminder = selected == 1,
                                condition = if (selected == 1) "" else draft.condition,
                                cooldownMinutes = if (selected == 1) "" else draft.cooldownMinutes,
                            )
                        },
                        tags = listOf("plan-editor-task", "plan-editor-reminder"),
                    )
                    Spacer(Modifier.height(10.dp))
                    PlanField(
                        draft.content,
                        { draft = draft.copy(content = it) },
                        tr(if (draft.reminder) R.string.plans_content_reminder else R.string.plans_content_task),
                        "plan-editor-content",
                        singleLine = false,
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        listOf(
                            tr(R.string.plans_once), tr(R.string.plans_repeat_interval),
                            tr(R.string.plans_every_day), tr(R.string.plans_repeat_weekly),
                        ),
                        draft.repeat.ordinal,
                        onSelect = { selected ->
                            val repeat = PlanRepeat.entries[selected]
                            val today = runCatching { ZoneId.of(draft.zone) }.getOrElse { ZoneId.systemDefault() }
                                .let { Instant.now().atZone(it).dayOfWeek.value }
                            draft = draft.copy(
                                repeat = repeat,
                                weekdays = if (repeat == PlanRepeat.Weekly && draft.weekdays.isEmpty()) setOf(today)
                                else draft.weekdays,
                            )
                        },
                        tags = listOf("plan-editor-once", "plan-editor-interval", "plan-editor-daily", "plan-editor-weekly"),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (draft.repeat in setOf(PlanRepeat.Once, PlanRepeat.Interval)) {
                        PlanField(
                            draft.firstAt, { draft = draft.copy(firstAt = it) },
                            tr(R.string.plans_edit_label), "plan-editor-first-at",
                            trailing = {
                                FieldIconButton(
                                    MikaIcons.Calendar, tr(R.string.plans_pick_time), "plan-editor-first-at-pick",
                                ) { pickingDate = true }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (draft.repeat == PlanRepeat.Interval) {
                        PlanField(
                            draft.intervalMinutes, { draft = draft.copy(intervalMinutes = it) },
                            tr(R.string.plans_interval_minutes), "plan-editor-interval-minutes", KeyboardType.Number,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (draft.repeat in setOf(PlanRepeat.Daily, PlanRepeat.Weekly)) {
                        PlanField(
                            draft.clock, { draft = draft.copy(clock = it) },
                            tr(R.string.plans_clock), "plan-editor-clock",
                            placeholder = "08:00, 12:30, 20:00",
                            trailing = {
                                FieldIconButton(
                                    MikaIcons.Plus, tr(R.string.plans_add_time), "plan-editor-clock-add-icon",
                                ) { pickingTime = TimeTarget.Clock(null) }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        ClockChips(
                            clockEntries,
                            onEdit = { pickingTime = TimeTarget.Clock(it) },
                            onRemove = { index ->
                                val updated = clockEntries.toMutableList().apply { removeAt(index) }
                                draft = draft.copy(clock = normalizedClockText(updated))
                            },
                            onAdd = { pickingTime = TimeTarget.Clock(null) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (draft.repeat == PlanRepeat.Weekly) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..7).forEach { day ->
                                MikaButton(
                                    DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, uiLocale()),
                                    onClick = {
                                        draft = draft.copy(weekdays = draft.weekdays.toMutableSet().apply {
                                            if (!add(day)) remove(day)
                                        })
                                    },
                                    modifier = Modifier.testTag("plan-editor-weekday-$day"),
                                    kind = if (day in draft.weekdays) ButtonKind.Primary else ButtonKind.Tonal,
                                    size = ButtonSize.Small,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    PlanField(
                        draft.zone, { draft = draft.copy(zone = it) },
                        tr(R.string.plans_zone), "plan-editor-zone",
                    )
                    if (!draft.reminder) {
                        Spacer(Modifier.height(10.dp))
                        PlanField(
                            draft.condition, { draft = draft.copy(condition = it) },
                            tr(R.string.plans_condition_optional), "plan-editor-condition", singleLine = false,
                        )
                        if (draft.condition.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            SegmentedControl(
                                listOf(tr(R.string.plans_notify_change), tr(R.string.plans_notify_each)),
                                if (draft.notifyWhen == ConditionNotify.BecomesMet) 0 else 1,
                                onSelect = {
                                    draft = draft.copy(notifyWhen = if (it == 0) ConditionNotify.BecomesMet else ConditionNotify.EachTimeMet)
                                },
                                tags = listOf("plan-editor-notify-change", "plan-editor-notify-each"),
                            )
                            Spacer(Modifier.height(10.dp))
                            PlanField(
                                draft.cooldownMinutes, { draft = draft.copy(cooldownMinutes = it) },
                                tr(R.string.plans_cooldown_minutes), "plan-editor-cooldown", KeyboardType.Number,
                            )
                        }
                    }
                    PlanSwitch(tr(R.string.plans_when_idle), draft.whenIdle, { draft = draft.copy(whenIdle = it) }, "plan-editor-idle")
                    PlanSwitch(tr(R.string.plans_enabled), draft.enabled, { draft = draft.copy(enabled = it) }, "plan-editor-enabled")

                    preview?.let { plan ->
                        Spacer(Modifier.height(8.dp))
                        Text(tr(R.string.plans_preview), style = MikaType.Caption, color = MikaTheme.colors.ink3)
                        val zone = plan.calendarZone ?: draft.zone
                        upcoming(plan, System.currentTimeMillis(), 3).forEachIndexed { index, at ->
                            Text(
                                "${index + 1}  ${formatPlanEditorTime(at, zone)} ($zone)",
                                Modifier.testTag("plan-editor-preview-$index"),
                                style = MikaType.Caption,
                                color = MikaTheme.colors.ink3,
                            )
                        }
                    }
                    if (attempted && preview == null) {
                        val reason = (candidate.exceptionOrNull() as? PlanValidationException)?.reason
                        Spacer(Modifier.height(8.dp))
                        Text(
                            reason?.let { planValidationText(it) } ?: tr(R.string.op_failed),
                            Modifier.testTag("plan-editor-error"),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.bad,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("plan-editor-save"),
                onClick = {
                    val saved = runCatching {
                        editedTrigger(draft, original, newId, newThreadId, System.currentTimeMillis())
                    }
                    if (saved.isFailure) attempted = true
                    else onSave(saved.getOrThrow())
                },
            ) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun PlanField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value,
        onValueChange,
        Modifier.fillMaxWidth().testTag(tag).keepsKeyboard(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 5,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/** 输入框右侧的取时间按钮:点开选择器,输入框本身仍可直接改文本。 */
@Composable
private fun FieldIconButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = MikaTheme.colors.ink3)
    }
}

/** 每天/每周的当地时刻:每个时刻一颗芯片,点时刻改时间,点叉删掉,再加一颗就是新增。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClockChips(
    entries: List<LocalTime>,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEachIndexed { index, time ->
            Row(
                Modifier.height(32.dp).background(c.fill, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.fillMaxHeight().clickable { onEdit(index) }
                        .padding(start = 12.dp, end = 4.dp).testTag("plan-editor-clock-$index"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MikaIcons.Clock, null, Modifier.size(14.dp), tint = c.ink3)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        formatPlanClock(time),
                        style = MikaType.Body.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                        color = c.ink2,
                    )
                }
                Box(
                    Modifier.fillMaxHeight().width(32.dp).clickable(role = Role.Button) { onRemove(index) }
                        .testTag("plan-editor-clock-remove-$index"),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, tr(R.string.plans_remove_time), Modifier.size(13.dp), tint = c.ink3) }
            }
        }
        MikaButton(
            "＋ " + tr(R.string.plans_add_time),
            onAdd,
            modifier = Modifier.testTag("plan-editor-clock-add"),
            kind = ButtonKind.Tonal,
            size = ButtonSize.Small,
        )
    }
}

/** 时间选择器是为哪个字段打开的:首次执行(带已选日期),或某个当地时刻(下标为空表示新增)。 */
private sealed interface TimeTarget {
    data class FirstRun(val date: LocalDate) : TimeTarget
    data class Clock(val index: Int?) : TimeTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    // 日期选择器的日期是 UTC 零点的毫秒数
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("plan-date-ok"),
                onClick = {
                    state.selectedDateMillis?.let {
                        onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text(tr(R.string.done)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    ) {
        DatePicker(state, Modifier.testTag("plan-date-picker"), showModeToggle = false)
    }
}

/** 时、分两列滚轮;停在中间一行的数字就是选中的值。 */
@Composable
private fun PlanTimePickerDialog(initial: LocalTime, title: String, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    var hour by remember(initial) { mutableIntStateOf(initial.hour) }
    var minute by remember(initial) { mutableIntStateOf(initial.minute) }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // 时间按「时:分」从左到右读,不跟着界面语言反过来
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberWheel(24, hour, "plan-time-hour", { hour = it }, Modifier.weight(1f))
                    Text(":", style = MikaType.Title, color = MikaTheme.colors.ink3, modifier = Modifier.padding(horizontal = 6.dp))
                    NumberWheel(60, minute, "plan-time-minute", { minute = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("plan-time-ok"),
                onClick = { onPicked(LocalTime.of(hour, minute)) },
            ) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

private val WheelItem = 40.dp

@Composable
private fun NumberWheel(count: Int, initial: Int, tag: String, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val density = LocalDensity.current
    val itemPx = with(density) { WheelItem.roundToPx() }
    val scroll = rememberScrollState(initial * itemPx)
    val scope = rememberCoroutineScope()
    val selected by remember {
        derivedStateOf { (scroll.value.toFloat() / itemPx).roundToInt().coerceIn(0, count - 1) }
    }
    // 拖完/惯性停下后吸到最近一格,中间那格就是选中的值
    LaunchedEffect(scroll, itemPx) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val target = selected * itemPx
                if (scroll.value != target) scroll.animateScrollTo(target)
            }
        }
    }
    LaunchedEffect(selected) { onValueChange(selected) }
    Box(modifier.height(WheelItem * 5f), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(WheelItem).background(c.fill, RoundedCornerShape(10.dp)))
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(WheelItem * 2f))
            repeat(count) { index ->
                Box(
                    Modifier.fillMaxWidth().height(WheelItem)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { scope.launch { scroll.animateScrollTo(index * itemPx) } }
                        .testTag("$tag-$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "%02d".format(java.util.Locale.ROOT, index),
                        style = MikaType.Body.copy(fontSize = 19.sp, fontFeatureSettings = "tnum"),
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selected) c.ink else c.ink3,
                    )
                }
            }
            Spacer(Modifier.height(WheelItem * 2f))
        }
    }
}

@Composable
private fun PlanSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MikaType.Body, color = MikaTheme.colors.ink2)
        MikaSwitch(checked, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun planValidationText(reason: PlanValidation): String = tr(when (reason) {
    PlanValidation.MissingContent -> R.string.plans_error_content
    PlanValidation.ContentTooLong -> R.string.plans_error_content_long
    PlanValidation.ConditionTooLong -> R.string.plans_error_condition_long
    PlanValidation.ReminderWithCondition -> R.string.plans_error_reminder_condition
    PlanValidation.InvalidFirstTime -> R.string.plans_error_time
    PlanValidation.TimeNotFuture -> R.string.plans_need_future
    PlanValidation.InvalidInterval -> R.string.plans_error_interval
    PlanValidation.InvalidClock -> R.string.plans_error_clock
    PlanValidation.InvalidZone -> R.string.plans_error_zone
    PlanValidation.InvalidWeekdays -> R.string.plans_error_weekdays
    PlanValidation.InvalidCooldown -> R.string.plans_error_cooldown
})
