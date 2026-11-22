package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class StdioMcpClientTest {
    private class FakeProcess(private val strictIds: Boolean = false) : McpStdioProcess, Closeable {
        private val toServer = PipedOutputStream()
        private val fromClient = PipedInputStream(toServer)
        private val fromServer = PipedOutputStream()
        private val toClient = PipedInputStream(fromServer)
        private val json = Json { ignoreUnknownKeys = true }
        private val seen = HashSet<String>()

        override val stdin = toServer
        override val stdout = toClient
        override val stderr = ByteArrayInputStream(ByteArray(0))

        init {
            thread(isDaemon = true, name = "fake-mcp-stdio") {
                val reader = fromClient.bufferedReader(Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        val message = json.parseToJsonElement(line) as? JsonObject ?: continue
                        val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                        val result = when (method) {
                            "initialize" -> buildJsonObject {
                                put("protocolVersion", "2025-03-26")
                                put("serverInfo", buildJsonObject { put("name", "stdio") })
                                put("instructions", "local server")
                                put("capabilities", buildJsonObject {
                                    put("tools", buildJsonObject {})
                                    put("resources", buildJsonObject {})
                                    put("prompts", buildJsonObject {})
                                })
                            }
                            "tools/list" -> buildJsonObject {
                                put("tools", kotlinx.serialization.json.JsonArray(listOf(
                                    buildJsonObject {
                                        put("name", "search")
                                        put("description", "Search")
                                        put("inputSchema", buildJsonObject { put("type", "object") })
                                    },
                                )))
                            }
                            "resources/list" -> buildJsonObject {
                                put("resources", kotlinx.serialization.json.JsonArray(listOf(
                                    buildJsonObject { put("uri", "file://a"); put("name", "A") },
                                )))
                            }
                            "prompts/list" -> buildJsonObject {
                                put("prompts", kotlinx.serialization.json.JsonArray(listOf(
                                    buildJsonObject { put("name", "summarize") },
                                )))
                            }
                            "resources/read" -> buildJsonObject {
                                put("contents", kotlinx.serialization.json.JsonArray(listOf(
                                    buildJsonObject { put("uri", "file://a"); put("text", "resource body") },
                                )))
                            }
                            "prompts/get" -> buildJsonObject {
                                put("messages", kotlinx.serialization.json.JsonArray(listOf(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", buildJsonObject { put("type", "text"); put("text", "do it") })
                                    },
                                )))
                            }
                            "tools/call" -> buildJsonObject {
                                put("content", kotlinx.serialization.json.JsonArray(listOf(
                                    buildJsonObject { put("type", "text"); put("text", "found") },
                                )))
                            }
                            else -> continue
                        }
                        val response = buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id.toLong())
                            if (strictIds && !seen.add(id)) put("error", buildJsonObject {
                                put("code", -32600); put("message", "request ID reused")
                            }) else put("result", result)
                        }
                        synchronized(fromServer) {
                            fromServer.write((response.toString() + "\n").toByteArray())
                            fromServer.flush()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        override fun close() {
            runCatching { toServer.close() }
            runCatching { fromServer.close() }
            runCatching { fromClient.close() }
            runCatching { toClient.close() }
        }
    }

    @Test(timeout = 20_000) fun speaksJsonRpcOverStdio() = runBlocking {
        val process = FakeProcess()
        val endpoint = StdioMcpEndpoint.connect(
            serverId = "local",
            command = listOf("fake-server"),
            launcher = McpStdioLauncher { _, _, _ -> process },
            workspace = "test",
            timeoutSec = 5,
        ).getOrThrow()
        try {
            assertEquals("stdio", endpoint.serverName)
            assertEquals("local server", endpoint.instructions)
            assertEquals(listOf("search"), endpoint.tools().map { it.name })
            assertEquals(listOf("file://a"), endpoint.resources().map { it.uri })
            assertEquals(listOf("summarize"), endpoint.prompts().map { it.name })
            assertEquals("resource body", endpoint.readResource("file://a").text)
            assertEquals("user: do it", endpoint.getPrompt("summarize", buildJsonObject {}).text)

            val result = endpoint.call("search", buildJsonObject { put("q", "x") })
            assertFalse(result.isError)
            assertEquals("found", result.text)
        } finally {
            endpoint.close()
        }
    }

    @Test(timeout = 20_000) fun requestIdsRemainUniqueAfterHandshake() = runBlocking {
        val process = FakeProcess(strictIds = true)
        val endpoint = StdioMcpEndpoint.connect("strict", listOf("fake-server"),
            McpStdioLauncher { _, _, _ -> process }, "test", timeoutSec = 5).getOrThrow()
        try {
            assertEquals("resource body", endpoint.readResource("file://a").text)
            assertEquals("user: do it", endpoint.getPrompt("summarize", buildJsonObject {}).text)
            kotlinx.coroutines.coroutineScope {
                (1..8).map { async {
                    endpoint.call("search", buildJsonObject {})
                } }.forEach { assertEquals("found", it.await().text) }
            }
        } finally { endpoint.close() }
    }

    @Test fun parsesCommandLineWithoutShell() {
        assertEquals(listOf("python3", "/root/server.py", "--flag", "a b"),
            parseStdioCommand("python3 /root/server.py --flag \"a b\""))
        assertEquals(listOf("a b", "c"), parseStdioCommand("a\\ b c"))
        assertEquals(listOf(""), parseStdioCommand("\"\""))
        assertThrows(McpClientException::class.java) { parseStdioCommand("\"unclosed") }
    }

    @Test(timeout = 20_000) fun stdioPassesOnlyResolvedEnvironment() = runBlocking {
        val process = FakeProcess()
        var receivedEnv: Map<String, String> = emptyMap()
        var receivedArgv: List<String> = emptyList()
        val endpoint = StdioMcpEndpoint.connect(
            serverId = "local",
            command = listOf("fake-server", "--region", "us"),
            launcher = McpStdioLauncher { _, argv, environment ->
                receivedArgv = argv
                receivedEnv = environment
                process
            },
            workspace = "test",
            environment = mapOf("SECRET_TOKEN" to "s3cret", "REGION" to "us"),
            timeoutSec = 5,
        ).getOrThrow()
        try {
            assertEquals(mapOf("SECRET_TOKEN" to "s3cret", "REGION" to "us"), receivedEnv)
            assertEquals(listOf("fake-server", "--region", "us"), receivedArgv)
            assertFalse("argv must never carry secrets", receivedArgv.contains("s3cret"))
        } finally {
            endpoint.close()
        }
    }

    @Test(timeout = 20_000) fun legacyStdioConfigStillLaunchesWithNoExtraEnvironment() = runBlocking {
        var receivedEnv: Map<String, String>? = null
        val endpoint = StdioMcpEndpoint.connect(
            serverId = "legacy",
            command = listOf("fake-server"),
            launcher = McpStdioLauncher { _, _, environment -> receivedEnv = environment; FakeProcess() },
            workspace = "test",
            timeoutSec = 5,
        ).getOrThrow()
        try {
            assertEquals(emptyMap<String, String>(), receivedEnv)
        } finally {
            endpoint.close()
        }
    }
}
