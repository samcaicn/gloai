package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.ProviderPreset
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.SignInTarget
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.rememberModels
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot

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
