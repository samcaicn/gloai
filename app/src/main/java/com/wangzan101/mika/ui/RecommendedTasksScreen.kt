package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.settings.RecommendedTaskError
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.ReorderableRow
import com.wangzan101.mika.ui.components.rememberReorderableRowsState
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 常用任务共用首页的本地列表；设置入口编辑，聊天入口选取并填入输入框。 */
@Composable
fun RecommendedTasksScreen(store: RecommendedTaskStore, onBack: () -> Unit, onPick: ((RecommendedTask) -> Unit)? = null, onOpenChecklists: (() -> Unit)? = null, onOpenWorkflows: (() -> Unit)? = null) {
    remember(store) { store.ensureLoaded { ChatCopy.recommendationSeeds() } }
    val tasks by store.tasks.collectAsState()
    val unreadable by store.unreadable.collectAsState()
    var editing by remember { mutableStateOf<RecommendedTask?>(null) }
    var deleting by remember { mutableStateOf<RecommendedTask?>(null) }
    var deleteError by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var reorderError by remember { mutableStateOf<RecommendedTaskError?>(null) }
    val reorder = rememberReorderableRowsState(
        ids = tasks.map { it.id }, scroll = scroll,
        onStart = { reorderError = null },
        onSave = { order ->
            val error = withContext(Dispatchers.IO) { store.reorder(order) }
            reorderError = error
            error == null
        },
    )

    val c = MikaTheme.colors
    PageScaffold(stringResource(R.string.recommendations_title), onBack, scrollState = scroll,
        viewportModifier = Modifier.onGloballyPositioned { reorder.viewport = it.boundsInWindow() }) {
        onOpenChecklists?.let { open ->
            ListRow(MikaIcons.ListChecks, stringResource(R.string.checklists_title), first = true, onClick = open)
        }
        onOpenWorkflows?.let { open ->
            ListRow(MikaIcons.Route, stringResource(R.string.workflows_title), first = false, onClick = open)
        }
        if (unreadable) {
            FooterNote(stringResource(R.string.recommendations_unreadable), color = c.bad)
            if (resetError) FooterNote(stringResource(R.string.recommendations_save_failed), color = c.bad)
            MikaButton(
                stringResource(R.string.recommendations_reset),
                { resetting = true },
                modifier = Modifier.padding(top = 4.dp),
                kind = ButtonKind.Secondary,
                size = ButtonSize.Medium,
            )
        } else {
            GroupCard {
                if (tasks.isEmpty()) {
                    ListRow(
                        MikaIcons.Message,
                        stringResource(R.string.recommendations_empty),
                        stringResource(R.string.recommendations_empty_sub),
                        first = true,
                    )
                } else {
                    tasks.forEachIndexed { index, task ->
                        key(task.id) {
                            ReorderableRow(reorder, task.id, index, "recommendations-drag-${task.id}") { first, dragHandle ->
                                val preview = promptPreview(task.prompt)
                                ListRow(
                                    MikaIcons.Message, task.title, preview.takeIf { it != task.title },
                                    first = first, onClick = { if (!reorder.busy) {
                                        if (onPick != null) onPick(task) else editing = task
                                    } },
                                ) {
                                    if (onPick != null) androidx.compose.material3.IconButton(
                                        onClick = { editing = task }, enabled = !reorder.busy,
                                    ) {
                                        Icon(MikaIcons.Edit, stringResource(R.string.recommendations_edit), Modifier.size(MikaSize.Icon), tint = c.ink3)
                                    }
                                    DeleteTaskButton { if (!reorder.busy) { deleteError = null; deleting = task } }
                                    if (onPick == null) dragHandle()
                                }
                            }
                        }
                    }
                }
                if (tasks.size < RecommendedTaskStore.MAX_TASKS) {
                    ListRow(MikaIcons.Plus, stringResource(R.string.recommendations_add), first = false, onClick = { if (!reorder.busy) adding = true })
                }
            }
            if (tasks.size >= RecommendedTaskStore.MAX_TASKS) {
                FooterNote(stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS))
            }
            reorderError?.let { FooterNote(recommendationError(it), color = c.bad) }
            if (onPick == null && tasks.size > 1) FooterNote(stringResource(R.string.recommendations_drag_hint))
            if (onPick == null) FooterNote(stringResource(R.string.recommendations_footer))
        }
    }

    if (adding || editing != null) {
        RecommendedTaskDialog(
            store = store,
            initial = editing,
            onDismiss = { adding = false; editing = null },
        )
    }
    deleting?.let { task ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recommendations_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.recommendations_delete_body))
                    deleteError?.let { Text(recommendationError(it), Modifier.padding(top = 8.dp), color = c.bad) }
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { store.delete(task.id) }
                        if (result == null) deleting = null else deleteError = result
                    }
                }, Modifier.testTag("recommendations-delete")) { Text(stringResource(R.string.recommendations_delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (resetting) {
        MikaDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.recommendations_reset)) },
            text = { Text(stringResource(R.string.recommendations_reset_body)) },
            confirmButton = {
                TextButton({
                    resetting = false
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { store.discardUnreadable() }
                        resetError = error != null
                    }
                }, Modifier.testTag("recommendations-reset")) { Text(stringResource(R.string.recommendations_reset)) }
            },
            dismissButton = { TextButton({ resetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun RecommendedTaskDialog(store: RecommendedTaskStore, initial: RecommendedTask?, onDismiss: () -> Unit, seedPrompt: String = "") {
    var title by rememberSaveable { mutableStateOf(initial?.title ?: seedPrompt.lineSequence().firstOrNull().orEmpty().take(60)) }
    var prompt by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = initial?.prompt ?: seedPrompt
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var fillIn by rememberSaveable { mutableStateOf(initial?.fillIn == true) }
    var addingField by rememberSaveable { mutableStateOf(false) }
    var fieldName by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (saving) return
        val titleToSave = title
        val promptToSave = prompt.text
        val fillInToSave = fillIn
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (initial == null) store.add(titleToSave, promptToSave, fillInToSave) else store.update(initial.id, titleToSave, promptToSave, fillInToSave)
            }
            saving = false
            if (result == null) onDismiss() else error = result
        }
    }

    MikaDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.recommendations_add else R.string.recommendations_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    title, { title = it; error = null },
                    label = { Text(stringResource(R.string.recommendations_title_label)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("saved-task-title"),
                )
                OutlinedTextField(
                    prompt, { prompt = it; error = null },
                    label = { Text(stringResource(R.string.recommendations_prompt_label)) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("saved-task-prompt"),
                )
                TextButton({ addingField = true }, enabled = !saving, modifier = Modifier.testTag("task-field-add")) {
                    Text(stringResource(R.string.task_field_add))
                }
                error?.let {
                    Text(recommendationError(it), Modifier.padding(top = 8.dp), color = MikaTheme.colors.bad)
                }
            }
        },
        confirmButton = {
            TextButton({ save() }, Modifier.testTag("recommendations-save"), enabled = !saving) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton({ if (!saving) onDismiss() }) { Text(stringResource(R.string.cancel)) } },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
    if (addingField) MikaDialog(
        onDismissRequest = { addingField = false },
        title = { Text(stringResource(R.string.task_field_add)) },
        text = {
            OutlinedTextField(
                fieldName, { fieldName = it }, singleLine = true, enabled = !saving,
                label = { Text(stringResource(R.string.task_field_name)) },
                isError = fieldName.isNotEmpty() && !TaskTemplate.validName(fieldName.trim()),
                supportingText = if (fieldName.isNotEmpty() && !TaskTemplate.validName(fieldName.trim())) ({
                    Text(stringResource(R.string.task_field_name_invalid))
                }) else null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("task-field-name"),
            )
        },
        confirmButton = {
            TextButton({
                val token = "{{${fieldName.trim()}}}"
                val start = prompt.selection.min
                val text = prompt.text.replaceRange(start, prompt.selection.max, token)
                prompt = TextFieldValue(text, TextRange(start + token.length))
                fillIn = true
                fieldName = ""
                addingField = false
                error = null
            }, enabled = !saving && TaskTemplate.validName(fieldName.trim()), modifier = Modifier.testTag("task-field-insert")) {
                Text(stringResource(R.string.task_field_insert))
            }
        },
        dismissButton = { TextButton({ addingField = false }) { Text(stringResource(R.string.cancel)) } },
    )

}

