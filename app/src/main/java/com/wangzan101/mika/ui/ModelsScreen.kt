package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.oauth.AccountSignInException
import com.wangzan101.mika.core.oauth.AccountSignInFailure
import com.wangzan101.mika.core.usage.TokenBudgetLimits
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.FieldClearButton
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions

/** 设置 › 模型:和上手时的配置模型是同一页([ModelSetupScreen]),不在引导流程里。 */
@Composable
fun ModelsScreen(
    store: ProviderStore, onBack: () -> Unit,
    onOpenSpeech: (() -> Unit)? = null,
    onOpenVoice: (() -> Unit)? = null,
    onOpenImage: (() -> Unit)? = null,
    onOpenUsage: (() -> Unit)? = null,
) {
    ModelSetupScreen(store, onboarding = false, onBack = onBack, onDone = onBack, onOpenSpeech = onOpenSpeech, onOpenVoice = onOpenVoice, onOpenImage = onOpenImage, onOpenUsage = onOpenUsage)
}

/** 子页面共用的骨架:顶栏 + 可滚动内容。 */
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(scrollState).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                content()
            }
        }
    }
}

/** 用途卡:大图标块、「名字 · 做什么」、选了什么(模型名用等宽字)、说明。 */
@Composable
internal fun RoleCard(icon: ImageVector, label: String, line: ModelsCopy.RoleLine, first: Boolean = false, tag: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline(start = 0.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).background(c.neutralSoft, RoleTileShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MikaType.Hint, color = c.ink3)
                Text(
                    line.title, Modifier.padding(top = 1.dp), style = MikaType.BodyStrong,
                    fontFamily = if (line.isModel) FontFamily.Monospace else null, color = if (line.unset) c.ink3 else c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (line.subtitle.isNotEmpty()) {
                    Text(
                        line.subtitle, Modifier.padding(top = 1.dp), style = MikaType.Caption,
                        color = if (line.subtitleOk) c.ok else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowChevron()
        }
    }
}

private val RoleTileShape = MikaShapes.Chip

/** 服务商一行:左边是名字首字的图标块;[warning] 是要提醒的一句(用提醒色另起一行)。 */
@Composable
internal fun ProviderRow(p: Provider, line: String, first: Boolean, warning: String? = null, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("provider-${p.name}").padding(horizontal = 16.dp).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(MikaSize.Tile).background(c.neutralSoft, MikaShapes.Tile), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(ModelsCopy.providerName(p), style = MikaType.Body, color = c.ink, maxLines = 1)
                val poolCount = com.wangzan101.mika.core.model.KeyPoolManager.shared.keyCount(p.id)
                val poolTag = if (poolCount > 1) " · Key Pool ($poolCount keys)" else ""
                Text(line + poolTag, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = if (poolCount > 1) c.ok else c.ink3)
                warning?.let { Text(it, Modifier.padding(top = 2.dp).testTag("provider-warning-${p.name}"), style = MikaType.Caption, color = c.warn) }
            }
            RowChevron()
        }
    }
}

// ---------- 选某个用途用哪个模型 ----------

class RolePickerActions(
    /** (服务商 id, 模型名) */
    val pick: (String, String) -> Unit = { _, _ -> },
    /** 不提供列表的服务商手填的模型名(服务商 id, 模型名):先试调用,通了才选。 */
    val pickManual: (String, String) -> Unit = { _, _ -> },
    /** 看图、轻量的固定选项(用主模型、不用)。 */
    val choose: (RoleChoice) -> Unit = {},
    val addProvider: () -> Unit = {},
)

/** 选择页顶上的固定选项:看图「用主模型」「不用」,轻量「用主模型」;主模型没有。 */
internal fun fixedChoices(role: ModelRole): List<RoleChoice> = when (role) {
    ModelRole.Main -> emptyList()
    ModelRole.Vision -> listOf(RoleChoice.SameAsMain, RoleChoice.Off)
    ModelRole.Light -> listOf(RoleChoice.SameAsMain)
}

/**
 * 选用途:看图、轻量顶上是固定选项;下面按服务商分组列出所有已连接服务商的全部模型(用记住的列表),
 * 标签只作参考,不按能力过滤或置灰;每家下面都能手动填模型名(列表里没有、或这家不提供列表),手填的标「手填」。点一下就生效并返回。
 */
