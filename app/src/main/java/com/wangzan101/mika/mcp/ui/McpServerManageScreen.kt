package com.wangzan101.mika.mcp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import com.wangzan101.mika.memory.ui.Link

/** 设置 › MCP 服务器 › 服务管理:查看真实工具、启停、重新测试、重新授权、替换凭据、删除。 */
@Composable
fun McpServerManageScreen(
    store: McpServerStore,
    connector: McpConnectorState,
    config: McpServerConfig,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val langTag = remember(context) { AppLanguage.resolved(context).tag ?: "en" }
    val logos = remember(context) { McpLogos.load(context) }
    val servers by store.servers.collectAsState()
    val health by store.health.collectAsState()
    val current = servers.firstOrNull { it.id == config.id } ?: config
    val state = health[current.id]
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors

    var showTools by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val isPreset = current.presetSnapshot != null
    val isOAuth = current.auth == "oauth"
    val secretFields = current.presetSnapshot?.fields?.filter { it.secret }.orEmpty()
    val address = if (current.transport == "stdio") current.command else current.url
    val logo = current.presetId?.let { logos.logo(it) }
    var secretValues by remember(current.id) { mutableStateOf(secretFields.associate { it.id to "" }) }
    var token by remember(current.id) { mutableStateOf("") }

    val statusTitle = state?.let {
        if (it.ok) stringResource(R.string.mcp_servers_status_ok, it.tools.size)
        else stringResource(R.string.mcp_servers_status_fail, it.message.orEmpty())
    } ?: address

    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(current.name, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                GroupCard(Modifier.padding(top = 8.dp)) {
                    ListRow(
                        logo ?: MikaIcons.Link, statusTitle, subtitle = address, first = true,
                        tone = if (state?.ok == true) TileTone.Ok else TileTone.Neutral,
                        brandTile = current.presetId?.let { logos.tile(it) },
                    ) {
                        MikaSwitch(current.enabled, { on -> store.setEnabled(current.id, on) })
                    }
                }
                GroupCard {
                    ListRow(
                        MikaIcons.ListChecks, stringResource(R.string.mcp_connect_view_tools), first = true,
                        onClick = { showTools = !showTools },
                    ) { RowChevron() }
                    if (showTools) (state?.tools.orEmpty()).forEach { tool -> ListRow(null, tool) }
                }
                GroupCard {
                    ListRow(
                        MikaIcons.Refresh, stringResource(R.string.mcp_manage_test), first = true,
                        titleColor = c.accent,
                        onClick = {
                            testing = true
                            scope.launch {
                                val result = store.test(current)
                                testing = false
                                message = result.fold(
                                    onSuccess = { null },
                                    onFailure = { it.message ?: it.javaClass.simpleName },
                                )
                            }
                        },
                    ) { RowChevron() }
                    if (isOAuth) {
                        ListRow(
                            MikaIcons.Key, stringResource(R.string.mcp_manage_reauthorize), titleColor = c.accent,
                            onClick = {
                                signingIn = true
                                scope.launch {
                                    val result = store.authorize(current) { link ->
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                                .addCategory(Intent.CATEGORY_BROWSABLE)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                    signingIn = false
                                    result.onFailure { message = it.message ?: it.javaClass.simpleName }
                                }
                            },
                        ) { RowChevron() }
                    }
                    if (secretFields.isNotEmpty() || (!isPreset && current.auth == "token")) {
                        ListRow(
                            MikaIcons.Key, stringResource(R.string.mcp_manage_replace), titleColor = c.accent,
                            onClick = { replacing = !replacing },
                        ) { RowChevron() }
                    }
                    if (!isPreset) {
                        ListRow(
                            MikaIcons.Edit, stringResource(R.string.mcp_manage_edit), titleColor = c.accent,
                            onClick = onEdit,
                        ) { RowChevron() }
                    }
                    ListRow(
                        MikaIcons.Trash, stringResource(R.string.mcp_servers_delete), titleColor = c.bad, tone = TileTone.Bad,
                        onClick = { confirmingDelete = true },
                    )
                }
                if (replacing && (secretFields.isNotEmpty() || (!isPreset && current.auth == "token"))) {
                    GroupCard {
                        if (secretFields.isNotEmpty()) {
                            secretFields.forEachIndexed { i, field ->
                                InputRow(
                                    label = field.label.resolve(langTag),
                                    value = secretValues[field.id].orEmpty(),
                                    onChange = { v -> secretValues = secretValues + (field.id to v) },
                                    placeholder = stringResource(R.string.mcp_manage_replace_hint),
                                    first = i == 0,
                                    secret = true,
                                )
                            }
                        } else {
                            InputRow(
                                label = stringResource(R.string.mcp_servers_token),
                                value = token,
                                onChange = { token = it },
                                placeholder = stringResource(R.string.mcp_manage_replace_hint),
                                first = true,
                                secret = true,
                            )
                        }
                        FooterNote(stringResource(R.string.mcp_manage_replace_hint))
                        MikaButton(
                            stringResource(R.string.mcp_servers_save),
                            {
                                if (secretFields.isNotEmpty()) {
                                    connector.replaceSecrets(current, secretValues)
                                } else {
                                    if (token.isNotBlank()) store.setToken(current.id, token)
                                }
                                replacing = false
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (testing) Text(stringResource(R.string.mcp_servers_testing), Modifier.padding(top = 8.dp), style = MikaType.Caption, color = c.ink3)
                if (signingIn) Text(stringResource(R.string.mcp_servers_signing_in), Modifier.padding(top = 8.dp), style = MikaType.Caption, color = c.ink3)
                message?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = c.bad) }
            }
        }
    }

    if (confirmingDelete) {
        MikaDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(current.name) },
            text = { Text(stringResource(R.string.mcp_manage_delete_note)) },
            confirmButton = {
                TextButton({
                    store.remove(current.id)
                    confirmingDelete = false
                    onDeleted()
                }) { Text(stringResource(R.string.mcp_servers_delete)) }
            },
            dismissButton = {
                TextButton({ confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
