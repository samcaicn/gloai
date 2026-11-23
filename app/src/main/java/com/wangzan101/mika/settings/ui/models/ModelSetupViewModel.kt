package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SignInTarget
import com.wangzan101.mika.settings.accountSession
import com.wangzan101.mika.settings.chatGptProvider
import com.wangzan101.mika.settings.chatGptSession
import com.wangzan101.mika.settings.hasKey
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.providerKeys
import com.wangzan101.mika.settings.xaiProvider
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechSettings
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.model.SttSelectionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 配置模型的状态与动作。添加服务商时「测试并保存」列出模型就存下这家;还没有主模型时再让推荐的一个试调用,通了才当主模型。
 * 改了服务商、地址或 Key,进行中的测试作废。用途:从所有已连接服务商记住的模型里选;主模型要先试调用通过
 * (试通过过的不再试),看图、轻量、备援选中即生效;看图还可以「用主模型」「不用」,轻量可以「用主模型」。
 * 首次上手和设置 › 模型共用。
 */
class ModelSetupViewModel(
    internal val store: ProviderStore,
    internal val scope: CoroutineScope,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    internal val browser: SignInBrowser? = null,
    /** 登录跑在哪:界面给的是 App 那一份(退到后台、界面重建都不断,期间开前台服务);测试里就用 [scope]。 */
    internal val signIns: ChatGptSignIns = ChatGptSignIns(scope),
    /** 语音转文字的选择;测试和上手流程不传,卡片就不出现。 */
    internal val speech: SpeechSettings? = null,
    internal val systemSpeech: () -> SystemCapability = { SystemCapability(available = false, onDevice = false) },
    internal val stt: SttSelectionController? = null,
    /** 统一语音目录的账号管理:删除服务商时一并清掉依赖它的语音模型;测试和上手流程可不传。 */
    internal val voiceAccounts: com.wangzan101.mika.voice.model.VoiceAccountManager? = null,
) {
    internal val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    internal val _speechProbe = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 服务商 id -> 选它的语音模型时试转没通过的原因(用现有的说法);选通了或重新选时清掉。 */
    val speechProbe: StateFlow<Map<String, String>> = _speechProbe.asStateFlow()

    internal var testJob: Job? = null
    internal val refreshJobs = mutableMapOf<String, Job>()

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

    internal fun snapshot(ui: ModelSetupUi): ModelSetupUi {
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

    internal fun sync() = _state.update(::snapshot)

    // ---------- 添加服务商 ----------

    /**
     * 试调用的结论:通过、[model] 没调用工具、失败([message] 是给人看的原因)。
     */
    internal sealed interface ProbeOutcome {
        data object Passed : ProbeOutcome
        data class NoToolCall(val model: String) : ProbeOutcome
        data class Failed(val message: String) : ProbeOutcome
    }

    companion object {
        /** 用户指定模型的试调用最多等多久。 */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** 自定义服务商的名字最多几个字(服务商列表一行放得下)。 */
        const val MAX_NAME = 32
    }
}
