package com.wangzan101.mika.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.MODEL_READ_TIMEOUT_SECONDS
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.TagTone
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.settings.MAX_MAIN_FALLBACKS

/**
 * 配置模型:用途(主模型、看图模型、轻量模型)和服务商两块。首次上手时 [onboarding] 为真
 * (服务商在前,底部有分页点和「下一步」);设置 › 模型([ModelsScreen])也是这一页,标题和引导语不同,没有底部按钮。
 * 选用途、服务商详情、服务商的全部模型是这一页里的子页面。上手时「下一步」选好主模型才可点,点了调 [onDone]。
 */
@Composable
fun ModelSetupScreen(
    store: ProviderStore, onboarding: Boolean, onBack: () -> Unit, onDone: () -> Unit,
    /** 设置里点「语音转文字」卡片去选择页;上手流程不传,卡片就不出现。 */
    onOpenSpeech: (() -> Unit)? = null,
    /** 设置里点「声音模型」卡片去设置页;上手流程不传,卡片就不出现。 */
    onOpenVoice: (() -> Unit)? = null,
    /** 设置里查看 Token 用量;上手流程不传。 */
    onOpenUsage: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm = remember {
        val signIns = (context.applicationContext as? MikaApp)?.chatGptSignIn ?: ChatGptSignIns(scope)
        ModelSetupViewModel(
            store, scope, browser = AndroidSignInBrowser(context.applicationContext), signIns = signIns,
            speech = (context.applicationContext as? MikaApp)?.speech,
            systemSpeech = {
                val silent = (context.applicationContext as? MikaApp)?.speech?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
        )
    }
    val ui by vm.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var pickingFallback by rememberSaveable { mutableStateOf<Int?>(null) }
    var openProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var allModels by rememberSaveable { mutableStateOf(false) }
    var openDecision by rememberSaveable { mutableStateOf(false) }
    // 账号登录的面板:在添加表单和服务商详情里都能打开,登录中关掉也不打断登录
    var signInSheet by rememberSaveable { mutableStateOf<SignInKind?>(null) }

    // 用账号登录成功:收起面板,打开这一家的详情(看账号和模型、设主模型)
    LaunchedEffect(ui.signedInProvider) {
        val id = ui.signedInProvider ?: return@LaunchedEffect
        signInSheet = null
        picking = null
        pickingFallback = null
        openProvider = id
        allModels = false
        vm.signedInShown()
    }
    // ChatGPT 登录成功照旧留在这一页(第一次会说明在用套餐额度),面板收起
    LaunchedEffect(ui.chatGptAccount?.signedIn, ui.chatGptSignIn) {
        if (signInSheet == SignInKind.ChatGpt && ui.chatGptAccount?.signedIn == true && ui.chatGptSignIn == ListState.Idle) signInSheet = null
    }

    val provider = openProvider?.let { id -> ui.providers.firstOrNull { it.id == id } }
    val role = picking
    when {
        openDecision -> JevSettingsScreen(remember { DecisionSettings.get(context) }, onBack = { openDecision = false })
        provider != null -> {
            LaunchedEffect(provider.id) { vm.ensureListed() }
            val accountKind = when {
                provider.kind == ProviderConfig.Kind.Xai -> SignInKind.Xai
                provider.signIn == ProviderStore.OPENROUTER_SIGN_IN -> SignInKind.OpenRouter
                else -> null
            }
            val actions = ProviderDetailActions(
                refresh = { vm.refresh(provider.id) }, changeKey = { vm.changeKey(provider.id, it) },
                remove = { vm.removeProvider(provider.id); openProvider = null; allModels = false },
                use = { r, model -> vm.pick(r, provider.id, model) },
                setVision = { model, sees -> vm.setModelVision(provider.id, model, sees) },
                showAll = { allModels = true },
                retest = { vm.retest(provider.id) },
                // ChatGPT 照旧直接去浏览器登录;xAI、OpenRouter 先打开说明面板
                signIn = { if (accountKind != null) signInSheet = accountKind else vm.signInWithChatGpt() },
                signOut = { if (accountKind != null) vm.signOutAccount(provider.id) else vm.signOutChatGpt(provider.id) },
                manageUsage = {
                    openInBrowser(context, when (accountKind) {
                        SignInKind.Xai -> ModelsCopy.ACCOUNT_MANAGE_URL
                        SignInKind.OpenRouter -> OpenRouterAuth.KEYS_PAGE
                        else -> ChatGptAuth.USAGE_URL
                    })
                },
                useAsMain = { vm.useAsMain(provider.id) },
                setDailyBudget = { vm.setDailyTokenBudget(provider.id, it) },
            )
            if (allModels) {
                BackHandler { allModels = false }
                AllModelsContent(ui, provider, actions, onBack = { allModels = false })
            } else {
                BackHandler { openProvider = null }
                ProviderDetailContent(ui, provider, actions, onBack = { openProvider = null })
            }
        }
        pickingFallback != null -> {
            val slot = pickingFallback!!
            BackHandler { pickingFallback = null }
            LaunchedEffect(slot) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, ModelRole.Main,
                RolePickerActions(
                    pick = { pid, model ->
                        // 和选主模型一样:没试通过的那家先试调用,通了才选上、返回
                        if (ui.probeBeforePick(ModelRole.Main, pid)) vm.pickFallbackManual(slot, pid, model) { pickingFallback = null }
                        else { vm.pickFallback(slot, pid, model); pickingFallback = null }
                    },
                    pickManual = { pid, model -> vm.pickFallbackManual(slot, pid, model) { pickingFallback = null } },
                    addProvider = { vm.openForm(); pickingFallback = null },
                ),
                onBack = { pickingFallback = null },
                titleOverride = stringResource(R.string.main_fallback_name, slot + 1),
                hintOverride = stringResource(R.string.main_fallback_hint),
                pickedOverride = { pid, model ->
                    ui.roles.mainFallbacks.getOrNull(slot)?.let { id -> ui.configs.firstOrNull { it.id == id } }
                        ?.let { it.providerId == pid && it.model == model } == true
                },
                // 主模型自己不列在备援选单里:选它当备援没有意义(MK-1 B-D2)。
                exclude = { pid, model ->
                    val mainCfg = ui.configs.firstOrNull { it.id == ui.roles.resolve(ModelRole.Main) }
                    mainCfg != null && mainCfg.providerId == pid && mainCfg.model == model
                },
                clearAction = { vm.clearFallback(slot); pickingFallback = null },
                clearSelected = ui.roles.mainFallbacks.getOrNull(slot) == null,
            )
        }
        role != null -> {
            BackHandler { picking = null }
            LaunchedEffect(role) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model ->
                        // 刚存下、没有一个模型试通过的那家:先试调用,通了才选上、返回
                        if (ui.probeBeforePick(role, pid)) vm.pickManual(role, pid, model) { picking = null }
                        else { vm.pick(role, pid, model); picking = null }
                    },
                    pickManual = { pid, model -> vm.pickManual(role, pid, model) { picking = null } },
                    choose = { vm.choose(role, it); picking = null },
                    addProvider = { vm.openForm(); picking = null },
                ),
                onBack = { picking = null },
            )
        }
        else -> {
            if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
            BackHandler(onBack = onBack)
            ModelSetupContent(
                ui, onboarding,
                ModelSetupActions(
                    selectPreset = vm::selectPreset, setKind = vm::setKind, setBaseUrl = vm::setBaseUrl, setApiKey = vm::setApiKey,
                    setModel = vm::setModel, setManual = vm::setManual, setName = vm::setName,
                    testAndSave = vm::testAndSave, saveUntested = vm::saveWithoutTest,
                    openForm = vm::openForm, closeForm = vm::closeForm,
                    openProvider = { openProvider = it; allModels = false }, pickRole = { picking = it }, pickFallback = { pickingFallback = it }, next = onDone, openSpeech = onOpenSpeech,
                    openVoice = onOpenVoice,
                    openUsage = onOpenUsage,
                    openDecision = { openDecision = true },
                    openSignIn = { signInSheet = it },
                    setReasoningEffort = vm::setReasoningEffort,
                    setModelContext = vm::setModelContext,
                ),
                onBack,
            )
        }
    }

    signInSheet?.let { kind ->
        SignInSheet(
            ui, kind,
            SignInSheetActions(
                start = { k -> k.account?.let(vm::signInWithAccount) ?: vm.signInWithChatGpt() },
                cancel = { k -> if (k.account != null) vm.cancelAccountSignIn() else vm.cancelChatGptSignIn() },
                useApiKey = { k ->
                    signInSheet = null
                    openProvider = null
                    picking = null
                    vm.openForm()
                    k.keyPreset?.let(vm::selectPreset)
                },
                dismiss = {
                    signInSheet = null
                    // 失败的原因看过了;还在登录的不打断
                    if (!ui.accountSignIn.running) vm.dismissAccountFailure()
                },
            ),
        )
    }
}

