package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCopy
import com.wangzan101.mika.core.cap.mcp.McpOAuthTokens
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.core.cap.mcp.McpPresetField
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpPresetConnectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private fun catalog(vararg presets: McpPreset) = McpCatalog(McpCatalog.CurrentFormatVersion, presets.toList())

    private fun store(catalog: McpCatalog) = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), catalog = catalog)

    private fun httpPreset() = McpPreset(
        id = "acme", name = "Acme", summary = McpCopy(mapOf("en" to "Acme")),
        category = "data", transport = "streamable-http", auth = "fields",
        endpoint = "https://api.example.com/{{tenant}}/mcp",
        fields = listOf(
            McpPresetField("tenant", McpCopy(mapOf("en" to "Tenant")), secret = false, required = true, target = "url"),
            McpPresetField("token", McpCopy(mapOf("en" to "Token")), secret = true, required = true, target = "bearer"),
            McpPresetField("apiKey", McpCopy(mapOf("en" to "API key")), secret = true, required = true, target = "header", name = "X-Api-Key"),
            McpPresetField("region", McpCopy(mapOf("en" to "Region")), secret = false, required = true, target = "header", name = "X-Region"),
        ),
        headers = mapOf("X-Client" to "mika"),
        officialSource = "https://example.com/docs",
    )

    private fun oauthPreset() = McpPreset(
        id = "acme", name = "Acme", summary = McpCopy(mapOf("en" to "Acme")),
        category = "data", transport = "streamable-http", auth = "oauth",
        endpoint = "https://api.example.com/mcp",
        officialSource = "https://example.com/docs",
    )

    private fun scriptTools(server: MockWebServer, vararg tools: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"}}}"""
        ))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[""" +
                tools.joinToString(",") { """{"name":"$it","description":"","inputSchema":{"type":"object"}}""" } + "]}}"
        ))
    }

    @Test fun presetHttpUsesResolvedUrlHeadersAndBearer() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            scriptTools(server, "search")
            val s = store(catalog(httpPreset()))
            val config = s.presetConfig("acme", mapOf("tenant" to "north / blue", "region" to "cn")).getOrThrow()
            assertEquals("https://api.example.com/north%20%2F%20blue/mcp", config.url)
            assertEquals("fields", config.auth)
            assertEquals(mapOf("tenant" to "north / blue", "region" to "cn"), config.fieldValues)
            assertEquals(mapOf("X-Client" to "mika"), config.headers)

            val mockUrl = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()
            val updated = config.copy(url = mockUrl, allowLocalHttp = true)
            s.update(updated).getOrThrow()
            s.setSecret(config.id, "token", "bearer-secret")
            s.setSecret(config.id, "apiKey", "header-secret")

            val result = s.test(updated).getOrThrow()
            assertEquals(listOf("search"), result.tools)

            val request = server.takeRequest()
            assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
            assertEquals("header-secret", request.getHeader("X-Api-Key"))
            assertEquals("cn", request.getHeader("X-Region"))
            assertEquals("mika", request.getHeader("X-Client"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun presetOAuthReusesExistingHeaderPath() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            scriptTools(server, "search")
            val s = store(catalog(oauthPreset()))
            val config = s.presetConfig("acme", emptyMap()).getOrThrow()
            assertEquals("oauth", config.auth)
            val mockUrl = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()
            val updated = config.copy(url = mockUrl, allowLocalHttp = true)
            s.update(updated).getOrThrow()
            s.setOAuthTokens(config.id, McpOAuthTokens("at", null, null, "cid", null, server.url("/token").toString()))

            val result = s.test(updated).getOrThrow()
            assertEquals(listOf("search"), result.tools)
            assertEquals("Bearer at", server.takeRequest().getHeader("Authorization"))
            assertEquals("at", s.oauthTokens(config.id)?.accessToken)
        } finally {
            server.shutdown()
        }
    }

    @Test fun failedPresetTestLeavesDisabledEditableConfig() = runBlocking {
        val preset = McpPreset(
            id = "dead", name = "Dead", summary = McpCopy(mapOf("en" to "Dead")),
            category = "data", transport = "streamable-http", auth = "fields",
            endpoint = "https://127.0.0.1:1/mcp",
            fields = listOf(McpPresetField("token", McpCopy(mapOf("en" to "Token")), secret = true, required = true, target = "bearer")),
            officialSource = "https://example.com/docs",
        )
        val s = store(catalog(preset))
        val result = s.connectPreset("dead", emptyMap(), mapOf("token" to "secret-token"))
        assertTrue(result.isFailure)
        val saved = s.servers.value.single()
        assertEquals("dead", saved.presetId)
        assertFalse(saved.enabled)
        assertEquals("secret-token", s.secret(saved.id, "token"))
        assertFalse(s.health.value.getValue(saved.id).ok)
        assertEquals(0, s.connections().connections.size)
    }

    @Test fun connectPresetRetryReusesConfigInsteadOfDuplicating() = runBlocking {
        val preset = McpPreset(
            id = "dead", name = "Dead", summary = McpCopy(mapOf("en" to "Dead")),
            category = "data", transport = "streamable-http", auth = "fields",
            endpoint = "https://127.0.0.1:1/mcp",
            fields = listOf(McpPresetField("token", McpCopy(mapOf("en" to "Token")), secret = true, required = true, target = "bearer")),
            officialSource = "https://example.com/docs",
        )
        val s = store(catalog(preset))
        s.connectPreset("dead", emptyMap(), mapOf("token" to "first"))
        s.connectPreset("dead", emptyMap(), mapOf("token" to "second"))
        // 重试复用同一 config id,不产生重复条目;秘密覆盖到同一 id。
        val saved = s.servers.value.single()
        assertEquals("dead", saved.presetId)
        assertEquals("second", s.secret(saved.id, "token"))
    }

    @Test fun connectPresetIgnoresUndeclaredSecrets() = runBlocking {
        val preset = McpPreset(
            id = "dead", name = "Dead", summary = McpCopy(mapOf("en" to "Dead")),
            category = "data", transport = "streamable-http", auth = "fields",
            endpoint = "https://127.0.0.1:1/mcp",
            fields = listOf(McpPresetField("token", McpCopy(mapOf("en" to "Token")), secret = true, required = true, target = "bearer")),
            officialSource = "https://example.com/docs",
        )
        val s = store(catalog(preset))
        s.connectPreset("dead", emptyMap(), mapOf("token" to "real", "nope" to "x", "" to ""))
        val saved = s.servers.value.single()
        assertEquals("real", s.secret(saved.id, "token"))
        assertNull(s.secret(saved.id, "nope"))
    }

    @Test fun existingPresetConfigKeepsConnectionSnapshotAfterCatalogUpdate() {
        val v1 = McpPreset(
            id = "demo", name = "Demo", summary = McpCopy(mapOf("en" to "Demo")),
            category = "data", transport = "streamable-http", auth = "none",
            endpoint = "https://v1.example.com/mcp", officialSource = "https://example.com/docs",
        )
        val s1 = store(catalog(v1))
        val config = s1.presetConfig("demo", emptyMap()).getOrThrow()
        assertEquals("https://v1.example.com/mcp", config.url)
        s1.add(config).getOrThrow()

        val v2 = v1.copy(endpoint = "https://v2.example.com/mcp")
        val s2 = store(catalog(v2))
        val loaded = s2.servers.value.single()
        assertEquals("https://v1.example.com/mcp", loaded.url)
        assertEquals("https://v1.example.com/mcp", loaded.presetSnapshot?.endpoint)

        val fresh = s2.presetConfig("demo", emptyMap()).getOrThrow()
        assertEquals("https://v2.example.com/mcp", fresh.url)
    }
}
