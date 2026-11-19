package com.wangzan101.mika.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.backup.BackupEngine
import com.wangzan101.mika.backup.BackupException
import com.wangzan101.mika.backup.BackupManifest
import com.wangzan101.mika.quit.MikaQuit
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & data: export everything to a plain ZIP file, or restore from one.
 *
 * The ZIP is not encrypted on purpose: it is a simple file the user hands to the new install.
 * It contains all secrets in plaintext, so the screen warns to keep it private and delete it
 * after importing. Export only works while this install is still installed: only it holds
 * the KeyStore keys that can decrypt the data. After an import the app must restart, because
 * providers, chats and memory are loaded once at startup and cached in memory.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { BackupEngine.create(context) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingManifest by remember { mutableStateOf<BackupManifest?>(null) }
    var importedManifest by remember { mutableStateOf<BackupManifest?>(null) }

    suspend fun doImport(uri: Uri, replace: Boolean) {
        try {
            val manifest = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Could not open the backup file")
                engine.import(ByteArrayInputStream(bytes), replace)
            }
            importedManifest = manifest
        } catch (e: BackupException) {
            status = e.message ?: context.getString(R.string.backup_import_failed)
        } catch (_: Exception) {
            status = context.getString(R.string.backup_import_failed)
        }
    }

    fun runImport(uri: Uri, replace: Boolean) {
        scope.launch {
            busy = true
            status = null
            try {
                doImport(uri, replace)
            } finally {
                busy = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = null
            try {
                val manifest = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { engine.export(it) }
                        ?: throw IOException("Could not open the file")
                }
                val skipped = manifest.skippedProviderKeys.size + manifest.skippedPrefs.values.sumOf { it.size }
                status = if (skipped > 0) {
                    context.getString(R.string.backup_exported_skipped, manifest.entries.size, skipped)
                } else {
                    context.getString(R.string.backup_exported, manifest.entries.size)
                }
            } catch (_: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                status = context.getString(R.string.backup_export_failed)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = null
            try {
                val manifest = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Could not open the backup file")
                    engine.inspect(ByteArrayInputStream(bytes))
                }
                if (engine.hasExistingData()) {
                    pendingImportUri = uri
                    pendingManifest = manifest
                } else {
                    doImport(uri, replace = false)
                }
            } catch (e: BackupException) {
                status = e.message ?: context.getString(R.string.backup_import_failed)
            } catch (_: Exception) {
                status = context.getString(R.string.backup_import_failed)
            } finally {
                busy = false
            }
        }
    }

    PageScaffold(stringResource(R.string.backup_title), onBack) {
        StateCard(
            MikaIcons.Shield,
            stringResource(R.string.backup_warning_title),
            stringResource(R.string.backup_warning_body),
            TileTone.Warn,
        )

        GroupLabel(stringResource(R.string.backup_included_label))
        Text(stringResource(R.string.backup_included_body), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.backup_excluded_body), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        Spacer(Modifier.height(12.dp))

        GroupCard {
            ListRow(
                MikaIcons.Download, stringResource(R.string.backup_export), stringResource(R.string.backup_export_sub),
                first = true, onClick = {
                    if (!busy) {
                        val name = "mika-backup-${BuildConfig.VERSION_NAME}-${LocalDate.now()}.zip"
                        exportLauncher.launch(name)
                    }
                },
            ) { }
            ListRow(
                MikaIcons.Archive, stringResource(R.string.backup_import), stringResource(R.string.backup_import_sub),
                onClick = { if (!busy) importLauncher.launch(arrayOf("application/zip")) },
            ) { }
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
        Text(
            stringResource(R.string.backup_restart_note),
            Modifier.padding(top = 12.dp),
            style = MikaType.Hint, color = MikaTheme.colors.ink3,
        )
    }

    // Import over existing data: replace everything, or cancel.
    if (pendingManifest != null) {
        MikaDialog(
            onDismissRequest = { pendingManifest = null; pendingImportUri = null },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_body)) },
            confirmButton = {
                TextButton({
                    val uri = pendingImportUri
                    pendingManifest = null
                    pendingImportUri = null
                    if (uri != null) runImport(uri, replace = true)
                }) { Text(stringResource(R.string.backup_replace)) }
            },
            dismissButton = {
                TextButton({ pendingManifest = null; pendingImportUri = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Import done: show what came back, then close the app so the fresh data loads.
    importedManifest?.let { manifest ->
        MikaDialog(
            onDismissRequest = { importedManifest = null },
            title = { Text(stringResource(R.string.backup_imported_title)) },
            text = {
                Column {
                    Text(context.getString(R.string.backup_imported_body, manifest.entries.size))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.backup_after_import_body))
                }
            },
            confirmButton = {
                TextButton({
                    importedManifest = null
                    MikaQuit.quit(context)
                }) { Text(stringResource(R.string.backup_close_app)) }
            },
            dismissButton = {
                TextButton({ importedManifest = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
