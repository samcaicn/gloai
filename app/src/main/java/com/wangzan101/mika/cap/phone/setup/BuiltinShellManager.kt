package com.wangzan101.mika.cap.phone.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.wangzan101.mika.core.adb.AdbConnectPort
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.ConnectCandidates
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbServiceTypes
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.LocalPortScan
import com.wangzan101.mika.core.adb.NioPortScanner
import com.wangzan101.mika.core.adb.PortCache
import com.wangzan101.mika.core.shell.BuiltinShellEngine
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellPlatform
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.WirelessDebuggingAutoOn
import com.wangzan101.mika.core.shell.ShellCommandRunner
import com.wangzan101.mika.core.shell.ShellTransport
import com.wangzan101.mika.core.shell.ShizukuPlatformState
import com.wangzan101.mika.core.shell.SuAnswer
import com.wangzan101.mika.core.shell.SuProbe
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.shell.adb.NsdAdbServiceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 内置助手的真实现:配对、经本机 adbd 或 su 起助手、看门狗。由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份,
 * App 启动时由 [start] 开始看着助手;[hub] 是助手交来 Binder 的落点;[consent] 是用户对那几项能力的选择(「自动打开无线调试」看它)。
 */
class BuiltinShellManager(context: Context, private val hub: HelperHub, private val consent: () -> RecoveryConsent) {
    private val app = context.applicationContext
    private var started = false

    private val _scanningPorts = MutableStateFlow(false)
    /** 正在扫本机找无线调试端口(引导页显示「正在本机查找…」)。 */
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 已 root 的手机上「用 root 启动手机助手」「允许 Mika 执行 root 命令」两个开关;变了就让看门狗按新的情况再试。 */
    val root = RootControl(app, scope) { engine.rootChanged() }

    /** Shizuku 官方 API 通道:检查安装/运行/授权并绑定 UserService。 */
    val shizuku = ShizukuController(app, hub, scope) { engine.shizukuChanged() }

