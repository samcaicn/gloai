package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFollowUpTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun trayShowsMessagesAndGuidesTheSelectedOne() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                FollowUpTray(
                    listOf(QueuedFollowUp("a", "c", "下一件"), QueuedFollowUp("b", "c", "再下一件")),
                    active = true, onGuide = { actions += "guide:$it" },
                    onEdit = {}, onCancel = {}, onStartNow = {},
                )
            }
        }
        compose.onNodeWithText("下一件").assertIsDisplayed()
        compose.onNodeWithText("再下一件").assertIsDisplayed()
        compose.onNodeWithTag("follow-up-tray").assertIsDisplayed()
        compose.onAllNodesWithTag("follow-up-steer")[0].performClick()
        compose.runOnIdle { assertEquals(listOf("guide:a"), actions) }
    }

    @Test fun longPressingAMessageOpensItsActions() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                FollowUpTray(
                    listOf(QueuedFollowUp("a", "c", "下一件")), active = true,
                    onEdit = {}, onCancel = {}, onStartNow = {}, onActions = { actions += "actions:${it.id}" },
                )
            }
        }
        compose.onNodeWithTag("follow-up-bubble").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf("actions:a"), actions) }
    }

    @Test fun idleTrayStartsTheFirstQueuedTask() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                FollowUpTray(
                    listOf(QueuedFollowUp("a", "c", "下一件"), QueuedFollowUp("b", "c", "再下一件")),
                    active = false, onEdit = {}, onCancel = {}, onStartNow = { actions += "start" },
                )
            }
        }
        compose.onNodeWithText("排队中 · 2 件", substring = true).assertIsDisplayed()
        compose.onNodeWithText("现在开始").performClick()
        compose.runOnIdle { assertEquals(listOf("start"), actions) }
    }
}
