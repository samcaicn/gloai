package com.wangzan101.mika.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.ui.theme.MikaTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.wangzan101.mika.mcp.ui.McpScreen
import com.wangzan101.mika.settings.ui.models.use

/** Uses the installed app's settings and computer server with locally created run data. */
@RunWith(AndroidJUnit4::class)
class PhoneHistoryDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun phonePermissionControlsSavedHistoryAndTraceThroughTheRunningServer() {
        val app = ApplicationProvider.getApplicationContext<MikaApp>()
        val runtime = app.mcp
        val oldHistory = runtime.settings.historyEnabled.value
        val oldUsb = runtime.usb.enabled.value
        val conversation = app.conversations.newConversation()
        val record = RunStore(app).open("history-device-fixture")
        record.event(RunEvent.TaskStarted(1, "history-device-fixture", "fixture"))
        record.close()
        app.conversations.append(conversation, Turn("history-device-fixture", "Saved answer", "unverified", runId = record.runId))
        val trace = app.detailedTraces.store.start(record.runId)!!
        trace.event(RunEvent.ToolResult(2, 1, "phone_list_apps", "{\"ok\":true}", null, null))
        val client = OkHttpClient()
        try {
            runtime.settings.setHistoryEnabled(false)
            compose.setContent { MikaTheme { McpScreen(runtime) {} } }
            compose.onNodeWithText(app.getString(R.string.mcp_advanced)).performScrollTo().performClick()
            compose.onNodeWithTag("computer-history").performScrollTo().assertIsOff()
            compose.runOnIdle { runtime.setUsbEnabled(true) }
            compose.waitUntil(15_000) { runtime.state.value is McpServerState.Listening }
            val port = (runtime.state.value as McpServerState.Listening).port
            val token = runtime.usb.token()!!
            fun get(path: String, authenticated: Boolean = true): Pair<Int, JSONObject> {
                val request = Request.Builder().url("http://127.0.0.1:$port/api/v1$path")
                if (authenticated) request.header("Authorization", "Bearer $token")
                return client.newCall(request.build()).execute().use { it.code to JSONObject(it.body!!.string()) }
            }
            assertEquals(401, get("/history/conversations", false).first)
            assertEquals(403, get("/history/conversations").first)
            compose.onNodeWithTag("computer-history").performClick().assertIsOn()
            assertTrue(get("").second.getBoolean("history_enabled"))
            val turns = get("/history/conversations/$conversation")
            assertEquals(200, turns.first)
            assertEquals(record.runId, turns.second.getJSONArray("turns").getJSONObject(0).getString("run_id"))
            val path = "/history/conversations/$conversation/runs/${record.runId}"
            val live = get(path).second
            assertTrue(live.getBoolean("recording"))
            assertTrue(live.getBoolean("trace_available"))
            assertTrue(live.getJSONArray("rows").length() > 0)
            app.detailedTraces.store.finish(trace)
            assertFalse(get(path).second.getBoolean("recording"))
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.getExternalFilesDir(null), "history-permission.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            compose.onNodeWithTag("computer-history").performClick().assertIsOff()
            assertEquals(403, get(path).first)
        } finally {
            app.detailedTraces.store.finish(trace)
            compose.runOnIdle {
                runtime.settings.setHistoryEnabled(oldHistory)
                runtime.setUsbEnabled(oldUsb)
            }
            app.conversations.delete(conversation)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
