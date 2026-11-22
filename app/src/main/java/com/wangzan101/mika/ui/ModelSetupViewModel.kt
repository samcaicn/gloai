package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.ProviderPreset
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProbeResult
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.AccountOutcome
import com.wangzan101.mika.settings.DevicePrompt
import com.wangzan101.mika.settings.SignInTarget
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechSettings
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.TranscribeMethod
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.SignInException
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.oauth.AccountSignInException
import com.wangzan101.mika.core.oauth.AccountSignInFailure
import com.wangzan101.mika.core.oauth.DeviceChallenge
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.core.oauth.XaiAuth
import com.wangzan101.mika.core.oauth.signInWithOpenRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 列模型(「测试并保存」或刷新一个服务商)的进度。 */
sealed interface ListState {
    data object Idle : ListState
    data object Running : ListState
    data class Failed(val message: String) : ListState
}

/** 用 ChatGPT 登录的账号:[email] 来自核对过的 ID 令牌(可能为空);退出登录后 [signedIn] 为假。 */
data class ChatGptAccount(val email: String, val signedIn: Boolean)

/**
 * 除 ChatGPT 以外用账号登录的几家:xAI / Grok 走设备码(浏览器里核对短码),OpenRouter 走官方的 PKCE 授权(本机回调换 Key)。
 * ChatGPT 的登录另有一套(见 [ModelSetupViewModel.signInWithChatGpt]),界面上和它们并排。
 */
enum class AccountProvider(val target: SignInTarget) {
    Xai(SignInTarget.Xai), OpenRouter(SignInTarget.OpenRouter);

    companion object {
        fun of(target: SignInTarget): AccountProvider? = entries.firstOrNull { it.target == target }
    }
}

/** 一家账号登录的账号。[label] 是给人看的账号名(邮箱等,可能为空);退出登录后 [signedIn] 为假。没有这家服务商时为 null。 */
data class SubscriptionAccount(val label: String, val signedIn: Boolean)

/**
 * 正在进行或刚失败的账号登录。没在登录、也没有失败时 [provider] 为 null。
 * 设备码登录时 [userCode] 是给用户看的短码,[openUrl] 是核对过主机的验证页,[expiresAtMs] 是短码失效的时间。
 */
data class AccountSignInUi(
    val provider: AccountProvider? = null,
    val running: Boolean = false,
    val userCode: String = "",
    val openUrl: String = "",
    val expiresAtMs: Long = 0,
    val failure: String? = null,
)

/**
 * 用账号登录要用到的系统能力:在浏览器里打开授权页、登录完回到 Mika、本机回调收到后浏览器里显示的网页。
 * 界面给出(要 Context);测试里不给,就不能登录。
 */
interface SignInBrowser {
    fun open(url: String)
    fun returnToApp()
    /** 回调处理完之后回给浏览器的网页:[failure] 为 null 是登录成功,否则是失败的原因(和界面上同一句)。 */
    fun page(failure: String?): String
}

/**
 * 「添加服务商」表单。[noList] 为真时上次测试没列出模型，可填 [model] 继续配置。
 * [manual] 为真时也显示手填模型名。[name] 是自定义服务商的名字(只有自定义才填,可不填,见 [providerName])。
 */
data class AddForm(
    val preset: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val apiKey: String,
    /**
     * 最近一次改 Key 的提示:零宽被去掉,或还留着放不进请求头的字符(含换行)。
     * [ApiKeyText.Notice.IllegalCharacters] 时不能测试、也不能保存。
     */
    val keyNotice: ApiKeyText.Notice = ApiKeyText.Notice.None,
    val test: ListState = ListState.Idle,
    val model: String = "",
    val noList: Boolean = false,
    val name: String = "",
    val manual: Boolean = false,
) {
    /** 显示「模型名」一栏:没列出模型,或用户要手填。 */
    val showModel: Boolean get() = noList || manual

    /** 填了的模型名;没显示那一栏时不算。 */
    val typedModel: String get() = if (showModel) model.trim() else ""

    val isCloudflare: Boolean get() = preset == "Cloudflare Workers AI"

    val accountId: String
        get() = if (baseUrl.contains("/accounts/") && !baseUrl.contains("{account_id}")) {
            baseUrl.substringAfter("/accounts/").substringBefore("/").trim()
        } else ""

    val isCustom: Boolean get() = preset == ProviderPresets.custom.label

    /** 跑在用户自己机器上的服务(见 [ProviderPreset.selfHosted]):地址预填了本机的默认端口,要能改成那台机器的。 */
    val isSelfHosted: Boolean get() = presetFor(preset).selfHosted

    /** 地址要用户填或改:自定义,自己跑的服务,或地址含参数的预设。Cloudflare 单独展示 Account ID 输入项。 */
    val urlEditable: Boolean get() = (isCustom || isSelfHosted || presetFor(preset).urlEditable) && !isCloudflare

    val urlProblem: String?
        get() = when {
            baseUrl.isBlank() -> tr(R.string.model_need_url)
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
            baseUrl.contains("{account_id}") -> tr(R.string.model_url_account_id)
            else -> null
        }

    /** 明文 http 又不在本机、局域网的地址:提醒一句,不拦着(见 [LocalHosts])。 */
    val unencryptedWarning: String?
        get() = if (urlProblem == null && LocalHosts.unencryptedRemote(baseUrl)) tr(R.string.model_url_unencrypted) else null

    /** 自己跑的服务和自定义的 OpenAI 兼容接口(多半是本地服务)可以不填 Key,其余都要。 */
    val keyRequired: Boolean get() = !isSelfHosted && !(isCustom && kind == ProviderConfig.Kind.OpenAiCompat)

    /** Key 里还有放不进请求头的字符:两个保存动作都不能发请求、也不能把 Key 存下来。 */
    val keyRejected: Boolean get() = keyNotice == ApiKeyText.Notice.IllegalCharacters

    val canTest: Boolean get() =
        urlProblem == null && !keyRejected && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running && (!noList || model.isNotBlank())

    /** 不测试也能存:地址格式对、该有 Key 的有 Key、Key 放得进请求头。和 [canTest] 不同,不看测试进度,也不要求填模型名。 */
    val canSaveUntested: Boolean get() =
        urlProblem == null && !keyRejected && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running

    /** 自定义填的是某家服务商的官方地址(主机名对得上,见 [ProviderPresets.forHost]):建议改用那个预设。 */
    val suggestedPreset: ProviderPreset? get() = if (isCustom && urlProblem == null) ProviderPresets.forHost(baseUrl) else null

    /** 地址就是 [suggestedPreset] 的那个地址,选的接口类型却和它的不一样:照这样一定调不通。 */
    val presetKindMismatch: Boolean
        get() = suggestedPreset?.let { s -> ProviderPresets.forBaseUrl(baseUrl.trim()) == s && kind != s.kind } == true

    /** 举模型名的例子按哪一类接口:地址对得上某个预设的按那个预设,否则按选的接口类型。 */
    val exampleKind: ProviderConfig.Kind get() = suggestedPreset?.kind ?: kind

    val defaultName: String
        get() = when {
            isCloudflare && accountId.isNotBlank() -> {
                val shortId = if (accountId.length >= 8) "${accountId.take(4)}…${accountId.takeLast(4)}" else accountId
                "$preset ($shortId)"
            }
            else -> ProviderConnection.nameForUrl(baseUrl)
        }

    /**
     * 存成服务商时用的名字:预设用预设名,自定义用填的名字、没填用 [defaultName]。
     * 服务商按接口类型、地址和名字归组,同一个地址起不同的名字就是两家(例如同一个中转站的两个 Key)。
     */
    val providerName: String
        get() = when {
            isCustom -> name.trim().ifEmpty { defaultName }
            isCloudflare -> name.trim().ifEmpty { defaultName }
            else -> preset
        }

    /** 这家要放会话号的请求头名:选的预设声明了的用它;自定义的地址对得上某个预设也用那个预设的。 */
    val sessionHeader: String? get() = presetFor(preset).sessionHeader ?: ProviderPresets.forBaseUrl(baseUrl.trim())?.sessionHeader

    /** 这家自己的模型名里的词(见 [ProviderPreset.familyTokens]):选的预设给了的用它,否则按地址对得上的预设。 */
    val familyTokens: List<String> get() = presetFor(preset).familyTokens.ifEmpty { ModelOptions.familyOf(baseUrl) }

    companion object {
        fun of(p: ProviderPreset) = AddForm(p.label, p.kind, p.baseUrl, "")
        fun presetFor(label: String): ProviderPreset = ProviderPresets.all.firstOrNull { it.label == label } ?: ProviderPresets.custom
    }
}

