package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch

private enum class McpRoute { List, Catalog, Connect }

/** 设置 › MCP 服务器:连接外部 MCP server(outbound),和「连接电脑」的 inbound 设置分开。 */
@Composable
fun McpServersScreen(store: McpServerStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connector = remember { McpConnectorState(store, store.catalog, scope) }
    var route by remember { mutableStateOf(McpRoute.List) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }

    when (route) {
        McpRoute.List -> McpServerList(
            store = store,
            onBrowseCatalog = { route = McpRoute.Catalog },
            onCustomServer = { adding = true },
            onEdit = { editing = it },
            onBack = onBack,
        )
        McpRoute.Catalog -> McpCatalogScreen(
            catalog = store.catalog,
            onOpenPreset = { connector.openPreset(it); route = McpRoute.Connect },
            onCustomServer = { adding = true },
            onBack = { route = McpRoute.List },
        )
        McpRoute.Connect -> McpConnectScreen(
            connector = connector,
            onDone = { connector.close(); route = McpRoute.List },
            onBack = { connector.cancel(); route = McpRoute.Catalog },
        )
    }

    if (adding || editing != null) {
        McpServerDialog(
            store = store,
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onChanged = { adding = false; editing = null },
        )
    }
}

@Composable
private fun McpServerList(
    store: McpServerStore,
    onBrowseCatalog: () -> Unit,
    onCustomServer: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onBack: () -> Unit,
) {
    val servers by store.servers.collectAsState()
    val health by store.health.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PageScaffold(stringResource(R.string.mcp_servers_title), onBack) {
        if (servers.isEmpty()) {
            GroupCard {
                ListRow(MikaIcons.Link, stringResource(R.string.mcp_servers_empty), stringResource(R.string.mcp_servers_empty_sub), first = true)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MikaButton(stringResource(R.string.mcp_catalog_browse), onBrowseCatalog, Modifier.weight(1f), size = ButtonSize.Medium)
                MikaButton(
                    stringResource(R.string.mcp_catalog_custom_server), onCustomServer, Modifier.weight(1f),
                    kind = ButtonKind.Secondary, size = ButtonSize.Medium,
                )
            }
        } else {
            GroupCard {
                servers.forEachIndexed { i, server ->
                    val state = health[server.id]
                    val address = if (server.transport == "stdio") server.command else server.url
                    ListRow(
                        MikaIcons.Link, server.name,
                        subtitle = (state?.let {
                            if (it.ok) stringResource(R.string.mcp_servers_status_ok, it.tools.size)
                            else stringResource(R.string.mcp_servers_status_fail, it.message.orEmpty())
                        } ?: address),
                        detail = state?.let { address },
                        first = i == 0,
                        onClick = { onEdit(server) },
                    ) {
                        MikaSwitch(server.enabled, { on -> store.setEnabled(server.id, on) })
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MikaButton(stringResource(R.string.mcp_servers_add), onBrowseCatalog, size = ButtonSize.Small)
                MikaButton(
                    stringResource(if (refreshing) R.string.mcp_servers_testing else R.string.mcp_servers_refresh_all),
                    {
                        refreshing = true
                        scope.launch {
                            store.testAll()
                            refreshing = false
                        }
                    },
                    size = ButtonSize.Small,
                    enabled = !refreshing && servers.isNotEmpty(),
                )
            }
        }
        GroupLabel(stringResource(R.string.mcp_servers_help))
        Text(stringResource(R.string.mcp_servers_help_text), Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun McpServerDialog(
    store: McpServerStore,
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var allowLocal by remember { mutableStateOf(initial?.allowLocalHttp == true) }
    var trustAnnotations by remember { mutableStateOf(initial?.trust == "trust-annotations") }
    var oauth by remember { mutableStateOf(initial?.auth == "oauth") }
    var transport by remember { mutableStateOf(initial?.transport ?: "streamable-http") }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var allowlist by remember { mutableStateOf(initial?.allowedTools?.joinToString(", ").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf(initial?.id) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signInLabel = stringResource(R.string.mcp_servers_sign_in)
    val signInOk = stringResource(R.string.mcp_servers_sign_in_ok)
    val transportIds = listOf("streamable-http", "http-sse", "stdio")
    val transportLabels = listOf(
        stringResource(R.string.mcp_servers_transport_http),
        stringResource(R.string.mcp_servers_transport_sse),
        stringResource(R.string.mcp_servers_transport_stdio),
    )

    fun config() = McpServerConfig(
        id = savedId ?: initial?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.trim(),
        url = if (transport == "stdio") "" else url.trim(),
        allowLocalHttp = allowLocal,
        trust = if (trustAnnotations) "trust-annotations" else "restricted",
        auth = if (oauth && transport != "stdio") "oauth" else "token",
        transport = transport,
        command = if (transport == "stdio") command.trim() else "",
        allowedTools = allowlist.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() },
    )

    fun save(): McpServerConfig? {
        val cfg = config()
        val result = if (savedId == null) {
            store.add(cfg, token.ifBlank { null }).onSuccess { savedId = it.id }
        } else {
            store.update(cfg).also {
                if (it.isSuccess && token.isNotBlank()) store.setToken(cfg.id, token)
                if (it.isSuccess && !oauth && initial?.auth == "oauth") store.clearOAuth(cfg.id)
            }
        }
        result.exceptionOrNull()?.let { error = it.message; return null }
        error = null
        return cfg
    }

    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.mcp_servers_add else R.string.mcp_servers_edit)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.mcp_servers_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SegmentedControl(
                    options = transportLabels,
                    selected = transportIds.indexOf(transport).coerceAtLeast(0),
                    onSelect = { transport = transportIds[it] },
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (transport == "stdio") {
                    OutlinedTextField(
                        command, { command = it },
                        label = { Text(stringResource(R.string.mcp_servers_command)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(stringResource(R.string.mcp_servers_stdio_help), Modifier.padding(top = 4.dp))
                } else {
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.mcp_servers_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (!oauth) {
                        OutlinedTextField(
                            token, { token = it }, label = { Text(stringResource(R.string.mcp_servers_token)) },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    allowlist, { allowlist = it },
                    label = { Text(stringResource(R.string.mcp_servers_allowlist)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (transport != "stdio") ToggleRow(stringResource(R.string.mcp_servers_use_oauth), oauth) { oauth = it }
                if (oauth && transport != "stdio") {
                    TextButton({
                        val saved = save() ?: return@TextButton
                        signingIn = true
                        scope.launch {
                            val result = store.authorize(saved) { link ->
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            signingIn = false
                            result.fold(
                                onSuccess = { error = signInOk },
                                onFailure = {
                                    error = context.getString(
                                        R.string.mcp_servers_sign_in_failed,
                                        it.message ?: it.javaClass.simpleName,
                                    )
                                },
                            )
                        }
                    }) { Text(signInLabel) }
                }
                if (transport != "stdio") ToggleRow(stringResource(R.string.mcp_servers_allow_local_http), allowLocal) { allowLocal = it }
                ToggleRow(stringResource(R.string.mcp_servers_trust_annotations), trustAnnotations) { trustAnnotations = it }
                error?.let { Text(it, Modifier.padding(top = 6.dp)) }
                if (testing) Text(stringResource(R.string.mcp_servers_testing), Modifier.padding(top = 6.dp))
                if (signingIn) Text(stringResource(R.string.mcp_servers_signing_in), Modifier.padding(top = 6.dp))
                if (initial != null) TextButton({ store.remove(initial.id); onChanged() }) { Text(stringResource(R.string.mcp_servers_delete)) }
            }
        },
        confirmButton = {
            TextButton({
                if (save() != null) onChanged()
            }) { Text(stringResource(R.string.mcp_servers_save)) }
        },
        dismissButton = {
            TextButton({
                val saved = save() ?: return@TextButton
                testing = true
                scope.launch {
                    val result = store.test(saved)
                    testing = false
                    result.fold(
                        onSuccess = { error = "OK: ${it.serverName ?: ""} · ${it.tools.size}" },
                        onFailure = { error = it.message ?: it.javaClass.simpleName },
                    )
                }
            }) { Text(stringResource(R.string.mcp_servers_save_test)) }
        },
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Checkbox(checked, onCheckedChange = onChecked)
        Text(title, Modifier.padding(start = 4.dp))
    }
}