/** 主页面上的全部动作。 */
class ModelSetupActions(
    val selectPreset: (String) -> Unit = {},
    val setKind: (ProviderConfig.Kind) -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setApiKey: (String) -> Unit = {},
    val setModel: (String) -> Unit = {},
    /** 「手动填写模型名」的开和收。 */
    val setManual: (Boolean) -> Unit = {},
    val setName: (String) -> Unit = {},
    val testAndSave: () -> Unit = {},
    /** 不测试,直接保存(见 [ModelSetupViewModel.saveWithoutTest])。 */
    val saveUntested: () -> Unit = {},
    val openForm: () -> Unit = {},
    val closeForm: () -> Unit = {},
    val openProvider: (String) -> Unit = {},
    val pickRole: (ModelRole) -> Unit = {},
    val pickFallback: (Int) -> Unit = {},
    /** 打开「语音转文字」的选择页;为 null 时不显示这张卡(上手流程)。 */
    val openSpeech: (() -> Unit)? = null,
    /** 打开「声音模型」设置页;为 null 时不显示这张卡(上手流程)。 */
    val openVoice: (() -> Unit)? = null,
    /** 打开 Token 用量;为 null 时不显示入口(上手流程)。 */
    val openUsage: (() -> Unit)? = null,
    /** Independent opt-in decision service; the three model roles remain unchanged. */
    val openDecision: (() -> Unit)? = null,
    val next: () -> Unit = {},
    /** 打开一家账号登录的说明面板。 */
    val openSignIn: (SignInKind) -> Unit = {},
    val setReasoningEffort: (ModelRole, String?) -> Unit = { _, _ -> },
    val setModelContext: (String, ModelContextSettings) -> Boolean = { _, _ -> false },
)

