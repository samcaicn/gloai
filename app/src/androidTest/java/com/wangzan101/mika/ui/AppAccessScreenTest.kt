package com.wangzan101.mika.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.settings.ui.AppAccessScreen

@RunWith(AndroidJUnit4::class)
class AppAccessScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun appAccessLoadsWithoutModelAndRecommendedCanBeCancelled() {
        compose.setContent { com.wangzan101.mika.ui.theme.MikaTheme { AppAccessScreen(onBack = {}, classifyApps = false) } }
        compose.onNodeWithText("应用访问").assertIsDisplayed()
        compose.onNodeWithText("推荐禁用", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("推荐禁用金融交易应用").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("全部允许").performScrollTo().assertIsDisplayed()
    }
}
