package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.SignInException
import com.wangzan101.mika.core.model.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.oauth.AccountSignInException
import com.wangzan101.mika.core.model.oauth.AccountSignInFailure
import com.wangzan101.mika.core.model.oauth.DeviceChallenge
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.core.model.oauth.XaiAuth
import com.wangzan101.mika.core.model.oauth.signInWithOpenRouter
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.AccountOutcome
import com.wangzan101.mika.settings.DevicePrompt
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.chatGptProvider
import com.wangzan101.mika.settings.chatGptRegistration
import com.wangzan101.mika.settings.chatGptSession
import com.wangzan101.mika.settings.connectAccount
import com.wangzan101.mika.settings.connectChatGpt
import com.wangzan101.mika.settings.connectOpenRouter
import com.wangzan101.mika.settings.listModels
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.rememberChatGptClient
import com.wangzan101.mika.settings.signOutAccount
import com.wangzan101.mika.settings.signOutChatGpt
import com.wangzan101.mika.settings.signOutOpenRouter
import com.wangzan101.mika.settings.xaiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 模型设置:ChatGPT 与账号登录、退出
/**
 * 打开浏览器登录 ChatGPT,回调后换令牌、核对,列出模型,存成(或更新)ChatGPT 服务商,回到 Mika。
 * 还没有主模型时选它的一个模型为主模型。第一次注册成功后说明一次在用套餐额度。失败说原因(并记下给诊断信息)。
 * 跑在 [signIns] 里:用户在浏览器里登录时 Mika 在后台,不能跟着界面走。
 */
fun ModelSetupViewModel.signInWithChatGpt() {
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
fun ModelSetupViewModel.cancelChatGptSignIn() {
    signIns.cancel()
    _state.update { it.copy(chatGptSignIn = ListState.Idle) }
}

fun ModelSetupViewModel.dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

/**
 * 用账号登录 xAI 或 OpenRouter:xAI 在浏览器里打开核对过的验证页、界面留下短码,等用户确认;
 * OpenRouter 打开授权页,同意后回到本机回调、换到一把属于用户的 Key。
 * 成功后列出模型、存下这一家,还没有主模型时选一个,再打开它的详情页([ModelSetupUi.signedInProvider])。
 * 失败只说类别和错误代码(并记下给诊断信息)。和 ChatGPT 一样跑在 [signIns] 里:用户在浏览器里时 Mika 在后台。
 */
fun ModelSetupViewModel.signInWithAccount(provider: AccountProvider) {
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
internal suspend fun ModelSetupViewModel.connectXai(browser: SignInBrowser): Pair<Provider, ModelListResult> {
    val session = store.xaiAuth.signIn(openBrowser = browser::open, onChallenge = ::publishDevice)
    val existing = store.xaiProvider()
    val listed = store.listModels(ProviderConfig.Kind.Xai, session.apiBase.ifBlank { XaiAuth.API_BASE }, session.accessToken)
    return store.connectAccount(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
}

/** OpenRouter 授权换 Key,列出模型,按 OpenRouter 预设存下(或更新)那一家。 */
internal suspend fun ModelSetupViewModel.connectOpenRouter(browser: SignInBrowser): Pair<Provider, ModelListResult> {
    val key = signInWithOpenRouter(store.openRouterAuth, browser::open, page = { e -> browser.page(e?.let(ModelsCopy::accountFailure)) })
    val existing = store.openRouterAccount()
    val listed = store.listModels(ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, key)
    return store.connectOpenRouter(key, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
}

/** 把短码留在界面上。打开浏览器由登录流程自己做,这里不再开一次。 */
internal fun ModelSetupViewModel.publishDevice(c: DeviceChallenge) {
    val openUrl = c.verificationUriComplete ?: c.verificationUri
    val expiresAt = System.currentTimeMillis() + c.expiresInSec * 1000
    // 界面经 signIns.device 接着显示(重建后的界面也收得到)
    signIns.showDevice(DevicePrompt(c.userCode, openUrl, expiresAt))
}

fun ModelSetupViewModel.cancelAccountSignIn() {
    signIns.cancel()
    _state.update { it.copy(accountSignIn = AccountSignInUi()) }
}

fun ModelSetupViewModel.dismissAccountFailure() = _state.update { it.copy(accountSignIn = AccountSignInUi()) }

/** 详情页打开过了。 */
fun ModelSetupViewModel.signedInShown() = _state.update { it.copy(signedInProvider = null) }

/** 退出账号登录:xAI 清本机令牌,OpenRouter 删本机的 Key;服务商和用途留着,再登录接着用。 */
fun ModelSetupViewModel.signOutAccount(providerId: String) {
    scope.launch {
        withContext(io) {
            val p = store.provider(providerId) ?: return@withContext
            if (p.kind == ProviderConfig.Kind.Xai) store.signOutAccount(providerId) else store.signOutOpenRouter(providerId)
        }
        _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
    }
}

/** 退出 ChatGPT 登录:服务端注销、本机清令牌;服务商和用途不动,再登录就能接着用。 */
fun ModelSetupViewModel.signOutChatGpt(providerId: String) {
    scope.launch {
        val revoked = withContext(io) { store.signOutChatGpt(providerId) }
        _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
    }
}
