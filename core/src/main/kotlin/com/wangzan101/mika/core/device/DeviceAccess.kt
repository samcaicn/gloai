package com.wangzan101.mika.core.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 主屏状态,与 Agent 的虚拟屏无关。读不到状态时由端口报错,不推定已解锁。 */
data class DeviceState(val interactive: Boolean, val keyguardShowing: Boolean, val deviceLocked: Boolean,
    val secure: Boolean, val helperAvailable: Boolean, val userUnlocked: Boolean) {
    val ready: Boolean get() = interactive && !keyguardShowing && !deviceLocked && userUnlocked
}

enum class CredentialKind { Pin, Password }

/** 凭据只在本机确定性流程内短暂存在;不提供带原文的 toString。用完擦除。 */
class DeviceCredential(val kind: CredentialKind, val value: CharArray, val revision: String = "") : AutoCloseable {
    override fun close() { value.fill('\u0000') }
    override fun toString() = "DeviceCredential($kind, redacted)"

    companion object {
        fun valid(kind: CredentialKind, value: CharArray): Boolean = when (kind) {
            CredentialKind.Pin -> value.size in 4..16 && value.all { it in '0'..'9' }
            CredentialKind.Password -> value.size in 4..64 && value.all { it in ' '..'~' }
        }
    }
}

/** 平台适配:唤醒、显示系统凭据页、确认输入目标、输入。绝不改变系统锁屏凭据。 */
interface DeviceAccessPort {
    suspend fun state(): DeviceState
    suspend fun wake(): Boolean
    suspend fun dismiss(): Boolean
    /** 安全锁屏还停在通知/指纹页时,尝试一次通用的系统导航来露出凭据页;不识别文字,不输入凭据。 */
    suspend fun revealCredential(): Boolean = false
    /** 上滑后仍是生物识别优先页、没有凭据框时,做一次通用的回退点击(点最大的可点目标);找不到目标就不动。 */
    suspend fun revealCredentialFallback(): Boolean = false
    suspend fun credentialReady(kind: CredentialKind): Boolean
    /** 只读识别当前唯一的系统凭据框;未知、歧义或平台不支持时返回 null,不聚焦或输入。 */
    suspend fun detectedCredentialKind(): CredentialKind? = null
    /** 认不出凭据页时给出不含屏上文字和凭据的受限诊断,便于定位通用识别规则的缺口。 */
    suspend fun credentialDiagnostics(): List<String> = emptyList()
    suspend fun enter(credential: DeviceCredential): Boolean
}

/** 许可和凭据来自手机设置,电脑不能修改。输入前持久化占位,失败/取消/崩溃后不再自动重试。 */
interface DeviceAccessStore {
    val enabled: Boolean
    val credentialConfigured: Boolean
    val attemptBlocked: Boolean
    fun credential(): DeviceCredential?
    fun beginAttempt(credential: DeviceCredential): Boolean
    fun completeAttempt(credential: DeviceCredential)
}

data class DeviceAccessResult(val status: String, val state: DeviceState?, val enabled: Boolean,
    val credentialConfigured: Boolean, val attemptBlocked: Boolean,
    val storedKind: CredentialKind? = null, val detectedKind: CredentialKind? = null,
    val keyguardDiagnostics: List<String> = emptyList(),
    /** 这次流程真的把手机从熄屏/锁屏带到可用(唤醒、上滑或注入凭据);用户中途自己解开的不算。 */
    val performedUnlock: Boolean = false)

