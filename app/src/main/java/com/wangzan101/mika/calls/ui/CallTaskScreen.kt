package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallSim
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.memory.ui.Row

/** A private draft in the workspace, never a saved call profile or background request. */
@Stable
internal class CallTaskDraft {
    var number by mutableStateOf("")
    var goal by mutableStateOf("")
    var questions by mutableStateOf("")
    var context by mutableStateOf("")
    var constraints by mutableStateOf("")
    var simOverride by mutableStateOf<String?>(null)

    val hasContent: Boolean
        get() = number.isNotBlank() || goal.isNotBlank() || questions.isNotBlank() ||
            context.isNotBlank() || constraints.isNotBlank()

    fun fill(number: String, brief: CallTaskBrief) {
        this.number = number
        goal = brief.goal
        questions = brief.questions.joinToString("\n")
        context = brief.context
        constraints = brief.constraints
        simOverride = null
    }

    fun snapshotFields(): List<String> = listOf(number, goal, questions, context, constraints, simOverride.orEmpty())

    companion object {
        val Saver = listSaver<CallTaskDraft, String>(
            save = { draft -> listOf(draft.number, draft.goal, draft.questions, draft.context,
                draft.constraints, draft.simOverride.orEmpty()) },
            restore = { fields ->
                fields.takeIf { it.size == 6 }?.let {
                    CallTaskDraft().apply {
                        number = it[0]
                        goal = it[1]
                        questions = it[2]
                        context = it[3]
                        constraints = it[4]
                        simOverride = it[5].takeIf(String::isNotEmpty)
                    }
                }
            },
        )
    }
}

internal data class CallTaskSubmission(
    val number: String, val simId: String?, val simLabel: String, val voiceName: String, val brief: CallTaskBrief,
) {
    override fun toString(): String = "CallTaskSubmission(redacted)"
}

