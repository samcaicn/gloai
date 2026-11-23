package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.settings.ui.JevSettingsScreen
import com.wangzan101.mika.settings.ui.UsageAllowanceCard
import com.wangzan101.mika.settings.ui.UsageCopy
import com.wangzan101.mika.setup.ui.Onboarding
import com.wangzan101.mika.setup.ui.OnboardingStep
import com.wangzan101.mika.ui.NavSlide
import com.wangzan101.mika.ui.Route
import com.wangzan101.mika.ui.openInBrowser
import com.wangzan101.mika.voice.ui.VoiceHubSection
import com.wangzan101.mika.voice.ui.voiceState

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
    /** 设置里点「图片模型」卡片去设置页;上手流程不传,卡片就不出现。 */
    onOpenImage: (() -> Unit)? = null,
    /** 设置里查看 Token 用量;上手流程不传。 */
    onOpenUsage: (() -> Unit)? = null,
    /** 设置里打开统一语音模型目录;上手流程不传。 */
    onOpenVoiceCatalog: (() -> Unit)? = null,
    /** 设置里打开「语音通话」选择页;上手流程不传。 */
    onOpenCallMode: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm = remember {
        val signIns = (context.applicationContext as? MikaApp)?.chatGptSignIn ?: ChatGptSignIns(scope)
        val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(context.applicationContext)
        val voiceAccounts = voiceModels.credentialStore?.let {
            com.wangzan101.mika.voice.model.VoiceAccountManager(voiceModels.catalog, it)
        }
        ModelSetupViewModel(
            store, scope, browser = AndroidSignInBrowser(context.applicationContext), signIns = signIns,
            speech = (context.applicationContext as? MikaApp)?.speech,
            systemSpeech = {
                val silent = (context.applicationContext as? MikaApp)?.speech?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
            voiceAccounts = voiceAccounts,
        )
    }
    val ui by vm.state.collectAsState()
    val voiceSettings = if (onOpenVoice != null) (context.applicationContext as? VoiceSettings.Owner)?.voiceSettings else null
    val voiceState = voiceSettings?.state?.collectAsState()?.value
    val voiceChoice = voiceState?.choice
    // 子页里改了朗读/通话的选择,回到这页要马上看到,所以直接跟目录走,不等 vm 下次同步
    val liveVoiceCatalog = if (onOpenCallMode != null) {
        remember { com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(context) }.catalog.state.collectAsState().value
    } else null
    var picking by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var pickingFallbackRole by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var pickingFallbackSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var groupsOpen by rememberSaveable { mutableStateOf(false) }
    // "" 表示新建,null 表示不在编辑组
    var groupEditing by rememberSaveable { mutableStateOf<String?>(null) }
    var openProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var allModels by rememberSaveable { mutableStateOf(false) }
    var openDecision by rememberSaveable { mutableStateOf(false) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    // 账号登录的面板:在添加表单和服务商详情里都能打开,登录中关掉也不打断登录
    var signInSheet by rememberSaveable { mutableStateOf<SignInKind?>(null) }

    // 用账号登录成功:收起面板,打开这一家的详情(看账号和模型、设主模型)
    LaunchedEffect(ui.signedInProvider) {
        val id = ui.signedInProvider ?: return@LaunchedEffect
        signInSheet = null
        if (picking == null && pickingFallbackSlot == null) {
            openProvider = id
            allModels = false
            showProviders = false
        }
        vm.signedInShown()
    }
    // ChatGPT 登录成功照旧留在这一页(第一次会说明在用套餐额度),面板收起
    LaunchedEffect(ui.chatGptAccount?.signedIn, ui.chatGptSignIn) {
        if (signInSheet == SignInKind.ChatGpt && ui.chatGptAccount?.signedIn == true && ui.chatGptSignIn == ListState.Idle) signInSheet = null
    }

    val provider = openProvider?.let { id -> ui.providers.firstOrNull { it.id == id } }
    val role = picking
    val actions = ModelSetupActions(
        selectPreset = vm::selectPreset, setKind = vm::setKind, setBaseUrl = vm::setBaseUrl, setAccountId = vm::setAccountId, setApiKey = vm::setApiKey,
        setModel = vm::setModel, setManual = vm::setManual, setName = vm::setName,
        testAndSave = vm::testAndSave,
        saveUntested = vm::saveWithoutTest,
        openForm = vm::openForm, closeForm = vm::closeForm,
        // 从服务商页面进详情时留着 showProviders:返回落回服务商页面,不是模型页
        openProvider = {
            if (ui.formOpen) vm.closeForm()
            openProvider = it
            allModels = false
        }, pickRole = { picking = it },
        pickFallback = { pickingFallbackRole = ModelRole.Main; pickingFallbackSlot = it },
        pickRoleFallback = { r, s -> pickingFallbackRole = r; pickingFallbackSlot = s },
        clearFallback = vm::clearFallback,
        openProviders = { showProviders = true },
        openGroups = { groupsOpen = true },
        next = onDone, openSpeech = onOpenSpeech,
        openVoice = onOpenVoice, openUsage = onOpenUsage,
        openVoiceCatalog = onOpenVoiceCatalog, openCallMode = onOpenCallMode,
        openDecision = { openDecision = true }, openSignIn = { signInSheet = it },
        setReasoningEffort = vm::setReasoningEffort, setModelContext = vm::setModelContext,
    )
    // 内部子页:标识稳定(换数据不重演动画)、带着层级(决定滑动方向)
    val origin: ModelSubPage = when {
        openDecision -> ModelSubPage.Decision
        groupEditing != null -> ModelSubPage.GroupEdit(groupEditing ?: "")
        groupsOpen -> ModelSubPage.Groups
        provider != null && allModels -> ModelSubPage.AllModels(provider.id, showProviders)
        provider != null -> ModelSubPage.Detail(provider.id, showProviders)
        showProviders -> ModelSubPage.Providers
        pickingFallbackSlot != null -> ModelSubPage.FallbackPick(pickingFallbackRole ?: ModelRole.Main, pickingFallbackSlot!!)
        role != null -> ModelSubPage.RolePick(role)
        else -> ModelSubPage.Main
    }
    val subPage = if (ui.formOpen) ModelSubPage.AddProvider(origin.depth + 1) else origin
    // 换页动画期间旧页还在渲染:服务商刚删掉时详情页也能从缓存里拿到数据画完退出
    var heldProviders by remember { mutableStateOf(ui.providers) }
    if (ui.providers.isNotEmpty()) heldProviders = ui.providers
    NavSlide(subPage, depth = { it.depth }) { page ->
    when (page) {
        is ModelSubPage.AddProvider -> {
            BackHandler(onBack = vm::closeForm)
            Page(ModelsCopy.ADD_PROVIDER, onBack = vm::closeForm) {
                AddProviderForm(ui, actions, standalone = true)
            }
        }
        is ModelSubPage.Decision -> JevSettingsScreen(remember { DecisionSettings.get(context) }, onBack = { openDecision = false })
        is ModelSubPage.Detail, is ModelSubPage.AllModels -> {
            val pid = if (page is ModelSubPage.Detail) page.id else (page as ModelSubPage.AllModels).id
            val p = ui.providers.firstOrNull { it.id == pid } ?: heldProviders.firstOrNull { it.id == pid }
            if (p != null) {
                LaunchedEffect(p.id) { vm.ensureListed() }
                val accountKind = when {
                    p.kind == ProviderConfig.Kind.Xai -> SignInKind.Xai
                    p.signIn == ProviderStore.OPENROUTER_SIGN_IN -> SignInKind.OpenRouter
                    else -> null
                }
                val detailActions = ProviderDetailActions(
                    refresh = { vm.refresh(p.id) }, changeKey = { vm.changeKey(p.id, it) },
                    addKey = { vm.addProviderKey(p.id, it) },
                    removeKey = { vm.removeProviderKey(p.id, it) },
                    remove = { vm.removeProvider(p.id); openProvider = null; allModels = false },
                    use = { r, model, done -> vm.useFromProvider(r, p.id, model, done) },
                    clearPick = vm::clearManualPick,
                    setVision = { model, sees -> vm.setModelVision(p.id, model, sees) },
                    setServiceTier = { model, tier -> vm.setModelServiceTier(p.id, model, tier) },
                    showAll = { allModels = true },
                    retestModel = { model -> vm.retestModel(p.id, model) },
                    voiceUsage = vm::providerVoiceUsage,
                    // ChatGPT 照旧直接去浏览器登录;xAI、OpenRouter 先打开说明面板
                    signIn = { if (accountKind != null) signInSheet = accountKind else vm.signInWithChatGpt() },
                    signOut = { if (accountKind != null) vm.signOutAccount(p.id) else vm.signOutChatGpt(p.id) },
                    manageUsage = {
                        openInBrowser(context, when (accountKind) {
                            SignInKind.Xai -> ModelsCopy.ACCOUNT_MANAGE_URL
                            SignInKind.OpenRouter -> OpenRouterAuth.KEYS_PAGE
                            else -> ChatGptAuth.USAGE_URL
                        })
                    },
                    useAsMain = { vm.useAsMain(p.id) },
                    setDailyBudget = { vm.setDailyTokenBudget(p.id, it) },
                )
                if (page is ModelSubPage.AllModels) {
                    BackHandler { allModels = false }
                    AllModelsContent(ui, p, detailActions, onBack = { allModels = false })
                } else {
                    BackHandler { openProvider = null }
                    ProviderDetailContent(ui, p, detailActions, onBack = { openProvider = null })
                }
            }
        }
        is ModelSubPage.Groups -> {
            BackHandler { groupsOpen = false }
            ModelGroupsScreen(
                ui, onBack = { groupsOpen = false },
                onNew = { groupEditing = "" },
                onEdit = { groupEditing = it },
            )
        }
        is ModelSubPage.GroupEdit -> {
            BackHandler { groupEditing = null }
            ModelGroupEditorScreen(
                ui, page.id.takeIf { it.isNotEmpty() },
                onBack = { groupEditing = null },
                onSave = { vm.saveGroup(it) },
                onDelete = { vm.deleteGroup(it) },
                ensureConfig = { p, m -> vm.ensureConfig(p, m) },
                onSetContext = vm::setModelContext,
            )
        }
        is ModelSubPage.Providers -> {
            BackHandler { showProviders = false }
            Page(ModelsCopy.PROVIDERS, onBack = { showProviders = false }) {
                ProvidersSection(ui, actions)
                // 各服务商的当前额度跟着服务商走,不放在 Token 用量页
                UsageAllowanceCard(ui.providers, store, onOpenUrl = { openInBrowser(context, it) })
            }
        }
        is ModelSubPage.FallbackPick -> {
            val role = page.role
            val slot = page.slot
            BackHandler { pickingFallbackSlot = null; pickingFallbackRole = null }
            LaunchedEffect(role, slot) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model ->
                        vm.pickFallback(role, slot, pid, model) {
                            pickingFallbackSlot = null
                            pickingFallbackRole = null
                        }
                    },
                    pickManual = { pid, model ->
                        vm.pickFallbackManual(role, slot, pid, model) {
                            pickingFallbackSlot = null
                            pickingFallbackRole = null
                        }
                    },
                    addProvider = vm::openForm,
                ),
                onBack = { pickingFallbackSlot = null; pickingFallbackRole = null },
                titleOverride = ModelsCopy.fallbackLabel(role, slot),
                hintOverride = "",
                pickedOverride = { pid, model ->
                    ui.roles.fallbacks(role).getOrNull(slot)?.let { id -> ui.configs.firstOrNull { it.id == id } }
                        ?.let { it.providerId == pid && it.model == model } == true
                },
                exclude = { pid, model ->
                    val primaryCfg = ui.configs.firstOrNull { it.id == ui.roles.resolve(role) }
                    primaryCfg != null && primaryCfg.providerId == pid && primaryCfg.model == model
                },
                clearAction = { vm.clearFallback(role, slot); pickingFallbackSlot = null; pickingFallbackRole = null },
                clearSelected = ui.roles.fallbacks(role).getOrNull(slot) == null,
            )
        }
        is ModelSubPage.RolePick -> {
            val role = page.role
            BackHandler { picking = null }
            LaunchedEffect(role) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model -> vm.pick(role, pid, model) { picking = null } },
                    pickManual = { pid, model -> vm.pickManual(role, pid, model) { picking = null } },
                    choose = { vm.choose(role, it); picking = null },
                    chooseGroup = { vm.chooseGroup(role, it); picking = null },
                    manageGroups = { picking = null; groupsOpen = true },
                    addProvider = vm::openForm,
                ),
                onBack = { picking = null },
            )
        }
        is ModelSubPage.Main -> {
            if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
            BackHandler(onBack = onBack)
            ModelSetupContent(ui, onboarding, actions, voiceChoice = voiceChoice, readAloudOn = voiceState?.enabled ?: false,
                liveVoiceCatalog = liveVoiceCatalog, onBack = onBack)
        }
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

