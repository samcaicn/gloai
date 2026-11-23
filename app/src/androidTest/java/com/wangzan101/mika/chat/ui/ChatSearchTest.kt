package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
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

/**
 * 侧栏搜索:输入标题里的词、正文里的词都能列出对话;清空后回到入口和原来的列表;点结果打开那个对话。
 * 不用真机的任务环境。
 */
@RunWith(AndroidJUnit4::class)
class ChatSearchTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-search-test-" + UUID.randomUUID()))

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
        io = Dispatchers.Main.immediate,
    )

    private fun show(vm: ChatViewModel) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list() }, conversationChanges = store.changes, conversationTitle = store::title,
                search = store.search,
                onSettings = {}, reopen = true,
            ) { open ->
                ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false), reduceMotion = true,
                    onOpenDrawer = open, openSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun awaitRow(title: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("conversation-row") and hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun searchesTitleAndBodyClearsAndOpensTheResult() {
        store.append("a", Turn("订票", "验证码一直过不去", "failed"))
        store.append("b", Turn("查天气", "明天晴", "succeeded"))
        val vm = vm()
        show(vm)

        // 标题命中
        compose.onNodeWithTag("drawer-search").performClick().performTextInput("订票")
        awaitRow("订票")
        compose.onNodeWithText("搜索结果").assertExists()

        // 正文命中:标题是「订票」,搜的是回答里的词
        compose.onNodeWithTag("drawer-search").performTextReplacement("验证码")
        awaitRow("订票")
        compose.onNode(hasTestTag("conversation-row") and hasText("订票"))
            .assert(hasText("验证码", substring = true))

        // 什么都没有
        compose.onNodeWithTag("drawer-search").performTextReplacement("没有这个词")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("没有找到「没有这个词」").fetchSemanticsNodes().isNotEmpty()
        }

        // 清空:入口回来,搜索框的清除按钮消失
        compose.onNodeWithTag("drawer-search-clear").performClick()
        compose.onNodeWithTag("drawer-search-clear").assertDoesNotExist()
        compose.onNodeWithContentDescription("新对话").assertExists()

        // 再搜另一个对话,点结果打开它
        compose.onNodeWithTag("drawer-search").performTextReplacement("天气")
        awaitRow("查天气")
        compose.onNode(hasTestTag("conversation-row") and hasText("查天气")).performClick()
        compose.waitUntil(5_000) { vm.ui.value.conversationId == "b" }
    }
}
