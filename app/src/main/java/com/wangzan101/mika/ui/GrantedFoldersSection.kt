package com.wangzan101.mika.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.GrantedFolderFiles
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The system picker is the only way to add a grant; directory hints never become write permissions. */
@Composable
internal fun GrantedFoldersSection() {
    val context = LocalContext.current
    val folders = remember(context) { GrantedFolderFiles(context.applicationContext.contentResolver) }
    val scope = rememberCoroutineScope()
    var grants by remember { mutableStateOf<List<GrantedFolderFiles.Grant>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    fun refresh(action: () -> Unit = {}) {
        scope.launch {
            busy = true
            try {
                grants = withContext(Dispatchers.IO) { action(); folders.grants() }
                error = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) refresh { folders.authorize(uri) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    GroupCard {
        grants.forEachIndexed { index, grant ->
            ListRow(null, grant.name, stringResource(when {
                !grant.available -> R.string.folder_grant_unavailable
                grant.writable -> R.string.folder_grant_writable
                else -> R.string.folder_grant_readonly
            }), first = index == 0) {
                TextButton(enabled = !busy, onClick = { refresh { folders.revoke(grant.uri) } }) {
                    Text(stringResource(R.string.folder_grant_revoke))
                }
            }
        }
        ListRow(null, stringResource(if (busy) R.string.folder_grant_loading else R.string.folder_grant_choose),
            first = grants.isEmpty(), onClick = if (busy) null else ({
                try { picker.launch(null) } catch (_: android.content.ActivityNotFoundException) { error = true }
            })) { RowChevron() }
    }
    if (error) FooterNote(stringResource(R.string.folder_grant_error))
}
