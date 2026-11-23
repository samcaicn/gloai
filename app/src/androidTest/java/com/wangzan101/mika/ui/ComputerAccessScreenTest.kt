package com.wangzan101.mika.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RelayState
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.mcp.ui.ComputerAccessPage
import com.wangzan101.mika.mcp.ui.ComputerAccessUi

@RunWith(AndroidJUnit4::class)
class ComputerAccessScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private val example = ComputerAccessUi(
        McpServerState.Listening(8765), networkEnabled = true, usbEnabled = false, port = 8765,
        RemoteActivity(), listOf("192.168.1.23", "100.64.0.2"), "example-ui-test-value", "adb shell sh /example/connect-computer.sh",
    )
    private val state = mutableStateOf(example)
    private val networkChanges = mutableListOf<Boolean>()
    private val usbChanges = mutableListOf<Boolean>()
    private val portChanges = mutableListOf<Int>()
    private val copied = mutableListOf<String>()

    private fun page(initial: ComputerAccessUi = example) {
        state.value = initial
        compose.setContent {
            MikaTheme {
                ComputerAccessPage(state.value, {}, usbChanges::add, networkChanges::add, portChanges::add, copied::add, {},
                    deviceSettings = { Text("Device settings slot") }, relayGuide = { mcp -> Text(if (mcp) "Remote MCP guide" else "Remote CLI guide") })
            }
        }
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun networkGuide() {
        compose.onNodeWithTag("computer-mcp").performScrollTo().performClick()
        compose.onNodeWithTag("computer-network").performScrollTo().performClick()
    }

    @Test fun remoteGuideSelectionDoesNotEnableOtherTransports() {
        page(example.copy(networkEnabled = false, server = McpServerState.Off))
        compose.onNodeWithTag("computer-mcp").performScrollTo().performClick()
        compose.onNodeWithTag("computer-remote").performScrollTo().performClick()
        compose.onNodeWithText("Remote MCP guide").assertIsDisplayed()
        assertTrue(networkChanges.isEmpty()); assertTrue(usbChanges.isEmpty())
        compose.onNodeWithTag("computer-cli").performScrollTo().performClick()
        compose.onNodeWithText("Remote CLI guide").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("computer-remote").assertIsDisplayed()
    }

    @Test fun remoteOnlyStartupFailureIsShownUntilTheRelayStarts() {
        page(example.copy(networkEnabled = false, usbEnabled = false,
            server = McpServerState.Failed("test-start-denied"), relayEnabled = true, relayState = RelayState.Off))
        compose.onNodeWithText("没能开启:test-start-denied").assertIsDisplayed()
        compose.onNodeWithText("正在连接远程网关…").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(relayState = RelayState.Online) }
        compose.onNodeWithText("等待电脑连接").assertIsDisplayed()
        compose.onNodeWithText("没能开启:test-start-denied").assertDoesNotExist()
    }

    @Test fun remoteConnectionWaitsForActualRequestAndShowsRevocation() {
        page(example.copy(networkEnabled = false, server = McpServerState.Off, relayEnabled = true, relayState = RelayState.Online))
        compose.onNodeWithText("等待电脑连接").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(activity = RemoteActivity(1, 1_700_000_000_000)) }
        compose.onNodeWithText("已收到电脑请求").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(relayState = RelayState.Unauthorized) }
        compose.onNodeWithText("配对已失效或被撤销，请重新配对网关").assertIsDisplayed()
    }

    @Test fun cliIsDefaultAndSelectionDoesNotChangeAccessPermissions() {
        page(example.copy(networkEnabled = false, server = McpServerState.Off))
        compose.onNodeWithText("1. 准备电脑端工具").assertIsDisplayed()
        compose.onNodeWithTag("mcp-enabled").assertDoesNotExist()
        compose.onNodeWithText("Device settings slot").assertDoesNotExist()
        tap("复制安装指引")
        compose.onAllNodesWithText("复制命令")[0].performScrollTo().performClick()
        compose.onNodeWithText("mika doctor").assertDoesNotExist()
        assertTrue(copied[0].contains("docs/cli.md"))
        assertEquals(listOf("mika connect"), copied.drop(1))
        networkGuide()
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
        tap("开启网络访问")
        assertEquals(listOf(true), networkChanges)
    }

    @Test fun cliSharesNetworkSelectionAndCopiesOriginWithTokenFile() {
        page()
        compose.onNodeWithTag("computer-network").performClick()
        tap("http://100.64.0.2:8765")
        compose.onNodeWithTag("computer-copy-cli-network").performScrollTo().performClick()
        assertEquals("mika connect --url http://100.64.0.2:8765 --token-file /path/to/token", copied.single())
        assertFalse(copied.single().contains(example.token!!))
        assertFalse(copied.single().contains("/mcp"))
        compose.onNodeWithText("mika doctor").assertDoesNotExist()
        compose.onNodeWithTag("computer-mcp").performScrollTo().performClick()
        compose.onNodeWithText("http://100.64.0.2:8765/mcp").assertExists()
        compose.onNodeWithTag("computer-cli").performScrollTo().performClick()
        compose.onNodeWithText("http://100.64.0.2:8765").assertExists()
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
        compose.runOnIdle { state.value = example.copy(token = null) }
        compose.onNodeWithTag("computer-copy-cli-network").assertIsNotEnabled()
    }

    @Test fun advancedSettingsAreHiddenUntilOpenedAndKeepExplicitControls() {
        page()
        tap("高级设置")
        compose.onNodeWithText("Device settings slot").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mcp-usb").performScrollTo().performClick()
        compose.onNodeWithTag("mcp-enabled").performScrollTo().performClick()
        assertEquals(listOf(true), usbChanges)
        assertEquals(listOf(false), networkChanges)
        tap("收起高级设置")
        compose.onNodeWithTag("mcp-enabled").assertDoesNotExist()
        compose.onNodeWithText("Device settings slot").assertDoesNotExist()
    }

    @Test fun listeningWaitsForARequestAndOffOrFailureOverridesOldActivity() {
        page()
        compose.onNodeWithText("等待电脑连接").assertIsDisplayed()
        compose.onNodeWithText("已收到电脑请求").assertDoesNotExist()
        compose.runOnIdle { state.value = example.copy(activity = RemoteActivity(1, 1_700_000_000_000)) }
        compose.onNodeWithText("已收到电脑请求").assertIsDisplayed()
        compose.onNodeWithText("上次请求", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(networkEnabled = false) }
        compose.onNodeWithText("电脑访问未开启").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(networkEnabled = true, server = McpServerState.Failed("port busy")) }
        compose.onNodeWithText("port busy", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已收到电脑请求").assertDoesNotExist()
    }

    @Test fun networkConfigMasksPreviewButCopiesTokenAndSelectedAddress() {
        page()
        networkGuide()
        compose.onNodeWithTag("computer-config-preview").assertDoesNotExist()
        tap("http://100.64.0.2:8765/mcp")
        tap("查看配置")
        compose.onNodeWithText(example.token!!, substring = true).assertDoesNotExist()
        compose.onNodeWithText("<token>", substring = true).assertExists()
        compose.onNodeWithTag("computer-copy-config").performScrollTo().performClick()
        val config = JSONObject(copied.single()).getJSONObject("mcpServers").getJSONObject("mika")
        assertEquals("http://100.64.0.2:8765/mcp", config.getString("url"))
        assertEquals("Bearer ${example.token}", config.getJSONObject("headers").getString("Authorization"))
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
        for (invalid in listOf(example.copy(addresses = emptyList()), example.copy(token = null),
            example.copy(server = McpServerState.Failed("port busy")))) {
            compose.runOnIdle { state.value = invalid }
            compose.onNodeWithTag("computer-copy-config").assertIsNotEnabled()
        }
    }

    @Test fun usbGuideCopiesBootstrapAndForwardWithoutNetworkCredentials() {
        page()
        compose.onNodeWithTag("computer-mcp").performClick()
        compose.onAllNodesWithText("复制命令")[0].performScrollTo().performClick()
        compose.onAllNodesWithText("复制命令")[1].performScrollTo().performClick()
        tap("复制设置说明")
        assertEquals(example.usbCommand, copied[0])
        assertEquals("adb forward tcp:8765 tcp:8765", copied[1])
        assertTrue(copied[2].contains("http://127.0.0.1:8765/mcp"))
        assertFalse(copied.any { it.contains(example.token!!) })
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
    }
}
