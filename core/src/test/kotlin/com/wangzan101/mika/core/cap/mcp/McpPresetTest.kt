package com.wangzan101.mika.core.cap.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPresetTest {
    @Test
    fun parsesLocalizedCatalogAndResolvesOnlyDeclaredFields() {
        val catalog = McpCatalogParser.parse(catalog(validHttpPreset, validStdioPreset))

        assertEquals(1, catalog.formatVersion)
        assertEquals(listOf("http", "stdio"), catalog.presets.map { it.id })
        assertEquals("查询团队数据", catalog.presets[0].summary.resolve("zh-CN"))
        assertEquals("Query team data", catalog.presets[0].summary.resolve("en"))

        val http = McpPresetResolver().resolve(
            catalog.presets[0],
            mapOf(
                "tenant" to "north / blue",
                "token" to "bearer-secret",
                "apiKey" to "header-secret",
                "region" to "cn",
            ),
        )
        assertEquals("https://api.example.com/north%20%2F%20blue/mcp", http.url)
        assertEquals(
            mapOf(
                "X-Client" to "mika",
                "Authorization" to "Bearer bearer-secret",
                "X-Api-Key" to "header-secret",
                "X-Region" to "cn",
            ),
            http.headers,
        )
        assertEquals(emptyMap<String, String>(), http.environment)
        assertEquals("fields", http.auth)

        val stdio = McpPresetResolver().resolve(
            catalog.presets[1],
            mapOf(
                "workspace" to "demo space",
                "accessToken" to "env-secret-one",
                "signingKey" to "env-secret-two",
            ),
        )
        assertEquals("", stdio.url)
        assertEquals(listOf("--workspace", "demo space", "--readonly"), stdio.argv)
        assertEquals(
            mapOf("ACCESS_TOKEN" to "env-secret-one", "SIGNING_KEY" to "env-secret-two"),
            stdio.environment,
        )
        assertEquals(emptyMap<String, String>(), stdio.headers)
        assertEquals("1.4.2", catalog.presets[1].packageSpec?.version)
    }

    @Test
    fun rejectsSecretExfiltrationAndUnsupportedTargets() {
        val invalid = linkedMapOf(
            "duplicate preset id" to catalog(validHttpPreset, validStdioPreset.replace("\"id\": \"stdio\"", "\"id\": \"http\"")),
            "duplicate field id" to catalog(validHttpPreset.replace("\"id\": \"apiKey\"", "\"id\": \"token\"")),
            "secret in URL" to catalog(
                validHttpPreset
                    .replace("{{tenant}}/mcp", "{{token}}/mcp")
                    .replace("\"id\": \"token\", \"label\": { \"en\": \"Token\" }, \"secret\": true, \"required\": true, \"target\": \"bearer\"", "\"id\": \"token\", \"label\": { \"en\": \"Token\" }, \"secret\": true, \"required\": true, \"target\": \"url\""),
            ),
            "secret in argv" to catalog(
                validStdioPreset
                    .replace("\"{{workspace}}\"", "\"{{accessToken}}\"")
                    .replace("\"id\": \"accessToken\", \"label\": { \"en\": \"Access token\" }, \"secret\": true, \"required\": true, \"target\": \"env\", \"name\": \"ACCESS_TOKEN\"", "\"id\": \"accessToken\", \"label\": { \"en\": \"Access token\" }, \"secret\": true, \"required\": true, \"target\": \"argv\""),
            ),
            "unknown transport" to catalog(validHttpPreset.replace("streamable-http", "websocket")),
            "unknown auth" to catalog(validHttpPreset.replace("\"auth\": \"fields\"", "\"auth\": \"password\"")),
            "unknown target" to catalog(validHttpPreset.replace("\"target\": \"bearer\"", "\"target\": \"cookie\"")),
            "blank official source" to catalog(validHttpPreset.replace("https://example.com/docs/mcp", "")),
            "unpinned package" to catalog(validStdioPreset.replace("\"version\": \"1.4.2\"", "\"version\": \"^1.4.2\"")),
            "reserved header overwrite" to catalog(validHttpPreset.replace("\"name\": \"X-Region\"", "\"name\": \"Authorization\"")),
            "OAuth with required secret" to catalog(validHttpPreset.replace("\"auth\": \"fields\"", "\"auth\": \"oauth\"")),
        )
        invalid.forEach { (name, text) ->
            assertThrows("Expected rejection: $name", IllegalArgumentException::class.java) {
                McpCatalogParser.parse(text)
            }
        }
    }

    @Test
    fun rejectsMalformedCatalogWithoutPartialSuccess() {
        assertThrows(IllegalArgumentException::class.java) { McpCatalogParser.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            McpCatalogParser.parse(catalog(validHttpPreset).replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpCatalogParser.parse("{ \"formatVersion\": 1, \"presets\": [ {} ] }")
        }
    }

    @Test
    fun copyResolvesExactLanguageThenBaseThenDefault() {
        val copy = McpCopy(mapOf("en" to "Default", "zh" to "中文", "zh-CN" to "简体中文"))

        assertEquals("简体中文", copy.resolve("zh-CN"))
        assertEquals("中文", copy.resolve("zh-TW"))
        assertEquals("Default", copy.resolve("fr-FR"))
    }

    @Test
    fun resolverRejectsMissingUnknownAndHeaderInjection() {
        val preset = McpCatalogParser.parse(catalog(validHttpPreset)).presets.single()
        val resolver = McpPresetResolver()
        val valid = mapOf("tenant" to "north", "token" to "secret", "apiKey" to "key", "region" to "cn")

        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(preset, valid - "token") }
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(preset, valid + ("undeclared" to "value")) }
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(preset, valid + ("apiKey" to "bad\r\nInjected: yes")) }
        val resolved = resolver.resolve(preset, valid)
        assertTrue(resolved.headers.none { it.key.contains("Injected") })
    }

    private fun catalog(vararg presets: String): String = presets.joinToString(
        separator = ",",
        prefix = "{ \"formatVersion\": 1, \"presets\": [",
        postfix = "] }",
    )

    private val validHttpPreset = """
        {
          "id": "http", "name": "Acme Data",
          "summary": { "en": "Query team data", "zh-CN": "查询团队数据" },
          "category": "data", "transport": "streamable-http", "auth": "fields",
          "endpoint": "https://api.example.com/{{tenant}}/mcp",
          "headers": { "X-Client": "mika" },
          "fields": [
            { "id": "tenant", "label": { "en": "Tenant" }, "secret": false, "required": true, "target": "url" },
            { "id": "token", "label": { "en": "Token" }, "secret": true, "required": true, "target": "bearer" },
            { "id": "apiKey", "label": { "en": "API key" }, "secret": true, "required": true, "target": "header", "name": "X-Api-Key" },
            { "id": "region", "label": { "en": "Region" }, "secret": false, "required": true, "target": "header", "name": "X-Region" }
          ],
          "officialSource": "https://example.com/docs/mcp"
        }
    """.trimIndent()

    private val validStdioPreset = """
        {
          "id": "stdio", "name": "Local Files",
          "summary": { "en": "Read approved files", "zh-CN": "读取授权文件" },
          "category": "local", "transport": "stdio", "auth": "fields",
          "command": ["--workspace", "{{workspace}}", "--readonly"],
          "fields": [
            { "id": "workspace", "label": { "en": "Workspace" }, "secret": false, "required": true, "target": "argv" },
            { "id": "accessToken", "label": { "en": "Access token" }, "secret": true, "required": true, "target": "env", "name": "ACCESS_TOKEN" },
            { "id": "signingKey", "label": { "en": "Signing key" }, "secret": true, "required": true, "target": "env", "name": "SIGNING_KEY" }
          ],
          "packageSpec": { "ecosystem": "npm", "name": "@example/files-mcp", "version": "1.4.2", "source": "https://www.npmjs.com/package/@example/files-mcp" },
          "officialSource": "https://example.com/docs/local-mcp"
        }
    """.trimIndent()
}
