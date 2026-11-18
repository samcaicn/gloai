package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private data class ChecklistEdit(val id: String, val version: Int, val title: String, val body: String, val source: ChecklistSource?)

@Composable
internal fun SavedChecklistsScreen(
    store: SavedChecklistStore, onBack: () -> Unit, onUseDraft: ((String) -> Unit)? = null,
    incoming: ChecklistSource? = null,
) {
    val items by store.items.collectAsState()
    val loaded by store.loaded.collectAsState()
    val readError by store.readError.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var incomingUsed by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChecklistEdit?>(null) }
    var deleting by remember { mutableStateOf<SavedChecklist?>(null) }
    var error by remember { mutableStateOf<ChecklistError?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableStateOf<Pair<ChecklistRevision, ChecklistRevision?>?>(null) }
    var action by remember { mutableStateOf<Pair<ChecklistRevision, Boolean>?>(null) }
    val selected = items.find { it.id == selectedId }
    val choosingUpdate = incoming != null && !incomingUsed
    val context = LocalContext.current
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    val back: () -> Unit = {
        when { history -> history = false; selectedId != null -> selectedId = null; else -> onBack() }
    }
    BackHandler(onBack = back)
    PageScaffold(
        if (history) stringResource(R.string.checklist_history)
        else selected?.current?.title ?: stringResource(if (choosingUpdate) R.string.checklist_choose else R.string.checklists_title),
        onBack = back,
    ) {
        if (readError) {
            FooterNote(stringResource(R.string.checklist_read_failed), color = MikaTheme.colors.bad)
            MikaButton(stringResource(R.string.retry), { scope.launch { withContext(Dispatchers.IO) { store.load() } } })
        } else if (!loaded) {
            CircularProgressIndicator(Modifier.padding(16.dp))
        } else if (selected == null) {
            GroupCard {
                ListRow(MikaIcons.Plus, stringResource(R.string.checklist_new), first = true, onClick = {
                    val source = incoming.takeIf { choosingUpdate }
                    editing = ChecklistEdit(UUID.randomUUID().toString(), 0,
                        source?.text?.lineSequence()?.firstOrNull().orEmpty().take(60), source?.text.orEmpty(), source)
                })
                items.forEach { item ->
                    ListRow(MikaIcons.ListChecks, item.current.title, item.current.body.lineSequence().firstOrNull()?.take(60), onClick = {
                        if (choosingUpdate) editing = ChecklistEdit(item.id, item.current.version, item.current.title, incoming!!.text, incoming)
                        else selectedId = item.id
                    })
                }
            }
            if (items.isEmpty()) FooterNote(stringResource(R.string.checklist_empty))
        } else if (history) {
            GroupCard {
                selected.revisions.forEachIndexed { index, item ->
                    ListRow(null, stringResource(R.string.checklist_version, item.version), savedTime(item.savedAt), first = index == 0,
                        onClick = { revision = item to selected.revisions.getOrNull(index + 1) })
                }
            }
            FooterNote(stringResource(R.string.checklist_history_limit, SavedChecklistStore.MAX_REVISIONS))
        } else {
            val current = selected.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaButton(stringResource(R.string.checklist_reuse), { action = current to false }, modifier = Modifier.weight(1f).testTag("checklist-reuse"))
                MikaButton(stringResource(R.string.checklist_update), { action = current to true }, modifier = Modifier.weight(1f).testTag("checklist-update"), kind = ButtonKind.Secondary)
            }
            SelectionContainer {
                Text(current.body, Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("checklist-body"), color = MikaTheme.colors.ink)
            }
            GroupCard {
                ListRow(MikaIcons.Edit, stringResource(R.string.checklist_edit), first = true, onClick = {
                    editing = ChecklistEdit(selected.id, current.version, current.title, current.body, null)
                })
                ListRow(MikaIcons.Copy, stringResource(R.string.chat_copy), onClick = { copyMessage(context, current.body) })
                ListRow(MikaIcons.ListChecks, stringResource(R.string.checklist_history), onClick = { history = true })
                ListRow(MikaIcons.Trash, stringResource(R.string.recommendations_delete), onClick = { deleting = selected; error = null })
            }
        }
    }
    editing?.let { draft ->
        ChecklistEditor(store, draft, onDismiss = { editing = null }, onSaved = {
            editing = null; selectedId = draft.id; incomingUsed = true; error = null
        })
    }
    action?.let { (current, update) ->
        ChecklistActionDialog(current, update, onDismiss = { action = null }, onUse = onUseDraft?.let { cb -> { text -> action = null; cb(text) } })
    }
    revision?.let { (current, before) -> ChecklistRevisionDialog(current, before) { revision = null } }
    deleting?.let { item ->
        var busy by remember { mutableStateOf(false) }
        MikaDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.checklist_delete_question)) },
            text = error?.let { failure -> { Text(checklistError(failure), color = MikaTheme.colors.bad) } },
            confirmButton = { TextButton({
                busy = true
                scope.launch {
                    val failure = withContext(Dispatchers.IO) { store.delete(item.id, item.current.version) }
                    busy = false
                    if (failure == null) { deleting = null; selectedId = null } else error = failure
                }
            }, enabled = !busy, modifier = Modifier.testTag("checklist-delete")) { Text(stringResource(R.string.recommendations_delete)) } },
            dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ChecklistEditor(store: SavedChecklistStore, initial: ChecklistEdit, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var title by rememberSaveable(initial.id, initial.version) { mutableStateOf(initial.title) }
    var body by rememberSaveable(initial.id, initial.version) { mutableStateOf(initial.body) }
    var error by remember { mutableStateOf<ChecklistError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MikaDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (initial.version == 0) R.string.checklist_new else R.string.checklist_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it; error = null }, label = { Text(stringResource(R.string.recommendations_title_label)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("checklist-title"))
                OutlinedTextField(body, { body = it; error = null }, label = { Text(stringResource(R.string.checklist_content)) },
                    minLines = 5, maxLines = 10, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("checklist-input"))
                error?.let { Text(checklistError(it), color = MikaTheme.colors.bad, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton({
            busy = true
            scope.launch {
                val failure = withContext(Dispatchers.IO) { store.save(initial.id, initial.version, title, body, initial.source) }
                busy = false
                if (failure == null) onSaved() else error = failure
            }
        }, enabled = !busy, modifier = Modifier.testTag("checklist-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChecklistActionDialog(current: ChecklistRevision, update: Boolean, onDismiss: () -> Unit, onUse: ((String) -> Unit)?) {
    var text by rememberSaveable(current.version, update) { mutableStateOf("") }
    val context = LocalContext.current
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (update) R.string.checklist_update else R.string.checklist_reuse)) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(stringResource(if (update) R.string.checklist_supplement else R.string.checklist_next)) },
            minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("checklist-action-input")) },
        confirmButton = {
            if (onUse != null) {
                TextButton({
                    onUse(if (update) context.getString(R.string.checklist_update_prompt, current.body, text.trim())
                        else context.getString(R.string.checklist_reuse_prompt, text.trim(), current.title, current.body))
                }, enabled = text.isNotBlank(), modifier = Modifier.testTag("checklist-action-apply")) { Text(stringResource(R.string.checklist_fill_input)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChecklistRevisionDialog(current: ChecklistRevision, before: ChecklistRevision?, onDismiss: () -> Unit) {
    var showSource by remember { mutableStateOf(false) }
    val changes = remember(current, before) { before?.let { checklistChanges(it.body, current.body) } }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checklist_version, current.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(current.title)
                Text(savedTime(current.savedAt))
                changes?.let {
                    if (before?.title != current.title) Text(stringResource(R.string.checklist_previous_title, before?.title.orEmpty()))
                    if (it.added.isNotEmpty()) { GroupLabel(stringResource(R.string.checklist_added)); SelectionContainer { Text(it.added.joinToString("\n")) } }
                    if (it.removed.isNotEmpty()) { GroupLabel(stringResource(R.string.checklist_removed)); SelectionContainer { Text(it.removed.joinToString("\n")) } }
                    if (it.reordered) Text(stringResource(R.string.checklist_reordered))
                }
                GroupLabel(stringResource(R.string.checklist_content))
                SelectionContainer { Text(current.body) }
                current.source?.let { source ->
                    TextButton({ showSource = !showSource }) { Text(stringResource(R.string.checklist_source)) }
                    if (showSource) SelectionContainer { Text(source.text) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok_got_it)) } },
    )
}

private fun savedTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
@Composable private fun checklistError(error: ChecklistError): String = stringResource(when (error) {
    ChecklistError.Blank -> R.string.checklist_blank
    ChecklistError.TooLong -> R.string.checklist_too_long
    ChecklistError.Full -> R.string.checklist_full
    ChecklistError.ReadFailed -> R.string.checklist_read_failed
    ChecklistError.SaveFailed -> R.string.recommendations_save_failed
    ChecklistError.Changed, ChecklistError.Missing -> R.string.checklist_changed
})