/** 整个进程共用一份。并发调用串行化,成功依据系统状态,不依据按键或命令的返回码。 */
class DeviceAccess(private val port: DeviceAccessPort, private val store: DeviceAccessStore,
    private val pollMs: Long = 150, private val waitMs: Long = 5_000) {
    private val mutex = Mutex()

    suspend fun state(): DeviceAccessResult {
        val s = read()
        return result(if (s == null) "device_unavailable" else "state", s)
    }

    suspend fun wake(): DeviceAccessResult = mutex.withLock {
        prepare()?.let { return@withLock it }
        val before = read() ?: return@withLock result("device_unavailable")
        if (before.interactive) return@withLock result("awake", before)
        if (!port.wake()) return@withLock result("wake_failed", read())
        val after = await { it.interactive }
        result(if (after?.interactive == true) "awake" else "wake_failed", after)
    }

    /** 来源授权只能进一步收窄本机总开关；等待锁和系统界面后、每次操作前都重新核对。 */
    suspend fun unlock(authorized: () -> Boolean = { true }): DeviceAccessResult = mutex.withLock {
        fun allowed() = store.enabled && authorized()
        prepare(authorized)?.let { return@withLock it }
        var now = read() ?: return@withLock result("device_unavailable")
        if (now.ready) return@withLock result("unlocked", now)
        var woke = false
        if (!now.interactive) {
            if (!allowed()) return@withLock result("disabled", now)
            if (!port.wake()) return@withLock result("wake_failed", read())
            woke = true
            now = await { !allowed() || it.interactive } ?: return@withLock result("device_unavailable")
            if (!allowed()) return@withLock result("disabled", now)
            if (!now.interactive) return@withLock result("wake_failed", now)
        }
        if (!allowed()) return@withLock result("disabled", now)
        val wasSecure = now.secure
        if (!port.dismiss()) return@withLock result("dismiss_failed", read())
        now = await { !allowed() || it.ready || it.secure && it.keyguardShowing } ?: return@withLock result("device_unavailable")
        if (!allowed()) return@withLock result("disabled", now)
        // 非安全锁屏由这次流程上滑解开、或这次流程唤醒了熄屏:算这次解的;安全锁屏没输入凭据就开了是用户同时自己解的
        if (now.ready) return@withLock result("unlocked", now, performedUnlock = woke || !wasSecure)
        if (!now.secure) return@withLock result("unlock_failed", now)
        if (store.attemptBlocked) return@withLock result("attempt_blocked", now)
        val credential = store.credential() ?: return@withLock result("credential_required", now)
        credential.use {
            if (!DeviceCredential.valid(it.kind, it.value)) return@withLock result("credential_unavailable", now)
            var targetReady = false
            var revealed = false
            var fallbackTried = false
            var waited = 0L
            while (!targetReady && waited < waitMs) {
                val current = read() ?: return@withLock result("device_unavailable")
                if (current.ready) return@withLock result("unlocked", current)
                if (!allowed()) return@withLock result("disabled", current)
                if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                targetReady = port.credentialReady(it.kind)
                // dismiss 后先给系统凭据页一个轮询周期;仍认不出时才做一次导航,避免在已经可用的 PIN 页上多余滑动。
                if (!targetReady) {
                    if (!revealed && waited >= pollMs) {
                        if (!allowed()) return@withLock result("disabled", current)
                        revealed = true
                        port.revealCredential()
                    } else if (revealed && !fallbackTried && waited >= pollMs * 2) {
                        // 上滑后仍是生物识别优先页(没有凭据框)时,再给一次回退点击的机会。
                        if (!allowed()) return@withLock result("disabled", current)
                        fallbackTried = true
                        port.revealCredentialFallback()
                    }
                    delay(pollMs); waited += pollMs
                }
            }
            if (!targetReady) {
                val current = read() ?: return@withLock result("device_unavailable")
                if (current.ready) return@withLock result("unlocked", current)
                if (!allowed()) return@withLock result("disabled", current)
                if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                val detected = port.detectedCredentialKind()
                val diagnostics = port.credentialDiagnostics()
                if (detected != null && detected != it.kind) {
                    return@withLock result("credential_kind_mismatch", current, it.kind, detected, diagnostics)
                }
                return@withLock result("unsupported_keyguard", current, keyguardDiagnostics = diagnostics)
            }
            if (!allowed()) return@withLock result("disabled", read())
            if (!store.beginAttempt(it)) return@withLock result("attempt_blocked", read())
            // 从此刻起中断也保留占位,不重复输入。只有系统已解锁才清掉。
            if (!allowed()) return@withLock result("disabled", read())
            if (!port.enter(it)) {
                val after = read()
                if (after?.ready == true) { store.completeAttempt(it); return@withLock result("unlocked", after) }
                return@withLock result("input_failed", after)
            }
            now = await { s -> s.ready } ?: return@withLock result("device_unavailable")
            if (now.ready) { store.completeAttempt(it); result("unlocked", now, performedUnlock = true) }
            else result("unlock_failed", now)
        }
    }

    private suspend fun prepare(authorized: () -> Boolean = { true }): DeviceAccessResult? {
        if (!store.enabled || !authorized()) return result("disabled", read())
        val s = read() ?: return result("device_unavailable")
        if (!s.userUnlocked) return result("first_unlock_required", s)
        if (!s.helperAvailable) return result("helper_unavailable", s)
        return null
    }

    private suspend fun read(): DeviceState? = try { port.state() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    private suspend fun await(done: (DeviceState) -> Boolean): DeviceState? {
        var waited = 0L
        var s = read()
        while (s != null && !done(s) && waited < waitMs) { delay(pollMs); waited += pollMs; s = read() }
        return s
    }
    private fun result(status: String, s: DeviceState? = null, storedKind: CredentialKind? = null, detectedKind: CredentialKind? = null,
        keyguardDiagnostics: List<String> = emptyList(), performedUnlock: Boolean = false) =
        DeviceAccessResult(status, s, store.enabled, store.credentialConfigured, store.attemptBlocked, storedKind, detectedKind,
            keyguardDiagnostics, performedUnlock)
}