/**
 * 不带状态的主页面,便于测试。改动当场生效(测试通过就存下服务商,用途选完就用),
 * 所以设置里进来只有返回;上手时底部留「下一步」,键盘弹出时藏起来,把地方让给表单。
 */
@Composable
fun ModelSetupContent(ui: ModelSetupUi, onboarding: Boolean, actions: ModelSetupActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    // 表单收起(测试通过存好了,或取消)后页面上没有要打字的地方,顺手收起键盘
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.showForm) { if (!ui.showForm) focus.clearFocus() }
    val onPick: (ModelRole) -> Unit = { role -> if (ui.providers.isEmpty()) actions.openForm() else actions.pickRole(role) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (onboarding) ModelSetupCopy.TITLE else ModelsCopy.TITLE, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (onboarding) {
                    Text(
                        ModelSetupCopy.LEAD, Modifier.padding(top = 4.dp, bottom = 12.dp),
                        fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                    )
                }
                // 上手时先连服务商再选模型;设置里先看用途
                if (onboarding) {
                    ProvidersSection(ui, actions)
                    RolesSection(ui, onPick, actions.setReasoningEffort, null, null, actions.setModelContext, null)
                    FooterNote(ModelSetupCopy.ONBOARDING_NOTE)
                } else {
                    RolesSection(ui, onPick, actions.setReasoningEffort, actions.openSpeech, actions.openVoice, actions.setModelContext, actions.pickFallback)
                    actions.openDecision?.let { openDecision ->
                        GroupCard {
                            ListRow(MikaIcons.Route, stringResource(R.string.jev_title), stringResource(R.string.jev_row_sub),
                                first = true, onClick = openDecision) { RowChevron() }
                        }
                    }
                    ProvidersSection(ui, actions)
                    actions.openUsage?.let { openUsage ->
                        GroupCard {
                            ListRow(MikaIcons.Chart, UsageCopy.TITLE, first = true, onClick = openUsage) { RowChevron() }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // 看键盘实际占的高度;isImeVisible 在系统第一次报告前默认是 true,会把按钮误藏
            if (onboarding && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Model), Modifier.padding(bottom = 14.dp))
                    MikaButton(ModelSetupCopy.NEXT, actions.next, Modifier.fillMaxWidth().testTag("setup-next"), enabled = ui.canNext)
                }
            }
        }
    }
}