/**
 * 模型页内部的子页:标识用稳定值(服务商 id、用途、槽位),数据变了不重演换页动画;
 * [depth] 决定滑动方向——主页 0,服务商列表和各选择页 1,详情 1(主页直进)或 2(从服务商列表进),全部模型再深一层。
 */
private sealed class ModelSubPage(val depth: Int) {
    data object Main : ModelSubPage(0)
    data object Decision : ModelSubPage(1)
    data object Providers : ModelSubPage(1)
    data object Groups : ModelSubPage(1)
    /** id 为空串表示新建组。 */
    data class GroupEdit(val id: String) : ModelSubPage(2)
    data class AddProvider(val level: Int) : ModelSubPage(level)
    data class Detail(val id: String, val fromProviders: Boolean) : ModelSubPage(if (fromProviders) 2 else 1)
    data class AllModels(val id: String, val fromProviders: Boolean) : ModelSubPage(if (fromProviders) 3 else 2)
    data class FallbackPick(val role: ModelRole, val slot: Int) : ModelSubPage(1)
    data class RolePick(val role: ModelRole) : ModelSubPage(1)
}

/** 主页面上的全部动作。 */
class ModelSetupActions(
    val selectPreset: (String) -> Unit = {},
    val setKind: (ProviderConfig.Kind) -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setAccountId: (String) -> Unit = {},
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
    val pickRoleFallback: (ModelRole, Int) -> Unit = { _, _ -> },
    val clearFallback: (ModelRole, Int) -> Unit = { _, _ -> },
    /** 打开「服务商」页(已连接的服务商 + 添加表单)。 */
    val openProviders: () -> Unit = {},
    /** 打开「语音转文字」的选择页;为 null 时不显示这张卡(上手流程)。 */
    val openSpeech: (() -> Unit)? = null,
    /** 打开「声音模型」设置页;为 null 时不显示这张卡(上手流程)。 */
    val openVoice: (() -> Unit)? = null,
    /** 打开「图片模型」设置页;为 null 时不显示这张卡(上手流程)。 */
    val openImage: (() -> Unit)? = null,
    /** 打开 Token 用量;为 null 时不显示入口(上手流程)。 */
    val openUsage: (() -> Unit)? = null,
    /** 打开统一语音模型目录;为 null 时不显示入口(上手流程)。 */
    val openVoiceCatalog: (() -> Unit)? = null,
    /** 打开「语音通话」选择页;为 null 时不显示(上手流程)。 */
    val openCallMode: (() -> Unit)? = null,
    /** Independent opt-in decision service; the three model roles remain unchanged. */
    val openDecision: (() -> Unit)? = null,
    val next: () -> Unit = {},
    /** 打开一家账号登录的说明面板。 */
    val openSignIn: (SignInKind) -> Unit = {},
    /** 打开模型组管理页;上手流程不显示入口。 */
    val openGroups: () -> Unit = {},
    val setReasoningEffort: (ModelRole, String?) -> Unit = { _, _ -> },
    val setModelContext: (String, ModelContextSettings) -> Boolean = { _, _ -> false },
)

