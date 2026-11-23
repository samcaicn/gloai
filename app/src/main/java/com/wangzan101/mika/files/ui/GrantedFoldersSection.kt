package com.wangzan101.mika.files.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.refresh

/** OpenDocumentTree drops result flags. Only persist what both the user and the provider allowed. */
internal class FolderGrantPicker(write: Boolean) : ActivityResultContract<Unit, Pair<Uri, Int>?>() {
    private val modes = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(modes or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?): Pair<Uri, Int>? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return intent.data?.let { it to (intent.flags and modes) }
    }
}

/** The system picker is the only way to add a grant; directory hints never become write permissions. */
@Composable
internal fun GrantedFoldersSection() {
    val context = LocalContext.current
    val folders = remember(context) { GrantedFolderFiles(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val refreshLock = remember { Mutex() }
    var grants by remember { mutableStateOf<List<GrantedFolderFiles.Grant>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    fun refresh(action: () -> Unit = {}) {
        scope.launch { refreshLock.withLock {
            busy = true
            try {
                grants = withContext(Dispatchers.IO) { action(); folders.grants() }
                error = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        } }
    }
    fun selected(result: Pair<Uri, Int>?) {
        if (result != null) refresh { folders.authorize(result.first, result.second) }
    }
    val readPicker = rememberLauncherForActivityResult(remember { FolderGrantPicker(write = false) }, ::selected)
    val writePicker = rememberLauncherForActivityResult(remember { FolderGrantPicker(write = true) }, ::selected)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    GroupCard {
        grants.forEachIndexed { index, grant ->
            val access = stringResource(when {
                !grant.available -> R.string.folder_grant_unavailable
                grant.writable -> R.string.folder_grant_writable
                else -> R.string.folder_grant_readonly
            })
            ListRow(null, grant.name, access, detail = grant.displayLocation, first = index == 0) {
                TextButton(enabled = !busy, onClick = { refresh { folders.revoke(grant.uri) } }) {
                    Text(stringResource(R.string.folder_grant_revoke))
                }
            }
        }
        ListRow(null, stringResource(if (busy) R.string.folder_grant_loading else R.string.folder_grant_choose_readonly),
            first = grants.isEmpty(), onClick = if (busy) null else ({
                try { readPicker.launch(Unit) } catch (_: ActivityNotFoundException) { error = true }
            })) { RowChevron() }
        ListRow(null, stringResource(R.string.folder_grant_choose), onClick = if (busy) null else ({
            try { writePicker.launch(Unit) } catch (_: ActivityNotFoundException) { error = true }
        })) { RowChevron() }
    }
    if (error) FooterNote(stringResource(R.string.folder_grant_error))
}