    private val _transport = MutableStateFlow(
        ShellTransport.parse(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSPORT, null)),
    )
    val transport: StateFlow<ShellTransport> = _transport.asStateFlow()

    private val platform: AndroidPlatform by lazy {
        AndroidPlatform(
            app, hub, root, shizuku, scope,
            { _transport.value = it },
            { lastConnectCandidates = it },
            { _scanningPorts.value = it },
            { lastHelperLaunchStage = it },
        )
    }

    /** 最近一次找连接端口时各来源给了几个候选(诊断信息用);这次进程里还没连过为 null。 */
    @Volatile var lastConnectCandidates: ConnectCandidates? = null
        private set

    /** 最近一次启动助手走到的确定阶段;不包含口令、地址、命令行或异常原文。 */
    @Volatile var lastHelperLaunchStage: String? = null
        private set

    /** 要起助手时替用户打开无线调试:只由 App 自己写系统设置(要起助手时没有别的连接可用)。 */
    val wirelessAutoOn: WirelessDebuggingAutoOn by lazy {
        WirelessDebuggingAutoOn(
            wants = { consent().wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = platform::wirelessDebugging,
            write = { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) },
        )
    }

    /** 配对客户端 + 看门狗。 */
    val engine: BuiltinShellEngine by lazy {
        BuiltinShellEngine(
            platform, hub.shell, scope, Dispatchers.IO, autoOn = wirelessAutoOn,
        )
    }

    /**
     * 恢复和授权开关用:助手在就经它跑(电脑经 USB 起的助手没有配对的连接也能用);
     * 不在才每条命令单独连一次已配对的 adbd,跑完就断。
     */
    val commandRunner = ShellCommandRunner { command ->
        withContext(Dispatchers.IO) {
            if (hub.shell.value == null && engine.shizukuStart) {
                engine.need()
                withTimeoutOrNull(SHIZUKU_COMMAND_WAIT_MS) { hub.shell.first { it != null } }
            }
            val running = hub.shell.value
            if (running != null) {
                running.runCommand(command)?.getString("output").orEmpty()
            } else if (engine.transport == ShellTransport.Shizuku) {
                throw IOException("Shizuku helper is not running")
            } else {
                platform.connect().use { it.exec(command).readText() }
            }
        }
    }

    /** 「用电脑启动」的脚本:跟着助手在不在重写。 */
    val usbScript = UsbStartScript(app, hub)

    /** 有人要用助手却没有:看门狗起一个(配对过才行),脚本也备好给电脑用。 */
    fun need() {
        engine.need()
        usbScript.refresh()
    }

    /** 刷新 Shizuku 状态;若有变化立即通知引擎。 */
    fun refreshShizuku(): ShizukuPlatformState {
        val before = shizuku.state.value
        val after = shizuku.refresh()
        if (after != before) engine.shizukuChanged()
        return after
    }

    /** 在后台线程刷新 Shizuku 状态;若有变化立即通知引擎。 */
    fun refreshShizukuAsync() {
        scope.launch(Dispatchers.IO) { refreshShizuku() }
    }

    /** 用户在引导页填了无线调试的端口:下一次连接带上它(只用这一次,连上了就记作缓存),然后重试。 */
    suspend fun connectWithPort(port: Int) {
        platform.manualPort.set(port)
        engine.retry()
    }

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < MIN_SDK) return
        started = true
        usbScript.start()
        root.refresh()
        refreshShizukuAsync()
        val e = engine
        // 无线调试一打开就试着连回去;关掉时状态马上变成「无线调试已关」
        app.contentResolver.registerContentObserver(
            DeveloperSettings.wirelessDebuggingUri, false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = e.wirelessDebuggingChanged()
            },
        )
        // 卡在「没有 Wi-Fi」或无线调试关着时,一连上任意 Wi-Fi(不用能上网)就再试
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = e.wifiAvailable()
                },
            )
        }
        e.start()
        shizuku.start(refreshNow = false)
    }

    private class AndroidPlatform(
        private val app: Context,
        private val hub: HelperHub,
        private val root: RootControl,
        private val shizuku: ShizukuController,
        private val scope: CoroutineScope,
        private val onTransportChanged: (ShellTransport) -> Unit,
        private val candidates: (ConnectCandidates) -> Unit,
        private val scanning: (Boolean) -> Unit,
        private val launchStage: (String) -> Unit,
    ) : BuiltinShellPlatform {
        /** 用户填的端口,下一次连接取走。 */
        val manualPort = AtomicReference<Int?>(null)
        private val scan = LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(portRangeText()) })
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val finder = NsdAdbServiceFinder(app)
        private val portCache = object : PortCache {
            override fun load() = prefs.getInt("port", 0).takeIf { it > 0 }
            override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
        }

        override fun paired() = Build.VERSION.SDK_INT >= MIN_SDK && prefs.getBoolean("paired", false)

        override fun setPaired(paired: Boolean) {
            prefs.edit().putBoolean("paired", paired).apply()
        }

        override fun rootStart() = root.rootStart()

        override fun setRootAnswer(answer: SuAnswer?) = root.setStartAnswer(answer)

        override fun requestRootStart(): Boolean {
            root.requestStart()
            return true
        }

        override fun transport(): ShellTransport =
            ShellTransport.parse(prefs.getString(KEY_TRANSPORT, null))

        override fun setTransport(transport: ShellTransport) {
            prefs.edit().putString(KEY_TRANSPORT, transport.name).apply()
            onTransportChanged(transport)
        }

        override fun shizukuState(): ShizukuPlatformState = shizuku.state.value

        override suspend fun launchHelperViaShizuku(): BuiltinShellFailure? = shizuku.launchHelper()

        /**
         * 经 su 以 shell 身份起同一个助手:`su 2000 -c '<id -u; 和无线调试同样的命令>'`。先读到 2000(su 答应了、也切到了 shell)
         * 才把口令写进标准输入;交接核对和无线调试起的完全一样(shell 身份、APK、口令、签名指纹)。
         * 交接到了(或超时)再关标准输入,sh 读到结尾就退出;助手在 setsid 另起的会话里,不受影响。
         */
        override suspend fun launchHelperAsRoot(): SuAnswer? {
            val su = RootShell.su() ?: return null.also { root.suGone() }
            val command = HelperHandoff.rootCommand(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app))
            val p = RootShell.start(SuProbe.shellArgs(su, command))
            val before = hub.shell.value
            try {
                val uid = try {
                    runInterruptible { RootShell.readUid(p, ROOT_ASK_MS) }
                } catch (e: TimeoutException) {
                    return SuAnswer.NoAnswer
                } catch (e: ExecutionException) {
                    return SuAnswer.Denied
                }
                when (uid) {
                    null -> return SuAnswer.Denied
                    HelperHandoff.SHELL_UID -> Unit
                    else -> return SuAnswer.WrongUid
                }
                p.outputStream.apply {
                    write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                    flush()
                }
                withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
                return SuAnswer.Granted
            } finally {
                runCatching { p.outputStream.close() }
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
            }
        }

        override fun usbStarted() = prefs.getBoolean("usbStarted", false)

        override fun setUsbStarted(on: Boolean) {
            prefs.edit().putBoolean("usbStarted", on).apply()
        }

        // 这一项没写过时读出 null:看不清就不拦着
        override fun wirelessDebugging(): Boolean? =
            Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }

        override fun wifiConnected(): Boolean = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(true)

        override suspend fun findPairingPort(timeoutMs: Long): Int? =
            finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs).firstOrNull()
                .also { Log.i(PAIRING_TAG, if (it != null) "found the pairing window on port $it" else "no pairing window within $timeoutMs ms") }

        override fun pair(port: Int, code: String) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(app))).pair(LOCALHOST, port, code)
        }

        override suspend fun launchHelper() {
            launchStage("preparing")
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            // 助手每次交接前都核对 Mika 的签名
            val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app))
            launchStage("adb_connecting")
            val connection = connect(tls)
            launchStage("adb_connected")
            // 流开着 sh 就一直等;交接到了(或超时)再关,交接结果仍由引擎从 HelperHub 判断
            val before = hub.shell.value
            connection.use {
                val stream = it.exec(command)
                launchStage("exec_opened")
                stream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                launchStage("launch_nonce_written")
                val handedOff = withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
                launchStage(if (handedOff != null) "handoff_received" else "handoff_timeout")
            }
        }

        suspend fun connect(tls: AdbTls = AndroidAdb.tls(AndroidAdb.identity(app))): AdbConnection =
            AdbConnectPort(
                ::tlsPort, finder, portCache, report = candidates, scan = scan, manual = { manualPort.getAndSet(null) }, scanning = scanning,
            ).connect { host, port -> AdbConnection.open(host, port, tls) }

        /** 扫端口时只扫本机自己:127.0.0.1 和 Wi-Fi 上的 IPv4 地址。 */
        private fun localHosts(): List<String> = listOf(LOCALHOST) + runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        }.getOrDefault(emptyList())

        /** 系统分配临时端口的范围;有的系统不让 App 读,读不到就扫默认的一段。 */
        private fun portRangeText(): String? = runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()

        override fun stopHelper() {
            val remaining = hub.shell.value
            hub.detachShizuku(remaining)
            val unbind = shizuku.prepareStop()
            if (unbind || remaining != null) {
                scope.launch(Dispatchers.IO) {
                    if (unbind) shizuku.unbindService()
                    // destroy 会让助手进程当场退出,这一调用多半以 DeadObjectException 结束
                    if (remaining != null) runCatching { remaining.destroy() }
                }
            }
        }

        override fun forget() {
            root.turnOffStart()
            hub.forget()
            AndroidAdb.forget(app)
            prefs.edit().remove("port").apply()
        }

        @SuppressLint("PrivateApi")
        private fun tlsPort(): Int? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, TLS_PORT_PROPERTY) as String?
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    companion object {
        /** 内置助手从安卓 12 起启用;旧系统使用不带较新屏幕标志的兼容路径。 */
        const val MIN_SDK = Build.VERSION_CODES.S
        private const val PREFS = "builtin_shell"
        private const val KEY_TRANSPORT = "transport"
        private const val SHIZUKU_COMMAND_WAIT_MS = 5_000L
        /** 和配对其余几处(PairingCoordinator、PairingCodeReader)用同一个,按它一条命令就能看完配对经过。 */
        private const val PAIRING_TAG = "MikaPairing"
        /** 安卓原版 adbd 把无线调试的连接端口写在这个系统属性里;读不到时靠缓存、mDNS、扫本机端口和用户填的端口。 */
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val LOCALHOST = AdbConnectPort.LOCALHOST
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val HANDOFF_WAIT_MS = 15_000L
        /** 看门狗经 su 起助手时等 su 回话(授权过的马上回;要弹授权框时给用户一点时间)。 */
        private const val ROOT_ASK_MS = 45_000L
    }
}
