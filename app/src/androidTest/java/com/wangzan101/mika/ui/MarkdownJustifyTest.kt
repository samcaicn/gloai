package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.memory.ui.Row

/**
 * 回答正文的两端对齐:排版要在真机上正常渲染,尤其是引用块——它外面是按内容量高的 Row,
 * 取宽度不能走子组合(BoxWithConstraints),否则会拿不到 intrinsic 而崩。
 */
@RunWith(AndroidJUnit4::class)
class MarkdownJustifyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val answerStyle = MikaType.Body.copy(textAlign = TextAlign.Justify)

    @Test
    fun quoteInJustifiedAnswerRenders() {
        val quote = "引用里的中文段落也应该正常显示，不能因为量不到宽度就崩掉。"
        compose.setContent {
            MikaTheme {
                MarkdownText(
                    "开头一段足够长的中文，用来确认两端对齐不影响原来的换行。\n\n> $quote\n\n- 列表项一\n- 列表项二",
                    answerStyle,
                )
            }
        }
        compose.onNodeWithText(quote, substring = true).assertIsDisplayed()
        compose.onNodeWithText("列表项二", substring = true).assertIsDisplayed()
    }

    @Test
    fun headingsAndTablesStayUnjustified() {
        val heading = "小标题"
        val cell = "单元格"
        compose.setContent {
            MikaTheme {
                MarkdownText(
                    "## $heading\n\n| 一 | 二 |\n| --- | --- |\n| $cell | 二 |",
                    answerStyle,
                )
            }
        }
        compose.onNodeWithText(heading, substring = true).assertIsDisplayed()
        compose.onNodeWithText(cell, substring = true).assertIsDisplayed()
    }
}
