package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.fetch.WebFetchProvider
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.WebFetchConfig
import com.wangzan101.mika.settings.WebFetchEntry
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 网页抓取设置里要的文字;不碰界面,单测直接测。 */
internal object WebFetchCopy {
    /** 服务商在界面上的名字:品牌名,不翻译。 */
    fun name(p: WebFetchProvider): String = when (p) {
        WebFetchProvider.TinyFish -> "TinyFish"
    }

    /** 选中这一家时,服务商下面那一行:它怎么用、要填什么。 */
    fun note(p: WebFetchProvider): String = tr(
        when (p) {
            WebFetchProvider.TinyFish -> R.string.web_fetch_note_tinyfish
        },
    )

    fun keyPage(p: WebFetchProvider): String? = when (p) {
        WebFetchProvider.TinyFish -> "https://agent.tinyfish.ai/api-keys"
    }

    /** 高级与其他那一行右边:开着时写用的哪一家,关着写「关闭」。 */
    fun value(config: WebFetchConfig): String = if (config.enabled) name(config.provider) else tr(R.string.state_off)
}

/**
 * 高级与其他里的网页抓取设置;仍是原有设置层级中的一个对话框,不增加新页面。
 * 上面一排服务商(和网页搜索一样的胶囊),下面只放选中那一家要填的密钥。
 * 各家填的东西分开记着,来回切换不会丢、也不会串到别家。
 * 没填好时「保存」照样能点:点了不保存,把密钥框标红、写明缺什么并把光标放过去;
 * 一打开不标红,只有点过保存、或那一栏填过又清空时才标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebFetchDialog(initial: WebFetchConfig, onDismiss: () -> Unit, onSave: (WebFetchConfig) -> Unit) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var provider by remember { mutableStateOf(initial.provider) }
    val entries = remember { mutableStateMapOf<WebFetchProvider, WebFetchEntry>().apply { putAll(initial.entries) } }
    val entry = entries[provider] ?: WebFetchEntry()
    val config = WebFetchConfig(enabled, provider, entries.toMap())
    val problem = config.problem
    // 点过保存(换一家就重新算)、填过的那一栏:只有这时才把缺的标红
    var tried by remember { mutableStateOf(false) }
    val touched = remember { mutableStateMapOf<WebFetchProvider, Boolean>() }
    fun shows(why: WebFetchProvider.Problem) = problem == why && (tried || touched[provider] == true)
    val keyFocus = remember { FocusRequester() }
    val uri = LocalUriHandler.current
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_fetch_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.web_fetch_enabled), Modifier.weight(1f))
                    MikaSwitch(enabled, { enabled = it }, Modifier.testTag("web-fetch-enabled"))
                }
                GroupLabel(stringResource(R.string.web_fetch_provider), Modifier.padding(top = 4.dp))
                FlowRow(
                    Modifier.fillMaxWidth().testTag("web-fetch-provider"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WebFetchProvider.entries.forEach { p ->
                        SelectChip(WebFetchCopy.name(p), p == provider, { provider = p; tried = false }, Modifier.testTag("web-fetch-provider-${p.wire}"))
                    }
                }
                Text(
                    WebFetchCopy.note(provider), Modifier.padding(top = 8.dp).testTag("web-fetch-note"),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
                if (provider.keyRequired) {
                    val error = shows(WebFetchProvider.Problem.Key)
                    OutlinedTextField(
                        entry.apiKey, { entries[provider] = entry.copy(apiKey = it); touched[provider] = true },
                        Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(keyFocus).testTag("web-fetch-key"),
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        isError = error,
                        label = { Text(stringResource(R.string.web_search_api_key_required)) },
                        supportingText = if (error) ({ Text(tr(R.string.web_search_missing_key, WebFetchCopy.name(provider))) }) else null,
                    )
                    WebFetchCopy.keyPage(provider)?.let { page ->
                        // 没有能打开网页的应用时 openUri 会抛错:这时什么都不做
                        TextButton({ runCatching { uri.openUri(page) } }, Modifier.testTag("web-fetch-get-key")) { Text(stringResource(R.string.web_search_get_key)) }
                    }
                }
                // 和网页搜索一样:密钥加密存在本机、抓到的内容按不可信内容处理
                Text(
                    stringResource(R.string.web_fetch_privacy), Modifier.padding(top = 8.dp).testTag("web-fetch-privacy"),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
            }
        },
        confirmButton = {
            TextButton({
                when (problem) {
                    null -> onSave(config)
                    else -> {
                        tried = true
                        runCatching { keyFocus.requestFocus() }
                    }
                }
            }, Modifier.testTag("web-fetch-save")) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}