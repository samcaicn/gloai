package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.IncomingScope
import com.wangzan101.mika.calls.IncomingCallType
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun CallNumberRuleEditor(config: CallConfig, personal: Boolean, onDismiss: () -> Unit,
    onSave: suspend (IncomingScope, Set<String>, Set<IncomingCallType>, Int) -> Boolean) {
    var text by rememberSaveable {
        val initial = if (personal) config.bypassIncomingNumbers else config.allowedIncomingNumbers
        mutableStateOf(initial.sorted().joinToString("\n"))
    }
    val draft = remember(text) { CallNumberDraft(text) }
    var mode by rememberSaveable { mutableStateOf(config.incomingScope) }
    var types by rememberSaveable { mutableStateOf(config.allowedCallTypes) }
    val min = CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS
    val max = CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS
    var seconds by rememberSaveable { mutableIntStateOf(config.autoAnswerDelaySeconds.coerceIn(min, max)) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = stringResource(if (personal) R.string.calls_bypass_title else R.string.calls_rule_scope)
    val showNumbers = personal || mode == IncomingScope.LIST
    val showTypes = !personal && mode == IncomingScope.TYPES
    val strangers = !personal && mode == IncomingScope.STRANGERS
    val close = { if (!busy) onDismiss() }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val contacts = if (showNumbers && !manual) rememberCallContacts() else null
        val permission = if (strangers) rememberCallContacts(loadRows = false) else null
        val visible = remember(contacts?.rows, query) {
            val term = query.trim()
            val digits = term.filter(Char::isDigit)
            contacts?.rows.orEmpty().filter {
                it.name.contains(term, ignoreCase = true) || it.number.contains(term) ||
                    (digits.isNotEmpty() && term.all { c -> c.isDigit() || c in "+ ().-" } && it.key.contains(digits))
            }
        }
        PageBackground {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                TopBar(title, MikaIcons.Back, close, stringResource(R.string.calls_cancel), right = {
                    TextButton(enabled = !busy && draft.valid && (!showTypes || types.isNotEmpty()) && permission?.actionLabel == null, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                if (onSave(mode, draft.selectedNumbers, types, seconds)) onDismiss() else saveFailed = true
                            } finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.calls_save)) }
                })
                if (saveFailed) FooterNote(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
                LazyColumn(contentPadding = PaddingValues(horizontal = MikaSpace.Page, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (personal) item { FooterNote(stringResource(R.string.calls_bypass_note)) }
                    else item {
                        GroupCard {
                            RadioRow(stringResource(R.string.calls_only_list), mode == IncomingScope.LIST, { mode = IncomingScope.LIST }, first = true, enabled = !busy)
                            RadioRow(stringResource(R.string.calls_all_incoming), mode == IncomingScope.ALL, { mode = IncomingScope.ALL },
                                subtitle = stringResource(R.string.calls_all_note), enabled = !busy && draft.valid)
                            RadioRow(stringResource(R.string.calls_only_strangers), strangers,
                                { mode = IncomingScope.STRANGERS }, enabled = !busy && draft.valid,
                                subtitle = if (strangers) stringResource(R.string.calls_strangers_note) else null)
                            if (strangers) permission?.actionLabel?.let { label ->
                                TextButton(onClick = permission.action, enabled = !busy,
                                    modifier = Modifier.padding(start = 50.dp)) { Text(stringResource(label)) }
                            }
                            RadioRow(stringResource(R.string.calls_only_types), showTypes,
                                { mode = IncomingScope.TYPES }, enabled = !busy && draft.valid,
                                subtitle = if (showTypes) stringResource(R.string.calls_type_note) else null)
                        }
                    }
                    if (showTypes) item {
                        val service by ScreenAccessibility.instance.collectAsState()
                        val context = LocalContext.current
                        if (service == null) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text(stringResource(R.string.calls_type_accessibility)) }
                        GroupCard {
                            for (type in IncomingCallType.entries) {
                                val checked = type in types
                                Row(Modifier.fillMaxWidth().toggleable(checked, !busy, Role.Checkbox) {
                                    types = if (it) types + type else types - type
                                }.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(type.title), Modifier.weight(1f), style = MikaType.Body)
                                    Checkbox(checked, onCheckedChange = null, enabled = !busy)
                                }
                            }
                        }
                    }
                    if (!personal) item {
                        val delayLabel = stringResource(R.string.calls_rule_delay_summary, seconds)
                        GroupCard {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(delayLabel, style = MikaType.Body)
                                Slider(value = seconds.toFloat(), onValueChange = { seconds = it.roundToInt() },
                                    valueRange = min.toFloat()..max.toFloat(), steps = max - min - 1, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("call-answer-delay")
                                        .semantics { contentDescription = delayLabel })
                            }
                        }
                    }
                    if (showNumbers) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(if (personal) R.string.calls_number else R.string.calls_allowlist),
                                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.drawer_selected_count, draft.keys.size) + " / " + CallPolicy.MAX_ALLOWED_NUMBERS,
                                    style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                            }
                        }
                        item {
                            SegmentedControl(listOf(stringResource(R.string.calls_contacts_tab), stringResource(R.string.calls_manual_tab)),
                                if (manual) 1 else 0, { if (!busy) manual = it == 1 },
                                tags = listOf("call-contacts-tab", "call-manual-tab"))
                        }
                        if (!draft.valid) item {
                            FooterNote(stringResource(R.string.calls_allowlist_invalid, CallPolicy.MAX_ALLOWED_NUMBERS),
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (manual) item {
                            OutlinedTextField(text, { text = it; saveFailed = false },
                                Modifier.fillMaxWidth().testTag(if (personal) "call-bypass-numbers" else "call-allowlist"),
                                label = { Text(stringResource(R.string.calls_number)) },
                                supportingText = { Text(stringResource(R.string.calls_allowlist_hint)) },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                                isError = !draft.valid, minLines = 3, maxLines = 8, enabled = !busy)
                        }
                        if (contacts != null) {
                            if (contacts.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            contacts.message?.let { message -> item {
                                GroupCard {
                                    Column(Modifier.padding(start = MikaSpace.S16, end = MikaSpace.S16,
                                        top = MikaSpace.S8, bottom = MikaSpace.S16)) {
                                        contacts.actionLabel?.let { label ->
                                            TextButton(onClick = contacts.action, enabled = !busy,
                                                contentPadding = PaddingValues(horizontal = 0.dp)) {
                                                Text(stringResource(label))
                                            }
                                        }
                                        Text(stringResource(message), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                    }
                                }
                            } }
                            if (contacts.rows.isNotEmpty()) item {
                                SearchField(query, { query = it }, stringResource(R.string.calls_contacts_search))
                            }
                            if (contacts.rows.isNotEmpty() && visible.isEmpty()) item {
                                EmptyNote(stringResource(R.string.browser_find_no_matches))
                            }
                            items(visible, key = { "${it.id}/${it.key}" }) { contact ->
                                val checked = contact.key in draft.keys
                                val enabled = !busy && (checked || draft.keys.size < CallPolicy.MAX_ALLOWED_NUMBERS)
                                GroupCard {
                                    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Checkbox) {
                                        text = draft.toggle(contact.number)
                                        saveFailed = false
                                    }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            if (contact.name.isNotBlank() && contact.name != contact.number)
                                                Text(contact.name, style = MaterialTheme.typography.bodyMedium)
                                            Text("\u2066${contact.number}\u2069", style = MaterialTheme.typography.bodyMedium,
                                                color = MikaTheme.colors.ink2)
                                        }
                                        Checkbox(checked, onCheckedChange = null, enabled = enabled)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
