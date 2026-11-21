package com.wangzan101.mika.settings

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.cap.mcp.HttpMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.LegacySseMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpOAuth
import com.wangzan101.mika.core.cap.mcp.McpOAuthException
import com.wangzan101.mika.core.cap.mcp.McpOAuthTokens
import com.wangzan101.mika.core.cap.mcp.McpStdioLauncher
import com.wangzan101.mika.core.cap.mcp.StdioMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.parseStdioCommand
import com.wangzan101.mika.core.cap.mcp.McpClientException
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.oauth.LoopbackCallback
import com.wangzan101.mika.core.resource.ResourceScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 外部 MCP server 的一条配置;token 不在这个对象里,单独加密存。 */
@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** restricted | trust-annotations */
    val trust: String = "restricted",
    val timeoutSec: Int = 60,
    val previewChars: Int = 4000,
    val maxCostUnits: Long? = null,
    val allowLocalHttp: Boolean = false,
    /** streamable-http | http-sse(2024-11-05 旧版) | stdio(沙箱里的本地进程) */
    val transport: String = "streamable-http",
    /** stdio 的命令行;不经过 shell,按 argv 直接执行。 */
    val command: String = "",
    /** token | oauth(token 是手动 Bearer/自定义头;oauth 走 OAuth 2.1 登录) */
    val auth: String = "token",
    /** 只暴露这些工具;null 表示全部。 */
    val allowedTools: List<String>? = null,
    /** 非密的自定义请求头;Authorization 另存。 */
    val headers: Map<String, String> = emptyMap(),
)

data class McpHealth(val ok: Boolean, val message: String?, val tools: List<String>, val atMs: Long)

data class McpConnections(
    val connections: List<McpConnection>,
    val close: () -> Unit,
    val failures: List<Pair<String, String>> = emptyList(),
)

data class McpTestResult(val serverName: String?, val instructions: String?, val tools: List<String>)

/**
 * 外部 MCP server 的设置:配置存 `files/settings/mcp-servers.json`,token 用 [KeyCipher] 加密另存。
 * [connectionsFor] 在任务开始时连接所有启用项(整段有总时限),失败、超时的不影响其他任务/其他 server。
 */
class McpServerStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val http: OkHttpClient = NetProxy.shared.client(
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS),
    ),
    /** stdio server 只能由沙箱启动;为 null 时 stdio 配置会报不可用。 */
    private val stdio: McpStdioLauncher? = null,
) {
    private val file = File(dir, "mcp-servers.json")
    private val secretsFile = File(dir, "mcp-server-secrets.bin")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    private val oauth = McpOAuth(http)
    private val tokenJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _health = MutableStateFlow<Map<String, McpHealth>>(emptyMap())
    /** 最近一次连接/测试的结果;只在进程内,不落盘。 */
    val health: StateFlow<Map<String, McpHealth>> = _health.asStateFlow()

    @Synchronized fun add(name: String, url: String, token: String? = null): Result<McpServerConfig> =
        add(McpServerConfig(name = name.trim(), url = url.trim()), token)

    @Synchronized fun add(config: McpServerConfig, token: String? = null): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val next = _servers.value + config
        saveConfigs(next)
        _servers.value = next
        if (!token.isNullOrBlank()) setToken(config.id, token)
        return Result.success(config)
    }

    @Synchronized fun update(config: McpServerConfig): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val next = _servers.value.map { if (it.id == config.id) config else it }
        saveConfigs(next)
        _servers.value = next
        return Result.success(config)
    }

    @Synchronized fun remove(id: String) {
        val next = _servers.value.filterNot { it.id == id }
        saveConfigs(next)
        _servers.value = next
        val secrets = loadSecrets().toMutableMap()
        val hadToken = secrets.remove(id) != null
        val hadOAuth = secrets.remove("oauth:$id") != null
        if (hadToken || hadOAuth) saveSecrets(secrets)
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val next = _servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveConfigs(next)
        _servers.value = next
    }

    @Synchronized fun token(id: String): String? = loadSecrets()[id]

    @Synchronized fun setToken(id: String, token: String?) {
        val secrets = loadSecrets().toMutableMap()
        if (token.isNullOrBlank()) secrets.remove(id) else secrets[id] = token.trim()
        saveSecrets(secrets)
    }

    fun validate(config: McpServerConfig): String? {
        if (config.name.isBlank()) return "name is required"
        if (config.transport == "stdio") {
            if (config.command.isBlank()) return "command is required"
            if (config.command.length > 4000) return "command is too long"
            if (config.command.any { it == '\n' || it == '\u0000' }) return "command must be one line"
            return null
        }
        val url = config.url.toHttpUrlOrNull() ?: return "invalid URL"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "URL must not contain user info"
        if (isBlockedHost(url.host)) return "this host is not allowed"
        if (url.scheme == "http") {
            if (!config.allowLocalHttp) return "HTTP is only allowed for local/private servers after enabling it"
            if (!isLocalHost(url.host)) return "HTTP is only allowed for local/private hosts"
        } else if (url.scheme != "https") {
            return "URL must be https (or http for a local server)"
        }
        return null
    }

    suspend fun test(config: McpServerConfig, workspace: String = SETTINGS_WORKSPACE): Result<McpTestResult> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val headers = try {
            authHeaders(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _health.update { it + (config.id to healthFailure(e)) }
            return Result.failure(e)
        }
        val endpoint = open(config, headers, workspace).getOrElse { e ->
            _health.update { it + (config.id to healthFailure(e)) }
            return Result.failure(e)
        }
        return try {
            val name = when (endpoint) {
                is HttpMcpEndpoint -> endpoint.serverName
                is LegacySseMcpEndpoint -> endpoint.serverName
                is StdioMcpEndpoint -> endpoint.serverName
                else -> null
            }
            val instructions = when (endpoint) {
                is HttpMcpEndpoint -> endpoint.instructions
                is LegacySseMcpEndpoint -> endpoint.instructions
                is StdioMcpEndpoint -> endpoint.instructions
                else -> null
            }
            val result = McpTestResult(name, instructions, endpoint.tools().map { it.name })
            _health.update { it + (config.id to McpHealth(true, null, result.tools, System.currentTimeMillis())) }
            Result.success(result)
        } finally {
            (endpoint as? Closeable)?.close()
        }
    }

    /** 刷新所有启用 server 的状态(并行)。 */
    suspend fun testAll() = coroutineScope {
        _servers.value.filter { it.enabled }.map { config -> async { test(config) } }.awaitAll()
    }

    /**
     * 任务开头用:连上的归 [owner](任务的 [ResourceScope]),任务收尾关作用域时一起关掉;
     * 取消落在连接中途或刚连上、还没交回时也不漏(stdio server 的进程和沙箱租约跟着放掉)。
     */
    suspend fun connectionsFor(
        owner: ResourceScope,
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore? = null,
        workspace: String = SETTINGS_WORKSPACE,
        budgetMs: Long = CONNECT_BUDGET_MS,
    ): McpConnections = owner.acquire(Dispatchers.IO, { c: McpConnections -> c.close() }) { connections(resultStore, workspace, budgetMs) }

    /**
     * 并行连接所有启用项;单个失败只记录,不抛。整段最多等 [budgetMs]:到点还没连上的这次不接,
     * 记成失败(健康状态里写明超时),不拖住任务开头;已经连上的照常用。被取消时连上的也关掉再抛。
     */
    suspend fun connections(
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore? = null,
        workspace: String = SETTINGS_WORKSPACE,
        budgetMs: Long = CONNECT_BUDGET_MS,
    ): McpConnections {
        val enabled = _servers.value.filter { it.enabled }
        val done = ConcurrentHashMap<String, Result<McpConnection>>()
        try {
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    enabled.map { config -> async { done[config.id] = attempt(config, resultStore, workspace) } }.awaitAll()
                }
            }
        } catch (e: Throwable) {
            done.values.forEach { r -> r.getOrNull()?.let { (it.endpoint as? Closeable)?.let { c -> runCatching { c.close() } } } }
            throw e
        }
        val late = "did not connect within ${(budgetMs + 999) / 1000} s; skipped for this task"
        val attempts = enabled.map { config ->
            config to (done[config.id] ?: Result.failure<McpConnection>(McpClientException(late)).also {
                _health.update { h -> h + (config.id to McpHealth(false, late, emptyList(), System.currentTimeMillis())) }
            })
        }
        val connected = attempts.mapNotNull { (c, r) -> r.getOrNull()?.let { c to it } }
        val failures = attempts.mapNotNull { (c, r) -> r.exceptionOrNull()?.let { c.id to (it.message ?: it.javaClass.simpleName) } }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return McpConnections(
            connections = connected.map { it.second },
            // 任务收尾和它的 ResourceScope 都会关;只关一次
            close = {
                if (closed.compareAndSet(false, true)) connected.forEach { (it.second.endpoint as? Closeable)?.let { c -> runCatching { c.close() } } }
            },
            failures = failures,
        )
    }

    /** 连一个 server 并记下健康状态。 */
    private suspend fun attempt(
        config: McpServerConfig,
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore?,
        workspace: String,
    ): Result<McpConnection> {
        val result = try {
            open(config, if (config.transport == "stdio") emptyMap() else authHeaders(config), workspace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val connection: Result<McpConnection> = result.map { endpoint ->
            McpConnection(
                endpoint = endpoint,
                resultStore = resultStore,
                maxCostUnits = config.maxCostUnits,
                previewChars = config.previewChars,
                trust = if (config.trust == "trust-annotations") McpTrust.TrustAnnotations else McpTrust.Restricted,
                allowedTools = config.allowedTools?.toSet(),
                displayName = config.name,
            )
        }
        _health.update {
            it + (config.id to connection.fold(
                onSuccess = { c -> McpHealth(true, null, c.endpoint.tools().map { t -> t.name }, System.currentTimeMillis()) },
                onFailure = { e -> McpHealth(false, e.message ?: e.javaClass.simpleName, emptyList(), System.currentTimeMillis()) },
            ))
        }
        return connection
    }

    private suspend fun open(
        config: McpServerConfig,
        headers: Map<String, String>,
        workspace: String,
    ): Result<com.wangzan101.mika.core.cap.mcp.McpEndpoint> = try {
        when (config.transport) {
            "stdio" -> {
                val launcher = stdio ?: throw IllegalStateException("stdio MCP servers are not available on this build")
                val argv = parseStdioCommand(config.command)
                StdioMcpEndpoint.connect(
                    serverId = config.id, command = argv, launcher = launcher, workspace = workspace,
                    timeoutSec = config.timeoutSec,
                    clientName = "Mika", clientVersion = BuildConfig.VERSION_NAME,
                ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
            }
            "http-sse" -> LegacySseMcpEndpoint.connect(
                serverId = config.id, url = config.url, http = http,
                headers = headers, timeoutSec = config.timeoutSec,
                clientName = "Mika", clientVersion = BuildConfig.VERSION_NAME,
            ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
            else -> HttpMcpEndpoint.connect(
                serverId = config.id, url = config.url, http = http,
                headers = headers, timeoutSec = config.timeoutSec,
                clientName = "Mika", clientVersion = BuildConfig.VERSION_NAME,
            ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun authHeaders(config: McpServerConfig): Map<String, String> {
        if (config.transport == "stdio") return emptyMap()
        return buildMap {
            putAll(config.headers)
            if (config.auth == "oauth") {
                var tokens = oauthTokens(config.id) ?: throw McpOAuthException("oauth_login_required")
                if (!tokens.isFresh()) {
                    tokens = oauth.refresh(tokens)
                    setOAuthTokens(config.id, tokens)
                }
                put("Authorization", "Bearer ${tokens.accessToken}")
            } else {
                token(config.id)?.let { put("Authorization", "Bearer $it") }
            }
        }
    }

    @Synchronized fun oauthTokens(id: String): McpOAuthTokens? =
        loadSecrets()["oauth:$id"]?.let { runCatching { tokenJson.decodeFromString<McpOAuthTokens>(it) }.getOrNull() }

    @Synchronized fun setOAuthTokens(id: String, tokens: McpOAuthTokens) {
        val secrets = loadSecrets().toMutableMap()
        secrets["oauth:$id"] = tokenJson.encodeToString(tokens)
        saveSecrets(secrets)
    }

    @Synchronized fun clearOAuth(id: String) {
        val secrets = loadSecrets().toMutableMap()
        if (secrets.remove("oauth:$id") != null) saveSecrets(secrets)
    }

    /**
     * OAuth 2.1 登录:发现授权服务器 → 动态注册本机回调 → 打开浏览器 → 等回调换令牌。
     * [openBrowser] 在登录开始时调用一次(由 UI 打开系统浏览器);整个流程最多等 5 分钟。
     */
    suspend fun authorize(config: McpServerConfig, openBrowser: (String) -> Unit): Result<McpOAuthTokens> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val serverUrl = config.url.toHttpUrlOrNull() ?: return Result.failure(IllegalArgumentException("invalid URL"))
        return try {
            Result.success(LoopbackCallback.open().use { callback ->
                val server = oauth.discover(serverUrl)
                val redirect = callback.redirectUri(LoopbackCallback.HOST)
                val registration = oauth.register(server, redirect)
                val attempt = oauth.newAttempt(redirect)
                openBrowser(oauth.authorizeUrl(server, registration, attempt, server.resource))
                var tokens: McpOAuthTokens? = null
                var failure: Exception? = null
                callback.await(
                    timeoutMs = 5 * 60_000L,
                    accept = { it["state"] == attempt.state },
                ) { params ->
                    try {
                        val error = oauth.readCallbackError(params, attempt)
                        if (error != null) {
                            failure = error
                            oauthPage(false)
                        } else {
                            tokens = oauth.exchange(server, registration, oauth.readCode(params, attempt), attempt, server.resource)
                            tokens?.let { setOAuthTokens(config.id, it) }
                            oauthPage(true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                        oauthPage(false)
                    }
                } ?: throw McpOAuthException("oauth_timeout")
                failure?.let { throw it }
                tokens ?: throw McpOAuthException("oauth_no_token")
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun healthFailure(e: Throwable): McpHealth =
        McpHealth(false, e.message ?: e.javaClass.simpleName, emptyList(), System.currentTimeMillis())

    private fun oauthPage(ok: Boolean): String = if (ok) {
        "<html><body><h3>Mika: 登录成功</h3><p>Sign-in complete. You can return to Mika.</p></body></html>"
    } else {
        "<html><body><h3>Mika: 登录失败</h3><p>Sign-in failed. Return to Mika and try again.</p></body></html>"
    }

    private fun loadServers(): List<McpServerConfig> = runCatching {
        if (!file.isFile) emptyList()
        else json.decodeFromString<List<McpServerConfig>>(file.readText())
    }.getOrDefault(emptyList())

    private fun saveConfigs(configs: List<McpServerConfig>) = atomicWrite(file) { json.encodeToString(configs) }

    private fun loadSecrets(): Map<String, String> = runCatching {
        if (!secretsFile.isFile) emptyMap()
        else {
            val text = String(cipher.decrypt(secretsFile.readBytes()), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(text).jsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
    }.getOrDefault(emptyMap())

    private fun saveSecrets(secrets: Map<String, String>) {
        val text = JsonObject(secrets.mapValues { JsonPrimitive(it.value) }).toString()
        atomicWriteBytes(secretsFile) { cipher.encrypt(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun atomicWrite(target: File, text: () -> String) {
        atomicWriteBytes(target) { text().toByteArray(Charsets.UTF_8) }
    }

    private fun atomicWriteBytes(target: File, bytes: () -> ByteArray) {
        dir.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes())
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun isLocalHost(host: String): Boolean = LocalHosts.isPrivateOrMesh(host)

    companion object {
        /** 设置页测试 stdio server 时用的沙箱工作区;任务里用对话 ID。 */
        const val SETTINGS_WORKSPACE = "mcp-settings"
        /**
         * 任务开头连接外部 server 最多等这么久(所有 server 一起算)。单个 server 自己的超时可以长到几分钟,
         * 那是给工具调用的;开头只是握手,慢的这次先不接,不让任务一直停在「准备中」。沙箱里的 stdio server 要起 PRoot,留得宽一点。
         */
        const val CONNECT_BUDGET_MS = 15_000L
    }

    private fun isBlockedHost(host: String): Boolean =
        host == "169.254.169.254" || host == "metadata.google.internal" ||
            host.startsWith("169.254.") || host == "0.0.0.0" || host == "::" || host == "[::]" ||
            host.startsWith("fd00:ec2:") || host.startsWith("fe80:")
}
