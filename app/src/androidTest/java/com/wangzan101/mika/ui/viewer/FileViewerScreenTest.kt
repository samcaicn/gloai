package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileViewerScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(name: String, content: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(content) })

    private fun show(uri: Uri, name: String) {
        val kind = FileViewer.kindOf(context, uri, name)
        compose.setContent { MikaTheme { FileViewerScreen(uri, name, kind, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.loading").fetchSemanticsNodes().isEmpty() }
    }

    private fun openFind(query: String) {
        compose.onNodeWithContentDescription(context.getString(R.string.viewer_find)).performClick()
        compose.onNodeWithTag("viewer-find-input").performTextInput(query)
    }

    private fun waitForCount(count: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("viewer-find-count").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(count).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun markdownFileIsRendered() {
        show(file("notes.md", "# 周报\n\n本周完成了 **三件事**。\n\n- 第一项\n- 第二项\n"), "notes.md")
        compose.onNodeWithTag("viewer.markdown").assertIsDisplayed()
        compose.onNodeWithText("周报").assertIsDisplayed()
        compose.onNodeWithText("第一项").assertIsDisplayed()
        compose.onNodeWithText("notes.md").assertIsDisplayed()
    }

    @Test fun textFileIsShownAsIs() {
        show(file("run.log", "line one\nline two: ok\n"), "run.log")
        compose.onNodeWithTag("viewer.text").assertIsDisplayed()
        compose.onNodeWithText("line two: ok", substring = true).assertIsDisplayed()
    }

    @Test fun unsupportedFileFallsBackToOtherApps() {
        val uri = file("archive.zip", "PK")
        assertNull(FileViewer.kindOf(context, uri, "archive.zip"))
        show(uri, "archive.zip")
        compose.onNodeWithTag("viewer.unsupported").assertIsDisplayed()
        compose.onNodeWithText("用其他应用打开").assertIsDisplayed()
    }

    @Test fun textCanBeSearched() {
        show(file("run.log", "line one: ok\nline two: ok\n"), "run.log")
        openFind("ok")
        waitForCount("1/2")
        compose.onNodeWithTag("viewer-find-next").performClick()
        waitForCount("2/2")
        compose.onNodeWithTag("viewer-find-next").performClick()
        waitForCount("1/2")
    }

    @Test fun markdownCanBeSearched() {
        show(file("readme.md", "# 说明\n\n正文里的关键词\n\n- 关键词二\n"), "readme.md")
        openFind("关键词")
        waitForCount("1/2")
    }

    @Test fun searchSaysWhenNothingMatches() {
        show(file("run.log", "line one\n"), "run.log")
        openFind("找不到的词")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.viewer_find_no_matches)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun copyCopiesTheWholeText() {
        val text = "line one\nline two\n"
        show(file("notes.txt", text), "notes.txt")
        compose.onNodeWithContentDescription(context.getString(R.string.viewer_copy)).performClick()
        // 测试窗口拿到焦点前,系统不让读剪贴板
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