@Composable
fun RolePickerContent(ui: ModelSetupUi, role: ModelRole, actions: RolePickerActions, onBack: () -> Unit,
    titleOverride: String? = null, hintOverride: String? = null,
    pickedOverride: ((String, String) -> Boolean)? = null,
    exclude: ((String, String) -> Boolean)? = null,
    clearAction: (() -> Unit)? = null, clearSelected: Boolean = false,
) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    val groups = ui.providers.map { it to ui.options(it) }
    val total = groups.sumOf { it.second.size }
    val q = filter.trim()
    Page(titleOverride ?: ModelsCopy.roleName(role), onBack) {
        val hint = hintOverride ?: ModelsCopy.roleHint(role)
        if (hint.isNotEmpty()) {
            Text(hint, Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        }
        val fixed = fixedChoices(role)
        if (fixed.isNotEmpty()) {
            GroupCard {
                fixed.forEachIndexed { i, choice ->
                    RadioRow(
                        ModelsCopy.choiceTitle(choice), selected = ui.roles[role] == choice, onClick = { actions.choose(choice) },
                        subtitle = ModelsCopy.choiceNote(role, choice), first = i == 0,
                    )
                }
            }
        }
        if (clearAction != null) {
            GroupCard {
                RadioRow(ModelsCopy.FALLBACK_CLEAR, selected = clearSelected, onClick = clearAction, first = true)
            }
        }
        if (total > FILTER_FROM) {
            GroupLabel(ModelsCopy.PICK_FROM, Modifier.padding(top = 8.dp))
            Box(Modifier.padding(bottom = 4.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("model-filter"))
            }
        }
        var anyShown = false
        groups.forEach { (p, options) ->
            val shown = options.filter { (q.isEmpty() || it.id.contains(q, ignoreCase = true)) && exclude?.invoke(p.id, it.id) != true }
            // 这家列不出模型:手填是唯一的选法
            val noList = p.models?.isEmpty() == true
            if (q.isNotEmpty() && shown.isEmpty()) return@forEach
            anyShown = true
            val state = ui.listing[p.id]
            GroupLabel(ModelsCopy.providerName(p))
            GroupCard {
                shown.take(PICK_AT_MOST).forEachIndexed { i, o ->
                    RadioRow(
                        o.id, selected = pickedOverride?.invoke(p.id, o.id) ?: ui.picked(role, p.id, o.id), onClick = { actions.pick(p.id, o.id) }, first = i == 0,
                        titleStyle = ModelIdStyle, below = { Tags(o, forVision = role == ModelRole.Vision) },
                    )
                }
                ManualModel(p, ui.manualPick[p.id], first = shown.isEmpty()) { actions.pickManual(p.id, it) }
            }
            val manualState = ui.manualPick[p.id]
            when {
                manualState is ListState.Failed -> FooterNote(manualState.message, Modifier.testTag("manual-model-failed"), color = c.bad)
                // 选列表里的模型也可能先试调用(见 ModelSetupUi.probeBeforePick);手填的那一行自己显示进度
                !noList && manualState == ListState.Running -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("pick-probe-running"))
                state == ListState.Running -> FooterNote(ModelsCopy.LISTING)
                state is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                shown.size > PICK_AT_MOST -> FooterNote(ModelsCopy.more(shown.size - PICK_AT_MOST))
            }
        }
        if (q.isNotEmpty() && !anyShown) FooterNote(ModelsCopy.NO_MATCH)
        if (ui.providers.isEmpty()) FooterNote(ModelsCopy.ROLE_EMPTY)
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = actions.addProvider)
        }
        FooterNote(ModelsCopy.PICKER_NOTE)
    }
}