/** 行尾的删除。单独可点,不会连着打开编辑。 */
@Composable
private fun DeleteTaskButton(onClick: () -> Unit) {
    val label = stringResource(R.string.recommendations_delete)
    Box(
        Modifier.size(MikaSize.IconButton).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Trash, label, Modifier.size(MikaSize.Icon), tint = MikaTheme.colors.ink3)
    }
}

@Composable
private fun recommendationError(error: RecommendedTaskError): String = when (error) {
    RecommendedTaskError.BlankTitle -> stringResource(R.string.recommendations_title_required)
    RecommendedTaskError.BlankPrompt -> stringResource(R.string.recommendations_prompt_required)
    RecommendedTaskError.TooLong -> stringResource(R.string.recommendations_too_long)
    RecommendedTaskError.TooManyFields -> stringResource(R.string.task_fields_too_many, TaskTemplate.MAX_FIELDS)
    RecommendedTaskError.TooMany -> stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS)
    RecommendedTaskError.SaveFailed -> stringResource(R.string.recommendations_save_failed)
    RecommendedTaskError.Unreadable -> stringResource(R.string.recommendations_unreadable)
}

/** 设置列表里只露一行,避免一条长提示词把整页撑开。 */
private fun promptPreview(prompt: String): String {
    val flat = prompt.replace(Regex("\\s+"), " ").trim()
    val max = 60
    if (flat.codePointCount(0, flat.length) <= max) return flat
    var end = 0
    var taken = 0
    while (end < flat.length && taken < max) {
        end += Character.charCount(flat.codePointAt(end))
        taken++
    }
    return flat.substring(0, end) + "…"
}
