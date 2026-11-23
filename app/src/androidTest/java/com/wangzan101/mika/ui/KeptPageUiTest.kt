package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.capsule.CapsuleCard
import com.wangzan101.mika.capsule.CapsuleContent
import com.wangzan101.mika.capsule.CapsuleKind
import com.wangzan101.mika.capsule.CapsuleView
import com.wangzan101.mika.capsule.CardActions
import com.wangzan101.mika.capsule.CardView
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.chat.ui.KeptLine
import com.wangzan101.mika.chat.ui.draft

/** 留在后台运行的那一页在界面上:对话里那一行、胶囊、展开的卡片(实时画面、剩余时间、来源对话、两个按钮)。 */
@RunWith(AndroidJUnit4::class)
class KeptPageUiTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val page = KeptPage(1, "c1", "r1", "把这门课点开,从第 3 节开始播,挂着别关", "course.example.com", null, "第 3 节",
        System.currentTimeMillis(), System.currentTimeMillis() + 2 * 3_600_000L - 30_000)
    private val calls = mutableListOf<String>()
    private val actions = object : CardActions {
        override fun collapse() { calls += "collapse" }
        override fun stop(runKey: String) {}
        override fun takeOver(runKey: String) {}
        override fun resume(runKey: String) {}
        override fun answer(runKey: String, text: String) {}
        override fun approve(runKey: String, requestKey: Int) {}
        override fun decline(runKey: String, requestKey: Int) {}
        override fun openMika(conversationId: String) { calls += "open $conversationId" }
        override fun zoom(on: Boolean) { calls += "zoom $on" }
        override fun openInput() {}
        override fun draft(text: String) {}
        override fun send() {}
        override fun freshChat(fresh: Boolean) {}
        override fun loadEarlier() {}
        override fun captureScreen() {}
        override fun dropScreen() {}
        override fun closeKeptPage() { calls += "closeKept" }
    }

    @Test fun chatRowShowsHostTimeAndCloses() {
        var closed = 0
        compose.setContent { MikaTheme(dark = false, reduceMotion = true) { KeptLine(page) { closed++ } } }
        compose.onNodeWithText("这一页保持在后台运行").assertIsDisplayed()
        compose.onNodeWithText("course.example.com · 2 小时后自动关").assertIsDisplayed()
        compose.onNodeWithTag("kept-close").performClick()
        assertEquals(1, closed)
    }

    @Test fun capsuleSaysBackgroundPageRunning() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(CapsuleView.Shown(CapsuleKind.KeptPage, "后台网页运行中", conversationId = "c1", pill = true), onTap = {}, onStop = {}, onHandBack = {})
            }
        }
        compose.onNodeWithText("后台网页运行中").assertIsDisplayed()
        compose.onNodeWithTag("capsule-live-dot", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun cardShowsLiveViewTimeSourceAndButtons() {
        val frame = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleCard(CardView.Kept(page), frame, zoomed = false, actions = actions, elapsed = { null }, height = 520.dp)
            }
        }
        compose.onNodeWithText("后台网页").assertIsDisplayed()
        compose.onNodeWithText("还剩 2 小时").assertIsDisplayed()
        compose.onNodeWithTag("kept-live").assertIsDisplayed()
        compose.onNodeWithText("course.example.com").assertIsDisplayed()
        compose.onNodeWithText("来自对话「把这门课点开", substring = true).assertIsDisplayed()
        compose.onNodeWithText("实时画面", substring = true).assertIsDisplayed()
        compose.onNodeWithText("打开对话").performClick()
        compose.onNodeWithTag("kept-close").performClick()
        assertEquals(listOf("open c1", "closeKept"), calls)
    }
}
