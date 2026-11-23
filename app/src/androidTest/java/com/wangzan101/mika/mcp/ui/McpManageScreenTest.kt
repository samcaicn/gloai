package com.wangzan101.mika.mcp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** 服务管理与自定义页面的 UI 冒烟:页面渲染出关键动作,不依赖真实网络。 */
@RunWith(AndroidJUnit4::class)
class McpManageScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun manageScreenRendersStatusToolsAndDelete() {
        val store = McpServerStore(tmp.root, TrivialCipher(), OkHttpClient())
        store.add(McpServerConfig(name = "My Server", url = "https://example.com/mcp"))
        val config = store.servers.value.first()
        val connector = McpConnectorState(store, McpCatalog.Empty, CoroutineScope(Dispatchers.Unconfined))
        compose.setContent { McpServerManageScreen(store, connector, config, onEdit = {}, onDeleted = {}, onBack = {}) }
        compose.onNodeWithText("My Server").assertIsDisplayed()
        compose.onNodeWithText("View tools").assertIsDisplayed()
        compose.onNodeWithText("Test now").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test fun customScreenRendersTitleAndSave() {
        val store = McpServerStore(tmp.root, TrivialCipher(), OkHttpClient())
        compose.setContent { McpCustomServerScreen(store, initial = null, onSaved = {}, onBack = {}) }
        compose.onNodeWithText("Custom server").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    private class TrivialCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
}
