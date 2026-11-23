package com.wangzan101.mika.about.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.about.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val r020 = ReleaseInfo("0.2.0", "Mika 0.2.0", "- 新", "2026-11-05", "https://github.com/wangzan101/Mika/releases/tag/v0.2.0", null)
    private val r010 = r020.copy(version = "0.1.0")

    @Test
    fun checkRowStates() {
        assertEquals(AboutCopy.CheckValue(null), AboutCopy.checkValue(UpdateState()))
        assertTrue(AboutCopy.checkValue(UpdateState(status = CheckStatus.Checking)).spinner)
        // 连不上但早先查到过新版:照样提示有新版
        assertTrue(AboutCopy.checkValue(UpdateState(latest = r020, update = r020, status = CheckStatus.Failed)).dot)
    }
}
