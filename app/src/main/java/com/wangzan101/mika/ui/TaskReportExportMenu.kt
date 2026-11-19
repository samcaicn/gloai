package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.TransactionTooLargeException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
internal fun ReportExportMenu(runId: String, report: TaskReport?) {
    val context = LocalContext.current
    val scope = LocalLifecycleOwner.current.lifecycleScope
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var choosingShare by remember { mutableStateOf(false) }
    fun save(uri: Uri?, format: TaskReportExport.Format) {
        if (uri == null || report == null) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No destination")).use {
                        TaskReportExport.write(report, format, it)
                    }
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            saving = false
            Toast.makeText(context, if (ok) R.string.report_saved else R.string.report_save_failed, Toast.LENGTH_LONG).show()
        }
    }
    // 复制和分享都直接用报告正文，省去先落盘再打开复制的步骤。
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.report_title), text))
        Toast.makeText(context, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }
    // 纯文本分享走 EXTRA_TEXT；太长会撞 Binder 限制，明确提示改用文件分享。
    fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.report_title)))
        } catch (_: TransactionTooLargeException) {
            Toast.makeText(context, R.string.report_share_too_long, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, R.string.report_share_failed, Toast.LENGTH_LONG).show()
        }
    }
    // TXT / Markdown / Word 先写进缓存再以只读 content:// 地址交给分享面板，不受文本长度限制。
    fun shareFile(format: TaskReportExport.Format) {
        val r = report ?: return
        sharing = true
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val dir = File(context.cacheDir, "report-share").apply { mkdirs() }
                    var file = File(dir, "Mika-$runId.${format.extension}")
                    if (file.exists()) file = File(dir, "Mika-$runId-${System.currentTimeMillis()}.${format.extension}")
                    file.outputStream().use { TaskReportExport.write(r, format, it) }
                    FileProvider.getUriForFile(context, "${context.packageName}.reportfiles", file)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            sharing = false
            if (uri == null) {
                Toast.makeText(context, R.string.report_share_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分接收方靠 ClipData 拿文件名和类型，也靠它拿到读授权。
            send.clipData = ClipData.newUri(context.contentResolver, "", uri)
            try {
                context.startActivity(Intent.createChooser(send, context.getString(R.string.report_title)))
            } catch (_: Exception) {
                Toast.makeText(context, R.string.report_share_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val md = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Markdown.mime)) { save(it, TaskReportExport.Format.Markdown) }
    val word = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Word.mime)) { save(it, TaskReportExport.Format.Word) }
    val txt = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Text.mime)) { save(it, TaskReportExport.Format.Text) }
    Box {
        TextButton({ open = true }, enabled = report != null && !saving && !sharing) { Text(stringResource(R.string.report_export), style = MikaType.Caption) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.report_copy_markdown)) }, onClick = {
                open = false
                report?.let { copy(TaskReportExport.markdown(it)) }
            }, modifier = Modifier.testTag("report-copy-markdown"))
            DropdownMenuItem(text = { Text(stringResource(R.string.report_copy_text)) }, onClick = {
                open = false
                report?.let { copy(TaskReportExport.text(it)) }
            }, modifier = Modifier.testTag("report-copy-text"))
            DropdownMenuItem(text = { Text(stringResource(R.string.report_share)) }, onClick = {
                open = false
                choosingShare = true
            }, modifier = Modifier.testTag("report-share"))
            HorizontalDivider()
            listOf("Markdown (.md)", "Word (.docx)", "TXT (.txt)").forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    val format = TaskReportExport.Format.entries[index]
                    val name = "Mika-${runId}.${format.extension}"
                    when (index) { 0 -> md.launch(name); 1 -> word.launch(name); else -> txt.launch(name) }
                })
            }
        }
    }
    if (choosingShare) AlertDialog(
        onDismissRequest = { choosingShare = false },
        title = { Text(stringResource(R.string.report_share)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                ShareChoice(R.string.report_share_text, "report-share-text") {
                    choosingShare = false
                    report?.let { shareText(TaskReportExport.text(it)) }
                }
                ShareChoice(R.string.report_share_txt, "report-share-txt") {
                    choosingShare = false
                    shareFile(TaskReportExport.Format.Text)
                }
                ShareChoice(R.string.report_share_md, "report-share-md") {
                    choosingShare = false
                    shareFile(TaskReportExport.Format.Markdown)
                }
                ShareChoice(R.string.report_share_docx, "report-share-docx") {
                    choosingShare = false
                    shareFile(TaskReportExport.Format.Word)
                }
            }
        },
        confirmButton = { TextButton({ choosingShare = false }) { Text(stringResource(R.string.report_close)) } },
    )
}

@Composable
private fun ShareChoice(label: Int, tag: String, onClick: () -> Unit) {
    Text(
        stringResource(label),
        Modifier.fillMaxWidth().testTag(tag).clip(MikaShapes.Chip)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        style = MikaType.Body, color = MikaTheme.colors.ink,
    )
}
