package com.wangzan101.mika.chat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 聊天页上滑离开末尾时在输入栏上方浮出「回到底部」玻璃圆按钮；
 * 点击后平滑滚回末尾（含最后一条超过一屏的情况）并隐藏按钮；右下角有小窗时避开它。
 */
@RunWith(AndroidJUnit4::class)
class ChatScrollToBottomTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-scroll-test-" + UUID.randomUUID()))
    private val recommendations = RecommendedTaskStore(File(context.cacheDir, "recommendations-" + UUID.randomUUID())).also {
        it.ensureLoaded { emptyList() }
    }

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

    private fun vm(
        taskState: MutableStateFlow<TaskState> = MutableStateFlow(TaskState.Idle),
        screenOpen: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        taskState,
        MutableStateFlow(Takeover.Stage.None),
        actions,
        store,
        screenOpen = screenOpen,
        io = Dispatchers.Main.immediate,
    )

    private fun show(vm: ChatViewModel, reduceMotion: Boolean = false) {
        compose.setContent {
            MikaTheme {
                ChatScreen(
                    vm,
                    Prerequisites.Check(emptyList()),
                    MutableStateFlow(null),
                    MutableStateFlow(false),
                    reduceMotion = reduceMotion,
                    onOpenDrawer = {},
                    openSettings = {},
                    recommendations = recommendations,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun waitButtonVisible() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("scroll-to-bottom").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitButtonGone() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("scroll-to-bottom").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun shortConversationDoesNotShowScrollToBottomButton() {
        store.append("short", Turn("你好", "你好，有什么可以帮你的？", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("short") }
        show(vm)

        compose.onNodeWithText("你好，有什么可以帮你的？").assertIsDisplayed()
        compose.onNodeWithTag("scroll-to-bottom").assertDoesNotExist()
    }

    @Test
    fun scrollingUpShowsButtonAndTappingReturnsToLatestMessage() {
        for (i in 1..20) {
            store.append("long", Turn("第 $i 轮提问", "这是第 $i 轮的回答内容，用于撑起足够长的对话历史列表。", "succeeded"))
        }
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("long") }
        show(vm)

        compose.onNodeWithText("第 20 轮的回答内容", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("scroll-to-bottom").assertDoesNotExist()

        repeat(3) {
            compose.onNodeWithTag("chat-list").performTouchInput { swipeDown() }
        }
        waitButtonVisible()
        compose.onNodeWithContentDescription("回到底部").assertIsDisplayed().performClick()

        waitButtonGone()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("第 20 轮的回答内容", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("第 20 轮的回答内容", substring = true).assertIsDisplayed()
    }

    @Test
    fun veryLongFinalAnswerScrollsAllTheWayToItsEnd() {
        for (i in 1..5) {
            store.append("tall", Turn("前置问题 $i", "前置回答 $i", "succeeded"))
        }
        val lines = (1..45).joinToString("\n\n") { "第 $it 段长正文：补充足够多行文字让最后一条回答超过整屏高度。" }
        val finalAnswer = "$lines\n\n最后一行结尾标记-TAIL_END"
        store.append("tall", Turn("展开长回答", finalAnswer, "succeeded"))

        val vm = vm()
        compose.runOnUiThread { vm.openConversation("tall") }
        show(vm)

        repeat(4) {
            compose.onNodeWithTag("chat-list").performTouchInput { swipeDown() }
        }
        waitButtonVisible()
        compose.onNodeWithTag("scroll-to-bottom").performClick()

        waitButtonGone()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("TAIL_END", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("TAIL_END", substring = true).assertIsDisplayed()
    }

    @Test
    fun activePipShiftsButtonAwayFromBottomEndCorner() {
        for (i in 1..15) {
            store.append("pip", Turn("历史步骤 $i", "历史结果 $i", "succeeded"))
        }
        val taskState = MutableStateFlow<TaskState>(TaskState.Idle)
        val screenOpen = MutableStateFlow(false)
        val vm = vm(taskState, screenOpen)
        compose.runOnUiThread {
            vm.openConversation("pip")
            vm.send("正在执行的任务", Prerequisites.Check(emptyList()))
        }
        taskState.value = TaskState.Running(step = 1, note = "正在操作手机")
        screenOpen.value = true
        show(vm, reduceMotion = true)

        repeat(3) {
            compose.onNodeWithTag("chat-list").performTouchInput { swipeDown() }
        }
        waitButtonVisible()

        val buttonBounds = compose.onNodeWithTag("scroll-to-bottom").getBoundsInRoot()
        val pipNodes = compose.onAllNodesWithTag("pip-window").fetchSemanticsNodes()
        assertTrue("expected pip-window to be displayed", pipNodes.isNotEmpty())
        val pipBounds = compose.onNodeWithTag("pip-window").getBoundsInRoot()
        assertTrue(
            "expected scroll-to-bottom ($buttonBounds) to stay left of bottom-end pip ($pipBounds)",
            buttonBounds.right <= pipBounds.left || buttonBounds.bottom <= pipBounds.top,
        )
    }
}
