package com.wangzan101.mika.files.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.TaskActions

/** 文件库多选:勾两行、底部计数、全选/取消、加入待发栏。复制用假实现,不碰真实工作区。 */
@RunWith(AndroidJUnit4::class)
class FileLibrarySelectTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "file-library-select-" + UUID.randomUUID()))
    private val added = mutableListOf<String>()
    private val batches = mutableListOf<Pair<String, List<LibraryFileHandle>>>()
    private val shares = mutableListOf<List<LibraryFileHandle>>()
    private var shareOutcome: LibraryShareOutcome? = null

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun vm() = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        sandboxReady = MutableStateFlow(true), io = Dispatchers.Main.immediate,
    )

    private fun seed() {
        store.append("a", Turn(
            AttachmentLines.compose("look", listOf("/workspace/a.txt" to "1 B", "/workspace/b.txt" to "2 B")),
            "", "succeeded",
        ))
        store.setTitle("a", "A conversation")
    }

    private fun show(fromDrawer: Boolean) {
        val model = vm()
        compose.setContent {
            FileLibraryScreen(
                model,
                initialConversation = if (fromDrawer) null else "a",
                fromDrawer = fromDrawer,
                onBack = {},
                onAdded = { id -> added += id; true },
                conversationStore = store,
                addBatch = { _, _, target, sources, progress ->
                    progress(sources.size, sources.size)
                    batches += target to sources
                    LibraryBatchOutcome(sources.size, 0, 0, emptyList())
                },
                shareBatch = { _, sources, progress ->
                    progress(sources.size, sources.size)
                    shares += sources
                    shareOutcome ?: LibraryShareOutcome(sources.size, emptyList())
                },
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("file-library-row").fetchSemanticsNodes().size == 2
        }
    }

    @Test fun selectingTwoFilesSendsOneBatchToTheChosenConversation() {
        seed()
        show(fromDrawer = true)
        compose.onNodeWithTag("file-library-select").performClick()
        compose.onAllNodesWithTag("file-library-row")[0].performClick()
        compose.onAllNodesWithTag("file-library-row")[1].performClick()
        compose.onNodeWithTag("file-library-selected-count").assertTextEquals("已选 2 个")
        compose.onNodeWithTag("file-library-add-selected").assertIsEnabled().performClick()
        compose.onNode(hasText("A conversation") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(listOf("a"), added)
        assertEquals(1, batches.size)
        assertEquals("a", batches.single().first)
        assertEquals(2, batches.single().second.size)
    }

    @Test fun selectAllTogglesVisibleRowsAndCancelLeavesSelection() {
        seed()
        show(fromDrawer = true)
        compose.onNodeWithTag("file-library-select").performClick()
        compose.onNodeWithTag("file-library-selected-count").assertTextEquals("选择文件")
        compose.onNodeWithTag("file-library-add-selected").assertIsNotEnabled()
        compose.onNodeWithTag("file-library-select-all").performClick()
        compose.onNodeWithTag("file-library-selected-count").assertTextEquals("已选 2 个")
        compose.onNodeWithTag("file-library-select-all").performClick()
        compose.onNodeWithTag("file-library-selected-count").assertTextEquals("选择文件")
        compose.onNodeWithTag("file-library-select-cancel").performClick()
        compose.onNodeWithTag("file-library-select-bar").assertDoesNotExist()
        compose.onNodeWithTag("file-library-select").assertExists()
    }

    @Test fun typeFilterLimitsVisibleRowsAndSelectAll() {
        store.append("a", Turn(
            AttachmentLines.compose("look", listOf("/workspace/photo.JPG" to "1 B", "/workspace/report.pdf" to "2 B")),
            "", "succeeded",
        ))
        show(fromDrawer = false)
        compose.onNodeWithTag("file-library-type-filter").performClick()
        compose.onNodeWithText("图片").performClick()
        compose.onNodeWithText("photo.JPG").assertIsDisplayed()
        compose.onNodeWithText("report.pdf").assertDoesNotExist()

        compose.onNodeWithTag("file-library-select").performClick()
        compose.onNodeWithTag("file-library-select-all").performClick()
        compose.onNodeWithTag("file-library-selected-count").assertTextEquals("已选 1 个")
        compose.onNodeWithTag("file-library-share-selected").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LibraryFileHandle.Workspace("a", "/workspace/photo.JPG")), shares.single())
    }

    @Test fun addingFromConversationUsesInitialConversationWithoutPicker() {
        seed()
        show(fromDrawer = false)
        compose.onNodeWithTag("file-library-select").performClick()
        compose.onAllNodesWithTag("file-library-row")[0].performClick()
        compose.onNodeWithTag("file-library-add-selected").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a"), added)
        assertEquals(1, batches.size)
        assertEquals("a", batches.single().first)
    }

    @Test fun sharingSelectedFilesSendsOneBatchAndKeepsSelection() {
        seed()
        show(fromDrawer = true)
        compose.onNodeWithTag("file-library-select").performClick()
        compose.onAllNodesWithTag("file-library-row")[0].performClick()
        compose.onAllNodesWithTag("file-library-row")[1].performClick()
        compose.onNodeWithTag("file-library-share-selected").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, shares.size)
        assertEquals(2, shares.single().size)
        assertEquals(0, added.size)
        compose.onNodeWithTag("file-library-selected-count").assertTextEquals("已选 2 个")
    }

    @Test fun sharePartialFailureShowsSummaryAndSkippedNames() {
        seed()
        shareOutcome = LibraryShareOutcome(1, listOf("b.txt"))
        show(fromDrawer = true)
        compose.onNodeWithTag("file-library-select").performClick()
        compose.onAllNodesWithTag("file-library-row")[0].performClick()
        compose.onAllNodesWithTag("file-library-row")[1].performClick()
        compose.onNodeWithTag("file-library-share-selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已分享 1 个 · 未能分享 1 个").assertExists()
        compose.onNodeWithText("未能分享：b.txt").assertExists()
    }
}
