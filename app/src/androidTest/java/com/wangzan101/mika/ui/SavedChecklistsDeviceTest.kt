package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.*
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import javax.crypto.KeyGenerator
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SavedChecklistsDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "saved-checklists-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val taskFile = File(directory, "tasks.json")
    private val tasks = RecommendedTaskStore(taskFile).also { it.ensureLoaded { emptyList() } }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private var failWrite = false
    private val listFile = File(directory, "lists.bin")
    private val lists = SavedChecklistStore(listFile, object : KeyCipher {
        override fun encrypt(plain: ByteArray) = if (failWrite) error("unavailable") else cipher.encrypt(plain)
        override fun decrypt(data: ByteArray) = cipher.decrypt(data)
    })
    private val conversations = ConversationStore(File(directory, "chats"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = mutableListOf<String>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { started += goal }
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

    @After fun cleanUp() { scope.cancel(); directory.deleteRecursively() }

    private fun show(): ChatViewModel {
        val vm = ChatViewModel(scope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None),
            actions, conversations, parallelFlag = { false }, sandboxReady = MutableStateFlow(false),
            io = Dispatchers.Main.immediate)
        compose.runOnUiThread { vm.openConversation("history") }
        compose.setContent {
            ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow<Bitmap?>(null), MutableStateFlow(false),
                reduceMotion = true, onOpenDrawer = {}, openSettings = {}, recommendations = tasks, checklists = lists)
        }
        compose.waitForIdle()
        return vm
    }

    /** 清单管理页现在只经设置页进入;管理类用例直接组合这个屏,不再走输入栏星标。 */
    private fun showChecklists() {
        compose.setContent { SavedChecklistsScreen(lists, onBack = {}) }
        compose.waitForIdle()
    }


    @Test fun saveSupplementUpdateCompareAndReuseLatestReply() {
        val first = "已确定：数量20件。\n\n待补充：交期。"
        val updated = "已确定：数量30件，周五交付。\n\n下一步：确认收货地址。"
        conversations.append("history", Turn("整理采购需求", first, "succeeded"))
        conversations.append("history", Turn("数量改为30件，周五交付", updated, "succeeded"))
        show()
        compose.onNodeWithText("已确定：数量20件。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-checklist").performClick()
        compose.onNodeWithText("新建清单").performClick()
        compose.onNodeWithTag("checklist-title").performTextReplacement("采购需求")
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(5_000) { lists.items.value.size == 1 }
        val id = lists.items.value.single().id
        assertEquals("history", lists.items.value.single().current.source!!.conversationId)
        compose.onNodeWithTag("checklist-update").performClick()
        compose.onNodeWithTag("checklist-action-apply").assertIsNotEnabled()
        compose.onNodeWithTag("checklist-action-input").performTextReplacement("数量改为30件，周五交付")
        snapshot("supplement")
        compose.onNodeWithTag("checklist-action-apply").performClick()
        val updateRequest = compose.onNodeWithTag("composer-field").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertTrue(updateRequest.contains("数量20件"))
        assertTrue(updateRequest.contains("数量改为30件，周五交付"))
        assertTrue(started.isEmpty())

        // A completed update reply is a fixture: no model or external app is called by this UI test.
        compose.onNodeWithText("已确定：数量30件，周五交付。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-checklist").performClick()
        compose.onNodeWithText("采购需求").performClick()
        compose.onNodeWithTag("checklist-input").assert(hasText("数量30件", substring = true))
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(5_000) { lists.items.value.single().current.version == 2 }
        snapshot("latest")
        compose.onNodeWithText("变化与历史").performScrollTo().performClick()
        compose.onNodeWithText("第 2 版").performClick()
        compose.onNodeWithText("新增或改写").assertExists()
        compose.onNodeWithText("移除或替换").assertExists()
        snapshot("changes")
        compose.onNodeWithText("保存时的来源回复").performScrollTo().performClick()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("复制").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(lists.items.value.single().current.body, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("checklist-reuse").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-action-input").performTextReplacement("拟一条确认消息")
        compose.onNodeWithTag("checklist-action-apply").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals(updateRequest)
        compose.onNodeWithText("已确定：数量30件，周五交付。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-checklist").performClick()
        compose.onNodeWithText("采购需求").performClick()
        compose.onNodeWithTag("checklist-reuse").performClick()
        compose.onNodeWithTag("checklist-action-input").performTextReplacement("拟一条确认消息")
        compose.onNodeWithTag("checklist-action-apply").performClick()
        compose.onNodeWithTag("checklist-replace-draft").performClick()
        val next = compose.onNodeWithTag("composer-field").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertTrue(next.contains("拟一条确认消息") && next.contains("数量30件"))
        assertFalse(next.contains("数量20件"))
        val reopened = SavedChecklistStore(listFile, cipher).also { assertTrue(it.load()) }
        assertEquals(lists.items.value, reopened.items.value)
        assertEquals(id, reopened.items.value.single().id)
        assertEquals(2, reopened.items.value.single().revisions.size)
        assertTrue(started.isEmpty())
    }

    @Test fun failedSavePreservesEditsAndHistoryThenDeletionPersists() {
        showChecklists()
        compose.onNodeWithText("新建清单").performClick()
        compose.onNodeWithTag("checklist-title").performTextReplacement("维修安排")
        compose.onNodeWithTag("checklist-input").performTextReplacement("故障：无法启动\n上门时间：待确认")
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(5_000) { lists.items.value.size == 1 }
        compose.onNodeWithText("编辑清单").performClick()
        val revised = "故障：无法启动\n上门时间：周六上午"
        compose.onNodeWithTag("checklist-input").performTextReplacement(revised)
        failWrite = true
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("没能保存，请再试一次。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("checklist-input").assert(hasText(revised))
        assertEquals(1, lists.items.value.single().current.version)
        failWrite = false
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(5_000) { lists.items.value.single().current.version == 2 }
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-delete").performClick()
        compose.waitUntil(5_000) { lists.items.value.isEmpty() }
        assertTrue(SavedChecklistStore(listFile, cipher).also { assertTrue(it.load()) }.items.value.isEmpty())
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        // Window-manager dialog animations are outside Compose idling. Capture the settled window.
        android.os.SystemClock.sleep(800)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "checklist-$name-${bitmap.width}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