/** 手填模型名(列表里没有、或服务不提供列表):照服务商文档填;点「使用」先试调用一次([state] 是进度),通了才选上并记为手填。 */
@Composable
internal fun ManualModel(p: Provider, state: ListState?, first: Boolean, onUse: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val running = state == ListState.Running
    val enabled = name.isNotBlank() && !running
    val example = ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind)
    InputRow(ModelsCopy.MODEL_NAME, name, { name = it }, example, first = first, modifier = Modifier.testTag("manual-model")) {
        Text(
            if (running) ModelSetupCopy.TESTING else ModelsCopy.USE, Modifier.clickable(enabled = enabled) { onUse(name.trim()) }.testTag("manual-model-use"),
            style = MikaType.Label, color = if (enabled) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

private const val FILTER_FROM = 8
private const val PICK_AT_MOST = 80

/** 服务商详情里最多放几个模型;其余进「全部模型」页。 */
internal const val MODEL_PREVIEW = 3

// ---------- 一个服务商 ----------

class ProviderDetailActions(
    val refresh: () -> Unit = {},
    val changeKey: (String) -> Unit = {},
    val addKey: (String) -> Unit = {},
    val removeKey: (String) -> Unit = {},
    val remove: () -> Unit = {},
    /** 把这家的一个模型用作某个用途(「用作」面板里点的)。 */
    val use: (ModelRole, String) -> Unit = { _, _ -> },
    /** 用户说这家的一个模型能不能看图(「用作」面板里的开关)。 */
    val setVision: (String, Boolean) -> Unit = { _, _ -> },
    val setServiceTier: (String, OpenAiServiceTier?) -> Boolean = { _, _ -> false },
    val showAll: () -> Unit = {},
    /** 在用、没核实过的手填模型再试调用一次(见 [ModelSetupUi.unverified])。 */
    val retest: () -> Unit = {},
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
    var editingBudget by remember { mutableStateOf(false) }
    var customBudget by remember { mutableStateOf("") }
    var budgetSaveFailed by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<ModelOption?>(null) }
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
        if (list.isNotEmpty()) {
            GroupCard {
                list.take(MODEL_PREVIEW).forEachIndexed { i, o -> ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o } }
                if (list.size > MODEL_PREVIEW) {
                    ListRow(null, ModelsCopy.viewAll(list.size), titleColor = c.accent, onClick = actions.showAll) { RowChevron() }
                }
            }
            FooterNote(ModelsCopy.MODELS_NOTE)
        }
        // 跳过测试直接存下的:说清它还没测过,别说成「这家不提供模型列表」——那个说法对它不成立
        val untested = ui.untested(p)
        val unverified = ui.unverified(p)
        if (untested) {
            FooterNote(ModelsCopy.UNTESTED_NOTE, Modifier.testTag("provider-untested"), color = c.warn)
        } else if (unverified.isNotEmpty()) {
            FooterNote(ModelsCopy.unverifiedNote(unverified), Modifier.testTag("provider-unverified"), color = c.warn)
        }
        // 有模型在用时可以就地重测一次;没有的在用的,先刷新列表或去选一个
        if (unverified.isNotEmpty()) {
            val retest = ui.retest[p.id]
            MikaButton(
                if (retest == ListState.Running) ModelSetupCopy.TESTING else ModelsCopy.RETEST, actions.retest,
                Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("provider-retest"),
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
    using?.let { UseAsSheet(ui, p, it.id, actions, onDismiss = { using = null }) }
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
            text = { Text(ModelsCopy.deleteBody(ui.rolesOf(p))) },
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

/**
 * ChatGPT 的连接:账号(邮箱)、退出登录或再登录、管理用量(打开 ChatGPT 的用量页)。
 * 退出登录只清这台手机上的令牌,服务商、模型和用途都留着。
 */
@Composable
private fun ChatGptConnection(ui: ModelSetupUi, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val account = ui.chatGptAccount
    val signedIn = account?.signedIn == true
    GroupCard {
        ListRow(null, ModelsCopy.STATUS, first = true) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
        account?.email?.takeIf { it.isNotEmpty() }?.let { email -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT) { RowValue(email) } }
        ListRow(null, ModelsCopy.CHATGPT_MANAGE_USAGE, titleColor = c.accent, onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(
                null, if (ui.chatGptSignIn == ListState.Running) ModelsCopy.CHATGPT_WAITING else ModelsCopy.CHATGPT_CONTINUE,
                titleColor = c.accent, onClick = actions.signIn,
            )
        }
    }
    FooterNote(if (signedIn) ModelsCopy.CHATGPT_PLAN_NOTE else ModelsCopy.CHATGPT_SIGNED_OUT_NOTE)
    if (ui.chatGptRevokeUnconfirmed) FooterNote(ModelsCopy.CHATGPT_REVOKE_UNCONFIRMED, color = c.warn)
    (ui.chatGptSignIn as? ListState.Failed)?.let { FooterNote(it.message, color = c.bad) }
}

/**
 * 用 xAI 或 OpenRouter 账号登录的连接:账号、状态、设为主模型、打开对方的账号页(OpenRouter 是 Keys 页面)、退出或再登录。
 * 退出只清这台手机上的登录信息(xAI 的令牌、OpenRouter 换来的 Key),服务商、模型和用途都留着。
 */
@Composable
private fun AccountConnection(ui: ModelSetupUi, p: Provider, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val kind = if (p.kind == ProviderConfig.Kind.Xai) SignInKind.Xai else SignInKind.OpenRouter
    val account = if (kind == SignInKind.Xai) ui.xaiAccount else ui.openRouterAccount
    val signedIn = account?.signedIn == true
    val (_, waiting, failure) = ui.signInState(kind)
    GroupCard {
        account?.label?.takeIf { it.isNotEmpty() }?.let { label -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT, first = true) { RowValue(label) } }
        ListRow(null, ModelsCopy.STATUS, first = account?.label.isNullOrEmpty()) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
    }
    GroupCard {
        // 主模型已经在这家就不放这一行
        val isMain = ui.configFor(ModelRole.Main)?.providerId == p.id
        if (signedIn && !isMain && ui.options(p).isNotEmpty()) {
            ListRow(null, ModelsCopy.SET_MAIN, ModelsCopy.SET_MAIN_NOTE, first = true, onClick = actions.useAsMain) { RowChevron() }
        }
        ListRow(null, ModelsCopy.accountManage(kind), titleColor = c.accent, first = !(signedIn && !isMain && ui.options(p).isNotEmpty()), onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, ModelsCopy.SIGN_OUT_NOTE, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(null, if (waiting) ModelsCopy.SIGN_IN_RUNNING else ModelsCopy.signInStart(kind), titleColor = c.accent, onClick = actions.signIn)
        }
    }
    FooterNote(ModelsCopy.accountNote(kind, signedIn))
    if (signedIn && kind == SignInKind.Xai) FooterNote(ModelsCopy.XAI_403_NOTE, Modifier.testTag("account-403-note"), color = c.warn)
    failure?.let { FooterNote(it, color = c.bad) }
}

/**
 * 「<服务商> · 全部模型」:搜索框固定在顶栏下面,列表只在下面滚(模型多时按需排版);右上角刷新重新列一次。
 * 点模型同样弹出「用作」。
 */
@Composable
fun AllModelsContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val all = ui.sortedOptions(p)
    val q = filter.trim()
    val shown = all.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(ModelsCopy.allModelsTitle(ModelsCopy.providerName(p)), MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
            })
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("all-models-filter"))
            }
            when (state) {
                ListState.Running -> FooterNote(ModelsCopy.LISTING)
                is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                else -> Unit
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, o -> o.id }) { i, o ->
                    Box(Modifier.fillMaxWidth().clip(rowShape(i, shown.size)).background(c.surface)) {
                        ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o }
                    }
                }
                item {
                    FooterNote(
                        if (q.isNotEmpty() && shown.isEmpty()) ModelsCopy.NO_MATCH else ModelsCopy.allModelsNote(all.size),
                        Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
    using?.let { UseAsSheet(ui, p, it.id, actions, onDismiss = { using = null }) }
}

/** 一张卡拆成懒加载的行时,每行的圆角:首行上圆、末行下圆。 */
private fun rowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp)
}