/** 服务商:每家一行(写明在给哪些用途干活),加「添加服务商」;没有服务商或点了添加时,表单就摊在这里。 */
@Composable
private fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    if (ui.providers.isNotEmpty()) {
        GroupLabel(ModelsCopy.PROVIDERS)
        GroupCard {
            ui.providers.forEachIndexed { i, p ->
                val line = if (ui.signedOut(p)) ModelsCopy.CHATGPT_SIGNED_OUT else ModelsCopy.providerLine(p, ui.listing[p.id], ui.rolesOf(p))
                // 有用着却没试通过的模型、或跳过测试存下且已经在用:这一行就提醒「没测试通过」
                val warning = ModelsCopy.UNVERIFIED.takeIf {
                    ui.unverified(p).isNotEmpty() || (ui.untested(p) && ui.rolesOf(p).isNotEmpty())
                }
                ProviderRow(p, line, first = i == 0, warning = warning) { actions.openProvider(p.id) }
            }
            if (!ui.showForm) {
                ListRow(
                    MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, titleColor = c.accent, tone = TileTone.Accent,
                    onClick = actions.openForm,
                )
            }
        }
    }
    ui.probeNote?.let { FooterNote(it, Modifier.testTag("setup-probe-note"), color = c.warn) }
    // 刚存下的那家一个模型都没试通过:直接去选主模型(选的时候先试调用)
    if (ui.pickToProbe != null && ui.roles.main == null) {
        Text(
            ModelSetupCopy.PICK_MAIN,
            Modifier.padding(start = 16.dp, bottom = 12.dp).clickable { actions.pickRole(ModelRole.Main) }.testTag("setup-pick-main").padding(vertical = 4.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
        )
    }
    if (ui.showForm) AddProviderForm(ui, actions)
    FooterNote(ModelsCopy.KEY_NOTE)
}

