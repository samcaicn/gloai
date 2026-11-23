package com.wangzan101.mika.task.ui

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.run.record.PlaybackMark
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.record.RunStore
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import com.wangzan101.mika.settings.ui.models.use

/** 使用独立测试记录，不操作真实设备或调用模型。 */
@RunWith(AndroidJUnit4::class)
class TaskReportScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private val dirs = mutableListOf<java.io.File>()
    @After fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private fun vm(): TaskReportViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.io.File(context.cacheDir, "report-test-${java.util.UUID.randomUUID()}")
        dirs += dir
        val store = RunStore(dir, { null }, 30, System::currentTimeMillis) { it }
        val h = store.open("报告界面测试")
        val bitmap = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        h.event(RunEvent.TaskStarted(1, "报告界面测试", "test"))
        (1..4).forEach { h.media(it, bytes) }
        h.event(RunEvent.TaskPlayback(2, 1, "phone_tap", "a", 1, 2, PlaybackMark("tap", 500, 500)))
        h.event(RunEvent.TaskPlayback(3, 2, "phone_swipe", "b", 3, 4, PlaybackMark("swipe", 500, 800, 500, 200)))
        h.event(RunEvent.TaskEnded(4, "succeeded", "## 查询结果\n完整的测试报告。"))
        h.close()
        return TaskReportViewModel(store, h.runId)
    }

    @Test fun entriesOpenReportAndEvidenceOpensReplayWithMarkers() {
        val vm = vm()
        compose.setContent {
            var page by remember { mutableStateOf("chat") }
            var screenshot by remember { mutableStateOf<Int?>(null) }
            when (page) {
                "chat" -> TaskReportEntries({ page = "report" }, { page = "replay" })
                "report" -> TaskReportScreen(vm, { page = "chat" }, { screenshot = it; page = "replay" }, {})
                else -> TaskPlaybackScreen(vm, screenshot, { page = "report" })
            }
        }
        compose.onNodeWithText("任务报告").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("完整的测试报告。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("操作回放").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-mark-tap").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一步").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-frame-2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playback-mark-tap").assertDoesNotExist()
        compose.onAllNodesWithText("操作结果").assertCountEquals(2)
        compose.onNodeWithText("下一步").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-mark-swipe").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("playback-mark-swipe").assertDoesNotExist()
        compose.onNodeWithText("操作前").assertDoesNotExist()
        compose.onNodeWithText("操作后").assertDoesNotExist()
        val image = compose.onNodeWithTag("playback-image").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("playback-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(image.top >= viewport.top && image.bottom <= viewport.bottom + 1)
        assertTrue(image.left >= viewport.left && image.right <= viewport.right + 1)
        compose.onNodeWithText("播放回放").assertIsDisplayed()
        compose.onAllNodesWithText("结束时页面").assertCountEquals(2)
        compose.onNodeWithText("步骤明细").assertDoesNotExist()
        compose.onNodeWithText("1×").performClick()
        compose.onNodeWithText("2×").assertExists()
    }
    @Test fun exportMenuCopiesAndSharesTheReportWithoutSavingAFile() {
        val vm = vm()
        compose.setContent { TaskReportScreen(vm, {}, {}, {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("完整的测试报告。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("导出").performClick()
        compose.onNodeWithText("复制为 Markdown").assertExists()
        compose.onNodeWithText("分享报告").performClick()
        compose.onNodeWithText("纯文本").assertExists()
        compose.onNodeWithText("TXT 文件").assertExists()
        compose.onNodeWithText("Markdown 文件").assertExists()
        compose.onNodeWithText("Word 文件").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("导出").performClick()
        compose.onNodeWithText("复制为纯文本").performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains("完整的测试报告。") == true }
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("报告界面测试"))
        assertTrue(copied.contains("完整的测试报告。"))
        assertFalse(copied.contains("**"))
    }

    @Test fun keyScreenshotOpensItsResultInsteadOfThePrecedingGesture() {
        val vm = vm()
        compose.setContent {
            var replay by remember { mutableStateOf(false) }
            var screenshot by remember { mutableStateOf<Int?>(null) }
            if (replay) TaskPlaybackScreen(vm, screenshot, {})
            else TaskReportScreen(vm, {}, { screenshot = it; replay = true }, {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("完整的测试报告。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("结束时页面").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-frame-4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playback-mark-swipe").assertDoesNotExist()
        compose.onNodeWithText("上一步").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-frame-3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playback-mark-swipe").assertExists()
    }

    @Test fun imageSwipeNavigatesAndPausesWithoutOpeningFullscreen() {
        val vm = vm()
        compose.setContent { TaskPlaybackScreen(vm, null, {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-frame-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playback-viewport").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-frame-2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playback-fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("playback-viewport").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playback-frame-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("播放回放").performClick()
        compose.onNodeWithTag("playback-viewport").performTouchInput { swipeUp() }
        compose.onNodeWithText("播放回放").assertExists()
        compose.onNodeWithTag("playback-viewport").performClick()
        compose.onNodeWithTag("playback-fullscreen").assertExists()
        compose.onNodeWithTag("playback-fullscreen").performTouchInput { swipeLeft() }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("playback-fullscreen").assertDoesNotExist()
    }

}
