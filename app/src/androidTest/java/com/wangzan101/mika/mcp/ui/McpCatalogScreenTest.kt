package com.wangzan101.mika.mcp.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCopy
import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.core.cap.mcp.McpPresetField
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.McpServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** 目录浏览与引导式连接页面的 UI 冒烟:条目渲染、搜索过滤、自定义入口、预设页只呈现声明字段。 */
@RunWith(AndroidJUnit4::class)
class McpCatalogScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun preset(
        id: String,
        name: String,
        category: String,
        transport: String = "streamable-http",
        auth: String = "none",
        fields: List<McpPresetField> = emptyList(),
        packageSpec: McpPackageSpec? = null,
    ) = McpPreset(
        id = id, name = name, summary = McpCopy(mapOf("en" to "$name summary")),
        category = category, transport = transport, auth = auth,
        endpoint = if (transport == "stdio") "" else "https://example.com/mcp",
        command = if (transport == "stdio") listOf("mcp-$id") else emptyList(),
        fields = fields, packageSpec = packageSpec, officialSource = "https://example.com/docs",
    )

    private fun catalog() = McpCatalog(
        1,
        listOf(
            preset("acme", "Acme", "llm"),
            preset("northwind", "Northwind", "database"),
            preset(
                "fs", "Filesystem", "files", transport = "stdio",
                packageSpec = McpPackageSpec("npm", "server-filesystem", "1.0.0", "https://registry.npmjs.org/package/server-filesystem"),
            ),
        ),
    )

    @Test fun catalogShowsPresetsAndCustomEntryAndOpensPreset() {
        val opened = mutableListOf<String>()
        compose.setContent { McpCatalogScreen(catalog(), onOpenPreset = { opened += it }, onCustomServer = {}, onBack = {}) }
        compose.onNodeWithText("Acme").assertIsDisplayed()
        compose.onNodeWithText("Northwind").assertIsDisplayed()
        compose.onNodeWithTag("mcp-catalog-custom").assertIsDisplayed()
        compose.onNodeWithTag("mcp-preset-acme").performClick()
        assertEquals(listOf("acme"), opened)
    }

    @Test fun catalogSearchFiltersByName() {
        compose.setContent { McpCatalogScreen(catalog(), onOpenPreset = {}, onCustomServer = {}, onBack = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("Acme")
        compose.onNodeWithText("Acme").assertIsDisplayed()
        compose.onAllNodesWithText("Northwind").assertCountEquals(0)
    }

    @Test fun connectFormShowsDeclaredFieldsWithoutUrlOrTransport() {
        val fieldsPreset = preset(
            "acme", "Acme", "data", transport = "streamable-http", auth = "fields",
            fields = listOf(
                McpPresetField("tenant", McpCopy(mapOf("en" to "Tenant")), secret = false, required = true, target = "url"),
                McpPresetField("token", McpCopy(mapOf("en" to "Token")), secret = true, required = true, target = "bearer"),
            ),
        )
        val cat = McpCatalog(1, listOf(fieldsPreset))
        val store = McpServerStore(tmp.root, TrivialCipher(), OkHttpClient(), catalog = cat)
        val connector = McpConnectorState(store, cat, CoroutineScope(Dispatchers.Unconfined))
        connector.openPreset("acme")
        compose.setContent { McpConnectScreen(connector, onDone = {}, onBack = {}) }
        compose.onNodeWithText("Tenant").assertIsDisplayed()
        compose.onNodeWithText("Token").assertIsDisplayed()
        // 预设页不展示端点/transport,也不自行拼 URL。
        compose.onAllNodesWithText("https://example.com/mcp").assertCountEquals(0)
        compose.onNodeWithTag("mcp-connect-action").assertIsDisplayed()
    }

    private class TrivialCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
}
