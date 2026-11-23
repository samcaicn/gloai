package com.wangzan101.mika.mcp.ui

import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCopy
import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.core.cap.mcp.McpPresetField
import com.wangzan101.mika.core.cap.mcp.McpStdioLauncher
import com.wangzan101.mika.core.cap.mcp.McpStdioProcess
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.learn.knowledge.ScriptEntry
import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.mcp.McpPackageManager
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.McpServerStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class McpConnectorStateTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private class FakePort : SandboxPort {
        override val tree = SandboxTree()
        val runs = mutableListOf<List<String>>()
        val markers = mutableSetOf<String>()
        var network = true
        override fun status() = SandboxStatus.Ready(network = network)
        override fun networkAllowed() = network
        override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult {
            runs += argv
            return when (argv.firstOrNull()) {
                "test" -> CommandResult(if ("$workspace|${argv.last()}" in markers) 0 else 1, false, false, 1, "", 0, network)
                "touch" -> { markers += "$workspace|${argv.last()}"; CommandResult(0, false, false, 1, "", 0, network) }
                "mkdir", "rm" -> CommandResult(0, false, false, 1, "", 0, network)
                else -> CommandResult(0, false, false, 10, "", 0, network)
            }
        }
        override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): java.io.File? = null
        override val files = WorkspaceFileGate(NioFdSys)
        override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<java.io.File, String>? = null
        override suspend fun share(file: java.io.File, name: String): String = ""
        override fun freeBytes(): Long = 1L shl 30
        override fun workspaceBytes(workspace: String): Long = 0
        override fun saveOutput(workspace: String, text: String): String = ""
        override fun scriptManifest(skillPath: String): List<ScriptEntry> = emptyList()
    }

    private fun stdioProcess(): McpStdioProcess {
        val toServer = PipedOutputStream()
        val fromClient = PipedInputStream(toServer)
        val fromServer = PipedOutputStream()
        val toClient = PipedInputStream(fromServer)
        val json = Json { ignoreUnknownKeys = true }
        thread(isDaemon = true, name = "fake-connector-stdio") {
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

    private fun catalog(vararg presets: McpPreset) = McpCatalog(McpCatalog.CurrentFormatVersion, presets.toList())

    private fun httpFieldsPreset() = McpPreset(
        id = "acme", name = "Acme", summary = McpCopy(mapOf("en" to "Acme")),
        category = "data", transport = "streamable-http", auth = "fields",
        endpoint = "https://127.0.0.1:1/mcp",
        fields = listOf(
            McpPresetField("tenant", McpCopy(mapOf("en" to "Tenant")), secret = false, required = true, target = "url"),
            McpPresetField("token", McpCopy(mapOf("en" to "Token")), secret = true, required = true, target = "bearer"),
        ),
        officialSource = "https://example.com/docs",
    )

    private fun stdioPreset(packageSpec: McpPackageSpec? = null) = McpPreset(
        id = "fs", name = "Filesystem", summary = McpCopy(mapOf("en" to "Filesystem")),
        category = "dev", transport = "stdio", auth = "none",
        command = listOf("mcp-server-filesystem"),
        packageSpec = packageSpec,
        officialSource = "https://registry.npmjs.org/package/server-filesystem",
    )

    private fun oauthPreset() = McpPreset(
        id = "cloud", name = "Cloud", summary = McpCopy(mapOf("en" to "Cloud")),
        category = "data", transport = "streamable-http", auth = "oauth",
        endpoint = "https://{{tenant}}.example.com/mcp",
        fields = listOf(McpPresetField("tenant", McpCopy(mapOf("en" to "Tenant")), secret = false, required = true, target = "url")),
        officialSource = "https://example.com/docs",
    )

    @Test fun openPresetLoadsOnlyDeclaredFields() {
        val cat = catalog(httpFieldsPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        connector.openPreset("acme")
        val form = connector.stage.value as McpConnectStage.Form
        assertEquals("acme", form.preset.id)
        assertTrue(form.values.isEmpty())
        assertTrue(form.secretValues.isEmpty())
        assertFalse(form.needsPackageConsent)
        assertFalse(form.connecting)
    }

    @Test fun updateFieldRoutesSecretAndPlain() {
        val cat = catalog(httpFieldsPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        connector.openPreset("acme")
        connector.updateField("tenant", "north")
        connector.updateField("token", "bearer-secret")
        val form = connector.stage.value as McpConnectStage.Form
        assertEquals(mapOf("tenant" to "north"), form.values)
        assertEquals(mapOf("token" to "bearer-secret"), form.secretValues)
    }

    @Test fun reopenSamePresetKeepsNonSecretDraft() {
        val cat = catalog(httpFieldsPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        connector.openPreset("acme")
        connector.updateField("tenant", "north")
        // 返回目录(不 reset 连接器)后重新打开同一预设:非秘密草稿还在,连接状态复位。
        connector.openPreset("acme")
        val form = connector.stage.value as McpConnectStage.Form
        assertEquals(mapOf("tenant" to "north"), form.values)
        assertFalse(form.connecting)
        assertNull(form.error)
    }

    @Test fun reopenSamePresetDropsSecretDraft() {
        val cat = catalog(httpFieldsPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        connector.openPreset("acme")
        connector.updateField("tenant", "north")
        connector.updateField("token", "bearer-secret")
        // 返回目录后重开同一预设:非秘密草稿保留,秘密草稿丢弃(不再残留显示)。
        connector.openPreset("acme")
        val form = connector.stage.value as McpConnectStage.Form
        assertEquals(mapOf("tenant" to "north"), form.values)
        assertTrue(form.secretValues.isEmpty())
    }

    @Test fun replaceSecretsWritesOnlyDeclaredNonBlankFields() {
        val cat = catalog(httpFieldsPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val config = store.presetConfig("acme", mapOf("tenant" to "north")).getOrThrow()
        store.add(config)
        store.setSecret(config.id, "token", "old-secret")

        // 非空替换;空值保留;未声明的字段被忽略。
        connector.replaceSecrets(config, mapOf("token" to "new-secret", "nope" to "x"))
        assertEquals("new-secret", store.secret(config.id, "token"))
        assertNull(store.secret(config.id, "nope"))

        connector.replaceSecrets(config, mapOf("token" to ""))
        assertEquals("new-secret", store.secret(config.id, "token"))
    }

    @Test fun connectFailurePreservesInputs() = runBlocking {
        val cat = catalog(httpFieldsPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, this)
        connector.openPreset("acme")
        connector.updateField("tenant", "north")
        connector.updateField("token", "bearer-secret")
        connector.connect()
        connector.inFlight?.join()
        val form = connector.stage.value as McpConnectStage.Form
        assertNotNull(form.error)
        assertEquals(mapOf("tenant" to "north"), form.values)
        assertEquals(mapOf("token" to "bearer-secret"), form.secretValues)
        assertFalse(form.connecting)
    }

    @Test fun connectStdioSucceedsAndEnables() = runBlocking {
        val cat = catalog(stdioPreset())
        val launcher = McpStdioLauncher { _, _, _ -> stdioProcess() }
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = launcher, catalog = cat)
        val connector = McpConnectorState(store, cat, this)
        connector.openPreset("fs")
        connector.connect()
        connector.inFlight?.join()
        val connected = connector.stage.value as McpConnectStage.Connected
        assertEquals("fs", connected.config.presetId)
        assertTrue(store.servers.value.single().enabled)
    }

    @Test fun stdioPackageRequiresConsentBeforeDownload() = runBlocking {
        val spec = McpPackageSpec("npm", "server-filesystem", "1.0.0", "https://registry.npmjs.org/package/server-filesystem")
        val cat = catalog(stdioPreset(spec))
        val port = FakePort()
        val launcher = McpStdioLauncher { _, _, _ -> stdioProcess() }
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = launcher, catalog = cat, packageProvisioner = McpPackageManager(port))
        val connector = McpConnectorState(store, cat, this)
        connector.openPreset("fs")
        assertTrue((connector.stage.value as McpConnectStage.Form).needsPackageConsent)

        // 未确认:不发起连接,也不触发任何安装。
        connector.connect()
        assertFalse(store.packageAccepted(spec))
        assertTrue(port.runs.none { it.firstOrNull() == "npm" })
        assertFalse((connector.stage.value as McpConnectStage.Form).connecting)

        // 确认后:接受包并继续连接,成功。
        connector.confirmPackage()
        connector.inFlight?.join()
        assertTrue(store.packageAccepted(spec))
        assertTrue(connector.stage.value is McpConnectStage.Connected)
        assertTrue(port.runs.any { it.firstOrNull() == "npm" })
    }

    @Test fun authorizeMissingRequiredFieldSurfacesError() = runBlocking {
        val cat = catalog(oauthPreset())
        val store = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, this)
        connector.openPreset("cloud")
        connector.authorize { }
        connector.inFlight?.join()
        val form = connector.stage.value as McpConnectStage.Form
        assertNotNull(form.error)
        assertNull(store.servers.value.firstOrNull())
    }
}
