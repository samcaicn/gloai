package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.mcp.McpRpc
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RelayClient
import com.wangzan101.mika.core.remote.RelayGateway
import com.wangzan101.mika.settings.RelaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.device.AndroidDeviceAccess
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.RunExportSource
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.task.RemoteRunExport
import com.wangzan101.mika.task.RemoteTaskHub
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** 电脑连接此刻的样子:关着、在监听哪个端口、或没能开起来(端口被占等)。 */
sealed interface McpServerState {
    data object Off : McpServerState
    data class Listening(val port: Int) : McpServerState
    data class Failed(val message: String) : McpServerState
}

/**
 * 电脑连接(MCP)的开关与服务:整个进程一份。局域网([settings])和 USB([usb])的监听范围见 [McpModes],
 * 远程网关([relaySettings])使用独立出站通道。有一种开着就起 [McpService] 前台服务,
 * 由各自开关决定本地监听和出站连接;都关了就停止。三种方式的任务共用 [tasks] 和同一协议入口。
 */
class McpRuntime(
    private val context: Context, val settings: McpSettings, val usb: McpUsbSettings, private val tasks: RemoteTasks,
    val device: AndroidDeviceAccess,
    val relaySettings: RelaySettings,
    private val history: com.wangzan101.mika.core.remote.RemoteHistory? = null,
    calls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    companion: com.wangzan101.mika.core.remote.RemoteCompanion? = null,
) {
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Off)
    val state: StateFlow<McpServerState> = _state.asStateFlow()
    @Volatile private var host: RemoteHost? = null
    private var hostListen = McpModes.Listen.Off
    private val records: RunExportSource? = (tasks as? RemoteTaskHub)?.let { hub -> RemoteRunExport(hub) { RunStore(context) } }
    private val endpoints = RemoteEndpoints(tasks, BuildConfig.VERSION_NAME, device = device.access, history = history, records = records, calls = calls, companion = companion)
    private val relayAccess = RemoteAccess(token = { if (relaySettings.config.value.enabled) settings.token() else null })
    // 经网关来的请求要另开「允许经网关唤醒和解锁」才看得到屏幕能力;手机上的远程亮屏与解锁许可照样要开。
    private val relay = RelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), { req ->
        relayAccess.handle(req) { endpoints.handle(it, deviceAccess = relaySettings.config.value.deviceAccess, historyAccess = false) }
    })
    private val relayNetwork = RelayNetwork(context, relay::networkChanged)
    val relayState get() = relay.state
    private val active get() = listen() != McpModes.Listen.Off || relaySettings.config.value.enabled

    /** 最近连过的客户端和最后一次请求;没在监听时为空。 */
    val activity: RemoteActivity get() = if (active) endpoints.activity.value else RemoteActivity()

    /** 配对只保存凭据,必须用户另行开启远程访问。 */
    suspend fun pairRelay(gateway: String, code: String) {
        check(!relaySettings.config.value.paired) { "Unpair the previous gateway first" }
        val remote = RelayGateway(RelayGateway.parse(gateway), RelayGateway.http())
        val paired = remote.pair(code)
        try { relaySettings.save(paired) }
        catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { remote.unpair(paired.deviceToken) } }
            throw e
        }
    }

    fun setRelayDeviceAccess(on: Boolean) = relaySettings.setDeviceAccess(on)

    fun setRelayEnabled(on: Boolean) {
        if (!on) { relayNetwork.stop(); relay.stop() }
        try { relaySettings.setEnabled(on) } finally { if (!on) apply() }
        if (on) apply()
    }

    suspend fun unpairRelay() {
        setRelayEnabled(false)
        relaySettings.pairing()?.let { RelayGateway(RelayGateway.parse(it.gateway), RelayGateway.http()).unpair(it.deviceToken) }
        relaySettings.clear()
    }

    fun listen(): McpModes.Listen = McpModes.listen(settings.enabled.value, usb.enabled.value)

    /** 设置页上局域网那种的开关。 */
    fun setEnabled(on: Boolean) {
        settings.setEnabled(on)
        apply()
    }

    /** 设置页上 USB 那种的开关(电脑经 adb 开关的走 [McpService] 的动作,同一套)。 */
    fun setUsbEnabled(on: Boolean) {
        if (on) usb.enable() else usb.disable()
        apply()
    }

    /** 换监听端口:存下、按新端口重写电脑上跑的脚本;有一种连接开着就重起服务按新端口重绑并重发连接信息。 */
    fun setPort(port: Int) {
        settings.setPort(port)
        writeUsbScript()
        apply()
    }

    /** 开关变了:还有一种开着就让服务按新的开关重新监听,都关了就停服务。 */
    private fun apply() {
        if (active) ensureRunning() else {
            context.stopService(Intent(context, McpService::class.java))
            _state.value = McpServerState.Off
        }
    }

    /**
     * 有一种开着就把服务起来(已经在跑就让它按开关重新对一遍)。从后台起前台服务可能被系统拒绝,这时记成没开起来,
     * 等下次打开 Mika 的界面再起。
     */
    fun ensureRunning() {
        if (!active || com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return
        runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
            .onFailure { e ->
                Log.w(TAG, "could not start computer access service", e)
                _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
    }

    /**
     * 由 [McpService] 调:同步本地监听和远程通道。监听范围没变时复用服务,USB 开着时发布连接信息。
     * 本地监听和远程通道均未启用或未能启动时返回 false。
     */
    @Synchronized internal fun sync(): Boolean {
        if (com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return false
        val want = listen()
        // 端口改了也要重绑(RemoteHost 绑精确端口,boundPort 就是实际监听的口)
        if (host != null && (hostListen != want || host!!.boundPort != settings.port)) stopListener()
        if (want == McpModes.Listen.Off) _state.value = McpServerState.Off
        val ok = want != McpModes.Listen.Off && (host != null || startServer(want))
        if (ok && usb.enabled.value) runCatching { usb.publish(host!!.boundPort) }.onFailure { Log.w(TAG, "cannot write USB connection info", it) }
        else usb.unpublish()
        val remoteOn = relaySettings.config.value.enabled
        if (remoteOn) {
            val pairing = relaySettings.pairing()
            if (pairing != null) { relayNetwork.start(); relay.start(pairing) }
            else { relayNetwork.stop(); relay.stop(); relaySettings.setEnabled(false) }
        } else { relayNetwork.stop(); relay.stop() }
        return ok || remoteOn && relaySettings.config.value.enabled
    }

    private fun startServer(want: McpModes.Listen): Boolean {
        val network = want == McpModes.Listen.PrivateNetworks
        val h = RemoteHost(tasks, { if (settings.enabled.value) settings.token() else null }, settings.port, allowAllInterfaces = network,
            serverVersion = BuildConfig.VERSION_NAME, onError = { Log.w(TAG, "computer access server error", it) },
            loopbackToken = usb::token, device = device.access, endpoints = endpoints)
        return try {
            h.start()
            host = h
            hostListen = want
            _state.value = McpServerState.Listening(h.boundPort)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on port ${settings.port}", e)
            _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    @Synchronized internal fun stopServer() {
        relayNetwork.stop()
        relay.stop()
        stopListener()
    }

    private fun stopListener() {
        host?.stop()
        host = null
        hostListen = McpModes.Listen.Off
        if (_state.value is McpServerState.Listening) _state.value = McpServerState.Off
    }

    /** 给电脑跑的「经 USB 连接」脚本:不带秘密,App 启动时写一次(外部存储不可用就跳过)。 */
    fun writeUsbScript() {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, McpUsb.SCRIPT)
        val text = McpUsb.script(
            scriptPath = shortPath(f.absolutePath),
            helperScript = File(dir, HelperHandoff.USB_SCRIPT).absolutePath,
            infoPath = File(dir, McpUsb.INFO_FILE).absolutePath,
            component = "${context.packageName}/${McpService::class.java.name}",
            onAction = McpModes.ACTION_USB_ON, offAction = McpModes.ACTION_USB_OFF, port = settings.port,
        )
        if (runCatching { f.readText() }.getOrNull() == text) return
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** 电脑上要跑的那条命令(设置页显示的就是它)。 */
    fun usbCommand(): String {
        val path = context.getExternalFilesDir(null)?.let { File(it, McpUsb.SCRIPT).absolutePath }
            ?: "/storage/emulated/0/Android/data/${context.packageName}/files/${McpUsb.SCRIPT}"
        return "adb shell sh " + shortPath(path)
    }

    /** 换令牌:旧的当场失效。 */
    fun regenerateToken(): String = settings.regenerate()

    companion object {
        private const val TAG = "MikaMcp"

        /** 主用户的外部存储在 shell 里也叫 /sdcard;给人看的命令用短写法(和「用电脑启动」的脚本一样)。 */
        private fun shortPath(path: String) = HelperHandoff.usbCommand(path).removePrefix("adb shell sh ")

        /** 电脑能连的地址:开着的网卡上的局域网、私有组网 IPv4 地址。 */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && RemoteAccess.privateAddress(it) }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        fun url(address: String, port: Int) = "http://$address:$port${McpRpc.PATH}"

        /** 给电脑上的 MCP 客户端用的配置片段:地址加 Authorization 头。 */
        fun clientConfig(url: String, token: String): String = """
            {
              "mcpServers": {
                "mika": {
                  "type": "http",
                  "url": "$url",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
    }
}
