package com.wangzan101.mika.calls.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.instructions.CallInstruction
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.memory.ui.Row

@Composable
internal fun CallInstructionsScreen(runtime: CallRuntime, onBack: () -> Unit) {
    val store = runtime.instructions
    val rules by store.items.collectAsState()
    val storageError by store.error.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<CallInstruction?>(null) }
    var deleting by remember { mutableStateOf<CallInstruction?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val savedLabel = stringResource(R.string.call_instructions_saved)
    val failedLabel = stringResource(R.string.call_instructions_failed)
    val deliveryTitle = stringResource(R.string.call_instructions_delivery)
    val deliveryWhen = stringResource(R.string.call_instructions_delivery_when)
    val deliveryReply = stringResource(R.string.call_instructions_delivery_reply)
    val businessTitle = stringResource(R.string.call_instructions_business)
    val businessWhen = stringResource(R.string.call_instructions_business_when)
    val businessReply = stringResource(R.string.call_instructions_business_reply)

    fun mutate(action: () -> Unit) {
        scope.launch {
            feedback = withContext(Dispatchers.IO) {
                runCatching { action(); savedLabel }.getOrElse { failedLabel }
            }
        }
    }
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    BackHandler { if (draft != null) draft = null else onBack() }

    PageScaffold(stringResource(R.string.call_instructions_title), onBack,
        Modifier.testTag("call-instructions").imePadding()) {
        FooterNote(stringResource(R.string.call_instructions_intro))
        MikaButton(stringResource(R.string.call_instructions_add), {
            draft = CallInstruction("", "", "", "")
        }, Modifier.fillMaxWidth().testTag("call-instruction-add"))
        if (rules.isEmpty()) {
            FooterNote(stringResource(R.string.call_instructions_empty))
            GroupLabel(stringResource(R.string.call_instructions_presets))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { draft = CallInstruction("", deliveryTitle, deliveryWhen, deliveryReply) }) { Text(deliveryTitle) }
                TextButton(onClick = { draft = CallInstruction("", businessTitle, businessWhen, businessReply) }) { Text(businessTitle) }
            }
        }
        rules.forEach { rule ->
            MikaCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(rule.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(rule.enabled, { value -> mutate { store.setEnabled(rule.id, value) } })
                }
                if (rule.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) {
                    Text(stringResource(R.string.call_instructions_expired), color = MaterialTheme.colorScheme.error)
                }
                Text(rule.situation, style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(rule.response) }
                Row {
                    TextButton(onClick = { draft = rule }) { Text(stringResource(R.string.call_instructions_edit)) }
                    TextButton(onClick = { deleting = rule }) { Text(stringResource(R.string.call_instructions_delete)) }
                }
            }
        }
        (storageError ?: feedback)?.let { FooterNote(it) }
    }
    draft?.let { row ->
        InstructionEditor(row, feedback.takeIf { it == failedLabel }, onDismiss = { draft = null }, onSave = { edited ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { store.save(edited) }.isSuccess }
                if (ok) { draft = null; feedback = savedLabel } else feedback = failedLabel
            }
        })
    }
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.call_instructions_delete)) },
        text = { Text(stringResource(R.string.call_instructions_delete_confirm)) },
        confirmButton = { TextButton(onClick = { val id = deleting!!.id; deleting = null; mutate { store.remove(id) } }) { Text(stringResource(R.string.call_instructions_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.call_instructions_cancel)) } })
}

@Composable
private fun InstructionEditor(row: CallInstruction, saveError: String?, onDismiss: () -> Unit, onSave: (CallInstruction) -> Unit) {
    var title by remember(row) { mutableStateOf(row.title) }
    var situation by remember(row) { mutableStateOf(row.situation) }
    var response by remember(row) { mutableStateOf(row.response) }
    var expiry by remember(row) { mutableStateOf(row.expiresAtMs) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (row.id.isBlank()) R.string.call_instructions_add else R.string.call_instructions_edit)) },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        text = {
            val titleFocus = remember { FocusRequester() }
            val window = LocalWindowInfo.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(row, window) {
                // A text field can take focus before the dialog owns the IME window.
                snapshotFlow { window.isWindowFocused }.first { it }
                titleFocus.requestFocus()
                withFrameNanos { }
                keyboard?.show()
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(64) }, label = { Text(stringResource(R.string.call_instructions_heading)) },
                    modifier = Modifier.focusRequester(titleFocus).testTag("call-instruction-title-input"), singleLine = true)
                OutlinedTextField(situation, { situation = it.take(200) }, label = { Text(stringResource(R.string.call_instructions_situation)) }, maxLines = 3)
                OutlinedTextField(response, { response = it }, label = { Text(stringResource(R.string.call_instructions_response)) },
                    supportingText = { Text(response.length.toString() + " / 800") }, isError = response.length > 800, minLines = 3, maxLines = 7)
                Text(stringResource(R.string.call_instructions_expiry), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(expiry == null, { expiry = null }, label = { Text(stringResource(R.string.call_instructions_forever)) })
                    FilterChip(expiry == LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), { expiry = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                        label = { Text(stringResource(R.string.call_instructions_today)) })
                }
                TextButton(onClick = { expiry = System.currentTimeMillis() + 2 * 60 * 60 * 1_000L }) {
                    Text(stringResource(R.string.call_instructions_two_hours))
                }
                expiry?.let { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)), style = MaterialTheme.typography.bodySmall) }
                if (response.length > 800) Text(stringResource(R.string.call_instructions_review_limit))
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(row.copy(title = title, situation = situation, response = response, expiresAtMs = expiry)) },
            enabled = title.isNotBlank() && situation.isNotBlank() && response.isNotBlank() && response.length <= 800) {
            Text(stringResource(R.string.call_instructions_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_instructions_cancel)) } })
}
