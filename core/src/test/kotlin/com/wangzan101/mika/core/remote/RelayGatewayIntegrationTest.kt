package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/** 真正启动 Go 网关,经 WebSocket 接到与 Android 完全相同的隧道与 MCP 入口。 */
class RelayGatewayIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun publicClientPairsDispatchesTasksAndRevokesAcrossGoAndKotlin() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port"
        val key = "integration-test-owner-key-not-a-real-secret"
        val owner = tmp.newFile("owner-key").apply { writeText(key) }
        val log = tmp.newFile("gateway.log")
        val process = ProcessBuilder(System.getProperty("mika.relayBinary"), "-listen", "127.0.0.1:$port", "-public-url", base,
            "-local-http", "-trust-proxy", "-owner-key-file", owner.absolutePath, "-data", tmp.root.resolve("relay.json").absolutePath)
            .redirectErrorStream(true).redirectOutput(log).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        var tunnel: RelayClient? = null
        try {
            withTimeout(10_000) {
                while (runCatching { http.newCall(Request.Builder().url("$base/healthz").build()).execute().use { it.code == 200 } }.getOrDefault(false).not()) {
                    check(process.isAlive) { "Gateway failed to start" }; delay(50)
                }
            }
            val form = FormBody.Builder().add("owner_key", key).add("action", "pair").build()
            val html = http.newCall(Request.Builder().url("$base/admin").post(form).build()).execute().use { assertEquals(200, it.code); it.body!!.string() }
            val code = Regex("<strong>([A-Za-z0-9_-]{16})</strong>").find(html)!!.groupValues[1]
            val gateway = RelayGateway(RelayGateway.parse(base, allowLoopbackHttp = true), http)
            val pairing = gateway.pair(code)
            val tasks = FakeRemoteTasks()
            val endpoints = RemoteEndpoints(tasks, "integration")
            val phoneToken = AtomicReference("phone-common-access-token")
            val access = RemoteAccess(phoneToken::get, loopbackToken = { "usb-only-token" })
            tunnel = RelayClient(scope, { req -> access.handle(req, endpoints::handle) }, allowLoopbackHttp = true)
            tunnel.start(pairing)
            withTimeout(5_000) { tunnel.state.first { it == RelayState.Online } }
            // The test client stands in for the reverse proxy: the gateway reads the caller from X-Forwarded-For.
            fun post(body: String, token: String = phoneToken.get(), caller: String = "198.51.100.7"): Pair<Int, String> =
                http.newCall(Request.Builder().url("$base/mcp").header("Authorization", "Bearer $token").header("X-Forwarded-For", caller)
                    .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { it.code to it.body!!.string() }
            repeat(5) { assertEquals(401, post("{}", "anonymous-client", caller = "203.0.113.9").first) }
            assertEquals(429, post("{}", "anonymous-client", caller = "203.0.113.9").first)
            // A locked-out caller learns nothing, not even with the right token; other callers are unaffected.
            assertEquals(429, post("""{"jsonrpc":"2.0","id":0,"method":"tools/list"}""", caller = "203.0.113.9").first)
            assertEquals(200, post("""{"jsonrpc":"2.0","id":0,"method":"tools/list"}""").first)
            assertEquals(401, post("{}", pairing.deviceToken).first)
            assertEquals(401, post("{}", "usb-only-token").first)
            val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"remote-check","version":"1"}}}""")
            assertEquals(200, init.first); assertTrue(init.second.contains("2025-11-25"))
            val tools = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals(200, tools.first); assertTrue(tools.second.contains("mika_run_task"))
            val run = post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mika_run_task","arguments":{"goal":"test remote dispatch"}}}""")
            assertEquals(200, run.first); assertTrue(run.second, run.second.contains("\"task_id\":\"t1\""))
            tasks.set("t1", TaskState.Succeeded("remote result"))
            val result = post("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"mika_wait_task","arguments":{"task_id":"t1","timeout_s":1}}}""")
            assertTrue(result.second, result.second.contains("remote result"))
            assertTrue(endpoints.activity.value.recentClients > 0)
            // Run the shipped Python CLI through the real Go gateway and Kotlin phone API.
            val tokenFile = tmp.newFile("phone-token").apply { writeText(phoneToken.get()) }
            val script = File("../companions/mika/scripts/mika_cli.py").canonicalPath
            fun cli(vararg args: String): JsonObject {
                lateinit var err: File
                val cli = ProcessBuilder(listOf(System.getProperty("mika.python", "python3"), script,
                    "--url", base, "--token-file", tokenFile.absolutePath, "--json") + args)
                    .directory(tmp.root).redirectError(File.createTempFile("cli", ".err", tmp.root).also { err = it }).start()
                if (!cli.waitFor(10, TimeUnit.SECONDS)) { cli.destroyForcibly(); fail("Remote CLI timed out") }
                // Progress goes to stderr; stdout carries only the JSON result.
                val output = cli.inputStream.bufferedReader().readText()
                assertFalse(output.contains(phoneToken.get())); assertFalse(err.readText().contains(phoneToken.get()))
                assertEquals(output, 0, cli.exitValue())
                return Json.parseToJsonElement(output).jsonObject
            }
            assertTrue(cli("connect")["connected"]!!.jsonPrimitive.boolean)
            assertEquals("unverified", cli("doctor")["model_configuration"]!!.jsonPrimitive.content)
            assertEquals("done", cli("task", "status", "t1")["status"]!!.jsonPrimitive.content)
            val created = cli("task", "run", "--goal", "远程 CLI \"任务\" 👋")
            val id = created["task_id"]!!.jsonPrimitive.content
            tasks.set(id, TaskState.Running(1, "working"))
            assertTrue(cli("task", "steer", id, "--text", "继续只读验证")["accepted"]!!.jsonPrimitive.boolean)
            assertEquals(1, cli("task", "list", "--limit", "1")["tasks"]!!.jsonArray.size)
            tasks.set(id, TaskState.WaitingInput("Which?", listOf("one", "two")))
            assertTrue(cli("task", "answer", id, "--text", "one")["accepted"]!!.jsonPrimitive.boolean)
            tasks.set(id, TaskState.Succeeded("CLI result"))
            assertEquals("CLI result", cli("task", "wait", id, "--timeout", "1")["answer"]!!.jsonPrimitive.content)
            val cancelId = cli("task", "run", "--goal", "cancel test")["task_id"]!!.jsonPrimitive.content
            assertTrue(cli("task", "cancel", cancelId)["accepted"]!!.jsonPrimitive.boolean)
            // No gateway update or re-pairing: phone token rotation governs both protocols immediately.
            val oldToken = phoneToken.getAndSet("rotated-phone-common-access-token")
            assertEquals(401, post("{}", oldToken).first)
            assertEquals(200, post("""{"jsonrpc":"2.0","id":5,"method":"tools/list"}""").first)
            tokenFile.writeText(phoneToken.get())
            assertTrue(cli("connect")["connected"]!!.jsonPrimitive.boolean)
            val state = tmp.root.resolve("relay.json").readText()
            assertFalse(state.contains(oldToken)); assertFalse(state.contains(phoneToken.get()))
            assertEquals(setOf("device_hash"), Json.parseToJsonElement(state).jsonObject.keys)
            gateway.unpair(pairing.deviceToken)
            assertEquals(503, post("{}").first)
        } finally {
            tunnel?.stop(); scope.cancel()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
