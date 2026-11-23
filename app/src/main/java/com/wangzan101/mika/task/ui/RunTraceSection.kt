package com.wangzan101.mika.task.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.run.trace.TraceArchive
import com.wangzan101.mika.core.run.trace.TraceRow
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.use

/** 运行还在录 Trace 时,多久重读一次。 */
private const val TRACE_REFRESH_MS = 1_000L

@Composable
internal fun RunTraceSection(runId: String) {
    val context = LocalContext.current
    val store = (context.applicationContext as MikaApp).detailedTraces.store
    // 运行过程页在任务跑着时就能打开:还在录就按秒重读,录完再读最后一次就停
    val loaded by produceState<Pair<TraceArchive, List<TraceRow>>?>(null, runId) {
        var archive: TraceArchive? = null
        while (true) {
            val live = store.recording(runId)
            withContext(Dispatchers.IO) {
                (archive ?: store.archive(runId))?.let { it to try { it.rows() } catch (_: IOException) { emptyList() } }
            }?.let { archive = it.first; value = it }
            if (!live) break
            delay(TRACE_REFRESH_MS)
        }
    }
    loaded?.let { (archive, rows) -> TraceDetails(archive, rows) }
}

@Composable
private fun TraceDetails(archive: TraceArchive, all: List<TraceRow>) {
    val root = all.firstOrNull()
    var expanded by rememberSaveable(archive.runId) { mutableStateOf(false) }
    GroupCard {
        ListRow(MikaIcons.Code, stringResource(R.string.trace_title),
            stringResource(if (root == null || root.status == "partial") R.string.trace_partial else R.string.trace_recorded),
            first = true, onClick = { expanded = !expanded }) { RowChevron() }
        if (expanded && root != null) {
            val worst = all.maxByOrNull { it.uncached ?: -1 }
            for (row in all.drop(1)) key(row.id) { TraceEntryRow(archive, row, row == worst && row.uncached != null) }
            TraceExportControls(archive)
            TraceSendRow(archive)
        }
    }
}

@Composable
private fun TraceEntryRow(archive: TraceArchive, row: TraceRow, worst: Boolean) {
    var open by rememberSaveable(row.id) { mutableStateOf(false) }
    val unknown = stringResource(R.string.trace_unknown)
    val attempt = row.attempt
    val title = if (attempt == null) stringResource(R.string.trace_tool, row.step ?: 0, row.name)
        else stringResource(R.string.trace_attempt, row.step ?: 0, attempt, row.name)
    val status = stringResource(when (row.status) {
        "ok" -> R.string.trace_ok
        "error" -> R.string.trace_error
        "cancelled" -> R.string.trace_cancelled
        else -> R.string.trace_partial
    })
    val detail = buildString {
        append(status).append(" · ").append(row.durationMs).append(" ms")
        if (attempt != null) appendLine().append(stringResource(R.string.trace_counts,
            row.input?.toString() ?: unknown, row.cached?.toString() ?: unknown, row.uncached?.toString() ?: unknown,
            row.output?.toString() ?: unknown))
        if (worst) append(" · ").append(stringResource(R.string.trace_largest))
        row.difference?.let { appendLine().append(stringResource(R.string.trace_diff, it)) }
        row.contextChange?.let { appendLine().append(it) }
        row.toolCallId?.let { appendLine().append(it) }
        if (attempt != null && !row.captured) appendLine().append(stringResource(R.string.trace_no_body))
    }
    ListRow(null, title, detail, onClick = if (row.captured) ({ open = !open }) else null) {
        if (row.captured) RowChevron()
    }
    if (open) {
        var page by rememberSaveable(row.id) { mutableIntStateOf(0) }
        val body by produceState<TraceArchive.Page?>(null, archive, row.id, page) {
            value = withContext(Dispatchers.IO) { runCatching { archive.bodyPage(row.id, page * 3000) }.getOrNull() }
        }
        body?.let { result ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                SelectionContainer { Text(result.text.ifEmpty { unknown }, style = MikaType.Hint) }
                if (page > 0 || result.hasMore) Row {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text("‹") }
                    Text((page + 1).toString())
                    TextButton(onClick = { page++ }, enabled = result.hasMore) { Text("›") }
                }
            }
        }
    }
}

@Composable
private fun TraceExportControls(archive: TraceArchive) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeBody by rememberSaveable(archive.runId) { mutableStateOf(false) }
    var result by rememberSaveable(archive.runId) { mutableIntStateOf(0) }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            result = saveTrace(archive, true) { context.contentResolver.openOutputStream(uri) }
        }
    }
    val proto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-protobuf")) { uri ->
        if (uri != null) scope.launch {
            result = saveTrace(archive, false) { context.contentResolver.openOutputStream(uri) }
        }
    }
    ListRow(null, stringResource(R.string.trace_attach), stringResource(R.string.trace_attach_hint),
        onClick = { includeBody = !includeBody }) { MikaSwitch(includeBody, null) }
    ListRow(MikaIcons.Doc, stringResource(R.string.trace_save), onClick = {
        if (includeBody) zip.launch("mika-trace-${archive.runId}.zip") else proto.launch("mika-trace-${archive.runId}.pb")
    }) { RowChevron() }
    if (result != 0) Text(stringResource(result), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MikaType.Hint)
}

private suspend fun saveTrace(archive: TraceArchive, includeBody: Boolean, open: () -> java.io.OutputStream?): Int =
    withContext(Dispatchers.IO) {
        try {
            (open() ?: throw IOException("No destination")).use { archive.save(it, includeBody) }
            R.string.trace_saved
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { R.string.trace_failed }
    }

@Composable
internal fun TraceSettingsRows() {
    val traces = (LocalContext.current.applicationContext as MikaApp).detailedTraces
    val scope = LocalLifecycleOwner.current.lifecycleScope
    var traceEnabled by remember { mutableStateOf(traces.enabled) }
    var traceLimitMiB by remember { mutableIntStateOf(traces.limitMiB) }
    var editingLimit by rememberSaveable { mutableStateOf(false) }
    fun onTrace(enabled: Boolean) {
        traces.setEnabled(enabled)
        traceEnabled = traces.enabled
    }

    ListRow(null, stringResource(R.string.trace_switch), stringResource(R.string.trace_switch_detail), first = true, onClick = { onTrace(!traceEnabled) }) {
        MikaSwitch(traceEnabled, ::onTrace, Modifier.testTag("dev-trace"))
    }
    ListRow(null, stringResource(R.string.trace_storage_limit), stringResource(R.string.trace_storage_hint), onClick = { editingLimit = true }) {
        RowValue(stringResource(R.string.trace_storage_value, traceLimitMiB))
    }
    if (editingLimit) {
        var text by rememberSaveable { mutableStateOf(traceLimitMiB.toString()) }
        val limit = text.toIntOrNull()?.takeIf { it > 0 }
        MikaDialog(
            onDismissRequest = { editingLimit = false },
            title = { Text(stringResource(R.string.trace_storage_limit)) },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("MiB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(enabled = limit != null, onClick = {
                    if (limit != null) scope.launch {
                        withContext(Dispatchers.IO) { traces.setLimitMiB(limit) }
                        traceLimitMiB = traces.limitMiB
                    }
                    editingLimit = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editingLimit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
