package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RelayState
import com.wangzan101.mika.mcp.McpRuntime
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.TileTone
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 展示现有连接状态,不把启用开关或监听端口当作电脑已连接。刷新仍只在本页,沿用两秒间隔。 */
@Composable
fun McpScreen(runtime: McpRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by runtime.settings.enabled.collectAsState()
    val usbOn by runtime.usb.enabled.collectAsState()
    val relayConfig by runtime.relaySettings.config.collectAsState()
    val relayState by runtime.relayState.collectAsState()
    val usbCommand = remember { runtime.usbCommand() }
    val state by runtime.state.collectAsState()
    var addresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var activity by remember { mutableStateOf(RemoteActivity()) }
    var token by remember { mutableStateOf<String?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, usbOn, relayConfig.enabled, relayConfig.paired) {
        token = if (enabled || relayConfig.paired) withContext(Dispatchers.IO) { runCatching { runtime.settings.token() }.getOrNull() } else null
        if (!enabled) addresses = emptyList()
        if (!enabled && !usbOn && !relayConfig.enabled) { activity = RemoteActivity(); return@LaunchedEffect }
        while (true) {
            if (enabled) addresses = withContext(Dispatchers.IO) { McpRuntime.addresses() }
            activity = runtime.activity
            delay(2_000)
        }
    }
    ComputerAccessPage(
        ComputerAccessUi(state, enabled, usbOn, (state as? McpServerState.Listening)?.port ?: runtime.settings.port,
            activity, addresses, token, usbCommand, relayConfig.enabled, relayState),
        onBack, runtime::setUsbEnabled, runtime::setEnabled,
        onPortChange = runtime::setPort,
        onCopy = { copy(context, it) }, onRegenerate = { confirmRegenerate = true },
        deviceSettings = {
            val historyEnabled by runtime.settings.historyEnabled.collectAsState()
            GroupCard {
                ListRow(MikaIcons.Desktop, stringResource(R.string.mcp_history_title), stringResource(R.string.mcp_history_detail),
                    first = true, onClick = { runtime.settings.setHistoryEnabled(!historyEnabled) }) {
                    MikaSwitch(historyEnabled, runtime.settings::setHistoryEnabled, Modifier.testTag("computer-history"))
                }
            }
            DeviceAccessSection(runtime.device.settings)
        },
        relayGuide = { mcp -> RelaySection(runtime, mcp) { copy(context, it) } },
    )
    if (confirmRegenerate) MikaDialog(
        onDismissRequest = { confirmRegenerate = false },
        title = { Text(stringResource(R.string.mcp_regenerate_title)) },
        text = { Text(stringResource(R.string.mcp_regenerate_body)) },
        confirmButton = { TextButton({ confirmRegenerate = false; token = runtime.regenerateToken() }) { Text(stringResource(R.string.mcp_regenerate_confirm)) } },
        dismissButton = { TextButton({ confirmRegenerate = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 一份只读页面快照。引导选择和折叠状态不写入连接设置。 */
internal data class ComputerAccessUi(
    val server: McpServerState,
    val networkEnabled: Boolean,
    val usbEnabled: Boolean,
    val port: Int,
    val activity: RemoteActivity,
    val addresses: List<String>,
    val token: String?,
    val usbCommand: String,
    val relayEnabled: Boolean = false,
    val relayState: RelayState = RelayState.Off,
)

@Composable
internal fun ComputerAccessPage(
    ui: ComputerAccessUi,
    onBack: () -> Unit,
    onUsbEnabled: (Boolean) -> Unit,
    onNetworkEnabled: (Boolean) -> Unit,
    onPortChange: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    deviceSettings: @Composable () -> Unit,
    relayGuide: (@Composable (Boolean) -> Unit)? = null,
) {
    var client by remember { mutableStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    var editingPort by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf(0) }
    PageScaffold(stringResource(R.string.mcp_title), onBack) {
        ConnectionStatus(ui)
        GroupLabel(stringResource(R.string.mcp_choose_client))
        SegmentedControl(listOf("CLI / Skill", "MCP"), client, { client = it },
            Modifier.padding(bottom = 12.dp), tags = listOf("computer-cli", "computer-mcp"))
        GroupLabel(stringResource(R.string.mcp_choose_transport))
        val remoteChoice = relayGuide != null
        SegmentedControl(listOf(stringResource(R.string.mcp_transport_usb), stringResource(R.string.mcp_transport_network)) +
            if (remoteChoice) listOf(stringResource(R.string.relay_transport)) else emptyList(),
            transport, { transport = it }, Modifier.padding(bottom = 12.dp),
            tags = listOf("computer-usb", "computer-network") + if (remoteChoice) listOf("computer-remote") else emptyList())
        if (client == 0) CliInstallGuide(onCopy)
        when {
            transport == 2 -> relayGuide?.invoke(client == 1)
            transport == 1 -> NetworkGuide(ui, mcp = client == 1, onNetworkEnabled, onCopy)
            client == 0 -> GuideStep(stringResource(R.string.mcp_cli_connect), stringResource(R.string.mcp_cli_connect_sub),
                stringResource(R.string.mcp_copy_command), { onCopy("mika connect") }, command = "mika connect")
            else -> McpUsbGuide(ui, onCopy)
        }

        GroupCard {
            ListRow(MikaIcons.Gear, stringResource(if (advanced) R.string.mcp_advanced_hide else R.string.mcp_advanced),
                stringResource(R.string.mcp_advanced_sub), first = true, onClick = { advanced = !advanced })
        }
        if (advanced) {
            GroupCard {
                ListRow(MikaIcons.Link, stringResource(R.string.mcp_usb_title),
                    stringResource(if (ui.usbEnabled) R.string.mcp_usb_on else R.string.mcp_usb_off_sub), first = true,
                    onClick = { onUsbEnabled(!ui.usbEnabled) }) {
                    MikaSwitch(ui.usbEnabled, onUsbEnabled, Modifier.testTag("mcp-usb"))
                }
                ListRow(MikaIcons.Desktop, stringResource(R.string.mcp_network_title), stringResource(R.string.mcp_network_scope),
                    onClick = { onNetworkEnabled(!ui.networkEnabled) }) {
                    MikaSwitch(ui.networkEnabled, onNetworkEnabled, Modifier.testTag("mcp-enabled"))
                }
                ListRow(
                    MikaIcons.Link, stringResource(R.string.mcp_port_title), ui.port.toString(),
                    // 端口被占时换一个;改动即重绑并按新端口重写电脑上的连接脚本
                    onClick = { editingPort = true },
                )
            }
            FooterNote(stringResource(R.string.mcp_port_detail, ui.port))
            if (editingPort) PortDialog(current = ui.port, onPick = { onPortChange(it); editingPort = false }, onDismiss = { editingPort = false })
            if (ui.networkEnabled || ui.relayEnabled) {
                GroupLabel(stringResource(R.string.mcp_token))
                GroupCard {
                    ListRow(MikaIcons.Key, ui.token?.let { it.take(6) + "…" + it.takeLast(4) } ?: "…", first = true)
                    RowActions {
                        MikaButton(stringResource(R.string.mcp_copy), { ui.token?.let(onCopy) }, size = ButtonSize.Small, enabled = ui.token != null)
                        MikaButton(stringResource(R.string.mcp_regenerate), onRegenerate, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                    }
                }
            }
            deviceSettings()
        }
    }
}

/** 监听端口的编辑:默认 8765 一个快捷项,别的口在输入框里填(1024–65535)。 */
@Composable
private fun PortDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(if (current != McpSettings.DEFAULT_PORT) current.toString() else "") }
    val value = custom.toIntOrNull()?.takeIf { it in McpSettings.PORT_RANGE }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_port_title)) },
        text = {
            Column {
                Text(stringResource(R.string.mcp_port_dialog_sub), Modifier.padding(bottom = 8.dp))
                RadioRow(stringResource(R.string.mcp_port_default, McpSettings.DEFAULT_PORT), selected = current == McpSettings.DEFAULT_PORT,
                    onClick = { onPick(McpSettings.DEFAULT_PORT) }, first = true)
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mcp_port_custom)) },
                    isError = custom.isNotEmpty() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (custom.isNotEmpty() && value == null) {
                    Text(stringResource(R.string.mcp_port_invalid), Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton({ value?.let(onPick) }, enabled = value != null) { Text(stringResource(R.string.mcp_port_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConnectionStatus(ui: ComputerAccessUi) {
    val enabled = ui.networkEnabled || ui.usbEnabled || ui.relayEnabled
    val reachable = ui.server is McpServerState.Listening || ui.relayState == RelayState.Online
    val received = enabled && reachable && ui.activity.lastRequestAtMs != null
    val onlyRelay = ui.relayEnabled && !ui.networkEnabled && !ui.usbEnabled
    val failure = (ui.server as? McpServerState.Failed)?.takeIf { !onlyRelay || ui.relayState == RelayState.Off }
    val title = when {
        !enabled -> stringResource(R.string.mcp_access_off)
        failure != null -> stringResource(R.string.mcp_failed, failure.message)
        onlyRelay && ui.relayState == RelayState.Incompatible -> stringResource(R.string.relay_incompatible)
        onlyRelay && ui.relayState == RelayState.Unauthorized -> stringResource(R.string.relay_unauthorized)
        onlyRelay && ui.relayState == RelayState.Offline -> stringResource(R.string.relay_offline)
        onlyRelay && ui.relayState != RelayState.Online -> stringResource(R.string.relay_connecting)
        ui.server is McpServerState.Off && !onlyRelay -> stringResource(R.string.mcp_starting)
        received -> stringResource(R.string.mcp_request_received)
        else -> stringResource(R.string.mcp_waiting)
    }
    val detail = if (received) stringResource(R.string.mcp_last_request, clock(ui.activity.lastRequestAtMs!!))
        else stringResource(R.string.mcp_follow_steps)
    val tone = when { failure != null && enabled -> TileTone.Bad; received -> TileTone.Ok; else -> TileTone.Neutral }
    GroupCard(Modifier.testTag("computer-status")) {
        ListRow(if (received) MikaIcons.Check else MikaIcons.Desktop, title, detail, first = true, tone = tone)
    }
}

@Composable
private fun CliInstallGuide(onCopy: (String) -> Unit) {
    val install = stringResource(R.string.mcp_cli_install_prompt)
    GuideStep(stringResource(R.string.mcp_cli_install), stringResource(R.string.mcp_cli_install_sub),
        stringResource(R.string.mcp_copy_install), { onCopy(install) })
}

@Composable
private fun McpUsbGuide(ui: ComputerAccessUi, onCopy: (String) -> Unit) {
    GuideStep(stringResource(R.string.mcp_usb_start), stringResource(R.string.mcp_usb_start_sub),
        stringResource(R.string.mcp_copy_command), { onCopy(ui.usbCommand) }, command = ui.usbCommand)
    val forward = McpUsb.forward(ui.port)
    GuideStep(stringResource(R.string.mcp_usb_forward), stringResource(R.string.mcp_usb_forward_sub),
        stringResource(R.string.mcp_copy_command), { onCopy(forward) }, command = forward)
    val instructions = stringResource(R.string.mcp_usb_client_instructions, ui.port)
    GuideStep(stringResource(R.string.mcp_usb_client), stringResource(R.string.mcp_usb_client_sub),
        stringResource(R.string.mcp_copy_instructions), { onCopy(instructions) })
}

@Composable
private fun NetworkGuide(ui: ComputerAccessUi, mcp: Boolean, onNetworkEnabled: (Boolean) -> Unit, onCopy: (String) -> Unit) {
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    if (!ui.networkEnabled) {
        GuideStep(stringResource(R.string.mcp_network_enable), stringResource(R.string.mcp_network_scope),
            stringResource(R.string.mcp_network_enable_action), { onNetworkEnabled(true) })
        return
    }
    val address = selectedAddress?.takeIf { it in ui.addresses } ?: ui.addresses.firstOrNull()
    GroupLabel(stringResource(if (mcp) R.string.mcp_network_address_step else R.string.mcp_address))
    GroupCard {
        if (ui.addresses.isEmpty()) ListRow(MikaIcons.Link, stringResource(R.string.mcp_no_address), first = true)
        ui.addresses.forEachIndexed { i, a ->
            RadioRow(if (mcp) McpRuntime.url(a, ui.port) else "http://$a:${ui.port}",
                a == address, { selectedAddress = a }, first = i == 0)
        }
    }
    FooterNote(stringResource(R.string.mcp_address_foot))
    if (!mcp) {
        val command = address?.let { "mika connect --url http://$it:${ui.port} --token-file /path/to/token" }
        GroupCard {
            ListRow(MikaIcons.Code, stringResource(R.string.mcp_cli_connect), stringResource(R.string.mcp_cli_network_sub), first = true)
            command?.let { CommandText(it) }
            RowActions {
                MikaButton(stringResource(R.string.mcp_copy_command), { command?.let(onCopy) }, size = ButtonSize.Small,
                    enabled = command != null && ui.token != null && ui.server is McpServerState.Listening,
                    modifier = Modifier.testTag("computer-copy-cli-network"))
                MikaButton(stringResource(R.string.mcp_copy_token), { ui.token?.let(onCopy) }, kind = ButtonKind.Tonal,
                    size = ButtonSize.Small, enabled = ui.token != null)
            }
        }
        FooterNote(stringResource(R.string.mcp_cli_connection_check))
        return
    }
    GroupCard {
        ListRow(MikaIcons.Code, stringResource(R.string.mcp_network_config_step), stringResource(R.string.mcp_network_config_sub), first = true)
        val canCopy = address != null && ui.token != null && ui.server is McpServerState.Listening
        RowActions {
            MikaButton(stringResource(R.string.mcp_copy_config), {
                if (canCopy) onCopy(McpRuntime.clientConfig(McpRuntime.url(address!!, ui.port), ui.token!!))
            }, size = ButtonSize.Small, enabled = canCopy, modifier = Modifier.testTag("computer-copy-config"))
            MikaButton(stringResource(if (preview) R.string.mcp_hide_preview else R.string.mcp_show_preview), { preview = !preview },
                kind = ButtonKind.Text, size = ButtonSize.Small)
        }
        if (preview) {
            // 预览只用占位符;真实令牌只在用户点击复制时进入剪贴板。
            val config = McpRuntime.clientConfig(address?.let { McpRuntime.url(it, ui.port) } ?: "<address>", "<token>")
            CommandText(config, Modifier.testTag("computer-config-preview"))
        }
    }
    FooterNote(stringResource(R.string.mcp_network_verify))
}

@Composable
private fun GuideStep(title: String, body: String, button: String, onClick: () -> Unit, command: String? = null) {
    GroupCard {
        ListRow(null, title, body, first = true)
        if (command != null) CommandText(command)
        RowActions { MikaButton(button, onClick, size = ButtonSize.Small) }
    }
}

@Composable
internal fun CommandText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        fontFamily = FontFamily.Monospace, style = MikaType.Body, color = MikaTheme.colors.ink)
}

private fun clock(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mika", text))
    Toast.makeText(context, context.getString(R.string.mcp_copied), Toast.LENGTH_SHORT).show()
}
