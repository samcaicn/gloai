package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Manual controls only. The owning card supplies the immutable call identity and runtime callback. */
@Composable
internal fun CallDtmfKeypadDialog(
    callId: String,
    onDismiss: () -> Unit,
    onSend: suspend (String, String) -> CallActionResult,
    externallyBusy: Boolean = false,
) {
    val colors = MikaTheme.colors
    val scope = rememberCoroutineScope()
    var batch by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val blocked = sending || externallyBusy
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    val sendCallback by rememberUpdatedState(onSend)
    val failed = stringResource(R.string.calls_dtmf_failed)
    val sent = stringResource(R.string.calls_dtmf_sent)
    val invalid = stringResource(R.string.calls_dtmf_invalid)
    val batchLabel = stringResource(R.string.calls_dtmf_batch)

    fun send(digits: String) {
        if (blocked || digits.isEmpty()) return
        if (digits.length > 32 || digits.any { it !in "0123456789*#" }) {
            feedback = CallActionResult(false, invalid)
            return
        }
        sending = true
        feedback = null
        scope.launch {
            try {
                val result = sendCallback(callId, digits)
                // Successful transport is not a claim that the remote menu accepted the input.
                feedback = if (result.ok) CallActionResult(true, sent) else result
                if (result.ok && batch) input = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = CallActionResult(false, failed)
            } finally {
                sending = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MikaShapes.Card, color = colors.surface) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp)
                    .padding(16.dp).testTag("call-dtmf-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calls_dtmf_title), Modifier.weight(1f), style = MikaType.Title)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("call-dtmf-close")) {
                        Text(stringResource(R.string.calls_dtmf_close))
                    }
                }
                if (blocked || feedback != null) {
                    Text(
                        text = if (blocked) stringResource(R.string.calls_dtmf_sending) else feedback?.message.orEmpty(),
                        style = MikaType.Caption,
                        color = if (feedback?.ok == false) MaterialTheme.colorScheme.error else colors.ink2,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("call-dtmf-status"),
                    )
                }
                // Scroll only the keypad body at large font sizes; closing is always reachable.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("call-dtmf-body"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.calls_dtmf_batch), Modifier.weight(1f), style = MikaType.Body)
                        Switch(
                            checked = batch,
                            enabled = !blocked,
                            onCheckedChange = { batch = it; input = ""; feedback = null },
                            modifier = Modifier.semantics { contentDescription = batchLabel }.testTag("call-dtmf-batch"),
                        )
                    }
                    Text(
                        stringResource(if (batch) R.string.calls_dtmf_batch_hint else R.string.calls_dtmf_hint),
                        style = MikaType.Caption, color = colors.ink2,
                    )
                    if (batch) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { value ->
                                if (value.length <= 32 && value.all { it in "0123456789*#" }) {
                                    input = value
                                    feedback = null
                                } else feedback = CallActionResult(false, invalid)
                            },
                            enabled = !blocked,
                            label = { Text(stringResource(R.string.calls_dtmf_input)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth().testTag("call-dtmf-input"),
                        )
                    }
                    for (row in listOf("123", "456", "789", "*0#")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (digit in row) {
                                FilledTonalButton(
                                    onClick = {
                                        if (batch) {
                                            if (input.length < 32) { input += digit; feedback = null }
                                            else feedback = CallActionResult(false, invalid)
                                        } else send(digit.toString())
                                    },
                                    enabled = !blocked,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("call-dtmf-key-$digit"),
                                ) {
                                    Text(digit.toString(), style = MikaType.LargeTitle, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    if (batch) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { input = input.dropLast(1); feedback = null },
                                enabled = !blocked && input.isNotEmpty(),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("call-dtmf-delete"),
                            ) { Text(stringResource(R.string.calls_dtmf_delete)) }
                            Button(
                                onClick = { send(input) }, enabled = !blocked && input.isNotEmpty(),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("call-dtmf-send"),
                            ) { Text(stringResource(R.string.calls_dtmf_send)) }
                        }
                    }
                }
            }
        }
    }
}