/** 添加服务商:先是「用账号登录」一组(不用填 Key),再是填 Key 的:预设胶囊 → (自定义时)接口类型和地址 → API Key →「测试并保存」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderForm(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    val f = ui.form
    var showKey by remember { mutableStateOf(false) }
    var confirmUntested by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(ModelsCopy.ADD_PROVIDER, Modifier.weight(1f))
        if (ui.providers.isNotEmpty()) {
            Text(
                ModelSetupCopy.CANCEL, Modifier.clickable(onClick = actions.closeForm).padding(horizontal = 4.dp, vertical = 6.dp),
                fontSize = 13.sp, color = c.accent,
            )
        }
    }
    AccountSignInGroup(ui, actions.openSignIn, actions.openProvider)
    GroupLabel(ModelsCopy.KEY_GROUP)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderPresets.all.forEach { p ->
            // 「自定义」按界面语言显示;服务商的名字原样
            SelectChip(if (p == ProviderPresets.custom) stringResource(R.string.preset_custom) else p.label, f.preset == p.label, { actions.selectPreset(p.label) }, Modifier.testTag("preset-${p.label}"))
        }
    }
    GroupCard {
        if (f.isCustom) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ModelSetupCopy.KIND, Modifier.padding(end = 12.dp), style = MikaType.Body, color = c.ink)
                // 四种接口一行放不下时折到下一行
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderConfig.Kind.withApiKey.forEach { k -> SelectChip(ModelsCopy.kindLabel(k), f.kind == k, { actions.setKind(k) }, small = true) }
                }
            }
        }
        if (f.urlEditable) {
            InputRow(
                ModelSetupCopy.BASE_URL, f.baseUrl, actions.setBaseUrl, "https://…", first = !f.isCustom,
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-base-url"),
            )
        }
        if (f.isCustom) {
            // 可不填:提示里直接写出不填时用的名字(地址的主机名)
            InputRow(
                ModelSetupCopy.NAME, f.name, actions.setName,
                if (f.urlProblem == null) f.defaultName else ModelSetupCopy.NAME_HINT,
                modifier = Modifier.testTag("setup-name"),
            )
        }
        InputRow(
            ModelSetupCopy.API_KEY, f.apiKey, actions.setApiKey,
            if (f.keyRequired) ModelSetupCopy.KEY_HINT else ModelSetupCopy.KEY_OPTIONAL, first = !f.urlEditable,
            keyboard = KeyboardType.Password, secret = !showKey, modifier = Modifier.testTag("setup-api-key"),
            onClear = { actions.setApiKey("") },
            clearDescription = stringResource(R.string.model_key_clear),
            clearModifier = Modifier.testTag("setup-api-key-clear"),
        ) {
            Text(if (showKey) stringResource(R.string.hide) else stringResource(R.string.show), Modifier.clickable { showKey = !showKey }, fontSize = 13.sp, color = c.accent)
        }
        if (f.showModel) {
            InputRow(
                ModelSetupCopy.MODEL_NAME, f.model, actions.setModel, ModelSetupCopy.modelExample(f.exampleKind),
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-model"),
            )
        }
    }
    ApiKeyNoticeNote(f.keyNotice)
    // 列表里没有要的模型、或这家不提供列表时,随时可以自己填(没列出模型时那一栏已经在了)
    if (!f.noList) {
        Text(
            if (f.manual) ModelSetupCopy.MANUAL_OFF else ModelSetupCopy.MANUAL_ON,
            Modifier.padding(start = 16.dp, top = 8.dp).clickable { actions.setManual(!f.manual) }.padding(vertical = 4.dp).testTag("setup-manual-model"),
            fontSize = 13.sp, color = c.accent,
        )
    }
    if (f.manual && !f.noList) FooterNote(ModelSetupCopy.MANUAL_NOTE, Modifier.testTag("setup-manual-note"))
    f.urlProblem?.takeIf { f.urlEditable && f.baseUrl.isNotBlank() }?.let { FooterNote(it, color = c.bad) }
    f.suggestedPreset?.let { preset -> PresetSuggestion(f, preset.label) { actions.selectPreset(preset.label) } }
    if (f.noList) FooterNote(if (f.isCustom) ModelSetupCopy.NO_LIST_CUSTOM else ModelSetupCopy.NO_LIST_PRESET, Modifier.testTag("setup-no-list"))
    if (f.isSelfHosted) FooterNote(ModelSetupCopy.SELF_HOSTED_NOTE, Modifier.testTag("setup-self-hosted"))
    f.unencryptedWarning?.let { FooterNote(it, Modifier.testTag("setup-url-unencrypted"), color = c.warn) }
    MikaButton(
        if (f.test == ListState.Running) ModelSetupCopy.TESTING else ModelSetupCopy.TEST_AND_SAVE,
        actions.testAndSave, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("setup-test"),
        kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = f.canTest,
    )
    (f.test as? ListState.Failed)?.let { failed ->
        Text(
            ModelSetupCopy.failed(failed.message), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("setup-test-failed"),
            fontSize = 13.sp, lineHeight = 18.sp, color = c.bad,
        )
    }
    // 试不成又想先存下的:做成按钮,不要藏成一行小字——它是个真实的动作,不是说明
    MikaButton(
        ModelSetupCopy.SAVE_UNTESTED, { confirmUntested = true },
        Modifier.fillMaxWidth().padding(top = 6.dp).testTag("setup-save-untested"),
        kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = f.canSaveUntested,
    )
    if (confirmUntested) MikaDialog(
        onDismissRequest = { confirmUntested = false },
        title = { Text(ModelSetupCopy.SAVE_UNTESTED_TITLE) },
        text = { Text(ModelSetupCopy.SAVE_UNTESTED_BODY) },
        confirmButton = {
            TextButton({
                confirmUntested = false
                actions.saveUntested()
            }) { Text(ModelSetupCopy.SAVE_UNTESTED_CONFIRM) }
        },
        dismissButton = {
            TextButton({ confirmUntested = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
    FooterNote(ModelSetupCopy.TEST_NOTE, Modifier.padding(top = 6.dp))
}

/**
 * API Key 这一栏下面的一句:零宽被去掉时用次要文字色,换行或其它放不进请求头的字符用错误色。
 * 和表单上其它 [FooterNote] 同一套字号。
 */
@Composable
internal fun ApiKeyNoticeNote(notice: ApiKeyText.Notice, tag: String = "setup-key-notice") {
    val c = MikaTheme.colors
    when (notice) {
        ApiKeyText.Notice.None -> Unit
        ApiKeyText.Notice.InvisibleRemoved ->
            FooterNote(ModelSetupCopy.KEY_INVISIBLE_REMOVED, Modifier.testTag(tag), color = c.ink2)
        ApiKeyText.Notice.IllegalCharacters ->
            FooterNote(ModelSetupCopy.KEY_ILLEGAL, Modifier.testTag("$tag-illegal"), color = c.bad)
    }
}

/** 自定义填的是某家的官方地址:建议改用那家的预设(不拦着),接口类型对不上时一并说;点一下就换过去,Key 留着。 */
@Composable
private fun PresetSuggestion(f: AddForm, label: String, onUse: () -> Unit) {
    val c = MikaTheme.colors
    FooterNote(ModelSetupCopy.presetSuggestion(f), Modifier.padding(top = 2.dp).testTag("setup-preset-hint"), color = c.warn)
    Text(
        ModelSetupCopy.usePreset(label),
        Modifier.padding(start = 16.dp, bottom = 12.dp).clickable(onClick = onUse).testTag("setup-use-preset").padding(vertical = 4.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
    )
}

/** 第一次用 ChatGPT 登录成功后说明一次:Mika 里的用量走你的 ChatGPT 套餐。 */
@Composable
private fun ChatGptWelcome(onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelsCopy.CHATGPT_WELCOME_TITLE) },
        text = { Text(ModelsCopy.CHATGPT_WELCOME_BODY) },
        confirmButton = { TextButton(onDismiss) { Text(ModelsCopy.CHATGPT_GOT_IT) } },
    )
}

