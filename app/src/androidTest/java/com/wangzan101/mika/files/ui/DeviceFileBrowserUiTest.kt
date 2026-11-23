package com.wangzan101.mika.files.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.workspace.DeviceFileEntry
import com.wangzan101.mika.core.workspace.DeviceFileImport
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.DeviceFileSearchResult
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.task.DeviceImportRecord
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFileBrowserUiTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private class FakeDeviceFiles : DeviceFilesPort {
        override suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage = when (path) {
            null -> DeviceFilePage(listOf(DeviceFileEntry("Download", "/sdcard/Download", isDirectory = true)))
            "/sdcard/Download" -> DeviceFilePage(
                listOf(
                    DeviceFileEntry("Work", "/sdcard/Download/Work", isDirectory = true),
                    DeviceFileEntry("notes.txt", "/sdcard/Download/notes.txt", isDirectory = false, size = 128),
                ),
            )
            "/sdcard/Download/Work" -> DeviceFilePage(
                listOf(
                    DeviceFileEntry("VeryDeepFolderName", "/sdcard/Download/Work/VeryDeepFolderName", isDirectory = true),
                ),
            )
            "/sdcard/Download/Work/VeryDeepFolderName" -> DeviceFilePage(
                listOf(
                    DeviceFileEntry("report.pdf", "/sdcard/Download/Work/VeryDeepFolderName/report.pdf", isDirectory = false, size = 2048),
                ),
            )
            else -> DeviceFilePage(emptyList())
        }

        override suspend fun importFile(path: String, workspace: String): DeviceFileImport = DeviceFileImport("/workspace/inbox/a", 1)

        override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult = DeviceFileSearchResult(
            entries = listOf(
                DeviceFileEntry("VeryDeepFolderName", "/sdcard/Download/Work/VeryDeepFolderName", isDirectory = true),
                DeviceFileEntry("report.pdf", "/sdcard/Download/Work/VeryDeepFolderName/report.pdf", isDirectory = false, size = 2048),
            ),
            visited = 4,
            truncated = true,
        )
    }

    @Test
    fun breadcrumbsNavigationSelectionSearchAndHistoryWork() {
        val added = mutableListOf<List<String>>()
        var closed = false
        val records = listOf(
            DeviceImportRecord(
                id = "r1",
                conversationId = "c1",
                sourcePath = "/sdcard/Download/old.png",
                workspacePath = "/workspace/inbox/old.png",
                size = 512,
                importedAt = 1_700_000_000_000L,
                sourceModifiedSeconds = null,
                sourceModifiedNanos = null,
            ),
        )
        compose.setContent {
            MikaTheme {
                DeviceFileBrowserDialog(
                    files = FakeDeviceFiles(),
                    loadRootDescriptions = { mapOf("/sdcard/Download" to "下载目录") },
                    records = records,
                    onAddSelected = { added += it },
                    onClose = { closed = true },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("下载目录").assertIsDisplayed()
        compose.onNodeWithTag("device-browser-add").assertIsNotEnabled()

        // Drill down 3 levels and verify the current directory name is visible in breadcrumbs
        compose.onNodeWithText("Download").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("notes.txt").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("VeryDeepFolderName").performClick()
        compose.waitForIdle()

        compose.onNode(hasText("VeryDeepFolderName") and hasAnyAncestor(hasTestTag("device-browser-breadcrumbs")))
            .assertIsDisplayed()
        compose.onNodeWithText("report.pdf").performClick()
        compose.onNodeWithTag("device-browser-add").assertIsEnabled()
        compose.onNodeWithText("添加已选文件（2）").assertIsDisplayed()

        // Jump back via breadcrumb to Download and test search + truncation banner
        compose.onNode(hasText("Download") and hasAnyAncestor(hasTestTag("device-browser-breadcrumbs")))
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("device-browser-search").performTextInput("report")
        compose.onNodeWithTag("device-browser-search-submit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Work/VeryDeepFolderName").assertIsDisplayed()
        compose.onNodeWithText("VeryDeepFolderName").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Work") and hasAnyAncestor(hasTestTag("device-browser-breadcrumbs")))
            .assertIsDisplayed()
        compose.onNode(hasText("VeryDeepFolderName") and hasAnyAncestor(hasTestTag("device-browser-breadcrumbs")))
            .assertIsDisplayed()

        // Switch to history and back
        compose.onNodeWithTag("device-browser-history-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("old.png").assertIsDisplayed()
        compose.onNodeWithTag("device-browser-history-toggle").performClick()
        compose.waitForIdle()

        // Confirm selection
        compose.onNodeWithTag("device-browser-add").performClick()
        assertEquals(
            listOf(listOf("/sdcard/Download/notes.txt", "/sdcard/Download/Work/VeryDeepFolderName/report.pdf")),
            added,
        )

        // Cancel button triggers close
        compose.onNodeWithTag("device-browser-cancel").performClick()
        assertTrue(closed)
    }
}
