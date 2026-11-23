package com.wangzan101.mika.export

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.export.ExportBlockId
import com.wangzan101.mika.core.export.ExportContent
import com.wangzan101.mika.core.export.ExportIds
import com.wangzan101.mika.core.export.ExportNames
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportRedactor
import com.wangzan101.mika.core.export.ExportSelection
import com.wangzan101.mika.core.export.RedactionLevel
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.chat.ui.AttachmentMessage
import com.wangzan101.mika.chat.ui.ScreenCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ExportCandidate(val id: ExportBlockId, val title: String, val preview: String)

private data class ExportPreview(
    val source: ConversationExportSource,
    val candidates: List<ExportCandidate>,
    val fileAssets: Boolean,
    val screenAssets: Boolean,
)

private data class ExportRequest(
    val source: ConversationExportSource,
    val selection: ExportSelection,
    val content: ExportContent,
    val format: String,
    val bundle: Boolean,
)

/**
 * P1 的导出面板:勾消息、选格式和内容选项,然后走 SAF 保存。
 * 导出在界面作用域里跑;离开页面会取消,暂存由 [ExportRuntime] 清理。
 */
@Composable
internal fun ExportConversationDialog(
    conversationId: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    var exportPreview by remember(conversationId) { mutableStateOf<ExportPreview?>(null) }
    var pending by remember(conversationId) { mutableStateOf<ExportRequest?>(null) }
    var selected by remember { mutableStateOf<Set<ExportBlockId>>(emptySet()) }
    var format by remember { mutableStateOf("markdown") }
    var toolSteps by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(true) }
    var screens by remember { mutableStateOf(true) }
    var timestamps by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(false) }
    var pii by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var outcome by remember { mutableStateOf<ExportOutcome?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(conversationId) {
        val records = withContext(Dispatchers.IO) { ConversationStore.shared(context).records(conversationId) }
        var files = false
        var screens = false
        records.forEach { (turn, shared) ->
            if (shared.isNotEmpty() || AttachmentLines.parse(turn.userText).files.isNotEmpty()) files = true
            if (UserScreenMessage.has(turn.userText)) screens = true
            turn.steers.forEach { steer ->
                if (AttachmentLines.parse(steer).files.isNotEmpty()) files = true
                if (UserScreenMessage.has(steer)) screens = true
            }
        }
        val list = buildList {
            records.forEachIndexed { index, (turn, _) ->
                add(ExportCandidate(ExportIds.user(index), context.getString(R.string.export_label_user), preview(turn.userText)))
                turn.steers.forEachIndexed { i, steer ->
                    add(ExportCandidate(ExportIds.steer(index, i), context.getString(R.string.export_label_steer), preview(steer)))
                }
                add(ExportCandidate(ExportIds.answer(index), context.getString(R.string.export_label_mika), preview(turn.outcome)))
            }
        }
        exportPreview = ExportPreview(ConversationExportSource(context, conversationId, records), list, files, screens)
        selected = list.map { it.id }.toSet()
    }

    val all = exportPreview?.candidates.orEmpty()
    val bundle = exportPreview?.let { (attachments && it.fileAssets) || (screens && it.screenAssets) } == true

    fun start(uri: Uri?) {
        val request = pending
        pending = null
        if (uri == null || request == null) return
        running = true
        failure = null
        outcome = null
        progress = null
        job = scope.launch {
            try {
                val result = ExportRuntime(context).export(
                    source = request.source,
                    title = title,
                    selection = request.selection,
                    content = request.content,
                    presentation = ExportPresentation(),
                    formatId = request.format,
                    destination = uri,
                    bundle = request.bundle,
                ) { p -> handler.post { progress = p } }
                outcome = result
            } catch (e: CancellationException) {
                // 用户取消;暂存由 runtime 的 finally 清理
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
                job = null
            }
        }
    }

    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::start)
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::start)
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::start)
    val suggested = suggestedName(title, bundle, format, context.getString(R.string.export_filename_fallback), pii)

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    running -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.heightIn(max = 24.dp))
                            Text(progressText(progress))
                        }
                    }
                    outcome != null -> {
                        Text(stringResource(R.string.export_done))
                        outcome?.takeIf { it.warnings.isNotEmpty() }?.let {
                            Text(stringResource(R.string.export_warnings, it.warnings.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    failure != null -> Text(stringResource(R.string.export_failed, failure.orEmpty()))
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = format == "markdown", onClick = { format = "markdown" })
                            Text(stringResource(R.string.export_format_markdown))
                            RadioButton(selected = format == "json", onClick = { format = "json" })
                            Text(stringResource(R.string.export_format_json))
                        }
                        OptionRow(stringResource(R.string.export_include_tool_steps), toolSteps) { toolSteps = it }
                        OptionRow(stringResource(R.string.export_include_attachments), attachments) { attachments = it }
                        OptionRow(stringResource(R.string.export_include_screens), screens) { screens = it }
                        OptionRow(stringResource(R.string.export_include_timestamps), timestamps) { timestamps = it }
                        OptionRow(stringResource(R.string.export_include_model), model) { model = it }
                        OptionRow(stringResource(R.string.export_redact_pii), pii) { pii = it }
                        if (bundle) Text(stringResource(R.string.export_bundle_note), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.export_messages), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = {
                                selected = if (selected.size == all.size) emptySet() else all.map { it.id }.toSet()
                            }) { Text(stringResource(R.string.export_select_all)) }
                            Text(
                                stringResource(R.string.export_selected_count, selected.size, all.size),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (exportPreview == null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Text(stringResource(R.string.export_preparing))
                            }
                        } else if (all.isEmpty()) {
                            Text(stringResource(R.string.export_no_messages))
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                                items(all, key = { it.id.value }) { item ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selected = toggle(selected, item.id) }.padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = item.id in selected, onCheckedChange = { selected = toggle(selected, item.id) })
                                        Column(Modifier.weight(1f)) {
                                            Text(item.title, style = MaterialTheme.typography.labelMedium)
                                            Text(item.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (outcome != null || failure != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_close)) }
            } else if (running) {
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.export_cancel)) }
            } else {
                TextButton(
                    enabled = selected.isNotEmpty() && all.isNotEmpty() && pending == null,
                    onClick = {
                        val source = exportPreview?.source ?: return@TextButton
                        pending = ExportRequest(
                            source = source,
                            selection = ExportSelection(conversationId, selected, allSelected = selected.size == all.size),
                            content = ExportContent(
                                includeToolSteps = toolSteps,
                                includeAttachments = attachments,
                                includeScreens = screens,
                                includeTimestamps = timestamps,
                                includeModel = model,
                                redaction = if (pii) RedactionLevel.SecretsAndPii else RedactionLevel.Secrets,
                            ),
                            format = format,
                            bundle = bundle,
                        )
                        when {
                            bundle -> zip.launch(suggested)
                            format == "json" -> json.launch(suggested)
                            else -> md.launch(suggested)
                        }
                    },
                ) { Text(stringResource(R.string.export_button)) }
            }
        },
        dismissButton = {
            if (!running && outcome == null && failure == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_cancel)) }
            }
        },
    )
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun toggle(selected: Set<ExportBlockId>, id: ExportBlockId): Set<ExportBlockId> =
    if (id in selected) selected - id else selected + id

@Composable
private fun progressText(progress: ExportProgress?): String = when (progress?.phase) {
    ExportPhase.Preparing -> stringResource(R.string.export_preparing)
    ExportPhase.Rendering -> stringResource(R.string.export_rendering)
    ExportPhase.Packaging -> stringResource(R.string.export_packaging)
    ExportPhase.Delivering -> stringResource(R.string.export_delivering)
    null -> stringResource(R.string.export_preparing)
}

private fun preview(text: String): String = ScreenCopy.plain(AttachmentMessage.plain(text)).replace('\n', ' ').trim().take(120)

private fun suggestedName(title: String, bundle: Boolean, format: String, fallback: String, pii: Boolean): String {
    val level = if (pii) RedactionLevel.SecretsAndPii else RedactionLevel.Secrets
    val safeTitle = ExportRedactor.apply(title, level)
    val base = ExportNames.safeFileName(safeTitle.ifBlank { fallback }, fallback)
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val ext = if (bundle) "zip" else if (format == "json") "json" else "md"
    return ExportNames.fit("$base-$stamp.$ext")
}
