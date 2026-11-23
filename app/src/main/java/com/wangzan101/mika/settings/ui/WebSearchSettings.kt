package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.GroundingStatus
import com.wangzan101.mika.settings.WebSearchConfig
import com.wangzan101.mika.settings.WebSearchEntry
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 网页搜索设置里要的文字;不碰界面,单测直接测。 */
internal object WebSearchCopy {
    /** 服务商在界面上的名字:品牌名,不翻译。 */
    fun name(p: WebSearchProvider): String = when (p) {
        WebSearchProvider.Google -> "Google Search"
        WebSearchProvider.You -> "You.com"
        WebSearchProvider.Jina -> "Jina"
        WebSearchProvider.Exa -> "Exa"
        WebSearchProvider.DeepSeek -> "DeepSeek"
        WebSearchProvider.SearXNG -> "SearXNG"
    }

    /** 选中这一家时,服务商下面那一行:它怎么用、要填什么。 */
    fun note(p: WebSearchProvider): String = tr(
        when (p) {
            WebSearchProvider.Google -> R.string.web_search_note_google
            WebSearchProvider.You -> R.string.web_search_note_you
            WebSearchProvider.Jina -> R.string.web_search_note_jina
            WebSearchProvider.Exa -> R.string.web_search_note_exa
            WebSearchProvider.DeepSeek -> R.string.web_search_note_deepseek
            WebSearchProvider.SearXNG -> R.string.web_search_note_searxng
        },
    )

    fun groundingUnavailableMessage(grounding: GroundingStatus): String =
        if (grounding.providerName.isNullOrBlank()) {
            tr(R.string.web_search_google_unavailable_none)
        } else {
            tr(R.string.web_search_google_unavailable_provider, grounding.providerName)
        }

    fun keyLabel(p: WebSearchProvider): String = tr(if (p.keyRequired) R.string.web_search_api_key_required else R.string.web_search_api_key)

    /** 要填地址的只有 SearXNG:它的实例地址。 */
    fun endpointLabel(): String = tr(R.string.web_search_searxng_url)

    const val ENDPOINT_PLACEHOLDER = "https://searx.example.org"

    fun problem(
        p: WebSearchProvider,
        why: WebSearchProvider.Problem,
        grounding: GroundingStatus = GroundingStatus(),
    ): String = when (why) {
        WebSearchProvider.Problem.Endpoint -> tr(R.string.web_search_invalid_endpoint)
        WebSearchProvider.Problem.Key -> tr(R.string.web_search_missing_key, name(p))
        WebSearchProvider.Problem.UnsupportedProvider -> groundingUnavailableMessage(grounding)
    }

    /** 有密钥框的那几家去哪里拿密钥(官方的密钥页);You.com 不填也能用,填了额度更高。 */
    fun keyPage(p: WebSearchProvider): String? = when (p) {
        WebSearchProvider.You -> "https://you.com/platform/api-keys"
        WebSearchProvider.Jina -> "https://jina.ai/?sui=apikey"
        WebSearchProvider.Exa -> "https://dashboard.exa.ai/api-keys"
        WebSearchProvider.DeepSeek -> "https://platform.deepseek.com/api_keys"
        else -> null
    }

    /** 高级与其他那一行右边:开着时写用的哪一家,关着写「关闭」。 */
    fun value(config: WebSearchConfig): String = when {
        !config.enabled -> tr(R.string.state_off)
        config.provider == WebSearchProvider.Google && !config.grounding.available ->
            tr(R.string.web_search_google_unavailable_value)
        else -> name(config.provider)
    }
}

