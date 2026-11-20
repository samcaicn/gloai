package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskGoalProgressTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cardKeepsActionAndExpandsTheLastConfirmedGoals() {
        val items = listOf("确认地址", "核实店名", "给出推荐")
        fun running(ids: Set<Int>, stale: Boolean, action: String): Exchange {
            val progress = RunProgress.Task("", ids.size, items.size, null, GoalChecklist(items, ids, stale))
            return ChatViewModel.reduce(Exchange("找咖啡馆", emptyList(), ExchangeStatus.Working, true, "coffee"),
                TaskState.Running(4, action, "phone_observe", progress, narration = action))
        }
        val ex = mutableStateOf(running(setOf(1), false, "打开店铺页"))
        compose.setContent { MikaTheme { WorkBlock(ex.value, emptyList(), true, false) {} } }
        compose.onNodeWithText("打开店铺页").assertIsDisplayed()
        compose.onNodeWithText("已确认目标 1/3").performClick()
        compose.onNodeWithText("✓  确认地址").assertIsDisplayed()
        compose.onNodeWithText("○  核实店名").assertIsDisplayed()
        compose.runOnIdle { ex.value = running(setOf(1, 2), true, "整理五篇笔记") }
        compose.onNodeWithText("整理五篇笔记").assertIsDisplayed()
        compose.onNodeWithText("上次确认 2/3").assertIsDisplayed()
        compose.onNodeWithText("✓  核实店名").assertIsDisplayed()
        compose.onNodeWithText("○  给出推荐").assertIsDisplayed()
        compose.onNodeWithText("上次确认 2/3").performClick()
        compose.onNodeWithText("○  给出推荐").assertDoesNotExist()
    }
}
