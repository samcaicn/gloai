package com.wangzan101.mika.settings

import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.core.model.chatgpt.SignInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 最近一次登录失败,只给诊断信息用:时间、原因的类别、服务端的错误代码、一小段说明(已脱敏)。 */
data class ChatGptSignInFailureRecord(val atMs: Long, val kind: String, val code: String?, val detail: String)

/** 这次登录是哪一家。前台服务和界面重建时靠它认,避免把别家的登录显示成 ChatGPT。 */
enum class SignInTarget { None, ChatGpt, Xai, OpenRouter }

/** 设备码登录给用户看的用户码、验证页和到什么时候失效。设备码本身不在这里。 */
data class DevicePrompt(val userCode: String, val openUrl: String, val expiresAtMs: Long = 0)

/**
 * 一次账号登录(xAI、OpenRouter)的结果:成功是存下的服务商 id,失败是给用户看的原因。
 * 放在 [ChatGptSignIns] 里而不是界面状态里:登录期间界面可能重建,结果要交给当时在的那一个界面。
 */
data class AccountOutcome(val target: SignInTarget, val providerId: String? = null, val failure: String? = null)

/**
 * 用账号登录(ChatGPT、xAI、OpenRouter)在进程里的那一份。登录要等用户在外部浏览器里输密码、过两步验证,期间 Mika 在后台:
 * 跑在 App 的作用域 [scope] 里(界面重建、退到后台都不断),开始时调 [keepAlive] 撑住进程(前台服务,
 * 免得进程被冻住、本机回调没人接);它起不来只记一句,登录照常。前台服务看着 [running],变回假就自己停,
 * 所以成功、失败、取消、超时都会停。同一时刻只有一次登录;[cancel] 放弃(通知上的「取消」也走这里)。
 */
class ChatGptSignIns(
    private val scope: CoroutineScope,
    private val keepAlive: () -> Unit = {},
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** 最近一次失败(见 [recordFailure]);只在内存里。 */
    @Volatile var lastFailure: ChatGptSignInFailureRecord? = null
        private set

    /** 别的账号登录(xAI、OpenRouter)最近一次登录失败;说明同样已脱敏。 */
    @Volatile var lastAccountFailure: ChatGptSignInFailureRecord? = null
        private set

    /** 正在进行的是哪一家;没有在登录时是 [SignInTarget.None]。 */
    @Volatile var target: SignInTarget = SignInTarget.None
        private set

    /** 前台通知上的文字;空则用 ChatGPT 的那两句。 */
    @Volatile var noticeTitle: String? = null
        private set

    @Volatile var noticeBody: String? = null
        private set

    private val _device = MutableStateFlow<DevicePrompt?>(null)
    /** 设备码登录正在显示的用户码;登录结束就清掉。界面重建后靠它接着显示。 */
    val device: StateFlow<DevicePrompt?> = _device.asStateFlow()
    val devicePrompt: DevicePrompt? get() = _device.value

    fun showDevice(prompt: DevicePrompt) {
        _device.value = prompt
    }

    private val _outcome = MutableStateFlow<AccountOutcome?>(null)
    /** 最近一次账号登录的结果,界面拿去显示后 [consumeOutcome]。 */
    val outcome: StateFlow<AccountOutcome?> = _outcome.asStateFlow()

    fun finishAccount(o: AccountOutcome) {
        _outcome.value = o
    }

    fun consumeOutcome(o: AccountOutcome) {
        _outcome.compareAndSet(o, null)
    }

    /** 开始一次登录,跑 [block];已有一次在跑时不开,返回假。 */
    fun start(
        target: SignInTarget = SignInTarget.ChatGpt,
        noticeTitle: String? = null,
        noticeBody: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean = synchronized(this) {
        if (_running.value) return false
        this.target = target
        this.noticeTitle = noticeTitle
        this.noticeBody = noticeBody
        _device.value = null
        _outcome.value = null
        _running.value = true
        runCatching(keepAlive).onFailure { log("sign-in keep-alive could not start", it) }
        // App 的作用域没有异常处理:漏出来的异常只记下,不让进程崩
        val j = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sign-in ended with an unexpected error", e)
            }
        }
        job = j
        j.invokeOnCompletion {
            synchronized(this) {
                if (job === j) {
                    job = null
                    _running.value = false
                    this.target = SignInTarget.None
                    _device.value = null
                    this.noticeTitle = null
                    this.noticeBody = null
                }
            }
        }
        true
    }

    fun cancel() {
        synchronized(this) { job }?.cancel()
    }

    fun recordFailure(e: SignInException) {
        val detail = Diagnostics.sanitize(e.message.orEmpty()).take(MAX_DETAIL)
        lastFailure = ChatGptSignInFailureRecord(now(), e.reason.name, e.code, detail)
    }

    /** 订阅账号登录失败。只记下类别和错误代码,说明再过一遍脱敏。 */
    fun recordAccountFailure(provider: String, reason: String, code: String?) {
        val detail = Diagnostics.sanitize(listOfNotNull(reason, code).joinToString(": ")).take(MAX_DETAIL)
        lastAccountFailure = ChatGptSignInFailureRecord(now(), provider, code, detail)
    }

    private companion object {
        const val MAX_DETAIL = 160
    }
}
