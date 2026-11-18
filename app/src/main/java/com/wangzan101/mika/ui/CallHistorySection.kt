package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.calls.SummaryStatus
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class HistoryFilter(@StringRes val label: Int) {
    ALL(R.string.calls_history_all), OPEN(R.string.calls_history_open), DONE(R.string.calls_history_done),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallHistorySection(
    history: List<CallRecord>,
    historyWriteFailed: Boolean,
    onMark: suspend (String, Boolean) -> CallActionResult,
    onDelete: suspend (String) -> CallActionResult,
    onRetrySummary: suspend (String) -> CallActionResult,
    onClear: suspend () -> CallActionResult,
    focusRecordId: String? = null,
    onReuseTask: ((String, CallTaskBrief) -> Unit)? = null,
    onSaveDraft: (suspend (String, String) -> CallActionResult)? = null,
) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingClear by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusRecordId, history) {
        if (focusRecordId != null && history.any { it.id == focusRecordId }) {
            filter = HistoryFilter.ALL
            query = ""
            showAll = true
            expanded = focusRecordId
        }
    }
    val failure = stringResource(R.string.calls_action_failed)
    val clearFailure = stringResource(R.string.calls_history_clear_failed)
    val summaryFailure = stringResource(R.string.calls_summary_failed)
    val cleared = stringResource(R.string.calls_history_cleared)
    val deleted = stringResource(R.string.calls_history_deleted)
    val handled = stringResource(R.string.calls_history_done)
    val unhandled = stringResource(R.string.calls_history_open)
    val incoming = directionLabel(CallDirection.INCOMING)
    val outgoing = directionLabel(CallDirection.OUTGOING)
    val results = remember(history, filter, query, incoming, outgoing) {
        val text = query.trim()
        val digits = text.filter(Char::isDigit).takeIf {
            it.isNotEmpty() && text.all { char -> char.isDigit() || char in "+ ().-" }
        }
        history.filter { record ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.OPEN -> !record.handled
                HistoryFilter.DONE -> record.handled
            }
            val direction = if (record.direction == CallDirection.INCOMING) incoming else outgoing
            matchesFilter && (text.isEmpty() || record.number.orEmpty().contains(text, ignoreCase = true) ||
                (digits != null && record.number.orEmpty().filter(Char::isDigit).contains(digits)) ||
                direction.contains(text, ignoreCase = true) || record.result.contains(text, ignoreCase = true) ||
                record.transcript.any { it.text.contains(text, ignoreCase = true) } ||
                record.summary?.let { summary ->
                    listOfNotNull(summary.purpose, summary.conclusion, summary.followUpDraft).plus(summary.highlights)
                        .plus(summary.uncertainties).plus(summary.actions.map { it.text })
                        .any { it.contains(text, ignoreCase = true) }
                } == true ||
                record.task?.let { task ->
                    (listOf(task.goal, task.context, task.constraints) + task.questions).any {
                        it.contains(text, ignoreCase = true)
                    }
                } == true)
        }
    }

    fun change(success: String, errorMessage: String? = null, action: suspend () -> CallActionResult) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val result = action()
                feedback = if (result.ok) CallActionResult(true, success)
                    else if (errorMessage != null) CallActionResult(false, errorMessage) else result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                feedback = CallActionResult(false, errorMessage ?: failure)
            } finally { busy = false }
        }
    }

    GroupLabel(stringResource(R.string.calls_tab_history))
    if (historyWriteFailed) FooterNote(
        stringResource(R.string.calls_history_write_failed),
        color = MaterialTheme.colorScheme.error,
    )
    feedback?.let { result ->
        FooterNote(result.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (result.ok) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
    }
    if (history.isNotEmpty() || historyWriteFailed) {
        TextButton(
            onClick = { pendingClear = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("call-history-clear"),
        ) {
            Text(stringResource(R.string.calls_history_clear), color = MaterialTheme.colorScheme.error)
        }
    }
    if (history.isEmpty()) {
        GroupCard { ListRow(MikaIcons.Clock, stringResource(R.string.calls_history_empty), first = true) }
    } else {
        OutlinedTextField(
            query, { query = it; showAll = false }, singleLine = true,
            label = { Text(stringResource(R.string.calls_history_search)) },
            leadingIcon = { Icon(MikaIcons.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag("call-history-search"),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { choice ->
                FilterChip(
                    selected = filter == choice, onClick = { filter = choice; showAll = false },
                    label = { Text(stringResource(choice.label)) },
                    modifier = Modifier.testTag("call-history-filter-${choice.name.lowercase()}"),
                )
            }
        }
        if (results.isEmpty()) {
            GroupCard { ListRow(MikaIcons.Search, stringResource(R.string.calls_history_no_match), first = true) }
        } else GroupCard {
            val visible = if (showAll) results else results.take(3)
            visible.forEachIndexed { index, record ->
                key(record.id) {
                    CallHistoryItem(
                        record, expanded == record.id, first = index == 0, busy = busy,
                        onExpand = { expanded = if (expanded == record.id) null else record.id },
                        onMark = {
                            val next = !record.handled
                            change(if (next) handled else unhandled) { onMark(record.id, next) }
                        },
                        onDelete = { pendingDelete = record.id },
                        onSaveDraft = onSaveDraft?.let { save -> { text -> save(record.id, text) } },
                        onRetrySummary = { change("摘要已更新", summaryFailure) { onRetrySummary(record.id) } },
                        onReuse = if (record.number != null && record.task != null && onReuseTask != null) {
                            { onReuseTask(record.number, record.task) }
                        } else null,
                    )
                }
            }
            if (results.size > 3) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showAll) stringResource(R.string.calls_history_less)
                        else stringResource(R.string.calls_history_more, results.size))
                }
            }
        }
    }

    if (pendingClear) AlertDialog(
        onDismissRequest = { pendingClear = false },
        title = { Text(stringResource(R.string.calls_history_clear)) },
        text = { Text(stringResource(R.string.calls_history_clear_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                pendingClear = false
                change(cleared, clearFailure) { onClear() }
            }, enabled = !busy, modifier = Modifier.testTag("call-history-clear-confirm")) {
                Text(stringResource(R.string.calls_history_clear), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { pendingClear = false }) { Text(stringResource(R.string.calls_cancel)) }
        },
    )

    val toDelete = history.firstOrNull { it.id == pendingDelete }
    if (toDelete != null) AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.calls_history_delete)) },
        text = {
            val locale = LocalContext.current.resources.configuration.locales[0]
            val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
            val whenCalled = remember(toDelete.startedAtMs, formatter) { formatter.format(Date(toDelete.startedAtMs)) }
            val number = toDelete.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$number · $whenCalled", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.calls_history_delete_confirm))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                pendingDelete = null
                change(deleted) { onDelete(toDelete.id) }
            }, enabled = !busy, modifier = Modifier.testTag("call-history-delete-confirm")) {
                Text(stringResource(R.string.calls_history_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun CallHistoryItem(
    record: CallRecord, expanded: Boolean, first: Boolean, busy: Boolean,
    onExpand: () -> Unit, onMark: () -> Unit, onDelete: () -> Unit, onRetrySummary: () -> Unit,
    onReuse: (() -> Unit)?,
    onSaveDraft: (suspend (String) -> CallActionResult)?,
) {
    var showTask by rememberSaveable(record.id) { mutableStateOf(false) }
    var showTranscript by rememberSaveable(record.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    val start = remember(record.startedAtMs, formatter) { formatter.format(Date(record.startedAtMs)) }
    val end = remember(record.endedAtMs, formatter) { formatter.format(Date(record.endedAtMs)) }
    val direction = directionLabel(record.direction)
    val status = stringResource(if (record.handled) R.string.calls_history_done else R.string.calls_history_open)
    val name = record.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number)
    val expansion = stringResource(if (expanded) R.string.calls_collapse else R.string.calls_transcript)
    Column(Modifier.fillMaxWidth().semantics { stateDescription = expansion }) {
        ListRow(MikaIcons.Clock, record.task?.goal ?: name,
            if (record.task != null) "$name · $direction · $start" else "$direction · $start",
            first = first, detail = "$status · ${record.result}",
            onClick = onExpand) { CallExpandIcon(expanded) }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text(stringResource(R.string.calls_history_period, start, end),
                    style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                record.summary?.let { summary ->
                    summary.purpose?.let { Text("${stringResource(R.string.calls_summary_purpose)}：$it") }
                    if (summary.highlights.isNotEmpty()) {
                        Text(stringResource(R.string.calls_summary_highlights), style = MaterialTheme.typography.labelLarge)
                        summary.highlights.forEach { Text("• $it") }
                    }
                    summary.conclusion?.let { Text("${stringResource(R.string.calls_summary_conclusion)}：$it") }
                    if (summary.uncertainties.isNotEmpty()) {
                        Text(stringResource(R.string.calls_summary_uncertainties), style = MaterialTheme.typography.labelLarge)
                        summary.uncertainties.forEach { Text("• $it") }
                    }
                    if (summary.actions.isNotEmpty()) {
                        Text(stringResource(R.string.calls_summary_actions), style = MaterialTheme.typography.labelLarge)
                        summary.actions.forEach { item ->
                            val state = when (item.status) {
                                com.wangzan101.mika.calls.CallFollowUp.Status.PENDING -> R.string.calls_summary_pending
                                com.wangzan101.mika.calls.CallFollowUp.Status.DONE -> R.string.calls_summary_done
                                com.wangzan101.mika.calls.CallFollowUp.Status.UNKNOWN -> R.string.calls_summary_unknown
                            }
                            Text("${stringResource(state)}：${item.text}")
                        }
                    }
                }
                if (record.summaryStatus == SummaryStatus.READY && record.summary != null) {
                    CallFollowUpDraft(record.id, record.summary.followUpDraft, onSaveDraft)
                }
                if (record.summaryStatus == SummaryStatus.PENDING) {
                    Text(stringResource(R.string.calls_summary_generating), color = MikaTheme.colors.ink3)
                } else if (record.summaryStatus == SummaryStatus.FAILED) {
                    Text(stringResource(R.string.calls_summary_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetrySummary, enabled = !busy, modifier = Modifier.testTag("call-summary-retry")) {
                        Text(stringResource(R.string.calls_summary_retry))
                    }
                }
                record.task?.let { task ->
                    Text(stringResource(R.string.calls_record_result_unconfirmed),
                        style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                    TextButton(onClick = { showTask = !showTask }, modifier = Modifier.fillMaxWidth().testTag("call-record-task-toggle")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.calls_record_brief))
                            CallExpandIcon(showTask)
                        }
                    }
                    if (showTask) SelectionContainer {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.calls_record_goal), style = MaterialTheme.typography.labelLarge)
                            Text(task.goal)
                            if (task.questions.isNotEmpty()) {
                                Text(stringResource(R.string.calls_record_questions), style = MaterialTheme.typography.labelLarge)
                                task.questions.forEachIndexed { index, question -> Text("${index + 1}. $question") }
                            }
                            if (task.context.isNotBlank()) {
                                Text(stringResource(R.string.calls_record_context), style = MaterialTheme.typography.labelLarge)
                                Text(task.context)
                            }
                            if (task.constraints.isNotBlank()) {
                                Text(stringResource(R.string.calls_record_constraints), style = MaterialTheme.typography.labelLarge)
                                Text(task.constraints)
                            }
                        }
                    }
                    if (onReuse != null) OutlinedButton(onClick = onReuse, modifier = Modifier.fillMaxWidth().testTag("call-record-reuse")) {
                        Text(stringResource(R.string.calls_record_reuse))
                    }
                }
                TextButton(onClick = { showTranscript = !showTranscript }, modifier = Modifier.fillMaxWidth().testTag("call-record-transcript-toggle")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.calls_original_transcript))
                        CallExpandIcon(showTranscript)
                    }
                }
                if (showTranscript) {
                    if (record.transcript.isEmpty()) {
                        Text(stringResource(R.string.calls_no_transcript), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                    } else SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            record.transcript.forEach { CallTranscriptText(it) }
                        }
                    }
                }
                record.audioReport?.let { report ->
                    val label = stringResource(R.string.diagnostics_copy)
                    val copied = stringResource(R.string.diagnostics_copied)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText(label, report.supportSummary()))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.testTag("call-record-diagnostics-copy")) { Text(label) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onMark, enabled = !busy, modifier = Modifier.weight(1f).testTag("call-history-mark")) {
                        Text(stringResource(if (record.handled) R.string.calls_history_reopen else R.string.calls_history_mark_done))
                    }
                    TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f).testTag("call-history-delete")) {
                        Text(stringResource(R.string.calls_history_delete),
                            color = if (busy) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CallTranscriptText(line: CallTranscriptLine) {
    Column {
        Text(stringResource(callTranscriptSpeakerLabel(line.role)),
            style = MaterialTheme.typography.labelMedium, color = MikaTheme.colors.ink3)
        Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One role mapping for the full transcript and the compact latest-line preview. */
@StringRes
internal fun callTranscriptSpeakerLabel(role: String): Int = when (role) {
    "assistant" -> R.string.app_name
    "system" -> R.string.calls_dtmf_status_label
    else -> R.string.calls_caller
}
