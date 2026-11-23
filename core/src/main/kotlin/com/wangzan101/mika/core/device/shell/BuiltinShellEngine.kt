package com.wangzan101.mika.core.device.shell

import com.wangzan101.mika.core.device.adb.AdbConnectionException
import com.wangzan101.mika.core.device.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.device.adb.AdbPairingException
import com.wangzan101.mika.core.device.adb.AdbServiceTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 引擎要的平台能力。阻塞调用都在 IO 调度器上执行。 */
interface BuiltinShellPlatform {
    fun paired(): Boolean
    fun setPaired(paired: Boolean)
    /** 无线调试开关;读不到为 null(不拦着往下走)。 */
    fun wirelessDebugging(): Boolean?
    fun wifiConnected(): Boolean
    suspend fun findPairingPort(timeoutMs: Long): Int?
    /** 用配对码配对;失败抛 [AdbPairingException]。 */
    fun pair(port: Int, code: String)
    /** 连本机 adbd 并起助手(不等交接);失败抛 [AdbConnectionException],存着的钥匙读不出抛 [AdbKeyUnreadableException]。 */
    suspend fun launchHelper()
    /** 让正在跑的助手撤掉改动并退出;没有就什么都不做。 */
    fun stopHelper()
    /** 删掉配对钥匙和交接口令;「用 root 启动」也一并关掉(解除配对就是停止手机操作,看门狗不再经 su 把助手起回来)。 */
    fun forget()
    /** 没配对时收下过助手(是电脑经 USB 起的):它停了要告诉用户怎么再起,而不是当作从没设置过。持久化。 */
    fun usbStarted(): Boolean
    fun setUsbStarted(on: Boolean)
    /** 「用 root 启动」此刻能不能用(用户的开关、有没有 su、su 上一次的答复);默认没有这条路。 */
    fun rootStart(): RootStart = RootStart.Off
    /**
     * 经 su 以 shell 身份起助手(不等交接),返回 su 的答复:[SuAnswer.Granted] 时助手已在起,交接由引擎等;
     * 手机上找不到 su 了为 null。失败抛异常(当作原因不明)。
     */
    suspend fun launchHelperAsRoot(): SuAnswer? = null
    /** 记下 su 的答复,持久化;null 是忘掉上一次的拒绝(用户点了重试)。 */
    fun setRootAnswer(answer: SuAnswer?) {}
    /** 用户明确点了 Root 失败后的重试。返回 true 表示平台已发起异步授权,完成后会通知引擎。 */
    fun requestRootStart(): Boolean = false
    /** 当前选择的启动通道;默认 [ShellTransport.Auto]。 */
    fun transport(): ShellTransport = ShellTransport.Auto
    /** 持久化并更新启动通道选择。 */
    fun setTransport(transport: ShellTransport) {}
    /** 此刻 Shizuku 的安装、运行和授权状态;默认不可用。 */
    fun shizukuState(): ShizukuPlatformState = ShizukuPlatformState.Unavailable
    /** 经 Shizuku 调起助手(不等交接);成功返回 null,失败返回具体原因。 */
    suspend fun launchHelperViaShizuku(): BuiltinShellFailure? = BuiltinShellFailure.ShizukuNotRunning
}

/** 看门狗重起助手的间隔:第 n 次失败后等 base·2^(n-1),封顶 [maxMs];连续失败 [maxAttempts] 次后停下等人或等开关变化。 */
data class Backoff(val baseMs: Long = 2_000, val maxMs: Long = 60_000, val maxAttempts: Int = 5) {
    fun delayAfter(failures: Int): Long? {
        if (failures >= maxAttempts) return null
        return (baseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}

object BuiltinShellFailures {
    fun ofPairing(reason: AdbPairingException.Reason) = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
        // 配对端口连不上:配对窗口已经关了(端口随窗口一起消失)
        AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> BuiltinShellFailure.Unknown
        AdbPairingException.Reason.NOT_LOCAL -> BuiltinShellFailure.NotLocal
    }

    fun ofConnection(reason: AdbConnectionException.Reason) = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> BuiltinShellFailure.AuthorizationRevoked
        AdbConnectionException.Reason.NOT_LOCAL -> BuiltinShellFailure.NotLocal
        AdbConnectionException.Reason.CONNECT_FAILED,
        AdbConnectionException.Reason.TLS_FAILED,
        AdbConnectionException.Reason.PROTOCOL -> BuiltinShellFailure.ConnectFailed
    }

