package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.settings.DeviceDirectory
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext

@Composable
fun DeviceDirectoriesScreen(store: DeviceDirectoryStore, hub: HelperHub, onBack: () -> Unit) {
    val initialDirectories = remember(store) { try { store.directories() } catch (_: IllegalStateException) { null } }
    var writeError by remember(store) { mutableStateOf(initialDirectories == null) }
    var authorizationInvalid by remember(store) { mutableStateOf(false) }
    var syncAttempt by remember(store) { mutableIntStateOf(0) }
    val directories by remember(store) {
        store.changes().catch { error ->
            if (error is IllegalStateException) writeError = true else throw error
        }
    }.collectAsState(initialDirectories.orEmpty())
    val syncedRevision by remember(store) { store.syncedRevision() }.collectAsState()
    val shell by hub.shell.collectAsState()
    val currentRevision = try { store.authorizationSnapshot().revision } catch (_: IllegalStateException) { null }
    val synced = syncedRevision != null && syncedRevision == currentRevision
    fun updateAuthorization(directory: DeviceDirectory, allowed: Boolean, includeSubdirectories: Boolean) {
        try {
            store.setAuthorization(directory.path, allowed, includeSubdirectories)
            writeError = false
            authorizationInvalid = false
        } catch (_: IllegalStateException) { writeError = true }
        catch (_: IllegalArgumentException) { authorizationInvalid = true }
    }
    var editing by remember { mutableStateOf<DeviceDirectory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var statuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(hub, shell, currentRevision, syncAttempt) {
        if (currentRevision != null) withContext(Dispatchers.IO) {
            try { hub.syncDeviceFiles() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* The status remains unsynced. */ }
        }
    }
    LaunchedEffect(hub, shell, directories, syncedRevision) {
        statuses = withContext(Dispatchers.IO) {
            directories.associate { d -> d.path to try {
                hub.listFiles(d.path, null, 1).getString("status").orEmpty()
            } catch (_: Exception) { "helper_unavailable" } }
        }
    }
    PageScaffold(stringResource(R.string.device_dirs_title), onBack) {
        GrantedFoldersSection()
        FooterNote(stringResource(R.string.device_dirs_explanation))
        if (writeError) FooterNote(stringResource(R.string.device_dirs_write_failed))
        if (authorizationInvalid) FooterNote(stringResource(R.string.device_dirs_invalid) + " " + stringResource(R.string.device_dirs_edit_path))
        GroupCard {
            if (!synced) ListRow(null, stringResource(R.string.device_dirs_retry_sync), first = true, onClick = { syncAttempt++ }) { RowChevron() }
            directories.forEachIndexed { index, directory ->
                val status = if (!directory.allowed) stringResource(R.string.device_dirs_not_authorized) else when (statuses[directory.path]) {
                    "ok" -> stringResource(R.string.device_dirs_available)
                    "not_found" -> stringResource(R.string.device_dirs_missing)
                    "denied" -> stringResource(R.string.device_dirs_denied)
                    else -> stringResource(R.string.device_dirs_unknown)
                }
                val source = when (directory.source) {
                    "preset" -> stringResource(R.string.device_dirs_source_preset)
                    "mika" -> stringResource(R.string.device_dirs_source_mika)
                    else -> stringResource(R.string.device_dirs_source_user)
                }
                ListRow(null, directory.name, "${directory.path}\n${directory.description}\n$status · $source", first = index == 0 && synced,
                    onClick = { editing = directory }) { RowChevron() }
                ListRow(null, stringResource(R.string.device_dirs_authorize),
                    stringResource(if (synced) R.string.device_dirs_synced else R.string.device_dirs_not_synced)) {
                    MikaSwitch(directory.allowed, { allowed ->
                        updateAuthorization(directory, allowed, directory.includeSubdirectories)
                    })
                }
                if (directory.allowed) {
                    ListRow(null, stringResource(R.string.device_dirs_recursive)) {
                        MikaSwitch(directory.includeSubdirectories, { include ->
                            updateAuthorization(directory, true, include)
                        })
                    }
                }
            }
            ListRow(null, stringResource(R.string.device_dirs_add), first = directories.isEmpty() && synced, onClick = { adding = true }) { RowChevron() }
        }
    }
    if (adding || editing != null) {
        val original = editing
        var name by remember(original, adding) { mutableStateOf(original?.name.orEmpty()) }
        var path by remember(original, adding) { mutableStateOf(original?.path.orEmpty()) }
        var description by remember(original, adding) { mutableStateOf(original?.description.orEmpty()) }
        var error by remember(original, adding) { mutableStateOf(false) }
        fun close() { adding = false; editing = null }
        MikaDialog(onDismissRequest = ::close, title = { Text(stringResource(R.string.device_dirs_edit)) }, text = {
            Column {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.device_dirs_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it.take(1024) }, label = { Text(stringResource(R.string.device_dirs_path)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(1000) }, label = { Text(stringResource(R.string.device_dirs_description)) }, modifier = Modifier.fillMaxWidth())
                if (error) Text(stringResource(R.string.device_dirs_invalid))
                if (writeError) Text(stringResource(R.string.device_dirs_write_failed))
                if (original != null) TextButton(onClick = {
                    try { store.remove(original.path); writeError = false; close() }
                    catch (_: IllegalStateException) { writeError = true }
                }) { Text(stringResource(R.string.device_dirs_delete)) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                try { store.save(DeviceDirectory(path, name, description, "user"), original?.path); writeError = false; authorizationInvalid = false; close() }
                catch (_: IllegalArgumentException) { error = true }
                catch (_: IllegalStateException) { error = false; writeError = true }
            }) { Text(stringResource(R.string.device_dirs_save)) }
        }, dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.device_dirs_cancel)) } })
    }
}
