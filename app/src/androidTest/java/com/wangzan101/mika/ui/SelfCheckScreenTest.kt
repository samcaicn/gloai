package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckResult
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.DeviceFacts
import com.wangzan101.mika.core.selfcheck.SelfCheck
import com.wangzan101.mika.core.selfcheck.SelfCheckState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.selfcheck.ui.SelfCheckContent
import com.wangzan101.mika.selfcheck.ui.SelfCheckCopy

/** 设置 › 恢复与修复 › 兼容性自检:不带真实自检的页面,看各状态下显示什么、按钮交给谁。 */
@RunWith(AndroidJUnit4::class)
class SelfCheckScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val facts = DeviceFacts("Acme", "Phone 7", "14", 34, listOf("arm64-v8a"), 8L shl 30, 1080, 2400, true, "0.1.21", "abc1234")

    private fun show(state: SelfCheckState, busy: Boolean = false) {
        compose.setContent {
            SelfCheckContent(
                state, busy, onStart = { calls += "start" }, onCancel = { calls += "cancel" },
                onCopy = { calls += "copy" }, onGithub = { calls += "github" }, onBack = { calls += "back" },
            )
        }
    }

    @Test
    fun idleShowsStartAndEveryCheck() {
        show(SelfCheckState())
        compose.onNodeWithTag("selfcheck-start").performClick()
        assertEquals(listOf("start"), calls)
        compose.onNodeWithText(SelfCheckCopy.name(SelfCheck.APP_COPIES)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun startIsBlockedWhileATaskRuns() {
        show(SelfCheckState(), busy = true)
        compose.onNodeWithTag("selfcheck-start").assertIsNotEnabled()
    }

    @Test
    fun runningShowsCancel() {
        show(SelfCheckState(SelfCheckState.Phase.Running))
        compose.onNodeWithTag("selfcheck-cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun endedShowsReportAndBothButtons() {
        val results = SelfCheck.entries.map { CheckResult(it, CheckStatus.Pass, "ok") }.toMutableList()
        results[SelfCheck.STAYS_ALIVE.ordinal] = CheckResult(SelfCheck.STAYS_ALIVE, CheckStatus.Fail, "frames stopped after 6 s", Advice.FramesStopped)
        show(SelfCheckState(SelfCheckState.Phase.Done, results, facts, 40_000))
        compose.onNodeWithText(SelfCheckCopy.advice(Advice.FramesStopped)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("selfcheck-report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("selfcheck-copy").performScrollTo().performClick()
        compose.onNodeWithTag("selfcheck-github").performScrollTo().performClick()
        assertEquals(listOf("copy", "github"), calls)
    }
}
