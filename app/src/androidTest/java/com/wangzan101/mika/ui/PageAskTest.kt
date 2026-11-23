package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.browser.PageAskMode
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.capsule.CapsuleModel
import com.wangzan101.mika.capsule.CapsuleRun
import com.wangzan101.mika.capsule.ChatThread
import com.wangzan101.mika.capsule.ThreadItem
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.browser.ui.PageAskBar
import com.wangzan101.mika.browser.ui.PageAskCopy
import com.wangzan101.mika.browser.ui.PageAskOverlay
import com.wangzan101.mika.browser.ui.PageChat
import com.wangzan101.mika.chat.ui.ConversationRowContent
import com.wangzan101.mika.chat.ui.DrawerConversation

/**
 * 浏览器页上「问 Mika」那一块的几种样子:平时的输入条 → 干活中的半屏面板(光边、步骤、停止)→ 你在页面上动手后收成小卡片、
 * 顶上一条「继续」;Mika 问你时面板自己打开。不跑任务,直接给状态。
 */
@RunWith(AndroidJUnit4::class)
class PageAskTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var chat: PageChat
    private val model = CapsuleModel()

    private fun card(state: TaskState) = model.cardRunOf(CapsuleRun("run-1", "conv-1", state, goal = "挑一个评价最好的", onScreen = true))
    private val thread = ChatThread(listOf(ThreadItem.User("u0", "挑一个评价最好的"), ThreadItem.Answer("a0", "好的，我先看一下这一页。")))

    private fun start() {
        compose.setContent {
            MikaTheme {
                val scope = rememberCoroutineScope()
                chat = remember { PageChat(ctx, UserBrowser.of(ctx), scope) }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { PageAskOverlay(chat, site = "shop.example.com", pageTitle = "降噪耳机搜索结果") }
                    if (chat.mode == PageAskMode.Bar) PageAskBar(chat)
                }
            }
        }
    }

    @Test
    fun barThenPanelThenPausedCard() {
        start()
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.HINT).assertIsDisplayed()

        // 用户问了:面板打开,Mika 在干活,四周亮光边,输入框右边是停止键
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Running(2, "", reason = "正在点「加入购物车」")), thread)
            chat.open = true
        }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertExists()
        compose.onNodeWithText(PageAskCopy.FROM_PAGE, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("page-panel-open-chat").assertIsDisplayed()
        compose.onNodeWithText("好的，我先看一下这一页。").assertIsDisplayed()
        compose.onNodeWithTag("capsule-stop").assertIsDisplayed()

        // 你在页面上动手:收成小卡片,顶上一条「继续」,不再亮光边
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.onNodeWithTag("page-paused-banner").assertIsDisplayed()
        compose.onNodeWithTag("page-card-resume").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertDoesNotExist()
        compose.onNodeWithTag("page-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PageAskMode.Card, chat.mode) }

        // 点卡片展开回面板
        compose.onNodeWithTag("page-card").performClick()
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-panel-collapse").performClick()
        compose.onNodeWithTag("page-card").assertIsDisplayed()
    }

    @Test
    fun questionOpensThePanelAndEndedRunReturnsToTheBar() {
        start()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Running(1, "")), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.WaitingInput("要哪个颜色？", listOf("午夜黑", "云白"))), thread) }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithText("午夜黑").assertIsDisplayed()
        // 结束了、收起来:回到输入条
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Succeeded("加好了")), thread)
            chat.open = false
        }
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
    }

    /** 侧栏里从一页开始的对话:标题后面「网页」小标签,下面一行是网站;别的对话没有。 */
    @Test
    fun drawerRowShowsTheWebTagAndSite() {
        compose.setContent {
            MikaTheme {
                Column {
                    ConversationRowContent(DrawerConversation("a", "挑一个降噪耳机", "", false, false, true, site = "shop.example.com"))
                    ConversationRowContent(DrawerConversation("b", "明早的闹钟", "", false, false, true))
                }
            }
        }
        compose.onNodeWithTag("conversation-web").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.WEB).assertIsDisplayed()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("明早的闹钟").assertIsDisplayed()
        compose.onAllNodesWithTag("conversation-web").assertCountEquals(1)
    }
}
