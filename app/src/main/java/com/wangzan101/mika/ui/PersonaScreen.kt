package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TokenEstimate
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.settings.Persona
import com.wangzan101.mika.settings.PersonaStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 设置 › 人格:列表、新建、编辑、复制、删除。不导出/导入(v2),不绑定模型(v3)。 */
@Composable
fun PersonaScreen(store: PersonaStore, onBack: () -> Unit) {
    val personas by store.personas.collectAsState()
    var editing by remember { mutableStateOf<Persona?>(null) }
    var creating by remember { mutableStateOf(false) }

    val current = editing
    if (creating || current != null) {
        PersonaEditor(
            initial = current,
            onSave = { name, description, prompt ->
                val result = if (current == null) store.add(name, description, prompt)
                else store.update(current.copy(name = name, description = description, prompt = prompt))
                result.isSuccess
            },
            onDelete = current?.let { p -> { store.delete(p.id) } },
            onDuplicate = current?.let { p -> { store.duplicate(p.id) } },
            onDone = { creating = false; editing = null },
            onBack = { creating = false; editing = null },
        )
        return
    }

    PageScaffold(stringResource(R.string.persona_title), onBack) {
        if (personas.isEmpty()) {
            GroupCard {
                ListRow(
                    MikaIcons.Spark, stringResource(R.string.persona_empty),
                    stringResource(R.string.persona_empty_sub), first = true,
                )
            }
        } else {
            GroupCard {
                personas.forEachIndexed { i, p ->
                    ListRow(
                        MikaIcons.Spark, p.name,
                        subtitle = p.description.takeIf { it.isNotBlank() },
                        first = i == 0,
                        onClick = { editing = p },
                    ) { RowChevron() }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MikaButton(stringResource(R.string.persona_new), { creating = true }, size = ButtonSize.Small, modifier = Modifier.testTag("persona-new"))
        }
        FooterNote(stringResource(R.string.persona_switch_note))
    }
}

@Composable
private fun PersonaEditor(
    initial: Persona?,
    onSave: (name: String, description: String, prompt: String) -> Boolean,
    onDelete: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var prompt by remember(initial) { mutableStateOf(initial?.prompt.orEmpty()) }
    var preview by remember(initial) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val nameRequired = stringResource(R.string.persona_name_required)
    val nameTooLong = stringResource(R.string.persona_name_too_long)
    val descriptionTooLong = stringResource(R.string.persona_description_too_long, PersonaStore.MAX_DESCRIPTION)
    val promptTooLong = stringResource(R.string.persona_prompt_too_long, PersonaPrompt.MAX_PROMPT_CHARS)
    val saveFailed = stringResource(R.string.persona_save_failed)
    val counter = stringResource(R.string.persona_counter, prompt.length, TokenEstimate.of(prompt))

    PageScaffold(stringResource(if (initial == null) R.string.persona_new else R.string.persona_edit), onBack) {
        GroupCard {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(R.string.persona_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("persona-name"),
                )
                OutlinedTextField(
                    description, { description = it },
                    label = { Text(stringResource(R.string.persona_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("persona-description"),
                )
                OutlinedTextField(
                    prompt, { prompt = it },
                    label = { Text(stringResource(R.string.persona_prompt)) },
                    placeholder = { Text(stringResource(R.string.persona_prompt_hint)) },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("persona-prompt"),
                )
                Text(counter, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ink3)
                if (prompt.isBlank()) Text(stringResource(R.string.persona_empty_prompt), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ink3)
                error?.let { Text(it, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.bad) }
            }
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MikaButton(
                stringResource(R.string.save),
                {
                    error = when {
                        name.trim().isEmpty() -> nameRequired
                        name.trim().length > PersonaPrompt.MAX_NAME -> nameTooLong
                        description.length > PersonaStore.MAX_DESCRIPTION -> descriptionTooLong
                        prompt.length > PersonaPrompt.MAX_PROMPT_CHARS -> promptTooLong
                        !onSave(name.trim(), description.trim(), prompt.trim()) -> saveFailed
                        else -> null
                    }
                    if (error == null) onDone()
                },
                size = ButtonSize.Medium, modifier = Modifier.weight(1f).testTag("persona-save"),
            )
            if (onDuplicate != null) {
                MikaButton(
                    stringResource(R.string.persona_copy), { onDuplicate(); onDone() },
                    kind = ButtonKind.Secondary, size = ButtonSize.Medium, modifier = Modifier.weight(1f),
                )
            }
        }

        MikaButton(
            stringResource(if (preview) R.string.persona_preview_hide else R.string.persona_preview),
            { preview = !preview }, kind = ButtonKind.Text, size = ButtonSize.Small,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (preview) {
            GroupCard(Modifier.padding(top = 4.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.persona_preview_note), style = MikaType.Hint, color = c.ink3)
                    PersonaPrompt.layer(name, prompt)?.let {
                        Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = c.ink2)
                    }
                }
            }
        }

        if (onDelete != null) {
            MikaButton(
                stringResource(R.string.confirm_delete), { confirmDelete = true },
                kind = ButtonKind.Text, size = ButtonSize.Small, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.persona_delete_title, initial.name)) },
            text = { Text(stringResource(R.string.persona_delete_body)) },
            confirmButton = {
                TextButton({ confirmDelete = false; onDelete?.invoke(); onDone() }) { Text(stringResource(R.string.confirm_delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