/**
 * 不带状态的主页面,便于测试。改动当场生效(测试通过就存下服务商,用途选完就用),
 * 所以设置里进来只有返回;上手时底部留「下一步」,键盘弹出时藏起来,把地方让给表单。
 */
@Composable
fun ModelSetupContent(
    ui: ModelSetupUi, onboarding: Boolean, actions: ModelSetupActions,
    voiceChoice: VoiceChoice? = null, readAloudOn: Boolean = false,
    liveVoiceCatalog: VoiceCatalogSnapshot? = null, onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    // 表单收起(测试通过存好了,或取消)后页面上没有要打字的地方,顺手收起键盘
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.showForm) { if (!ui.showForm) focus.clearFocus() }
    val onPick = actions.pickRole
    val scrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(initial = 0)
    }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (onboarding) ModelSetupCopy.TITLE else ModelsCopy.TITLE, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 16.dp)) {
                if (onboarding) {
                    Text(
                        ModelSetupCopy.LEAD, Modifier.padding(top = 4.dp, bottom = 12.dp),
                        style = MikaType.Body, color = c.ink2,
                    )
                }
                // 上手时先连服务商再选模型;设置里先看用途
                if (onboarding) {
                    ProvidersSection(ui, actions)
                    // 上手时不放「上下文与压缩」,设置里再调
                    RolesSection(ui, onPick, actions.setReasoningEffort)
                    FooterNote(ModelSetupCopy.ONBOARDING_NOTE)
                } else {
                    // Providers entry — show count + total key count in subtitle
                    val totalKeys = ui.providers.sumOf { p ->
                        ui.providerKeyMap[p.id]?.size ?: 0
                    }
                    val providerSubtitle = when {
                        ui.providers.isEmpty() -> "No providers connected"
                        totalKeys > 1 -> "${ui.providers.size} providers · $totalKeys keys in pool"
                        else -> "${ui.providers.size} provider${if (ui.providers.size > 1) "s" else ""}"
                    }
                    GroupCard {
                        ListRow(MikaIcons.Key, ModelsCopy.PROVIDERS, providerSubtitle, first = true, onClick = actions.openProviders) {
                            RowChevron()
                        }
                    }
                    actions.openUsage?.let { openUsage ->
                        GroupCard {
                            ListRow(MikaIcons.Chart, UsageCopy.TITLE, first = true, onClick = openUsage) { RowChevron() }
                        }
                    }
                    // 存下服务商、推荐的模型没试通过时的说明和「选主模型」(上手时在服务商一组下面)
                    ProbeNote(ui, actions)
                    // 迁移到语音目录后,三种用途和来源账号收成一组「语音」;迁移前仍是旧的分散入口
                    // 设置页的 vm 不带语音控制器,迁移与否直接看目录本身
                    val voiceCatalog = liveVoiceCatalog?.takeIf { it.legacyMigrationComplete } ?: ui.sttCatalog
                    val hubOpen = voiceCatalog != null && actions.openSpeech != null && actions.openVoice != null &&
                        actions.openCallMode != null && actions.openVoiceCatalog != null
                    if (voiceCatalog != null && hubOpen) {
                        RolesSection(ui, onPick, actions.setReasoningEffort, actions.setModelContext)
                        ImageModelSection(ui, actions.openImage)
                        VoiceHubSection(
                            voiceCatalog, ui.providers, readAloudOn,
                            onStt = actions.openSpeech!!, onTts = actions.openVoice!!,
                            onCallMode = actions.openCallMode!!, onServices = actions.openVoiceCatalog!!,
                        )
                    } else {
                        RolesSection(ui, onPick, actions.setReasoningEffort, actions.setModelContext, actions.openVoice, voiceChoice)
                        SpeechSection(ui, actions.openSpeech)
                        ImageModelSection(ui, actions.openImage)
                        VoiceCatalogSection(actions.openVoiceCatalog)
                    }
                    GroupCard(Modifier.padding(top = 8.dp)) {
                        ListRow(MikaIcons.Leaf, ModelsCopy.GROUPS, first = true, onClick = actions.openGroups) { RowChevron() }
                    }
                    actions.openDecision?.let { openDecision ->
                        GroupCard {
                            ListRow(MikaIcons.Route, stringResource(R.string.jev_title), stringResource(R.string.jev_row_sub),
                                first = true, onClick = openDecision) { RowChevron() }
                        }
                    }
                    FallbackSection(ui, ModelRole.Main, actions.pickRoleFallback, actions.clearFallback)
                    FallbackSection(ui, ModelRole.Vision, actions.pickRoleFallback, actions.clearFallback)
                    FallbackSection(ui, ModelRole.Light, actions.pickRoleFallback, actions.clearFallback)
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
    /** 填 Key 的大输入框:可以一次贴多把,逗号或换行分开,自动轮换。 */
    val KEY_POOL_HINT: String get() = tr(R.string.model_key_pool_hint)
    val KEY_INVISIBLE_REMOVED: String get() = tr(R.string.model_key_invisible_removed)
    val KEY_ILLEGAL: String get() = tr(R.string.model_key_illegal_chars)
    val KEY_WEBSITE: String get() = tr(R.string.model_key_website)
    val TEST_AND_SAVE: String get() = tr(R.string.model_test_save)
    val TESTING: String get() = tr(R.string.model_testing)
    val PICK_MAIN: String get() = tr(R.string.model_pick_main)
    val SELF_HOSTED_NOTE: String get() = tr(R.string.model_self_hosted_note)
    val MODEL_NAME: String get() = tr(R.string.model_manual_name)
    val MANUAL_ON: String get() = tr(R.string.model_manual_on)
    val MANUAL_OFF: String get() = tr(R.string.model_manual_off)
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
