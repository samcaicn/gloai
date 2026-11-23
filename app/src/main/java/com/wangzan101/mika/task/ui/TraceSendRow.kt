package com.wangzan101.mika.task.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.wangzan101.mika.R
import com.wangzan101.mika.core.run.trace.TraceArchive
import com.wangzan101.mika.record.OtlpReceipt
import com.wangzan101.mika.record.OtlpSender
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keeps the in-flight request and its receipt for this run across activity recreation. */
internal class TraceSendState : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var receipt by mutableStateOf<OtlpReceipt?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    fun send(archive: TraceArchive, address: String, name: String, value: String) {
        if (busy) return
        busy = true
        receipt = null
        failed = false
        viewModelScope.launch {
            try { receipt = OtlpSender.send(archive, address, name, value) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
}

@Composable
internal fun TraceSendRow(archive: TraceArchive) {
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val sender = remember(owner, archive.runId) {
        ViewModelProvider(owner)["otlp-send:${archive.runId}", TraceSendState::class.java]
    }
    var dialog by remember(archive.runId) { mutableStateOf(false) }
    ListRow(null, stringResource(R.string.trace_send), stringResource(R.string.trace_network_hint),
        onClick = if (sender.busy) null else ({ dialog = true })) { RowChevron() }
    val message = when (val r = sender.receipt) {
        OtlpReceipt.Accepted -> stringResource(R.string.trace_sent)
        OtlpReceipt.AcceptedWithWarning -> stringResource(R.string.trace_sent_warning)
        is OtlpReceipt.Rejected -> stringResource(R.string.trace_rejected, r.spans)
        is OtlpReceipt.HttpError -> stringResource(R.string.trace_http_error, r.code)
        null -> if (sender.failed) stringResource(R.string.trace_send_failed) else null
    }
    if (message != null) Text(message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MikaType.Hint)
    if (dialog) {
        // Credentials live only in this dialog and the single user-initiated request.
        var address by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("Authorization") }
        var value by remember { mutableStateOf("") }
        val validAddress = OtlpSender.endpoint(address) != null
        val validHeader = OtlpSender.validHeader(name, value)
        MikaDialog(onDismissRequest = { dialog = false }, title = { Text(stringResource(R.string.trace_send)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(address, { address = it }, Modifier.testTag("trace-endpoint"), singleLine = true, isError = address.isNotBlank() && !validAddress,
                        label = { Text(stringResource(R.string.trace_endpoint)) },
                        supportingText = { Text(stringResource(R.string.trace_endpoint_hint)) })
                    OutlinedTextField(name, { name = it }, Modifier.testTag("trace-auth-name"), singleLine = true, isError = !validHeader,
                        label = { Text(stringResource(R.string.trace_header_name)) })
                    OutlinedTextField(value, { value = it }, Modifier.testTag("trace-auth-value"), singleLine = true, isError = !validHeader,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        label = { Text(stringResource(R.string.trace_header_value)) })
                }
            },
            confirmButton = { TextButton(enabled = validAddress && validHeader, onClick = {
                dialog = false
                sender.send(archive, address, name, value)
            }) { Text(stringResource(R.string.trace_send)) } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