@Composable
internal fun CallTaskScreen(
    draft: CallTaskDraft, config: CallConfig, state: CallRuntimeState, ready: Boolean,
    editable: Boolean, canDial: Boolean, feedback: CallActionResult?,
    onSetup: () -> Unit, onConfirm: (CallTaskSubmission) -> Unit,
    onConfirmLocked: (CallTaskSubmission) -> Unit,
    voiceSummary: String,
    onVoiceProfiles: () -> Unit,
    voiceBriefing: VoiceBriefingLauncher? = null,
) {
    var showSims by rememberSaveable { mutableStateOf(false) }
    var review by remember { mutableStateOf<CallTaskSubmission?>(null) }
    val number = CallPolicy.outgoingNumber(draft.number)
    val goalLength = draft.goal.trim().length
    val maxGoal = CallTaskBrief.MAX_GOAL_CHARS
    val questions = remember(draft.questions) { draft.questions.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
    val badQuestions = questions.size > CallTaskBrief.MAX_QUESTIONS ||
        questions.any { it.length > CallTaskBrief.MAX_QUESTION_CHARS }
    val contextLength = draft.context.trim().length
    val constraintsLength = draft.constraints.trim().length
    val preferred = state.simOptions.firstOrNull { it.id == config.defaultOutgoingSimId }
    val sim = CallPolicy.chooseOutgoingSim(state.simOptions, draft.simOverride, config.defaultOutgoingSimId)
    val missingSim = stringResource(R.string.calls_task_pick_sim)
    val brief = remember(draft.goal, questions, draft.context, draft.constraints) {
        runCatching { CallTaskBrief(draft.goal, questions, draft.context, draft.constraints).validatedSnapshot() }.getOrNull()
    }

    feedback?.let { CallFeedback(it) }
    val numberError = draft.number.isNotBlank() && number == null
    GroupCard(Modifier.testTag("call-task-fields")) {
        CallFieldRow(MikaIcons.Phone, draft.number, { draft.number = it }, stringResource(R.string.calls_number),
            editable, "call-number", keyboard = KeyboardType.Phone, ltr = true,
            error = if (numberError) stringResource(R.string.calls_task_number_hint) else null)
        Hairline()
        CallFieldRow(MikaIcons.Doc, draft.goal, { draft.goal = it }, stringResource(R.string.calls_task_goal_example),
            editable, "call-task-goal", minLines = 3, maxLines = 6,
            error = if (goalLength > maxGoal) stringResource(R.string.calls_task_limit, goalLength, maxGoal) else null)
    }

    // 三个可选项收在一行里;填过的草稿(例如从记录里复用)直接展开
    val anyExtra = draft.questions.isNotBlank() || draft.context.isNotBlank() || draft.constraints.isNotBlank()
    var extrasOpen by rememberSaveable { mutableStateOf(false) }
    val showExtras = extrasOpen || anyExtra
    GroupCard {
        ListRow(if (showExtras) MikaIcons.ListChecks else MikaIcons.Plus,
            stringResource(R.string.calls_task_more), first = true, titleColor = MikaTheme.colors.accent,
            onClick = { if (!anyExtra) extrasOpen = !extrasOpen }.takeIf { !anyExtra },
            modifier = Modifier.testTag("call-task-more"))
        if (showExtras) {
            Hairline()
            CallFieldRow(MikaIcons.Doc, draft.questions, { draft.questions = it },
                stringResource(R.string.calls_task_questions) + " · " + stringResource(R.string.calls_task_questions_hint),
                editable, "call-task-questions", minLines = 2, maxLines = 5,
                error = if (badQuestions) stringResource(R.string.calls_task_questions_limit,
                    CallTaskBrief.MAX_QUESTIONS, CallTaskBrief.MAX_QUESTION_CHARS) else null)
            Hairline()
            CallFieldRow(MikaIcons.Message, draft.context, { draft.context = it }, stringResource(R.string.calls_task_context),
                editable, "call-task-context", minLines = 1, maxLines = 5,
                error = if (contextLength > CallTaskBrief.MAX_CONTEXT_CHARS)
                    stringResource(R.string.calls_task_limit, contextLength, CallTaskBrief.MAX_CONTEXT_CHARS) else null)
            Hairline()
            CallFieldRow(MikaIcons.Key, draft.constraints, { draft.constraints = it }, stringResource(R.string.calls_task_constraints),
                editable, "call-task-constraints", minLines = 1, maxLines = 5,
                error = if (constraintsLength > CallTaskBrief.MAX_CONSTRAINT_CHARS)
                    stringResource(R.string.calls_task_limit, constraintsLength, CallTaskBrief.MAX_CONSTRAINT_CHARS) else null)
        }
    }

    GroupLabel(stringResource(R.string.calls_this_call))
    GroupCard {
        ListRow(MikaIcons.Mic, stringResource(R.string.calls_voice_title), first = true,
            onClick = onVoiceProfiles.takeIf { editable }) { RowValue(callVoiceShort(voiceSummary)); RowChevron() }
        val simValue = when {
            sim != null -> sim.label
            config.defaultOutgoingSimId != null && draft.simOverride == null -> stringResource(R.string.calls_sim_unavailable)
            else -> stringResource(R.string.calls_not_set)
        }
        ListRow(MikaIcons.Phone, stringResource(R.string.calls_sim),
            onClick = if (editable) ({ showSims = true }) else null) {
            RowValue(simValue, if (sim == null) ValueTone.Warn else ValueTone.Plain); RowChevron()
        }
    }

    if (!ready) MikaButton(stringResource(R.string.calls_device_and_permissions), onSetup,
        Modifier.fillMaxWidth().padding(bottom = 10.dp), kind = ButtonKind.Tonal)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MikaButton(
            stringResource(R.string.calls_task_review),
            {
                if (number != null && brief != null) {
                    review = CallTaskSubmission(number, sim?.id, sim?.label ?: missingSim, voiceSummary, brief)
                }
            },
            Modifier.weight(1f).testTag("call-task-review"),
            enabled = editable && number != null && brief != null,
        )
        voiceBriefing?.let { voice ->
            val voiceLabel = stringResource(R.string.calls_voice_briefing_start)
            val canVoice = ready && canDial && editable && !voice.busy && number != null && sim != null
            Box(
                Modifier.size(MikaSize.Button).background(MikaTheme.colors.fill, CircleShape).clip(CircleShape)
                    .clickable(enabled = canVoice, onClickLabel = voiceLabel, role = Role.Button) {
                        if (number != null && sim != null) voice.start(number, sim.id, sim.label)
                    }
                    .alpha(if (canVoice) 1f else 0.4f)
                    .semantics { contentDescription = voiceLabel }
                    .testTag("call-task-voice"),
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Mic, null, Modifier.size(22.dp), tint = MikaTheme.colors.ink) }
        }
    }
    Spacer(Modifier.height(4.dp))
    FooterNote(stringResource(R.string.calls_task_one_off))

    if (showSims) CallTaskSimDialog(
        state.simOptions, config.defaultOutgoingSimId, draft.simOverride,
        onChoose = { draft.simOverride = it; showSims = false }, onDismiss = { showSims = false },
    )
    review?.let { submission ->
        val allowed = ready && canDial && state.simOptions.any { it.id == submission.simId }
        CallTaskReviewDialog(
            submission, allowed,
            onDismiss = { review = null },
            onDial = { locked ->
                if (allowed) {
                    review = null
                    if (locked) onConfirmLocked(submission) else onConfirm(submission)
                }
            },
        )
    }
}