/**
 * 配置模型(上手时)和设置 › 模型共用的全部状态:已连接的服务商、添加服务商表单、三个用途。
 * [providers]、[configs]、[roles] 是 [ProviderStore] 的快照,[keyed] 是存了 Key 的服务商 id;
 * [listing] 是各服务商刷新列表的进度(按服务商 id)。
 */
data class ModelSetupUi(
    val form: AddForm,
    val formOpen: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val configs: List<ProviderConfig> = emptyList(),
    val roles: RoleChoices = RoleChoices(),
    val groups: List<ModelGroup> = emptyList(),
    val keyed: Set<String> = emptySet(),
    val providerKeyMap: Map<String, List<String>> = emptyMap(),
    val listing: Map<String, ListState> = emptyMap(),
    /** ChatGPT 服务商的账号;没有这家时为 null。 */
    val chatGptAccount: ChatGptAccount? = null,
    /** 用 ChatGPT 登录的进度。 */
    val chatGptSignIn: ListState = ListState.Idle,
    /** 第一次登录成功后说明一次「在用你的 ChatGPT 套餐」,点「知道了」收起。 */
    val chatGptWelcome: Boolean = false,
    /** 退出登录时 ChatGPT 那边没确认注销(本机令牌已清)。 */
    val chatGptRevokeUnconfirmed: Boolean = false,
    /** 已连接的 Grok 订阅;没有这家时为 null。 */
    val xaiAccount: SubscriptionAccount? = null,
    /** 用 OpenRouter 账号授权连上的那一家;没有时为 null。 */
    val openRouterAccount: SubscriptionAccount? = null,
    /** xAI、OpenRouter 的登录进度。 */
    val accountSignIn: AccountSignInUi = AccountSignInUi(),
    /** 刚用账号登录成功的服务商 id:界面打开它的详情页(看账号和模型、设主模型),打开后清掉。 */
    val signedInProvider: String? = null,
    /**
     * 存下服务商时的说明:还没有主模型、推荐的那个没试调用通过(见 [ModelSetupViewModel.testAndSave]),
     * 或这家是跳过测试存下的(见 [ModelSetupViewModel.saveWithoutTest])。选了主模型时清掉。
     */
    val probeNote: String? = null,
    /** 刚存下、推荐的模型没试通过的服务商 id:界面给「选主模型」的入口;选好了清掉。 */
    val pickToProbe: String? = null,
    /**
     * 选择页、「用作」面板保存模型的进度,按服务商 id。设主模型要先试调用(见 [ModelSetupViewModel.pick]),
     * 这时是 [ListState.Running];没通过是 [ListState.Failed]。
     */
    val manualPick: Map<String, ListState> = emptyMap(),
    /** 服务商页的测试进度，按服务商 id 和模型名。 */
    val retest: Map<Pair<String, String>, ListState> = emptyMap(),
    /** 语音转文字现在的选择,和这台手机自带识别的能力(给用途卡片和选择页用)。 */
    val speech: SpeechChoice = SpeechChoice.Auto,
    val systemSpeech: SystemCapability = SystemCapability(available = false, onDevice = false),
    /** 迁移后的语音目录;null 表示仍走旧设置。 */
    val sttCatalog: VoiceCatalogSnapshot? = null,
    /** 正在拿一小段录音试转的服务商 id;没有时为 null。 */
    val speechTesting: String? = null,
) {
    /** 还没有服务商时表单总是展开。 */
    val showForm: Boolean get() = formOpen || providers.isEmpty()

    /** 选好了主模型就能「下一步」。 */
    val canNext: Boolean get() = roles.hasMain

    /** 这个用途实际用的配置(「用主模型」时是主模型的;组时是排在最前的启用成员)。 */
    fun configFor(role: ModelRole): ProviderConfig? =
        (roles.resolveConfig(role) ?: groups.firstOrNull { it.id == roles.resolveGroup(role) }?.enabledMembers?.firstOrNull()?.configId)
            ?.let { id -> configs.firstOrNull { it.id == id } }

    /** 配置 id 对应的配置;组里的成员、选择页都用它。 */
    fun configOf(id: String): ProviderConfig? = configs.firstOrNull { it.id == id }

    /** 组能不能用于这个用途(主模型要求全员支持工具,看图要求至少一个能看图)。 */
    fun usable(group: ModelGroup, role: ModelRole): Boolean =
        group.usableFor(role) { id -> configOf(id)?.capabilities }

    fun providerOf(cfg: ProviderConfig): Provider? = providers.firstOrNull { it.id == cfg.providerId }

    /** 对话用途可选的模型。服务商详情同时显示 TTS 模型。 */
    fun options(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs)

    /** 一个服务商的全部模型,在用的排前面,其余保持原来的顺序。 */
    fun sortedOptions(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs, includeTts = true)
        .sortedBy { if (rolesOf(p.id, it.id).isNotEmpty()) 0 else 1 }

    /** 实际在用这个模型的用途(含「用主模型」跟过来的),按主模型、看图、轻量的顺序。 */
    fun rolesOf(providerId: String, model: String): List<ModelRole> =
        ModelRole.entries.filter { role -> configFor(role)?.let { it.providerId == providerId && it.model == model } == true }

    /** 实际在用这个服务商的用途。 */
    fun rolesOf(p: Provider): List<ModelRole> = ModelRole.entries.filter { configFor(it)?.providerId == p.id }

    /** 这家正在使用的对话模型，包括各用途的备援。 */
    fun usedChatModels(p: Provider): List<String> =
        roles.referenced.mapNotNull { id -> configs.firstOrNull { it.id == id && it.providerId == p.id }?.model }
            .distinct().filter { it.isNotBlank() }

    /** 在用、没有模型列表且未验证的手填模型。 */
    fun unverified(p: Provider): List<String> = usedChatModels(p).filter(p::untested)

    /**
     * 这家是跳过测试直接存下的(见 [ModelSetupViewModel.saveWithoutTest]):既没列过模型、也没有一个模型试通过过。
     * 服务商页据此说「还没测过」,而不是笼统的「不提供模型列表」——后者对它是错的。
     * 之后列过一次模型、或试通过一次就不算(见 [ProviderStore.rememberModels]、[ProviderStore.markVerified])。
     */
    fun untested(p: Provider): Boolean = p.savedUntested

    /** 用账号登录的这一家已经退出了登录(ChatGPT、xAI 的令牌清了,OpenRouter 的 Key 删了)。 */
    fun signedOut(p: Provider): Boolean = when {
        p.kind == ProviderConfig.Kind.ChatGpt -> chatGptAccount?.signedIn != true
        p.kind == ProviderConfig.Kind.Xai -> xaiAccount?.signedIn != true
        p.signIn != null -> p.id !in keyed
        else -> false
    }

    /** 这个用途明确选的是不是这个模型(「用主模型」不算)。 */
    fun picked(role: ModelRole, providerId: String, model: String): Boolean {
        val id = (roles[role] as? RoleChoice.Model)?.configId ?: return false
        return configs.firstOrNull { it.id == id }?.let { it.providerId == providerId && it.model == model } == true
    }
}

