package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.UiTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.wangzan101.mika.chat.ui.StepRow
import com.wangzan101.mika.chat.ui.StepState

class StepRowLanguageTest {

    @Before
    fun setUp() {
        // 单测没有 App 进程装 UiText.source;给 ui_locale 定死一个英文值,让默认的「是否中文界面」判断稳定。
        UiText.source = object : UiTexts {
            override fun string(id: Int, args: Array<out Any?>): String =
                if (id == R.string.ui_locale) "en" else ""

            override fun plural(id: Int, count: Int, args: Array<out Any?>): String = ""
        }
    }

    @Test
    fun testShouldHideChineseReasonInNonChineseLocale() {
        assertTrue(StepRow.shouldHideRawReason("点击搜索按钮查找联系人 Ved", isChineseLocale = false))
        assertTrue(StepRow.shouldHideRawReason("在搜索框输入“ved”", isChineseLocale = false))
    }

    @Test
    fun testShouldKeepEnglishReasonInNonChineseLocale() {
        assertFalse(StepRow.shouldHideRawReason("Tap search button to find Ved", isChineseLocale = false))
        assertFalse(StepRow.shouldHideRawReason("Type \"ved\" in search box", isChineseLocale = false))
        assertFalse(StepRow.shouldHideRawReason("", isChineseLocale = false))
    }

    @Test
    fun testShouldKeepChineseReasonInChineseLocale() {
        assertFalse(StepRow.shouldHideRawReason("点击搜索按钮查找联系人 Ved", isChineseLocale = true))
    }

    @Test
    fun testShouldKeepJapaneseReasonInNonChineseLocale() {
        // 日语界面下模型按语言指令写的日语用意:含汉字也含假名,不是中文,不该被隐藏。
        assertFalse(StepRow.shouldHideRawReason("検索ボタンをタップして連絡先を探す", isChineseLocale = false))
        assertFalse(StepRow.shouldHideRawReason("「微信」アプリを開く", isChineseLocale = false))
        // 中文界面下日语用意照常显示(中文界面从不隐藏)。
        assertFalse(StepRow.shouldHideRawReason("設定を開いてBluetoothを確認する", isChineseLocale = true))
    }

    @Test
    fun testShouldHideKanjiOnlyReasonInNonChineseLocale() {
        // 纯汉字无假名的文本无法与中文区分,非中文界面下仍回退(残余局限,如实断言)。
        assertTrue(StepRow.shouldHideRawReason("設定確認", isChineseLocale = false))
    }

    @Test
    fun testStepRowTextFallback() {
        // 界面语言为英文(上面装的 ui_locale=en)时,模型输出的中文用意回退到本地化标题。
        val rowFallback = StepRow(
            step = 1,
            title = "Tap · WeChat",
            subtitle = "",
            state = StepState.Ok,
            reason = "点击“微信”应用图标",
        )
        assertEquals("Tap · WeChat", rowFallback.text)

        // 模型输出英文用意时,始终直接展示。
        val rowEnglish = StepRow(
            step = 1,
            title = "Tap · WeChat",
            subtitle = "",
            state = StepState.Ok,
            reason = "Tap \"WeChat\" icon to open app",
        )
        assertEquals("Tap \"WeChat\" icon to open app", rowEnglish.text)
    }

    @Test
    fun testStepRowTextShowsJapaneseReasonUnderJapaneseUi() {
        // 日语界面(ui_locale=ja):模型按语言指令写的日语用意(含汉字)直接展示,不回退到本地化标题。
        UiText.source = object : UiTexts {
            override fun string(id: Int, args: Array<out Any?>): String =
                if (id == R.string.ui_locale) "ja" else ""

            override fun plural(id: Int, count: Int, args: Array<out Any?>): String = ""
        }
        val row = StepRow(
            step = 1,
            title = "タップ · WeChat",
            subtitle = "",
            state = StepState.Ok,
            reason = "「微信」アプリを開く",
        )
        assertEquals("「微信」アプリを開く", row.text)
    }
}
