package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 侧栏长按一条对话在原地弹出菜单(置顶、改名、删除,点外面收起):删除先确认、删掉、列表当场更新;改名后列表换成新名字;置顶后排到最上面带图钉。
 * 还有对话页里长按回答、用户的话弹出复制 / 引用 / 选择文字:选择文字后能选中,引用随下一句话发出去。不用真机的任务环境。
 */
@RunWith(AndroidJUnit4::class)
class ChatDeleteAndSelectTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-delete-test-" + UUID.randomUUID()))
    private val recommendations = RecommendedTaskStore(File(context.cacheDir, "recommendations-" + UUID.randomUUID())).also {
        it.ensureLoaded { emptyList() }
    }
    private val removed = mutableListOf<String>()
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

    private fun vm(impact: DeleteImpact = DeleteImpact(runningTask = false, schedules = 0), parallel: Boolean = false, attachments: Boolean = false) = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        deletionImpact = { impact },
        removeConversation = { removed += it; store.delete(it) },
        branchTitle = { it + " · 分支" },
        parallelFlag = { parallel }, sandboxReady = MutableStateFlow(attachments),
        io = Dispatchers.Main.immediate,
    )

    private fun show(vm: ChatViewModel, drawerOpen: Boolean) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list() }, conversationChanges = store.changes, conversationTitle = store::title,
                onSettings = {}, reopen = drawerOpen,
            ) { open ->
                ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false), reduceMotion = true,
                    onOpenDrawer = open, openSettings = {}, recommendations = recommendations)
            }
        }
        compose.waitForIdle()
    }

    @Test fun typingNewDraftKeepsFocusAndBothDraftsCanBeReopenedFromTheDrawer() {
        val vm = vm()
        show(vm, drawerOpen = false)
        compose.onNodeWithTag("composer-field").performClick().performTextInput("第一份草稿")
        compose.onNodeWithTag("composer-field").assertIsFocused()
        compose.onNode(hasContentDescription("新对话") and hasAnyAncestor(hasTestTag("drawer")).not()).performClick()
        compose.onNodeWithTag("composer-field").assert(hasText(""))
        compose.onNodeWithTag("composer-field").performTextReplacement("第二份草稿")
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasTestTag("conversation-row") and hasText("第一份草稿")).performClick()
        compose.onNodeWithTag("composer-field").assertTextContains("第一份草稿")
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasTestTag("conversation-row") and hasText("第二份草稿")).performClick()
        compose.onNodeWithTag("composer-field").assertTextContains("第二份草稿")
        assertTrue(started.isEmpty())
    }

    @Test fun parallelConversationSwitchRestoresTextAttachmentAndQuoteTogether() {
        store.append("a", Turn("天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val vm = vm(parallel = true, attachments = true)
        compose.runOnUiThread { vm.openConversation("a") }
        show(vm, drawerOpen = false)
        compose.onNodeWithTag("composer-field").performTextReplacement("下周的安排")
        compose.runOnUiThread {
            vm.quote(QuoteMessage.From.Mika, "晴")
            vm.attach(listOf("content://test/plan.pdf"))
        }
        compose.waitForIdle()
        compose.onNodeWithText("plan.pdf").assertExists()
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("drawer"))).performScrollToNode(hasText("订票"))
        compose.onNode(hasTestTag("conversation-row") and hasText("订票")).performClick()
        compose.onNodeWithTag("composer-field").assert(hasText(""))
        compose.onNodeWithText("plan.pdf").assertDoesNotExist()
        compose.onNodeWithTag("composer-field").performTextReplacement("周末的车票")
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasTestTag("conversation-row") and hasText("天气")).performClick()
        compose.onNodeWithTag("composer-field").assertTextContains("下周的安排")
        compose.onNodeWithText("plan.pdf").assertExists()
        compose.onNodeWithContentDescription("去掉引用").assertExists()
        compose.onNodeWithContentDescription(AttachCopy.ADD).assertExists()
        assertTrue(started.isEmpty())
    }

    @Test fun savedStateRestoresConversationTextsIntoARecreatedModel() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var current: ChatViewModel? = null
        restoration.setContent {
            val model = androidx.compose.runtime.remember { vm() }
            current = model
            ChatScreen(model, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false),
                reduceMotion = true, onOpenDrawer = {}, openSettings = {}, recommendations = recommendations)
        }
        compose.runOnIdle { current!!.openConversation("a") }
        compose.onNodeWithTag("composer-field").performTextReplacement("A preserved")
        compose.runOnIdle { current!!.openConversation("b") }
        compose.onNodeWithTag("composer-field").performTextReplacement("B preserved")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("composer-field").assertTextContains("B preserved")
        compose.runOnIdle { current!!.openConversation("a") }
        compose.onNodeWithTag("composer-field").assertTextContains("A preserved")
    }

    @Test fun longPressARowAsksThenDeletes() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        store.append("b", Turn("订票", "好了", "succeeded"))
        val vm = vm(DeleteImpact(runningTask = true, schedules = 2))
        show(vm, drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithText("删除这个对话？").assertExists()
        val body = compose.onNodeWithTag("delete-conversation-body").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(body, "天气" in body && "停止" in body && "2" in body && "保留" in body)

        // 取消:什么都不删
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertTrue(removed.isEmpty())

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithTag("confirm-delete-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { removed.isNotEmpty() }
        assertEquals(listOf("a"), removed)
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        compose.onNodeWithText("订票").assertExists()
    }

    /** 多选批量删除:顶部「多选」进入,勾选 / 全选,底部写着已选几条;确认弹窗写明删几个、停几个任务;删完列表当场只剩没选的。 */
    @Test fun selectModeDeletesSeveralChatsAtOnce() {
        listOf("a" to "天气", "b" to "订票", "c" to "周报").forEach { (id, title) ->
            store.append(id, Turn("说说 $title", "好", "succeeded"))
            store.setTitle(id, title)
        }
        val vm = vm(DeleteImpact(runningTask = true, schedules = 2))
        show(vm, drawerOpen = true)

        // 上游已将多选入口移到会话的长按菜单,并默认选中该会话。
        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-select").performClick()
        compose.onNodeWithText("已选 1 条").assertExists()
        compose.onNode(hasTestTag("conversation-row") and hasText("天气")).performClick()
        compose.waitForIdle()
        // 每行左边出现勾选框;点一行是勾选,不再是打开对话
        compose.onAllNodesWithTag("conversation-check", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("已选 0 条").assertExists()
        compose.onNodeWithTag("drawer-delete-selected").assertIsNotEnabled()

        // 全选 / 取消全选
        compose.onNodeWithTag("drawer-select-all").performClick()
        compose.onNodeWithText("已选 3 条").assertExists()
        compose.onNodeWithTag("drawer-select-all").performClick()
        compose.onNodeWithText("已选 0 条").assertExists()

        // 勾两条
        compose.onNode(hasTestTag("conversation-row") and hasText("天气")).performClick()
        compose.onNode(hasTestTag("conversation-row") and hasText("周报")).performClick()
        compose.onNodeWithText("已选 2 条").assertExists()

        compose.onNodeWithTag("drawer-delete-selected").performClick()
        compose.onNodeWithText("删除选中的对话？").assertExists()
        val body = compose.onNodeWithTag("delete-many-conversation-body").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(body, "2" in body && "停止" in body && "4" in body && "保留" in body)

        // 取消:什么都不删,还在多选里
        compose.onNodeWithTag("dismiss-delete-many-conversation").performClick()
        compose.waitForIdle()
        assertTrue(removed.isEmpty())
        compose.onNodeWithText("已选 2 条").assertExists()

        compose.onNodeWithTag("drawer-delete-selected").performClick()
        compose.onNodeWithTag("confirm-delete-many-conversation").performClick()
        compose.waitUntil(5_000) { removed.size == 2 }
        assertEquals(setOf("a", "c"), removed.toSet())
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("周报").fetchSemanticsNodes().size)
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("drawer"))).performScrollToNode(hasText("订票"))
        compose.onNodeWithText("订票").assertExists()
        // 删完自动退出多选
        compose.onNodeWithTag("drawer-select-bar").assertDoesNotExist()
        compose.onNodeWithTag("drawer-select-cancel").assertDoesNotExist()
        compose.onNode(hasTestTag("conversation-row") and hasText("订票")).performClick()
        compose.waitUntil(5_000) { vm.ui.value.conversationId == "b" }
    }

    /** 长按在原地弹出菜单:抬起的那一行(还是那个标题)和三项置顶、改名、删除;只是打开菜单什么都不改。 */
    @Test fun longPressShowsTheThreeActions() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu").assertExists()
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("置顶"))
        compose.onNodeWithTag("drawer-menu-rename").assert(hasText("改名"))
        compose.onNodeWithTag("drawer-menu-delete").assert(hasText("删除"))
        compose.onNodeWithText("取消置顶").assertDoesNotExist()
        // 菜单开着时后面的内容不给无障碍读,只剩抬起的那一行
        assertEquals(1, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals("天气", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** 点菜单外面收起菜单,什么都不改;侧栏里那一行还在。 */
    @Test fun tappingOutsideClosesTheMenu() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assertExists()
        // 右下角:菜单靠左接在那一行下面,碰不到
        compose.onNodeWithTag("drawer-menu").performTouchInput { click(percentOffset(0.95f, 0.95f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer-menu-pin").assertDoesNotExist()
        compose.onNodeWithText("天气").assertExists()
        assertTrue(removed.isEmpty())
        assertEquals("天气", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** 改名:输入框里是现在的标题,换成新的保存;列表当场换名字,存下的也是新的。 */
    @Test fun renameUpdatesTheRow() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-rename").performClick()
        compose.onNodeWithTag("rename-conversation-field").assert(hasText("天气"))
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("   ")
        compose.onNodeWithTag("confirm-rename-conversation").assertIsNotEnabled()
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("上海出行")
        compose.onNodeWithTag("confirm-rename-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("上海出行").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals("上海出行", store.title("a"))
        // 之后才到的起好的标题不盖掉
        assertTrue(!store.setTitle("a", "天气"))
    }

    /** 置顶:排到「置顶」一段最上面、带图钉;再取消置顶回到「最近」。 */
    @Test fun pinMovesTheRowToTheTop() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        store.append("b", Turn("订票", "好了", "succeeded"))
        show(vm(), drawerOpen = true)
        compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).assertCountEquals(0)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("置顶")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("置顶").assertExists()
        compose.onAllNodesWithTag("conversation-row")[0].assert(hasText("天气"))
        assertTrue(store.pinnedAt("a") != null)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("取消置顶")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("置顶").assertDoesNotExist()
        assertEquals(null, store.pinnedAt("a"))
    }

    // 长按消息弹出菜单;选「选择文字」后那条换回系统的长按选中,侧栏不跟着打开
    @Test fun answerAndUserTextAreSelectableFromTheMenu() {
        store.append("s", Turn("今天天气怎么样", "上海今天**晴**,详见 [天气](https://example.com)", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNode(hasText("上海今天", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer"))).assertDoesNotExist()
        compose.onNodeWithText("上海今天", substring = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").assert(hasText("选择文字")).performClick()
        compose.waitForIdle()
        val answer = compose.onNode(hasText("上海今天", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer")))
        answer.assertExists()
        answer.performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer").assertIsNotDisplayed()

        compose.onNodeWithText("今天天气怎么样").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("今天天气怎么样") and hasAnyAncestor(hasTestTag("selectable-user"))).assertExists()
    }

    // 长按回答选「引用」:输入框上方出现引用小条,写一句发出去,引用跟着这句话交给任务,小条消失;气泡上方显示引的那条
    @Test fun longPressQuoteThenSend() {
        store.append("q", Turn("今天天气怎么样", "上海今天**晴**", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("q") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("上海今天晴").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-copy").assert(hasText("复制"))
        compose.onNodeWithTag("message-menu-quote").assert(hasText("引用")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("quote-chip").assertExists()

        compose.onNodeWithTag("composer-field").performTextReplacement("那明天呢")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals(listOf("> [Quoting your earlier reply]\n> 上海今天晴\n\n那明天呢"), started)
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
        compose.onNodeWithTag("sent-quote").assertExists()

        // × 去掉引用
        compose.runOnUiThread { vm.quote(QuoteMessage.From.You, "今天天气怎么样") }
        compose.onNodeWithContentDescription("去掉引用").performClick()
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
    }

    // 长按一条答完的回答「从这里分支」:复制到这一轮(含),切到新对话,标题带后缀,原对话不变
    @Test fun branchFromAnAnswerOpensTheCopiedChat() {
        store.append("s", Turn("今天天气怎么样", "上海今天晴", "succeeded"))
        store.append("s", Turn("那明天呢", "明天多云", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("明天多云").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-branch").assert(hasText("从这里分支")).performClick()
        compose.waitUntil(10_000) { vm.ui.value.conversationId != "s" }
        val branch = vm.ui.value.conversationId
        assertNotNull(branch)
        assertEquals(listOf("今天天气怎么样", "那明天呢"), store.turns(branch!!).map { it.userText })
        assertEquals("今天天气怎么样 · 分支", store.title(branch))
        assertEquals(store.branchOrigin(branch)?.parentId, "s")
        assertEquals(listOf("今天天气怎么样", "那明天呢"), vm.ui.value.exchanges.map { it.goal })
        // 原对话还是两轮
        assertEquals(2, store.turns("s").size)
    }

    // 长按自己说过的话「从这里重新问」:复制这一轮之前,输入框预填原话,不自动发送
    @Test fun retryFromAUserMessagePrefillsTheNewChat() {
        store.append("s", Turn("今天天气怎么样", "上海今天晴", "succeeded"))
        store.append("s", Turn("那明天呢", "明天多云", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("那明天呢").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-branch-retry").assert(hasText("从这里重新问")).performClick()
        compose.waitUntil(10_000) { vm.ui.value.conversationId != "s" }
        val branch = vm.ui.value.conversationId
        assertNotNull(branch)
        assertEquals(listOf("今天天气怎么样"), store.turns(branch!!).map { it.userText })
        assertEquals(false, store.branchOrigin(branch)?.afterTurn)
        compose.onNodeWithTag("composer-field").assertTextContains("那明天呢")
    }
}
