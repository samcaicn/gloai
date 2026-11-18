package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
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
class SavedTasksDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "saved-tasks-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val taskFile = File(directory, "tasks.json")
    private val tasks = RecommendedTaskStore(taskFile).also { it.ensureLoaded { emptyList() } }
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
                reduceMotion = true, onOpenDrawer = {}, openSettings = {}, recommendations = tasks)
        }
        compose.waitForIdle()
        return vm
    }

    /** 推荐任务页现在只经设置页进入;管理类用例直接组合这个屏,不再走输入栏星标。 */
    private fun showSavedTasks() {
        compose.setContent { RecommendedTasksScreen(tasks, onBack = {}, onPick = {}) }
        compose.waitForIdle()
    }

    @Test fun saveHistoryEditAndReuseWithoutSendingOrLosingDraft() {
        val prompt = "打开浏览器，搜索日语五十音学习资料，整理三个适合初学者的练习方法。"
        val original = Turn("> [Quoting your earlier reply]\n> 先学五十音，再练习单词。\n\n$prompt", "已整理好学习资料。", "succeeded")
        conversations.append("history", original)
        val historyBefore = conversations.turns("history")
        show()
        compose.onNodeWithText("已整理好学习资料。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-save-task").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(prompt).performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-save-task").performClick()
        compose.onNodeWithTag("saved-task-prompt").assert(hasText(prompt))
        compose.onNodeWithTag("saved-task-title").performTextReplacement("日语入门资料")
        snapshot("save")
        compose.onNodeWithTag("recommendations-save").performClick()
        compose.waitUntil(5_000) { tasks.tasks.value.size == 1 }
        assertEquals(historyBefore, conversations.turns("history"))

        showSavedTasks()
        snapshot("list")
        compose.onNodeWithContentDescription("编辑常用任务").performClick()
        val edited = "$prompt\n附上来源链接。"
        compose.onNodeWithTag("saved-task-prompt").performTextReplacement(edited)
        compose.onNodeWithTag("recommendations-save").performClick()
        compose.waitUntil(5_000) { tasks.tasks.value.single().prompt == edited }
        val reopened = RecommendedTaskStore(taskFile).also { it.ensureLoaded { emptyList() } }
        assertEquals(tasks.tasks.value, reopened.tasks.value)
        assertTrue(started.isEmpty())
    }

    @Test fun manuallyCreateValidateCancelAndDelete() {
        showSavedTasks()
        compose.onNodeWithText("添加常用任务").performClick()
        compose.onNodeWithTag("recommendations-save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("请填写标题").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("请填写标题").assertExists()
        compose.onNodeWithTag("saved-task-title").performTextReplacement("整理下载文件")
        compose.onNodeWithTag("saved-task-prompt").performTextReplacement("查看下载目录，按文件类型整理一份清单。")
        compose.onNodeWithTag("recommendations-save").performClick()
        compose.waitUntil(5_000) { tasks.tasks.value.size == 1 }
        compose.onNodeWithContentDescription("编辑常用任务").performClick()
        compose.onNodeWithTag("saved-task-title").performTextReplacement("取消的修改")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("整理下载文件").assertExists()
        compose.onNodeWithContentDescription("删除").performClick()
        compose.onNodeWithTag("recommendations-delete").performClick()
        compose.waitUntil(5_000) { tasks.tasks.value.isEmpty() }
        compose.onNodeWithText("还没有常用任务").assertExists()
        val reopened = RecommendedTaskStore(taskFile).also { it.ensureLoaded { listOf("不要恢复" to "默认内容") } }
        assertTrue(reopened.tasks.value.isEmpty())
        assertTrue(started.isEmpty())
    }


    @OptIn(ExperimentalTestApi::class)
    @Test fun insertFieldsAndReuseForPurchasingLearningAndRepairs() {
        showSavedTasks()
        compose.onNodeWithText("添加常用任务").performClick()
        compose.onNodeWithTag("saved-task-title").performTextReplacement("整理沟通需求")
        compose.onNodeWithTag("saved-task-prompt").performTextReplacement("查看测试联系人")
        compose.onNodeWithTag("saved-task-prompt").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 7))
        for ((name, suffix) in listOf("联系人" to "在", "时间范围" to "的消息，整理", "关注内容" to "。")) {
            compose.onNodeWithTag("task-field-add").performScrollTo().performClick()
            compose.onNodeWithTag("task-field-insert").assertIsNotEnabled()
            compose.onNodeWithTag("task-field-name").performTextReplacement(name)
            compose.onNodeWithTag("task-field-insert").performClick()
            compose.onNodeWithTag("saved-task-prompt").performTextInput(suffix)
        }
        val template = "查看{{联系人}}在{{时间范围}}的消息，整理{{关注内容}}。"
        compose.onNodeWithTag("saved-task-prompt").assert(hasText(template))
        snapshot("template-editor")
        compose.onNodeWithTag("recommendations-save").performClick()
        compose.waitUntil(5_000) { tasks.tasks.value.size == 1 }
        assertTrue(tasks.tasks.value.single().fillIn)
        show()
        compose.onNodeWithText("整理沟通需求").performClick()
        compose.onNodeWithTag("task-fill-apply").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals("")

        var previous = ""
        val scenarios = listOf(
            listOf("测试采购", "近三天", "尺寸、数量和交期"),
            listOf("日语学习群", "今天", "五十音记忆方法"),
            listOf("维修师傅", "本周", "故障现象、地址和上门时间"),
        )
        for ((index, values) in scenarios.withIndex()) {
            compose.onNodeWithText("整理沟通需求").performClick()
            compose.onNodeWithTag("task-fill-0").assert(hasText(""))
            compose.onNodeWithTag("task-fill-apply").assertIsNotEnabled()
            values.forEachIndexed { i, value -> compose.onNodeWithTag("task-fill-$i").performTextReplacement(value) }
            if (index == 0) snapshot("template-form")
            compose.onNodeWithTag("task-fill-apply").performClick()
            if (previous.isNotEmpty()) {
                compose.onNodeWithTag("composer-field").assertTextEquals(previous)
                compose.onNodeWithTag("confirm-replace-draft").performClick()
            }
            previous = "查看${values[0]}在${values[1]}的消息，整理${values[2]}。"
            compose.onNodeWithTag("composer-field").assertTextEquals(previous)
            if (index == 0) snapshot("template-filled")
        }
        assertEquals(template, tasks.tasks.value.single().prompt)
        val reopened = RecommendedTaskStore(taskFile).also { it.ensureLoaded { emptyList() } }
        assertEquals(tasks.tasks.value, reopened.tasks.value)
        assertFalse(taskFile.readText().contains("测试采购"))
        assertTrue(started.isEmpty())
    }

    @Test fun homeTemplatesProtectDraftAndLegacyBracesStayLiteral() {
        tasks.add("普通文字", "原样保留{{内容}}")
        tasks.add("学习方法", "搜索{{主题}}，整理{{主题}}的练习方法。", fillIn = true)
        show()
        compose.onNodeWithText("普通文字").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals("原样保留{{内容}}")
        compose.onNodeWithTag("task-fill-apply").assertDoesNotExist()
        compose.onNodeWithText("学习方法").performClick()
        compose.onNodeWithTag("task-fill-1").assertDoesNotExist()
        compose.onNodeWithTag("task-fill-0").performTextReplacement("日语五十音")
        compose.onNodeWithTag("task-fill-apply").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals("原样保留{{内容}}")
        compose.onNodeWithText("学习方法").performClick()
        compose.onNodeWithTag("task-fill-0").performTextReplacement("日语五十音")
        compose.onNodeWithTag("task-fill-apply").performClick()
        compose.onNodeWithTag("confirm-replace-draft").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals("搜索日语五十音，整理日语五十音的练习方法。")
        assertTrue(started.isEmpty())
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        // Window-manager dialog animations are outside Compose idling. Capture the settled window.
        android.os.SystemClock.sleep(800)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "saved-task-$name-${bitmap.width}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
