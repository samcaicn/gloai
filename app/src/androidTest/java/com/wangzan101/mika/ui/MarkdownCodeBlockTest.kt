package com.wangzan101.mika.ui

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownCodeBlockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertClipboard(expected: String) {
        compose.waitUntil(5_000) { compose.runOnIdle { compose.activity.hasWindowFocus() } }
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test
    fun codeBlockShowsLanguageAndCopiesOnlyCode() {
        val copyLabel = context.getString(R.string.chat_copy)
        val copiedLabel = context.getString(R.string.chat_copied)
        compose.setContent {
            MikaTheme {
                SelectionContainer {
                    MarkdownText(
                        "前文说明。\n\n```kotlin\nval answer = 42\nprintln(answer)\n```\n\n后文说明。",
                        MikaType.Body,
                    )
                }
            }
        }
        compose.onNodeWithTag("code-block-language").assertIsDisplayed().assertTextEquals("kotlin")
        compose.onNodeWithTag("code-block-copy").assertIsDisplayed().assertTextEquals(copyLabel)
        compose.onNodeWithTag("code-block-copy").performClick()

        assertClipboard("val answer = 42\nprintln(answer)")
        compose.onNodeWithTag("code-block-copy").assertTextEquals(copiedLabel)
    }

    @Test
    fun unlabeledCodeBlockStillShowsCopyButton() {
        val copyLabel = context.getString(R.string.chat_copy)
        compose.setContent {
            MikaTheme {
                MarkdownText("```\necho hello\n```", MikaType.Body)
            }
        }
        assertEquals(0, compose.onAllNodesWithTag("code-block-language").fetchSemanticsNodes().size)
        compose.onNodeWithText(copyLabel).assertIsDisplayed()
        compose.onNodeWithTag("code-block-copy").performClick()
        assertClipboard("echo hello")
    }
}
