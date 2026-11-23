package com.wangzan101.mika.browser.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.BrowserDownload
import com.wangzan101.mika.browser.BrowserDownloads
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.delay

/**
 * 下载面板:系统下载里的这个 App 的下载,最新的在前;每秒刷新一次。
 * 正在下载的可取消;下完的点一行打开,旁边分享或删除;删除前确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(downloads: BrowserDownloads, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    var items by remember { mutableStateOf<List<BrowserDownload>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BrowserDownload?>(null) }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    LaunchedEffect(Unit) {
        while (true) {
            items = downloads.list()
            loaded = true
            delay(1_000)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-downloads")) {
            Text(
                tr(R.string.browser_downloads), Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                style = MikaType.Title, color = c.ink,
            )
            if (loaded && items.isEmpty()) {
                Text(
                    tr(R.string.browser_download_empty), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MikaType.Body, color = c.ink3,
                )
            }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Hairline(start = 20.dp)
                    DownloadRow(
                        context, item,
                        onOpen = { openDownload(context, downloads, item).let { if (it != null) toast(it) } },
                        onShare = { shareDownload(context, downloads, item).let { if (it != null) toast(it) } },
                        onCancel = {
                            downloads.remove(item.id)
                            items = items.filterNot { it.id == item.id }
                        },
                        onDelete = { deleting = item },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    deleting?.let { item ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr(R.string.browser_download_delete_q)) },
            text = { Text(item.title) },
            confirmButton = {
                TextButton(onClick = {
                    downloads.remove(item.id)
                    items = items.filterNot { it.id == item.id }
                    deleting = null
                }) { Text(tr(R.string.browser_download_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DownloadRow(
    context: Context,
    item: BrowserDownload,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .then(if (item.done) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText(context, item), Modifier.padding(top = 2.dp), style = MikaType.Caption,
                    color = if (item.failed) c.bad else c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                item.running -> SheetAction(MikaIcons.Close, tr(R.string.browser_download_cancel), onCancel)
                item.done -> {
                    SheetAction(MikaIcons.Share, tr(R.string.share), onShare)
                    SheetAction(MikaIcons.Trash, tr(R.string.browser_download_delete), onDelete)
                }
                else -> SheetAction(MikaIcons.Trash, tr(R.string.browser_download_delete), onDelete)
            }
        }
        val percent = item.percent
        if (percent != null) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(MikaShapes.Micro).background(c.fill)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(c.accent))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(17.dp), tint = MikaTheme.colors.ink3) }
}

private fun statusText(context: Context, item: BrowserDownload): String = when {
    item.done -> {
        val size = Formatter.formatFileSize(context, if (item.total > 0) item.total else item.soFar)
        "${tr(R.string.browser_download_done)} · $size"
    }
    item.failed -> tr(R.string.browser_download_failed)
    item.percent != null -> {
        val size = Formatter.formatFileSize(context, item.soFar) + " / " + Formatter.formatFileSize(context, item.total)
        "${item.percent}% · $size"
    }
    else -> tr(R.string.browser_download_running)
}

private fun openDownload(context: Context, downloads: BrowserDownloads, item: BrowserDownload): String? {
    val uri = downloads.contentUri(item.id) ?: return tr(R.string.browser_download_open_failed)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mime?.takeIf { it.isNotBlank() } ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_download_open_failed)
    }
}

private fun shareDownload(context: Context, downloads: BrowserDownloads, item: BrowserDownload): String? {
    val uri = downloads.contentUri(item.id) ?: return tr(R.string.browser_download_open_failed)
    val send = Intent(Intent.ACTION_SEND)
        .setType(item.mime?.takeIf { it.isNotBlank() } ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_no_share_app)
    }
}
