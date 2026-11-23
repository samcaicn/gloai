package com.wangzan101.mika.task.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.chat.ui.draft

/** Filling a task is local draft editing. The stored template and execution service stay untouched. */
@Composable
internal fun TaskFillDialog(title: String, prompt: String, onDismiss: () -> Unit, onFill: (String) -> Unit) {
    val fields = remember(prompt) { TaskTemplate.fields(prompt) }
    val visibleFields = fields.take(TaskTemplate.MAX_FIELDS)
    var values by rememberSaveable(prompt) { mutableStateOf(List(visibleFields.size) { "" }) }
    val complete = fields.size <= TaskTemplate.MAX_FIELDS && values.all { it.isNotBlank() }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                visibleFields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        values[index], { text -> values = values.toMutableList().also { it[index] = text } },
                        label = { Text(field) }, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 8.dp)
                            .testTag("task-fill-$index"),
                    )
                }
                if (fields.size > TaskTemplate.MAX_FIELDS) Text(
                    stringResource(R.string.task_fields_too_many, TaskTemplate.MAX_FIELDS), color = MikaTheme.colors.bad,
                )
            }
        },
        confirmButton = {
            TextButton({
                TaskTemplate.render(prompt, visibleFields.zip(values).toMap())?.let(onFill)
            }, enabled = complete, modifier = Modifier.testTag("task-fill-apply")) {
                Text(stringResource(R.string.task_fill_apply))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
