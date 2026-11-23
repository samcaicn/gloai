package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.FieldClearButton
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.settings.ui.TokenBudgetDialog
import com.wangzan101.mika.settings.ui.tokenBudgetNumber

class ProviderDetailActions(
    val refresh: () -> Unit = {},
    val changeKey: (String) -> Unit = {},
    val addKey: (String) -> Unit = {},
    val removeKey: (String) -> Unit = {},
    val remove: () -> Unit = {},
    /**
     * 把这家的一个模型用作某个用途(「用作」面板里点的),存好后调最后一个参数。主模型先试调用,
     * 进度和原因在 [ModelSetupUi.manualPick]。
     */
    val use: (ModelRole, String, () -> Unit) -> Unit = { _, _, _ -> },
    /** 清掉上次保存或试调用的结果(「用作」面板打开时)。 */
    val clearPick: () -> Unit = {},
    /** 用户说这家的一个模型能不能看图(「用作」面板里的开关)。 */
    val setVision: (String, Boolean) -> Unit = { _, _ -> },
    val setServiceTier: (String, OpenAiServiceTier?) -> Boolean = { _, _ -> false },
    val showAll: () -> Unit = {},
    /** 测试用户确认的模型。 */
    val retestModel: (String) -> Unit = {},
    /** 这家服务商名下的语音模型能力引用;删除前预览哪些语音模型会一并删掉。 */
    val voiceUsage: (String) -> List<VoiceModelRef> = { emptyList() },
    /** 用账号登录的服务商(ChatGPT、xAI、OpenRouter):再登录、退出登录、打开对方的用量或账号页。 */
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val manageUsage: () -> Unit = {},
    /** 这家所有模型、用途合计的本地每日 Token 预算。 */
    val setDailyBudget: (Long?) -> Boolean = { false },
    /** 把这家默认的模型设为主模型(账号登录的详情里那一行)。 */
    val useAsMain: () -> Unit = {},
)

/**
 * 一个服务商:连接(状态、接口、地址、Key 可更换)、几个模型(在用的在前,最多 [MODEL_PREVIEW] 个,其余点「查看全部」)、删除。
 * 点一个模型从底部弹出「用作」。页面长度基本固定,模型再多也不会把删除挤到很远。
 */