/**
 * 高级与其他里的网页搜索设置;仍是原有设置层级中的一个对话框,不增加新页面。
 * 上面一排服务商(和识别语言一样的胶囊),下面只放选中那一家要填的:托管的几家只有密钥(Jina、Exa 必填),
 * SearXNG 只有实例地址。
 * 各家填的东西分开记着,来回切换不会丢、也不会串到别家。
 * 没填好时「保存」照样能点:点了不保存,把要填的那一栏标红、写明缺什么并把光标放过去;
 * 一打开不标红,只有点过保存、或那一栏填过又清空时才标。必须有密钥的那几家标着「必填」;有密钥框的都给出拿密钥的链接。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSearchDialog(initial: WebSearchConfig, onDismiss: () -> Unit, onSave: (WebSearchConfig) -> Unit) {
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var provider by remember(initial) { mutableStateOf(initial.provider) }
    val entries = remember(initial) { mutableStateMapOf<WebSearchProvider, WebSearchEntry>().apply { putAll(initial.entries) } }
    val grounding = initial.grounding
    val entry = entries[provider] ?: WebSearchEntry()
    val config = WebSearchConfig(enabled, provider, entries.toMap(), grounding)
    val problem = config.problem
    // 点过保存(换一家就重新算)、填过的那几栏:只有这时才把缺的标红
    var tried by remember(initial) { mutableStateOf(false) }
    val touched = remember(initial) { mutableStateMapOf<Pair<WebSearchProvider, WebSearchProvider.Problem>, Boolean>() }
    fun shows(why: WebSearchProvider.Problem) = problem == why && (tried || touched[provider to why] == true)
    val endpointFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val uri = LocalUriHandler.current
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_search_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.web_search_enabled), Modifier.weight(1f))
                    MikaSwitch(
                        enabled,
                        { next ->
                            if (!enabled && next && grounding.available &&
                                (provider == WebSearchProvider.Google ||
                                    (provider == WebSearchProvider.You && entries[WebSearchProvider.You]?.apiKey.isNullOrBlank()))
                            ) {
                                provider = WebSearchProvider.Google
                                tried = false
                            }
                            enabled = next
                        },
                        Modifier.testTag("web-search-enabled"),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.web_search_google_grounding_toggle))
                        if (grounding.available) {
                            if (provider != WebSearchProvider.Google) {
                                Text(
                                    stringResource(R.string.web_search_note_google),
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = MikaType.Hint,
                                    color = MikaTheme.colors.ink3,
                                )
                            }
                        } else {
                            Text(
                                WebSearchCopy.groundingUnavailableMessage(grounding),
                                modifier = Modifier.padding(top = 2.dp).testTag("web-search-grounding-unavailable"),
                                style = MikaType.Hint,
                                color = MikaTheme.colors.ink3,
                            )
                        }
                    }
                    MikaSwitch(
                        checked = enabled && provider == WebSearchProvider.Google,
                        onCheckedChange = { next ->
                            if (next && grounding.available) {
                                enabled = true
                                provider = WebSearchProvider.Google
                                tried = false
                            } else if (!next && provider == WebSearchProvider.Google) {
                                enabled = false
                            }
                        },
                        modifier = Modifier.testTag("web-search-grounding-toggle"),
                        enabled = grounding.available,
                    )
                }
                GroupLabel(stringResource(R.string.web_search_provider), Modifier.padding(top = 8.dp))
                FlowRow(
                    Modifier.fillMaxWidth().testTag("web-search-provider"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WebSearchProvider.entries.forEach { p ->
                        val chipEnabled = p != WebSearchProvider.Google || grounding.available
                        SelectChip(
                            WebSearchCopy.name(p),
                            p == provider,
                            { if (chipEnabled) { provider = p; tried = false } },
                            Modifier.testTag("web-search-provider-${p.wire}"),
                            enabled = chipEnabled,
                        )
                    }
                }
                Text(
                    WebSearchCopy.note(provider), Modifier.padding(top = 8.dp).testTag("web-search-note"),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
                if (shows(WebSearchProvider.Problem.UnsupportedProvider)) {
                    Text(
                        WebSearchCopy.problem(provider, WebSearchProvider.Problem.UnsupportedProvider, grounding),
                        modifier = Modifier.padding(top = 4.dp).testTag("web-search-error"),
                        style = MikaType.Hint,
                        color = MikaTheme.colors.bad,
                    )
                }
                if (provider.needsEndpoint) {
                    val error = shows(WebSearchProvider.Problem.Endpoint)
                    OutlinedTextField(
                        entry.endpoint, { entries[provider] = entry.copy(endpoint = it); touched[provider to WebSearchProvider.Problem.Endpoint] = true },
                        Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(endpointFocus).testTag("web-search-endpoint"),
                        singleLine = true, isError = error,
                        label = { Text(WebSearchCopy.endpointLabel()) }, placeholder = { Text(WebSearchCopy.ENDPOINT_PLACEHOLDER) },
                        supportingText = if (error) ({ Text(WebSearchCopy.problem(provider, WebSearchProvider.Problem.Endpoint, grounding)) }) else null,
                    )
                }
                if (provider.takesKey) {
                    val error = shows(WebSearchProvider.Problem.Key)
                    OutlinedTextField(
                        entry.apiKey, { entries[provider] = entry.copy(apiKey = it); touched[provider to WebSearchProvider.Problem.Key] = true },
                        Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(keyFocus).testTag("web-search-key"),
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        isError = error,
                        label = { Text(WebSearchCopy.keyLabel(provider)) },
                        supportingText = if (error) ({ Text(WebSearchCopy.problem(provider, WebSearchProvider.Problem.Key, grounding)) }) else null,
                    )
                    WebSearchCopy.keyPage(provider)?.let { page ->
                        // 没有能打开网页的应用时 openUri 会抛错:这时什么都不做
                        TextButton({ runCatching { uri.openUri(page) } }, Modifier.testTag("web-search-get-key")) { Text(stringResource(R.string.web_search_get_key)) }
                    }
                }
                // 原来那段说明里要紧的两点:密钥加密存在本机、结果按不可信内容处理
                Text(
                    stringResource(R.string.web_search_privacy), Modifier.padding(top = 8.dp).testTag("web-search-privacy"),
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
                        runCatching {
                            when (problem) {
                                WebSearchProvider.Problem.Key -> keyFocus.requestFocus()
                                WebSearchProvider.Problem.Endpoint -> endpointFocus.requestFocus()
                                WebSearchProvider.Problem.UnsupportedProvider -> Unit
                            }
                        }
                    }
                }
            }, Modifier.testTag("web-search-save")) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
