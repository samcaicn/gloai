package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCopy
import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.core.cap.mcp.McpStdioLauncher
import com.wangzan101.mika.core.cap.mcp.McpStdioProcess
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.learn.knowledge.ScriptEntry
import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.McpServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class McpPackageManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private fun assertThrowsSuspend(type: Class<out Throwable>, block: suspend () -> Unit): SandboxError =
        assertThrows(type) { runBlocking { block() } } as SandboxError

    /** 记录 run 的命令、按命令类型回结果;block 非空时 run 挂起(用于取消)。 */
    private class FakePort : SandboxPort {
        override val tree = SandboxTree()
        val runs = mutableListOf<List<String>>()
        val markers = mutableSetOf<String>()
        var installExit = 0
        var installOutput = ""
        var block: CompletableDeferred<Unit>? = null
        var network = true

        override fun status() = SandboxStatus.Ready(network = network)
        override fun networkAllowed() = network

        override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult {
            runs += argv
            block?.await()
            return when (argv.firstOrNull()) {
                "test" -> CommandResult(if (argv.last() in markers) 0 else 1, false, false, 1, "", 0, network)
                "touch" -> { markers += argv.last(); CommandResult(0, false, false, 1, "", 0, network) }
                "mkdir", "rm" -> CommandResult(0, false, false, 1, "", 0, network)
                else -> CommandResult(installExit, false, false, 10, installOutput, 0, network)
            }
        }

        override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? = null
        override val files = WorkspaceFileGate(NioFdSys)
        override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = null
        override suspend fun share(file: File, name: String): String = ""
        override fun freeBytes(): Long = 1L shl 30
        override fun workspaceBytes(workspace: String): Long = 0
        override fun saveOutput(workspace: String, text: String): String = ""
        override fun scriptManifest(skillPath: String): List<ScriptEntry> = emptyList()
    }

    private val npm = McpPackageSpec("npm", "@scope/server-filesystem", "1.2.3", "https://registry.npmjs.org/package/server-filesystem")
    private val pypi = McpPackageSpec("pypi", "mcp-server-fetch", "0.9.0", "https://pypi.org/project/mcp-server-fetch/")

    @Test fun installRequiresMatchingConsentAndNeverRunsAfterCancellation() = runBlocking {
        val port = FakePort()
        val consent = McpPackageConsent()
        val manager = McpPackageManager(port)

        // 未确认且未缓存:要求确认,不执行任何安装。
        val denied = assertThrowsSuspend(SandboxError::class.java) { manager.prepare("w", npm, confirmed = false) }
        assertEquals("mcp_package_consent", denied.code)
        assertTrue("no install must run before consent", port.runs.none { it.firstOrNull() == "npm" })

        // 版本不同:同一包名同意也不算。
        consent.accept(npm.copy(version = "9.9.9"))
        assertThrowsSuspend(SandboxError::class.java) { manager.prepare("w", npm, confirmed = consent.accepted(npm)) }

        // 来源不同:不算。
        consent.clear()
        consent.accept(npm.copy(source = "https://elsewhere.example"))
        assertThrowsSuspend(SandboxError::class.java) { manager.prepare("w", npm, confirmed = consent.accepted(npm)) }

        // 精确同意后,安装进行;取消时不得留下成功标记、不得执行服务。
        consent.clear()
        consent.accept(npm)
        port.block = CompletableDeferred()
        val job = launch { manager.prepare("w", npm, confirmed = true) }
        job.cancelAndJoin()
        assertTrue("cancelled install must leave no success marker", port.markers.isEmpty())
        assertTrue(port.runs.none { it.firstOrNull() == "touch" } || port.markers.isEmpty())
    }

    @Test fun installIsSharedCachedAndFailureIsRetryable() = runBlocking {
        val port = FakePort()
        val consent = McpPackageConsent()
        consent.accept(npm)
        val manager = McpPackageManager(port)

        // 首次安装:跑 npm install + touch 标记,返回 .bin 目录前缀。
        val prefix = manager.prepare("w1", npm, confirmed = true)
        assertEquals(listOf("/opt/mika/mcp-cache/npm-_scope_server-filesystem-1.2.3/node_modules/.bin"), prefix)
        assertEquals(1, port.runs.count { it.firstOrNull() == "npm" })

        // 已缓存:不再安装(同一工作区)。
        manager.prepare("w1", npm, confirmed = false)
        assertEquals(1, port.runs.count { it.firstOrNull() == "npm" })

        // 共享缓存:另一工作区复用同一份,不重装。
        manager.prepare("w2", npm, confirmed = false)
        assertEquals(1, port.runs.count { it.firstOrNull() == "npm" })

        // 失败不留标记,可重试。
        val p2 = FakePort()
        p2.installExit = 1
        p2.installOutput = "EACCES"
        val m2 = McpPackageManager(p2)
        val c2 = McpPackageConsent().apply { accept(pypi) }
        val failed = assertThrowsSuspend(SandboxError::class.java) { m2.prepare("w", pypi, confirmed = true) }
        assertEquals("mcp_package_install_failed", failed.code)
        assertTrue(p2.markers.isEmpty())
        p2.installExit = 0
        m2.prepare("w", pypi, confirmed = true)
        assertEquals(2, p2.runs.count { it.firstOrNull() == "python3" })
    }

    @Test fun networkOffAndUnsupportedPackageFailWithGuidance() = runBlocking {
        val port = FakePort().apply { network = false }
        val consent = McpPackageConsent().apply { accept(npm) }
        val manager = McpPackageManager(port)
        val e = assertThrowsSuspend(SandboxError::class.java) { manager.prepare("w", npm, confirmed = true) }
        assertEquals("mcp_package_network", e.code)

        val ruby = McpPackageSpec("ruby", "some-gem", "1.0.0", "https://rubygems.org/gems/some-gem")
        val m2 = McpPackageManager(FakePort())
        val u = assertThrowsSuspend(SandboxError::class.java) { m2.prepare("w", ruby, confirmed = true) }
        assertEquals("mcp_package_unsupported", u.code)
    }

    @Test fun consentPersistsAcrossInstances() {
        val file = File(tmp.root, "consent.json")
        val a = McpPackageConsent(file)
        assertFalse(a.accepted(npm))
        a.accept(npm)
        // 新实例从磁盘读回已确认项;换版本/来源不算同一项。
        val b = McpPackageConsent(file)
        assertTrue(b.accepted(npm))
        assertFalse(b.accepted(npm.copy(version = "9.9.9")))
        b.accept(pypi)
        assertTrue(McpPackageConsent(file).accepted(pypi))
    }

    @Test fun nodeAndPythonPackagesResolvePinnedArgvWithoutSecrets() = runBlocking {
        val port = FakePort()
        val consent = McpPackageConsent().apply { accept(npm); accept(pypi) }
        val manager = McpPackageManager(port)

        val npmPrefix = manager.prepare("w", npm, confirmed = true)
        assertEquals(listOf("/opt/mika/mcp-cache/npm-_scope_server-filesystem-1.2.3/node_modules/.bin"), npmPrefix)
        val npmInstall = port.runs.first { it.firstOrNull() == "npm" }
        assertEquals("npm", npmInstall[0])
        assertTrue("@scope/server-filesystem@1.2.3" in npmInstall)
        for (f in listOf("--ignore-scripts", "--no-audit", "--no-fund", "--no-save")) assertTrue(f, f in npmInstall)

        val pypiPrefix = manager.prepare("w", pypi, confirmed = true)
        assertEquals(listOf("/opt/mika/mcp-cache/pypi-mcp-server-fetch-0.9.0/bin"), pypiPrefix)
        val pypiInstall = port.runs.first { it.firstOrNull() == "python3" }
        assertTrue("mcp-server-fetch==0.9.0" in pypiInstall)
        assertTrue("--only-binary=:all:" in pypiInstall)

        // 秘密绝不出现在 argv 前缀或安装命令里。
        for (argv in port.runs) assertFalse("secret must not reach argv", argv.any { it.contains("SECRET") })
        assertFalse(npmPrefix.any { it.contains("SECRET") })
    }

    /** 只回答 initialize 和 tools/list 的 stdio server,供 store 测试验证启动与 argv 组合。 */
    private fun stdioProcess(): McpStdioProcess {
        val toServer = PipedOutputStream()
        val fromClient = PipedInputStream(toServer)
        val fromServer = PipedOutputStream()
        val toClient = PipedInputStream(fromServer)
        val json = Json { ignoreUnknownKeys = true }
        thread(isDaemon = true, name = "fake-pkg-stdio") {
            runCatching {
                val reader = fromClient.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    val message = json.parseToJsonElement(line).let { it as? kotlinx.serialization.json.JsonObject } ?: continue
                    val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val result = when (method) {
                        "initialize" -> buildJsonObject { put("protocolVersion", "2025-03-26"); put("serverInfo", buildJsonObject { put("name", "fs") }) }
                        "tools/list" -> buildJsonObject { put("tools", JsonArray(emptyList())) }
                        else -> continue
                    }
                    val response = buildJsonObject { put("jsonrpc", "2.0"); put("id", id.toLong()); put("result", result) }
                    synchronized(fromServer) { fromServer.write((response.toString() + "\n").toByteArray()); fromServer.flush() }
                }
            }
        }
        return object : McpStdioProcess {
            override val stdin = toServer
            override val stdout = toClient
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() {
                runCatching { toServer.close() }; runCatching { fromServer.close() }
                runCatching { fromClient.close() }; runCatching { toClient.close() }
            }
        }
    }

    /** 预设含包的 stdio 服务:未确认不安装不启动;确认后安装并拼成 argv 前缀/可执行名。 */
    @Test fun storeRequiresConsentAndComposesPackageArgv() = runBlocking {
        val preset = McpPreset(
            id = "fs", name = "Filesystem", summary = McpCopy(mapOf("en" to "Filesystem")),
            category = "dev", transport = "stdio", auth = "none",
            command = listOf("mcp-server-filesystem"),
            packageSpec = McpPackageSpec("npm", "server-filesystem", "1.0.0", "https://registry.npmjs.org/package/server-filesystem"),
            officialSource = "https://registry.npmjs.org/package/server-filesystem",
        )
        val catalog = McpCatalog(McpCatalog.CurrentFormatVersion, listOf(preset))
        val port = FakePort()
        var launchedArgv: List<String>? = null
        val launcher = McpStdioLauncher { _, argv, _ -> launchedArgv = argv; stdioProcess() }
        val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = launcher, catalog = catalog, packageProvisioner = McpPackageManager(port))
        val config = s.presetConfig("fs", emptyMap()).getOrThrow()
        s.add(config).getOrThrow()

        // 未确认:连接失败,不安装、不启动。
        assertTrue(s.test(config).isFailure)
        assertNull(launchedArgv)
        assertTrue(port.runs.none { it.firstOrNull() == "npm" })

        // 确认后:安装并启动,argv 拼成「缓存 .bin 目录 / 可执行名」。
        s.acceptPackage(preset.packageSpec!!)
        assertTrue(s.test(config).isSuccess)
        assertEquals(listOf("/opt/mika/mcp-cache/npm-server-filesystem-1.0.0/node_modules/.bin/mcp-server-filesystem"), launchedArgv)
    }
}
