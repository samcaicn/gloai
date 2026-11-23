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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch

/** 设置 › MCP 服务器 › 自定义服务器:完整自定义 HTTP/SSE/stdio,含多请求头与折叠的高级数值项。 */
@Composable
fun McpCustomServerScreen(
    store: McpServerStore,
    initial: McpServerConfig?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
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
    var headers by remember { mutableStateOf(initial?.headers?.map { it.key to it.value }.orEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf((initial?.timeoutSec ?: 60).toString()) }
    var preview by remember { mutableStateOf((initial?.previewChars ?: 4000).toString()) }
    var budget by remember { mutableStateOf(initial?.maxCostUnits?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf(initial?.id) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = MikaTheme.colors
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
        headers = headers.filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .associate { it.first.trim() to it.second.trim() },
        timeoutSec = timeout.toIntOrNull()?.coerceAtLeast(1) ?: 60,
        previewChars = preview.toIntOrNull()?.coerceAtLeast(0) ?: 4000,
        maxCostUnits = budget.trim().toLongOrNull(),
    )

    fun save(): McpServerConfig? {
        val cfg = config()
        val result = if (savedId == null) {
            store.add(cfg, token.ifBlank { null }).onSuccess { savedId = it.id }
        } else {
            store.update(cfg).also {
                if (it.isSuccess) {
                    // 传输/认证切换时清掉不再适用的旧秘密,避免残留。
                    when {
                        cfg.transport == "stdio" -> {
                            store.setToken(cfg.id, null)
                            store.clearOAuth(cfg.id)
                        }
                        cfg.auth == "oauth" -> store.setToken(cfg.id, null)
                        else -> {
                            store.clearOAuth(cfg.id)
                            if (token.isNotBlank()) store.setToken(cfg.id, token)
                        }
                    }
                }
            }
        }
        result.exceptionOrNull()?.let { error = it.message; return null }
        error = null
        return cfg
    }

    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (initial == null) stringResource(R.string.mcp_catalog_custom_server) else name.ifBlank { initial.name }, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                GroupCard(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.mcp_servers_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                    SegmentedControl(
                        options = transportLabels,
                        selected = transportIds.indexOf(transport).coerceAtLeast(0),
                        onSelect = { transport = transportIds[it] },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (transport == "stdio") {
                        OutlinedTextField(
                            command, { command = it }, label = { Text(stringResource(R.string.mcp_servers_command)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        FooterNote(stringResource(R.string.mcp_servers_stdio_help), Modifier.padding(top = 0.dp))
                    } else {
                        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.mcp_servers_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        ToggleRow(stringResource(R.string.mcp_servers_use_oauth), oauth) { oauth = it }
                        if (!oauth) {
                            OutlinedTextField(
                                token, { token = it }, label = { Text(stringResource(R.string.mcp_servers_token)) },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        ToggleRow(stringResource(R.string.mcp_servers_allow_local_http), allowLocal) { allowLocal = it }
                    }
                }

                GroupLabel(stringResource(R.string.mcp_custom_headers))
                GroupCard {
                    headers.forEachIndexed { i, (hname, hvalue) ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                OutlinedTextField(
                                    hname, { v -> headers = headers.toMutableList().also { it[i] = it[i].copy(first = v) } },
                                    label = { Text(stringResource(R.string.mcp_custom_header_name)) },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { headers = headers.filterIndexed { idx, _ -> idx != i } }) { Text(stringResource(R.string.mcp_servers_delete)) }
                            }
                            OutlinedTextField(
                                hvalue, { v -> headers = headers.toMutableList().also { it[i] = it[i].copy(second = v) } },
                                label = { Text(stringResource(R.string.mcp_custom_header_value)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    if (headers.isEmpty()) FooterNote(stringResource(R.string.mcp_servers_help_text))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        MikaButton(stringResource(R.string.mcp_custom_add_header), { headers = headers + ("" to "") }, kind = ButtonKind.Tonal, size = com.wangzan101.mika.ui.components.ButtonSize.Small)
                    }
                }

                GroupCard {
                    OutlinedTextField(
                        allowlist, { allowlist = it },
                        label = { Text(stringResource(R.string.mcp_servers_allowlist)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    ToggleRow(stringResource(R.string.mcp_servers_trust_annotations), trustAnnotations) { trustAnnotations = it }
                }

                GroupCard {
                    ListRow(MikaIcons.Gear, stringResource(R.string.mcp_custom_advanced), first = true, onClick = { advanced = !advanced }) { RowChevron() }
                    if (advanced) {
                        NumberField(stringResource(R.string.mcp_custom_timeout), timeout, { timeout = it })
                        NumberField(stringResource(R.string.mcp_custom_preview), preview, { preview = it })
                        NumberField(stringResource(R.string.mcp_custom_budget), budget, { budget = it })
                    }
                }

                error?.let { Text(it, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.bad) }
                if (testing) Text(stringResource(R.string.mcp_servers_testing), Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink3)
                if (signingIn) Text(stringResource(R.string.mcp_servers_signing_in), Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink3)

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
                                onFailure = { error = context.getString(R.string.mcp_servers_sign_in_failed, it.message ?: it.javaClass.simpleName) },
                            )
                        }
                    }) { Text(signInLabel) }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MikaButton(stringResource(R.string.mcp_servers_save_test), {
                        val saved = save() ?: return@MikaButton
                        testing = true
                        scope.launch {
                            val result = store.test(saved)
                            testing = false
                            result.fold(
                                onSuccess = { error = "OK: ${it.serverName ?: ""} · ${it.tools.size}" },
                                onFailure = { error = it.message ?: it.javaClass.simpleName },
                            )
                        }
                    }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                    MikaButton(stringResource(R.string.mcp_servers_save), {
                        if (save() != null) onSaved()
                    }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChecked)
        Text(title, Modifier.padding(start = 4.dp))
    }
}
