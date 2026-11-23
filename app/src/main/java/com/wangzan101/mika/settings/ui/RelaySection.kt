package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.wangzan101.mika.R
import com.wangzan101.mika.core.remote.RelayGateway
import com.wangzan101.mika.core.remote.RelayState
import com.wangzan101.mika.mcp.McpRuntime
import com.wangzan101.mika.settings.RelayConfig
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.mcp.ui.CommandText
import com.wangzan101.mika.memory.ui.Link

@Composable
internal fun RelaySection(runtime: McpRuntime, mcp: Boolean, onCopy: (String) -> Unit) {
    val config by runtime.relaySettings.config.collectAsState()
    val state by runtime.relayState.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun change(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    RelayControls(config, state, busy, failed,
        onPair = { url, code -> change { runtime.pairRelay(url, code) } },
        onEnabled = { on -> change { runtime.setRelayEnabled(on) } },
        onDeviceAccess = { on -> change { runtime.setRelayDeviceAccess(on) } },
        onUnpair = { change { runtime.unpairRelay() } },
        onCopyAddress = { config.gateway?.let { onCopy(RelayGateway.parse(it).newBuilder().encodedPath(if (mcp) "/mcp" else "/").build().toString()) } },
        onCopyConfig = { change {
            runtime.relaySettings.pairing()?.let { p ->
                val text = if (mcp) McpRuntime.clientConfig(RelayGateway.parse(p.gateway).newBuilder().encodedPath("/mcp").build().toString(), runtime.settings.token())
                else "mika connect --url ${p.gateway.removeSuffix("/")} --token-file /path/to/token"
                withContext(Dispatchers.Main) { onCopy(text) }
            }
        } }, mcp = mcp,
        onCopyToken = { change { val token = runtime.settings.token(); withContext(Dispatchers.Main) { onCopy(token) } } })
}

/** 纯展示与用户动作回调,测试不依赖真实网关。配对码不进可恢复状态;旧令牌不显示。 */
@Composable
internal fun RelayControls(config: RelayConfig, state: RelayState, busy: Boolean, failed: Boolean,
    onPair: (String, String) -> Unit, onEnabled: (Boolean) -> Unit, onUnpair: () -> Unit,
    onCopyAddress: () -> Unit, onCopyConfig: () -> Unit, mcp: Boolean = true, onCopyToken: () -> Unit = {},
    onDeviceAccess: (Boolean) -> Unit = {}) {
    var gateway by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    if (!config.paired) {
        GroupCard {
            ListRow(MikaIcons.Link, stringResource(R.string.relay_pair_title), stringResource(R.string.relay_pair_sub), first = true)
            InputRow(stringResource(R.string.relay_gateway), gateway, { if (!busy) gateway = it.take(2048) },
                "https://mika.example.com", keyboard = KeyboardType.Uri, modifier = Modifier.testTag("relay-gateway"))
            InputRow(stringResource(R.string.relay_code), code, { if (!busy) code = it.take(64) },
                stringResource(R.string.relay_code_hint), secret = true, keyboard = KeyboardType.Password, modifier = Modifier.testTag("relay-code"))
            RowActions {
                MikaButton(stringResource(if (busy) R.string.relay_busy else R.string.relay_pair), {
                    onPair(gateway.trim(), code.trim()); code = ""
                }, size = ButtonSize.Small, enabled = !busy && code.trim().length in 8..64 && runCatching { RelayGateway.parse(gateway) }.isSuccess,
                    modifier = Modifier.testTag("relay-pair"))
            }
        }
    } else {
        val status = when {
            !config.enabled -> R.string.relay_disabled
            state == RelayState.Online -> R.string.relay_online
            state == RelayState.Unauthorized -> R.string.relay_unauthorized
            state == RelayState.Incompatible -> R.string.relay_incompatible
            state == RelayState.Offline -> R.string.relay_offline
            else -> R.string.relay_connecting
        }
        GroupCard {
            ListRow(MikaIcons.Link, stringResource(R.string.relay_enable), stringResource(status), first = true) {
                MikaSwitch(config.enabled, onEnabled, Modifier.testTag("relay-enabled"), enabled = !busy)
            }
            ListRow(null, stringResource(R.string.relay_device_access), stringResource(R.string.relay_device_access_sub)) {
                MikaSwitch(config.deviceAccess, onDeviceAccess, Modifier.testTag("relay-device-access"), enabled = !busy)
            }
            ListRow(null, stringResource(R.string.relay_gateway), config.gateway)
            RowActions {
                MikaButton(stringResource(if (mcp) R.string.relay_copy_address else R.string.relay_copy_gateway), onCopyAddress, size = ButtonSize.Small, enabled = !busy)
                MikaButton(stringResource(if (mcp) R.string.mcp_copy_config else R.string.mcp_copy_command), onCopyConfig, kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = !busy)
            }
            if (!mcp) RowActions {
                MikaButton(stringResource(R.string.mcp_copy_token), onCopyToken, kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = !busy)
            }
            RowActions {
                MikaButton(stringResource(R.string.relay_unpair), { confirm = true }, kind = ButtonKind.Text, size = ButtonSize.Small, enabled = !busy)
            }
        }
        if (!mcp) CommandText("mika connect --url ${config.gateway!!.removeSuffix("/")} --token-file /path/to/token")
        FooterNote(stringResource(if (mcp) R.string.relay_client_help else R.string.relay_cli_help))
    }
    FooterNote(stringResource(R.string.relay_intro))
    if (failed) FooterNote(stringResource(R.string.relay_failed))
    if (confirm) MikaDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.relay_unpair)) }, text = { Text(stringResource(R.string.relay_unpair_body)) },
        confirmButton = { TextButton({ confirm = false; onUnpair() }) { Text(stringResource(R.string.relay_unpair)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
