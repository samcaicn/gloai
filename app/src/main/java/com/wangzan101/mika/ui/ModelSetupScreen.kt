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
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import com.wangzan101.mika.settings.ProviderKeySites
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
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.TagTone
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
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
    /** 设置里点「图片模型」卡片去设置页;上手流程不传,卡片就不出现。 */
    onOpenImage: (() -> Unit)? = null,
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
    var pickingFallbackRole by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var pickingFallbackSlot by rememberSaveable { mutableStateOf<Int?>(null) }
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
        picking = null
        pickingFallbackRole = null
        pickingFallbackSlot = null
        openProvider = id
        allModels = false
        showProviders = false
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
        testAndSave = vm::testAndSave, saveUntested = vm::saveWithoutTest,
        openForm = vm::openForm, closeForm = vm::closeForm,
        // 从服务商页面进详情时留着 showProviders:返回落回服务商页面,不是模型页
        openProvider = { openProvider = it; allModels = false }, pickRole = { picking = it },
        pickFallback = { pickingFallbackRole = ModelRole.Main; pickingFallbackSlot = it },
        pickRoleFallback = { r, s -> pickingFallbackRole = r; pickingFallbackSlot = s },
        clearFallback = vm::clearFallback,
        openProviders = { showProviders = true },
        next = onDone, openSpeech = onOpenSpeech, openVoice = onOpenVoice, openUsage = onOpenUsage,
        openDecision = { openDecision = true }, openSignIn = { signInSheet = it },
        setReasoningEffort = vm::setReasoningEffort, setModelContext = vm::setModelContext,
    )
    // 内部子页:标识稳定(换数据不重演动画)、带着层级(决定滑动方向)
    val subPage: ModelSubPage = when {
        openDecision -> ModelSubPage.Decision
        provider != null && allModels -> ModelSubPage.AllModels(provider.id, showProviders)
        provider != null -> ModelSubPage.Detail(provider.id, showProviders)
        showProviders -> ModelSubPage.Providers
        pickingFallbackSlot != null -> ModelSubPage.FallbackPick(pickingFallbackRole ?: ModelRole.Main, pickingFallbackSlot!!)
        role != null -> ModelSubPage.RolePick(role)
        else -> ModelSubPage.Main
    }
    // 换页动画期间旧页还在渲染:服务商刚删掉时详情页也能从缓存里拿到数据画完退出
    var heldProviders by remember { mutableStateOf(ui.providers) }
    if (ui.providers.isNotEmpty()) heldProviders = ui.providers
    NavSlide(subPage, depth = { it.depth }) { page ->
    when (page) {
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
                    use = { r, model -> vm.pick(r, p.id, model) },
                    setVision = { model, sees -> vm.setModelVision(p.id, model, sees) },
                    setServiceTier = { model, tier -> vm.setModelServiceTier(p.id, model, tier) },
                    showAll = { allModels = true },
                    retest = { vm.retest(p.id) },
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
                        if (ui.probeBeforePick(role, pid)) vm.pickFallbackManual(role, slot, pid, model) { pickingFallbackSlot = null; pickingFallbackRole = null }
                        else { vm.pickFallback(role, slot, pid, model); pickingFallbackSlot = null; pickingFallbackRole = null }
                    },
                    pickManual = { pid, model -> vm.pickFallbackManual(role, slot, pid, model) { pickingFallbackSlot = null; pickingFallbackRole = null } },
                    addProvider = { vm.openForm(); pickingFallbackSlot = null; pickingFallbackRole = null },
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
        is ModelSubPage.Main -> {
            if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
            BackHandler(onBack = onBack)
            ModelSetupContent(ui, onboarding, actions, onBack)
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

/**
 * 模型页内部的子页:标识用稳定值(服务商 id、用途、槽位),数据变了不重演换页动画;
 * [depth] 决定滑动方向——主页 0,服务商列表和各选择页 1,详情 1(主页直进)或 2(从服务商列表进),全部模型再深一层。
 */
private sealed class ModelSubPage(val depth: Int) {
    data object Main : ModelSubPage(0)
    data object Decision : ModelSubPage(1)
    data object Providers : ModelSubPage(1)
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
                    RolesSection(ui, onPick, actions.setReasoningEffort, actions.setModelContext)
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
                    RolesSection(ui, onPick, actions.setReasoningEffort, actions.setModelContext)
                    SpeechVoiceSection(ui, actions.openSpeech, actions.openVoice)
                    ImageModelSection(ui, actions.openImage)
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

/** 服务商:每家一行(写明在给哪些用途干活),加「添加服务商」;没有服务商或点了添加时,表单就摊在这里。 */
@Composable
private fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    if (ui.providers.isNotEmpty()) {
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
            style = MikaType.Label, color = c.accent,
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
    // Header row: title + cancel
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Connect a Provider",
            Modifier.weight(1f).padding(top = 16.dp, bottom = 8.dp),
            style = MikaType.Body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            fontSize = 15.sp, color = c.ink,
        )
        if (ui.providers.isNotEmpty()) {
            Text(
                ModelSetupCopy.CANCEL, Modifier.clickable(onClick = actions.closeForm).padding(horizontal = 4.dp, vertical = 6.dp),
                style = MikaType.Caption, color = c.accent,
            )
        }
    }
    // Sign-in options (ChatGPT, xAI, OpenRouter)
    AccountSignInGroup(ui, actions.openSignIn, actions.openProvider)
    // Provider preset chips
    Text("Choose a provider", Modifier.padding(top = 4.dp, bottom = 6.dp), style = MikaType.Caption, color = c.ink3)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderPresets.all.forEach { p ->
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
        if (f.isCloudflare) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 4.dp)
            ) {
                Text(
                    stringResource(R.string.model_setup_account_id),
                    style = MikaType.Body, color = c.ink,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, MikaShapes.Button)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (f.accountId.isEmpty()) {
                        Text(
                            stringResource(R.string.model_setup_account_id_placeholder),
                            style = MikaType.Body, color = c.ink3,
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = f.accountId,
                        onValueChange = actions.setAccountId,
                        modifier = Modifier.fillMaxWidth().testTag("setup-account-id"),
                        singleLine = true,
                        textStyle = MikaType.Body.copy(
                            color = c.ink,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                        ),
                    )
                }
            }
        } else if (f.urlEditable) {
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
        // ── API Key input — multi-line, large, prominent ──────────────────
        val parsedKeys = remember(f.apiKey) { com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(f.apiKey) }
        val poolActive = parsedKeys.size > 1
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (!f.urlEditable) 0.dp else 0.dp, bottom = 2.dp)
        ) {
            if (!f.urlEditable) Hairline(start = 0.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ModelSetupCopy.API_KEY,
                    style = MikaType.Body, color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showKey) stringResource(R.string.hide) else stringResource(R.string.show),
                    Modifier.clickable { showKey = !showKey }.padding(start = 8.dp),
                    fontSize = 13.sp, color = c.accent,
                )
            }
            // Large text area for single or multi-key paste
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.fill, MikaShapes.Button)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (f.apiKey.isEmpty()) {
                    Text(
                        if (f.keyRequired) "Paste your API key here\n(Separate multiple keys with commas or new lines for auto-rotation)"
                        else ModelSetupCopy.KEY_OPTIONAL,
                        style = MikaType.Body, color = c.ink3, lineHeight = 20.sp,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = f.apiKey,
                    onValueChange = { raw ->
                        val accepted = com.wangzan101.mika.core.model.ApiKeyText.accept(raw)
                        actions.setApiKey(accepted.text)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("setup-api-key"),
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MikaType.Body.copy(
                        color = c.ink,
                        fontFamily = if (!showKey) null else androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                                           else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
            // Live pool feedback
            when {
                poolActive -> Text(
                    "${parsedKeys.size} keys detected — pool will auto-rotate on rate-limit or timeout",
                    Modifier.padding(top = 6.dp).testTag("setup-key-notice"),
                    style = MikaType.Caption, color = c.ok,
                )
                f.keyNotice == com.wangzan101.mika.core.model.ApiKeyText.Notice.IllegalCharacters -> Text(
                    ModelSetupCopy.KEY_ILLEGAL,
                    Modifier.padding(top = 6.dp).testTag("setup-key-notice-illegal"),
                    style = MikaType.Caption, color = c.bad,
                )
                f.keyNotice == com.wangzan101.mika.core.model.ApiKeyText.Notice.InvisibleRemoved -> Text(
                    ModelSetupCopy.KEY_INVISIBLE_REMOVED,
                    Modifier.padding(top = 6.dp).testTag("setup-key-notice"),
                    style = MikaType.Caption, color = c.ink2,
                )
                f.apiKey.isNotBlank() -> Text(
                    "1 key ready",
                    Modifier.padding(top = 6.dp),
                    style = MikaType.Caption, color = c.ok,
                )
            }
            // 按地址认得出服务商时,给官方 Key 申请页的直达链接;认不出不显示
            ProviderKeySites.forBaseUrl(f.baseUrl)?.let { url ->
                val context = LocalContext.current
                Text(
                    ModelSetupCopy.KEY_WEBSITE,
                    Modifier.padding(top = 6.dp).clickable { openInBrowser(context, url) }.testTag("setup-key-website"),
                    style = MikaType.Caption, color = c.accent,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (f.showModel) {
            InputRow(
                ModelSetupCopy.MODEL_NAME, f.model, actions.setModel, ModelSetupCopy.modelExample(f.exampleKind),
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-model"),
            )
        }
    }
    // 列表里没有要的模型、或这家不提供列表时,随时可以自己填(没列出模型时那一栏已经在了)
    if (!f.noList) {
        Text(
            if (f.manual) ModelSetupCopy.MANUAL_OFF else ModelSetupCopy.MANUAL_ON,
            Modifier.padding(start = 16.dp, top = 8.dp).clickable { actions.setManual(!f.manual) }.padding(vertical = 4.dp).testTag("setup-manual-model"),
            style = MikaType.Caption, color = c.accent,
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
            style = MikaType.Caption, color = c.bad,
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
        style = MikaType.Label, color = c.accent,
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
    onContext: ((String, ModelContextSettings) -> Boolean)? = null,
) {
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
    }
}

/** 语音:语音转文字、声音模型两行,行样式和决策模型一样。 */
@Composable
private fun SpeechVoiceSection(ui: ModelSetupUi, onSpeech: (() -> Unit)?, onVoice: (() -> Unit)?) {
    if (onSpeech == null && onVoice == null) return
    GroupCard {
        var first = true
        if (onSpeech != null) {
            ListRow(ModelsCopy.SPEECH_ICON, stringResource(R.string.role_speech), first = first, onClick = onSpeech) {
                RowValue(ModelsCopy.speechLine(ui).title)
                RowChevron()
            }
            first = false
        }
        if (onVoice != null) {
            ListRow(ModelsCopy.VOICE_ICON, stringResource(R.string.role_voice), onClick = onVoice) { RowChevron() }
        }
    }
}

/** 图片模型:生成/改图用的模型,和语音一样单独一行。 */
@Composable
private fun ImageModelSection(ui: ModelSetupUi, onImage: (() -> Unit)?) {
    if (onImage == null) return
    GroupCard {
        ListRow(ModelsCopy.IMAGE_ICON, stringResource(R.string.role_image), subtitle = stringResource(R.string.role_image_what), first = true, onClick = onImage) {
            RowValue(ModelsCopy.imageLine(ui).title)
            RowChevron()
        }
    }
}

/** Backup models for a role: seamless failover if the primary model hits limits or goes down. */
@Composable
private fun FallbackSection(
    ui: ModelSetupUi,
    role: ModelRole = ModelRole.Main,
    onPickFallback: (ModelRole, Int) -> Unit,
    onClearFallback: (ModelRole, Int) -> Unit,
) {
    val shouldShow = when (role) {
        ModelRole.Main -> ui.roles.main != null
        ModelRole.Vision -> ui.roles.vision is RoleChoice.Model
        ModelRole.Light -> ui.roles.light is RoleChoice.Model
    }
    if (!shouldShow) return

    val fallbacks = ui.roles.fallbacks(role)
    val maxBackups = com.wangzan101.mika.settings.MAX_FALLBACKS
    val roleLabel = ModelsCopy.roleLabel(role)
    val hasAny = fallbacks.isNotEmpty()
    var expanded by rememberSaveable(role.name) { mutableStateOf(false) }
    var removingSlot by rememberSaveable(role.name) { mutableStateOf<Int?>(null) }
    val c = MikaTheme.colors

    GroupCard {
        // Collapsible Header Row - sleek and compact
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (hasAny) c.accent.copy(alpha = 0.12f) else c.neutralSoft, MikaShapes.Tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ModelsCopy.FALLBACK_ICON,
                    contentDescription = null,
                    tint = if (hasAny) c.accent else c.ink3,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.model_fallback_chain_title, roleLabel),
                        style = MikaType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasAny) {
                        Box(
                            Modifier
                                .background(c.accent.copy(alpha = 0.12f), MikaShapes.Pill)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.model_fallback_active_count, fallbacks.size),
                                style = MikaType.Micro.copy(fontWeight = FontWeight.SemiBold),
                                color = c.accent,
                                maxLines = 1,
                            )
                        }
                    }
                }
                val previewText = if (expanded) {
                    stringResource(R.string.model_fallback_collapse_hint)
                } else if (hasAny) {
                    val primarySnippet = ui.configFor(role)?.model?.substringAfterLast('/') ?: "Primary"
                    val fallbackSnippets = fallbacks.mapNotNull { id ->
                        ui.configs.firstOrNull { it.id == id }?.model?.substringAfterLast('/')
                    }
                    (listOf(primarySnippet) + fallbackSnippets).joinToString(" ➔ ")
                } else {
                    stringResource(R.string.model_fallback_empty_preview)
                }
                Text(
                    previewText,
                    Modifier.padding(top = 2.dp),
                    style = MikaType.Caption,
                    color = c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                MikaIcons.Chevron,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = c.ink3,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }

        // Expanded Interactive Chain - nested inside an indented tray card
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .background(c.fill, MikaShapes.Card)
                    .border(0.5.dp, c.edge, MikaShapes.Card)
                    .clip(MikaShapes.Card),
            ) {
                val primaryCfg = ui.configFor(role)
                if (primaryCfg != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.accent.copy(alpha = 0.14f), MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("1", style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = c.accent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.model_fallback_primary_badge),
                                style = MikaType.Hint,
                                color = c.accent,
                                maxLines = 1,
                            )
                            Text(
                                primaryCfg.model,
                                Modifier.padding(top = 1.dp),
                                style = MikaType.BodyStrong,
                                fontFamily = FontFamily.Monospace,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val providerName = ui.providerOf(primaryCfg)?.let(ModelsCopy::providerName) ?: primaryCfg.name
                            if (providerName.isNotEmpty()) {
                                Text(
                                    providerName,
                                    Modifier.padding(top = 1.dp),
                                    style = MikaType.Caption,
                                    color = c.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                fallbacks.forEachIndexed { slot, configId ->
                    Hairline(start = 14.dp)
                    val cfg = ui.configs.firstOrNull { it.id == configId }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickFallback(role, slot) }
                            .testTag("role-fallback-${role.name.lowercase()}-$slot")
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.surface, MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${slot + 2}", style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = c.ink2)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.model_fallback_backup_badge, slot + 1),
                                style = MikaType.Hint,
                                color = c.ink3,
                                maxLines = 1,
                            )
                            Text(
                                cfg?.model ?: ModelsCopy.UNSET,
                                Modifier.padding(top = 1.dp),
                                style = MikaType.BodyStrong,
                                fontFamily = FontFamily.Monospace,
                                color = if (cfg == null) c.ink3 else c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val providerName = cfg?.let { ui.providerOf(it)?.let(ModelsCopy::providerName) ?: it.name } ?: ""
                            if (providerName.isNotEmpty()) {
                                Text(
                                    providerName,
                                    Modifier.padding(top = 1.dp),
                                    style = MikaType.Caption,
                                    color = c.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(MikaShapes.Pill)
                                .clickable { removingSlot = slot },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MikaIcons.Close,
                                contentDescription = "Remove backup",
                                tint = c.ink3,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                if (fallbacks.size < maxBackups) {
                    Hairline(start = 14.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickFallback(role, fallbacks.size) }
                            .testTag("role-fallback-${role.name.lowercase()}-${fallbacks.size}")
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.accent.copy(alpha = 0.08f), MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MikaIcons.Plus, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                        }
                        Text(
                            if (fallbacks.isEmpty()) stringResource(R.string.model_fallback_add_first)
                            else stringResource(R.string.model_fallback_add_another, fallbacks.size + 1, maxBackups),
                            style = MikaType.BodyStrong,
                            color = c.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    val pendingRemoveSlot = removingSlot
    if (pendingRemoveSlot != null && pendingRemoveSlot in fallbacks.indices) {
        val targetCfgId = fallbacks[pendingRemoveSlot]
        val targetCfg = ui.configs.firstOrNull { it.id == targetCfgId }
        val modelName = targetCfg?.model ?: ModelsCopy.UNSET
        MikaDialog(
            onDismissRequest = { removingSlot = null },
            title = { Text(stringResource(R.string.model_fallback_remove_title)) },
            text = {
                Text(stringResource(R.string.model_fallback_remove_body, modelName, roleLabel))
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = removingSlot
                    removingSlot = null
                    if (s != null) onClearFallback(role, s)
                }) {
                    Text(stringResource(R.string.model_fallback_remove_confirm), color = c.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { removingSlot = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (hasAny && expanded) {
        FooterNote(stringResource(R.string.model_fallback_footer_note, roleLabel))
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

internal val ModelIdStyle = MikaType.Value.copy(fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

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
    val KEY_WEBSITE: String get() = tr(R.string.model_key_website)
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
