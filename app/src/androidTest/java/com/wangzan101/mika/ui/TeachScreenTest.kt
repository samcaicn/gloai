package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.teach.TeachNotice
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeachScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun view(
        folded: List<String> = listOf("切到「桌面」", "点了「小红书」"),
        basic: Boolean = false,
    ) = TeachSummaryView(
        display = "小红书搜装修并收藏",
        description = "当你说「帮我找装修案例并收藏第一条」时用",
        body = """
            ## 入口
            直接启动小红书。
            ## 步骤
            1. 启动小红书
            ## 容易出错的地方
            收藏在笔记右下角。
            ## 怎样算办成(核实证据)
            底部出现已收藏。
            ## 什么时候停下交给用户
            进入登录页时停。
        """.trimIndent(),
        folded = folded,
        basic = basic,
    )

    @Test fun summaryShowsTheSkillSectionsAndFoldedNote() {
        compose.setContent { MikaTheme { TeachSummaryPage(view(), {}, {}, {}) } }
        compose.onNodeWithText("入口").assertIsDisplayed()
        compose.onNodeWithText("怎样算办成", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.teach_folded_n, 2)).assertIsDisplayed()
        compose.onNodeWithTag("teach-accept").assertHasClickAction()
    }

    @Test fun summaryWithoutFoldedStepsHidesTheNote() {
        compose.setContent { MikaTheme { TeachSummaryPage(view(folded = emptyList()), {}, {}, {}) } }
        compose.onNodeWithTag("teach-folded").assertDoesNotExist()
    }

    @Test fun summaryWithoutModelExplainsTheLocalSummary() {
        compose.setContent { MikaTheme { TeachSummaryPage(view(basic = true), {}, {}, {}) } }
        compose.onNodeWithText(str(R.string.teach_basic_note)).assertIsDisplayed()
    }

    @Test fun acceptButtonCallsBack() {
        var accepted = 0
        compose.setContent { MikaTheme { TeachSummaryPage(view(), { accepted++ }, {}, {}) } }
        compose.onNodeWithTag("teach-accept").performClick()
        assertEquals(1, accepted)
    }

    @Test fun introStartCallsBack() {
        var started = 0
        compose.setContent { MikaTheme { TeachIntroPage("", { started++ }) } }
        compose.onNodeWithTag("teach-start").performClick()
        assertEquals(1, started)
    }

    @Test fun donePageOffersChatAndTeachAgain() {
        compose.setContent {
            MikaTheme { TeachDonePage("小红书搜装修并收藏", "帮你在小红书找案例并收藏第一条", {}, {}) }
        }
        compose.onNodeWithTag("teach-done").assertIsDisplayed()
        compose.onNodeWithTag("teach-back-chat").assertHasClickAction()
        compose.onNodeWithTag("teach-again").assertHasClickAction()
    }

    /** 没看到操作的总结页:说清是哪一种失败,并留出「再整理一次」和「放弃」两条路。 */
    @Test fun failedSummaryExplainsItselfAndOffersRetryAndDiscard() {
        var retried = 0
        var discarded = 0
        compose.setContent {
            MikaTheme {
                Summarizing(failed = true, notice = TeachNotice.NoSteps, onRetry = { retried++ }, onDiscard = { discarded++ })
            }
        }
        compose.onNodeWithText(str(R.string.teach_no_steps_again)).assertIsDisplayed()
        compose.onNodeWithTag("teach-retry").assertHasClickAction().performClick()
        compose.onNodeWithTag("teach-discard").assertHasClickAction().performClick()
        assertEquals(1, retried)
        assertEquals(1, discarded)
    }
}
