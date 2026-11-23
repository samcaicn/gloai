package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设置 › 恢复与修复:几项默认显示打开,拨动直接执行,不弹确认框。 */
@RunWith(AndroidJUnit4::class)
class RecoverySectionTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<Pair<RecoveryCapability, Boolean>>()

    private fun show(consent: RecoveryConsent, notes: Map<RecoveryCapability, String> = emptyMap()) {
        compose.setContent {
            RecoverySection(consent = consent, busy = null, notes = notes, lastRestore = null, onToggle = { c, on -> calls += c to on })
        }
    }

    @Test
    fun untouchedOnesShowOnAndExplanationsAppearOnToggle() {
        show(RecoveryConsent(), notes = mapOf(RecoveryCapability.Accessibility to RecoveryCopy.NEED_SETUP))
        RecoveryCapability.entries.forEach { compose.onNodeWithTag("recovery-${it.name}").assertIsOn() }
        compose.onAllNodesWithText("默认打开").assertCountEquals(0)
        compose.onNodeWithText(RecoveryCopy.NEED_SETUP).assertIsDisplayed()

        // 说明默认收起,点开「了解这几项在做什么」后才显示。
        compose.onAllNodesWithText(RecoveryCopy.switch(RecoveryCapability.BatteryWhitelist).explain).assertCountEquals(0)
        compose.onNodeWithText(RecoveryCopy.LEARN).performClick()
        compose.onNodeWithText(RecoveryCopy.switch(RecoveryCapability.BatteryWhitelist).explain).assertExists()
    }

    @Test
    fun tappingOnAppliesDirectlyWithoutADialog() {
        show(RecoveryConsent(declined = setOf(RecoveryCapability.BatteryWhitelist)))
        val tag = "recovery-${RecoveryCapability.BatteryWhitelist.name}"
        compose.onNodeWithTag(tag).assertIsOff()
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        assertEquals(listOf(RecoveryCapability.BatteryWhitelist to true), calls)
        compose.onAllNodesWithText("取消").assertCountEquals(0)
    }

    @Test
    fun tappingOffTurnsItOff() {
        show(RecoveryConsent(applied = setOf(RecoveryCapability.Accessibility)))
        compose.onNodeWithTag("recovery-${RecoveryCapability.Accessibility.name}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(RecoveryCapability.Accessibility to false), calls)
    }
}
