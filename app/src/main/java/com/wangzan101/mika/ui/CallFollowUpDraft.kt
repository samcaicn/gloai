package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A local draft only: no contact lookup, WeChat launch or send action. */
@Composable
internal fun CallFollowUpDraft(
    recordId: String,
    draft: String?,
    onSave: (suspend (String) -> CallActionResult)?,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.calls_draft_title)
    val copied = stringResource(R.string.calls_draft_copied)
    val saveFailed = stringResource(R.string.calls_draft_save_failed)
    var editing by rememberSaveable(recordId) { mutableStateOf(false) }
    var text by rememberSaveable(recordId) { mutableStateOf(draft.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (draft != null) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(draft, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("call-draft-text")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSave != null) TextButton(onClick = { text = draft; error = false; editing = true },
                modifier = Modifier.testTag("call-draft-edit")) { Text(stringResource(R.string.calls_draft_edit)) }
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, draft))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.testTag("call-draft-copy")) { Text(stringResource(R.string.calls_draft_copy)) }
        }
    } else if (onSave != null) {
        TextButton(onClick = { text = ""; error = false; editing = true },
            modifier = Modifier.testTag("call-draft-create")) { Text(stringResource(R.string.calls_draft_create)) }
    }
    if (editing && onSave != null) AlertDialog(
        onDismissRequest = { if (!saving) editing = false },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { if (it.length <= CallHistory.MAX_DRAFT_LENGTH) { text = it; error = false } },
                    enabled = !saving, minLines = 3, maxLines = 8,
                    label = { Text(title) },
                    modifier = Modifier.fillMaxWidth().testTag("call-draft-input"))
                if (error) Text(saveFailed, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && text.isNotBlank(), modifier = Modifier.testTag("call-draft-save"), onClick = {
                saving = true
                scope.launch {
                    try {
                        val result = onSave(text.trim())
                        error = !result.ok
                        if (result.ok) editing = false
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = true
                    } finally { saving = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text(stringResource(R.string.calls_cancel)) } },
    )
}
