package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.mcp.McpRpc
import com.wangzan101.mika.core.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * HTTP 与 MCP 每次请求都从同一个已认证归属构造任务服务,避免某条协议路径绕过任务、文件或导出权限。
 * 只读 Web 控制台([RemoteCompanion])与任务 API 并列,同样走这里的认证入口。
 */
class RemoteEndpoints(
    private val tasks: RemoteTasks,
    private val serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val device: DeviceAccess? = null,
    private val history: RemoteHistory? = null,
    private val records: RunExportSource? = null,
    private val calls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    private val companion: RemoteCompanion? = null,
) {
    private val companionApi = companion?.let(::CompanionApi)
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    fun handle(req: HttpRequest, deviceAccess: Boolean = true, historyAccess: Boolean = true): HttpResponse {
        // Relay adapters do not carry the phone's bearer token. They still get one isolated task namespace,
        // never the internal unscoped view and never local history.
        val owner = req.accessOwner ?: "relay"
        val scopedTasks = tasks.scope(owner)
        val service = RemoteTaskService(scopedTasks, records = records?.scope(owner))
        val rpc = McpRpc(McpTaskTools(service, device.takeIf { deviceAccess }, calls.takeIf { deviceAccess }), serverVersion) { remote, name -> seen(remote, name) }
        if (req.path == McpRpc.PATH) return rpc.handle(req)
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-mika-client")?.take(64))
            if (companionApi != null && CompanionApi.owns(req.path)) return companionApi.handle(req)
            val api = RemoteTaskApi(service, serverVersion, device.takeIf { deviceAccess }, history.takeIf { historyAccess }, companion = companion != null)
            return api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
