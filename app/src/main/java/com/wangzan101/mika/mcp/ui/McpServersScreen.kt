package com.wangzan101.mika.mcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.launch
import com.wangzan101.mika.memory.ui.Link

private enum class McpRoute { List, Catalog, Connect, Manage, Custom }

/** 设置 › MCP 服务器:连接外部 MCP server(outbound),和「连接电脑」的 inbound 设置分开。 */
@Composable
fun McpServersScreen(store: McpServerStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connector = remember { McpConnectorState(store, store.catalog, scope) }
    var route by remember { mutableStateOf(McpRoute.List) }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }

    when (route) {
        McpRoute.List -> McpServerList(
            store = store,
            onBrowseCatalog = { route = McpRoute.Catalog },
            onCustomServer = { editing = null; route = McpRoute.Custom },
            onEdit = { editing = it; route = McpRoute.Manage },
            onBack = onBack,
        )
        McpRoute.Catalog -> McpCatalogScreen(
            catalog = store.catalog,
            onOpenPreset = { connector.openPreset(it); route = McpRoute.Connect },
            onCustomServer = { editing = null; route = McpRoute.Custom },
            onBack = { route = McpRoute.List },
        )
        McpRoute.Connect -> McpConnectScreen(
            connector = connector,
            onDone = { connector.close(); route = McpRoute.List },
            onBack = { connector.cancel(); route = McpRoute.Catalog },
        )
        McpRoute.Manage -> editing?.let { cfg ->
            McpServerManageScreen(
                store = store,
                connector = connector,
                config = cfg,
                onEdit = { route = McpRoute.Custom },
                onDeleted = { editing = null; route = McpRoute.List },
                onBack = { editing = null; route = McpRoute.List },
            )
        }
        McpRoute.Custom -> McpCustomServerScreen(
            store = store,
            initial = editing,
            onSaved = { route = if (editing == null) McpRoute.List else McpRoute.Manage },
            onBack = { route = if (editing == null) McpRoute.List else McpRoute.Manage },
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
    val context = LocalContext.current
    val logos = remember(context) { McpLogos.load(context) }

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
                    val logo = server.presetId?.let { logos.logo(it) }
                    ListRow(
                        logo ?: MikaIcons.Link, server.name,
                        subtitle = (state?.let {
                            if (it.ok) stringResource(R.string.mcp_servers_status_ok, it.tools.size)
                            else stringResource(R.string.mcp_servers_status_fail, it.message.orEmpty())
                        } ?: address),
                        detail = state?.let { address },
                        first = i == 0,
                        brandTile = server.presetId?.let { logos.tile(it) },
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