/** 模型一行:模型名(等宽)和标签,右边标着它在给哪些用途干活。 */
@Composable
private fun ModelRow(o: ModelOption, roles: List<ModelRole>, first: Boolean, onClick: () -> Unit) {
    Column {
        if (!first) Hairline(start = 16.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("model-${o.id}").padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(o.id, style = ModelIdStyle, color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 4.dp)) { Tags(o, accent = false) }
            }
            roles.forEach { StatusPill(ModelsCopy.roleName(it), PillTone.Accent) }
            RowChevron()
        }
    }
}

/**
 * 点一个模型后从底部升起:「<服务商> · 用作」主模型 / 看图模型 / 轻量模型,点了马上生效;
 * 下面是「能看图」开关:列表没写的模型由用户说了算,列表写了的也能改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UseAsSheet(ui: ModelSetupUi, p: Provider, model: String, actions: ProviderDetailActions, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = MikaShapes.SheetTop) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 8.dp).testTag("use-as")) {
            Text(model, Modifier.padding(horizontal = 16.dp), style = ModelIdStyle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ModelsCopy.useAsSubtitle(ModelsCopy.providerName(p)), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MikaType.Caption, color = c.ink3)
            ModelRole.entries.forEachIndexed { i, role ->
                val on = ui.picked(role, p.id, model)
                RadioRow(
                    ModelsCopy.roleName(role), selected = on, first = i == 0,
                    subtitle = if (on) ModelsCopy.IN_USE else ModelsCopy.roleWhat(role),
                    onClick = { actions.use(role, model); onDismiss() },
                )
            }
            ui.options(p).firstOrNull { it.id == model }?.let { o ->
                val usedForVision = ModelRole.Vision in ui.rolesOf(p.id, model)
                val sees = ModelOptions.seesImages(o.capabilities.vision, o.visionByUser, usedForVision)
                ListRow(null, ModelsCopy.SEES_IMAGES, ModelsCopy.seesNote(o, usedForVision)) {
                    MikaSwitch(sees, { actions.setVision(model, it) }, Modifier.testTag("model-sees-images"))
                }
            }
            if (p.supportsServiceTier(model)) {
                ModelSpeedControl(p.serviceTiers[model]) { actions.setServiceTier(model, it) }
            }
            ListRow(null, ModelSetupCopy.CANCEL, titleColor = c.ink2, onClick = onDismiss)
        }
    }
}

@Composable
private fun ModelSpeedControl(selected: OpenAiServiceTier?, onSelect: (OpenAiServiceTier?) -> Boolean) {
    var open by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val title = stringResource(R.string.models_response_speed)
    ListRow(
        null, title, onClick = { saveFailed = false; open = true },
        modifier = Modifier.testTag("model-response-speed"),
    ) {
        RowValue(serviceTierLabel(selected))
        RowChevron()
    }
    if (open) MikaDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FooterNote(stringResource(R.string.models_response_speed_note))
                (listOf(null) + OpenAiServiceTier.entries).forEachIndexed { index, tier ->
                    RadioRow(serviceTierLabel(tier), selected = selected == tier, first = index == 0, onClick = {
                        if (onSelect(tier)) open = false else saveFailed = true
                    })
                }
                if (saveFailed) FooterNote(stringResource(R.string.model_context_save_failed), color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun serviceTierLabel(tier: OpenAiServiceTier?): String = when (tier) {
    null -> stringResource(R.string.model_reasoning_default)
    OpenAiServiceTier.Fast -> "Fast"
    OpenAiServiceTier.Ultrafast -> "Ultrafast"
}

/** 模型页(用途、选择页、服务商详情、全部模型)的文字(纯函数,便于测试)。三个用途的说法全 App 只在这里。 */
object ModelsCopy {
    val TITLE: String get() = tr(R.string.settings_model)
    val LEAD: String get() = tr(R.string.models_lead)
    val ROLES: String get() = tr(R.string.models_roles)
    val PROVIDERS: String get() = tr(R.string.models_providers)
    val ADD_PROVIDER: String get() = tr(R.string.models_add_provider)
    val KEY_NOTE: String get() = tr(R.string.models_key_note)
    val UNSET: String get() = tr(R.string.settings_not_set)
    val MAIN_ADD_FIRST: String get() = tr(R.string.models_main_add_first)
    val MAIN_PICK: String get() = tr(R.string.models_main_pick)
    val SAME_AS_MAIN: String get() = tr(R.string.models_same_as_main)
    val MAIN_NOT_PICKED: String get() = tr(R.string.models_main_not_picked)
    val OFF: String get() = tr(R.string.models_off)
    val OFF_NOTE: String get() = tr(R.string.models_off_note)
    val VISION_SAME_NOTE: String get() = tr(R.string.models_vision_same_note)
    val LIGHT_SAME_NOTE: String get() = tr(R.string.models_light_same_note)
    val PICK_FROM: String get() = tr(R.string.models_pick_from)
    val FILTER_HINT: String get() = tr(R.string.models_filter_hint)
    val NO_MATCH: String get() = tr(R.string.models_no_match)
    val MODEL_NAME: String get() = tr(R.string.models_model_name)
    val USE: String get() = tr(R.string.models_use)
    val ROLE_EMPTY: String get() = tr(R.string.models_role_empty)
    val PICKER_NOTE: String get() = tr(R.string.models_picker_note)
    val LISTING: String get() = tr(R.string.models_listing)
    val CONNECTION: String get() = tr(R.string.models_connection)
    val STATUS: String get() = tr(R.string.models_status)
    val CONNECTED: String get() = tr(R.string.phone_connected)
    val LIST_FAILED: String get() = tr(R.string.models_list_failed)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.models_url)
    const val API_KEY = "API Key"
    val KEY_SAVED: String get() = tr(R.string.models_key_saved)
    val KEY_NONE: String get() = tr(R.string.models_key_none)
    val CHANGE: String get() = tr(R.string.models_change)
    val CHANGE_KEY: String get() = tr(R.string.models_change_key)
    val NEW_KEY_HINT: String get() = tr(R.string.models_new_key_hint)
    val SAVE: String get() = tr(R.string.save)
    val MODELS: String get() = tr(R.string.models_models)
    val MODELS_NOTE: String get() = tr(R.string.models_models_note)
    val NO_LIST_NOTE: String get() = tr(R.string.models_no_list_note)
    val UNTESTED_NOTE: String get() = tr(R.string.models_untested_note)
    val UNVERIFIED: String get() = tr(R.string.models_unverified)
    val RETEST: String get() = tr(R.string.models_retest)
    val SEES_IMAGES: String get() = tr(R.string.models_sees_images)

    /** 「能看图」开关下的说明:用户说过的、用作看图的、列表写明的、没写未确认的。 */
    fun seesNote(o: ModelOption, usedForVision: Boolean): String = tr(when {
        o.visionByUser != null -> R.string.models_sees_note_user
        usedForVision -> R.string.models_sees_note_vision
        o.visionKnown -> R.string.models_sees_note_listed
        else -> R.string.models_sees_note_unconfirmed
    })

    fun unverifiedNote(models: List<String>) = tr(R.string.models_unverified_note, models.joinToString(tr(R.string.list_sep)))
    val NOT_LISTED: String get() = tr(R.string.models_not_listed)
    val REFRESH: String get() = tr(R.string.refresh)
    val IN_USE: String get() = tr(R.string.models_in_use)
    val DELETE: String get() = tr(R.string.models_delete)
    val DELETE_CONFIRM: String get() = tr(R.string.confirm_delete)
    val CHATGPT_TITLE: String get() = tr(R.string.chatgpt_use_plan_title)
    val CHATGPT_BODY: String get() = tr(R.string.chatgpt_use_plan_body)
    val CHATGPT_CONTINUE: String get() = tr(R.string.chatgpt_continue)
    val CHATGPT_WAITING: String get() = tr(R.string.chatgpt_waiting)
    val CHATGPT_WELCOME_TITLE: String get() = tr(R.string.chatgpt_welcome_title)
    val CHATGPT_WELCOME_BODY: String get() = tr(R.string.chatgpt_welcome_body)
    val CHATGPT_GOT_IT: String get() = tr(R.string.chatgpt_got_it)
    val CHATGPT_ACCOUNT: String get() = tr(R.string.chatgpt_account)
    val CHATGPT_SIGNED_IN: String get() = tr(R.string.chatgpt_signed_in)
    val CHATGPT_SIGNED_OUT: String get() = tr(R.string.chatgpt_signed_out)
    val CHATGPT_SIGN_OUT: String get() = tr(R.string.chatgpt_sign_out)
    val CHATGPT_MANAGE_USAGE: String get() = tr(R.string.chatgpt_manage_usage)
    val CHATGPT_PLAN_NOTE: String get() = tr(R.string.chatgpt_plan_note)
    val CHATGPT_SIGNED_OUT_NOTE: String get() = tr(R.string.chatgpt_signed_out_note)
    val CHATGPT_REVOKE_UNCONFIRMED: String get() = tr(R.string.chatgpt_revoke_unconfirmed)

    /**
     * 用 ChatGPT 登录没成的原因(界面和回调网页共用)。说得出具体原因的照说;只有服务端说了套餐、权限,
     * 或授予的权限里没有套餐那一项时才提 Plus、Pro;别的被拒带上错误代码 [code](不是机密)。
     */
    fun chatGptFailure(reason: SignInFailure, code: String? = null): String = when (reason) {
        SignInFailure.Denied -> tr(R.string.chatgpt_fail_denied)
        SignInFailure.StateMismatch, SignInFailure.ClientMismatch -> tr(R.string.chatgpt_fail_mismatch)
        SignInFailure.NoClientId -> tr(R.string.chatgpt_fail_no_client)
        SignInFailure.TimedOut -> tr(R.string.chatgpt_fail_timed_out)
        SignInFailure.Exchange -> tr(R.string.chatgpt_fail_exchange, code?.takeIf { it.isNotBlank() } ?: "unknown")
        SignInFailure.CodeExpired -> tr(R.string.chatgpt_fail_code_expired)
        SignInFailure.Region -> tr(R.string.chatgpt_fail_region)
        SignInFailure.Blocked -> tr(R.string.chatgpt_fail_blocked)
        SignInFailure.IdToken -> tr(R.string.chatgpt_fail_id_token)
        SignInFailure.NoPlanScope -> tr(R.string.chatgpt_fail_no_plan)
        SignInFailure.Network -> tr(R.string.chatgpt_fail_network)
    }

    // ---------- 用账号登录(xAI、OpenRouter;ChatGPT 的在上面) ----------

    val SIGN_IN_GROUP: String get() = tr(R.string.signin_group)
    val KEY_GROUP: String get() = tr(R.string.signin_key_group)
    val SIGN_IN_RUNNING: String get() = tr(R.string.signin_running)
    val SIGN_IN_USE_KEY: String get() = tr(R.string.signin_use_key)
    val SIGN_IN_BUSY: String get() = tr(R.string.signin_busy)

    /** 界面上服务商的名字:授权来的 OpenRouter 加一句来历,和用户自己填 Key 的那一家分得开。 */
    fun providerName(p: Provider): String {
        if (p.signIn == ProviderStore.OPENROUTER_SIGN_IN) return tr(R.string.signin_openrouter_name, p.name)
        if (p.name == "Cloudflare Workers AI" && p.baseUrl.contains("/accounts/")) {
            val acc = p.baseUrl.substringAfter("/accounts/").substringBefore("/").trim()
            if (acc.isNotEmpty() && !acc.contains("{")) {
                val shortId = if (acc.length >= 8) "${acc.take(4)}…${acc.takeLast(4)}" else acc
                return "${p.name} ($shortId)"
            }
        }
        return p.name
    }
    val XAI_CODE_TITLE: String get() = tr(R.string.signin_xai_code_title)
    val XAI_CODE_BODY: String get() = tr(R.string.signin_xai_code_body)
    val XAI_CODE_COPY_REOPEN: String get() = tr(R.string.signin_xai_code_copy_reopen)
    val XAI_CODE_WAITING: String get() = tr(R.string.signin_xai_code_waiting)
    val XAI_403_NOTE: String get() = tr(R.string.signin_xai_403_note)
    val SET_MAIN: String get() = tr(R.string.signin_set_main)
    val SET_MAIN_NOTE: String get() = tr(R.string.signin_set_main_note)
    val SIGN_OUT_NOTE: String get() = tr(R.string.signin_sign_out_note)
    /** xAI 的账号页。 */
    const val ACCOUNT_MANAGE_URL = "https://accounts.x.ai"

    fun codeValid(minutes: Int) = tr(R.string.signin_xai_code_valid, minutes)

    /** 「用账号登录」一组里每家下面那一行小字。 */
    fun signInRowLine(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> tr(R.string.signin_row_chatgpt)
        SignInKind.Xai -> tr(R.string.signin_row_xai)
        SignInKind.OpenRouter -> tr(R.string.signin_row_openrouter)
    }

    fun signInTitle(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_TITLE
        SignInKind.Xai -> tr(R.string.signin_xai_title)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_title)
    }

    fun signInBody(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_BODY
        SignInKind.Xai -> tr(R.string.signin_xai_body)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_body)
    }

    /** 登录前要先说清的风险(黄底);没有为 null。 */
    fun signInRisk(kind: SignInKind): String? = if (kind == SignInKind.Xai) tr(R.string.signin_xai_risk) else null

    /** 登录前的补充说明(灰底);没有为 null。 */
    fun signInInfo(kind: SignInKind): String? = when (kind) {
        SignInKind.ChatGpt -> null
        SignInKind.Xai -> tr(R.string.signin_stored_note)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_note)
    }

    fun signInStart(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_CONTINUE
        SignInKind.Xai -> tr(R.string.signin_continue)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_authorize)
    }

    fun signInWaiting(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_WAITING
        SignInKind.Xai -> tr(R.string.signin_xai_starting)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_waiting)
    }

    fun accountManage(kind: SignInKind) = when (kind) {
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_manage)
        else -> tr(R.string.account_manage_xai)
    }

    /** 账号登录详情下面的说明:在用什么、退出后怎样。 */
    fun accountNote(kind: SignInKind, signedIn: Boolean) = when (kind) {
        SignInKind.OpenRouter -> tr(if (signedIn) R.string.signin_openrouter_signed_in_note else R.string.signin_openrouter_signed_out_note)
        else -> tr(if (signedIn) R.string.account_signed_in_note_xai else R.string.account_signed_out_note)
    }

    fun accountFailure(e: AccountSignInException): String = when (e.reason) {
        AccountSignInFailure.Denied -> tr(R.string.account_fail_denied)
        AccountSignInFailure.Expired -> tr(R.string.account_fail_expired)
        AccountSignInFailure.TimedOut -> tr(R.string.account_fail_timed_out)
        AccountSignInFailure.Rejected -> tr(R.string.account_fail_rejected, e.code ?: "unknown")
        AccountSignInFailure.NoSubscription -> tr(R.string.account_fail_no_sub)
        AccountSignInFailure.Network -> tr(R.string.account_fail_network)
        AccountSignInFailure.BadIssuer -> tr(R.string.account_fail_issuer)
    }

    /** 用途卡上的一行:[title] 是模型名([isModel])、「用主模型」「不用」或「未设置」([unset]),[subtitle] 是补充说明。 */
    data class RoleLine(
        val title: String, val subtitle: String, val isModel: Boolean = false, val unset: Boolean = false,
        /** 副标题用「可用」的绿色(语音转文字写「可离线」)。 */
        val subtitleOk: Boolean = false,
    )

    fun roleName(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main)
        ModelRole.Vision -> tr(R.string.role_vision)
        ModelRole.Light -> tr(R.string.role_light)
    }

    /** 这个用途做什么。 */
    fun roleWhat(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_what)
        ModelRole.Vision -> tr(R.string.role_vision_what)
        ModelRole.Light -> tr(R.string.role_light_what)
    }

    fun roleLabel(role: ModelRole) = roleName(role)

    /** 选择页顶上的说明。 */
    fun roleHint(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_hint)
        ModelRole.Vision -> tr(R.string.role_vision_hint)
        ModelRole.Light -> tr(R.string.role_light_hint)
    }

    /** 第四个用途「语音转文字」不在 [ModelRole] 里(它不是对话模型),卡片的名字、图标、右边两行在这里。 */
    val SPEECH_LABEL: String get() = "${tr(R.string.role_speech)} · ${tr(R.string.role_speech_what)}"
    val SPEECH_ICON: ImageVector get() = MikaIcons.Mic

    fun speechLine(ui: ModelSetupUi): RoleLine {
        val s = SpeechOptions.summary(ui.speech, ui.systemSpeech, ui.providers)
        val unset = s.title == UNSET
        return RoleLine(s.title, s.sub.orEmpty(), isModel = !unset && (ui.speech is SpeechChoice.Provider || ui.speech is SpeechChoice.Doubao || ui.speech is SpeechChoice.FunAsr || ui.speech is SpeechChoice.Bailian), unset = unset, subtitleOk = s.subOk)
    }

    /** 主模型备援(项目 3)的卡片图标、名字和右边两行:和用途卡同一套说法。 */
    val FALLBACK_ICON: ImageVector get() = MikaIcons.Refresh
    val FALLBACK_CLEAR: String get() = tr(R.string.main_fallback_clear)

    fun fallbackLabel(role: ModelRole, slot: Int) = "${roleName(role)} ${tr(R.string.main_fallback_name, slot + 1)} · ${tr(R.string.main_fallback_what)}"

    fun fallbackLabel(slot: Int) = fallbackLabel(ModelRole.Main, slot)

    fun fallbackLine(ui: ModelSetupUi, role: ModelRole, slot: Int): RoleLine {
        val cfg = ui.roles.fallbacks(role).getOrNull(slot)?.let { id -> ui.configs.firstOrNull { it.id == id } }
        return if (cfg != null) RoleLine(cfg.model, ui.providerOf(cfg)?.let(::providerName) ?: cfg.name, isModel = true)
        else RoleLine(UNSET, tr(R.string.main_fallback_pick), unset = true)
    }

    fun fallbackLine(ui: ModelSetupUi, slot: Int): RoleLine = fallbackLine(ui, ModelRole.Main, slot)

    /** 第五个用途「声音模型」:朗读/主动说话,和语音转写一样不在 [ModelRole] 里。 */
    val VOICE_LABEL: String get() = "${tr(R.string.role_voice)} · ${tr(R.string.role_voice_what)}"
    val VOICE_ICON: ImageVector get() = MikaIcons.Sound

    fun voiceLine(): RoleLine = RoleLine(tr(R.string.role_voice), tr(R.string.role_voice_what), unset = true)

    /** 第六个用途「图片模型」:同样不在 [ModelRole] 里。入口卡片只说明用途,详细状态在设置页里。 */
    val IMAGE_LABEL: String get() = "${tr(R.string.role_image)} · ${tr(R.string.role_image_what)}"
    val IMAGE_ICON: ImageVector get() = MikaIcons.Photo

    fun imageLine(ui: ModelSetupUi): RoleLine = RoleLine(tr(R.string.role_image), tr(R.string.role_image_what), unset = true)

    fun roleIcon(role: ModelRole): ImageVector = when (role) {
        ModelRole.Main -> MikaIcons.Spark
        ModelRole.Vision -> MikaIcons.Eye
        ModelRole.Light -> MikaIcons.Leaf
    }

    /** 用途卡上写什么:选了模型写模型名和服务商;「用主模型」写主模型是谁;「不用」写只靠文字读屏。 */
    fun roleLine(ui: ModelSetupUi, role: ModelRole): RoleLine {
        fun modelLine(cfg: ProviderConfig) = RoleLine(cfg.model, ui.providerOf(cfg)?.let(::providerName) ?: cfg.name, isModel = true)
        val main = ui.configFor(ModelRole.Main)
        return when (val choice = ui.roles[role]) {
            null -> RoleLine(UNSET, if (ui.providers.isEmpty()) MAIN_ADD_FIRST else MAIN_PICK, unset = true)
            RoleChoice.SameAsMain -> RoleLine(SAME_AS_MAIN, main?.model ?: MAIN_NOT_PICKED)
            RoleChoice.Off -> RoleLine(OFF, OFF_NOTE)
            is RoleChoice.Model -> ui.configs.firstOrNull { it.id == choice.configId }?.let(::modelLine)
                ?: RoleLine(UNSET, MAIN_PICK, unset = true)
        }
    }

    fun choiceTitle(choice: RoleChoice) = if (choice == RoleChoice.Off) OFF else SAME_AS_MAIN

    fun choiceNote(role: ModelRole, choice: RoleChoice) = when {
        choice == RoleChoice.Off -> OFF_NOTE
        role == ModelRole.Vision -> VISION_SAME_NOTE
        else -> LIGHT_SAME_NOTE
    }

    fun roleNames(roles: List<ModelRole>) = roles.joinToString(tr(R.string.list_sep)) { roleName(it) }

    fun deleteTitle(name: String) = tr(R.string.models_delete_title, name)

    /** 删除前说清会影响哪些用途。 */
    fun deleteBody(roles: List<ModelRole>) =
        tr(R.string.models_delete_body) + if (roles.isEmpty()) tr(R.string.models_delete_unused) else tr(R.string.models_delete_used, roleNames(roles))

    fun more(n: Int) = trPlural(R.plurals.models_more, n)

    fun viewAll(n: Int) = trPlural(R.plurals.models_view_all, n)

    fun allModelsTitle(name: String) = tr(R.string.models_all_title, name)

    fun allModelsNote(n: Int) = trPlural(R.plurals.models_all_note, n)

    fun useAsSubtitle(name: String) = tr(R.string.models_use_as, name)

    fun kindLabel(kind: ProviderConfig.Kind) = when (kind) {
        ProviderConfig.Kind.OpenAiCompat -> tr(R.string.models_openai_compat)
        ProviderConfig.Kind.Anthropic -> "Anthropic"
        ProviderConfig.Kind.Gemini -> "Gemini"
        ProviderConfig.Kind.OpenAiResponses -> "OpenAI Responses"
        ProviderConfig.Kind.ChatGpt -> "ChatGPT"
        ProviderConfig.Kind.Xai -> "xAI Responses"
    }

    fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://")

    /** 记住的模型数;还没列过时为空,服务不提供列表时说明。 */
    fun modelCount(p: Provider): String? = when {
        p.models == null -> null
        p.models.isEmpty() -> tr(R.string.models_no_list)
        else -> trPlural(R.plurals.models_count, p.models.count { it.usable })
    }

    /** 服务商一行的说明:已连接 · N 个模型 · 哪些用途在用;刷新失败时说明。 */
    fun providerLine(p: Provider, state: ListState?, roles: List<ModelRole>): String =
        if (state is ListState.Failed) LIST_FAILED
        else listOfNotNull(CONNECTED, modelCount(p), roles.takeIf { it.isNotEmpty() }?.let { tr(R.string.models_used_by, roleNames(it)) }).joinToString(" · ")
}