    /** 看门狗自己再试有没有用:钥匙被撤销、地址不合法、没 Wi-Fi、缺 Shizuku/授权要等用户或开关变化。 */
    fun retryable(f: BuiltinShellFailure) = f == BuiltinShellFailure.ConnectFailed ||
        f == BuiltinShellFailure.HelperStartFailed || f == BuiltinShellFailure.ShizukuBindFailed ||
        f == BuiltinShellFailure.Unknown
}

/**
 * 内置助手的状态与看门狗。[helper] 有值就是助手的 Binder 在手(Running);
 * 没有时:Mika 自己起得来(用户打开了「用 root 启动」且能用,或配过对)就先等 [graceMs](还活着的助手会在 Mika 进程起来后自己交接),
 * 再起一个:先经 su(见 [BuiltinShellPlatform.rootStart]),不行或没打开再经 adbd;失败按 [backoff] 重试。
 * su 拒绝或不回话时记下来,不再自己去问,等用户点重试。
 * 要起助手时无线调试关着,有 [autoOn] 就先替用户打开(条件和停下的规矩见 [WirelessDebuggingAutoOn])。
 */
class BuiltinShellEngine(
    private val platform: BuiltinShellPlatform,
    private val helper: StateFlow<Any?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoff: Backoff = Backoff(),
    private val graceMs: Long = 3_000,
    private val handoffTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 10 * 60_000L,
    private val pairingPortTimeoutMs: Long = 5_000,
    private val autoOn: WirelessDebuggingAutoOn? = null,
) : BuiltinShellControl {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()
    private val _discovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _discovery.asStateFlow()

    /** 最近一次配对或连接失败的异常类别和说明(诊断信息用,原文未脱敏);连上后清空。 */
    @Volatile var lastError: String? = null
        private set

    private val launchLock = Mutex()
    private var failures = 0
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var pendingImmediate = false
    private var started = false

    override val paired: Boolean get() = platform.paired()

    override val transport: ShellTransport get() = platform.transport()

    override val shizukuStart: Boolean
        get() = platform.transport() != ShellTransport.WirelessDebugging && platform.shizukuState().ready

    override val restart: HelperRestart get() = when {
        platform.transport() == ShellTransport.Shizuku ||
            (platform.transport() == ShellTransport.Auto &&
                (platform.shizukuState().ready || (!platform.paired() && platform.shizukuState().installed))) -> HelperRestart.Shizuku
        platform.paired() -> HelperRestart.Wireless
        platform.usbStarted() -> HelperRestart.UsbCommand
        else -> HelperRestart.None
    }

    override val rootStart: Boolean get() = platform.rootStart() == RootStart.Ready

    /** Mika 自己起得来助手:按当前通道看 Shizuku、su 或已配对的无线调试。 */
    private fun selfStart(): Boolean = when (platform.transport()) {
        ShellTransport.Shizuku -> platform.shizukuState().ready
        ShellTransport.WirelessDebugging -> rootStart || platform.paired()
        ShellTransport.Auto -> platform.shizukuState().ready || rootStart || platform.paired()
    }

    private fun initialStatus(): BuiltinShellStatus = when {
        helper.value != null -> BuiltinShellStatus.Running
        selfStart() -> BuiltinShellStatus.Connecting
        // 电脑起过的助手可能还活着,等它来交接
        platform.transport() != ShellTransport.Shizuku && platform.usbStarted() -> BuiltinShellStatus.Connecting
        else -> idleStatus()
    }

    /**
     * Mika 自己起不来、手里也没有助手时的状态:Shizuku 模式下报 Shizuku 缺哪一步;
     * 自动模式下若未配对且已装 Shizuku 也如实提示;否则看 root、电脑启动或未配对。
     */
    private fun idleStatus(): BuiltinShellStatus = when (platform.transport()) {
        ShellTransport.Shizuku -> BuiltinShellStatus.Failed(platform.shizukuState().failure())
        ShellTransport.WirelessDebugging -> wirelessOrRootIdleStatus()
        ShellTransport.Auto -> {
            if (!platform.paired() && platform.rootStart() == RootStart.Off && !platform.usbStarted() && platform.shizukuState().installed) {
                BuiltinShellStatus.Failed(platform.shizukuState().failure())
            } else {
                wirelessOrRootIdleStatus()
            }
        }
    }

    private fun wirelessOrRootIdleStatus(): BuiltinShellStatus {
        platform.rootStart().failure?.let { return BuiltinShellStatus.Failed(it) }
        return if (platform.usbStarted()) BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) else BuiltinShellStatus.NotPaired
    }

    /** 开始看着助手:掉线就重起。只在功能打开时调。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        var first = true
        scope.launch {
            helper.collect { h ->
                if (h != null) {
                    synchronized(this@BuiltinShellEngine) { failures = 0; retryJob?.cancel(); pendingImmediate = false }
                    lastError = null
                    autoOn?.reset()
                    // 没配对、也没用 root 或 Shizuku 就来了:是电脑经 USB 起的。记下来,它停了才知道该怎么提示
                    if (!platform.paired() && platform.rootStart() == RootStart.Off && !shizukuStart && platform.transport() != ShellTransport.Shizuku) {
                        platform.setUsbStarted(true)
                    }
                    _status.value = BuiltinShellStatus.Running
                } else if (!selfStart()) {
                    // App 刚起来时还活着的助手会自己来交接;用着用着断了也可能是正在换一个新的来交接:
                    // 都先等一会儿再说它停了,免得界面一闪「已停止」
                    if (platform.transport() != ShellTransport.Shizuku && platform.usbStarted()) {
                        _status.value = BuiltinShellStatus.Connecting
                        schedule(graceMs)
                    } else {
                        _status.value = idleStatus()
                    }
                } else {
                    if (_status.value == BuiltinShellStatus.Running) _status.value = BuiltinShellStatus.Connecting
                    val alreadyImmediate = synchronized(this@BuiltinShellEngine) { pendingImmediate && retryJob?.isActive == true }
                    if (!alreadyImmediate) {
                        schedule(if (first) (if (shizukuStart) 0L else graceMs) else backoff.baseMs)
                    }
                }
                first = false
            }
        }
    }

    /** 有人要用助手却没有:没在起、也没排着重试就马上起一次。 */
    fun need() {
        if (helper.value != null || !selfStart()) return
        synchronized(this) { if (retryJob?.isActive == true) return }
        if (launchLock.isLocked) return
        schedule(0)
    }

    /**
     * 「用 root 启动」的开关或 su 的答复变了(用户在设置、引导页里打开、关掉、重新授权):助手不在就按新的情况从头再试;
     * Mika 自己起不来了就照实改状态。
     */
    fun rootChanged() {
        if (platform.transport() == ShellTransport.Shizuku) return
        if (helper.value != null) return
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        if (selfStart()) schedule(0) else _status.value = idleStatus()
    }

    /** 切换启动通道:停掉旧通道上的助手并按新通道重新连接或更新状态。 */
    override fun selectTransport(transport: ShellTransport) {
        if (platform.transport() == transport) return
        platform.setTransport(transport)
        runCatching { platform.stopHelper() }
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        if (selfStart()) {
            _status.value = BuiltinShellStatus.Connecting
            schedule(0)
        } else {
            _status.value = idleStatus()
        }
    }

    /** Shizuku 状态或授权变了:仅无线调试模式不动;有助手就保持运行;否则按新状态马上试或照实改状态。 */
    fun shizukuChanged() {
        if (platform.transport() == ShellTransport.WirelessDebugging) return
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        if (helper.value != null) return
        if (selfStart()) schedule(0) else _status.value = idleStatus()
    }

    /**
     * 无线调试开关变了:刚打开就从头再试。关掉时只改状态,不马上替用户打开(用户可能是亲手关的),等要用助手时再说。
     * Mika 自己正在写这个开关时不跟着动:系统可能马上拨回去,由写的那一边判断。
     */
    fun wirelessDebuggingChanged() {
        // 经 su 或纯 Shizuku 起助手不看无线调试
        if (helper.value != null || !platform.paired() || rootStart || platform.transport() == ShellTransport.Shizuku) return
        if (autoOn?.writing == true) return
        if (platform.wirelessDebugging() == false) {
            if (!shizukuStart) _status.value = offStatus()
            return
        }
        autoOn?.reset()
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /**
     * 连上了 Wi-Fi:之前卡在「没有 Wi-Fi」就马上再试(不用能上网);能替用户打开无线调试时,
     * 卡在无线调试关着(包括上一个网络不允许)也马上再试。换了网络,上一个网络上的拒绝不再算数。
     */
    fun wifiAvailable() {
        if (platform.transport() == ShellTransport.Shizuku) return
        autoOn?.reset()
        val s = _status.value
        val off = s == BuiltinShellStatus.WirelessDebuggingOff || s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        if (s != BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi) && !(autoOn != null && off)) return
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /** 无线调试关着时显示的状态:这个 Wi-Fi 上刚被系统拨回过就照实说,否则就是关着。 */
    private fun offStatus(): BuiltinShellStatus =
        if (autoOn?.blocked?.value == AutoOnBlock.NotAllowedHere) BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        else BuiltinShellStatus.WirelessDebuggingOff

    /** 无线调试关着:能替用户打开就打开,开着没被拨回返回 true 接着连;否则写好状态返回 false。 */
    private suspend fun turnOnWirelessDebugging(): Boolean {
        val auto = autoOn ?: return false.also { _status.value = offStatus() }
        when (val g = auto.gate(platform.wifiConnected())) {
            AutoOnGate.Act -> {
                _status.value = BuiltinShellStatus.Connecting
                if (withContext(io) { auto.turnOn() }) return true
            }
            // 离上一次写太近:到时候再来一次
            is AutoOnGate.Wait -> synchronized(this) {
                retryJob = scope.launch {
                    delay(g.ms)
                    launchOnce()
                }
            }
            is AutoOnGate.Skip -> Unit
        }
        _status.value = offStatus()
        return false
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        pendingImmediate = delayMs == 0L
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            launchOnce()
        }
    }

    private suspend fun launchOnce(): Unit = launchLock.withLock {
        synchronized(this) { pendingImmediate = false }
        if (helper.value != null) return@withLock
        when (platform.transport()) {
            ShellTransport.Shizuku -> {
                val shizuku = platform.shizukuState()
                if (!shizuku.ready) {
                    failed(shizuku.failure())
                    return@withLock
                }
                _status.value = BuiltinShellStatus.Connecting
                val failure = attemptShizuku()
                if (failure == null) {
                    lastError = null
                    _status.value = BuiltinShellStatus.Running
                } else {
                    failed(failure)
                }
                return@withLock
            }
            ShellTransport.Auto -> {
                if (platform.shizukuState().ready) {
                    _status.value = BuiltinShellStatus.Connecting
                    val failure = attemptShizuku()
                    if (failure == null) {
                        lastError = null
                        _status.value = BuiltinShellStatus.Running
                        return@withLock
                    }
                    if (!rootStart && !platform.paired()) {
                        failed(failure)
                        return@withLock
                    }
                }
            }
            ShellTransport.WirelessDebugging -> Unit
        }
        // 先走 root:不用配对、不用 Wi-Fi;不行时配过对就接着走无线调试
        if (rootStart) {
            _status.value = BuiltinShellStatus.Connecting
            val failure = attemptRoot()
            if (failure == null) {
                lastError = null
                _status.value = BuiltinShellStatus.Running
                return@withLock
            }
            if (!platform.paired()) {
                failed(failure)
                return@withLock
            }
        }
        if (!platform.paired()) {
            _status.value = idleStatus()
            return@withLock
        }
        if (platform.wirelessDebugging() == false && !turnOnWirelessDebugging()) return@withLock
        _status.value = BuiltinShellStatus.Connecting
        val failure = attempt()
        if (failure == null) {
            lastError = null
            _status.value = BuiltinShellStatus.Running
            return@withLock
        }
        failed(failure)
    }

    /** 报失败;看门狗自己再试有用的按 [backoff] 排下一次。 */
    private fun failed(failure: BuiltinShellFailure) {
        _status.value = BuiltinShellStatus.Failed(failure)
        val next = synchronized(this) { ++failures }
        if (BuiltinShellFailures.retryable(failure)) backoff.delayAfter(next)?.let { d ->
            synchronized(this) {
                retryJob = scope.launch {
                    delay(d)
                    launchOnce()
                }
            }
        }
    }

    /** 经 Shizuku 起一次助手并等交接。 */
    private suspend fun attemptShizuku(): BuiltinShellFailure? {
        val failure = try {
            withContext(io) { platform.launchHelperViaShizuku() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = describe(e)
            return BuiltinShellFailure.ShizukuBindFailed
        }
        if (failure != null) {
            lastError = "shizuku: ${failure.name}"
            return failure
        }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) return null
        runCatching { platform.stopHelper() }
        lastError = "helper handoff timed out (shizuku)"
        return BuiltinShellFailure.ShizukuBindFailed
    }

    /** 经 su 起一次助手并等交接;su 的答复记下来(拒绝、不回话之后就不再自己去问)。 */
    private suspend fun attemptRoot(): BuiltinShellFailure? {
        val answer = try {
            withContext(io) { platform.launchHelperAsRoot() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = describe(e)
            return BuiltinShellFailure.Unknown
        } ?: return BuiltinShellFailure.RootUnavailable.also { lastError = "su not found" }
        platform.setRootAnswer(answer)
        return when (answer) {
            SuAnswer.Granted ->
                if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
                else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out (root)" }
            SuAnswer.Denied -> BuiltinShellFailure.RootDenied.also { lastError = "su denied" }
            SuAnswer.NoAnswer -> BuiltinShellFailure.RootNoAnswer.also { lastError = "su did not answer" }
            SuAnswer.WrongUid -> BuiltinShellFailure.RootNoShellUid.also { lastError = "su cannot switch to the shell uid" }
        }
    }

    private suspend fun attempt(): BuiltinShellFailure? = try {
        withContext(io) { platform.launchHelper() }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
        else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        lastError = describe(e)
        val f = BuiltinShellFailures.ofConnection(e.reason)
        if (f == BuiltinShellFailure.ConnectFailed && !platform.wifiConnected()) BuiltinShellFailure.NoWifi else f
    } catch (e: AdbKeyUnreadableException) {
        lastError = describe(e)
        BuiltinShellFailure.KeyUnreadable
    } catch (e: Exception) {
        lastError = describe(e)
        BuiltinShellFailure.Unknown
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + ((e as? AdbConnectionException)?.reason?.let { "($it)" } ?: (e as? AdbPairingException)?.reason?.let { "($it)" } ?: "") +
            (e.message?.let { ": " + it.take(200) } ?: "")

    override fun startDiscovery() {
        synchronized(this) {
            // 还在找也重新开始:用户又回到配对这件事上了,找的时限从现在算
            discoveryJob?.cancel()
            _discovery.value = PairingDiscovery.Searching
            discoveryJob = scope.launch {
                val port = withContext(io) { platform.findPairingPort(discoveryTimeoutMs) }
                _discovery.value = port?.let { PairingDiscovery.Found(it) } ?: PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(this) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        _discovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        _status.value = BuiltinShellStatus.Connecting
        val target = port ?: (_discovery.value as? PairingDiscovery.Found)?.port
            ?: withContext(io) { platform.findPairingPort(pairingPortTimeoutMs) }
        if (target == null) {
            lastError = "pairing port not found"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
            return
        }
        val failure = try {
            withContext(io) { platform.pair(target, code) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            lastError = describe(e)
            BuiltinShellFailures.ofPairing(e.reason)
        } catch (e: Exception) {
            lastError = describe(e)
            BuiltinShellFailure.Unknown
        }
        if (failure != null) {
            _status.value = BuiltinShellStatus.Failed(failure)
            return
        }
        platform.setPaired(true)
        stopDiscovery()
        retry()
    }

    override suspend fun retry() {
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        // 用户看着 su 拒绝、没回话的提示点了重试:由平台明确再请求一次,未授权状态不会自动问 su
        val s = _status.value
        if (platform.transport() != ShellTransport.Shizuku && s is BuiltinShellStatus.Failed && s.reason in ROOT_ASKABLE) {
            if (platform.requestRootStart()) {
                _status.value = BuiltinShellStatus.Connecting
                return
            }
            platform.setRootAnswer(null)
        }
        // 用户点了重试:之前替用户打开无线调试时的拒绝不再算数(不允许的网络上系统会再问一次)
        autoOn?.reset()
        if (helper.value != null) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        launchOnce()
    }

    override suspend fun unpair() {
        synchronized(this) {
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        launchLock.withLock {
            withContext(io) {
                // 先忘掉「电脑起过」:助手停下时状态就是没设置,不提示再跑命令
                platform.setUsbStarted(false)
                runCatching { platform.stopHelper() }
                platform.forget()
            }
            platform.setPaired(false)
            _status.value = BuiltinShellStatus.NotPaired
        }
    }

    private companion object {
        /** 点重试会再问 su 的几种失败。 */
        val ROOT_ASKABLE = setOf(BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer, BuiltinShellFailure.RootNoShellUid)
    }
}