@Composable
fun ProviderDetailContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var changingKey by remember { mutableStateOf(false) }
    var addingKey by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRetest by remember(p.id) { mutableStateOf<String?>(null) }
    var editingBudget by remember { mutableStateOf(false) }
    var customBudget by remember { mutableStateOf("") }
    var budgetSaveFailed by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<String?>(null) }
    val state = ui.listing[p.id]
    val list = ui.sortedOptions(p)
    Page(ModelsCopy.providerName(p), onBack) {
        // ── Connection status ──────────────────────────────────────────────
        if (p.kind == ProviderConfig.Kind.ChatGpt) {
            GroupLabel(ModelsCopy.CONNECTION)
            ChatGptConnection(ui, state, actions)
        } else if (p.kind == ProviderConfig.Kind.Xai || p.signIn == ProviderStore.OPENROUTER_SIGN_IN) {
            GroupLabel(ModelsCopy.CONNECTION)
            AccountConnection(ui, p, state, actions)
        } else {
            // Status pill + endpoint at a glance
            GroupLabel(ModelsCopy.CONNECTION)
            GroupCard {
                ListRow(null, ModelsCopy.STATUS, first = true) {
                    when (state) {
                        ListState.Running -> RowValue(ModelsCopy.LISTING)
                        is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                        else -> RowValue(ModelsCopy.CONNECTED, ValueTone.Ok)
                    }
                }
                ListRow(null, ModelsCopy.BASE_URL) { RowValue(ModelsCopy.shortUrl(p.baseUrl)) }
            }
            (state as? ListState.Failed)?.let { FooterNote(ModelSetupCopy.failed(it.message), color = c.bad) }

            // ── API Key Pool ───────────────────────────────────────────────
            val keys = ui.providerKeyMap[p.id].orEmpty()
            val poolHealthy = keys.size
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GroupLabel(
                    if (poolHealthy == 0) "API Keys"
                    else if (poolHealthy == 1) "API Key Pool  ·  1 key"
                    else "API Key Pool  ·  $poolHealthy keys  ·  auto-rotating",
                    Modifier.weight(1f),
                )
                if (keys.isNotEmpty()) {
                    Text(
                        if (showKeys) "Hide Keys" else "Show Keys",
                        color = c.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { showKeys = !showKeys }
                            .padding(end = 4.dp, top = 8.dp, bottom = 4.dp)
                            .testTag("toggle-show-keys"),
                    )
                }
            }
            GroupCard {
                if (keys.isEmpty()) {
                    // No keys yet — prominent empty state
                    ListRow(null, "No key configured", subtitle = "Add a key to connect this provider", first = true) {
                        RowValue("Required", ValueTone.Bad)
                    }
                } else {
                    keys.forEachIndexed { i, k ->
                        val displayKey = if (showKeys) k else when {
                            k.length > 14 -> "${k.take(8)}…${k.takeLast(4)}"
                            k.length > 8  -> "${k.take(4)}…${k.takeLast(4)}"
                            else          -> "••••••••"
                        }
                        ListRow(null, "Key ${i + 1}", subtitle = displayKey, first = i == 0) {
                            RowValue("Active", ValueTone.Ok)
                            if (keys.size > 1) {
                                Text(
                                    "Remove",
                                    color = c.bad,
                                    style = MikaType.Caption,
                                    modifier = Modifier
                                        .clickable { actions.removeKey(k) }
                                        .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                                        .testTag("remove-key-$i"),
                                )
                            }
                        }
                    }
                }
                // Add key — always visible
                ListRow(
                    icon = MikaIcons.Plus,
                    title = "Add API Key",
                    subtitle = if (keys.isEmpty()) "Paste your key to connect" else "Add another key to the rotation pool",
                    first = keys.isEmpty(),
                    onClick = { addingKey = true },
                ) { RowChevron() }
            }
            if (keys.size > 1) {
                FooterNote("Keys rotate automatically. Any key that hits a 429 rate-limit or timeout is skipped until it recovers — zero downtime.")
            } else if (keys.size == 1) {
                FooterNote("Add more keys to enable automatic failover rotation.")
            }
        }

        GroupCard {
            ListRow(
                MikaIcons.Clock, stringResource(R.string.models_daily_token_budget),
                p.dailyTokenBudget?.let(::tokenBudgetNumber) ?: stringResource(R.string.settings_token_budget_unlimited),
                first = true,
                onClick = {
                    customBudget = p.dailyTokenBudget?.takeIf { it !in TokenBudgetLimits.PRESETS }?.toString().orEmpty()
                    budgetSaveFailed = false
                    editingBudget = true
                },
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(ModelsCopy.MODELS, Modifier.weight(1f))
            BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
        }
        GroupCard {
            list.take(MODEL_PREVIEW).forEachIndexed { i, o ->
                ModelListRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o.id }
            }
            if (list.size > MODEL_PREVIEW) {
                ListRow(null, ModelsCopy.viewAll(list.size), titleColor = c.accent, onClick = actions.showAll) { RowChevron() }
            }
            ManualModel(p, state = null, first = list.isEmpty()) { using = it }
        }
        if (list.isNotEmpty()) FooterNote(ModelsCopy.MODELS_NOTE)
        // 跳过测试直接存下的:说清它还没测过,别说成「这家不提供模型列表」——那个说法对它不成立
        val untested = ui.untested(p)
        val unverified = ui.unverified(p)
        if (untested) {
            FooterNote(ModelsCopy.UNTESTED_NOTE, Modifier.testTag("provider-untested"), color = c.warn)
        } else if (unverified.isNotEmpty()) {
            FooterNote(ModelsCopy.unverifiedNote(unverified), Modifier.testTag("provider-unverified"), color = c.warn)
        }
        ui.usedChatModels(p).forEach { model ->
            val retest = ui.retest[p.id to model]
            MikaButton(
                if (retest == ListState.Running) ModelSetupCopy.TESTING else tr(R.string.models_retest_model, model),
                { confirmRetest = model },
                Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("provider-retest-${model}"),
                kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = retest != ListState.Running,
            )
            (retest as? ListState.Failed)?.let { FooterNote(it.message, Modifier.testTag("provider-retest-failed"), color = c.bad) }
        }
        when {
            // 跳过测试存下的那次没列过模型,不能拿它当「这家不提供列表」的证据
            p.models?.isEmpty() == true && !untested -> FooterNote(ModelsCopy.NO_LIST_NOTE)
            p.models == null && list.isEmpty() && state != ListState.Running -> FooterNote(ModelsCopy.NOT_LISTED)
        }

        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Trash, ModelsCopy.DELETE, titleColor = c.bad, tone = TileTone.Bad, first = true, onClick = { confirmDelete = true })
        }
    }

    confirmRetest?.let { model ->
        MikaDialog(
            onDismissRequest = { confirmRetest = null },
            title = { Text(tr(R.string.models_retest_confirm_title)) },
            text = { Text(tr(R.string.models_retest_confirm_body, model, ModelsCopy.providerName(p))) },
            confirmButton = {
                TextButton(
                    { confirmRetest = null; actions.retestModel(model) },
                    modifier = Modifier.testTag("provider-confirm-retest"),
                ) { Text(ModelsCopy.RETEST) }
            },
            dismissButton = { TextButton({ confirmRetest = null }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }

    if (editingBudget) TokenBudgetDialog(
        title = stringResource(R.string.models_daily_token_budget),
        description = stringResource(R.string.models_daily_token_budget_sub),
        current = p.dailyTokenBudget,
        custom = customBudget,
        onCustom = { customBudget = it.filter(Char::isDigit).take(9); budgetSaveFailed = false },
        onPick = {
            if (actions.setDailyBudget(it)) editingBudget = false else budgetSaveFailed = true
        },
        onDismiss = { editingBudget = false },
        saveError = if (budgetSaveFailed) stringResource(R.string.models_daily_token_budget_save_failed) else null,
    )
    using?.let { model -> UseAsSheet(ui, p, model, actions, onDismiss = { using = null }) }
    if (changingKey) {
        var key by remember { mutableStateOf("") }
        var notice by remember { mutableStateOf(ApiKeyText.Notice.None) }
        val blocked = notice == ApiKeyText.Notice.IllegalCharacters
        val parsedKeys = remember(key) { com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(key) }
        MikaDialog(
            onDismissRequest = { changingKey = false },
            title = { Text(ModelsCopy.CHANGE_KEY) },
            text = {
                KeyboardDismissArea {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().keepsKeyboard().background(c.fill, MikaShapes.Button)
                                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (key.isEmpty()) Text("Paste one or more keys (comma or newline separated)", style = MikaType.Body, color = c.ink3)
                                BasicTextField(
                                    key,
                                    { raw ->
                                        val accepted = ApiKeyText.accept(raw)
                                        key = accepted.text
                                        notice = accepted.notice
                                    },
                                    Modifier.fillMaxWidth().testTag("new-key"), singleLine = false, maxLines = 4,
                                    textStyle = MikaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            }
                            if (key.isNotEmpty()) {
                                FieldClearButton(
                                    stringResource(R.string.model_key_clear),
                                    {
                                        val cleared = ApiKeyText.accept("")
                                        key = cleared.text
                                        notice = cleared.notice
                                    },
                                    Modifier.testTag("new-key-clear"),
                                )
                            }
                        }
                        if (parsedKeys.size > 1) {
                            Text(
                                "✓ Key Pool: ${parsedKeys.size} API keys detected (auto-rotating on 429/timeout)",
                                style = MikaType.Caption,
                                color = c.ok,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        } else {
                            ApiKeyNoticeNote(notice, tag = "new-key-notice")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(key.trim())
                        if (blocked || parsed.isEmpty() || !parsed.all { ApiKeyText.isHeaderLegal(it) }) return@TextButton
                        changingKey = false
                        actions.changeKey(key)
                    },
                    enabled = key.isNotBlank() && !blocked,
                ) { Text(ModelsCopy.SAVE) }
            },
            dismissButton = { TextButton({ changingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (addingKey) {
        var newKeyText by remember { mutableStateOf("") }
        var showAddKey by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf(ApiKeyText.Notice.None) }
        val blocked = notice == ApiKeyText.Notice.IllegalCharacters
        val parsedAdding = remember(newKeyText) { com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(newKeyText) }
        MikaDialog(
            onDismissRequest = { addingKey = false },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add API Key", Modifier.weight(1f))
                    if (newKeyText.isNotEmpty()) {
                        Text(
                            if (showAddKey) "Hide" else "Show",
                            color = c.accent,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { showAddKey = !showAddKey }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            },
            text = {
                KeyboardDismissArea {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "Paste one key, or multiple keys separated by commas or new lines to add them all at once.",
                            style = MikaType.Caption, color = c.ink2,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        Box(
                            Modifier.fillMaxWidth().keepsKeyboard()
                                .background(c.fill, MikaShapes.Button)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (newKeyText.isEmpty()) {
                                Text(
                                    "Paste API key(s) here",
                                    style = MikaType.Body, color = c.ink3,
                                )
                            }
                            BasicTextField(
                                newKeyText,
                                { raw ->
                                    val accepted = ApiKeyText.accept(raw)
                                    newKeyText = accepted.text
                                    notice = accepted.notice
                                },
                                Modifier.fillMaxWidth().testTag("add-key-input"),
                                singleLine = false,
                                maxLines = 5,
                                textStyle = MikaType.Body.copy(
                                    color = c.ink,
                                    fontFamily = if (showAddKey) FontFamily.Monospace else null,
                                ),
                                cursorBrush = SolidColor(c.accent),
                                visualTransformation = if (showAddKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            )
                        }
                        // Live feedback
                        when {
                            parsedAdding.size > 1 -> Text(
                                "${parsedAdding.size} keys detected — all will be added to the rotation pool",
                                style = MikaType.Caption, color = c.ok,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            newKeyText.isNotBlank() && !blocked -> Text(
                                "1 key ready to add",
                                style = MikaType.Caption, color = c.ok,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            else -> ApiKeyNoticeNote(notice, tag = "add-key-notice")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(newKeyText.trim())
                        if (blocked || parsed.isEmpty() || !parsed.all { ApiKeyText.isHeaderLegal(it) }) return@TextButton
                        addingKey = false
                        actions.addKey(newKeyText.trim())
                    },
                    enabled = newKeyText.isNotBlank() && !blocked,
                ) { Text(if (parsedAdding.size > 1) "Add ${parsedAdding.size} Keys" else ModelsCopy.SAVE) }
            },
            dismissButton = { TextButton({ addingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (confirmDelete) {
        MikaDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ModelsCopy.deleteTitle(ModelsCopy.providerName(p))) },
            text = { Text(ModelsCopy.deleteBody(ui.rolesOf(p), actions.voiceUsage(p.id))) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    actions.remove()
                }, Modifier.testTag("confirm-delete")) { Text(ModelsCopy.DELETE_CONFIRM, color = c.bad) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
}