/**
 * 配置模型的状态与动作。添加服务商时「测试并保存」列出模型就存下这家;还没有主模型时再让推荐的一个试调用,通了才当主模型。
 * 改了服务商、地址或 Key,进行中的测试作废。用途:从所有已连接服务商记住的模型里选;主模型要先试调用通过
 * (试通过过的不再试),看图、轻量、备援选中即生效;看图还可以「用主模型」「不用」,轻量可以「用主模型」。
 * 首次上手和设置 › 模型共用。
 */
class ModelSetupViewModel(
    private val store: ProviderStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val browser: SignInBrowser? = null,
    /** 登录跑在哪:界面给的是 App 那一份(退到后台、界面重建都不断,期间开前台服务);测试里就用 [scope]。 */
    private val signIns: ChatGptSignIns = ChatGptSignIns(scope),
    /** 语音转文字的选择;测试和上手流程不传,卡片就不出现。 */
    private val speech: SpeechSettings? = null,
    private val systemSpeech: () -> SystemCapability = { SystemCapability(available = false, onDevice = false) },
    private val stt: SttSelectionController? = null,
    /** 统一语音目录的账号管理:删除服务商时一并清掉依赖它的语音模型;测试和上手流程可不传。 */
    private val voiceAccounts: com.wangzan101.mika.voice.model.VoiceAccountManager? = null,
) {
    private val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    private val _speechProbe = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 服务商 id -> 选它的语音模型时试转没通过的原因(用现有的说法);选通了或重新选时清掉。 */
    val speechProbe: StateFlow<Map<String, String>> = _speechProbe.asStateFlow()

    private var testJob: Job? = null
    private val refreshJobs = mutableMapOf<String, Job>()

    init {
        // 别处(开发者选项等)改了存储时跟着更新
        scope.launch { merge(store.providers, store.configs, store.roles, store.groups).collect { sync() } }
        speech?.let { s -> scope.launch { s.choice.collect { sync() } } }
        stt?.let { controller -> scope.launch { controller.catalog.state.collect { sync() } } }
        // 登录在别处结束(通知上取消了)或界面重建前就开始了:跟着它的进度
        scope.launch {
            signIns.running.collect { running ->
                val prompt = signIns.devicePrompt
                val target = signIns.target
                _state.update {
                    when {
                        running && target == SignInTarget.ChatGpt && it.chatGptSignIn == ListState.Idle ->
                            it.copy(chatGptSignIn = ListState.Running)
                        !running && it.chatGptSignIn == ListState.Running -> snapshot(it.copy(chatGptSignIn = ListState.Idle))
                        running && AccountProvider.of(target) != null -> it.copy(
                            accountSignIn = it.accountSignIn.copy(
                                provider = AccountProvider.of(target),
                                running = true,
                                userCode = prompt?.userCode ?: it.accountSignIn.userCode,
                                openUrl = prompt?.openUrl ?: it.accountSignIn.openUrl,
                                expiresAtMs = prompt?.expiresAtMs ?: it.accountSignIn.expiresAtMs,
                            ),
                        )
                        !running && it.accountSignIn.running -> snapshot(it.copy(accountSignIn = AccountSignInUi()))
                        else -> it
                    }
                }
            }
        }
        // 设备码的短码可能在界面重建之后才来
        scope.launch {
            signIns.device.collect { prompt ->
                if (prompt == null) return@collect
                val provider = AccountProvider.of(signIns.target) ?: return@collect
                _state.update {
                    it.copy(accountSignIn = it.accountSignIn.copy(
                        provider = provider, running = true,
                        userCode = prompt.userCode, openUrl = prompt.openUrl, expiresAtMs = prompt.expiresAtMs,
                    ))
                }
            }
        }
        // 账号登录的结果交给现在这个界面(登录可能是重建前的那个界面开始的)
        scope.launch {
            signIns.outcome.collect { o ->
                if (o == null) return@collect
                val provider = AccountProvider.of(o.target) ?: return@collect
                _state.update {
                    if (o.providerId != null) snapshot(it.copy(accountSignIn = AccountSignInUi(), formOpen = false, signedInProvider = o.providerId))
                    else it.copy(accountSignIn = AccountSignInUi(provider = provider, failure = o.failure))
                }
                signIns.consumeOutcome(o)
            }
        }
    }

    private fun snapshot(ui: ModelSetupUi): ModelSetupUi {
        val providers = store.providers.value
        return ui.copy(
            providers = providers, configs = store.configs.value, roles = store.roles.value, groups = store.groups.value,
            keyed = providers.filter { store.hasKey(it.id) }.map { it.id }.toSet(),
            providerKeyMap = providers.associate { it.id to store.providerKeys(it.id) },
            chatGptAccount = store.chatGptProvider()?.let { p ->
                store.chatGptSession(p.id).let { ChatGptAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
            xaiAccount = store.xaiProvider()?.let { p ->
                store.accountSession(p.id).let { SubscriptionAccount(it?.label.orEmpty(), it?.signedIn == true) }
            },
            openRouterAccount = store.openRouterAccount()?.let { p -> SubscriptionAccount("", store.hasKey(p.id)) },
            speech = speech?.choice?.value ?: SpeechChoice.Auto,
            systemSpeech = systemSpeech(),
            sttCatalog = stt?.takeIf { it.migrated }?.catalog?.state?.value,
        )
    }

    private fun sync() = _state.update(::snapshot)

    // ---------- 添加服务商 ----------

    fun openForm() = _state.update { it.copy(formOpen = true) }

    fun closeForm() {
        testJob?.cancel()
        _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.all.first())) }
    }

    fun selectPreset(label: String) {
        val p = AddForm.presetFor(label)
        if (p.label == _state.value.form.preset) return
        val form = _state.value.form
        edit { AddForm.of(p).copy(apiKey = form.apiKey, keyNotice = form.keyNotice) }
    }

    // 换了接口类型或地址,上次「没列出模型」就不作数了,要重新列
    fun setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind, noList = false) }
    fun setBaseUrl(url: String) = edit { it.copy(baseUrl = url, noList = false) }

    fun setAccountId(input: String) {
        val trimmed = input.trim()
        val accountId = when {
            trimmed.contains("/accounts/") -> trimmed.substringAfter("/accounts/").substringBefore("/").trim()
            trimmed.contains("dash.cloudflare.com/") -> trimmed.substringAfter("dash.cloudflare.com/").substringBefore("/").trim()
            else -> trimmed
        }
        val newUrl = if (accountId.isBlank()) {
            "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"
        } else {
            "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1"
        }
        setBaseUrl(newUrl)
    }
    /** 粘贴或逐字改 Key:去掉零宽;换行和其它放不进请求头的字符原样留下并立刻标出来。 */
    fun setApiKey(key: String) {
        val accepted = ApiKeyText.accept(key)
        edit { it.copy(apiKey = accepted.text, keyNotice = accepted.notice) }
    }
    fun setModel(model: String) = edit { it.copy(model = model) }

    /** 「手动填写模型名」:打开时显示模型名一栏;收起时清掉填的(没列出模型时那一栏一直在)。 */
    fun setManual(on: Boolean) = edit { it.copy(manual = on, model = if (on || it.noList) it.model else "") }
    // 按字符(码点)截,不把 emoji 截成半个
    fun setName(name: String) = edit {
        val n = name.codePointCount(0, name.length)
        it.copy(name = if (n <= MAX_NAME) name else name.substring(0, name.offsetByCodePoints(0, MAX_NAME)))
    }

    /** 连接信息变了:进行中的测试和上次的失败原因作废。 */
    private fun edit(change: (AddForm) -> AddForm) {
        testJob?.cancel()
        _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
    }

    /**
     * 测试并保存:列得出模型就存下这家服务商和列表(列不出时请用户手填,或 [saveWithoutTest] 先存下)。
     * 已经有主模型时只列模型、不发对话请求。还没有主模型时(首次上手、第一家)挑一个候选:手填了模型名的用它,
     * 否则按 [ModelOptions.defaultMain],再不行用第一个对话模型(标为 TTS 的不算);让它调用一次工具(见 [verifyMain]),
     * 通了当主模型;没通过服务商照样存下、不定主模型,说明原因并给「选主模型」的入口([ModelSetupUi.pickToProbe])。
     */
    fun testAndSave() {
        val f = _state.value.form
        if (!f.canTest) return
        val key = headerKey(f.apiKey) ?: return rejectKey()
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val baseUrl = f.baseUrl.trim()
            val typed = f.typedModel
            val models = when (val r = listSafely { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> {
                    if (r.reason != ListFailure.NotSupported) {
                        _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listMessage(r)), manual = true)) }
                        return@launch
                    }
                    emptyList()
                }
            }
            if (models.isEmpty()) {
                _state.update { it.copy(form = it.form.copy(test = ListState.Idle, noList = true)) }
                return@launch
            }
            val saved = try {
                withContext(io) {
                    store.connect(
                        f.providerName, f.kind, baseUrl, key, models, f.sessionHeader,
                        typed = setOfNotNull(typed.takeIf { it.isNotEmpty() && models.none { model -> model.id == it } }),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listFailureText(e)))) }
                return@launch
            }
            var note: String? = null
            if (!store.roles.value.hasMain) {
                val options = ModelOptions.ofProvider(saved, store.list())
                // 手填的是列表里写明 Mika 调不了的(不在 options 里)就不试它
                val candidate = typed.takeIf { t -> t.isNotBlank() && (models.none { it.id == t } || options.any { it.id == t }) }
                    ?: ModelOptions.defaultMain(options, f.familyTokens) ?: options.firstOrNull()?.id
                if (candidate != null) {
                    val problem = verifyMain(saved, candidate)
                    if (problem == null) {
                        withContext(io) { store.provider(saved.id)?.let { use(ModelRole.Main, it, candidate, options) } }
                    } else {
                        note = tr(R.string.model_saved_no_main, problem)
                    }
                }
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = note, pickToProbe = saved.id.takeIf { note != null },
                ))
            }
        }
    }

    /**
     * 不测试,直接保存:只按填的接口类型、地址和 Key 存下这家服务商,不列模型、不试调用(见 [AddForm.canSaveUntested])。
     * 存下的这家带着 [Provider.savedUntested]，服务商页提供刷新目录的入口。
     *
     * 手填了模型名([AddForm.typedModel])时,只把它记为手填——列表里没有它、或这家列不出模型,刷新列表也不会丢。
     *
     * 列表参数给的是空列表(「列过但一个也没有」)而不是 null:这样「没列出模型」这件事和 [Provider.savedUntested]
     * 一起把两种情形分清楚了——真的没有列表,还是这次没去列。
     */
    fun saveWithoutTest() {
        val f = _state.value.form
        if (!f.canSaveUntested) return
        val key = headerKey(f.apiKey) ?: return rejectKey()
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val baseUrl = f.baseUrl.trim()
            val typed = f.typedModel
            try {
                withContext(io) {
                    store.connect(
                        f.providerName, f.kind, baseUrl, key.ifEmpty { null }, emptyList(), f.sessionHeader, untested = true,
                    ).also { p -> if (typed.isNotEmpty()) store.markTyped(p.id, typed) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listFailureText(e)))) }
                return@launch
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = tr(R.string.model_saved_untested, f.providerName),
                ))
            }
        }
    }

    /**
     * 拿去放进请求头的 Key:再收一次(零宽去掉),首尾空白去掉。换行算放不进头。
     * 还含有放不进头的字符时为 null,调用方不能发请求、也不能保存。
     */
    private fun headerKey(raw: String): String? {
        val accepted = ApiKeyText.accept(raw)
        if (accepted.notice == ApiKeyText.Notice.IllegalCharacters) return null
        val key = accepted.text.trim()
        if (key.isEmpty()) return ""
        val parsed = KeyPoolManager.shared.parseKeys(key)
        return if (parsed.isNotEmpty() && parsed.all { ApiKeyText.isHeaderLegal(it) }) key else null
    }

    /** 表单上的 Key 放不进请求头:标红,停在表单上,不发请求。 */
    private fun rejectKey() {
        _state.update { it.copy(form = it.form.copy(keyNotice = ApiKeyText.Notice.IllegalCharacters, test = ListState.Idle)) }
    }

    /** 列模型。头值仍让 OkHttp 抛时收成失败结果,不让异常逃出协程把进程打掉。消息里不带头的原文。 */
    private suspend fun listSafely(block: suspend () -> ModelListResult): ModelListResult = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        listFailure(e)
    }

    private fun listFailure(e: Exception): ModelListResult.Failed = when (e) {
        is ModelError.IllegalHeader, is IllegalArgumentException -> ModelListResult.Failed(ListFailure.IllegalKey)
        else -> ModelListResult.Failed(ListFailure.of(e), e.message.orEmpty())
    }

    private fun listMessage(r: ModelListResult.Failed): String =
        if (r.reason == ListFailure.IllegalKey) tr(R.string.model_key_illegal_chars) else r.message

    /** 列模型以外的失败给人看的一句。头值不合法时不把异常原文(可能含 Key)露出去。 */
    private fun listFailureText(e: Exception): String = when (e) {
        is ModelError.IllegalHeader, is IllegalArgumentException -> tr(R.string.model_key_illegal_chars)
        else -> e.message.orEmpty()
    }

    /**
     * 试调用的结论:通过、[model] 没调用工具、失败([message] 是给人看的原因)。
     */
    private sealed interface ProbeOutcome {
        data object Passed : ProbeOutcome
        data class NoToolCall(val model: String) : ProbeOutcome
        data class Failed(val message: String) : ProbeOutcome
    }

    /**
     * 让 [model] 调用一次工具,和真实任务走同一条建模型的路(请求头、会话号都一样):Key 不对、少了服务商要的请求头、
     * 模型名不对、模型不会调用工具,这里就露出来,不等到第一个任务。超过 [PROBE_TIMEOUT_MS] 没回算失败,服务端的报错原样带上;
     * 找不到模型时说明是哪个，以及 [exampleKind] 这一类接口的模型名示例。
     */
    private suspend fun probeModel(
        kind: ProviderConfig.Kind, baseUrl: String, key: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities, exampleKind: ProviderConfig.Kind,
        providerId: String = "",
    ): ProbeOutcome {
        val result = try {
            withContext(io) {
                withTimeout(PROBE_TIMEOUT_MS) {
                    store.probe(kind, baseUrl, key, sessionHeader, model, endpoint, caps, providerId)
                }
            }
        } catch (e: TimeoutCancellationException) {
            return ProbeOutcome.Failed(tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt()))
        }
        return when (result) {
            ProbeResult.ToolCalled -> ProbeOutcome.Passed
            ProbeResult.NoToolCall -> ProbeOutcome.NoToolCall(model)
            is ProbeResult.Failed -> when (val e = result.error) {
                is ModelError.ModelNotAvailable -> {
                    val detail = e.detail.ifEmpty { e.message.orEmpty() }
                    ProbeOutcome.Failed(tr(R.string.model_probe_not_available, model, detail))
                }
                is ModelError.ToolsUnsupported -> ProbeOutcome.Failed(tr(R.string.model_probe_no_tools_pick, model))
                is ModelError.ModelNotFound -> ProbeOutcome.Failed(
                    tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(exampleKind)),
                )
                // 代理那一段的问题:说是代理,不说成模型或服务商不行
                is ModelError.Proxy -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, proxyReason(e)))
                is ModelError.IllegalHeader -> ProbeOutcome.Failed(tr(R.string.model_key_illegal_chars))
                else -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, e.message.orEmpty()))
            }
        }
    }

    /**
     * 已连上的服务商里试调用 [model]:用存着的 Key、请求头,能力按已有的配置或预设。
     */
    private suspend fun probeSaved(p: Provider, model: String): ProbeOutcome {
        val stored = store.provider(p.id)
            ?: return ProbeOutcome.Failed(tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(p.kind)))
        val options = ModelOptions.ofProvider(stored, store.list())
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(stored)
        val listed = stored.models?.firstOrNull { it.id == model }
        val example = ProviderPresets.forHost(stored.baseUrl)?.kind ?: stored.kind
        return probeModel(
            stored.kind, stored.baseUrl, store.providerKey(p.id), stored.sessionHeader, model, listed?.endpoint, caps, example,
            providerId = stored.id,
        )
    }

    /**
     * 设主模型前的把关:让 [model] 调用一次工具,只回话、不调用工具也算没通过(主模型要会调用工具)。
     * [Provider.verifiedModels] 只说明回复过,不作为跳过的依据;已经是主模型不再测。
     * 通过时记为核实过。返回没通过的原因,通过为 null。
     */
    private suspend fun verifyMain(p: Provider, model: String): String? {
        // verifiedModels 只说明回复过(转写、无工具回复也算),不能证明会调用工具;只有已经是主模型的不再测
        if (store.isMainModel(p.id, model)) return null
        return when (val outcome = probeSaved(p, model)) {
            ProbeOutcome.Passed -> {
                withContext(io) { store.markVerified(p.id, model) }
                null
            }
            is ProbeOutcome.NoToolCall -> tr(R.string.model_probe_no_tools_pick, model)
            is ProbeOutcome.Failed -> outcome.message
        }
    }

    /**
     * 选择页里手填的模型名,看图、轻量和备援也先试调用这一个(主模型走 [verifyMain]):手填错了这里就露出来,不等到任务失败。
     * 只回话、不调用工具也算没通过,看图例外(看图不靠工具)。通过(含只回话的看图)时记为核实过。返回没通过的原因,通过为 null。
     */
    private suspend fun verifyTyped(p: Provider, model: String, role: ModelRole): String? =
        when (val outcome = probeSaved(p, model)) {
            ProbeOutcome.Passed -> {
                withContext(io) { store.markVerified(p.id, model) }
                null
            }
            is ProbeOutcome.NoToolCall -> if (role == ModelRole.Vision) {
                withContext(io) { store.markVerified(p.id, model) }
                null
            } else tr(R.string.model_probe_no_tools_pick, model)
            is ProbeOutcome.Failed -> outcome.message
        }

    // ---------- 用 ChatGPT 登录 ----------

    /**
     * 打开浏览器登录 ChatGPT,回调后换令牌、核对,列出模型,存成(或更新)ChatGPT 服务商,回到 Mika。
     * 还没有主模型时选它的一个模型为主模型。第一次注册成功后说明一次在用套餐额度。失败说原因(并记下给诊断信息)。
     * 跑在 [signIns] 里:用户在浏览器里登录时 Mika 在后台,不能跟着界面走。
     */
    fun signInWithChatGpt() {
        val browser = browser ?: return
        if (_state.value.chatGptSignIn == ListState.Running || signIns.running.value) return
        _state.update { it.copy(chatGptSignIn = ListState.Running, chatGptRevokeUnconfirmed = false) }
        signIns.start {
            val (listProblem, first) = try {
                withContext(io) {
                    val (hostId, clientId) = store.chatGptRegistration()
                    val existing = store.chatGptProvider()
                    val session = signInWithChatGpt(
                        store.chatGptAuth, hostId, clientId, existing?.let { store.chatGptSession(it.id) },
                        openBrowser = browser::open, onRegistered = store::rememberChatGptClient,
                        page = { e -> browser.page(e?.let { ModelsCopy.chatGptFailure(it.reason, it.code) }) },
                    )
                    val listed = store.listModels(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.accessToken)
                    val p = store.connectChatGpt(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty())
                    if (!store.roles.value.hasMain) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    (listed as? ModelListResult.Failed)?.message to (clientId == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? SignInException ?: SignInException(SignInFailure.Network, e.message.orEmpty(), e)
                signIns.recordFailure(failure)
                browser.returnToApp()
                _state.update { it.copy(chatGptSignIn = ListState.Failed(ModelsCopy.chatGptFailure(failure.reason, failure.code))) }
                return@start
            }
            browser.returnToApp()
            val id = store.chatGptProvider()?.id
            _state.update {
                snapshot(it.copy(
                    chatGptSignIn = ListState.Idle, chatGptWelcome = first, formOpen = false,
                    listing = if (listProblem != null && id != null) it.listing + (id to ListState.Failed(listProblem)) else it.listing - id.orEmpty(),
                ))
            }
        }
    }

    /** 放弃正在进行的登录(本机回调随之关掉)。 */
    fun cancelChatGptSignIn() {
        signIns.cancel()
        _state.update { it.copy(chatGptSignIn = ListState.Idle) }
    }

    fun dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

    /**
     * 用账号登录 xAI 或 OpenRouter:xAI 在浏览器里打开核对过的验证页、界面留下短码,等用户确认;
     * OpenRouter 打开授权页,同意后回到本机回调、换到一把属于用户的 Key。
     * 成功后列出模型、存下这一家,还没有主模型时选一个,再打开它的详情页([ModelSetupUi.signedInProvider])。
     * 失败只说类别和错误代码(并记下给诊断信息)。和 ChatGPT 一样跑在 [signIns] 里:用户在浏览器里时 Mika 在后台。
     */
    fun signInWithAccount(provider: AccountProvider) {
        val browser = browser ?: return
        if (signIns.running.value || _state.value.accountSignIn.running || _state.value.chatGptSignIn == ListState.Running) return
        _state.update { it.copy(accountSignIn = AccountSignInUi(provider = provider, running = true)) }
        val started = signIns.start(
            target = provider.target,
            noticeTitle = tr(R.string.account_signin_notif_title),
            noticeBody = tr(R.string.account_signin_notif_body),
        ) {
            try {
                val (p, failed) = withContext(io) {
                    val (p, listed) = when (provider) {
                        AccountProvider.Xai -> connectXai(browser)
                        AccountProvider.OpenRouter -> connectOpenRouter(browser)
                    }
                    if (!store.roles.value.hasMain) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    p to (listed as? ModelListResult.Failed)
                }
                browser.returnToApp()
                _state.update {
                    it.copy(listing = if (failed != null) it.listing + (p.id to ListState.Failed(failed.message)) else it.listing - p.id)
                }
                signIns.finishAccount(AccountOutcome(provider.target, providerId = p.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? AccountSignInException ?: AccountSignInException(AccountSignInFailure.Network, cause = e)
                signIns.recordAccountFailure(provider.name, failure.reason.name, failure.code)
                browser.returnToApp()
                signIns.finishAccount(AccountOutcome(provider.target, failure = ModelsCopy.accountFailure(failure)))
            }
        }
        if (!started) _state.update { it.copy(accountSignIn = AccountSignInUi()) }
    }

    /** xAI 设备码登录,列出模型,存成(或更新)那一家。 */
    private suspend fun connectXai(browser: SignInBrowser): Pair<Provider, ModelListResult> {
        val session = store.xaiAuth.signIn(openBrowser = browser::open, onChallenge = ::publishDevice)
        val existing = store.xaiProvider()
        val listed = store.listModels(ProviderConfig.Kind.Xai, session.apiBase.ifBlank { XaiAuth.API_BASE }, session.accessToken)
        return store.connectAccount(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
    }

    /** OpenRouter 授权换 Key,列出模型,按 OpenRouter 预设存下(或更新)那一家。 */
    private suspend fun connectOpenRouter(browser: SignInBrowser): Pair<Provider, ModelListResult> {
        val key = signInWithOpenRouter(store.openRouterAuth, browser::open, page = { e -> browser.page(e?.let(ModelsCopy::accountFailure)) })
        val existing = store.openRouterAccount()
        val listed = store.listModels(ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, key)
        return store.connectOpenRouter(key, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
    }

    /** 把短码留在界面上。打开浏览器由登录流程自己做,这里不再开一次。 */
    private fun publishDevice(c: DeviceChallenge) {
        val openUrl = c.verificationUriComplete ?: c.verificationUri
        val expiresAt = System.currentTimeMillis() + c.expiresInSec * 1000
        // 界面经 signIns.device 接着显示(重建后的界面也收得到)
        signIns.showDevice(DevicePrompt(c.userCode, openUrl, expiresAt))
    }

    fun cancelAccountSignIn() {
        signIns.cancel()
        _state.update { it.copy(accountSignIn = AccountSignInUi()) }
    }

    fun dismissAccountFailure() = _state.update { it.copy(accountSignIn = AccountSignInUi()) }

    /** 详情页打开过了。 */
    fun signedInShown() = _state.update { it.copy(signedInProvider = null) }

    /** 退出账号登录:xAI 清本机令牌,OpenRouter 删本机的 Key;服务商和用途留着,再登录接着用。 */
    fun signOutAccount(providerId: String) {
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                if (p.kind == ProviderConfig.Kind.Xai) store.signOutAccount(providerId) else store.signOutOpenRouter(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    /** 把这家默认的那个模型(见 [ModelOptions.defaultMain])设为主模型:和 [pick] 一样先试调用,进度在 [ModelSetupUi.manualPick]。 */
    fun useAsMain(providerId: String) {
        val p = store.provider(providerId) ?: return
        val options = ModelOptions.ofProvider(p, store.list())
        val m = ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id ?: return
        pick(ModelRole.Main, providerId, m)
    }

    /** 退出 ChatGPT 登录:服务端注销、本机清令牌;服务商和用途不动,再登录就能接着用。 */
    fun signOutChatGpt(providerId: String) {
        scope.launch {
            val revoked = withContext(io) { store.signOutChatGpt(providerId) }
            _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
        }
    }

    // ---------- 用途 ----------

    fun setReasoningEffort(role: ModelRole, effort: String?) {
        store.setReasoningEffort(role, effort)
        sync()
    }

    fun setModelContext(configId: String, settings: ModelContextSettings): Boolean {
        val saved = store.setModelContext(configId, settings)
        sync()
        return saved
    }

    fun setDailyTokenBudget(providerId: String, limit: Long?): Boolean {
        val saved = store.setDailyTokenBudget(providerId, limit)
        sync()
        return saved
    }

    fun setModelVision(providerId: String, model: String, sees: Boolean) {
        store.setModelVision(providerId, model, sees)
        sync()
    }

    fun setModelServiceTier(providerId: String, model: String, tier: OpenAiServiceTier?): Boolean {
        val saved = store.setModelServiceTier(providerId, model, tier)
        sync()
        return saved
    }

    /**
     * 服务商详情「用作」里明确改选用途。
     * 主模型和 [pick] 一样先试调用;看图、轻量是手填的(列表里没有)时也先试调用(见 [verifyTyped])。没通过不改;
     * 进度和原因在 [ModelSetupUi.manualPick]。保存成功后调 [onDone]。
     */
    fun useFromProvider(role: ModelRole, providerId: String, model: String, onDone: () -> Unit = {}) {
        saveModelSelection(
            providerId, model, clearNote = role == ModelRole.Main,
            // 主模型照样先试调用;看图、轻量只有手填的(列表里没有)才试,列表里的不试
            verify = when {
                role == ModelRole.Main -> { p, name -> verifyMain(p, name) }
                else -> { p, name -> if (listed(p, name)) null else verifyTyped(p, name, role) }
            },
            onPicked = onDone,
        ) { provider, name ->
            val caps = ModelOptions.ofProvider(provider, store.list()).firstOrNull { it.id == name }?.capabilities
                ?: ModelOptions.baseOf(provider)
            if (store.use(role, provider.id, name, caps)) null else tr(R.string.model_context_save_failed)
        }
    }

    /**
     * 保存从列表里选的对话模型。主模型先试调用这一个模型(见 [verifyMain]),通过才换,
     * 没通过原来的主模型不动;看图、轻量不试调用。手填的走 [pickManual]。
     */
    fun pick(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) =
        pickRole(role, providerId, model, onPicked, if (role == ModelRole.Main) { p, name -> verifyMain(p, name) } else null)

    /**
     * 选择页里手填的模型名:不论哪个用途都先试调用一次(见 [verifyTyped];主模型见 [verifyMain]),通了才用,
     * 列表里没有的记为手填;没通过说原因,原来的选择不动。
     */
    fun pickManual(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) =
        pickRole(role, providerId, model, onPicked) { p, name ->
            if (role == ModelRole.Main) verifyMain(p, name) else verifyTyped(p, name, role)
        }

    private fun pickRole(
        role: ModelRole, providerId: String, model: String, onPicked: () -> Unit,
        verify: (suspend (Provider, String) -> String?)?,
    ) {
        saveModelSelection(providerId, model, clearNote = role == ModelRole.Main, onPicked = onPicked, verify = verify) { provider, name ->
            if (use(role, provider, name, ModelOptions.ofProvider(provider, store.list()))) null
            else tr(R.string.model_context_save_failed)
        }
    }

    /** 保存从列表里选的备援模型,一般不试调用;主模型的备援例外见下。手填的走 [pickFallbackManual]。 */
    fun pickFallback(role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit = {}) =
        saveFallback(role, slot, providerId, model, onPicked, verify = if (role != ModelRole.Main) null else { p, name ->
            // 主模型的备援:跳过测试存下的那家、或列表里没有的(手填的),先试调用一次
            if (!p.savedUntested && listed(p, name)) null else verifyTyped(p, name, ModelRole.Main)
        })

    /**
     * [model] 在这家列出的模型目录里,且 Mika 用得上([ListedModel.usable]:目录写明只有别的接口的不算)。
     * 用不上的按手填处理,先试调用——目录里有、Mika 不会说的模型不能被静默设成看图/轻量/备援。
     */
    private fun listed(p: Provider, model: String): Boolean = p.models.orEmpty().any { it.id == model && it.usable }

    /** 备援里手填的模型名:先试调用一次(见 [verifyTyped]),通了才设上;没通过这一位不动。 */
    fun pickFallbackManual(role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit = {}) =
        saveFallback(role, slot, providerId, model, onPicked) { p, name -> verifyTyped(p, name, role) }

    private fun saveFallback(
        role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit,
        verify: (suspend (Provider, String) -> String?)?,
    ) {
        saveModelSelection(providerId, model, clearNote = true, onPicked = onPicked, verify = verify) { provider, name ->
            if (store.configFor(role)?.let { it.providerId == provider.id && it.model == name } == true) {
                return@saveModelSelection tr(if (role == ModelRole.Main) R.string.main_fallback_is_main else R.string.main_fallback_failed)
            }
            val caps = ModelOptions.ofProvider(provider, store.list()).firstOrNull { it.id == name }?.capabilities
                ?: ModelOptions.baseOf(provider)
            val targetCaps = if (role == ModelRole.Vision) caps.copy(vision = true) else caps.copy(toolCalls = true)
            val configId = store.ensureConfigId(provider.id, name, targetCaps)
            if (configId != null && store.setFallback(role, slot, configId)) null
            else tr(R.string.main_fallback_failed)
        }
    }

    /**
     * 选择页、「用作」面板保存一个模型:有 [verify] 的先过它(设主模型的试调用),
     * 再 [save]。进度和原因记在 [ModelSetupUi.manualPick]。
     */
    private fun saveModelSelection(
        providerId: String, model: String, clearNote: Boolean, onPicked: () -> Unit,
        verify: (suspend (Provider, String) -> String?)? = null,
        save: (Provider, String) -> String?,
    ) {
        val name = model.trim()
        if (name.isEmpty() || _state.value.manualPick[providerId] == ListState.Running) return
        _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Running)) }
        scope.launch {
            val problem = try {
                val checked = store.provider(providerId)
                when {
                    checked == null -> tr(R.string.model_context_save_failed)
                    else -> verify?.invoke(checked, name)
                } ?: withContext(io) {
                    val provider = store.provider(providerId) ?: return@withContext tr(R.string.model_context_save_failed)
                    val failure = save(provider, name)
                    if (failure == null && provider.models.orEmpty().none { it.id == name }) {
                        store.markTyped(providerId, name)
                    }
                    failure
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                tr(R.string.model_context_save_failed)
            }
            _state.update {
                snapshot(it.copy(
                    manualPick = if (problem == null) it.manualPick - providerId
                        else it.manualPick + (providerId to ListState.Failed(problem)),
                    probeNote = if (problem == null && clearNote) null else it.probeNote,
                    pickToProbe = if (problem == null && clearNote) null else it.pickToProbe,
                ))
            }
            if (problem == null) onPicked()
        }
    }

    /** 撤掉指定角色的第 [slot] 位备援。 */
    fun clearFallback(role: ModelRole, slot: Int) {
        scope.launch {
            withContext(io) { store.setFallback(role, slot, null) }
            sync()
        }
    }

    /** 撤掉第 [slot] 位主模型备援(项目 3)。 */
    fun clearFallback(slot: Int) = clearFallback(ModelRole.Main, slot)

    /** 看图、轻量的固定选项:「用主模型」或(只有看图)「不用」。 */
    fun choose(role: ModelRole, choice: RoleChoice) {
        scope.launch {
            withContext(io) { store.choose(role, choice) }
            sync()
        }
    }

    /** 把某个用途切到一个模型组;组不可用时不做。 */
    fun chooseGroup(role: ModelRole, groupId: String) {
        scope.launch {
            withContext(io) { store.choose(role, RoleChoice.Group(groupId)) }
            sync()
        }
    }

    /** 保存组(新建或替换同 id);成员配置不存在、或主模型组不满足全员支持工具时返回 false。 */
    suspend fun saveGroup(group: ModelGroup): Boolean = withContext(io) { store.saveGroup(group) }

    suspend fun deleteGroup(groupId: String) = withContext(io) { store.deleteGroup(groupId) }

    /** 组编辑时把一个「服务商 + 模型」加成成员:复用已有配置,没有就按当前能力建一条。 */
    suspend fun ensureConfig(providerId: String, model: String): String? = withContext(io) {
        val p = store.provider(providerId) ?: return@withContext null
        val caps = ModelOptions.ofProvider(p, store.list()).firstOrNull { it.id == model }?.capabilities
            ?: ModelOptions.baseOf(p)
        store.ensureConfig(providerId, model, caps)
    }

    // ---------- 语音转文字 ----------

    /** 选「手机自带」或「不用」,当场生效。 */
    fun pickSpeech(choice: SpeechChoice) {
        if (choice is SpeechChoice.Provider) return
        if (stt?.migrated == true) {
            when (choice) {
                SpeechChoice.Auto, SpeechChoice.System, SpeechChoice.Off -> {
                    if (choice != SpeechChoice.Off) speech?.retrySystem()
                    stt.select(choice)
                }
                else -> return
            }
        } else {
            speech?.set(choice)
        }
        _speechProbe.value = emptyMap()
        sync()
    }

    /** 迁移后的目录选择只写模型引用,不试转、不探测协议。 */
    fun pickSttModel(ref: VoiceModelRef, onPicked: () -> Unit = {}) {
        val controller = stt?.takeIf { it.migrated } ?: return
        controller.select(ref)
        _speechProbe.value = emptyMap()
        sync()
        onPicked()
    }

    /**
     * 迁移后按目录引用直接选择;迁移前不再拿静音试转探测接口,直接按服务商类型落成显式选择(默认接口),
     * 然后调 [onPicked]。旧选择经迁移后仍带着显式 [TranscribeMethod],运行时不重新探测。
     */
    fun pickSpeechModel(providerId: String, model: String, onPicked: () -> Unit = {}) {
        val m = model.trim()
        if (m.isEmpty() || _state.value.speechTesting != null) return
        val migrated = stt?.takeIf { it.migrated }
        if (migrated != null) {
            val snapshot = migrated.catalog.state.value
            val matches = snapshot.models.filter { candidate ->
                candidate.id == m && candidate.stt != null && snapshot.services.any { service ->
                    service.id == candidate.serviceId && (
                        service.id == "provider:$providerId" ||
                            (service.account as? VoiceAccount.ExistingProvider)?.providerId == providerId
                        )
                }
            }.map { VoiceModelRef(it.serviceId, it.id, VoiceCapability.STT) }
            if (matches.size == 1) pickSttModel(matches.single(), onPicked)
            return
        }
        val settings = speech ?: return
        val p = store.provider(providerId) ?: return
        // 迁移前没有目录可依,按服务商类型落成显式选择;默认接口,不再试转探测
        val method = if (p.kind == ProviderConfig.Kind.Gemini) TranscribeMethod.GeminiInline else TranscribeMethod.AudioTranscriptions
        settings.set(SpeechChoice.Provider(p.id, m, method))
        sync()
        onPicked()
    }

    private fun proxyReason(e: ModelError.Proxy): String = tr(
        if (e.failure == ProxyFailure.AuthFailed) R.string.net_proxy_auth_failed else R.string.net_proxy_unreachable,
    )

    /** 选择页打开时,上次试转的结果作废。 */
    fun clearSpeechProbe() { _speechProbe.value = emptyMap() }

    /** 选择页、「用作」面板打开时清掉上次保存的结果;还在试调用的留着(结束时自己更新)。 */
    fun clearManualPick() = _state.update { it.copy(manualPick = it.manualPick.filterValues { v -> v == ListState.Running }) }

    /** 只测试用户在服务商页确认的这一个模型。 */
    fun retestModel(providerId: String, model: String) {
        val provider = store.provider(providerId) ?: return
        val key = providerId to model
        if (model !in _state.value.usedChatModels(provider) || _state.value.retest[key] == ListState.Running) return
        _state.update { it.copy(retest = it.retest + (key to ListState.Running)) }
        scope.launch {
            val outcome = probeSaved(provider, model)
            val problem = when {
                outcome is ProbeOutcome.Failed -> outcome.message
                outcome is ProbeOutcome.NoToolCall && store.isMainModel(providerId, model) ->
                    tr(R.string.model_probe_no_tools_pick, model)
                else -> null
            }
            if (problem == null) withContext(io) { store.markVerified(providerId, model) }
            _state.update {
                snapshot(it.copy(retest = if (problem == null) it.retest - key else it.retest + (key to ListState.Failed(problem))))
            }
        }
    }

    private fun use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>): Boolean {
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        return store.use(role, p.id, model, caps)
    }

    // ---------- 一个服务商 ----------

    /** 重新列一次这个服务商的模型并记下;失败时记下原因。 */
    fun refresh(providerId: String) {
        val p = store.provider(providerId) ?: return
        if (refreshJobs[providerId]?.isActive == true) return
        _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
        refreshJobs[providerId] = scope.launch {
            val r = listSafely { store.listModels(p) }
            val models: List<ListedModel>? = when (r) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
            }
            if (models != null) withContext(io) { store.rememberModels(p.id, models) }
            val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(listMessage(r)) else ListState.Idle
            _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
        }
    }

    /** 选择页、服务商页打开时:还没记住列表的服务商列一次。 */
    fun ensureListed() {
        _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
    }

    /** 退出了登录的列不了模型,等再登录。 */
    private fun listable(p: Provider) = if (p.signIn != null) store.hasKey(p.id) else when (p.kind) {
        ProviderConfig.Kind.ChatGpt -> store.chatGptSession(p.id)?.signedIn == true
        ProviderConfig.Kind.Xai -> store.accountSession(p.id)?.signedIn == true
        else -> true
    }

    /**
     * 换 Key,然后用新 Key 重新列一次模型。零宽先去掉;换行和其它放不进请求头的字符不保存、不列模型。
     */
    fun changeKey(providerId: String, apiKey: String) {
        val k = headerKey(apiKey) ?: return
        if (k.isEmpty()) return
        scope.launch {
            try {
                withContext(io) { store.updateProviderKey(providerId, k) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
                return@launch
            }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
            sync()
        }
    }

    fun addProviderKey(providerId: String, apiKey: String) {
        val k = headerKey(apiKey) ?: return
        if (k.isEmpty()) return
        scope.launch {
            try {
                withContext(io) { store.addProviderKey(providerId, k) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
                return@launch
            }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
            sync()
        }
    }

    fun removeProviderKey(providerId: String, keyToRemove: String) {
        scope.launch {
            try {
                withContext(io) { store.removeProviderKey(providerId, keyToRemove) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
                return@launch
            }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
            sync()
        }
    }

    /** 删服务商前预览它名下的语音模型能力引用;删除面板靠它说清影响。 */
    fun providerVoiceUsage(providerId: String): List<com.wangzan101.mika.voice.model.VoiceModelRef> =
        voiceAccounts?.providerVoiceUsage(providerId) ?: emptyList()

    /** 删掉服务商和它的 Key;用着它的用途退回默认,依赖它的语音模型一并清掉。 */
    fun removeProvider(providerId: String) {
        scope.launch {
            withContext(io) {
                // ChatGPT 的先在服务端注销登录,再删
                when (store.provider(providerId)?.kind) {
                    ProviderConfig.Kind.ChatGpt -> store.signOutChatGpt(providerId)
                    ProviderConfig.Kind.Xai -> store.signOutAccount(providerId)
                    else -> Unit
                }
                // OpenRouter 授权来的 Key 随服务商一起删;OpenRouter 那边的 Key 要用户自己到 Keys 页面删
                store.removeProvider(providerId)
                voiceAccounts?.deleteProviderWithVoiceUsage(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    companion object {
        /** 用户指定模型的试调用最多等多久。 */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** 自定义服务商的名字最多几个字(服务商列表一行放得下)。 */
        const val MAX_NAME = 32
    }
}