/**
 * 分组里的输入行:左边图标块,右边无边框的输入框(空时显示 [placeholder]),出错时下面一行红字。
 * 和同组的 [ListRow] 共用一条左边线。
 */
@Composable
private fun CallFieldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, onValue: (String) -> Unit, placeholder: String,
    enabled: Boolean, tag: String, keyboard: KeyboardType = KeyboardType.Text, ltr: Boolean = false,
    minLines: Int = 1, maxLines: Int = 1, error: String? = null,
) {
    val c = MikaTheme.colors
    val single = maxLines == 1
    Row(
        Modifier.fillMaxWidth().heightIn(min = MikaSize.RowMin).padding(horizontal = 16.dp, vertical = if (single) 0.dp else 12.dp),
        verticalAlignment = if (single) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(top = if (single) 0.dp else 5.dp)) {
            Box {
                if (value.isEmpty()) Text(placeholder, style = MikaType.Body, color = c.ink3)
                BasicTextField(
                    value, onValue, Modifier.fillMaxWidth().testTag(tag), enabled = enabled,
                    singleLine = single, minLines = minLines, maxLines = maxLines,
                    textStyle = MikaType.Body.copy(color = c.ink,
                        textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
            }
            error?.let { Text(it, Modifier.padding(top = 4.dp), style = MikaType.Caption, color = c.bad) }
        }
    }
}

@Composable
private fun CallTaskSimDialog(
    options: List<CallSim>, preferred: String?, selectedOverride: String?, onChoose: (String?) -> Unit, onDismiss: () -> Unit,
) {
    val remembered = options.firstOrNull { it.id == preferred }
    val platformDefault = options.singleOrNull { it.isDefault }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.calls_sim)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (remembered != null) RadioRow(
                    stringResource(R.string.calls_sim_preferred, remembered.label), selectedOverride == null,
                    onClick = { onChoose(null) }, first = true,
                )
                if (platformDefault != null && options.size > 1) RadioRow(
                    stringResource(R.string.calls_default), selectedOverride == "default",
                    onClick = { onChoose("default") }, first = remembered == null, subtitle = platformDefault.label,
                )
                options.forEachIndexed { index, option ->
                    RadioRow(option.label, selectedOverride == option.id, onClick = { onChoose(option.id) },
                        first = index == 0 && remembered == null && platformDefault == null)
                }
                if (options.isEmpty()) Text(stringResource(R.string.calls_task_pick_sim))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun CallTaskReviewDialog(submission: CallTaskSubmission, canDial: Boolean, onDismiss: () -> Unit, onDial: (Boolean) -> Unit) {
    var locked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_task_review)) },
        modifier = Modifier.testTag("call-task-review-dialog"),
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                TaskReviewField(stringResource(R.string.calls_number), "\u2066${submission.number}\u2069")
                TaskReviewField(stringResource(R.string.calls_sim), submission.simLabel)
                TaskReviewField(stringResource(R.string.calls_voice_title), submission.voiceName)
                TaskReviewField(stringResource(R.string.calls_task_goal), submission.brief.goal)
                if (submission.brief.questions.isNotEmpty()) {
                    TaskReviewField(stringResource(R.string.calls_task_questions),
                        submission.brief.questions.mapIndexed { index, question -> "${index + 1}. $question" }.joinToString("\n"))
                }
                if (submission.brief.context.isNotBlank()) TaskReviewField(stringResource(R.string.calls_task_context), submission.brief.context)
                if (submission.brief.constraints.isNotBlank()) TaskReviewField(stringResource(R.string.calls_task_constraints), submission.brief.constraints)
                if (canDial) {
                    for (mode in listOf(false, true)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag(if (mode) "call-mode-lock" else "call-mode-now")
                            .selectable(selected = locked == mode, role = Role.RadioButton) { locked = mode },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected = locked == mode, onClick = null)
                            Text(stringResource(if (mode) R.string.calls_task_dial_locked else R.string.calls_task_dial_now))
                        }
                    }
                }
                Text(stringResource(when {
                    !canDial -> R.string.calls_task_review_blocked
                    locked -> R.string.calls_task_dial_locked_note
                    else -> R.string.calls_task_confirm_note
                }),
                    style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDial(locked) }, enabled = canDial,
                modifier = Modifier.testTag(if (locked) "call-dial-locked" else "call-dial")) {
                Text(stringResource(if (locked) R.string.calls_task_dial_locked_confirm else R.string.calls_task_confirm_dial))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("call-task-review-cancel")) {
                Text(stringResource(R.string.calls_task_back_to_edit))
            }
        },
    )
}

@Composable
private fun TaskReviewField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MikaTheme.colors.ink3)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