/**
 * 登录要用的系统能力:授权页用系统浏览器打开;回调页(换完令牌之后才给出)写成功或失败的原因,再放一个回到 Mika 的链接(intent 链接,点了由浏览器打开 Mika);
 * 登录完(成功或失败)把 Mika 带回前台。安卓限制后台打开界面,带不回来时用户点回调页的链接或从最近任务回来。
 */
private class AndroidSignInBrowser(private val context: Context) : SignInBrowser {
    override fun open(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.chatgpt_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    override fun returnToApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun page(failure: String?): String {
        val text = failure ?: tr(R.string.chatgpt_page_done)
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"
        return SignInPage.html(tr(R.string.chatgpt_page_title), text, tr(R.string.chatgpt_page_back), back)
    }
}

/** 回调后浏览器里那一页:一句话加一个回到 App 的按钮。文字转义后放进去。 */
internal object SignInPage {
    fun html(title: String, text: String, back: String, backUrl: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>${esc(title)}</title><style>body{font-family:sans-serif;margin:0;padding:48px 24px;text-align:center;color:#1d1d1f;background:#fff}""" +
            """p{font-size:17px;line-height:1.5}a{display:inline-block;margin-top:16px;padding:12px 28px;border-radius:999px;background:#1d1d1f;""" +
            """color:#fff;text-decoration:none;font-size:16px}</style></head><body><p>${esc(text)}</p><a href="${esc(backUrl)}">${esc(back)}</a></body></html>"""
    }
}

/** 用途:主模型、看图模型、轻量模型各一张卡,点开是选择页。 */
@Composable
private fun RolesSection(ui: ModelSetupUi, onPick: (ModelRole) -> Unit, onReasoning: (ModelRole, String?) -> Unit,
    onSpeech: (() -> Unit)?, onVoice: (() -> Unit)?, onContext: ((String, ModelContextSettings) -> Boolean)? = null,
    onPickFallback: ((Int) -> Unit)? = null,
) {
    GroupLabel(ModelsCopy.ROLES)
    GroupCard {
        ModelRole.entries.forEachIndexed { i, role ->
            RoleCard(ModelsCopy.roleIcon(role), ModelsCopy.roleLabel(role), ModelsCopy.roleLine(ui, role), first = i == 0, tag = "role-${role.name.lowercase()}") {
                onPick(role)
            }
            if (ui.roles[role] == RoleChoice.SameAsMain) {
                ReasoningControl(ui, role) { onReasoning(role, it) }
            } else {
                val reasoning = hasReasoningAction(ui, role)
                val context = onContext != null && hasModelContextControl(ui, role)
                if (reasoning || context) {
                    Column(Modifier.padding(start = 70.dp, end = 16.dp)) {
                        Hairline(start = 0.dp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (reasoning) ReasoningControl(ui, role, Modifier.weight(1f)) { onReasoning(role, it) }
                            if (reasoning && context) Box(Modifier.width(0.5.dp).height(14.dp).background(MikaTheme.colors.hair))
                            if (onContext != null && context) ModelContextControl(ui, role, onContext, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // 主模型的备援(项目 3):主模型不能用时按顺序接手;上手流程不传,就不出现
        if (onPickFallback != null) {
            for (slot in 0 until MAX_MAIN_FALLBACKS) {
                RoleCard(ModelsCopy.FALLBACK_ICON, ModelsCopy.fallbackLabel(slot), ModelsCopy.fallbackLine(ui, slot), tag = "role-fallback-$slot") {
                    onPickFallback(slot)
                }
            }
        }
        // 第四个用途:语音转文字(不是对话模型,点开是单独的选择页)
        if (onSpeech != null) {
            RoleCard(ModelsCopy.SPEECH_ICON, ModelsCopy.SPEECH_LABEL, ModelsCopy.speechLine(ui), tag = "role-speech", onClick = onSpeech)
        }
        // 第五个用途:声音模型(朗读/说话,同样独立)
        if (onVoice != null) {
            RoleCard(ModelsCopy.VOICE_ICON, ModelsCopy.VOICE_LABEL, ModelsCopy.voiceLine(), tag = "role-voice", onClick = onVoice)
        }
    }
}

private fun hasModelContextControl(ui: ModelSetupUi, role: ModelRole): Boolean =
    ui.roles[role] is RoleChoice.Model && ui.configFor(role) != null

@Composable
private fun ModelContextControl(ui: ModelSetupUi, role: ModelRole,
    onSave: (String, ModelContextSettings) -> Boolean, modifier: Modifier = Modifier,
) {
    if (ui.roles[role] !is RoleChoice.Model) return
    val cfg = ui.configFor(role) ?: return
    val saved = ui.providerOf(cfg)?.contextSettings?.get(cfg.model) ?: ModelContextSettings()
    var open by rememberSaveable(cfg.id) { mutableStateOf(false) }
    ModelOptionButton(stringResource(R.string.model_context_title), "context-${role.name.lowercase()}", modifier) { open = true }
    if (!open) return
    var window by rememberSaveable(cfg.id) { mutableStateOf(saved.windowTokens?.toString().orEmpty()) }
    var trigger by rememberSaveable(cfg.id) { mutableStateOf(saved.triggerTokens?.toString().orEmpty()) }
    var readTimeout by rememberSaveable(cfg.id) { mutableStateOf(saved.readTimeoutSeconds?.toString().orEmpty()) }
    var saveFailed by remember { mutableStateOf(false) }
    fun positiveInt(text: String) = text.trim().takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
    val settings = ModelContextSettings(positiveInt(window), positiveInt(trigger),
        positiveInt(readTimeout)?.takeIf { it in MODEL_READ_TIMEOUT_SECONDS })
    val modelWindow = cfg.capabilities.contextTokens
    val limits = settings.limits(modelWindow)
    val badWindow = window.isNotBlank() && settings.windowTokens == null
    val badTrigger = trigger.isNotBlank() && (settings.triggerTokens == null || (!badWindow && settings.triggerTokens != limits.trigger))
    val badReadTimeout = readTimeout.isNotBlank() && settings.readTimeoutSeconds == null
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    MikaDialog(
        onDismissRequest = { open = false },
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.model_context_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(cfg.model)
                Text(stringResource(R.string.model_context_note))
                OutlinedTextField(window, { window = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-window"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_window)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, modelWindow)) },
                    supportingText = if (badWindow) ({ Text(stringResource(R.string.model_context_integer)) }) else null,
                    isError = badWindow)
                OutlinedTextField(trigger, { trigger = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-trigger"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_trigger)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, limits.trigger)) },
                    supportingText = {
                        if (!badWindow && limits.usable > 0) Text(stringResource(R.string.model_context_range,
                            limits.minTrigger.coerceAtLeast(1), limits.usable))
                        else if (badTrigger) Text(stringResource(R.string.model_context_integer))
                    }, isError = badTrigger)
                OutlinedTextField(readTimeout, { readTimeout = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-read-timeout"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_read_timeout)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, 120)) },
                    supportingText = if (badReadTimeout) ({
                        Text("${stringResource(R.string.model_context_integer)} (1–${MODEL_READ_TIMEOUT_SECONDS.last})")
                    }) else null,
                    isError = badReadTimeout)
                if (!badWindow && (trigger.isBlank() || settings.triggerTokens != null)) Text(stringResource(R.string.model_context_effective, limits.window, limits.trigger),
                    modifier = Modifier.testTag("context-effective"))
                TextButton(onClick = { window = ""; trigger = ""; readTimeout = ""; saveFailed = false }) { Text(stringResource(R.string.reset)) }
                if (saveFailed) Text(stringResource(R.string.model_context_save_failed), color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (onSave(cfg.id, settings)) open = false else saveFailed = true
        }, enabled = !badWindow && !badTrigger && !badReadTimeout, modifier = Modifier.testTag("context-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 模型的能力标签;[accent] 为假时一律中性色(列表行里强调色留给「在用」的胶囊)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(o: ModelOption, forVision: Boolean = false, accent: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSetupCopy.tags(o, forVision).forEach { (text, strong) -> CapabilityTag(text, if (strong && accent) TagTone.Accent else TagTone.Neutral) }
    }
}

internal val ModelIdStyle = MikaType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

/** 配置模型页(上手时)和添加服务商表单的文字(纯函数,便于测试)。用途的说法在 [ModelsCopy]。 */
object ModelSetupCopy {
    val TITLE: String get() = tr(R.string.model_setup_title)
    val LEAD: String get() = tr(R.string.model_setup_lead)
    val ONBOARDING_NOTE: String get() = tr(R.string.model_setup_onboarding_note)
    val CANCEL: String get() = tr(R.string.cancel)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.model_base_url)
    val NAME: String get() = tr(R.string.model_provider_name)
    val NAME_HINT: String get() = tr(R.string.model_provider_name_hint)
    const val API_KEY = "API Key"
    val KEY_HINT: String get() = tr(R.string.model_key_hint)
    val KEY_OPTIONAL: String get() = tr(R.string.model_key_optional)
    val KEY_INVISIBLE_REMOVED: String get() = tr(R.string.model_key_invisible_removed)
    val KEY_ILLEGAL: String get() = tr(R.string.model_key_illegal_chars)
    val TEST_AND_SAVE: String get() = tr(R.string.model_test_save)
    val TESTING: String get() = tr(R.string.model_testing)
    val PICK_MAIN: String get() = tr(R.string.model_pick_main)
    val TEST_NOTE: String get() = tr(R.string.model_test_note)
    val SELF_HOSTED_NOTE: String get() = tr(R.string.model_self_hosted_note)
    val MODEL_NAME: String get() = tr(R.string.model_manual_name)
    val NO_LIST_CUSTOM: String get() = tr(R.string.model_no_list_custom)
    val NO_LIST_PRESET: String get() = tr(R.string.model_no_list_preset)
    val MANUAL_ON: String get() = tr(R.string.model_manual_on)
    val MANUAL_OFF: String get() = tr(R.string.model_manual_off)
    val MANUAL_NOTE: String get() = tr(R.string.model_manual_note)
    val SAVE_UNTESTED: String get() = tr(R.string.model_save_untested)
    val SAVE_UNTESTED_TITLE: String get() = tr(R.string.model_save_untested_title)
    val SAVE_UNTESTED_BODY: String get() = tr(R.string.model_save_untested_body)
    val SAVE_UNTESTED_CONFIRM: String get() = tr(R.string.model_save_untested_confirm)

    /** 手填模型名时举的例子,按接口类型(各家模型名的样子不同)。 */
    fun modelExample(kind: ProviderConfig.Kind): String = tr(
        when (kind) {
            ProviderConfig.Kind.Gemini -> R.string.model_example_gemini
            ProviderConfig.Kind.Anthropic -> R.string.model_example_anthropic
            ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt -> R.string.model_example_openai
            ProviderConfig.Kind.Xai -> R.string.model_example_grok
            ProviderConfig.Kind.OpenAiCompat -> R.string.model_example_compat
        },
    )

    /** 自定义地址是某家官方地址时的建议;接口类型和那家对不上时补一句。 */
    fun presetSuggestion(f: AddForm): String {
        val preset = f.suggestedPreset ?: return ""
        val match = tr(R.string.model_preset_match, preset.label)
        return if (!f.presetKindMismatch) match
        else match + tr(R.string.sentence_sep) + tr(R.string.model_preset_kind_mismatch, preset.label, ModelsCopy.kindLabel(preset.kind), ModelsCopy.kindLabel(f.kind))
    }

    fun usePreset(label: String) = tr(R.string.model_use_preset, label)
    val NEXT: String get() = tr(R.string.next)

    fun failed(reason: String) = tr(R.string.model_failed, reason)

    /** 模型的能力标签:(文字, 是否强调色)。接口没给、用户也没说、按预设推断的标「未确认」。只作参考,不拦选择。 */
    fun tags(o: ModelOption, forVision: Boolean = false): List<Pair<String, Boolean>> = buildList {
        val caps = o.capabilities
        val visionKnown = o.visionKnown || o.visionByUser != null
        if (o.typed) add(tr(R.string.cap_typed) to false)
        if (forVision) {
            add((if (visionKnown) tr(R.string.cap_vision) else tr(R.string.cap_vision_unconfirmed)) to false)
        } else {
            when {
                !caps.toolCalls -> add(tr(R.string.cap_no_tools) to false)
                o.toolCallsKnown -> add(tr(R.string.cap_tools) to true)
                else -> add(tr(R.string.cap_tools_unconfirmed) to false)
            }
            if (caps.vision && visionKnown) add(tr(R.string.cap_vision) to false)
        }
        if (caps.reasoning) add(tr(R.string.cap_reasoning) to false)
        if (o.contextKnown) add(ModelOptions.contextLabel(caps.contextTokens) to false)
    }
}
