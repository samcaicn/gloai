package com.wangzan101.mika.ui

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalReplyCopyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertClipboard(expected: String) {
        // Android denies clipboard reads while the test Activity is still gaining window focus.
        compose.waitUntil(5_000) { compose.runOnIdle { compose.activity.hasWindowFocus() } }
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun copyButtonCopiesOnlyThisReplyAsReadableText() {
        compose.setContent {
            MikaTheme {
                Column {
                    PersonalReply("Another reply", false)
                    PersonalReply("# Heading\n\nSome **bold** text.\n\n```\nline 1\nline 2\n```", false)
                }
            }
        }
        compose.onAllNodesWithContentDescription(context.getString(R.string.chat_copy))[1].performClick()
        assertClipboard("Heading\n\nSome bold text.\n\nline 1\nline 2")
    }

    @Test fun longPressCanCopyPartOrSelectTheWholeReply() {
        // Invoke the actions offered to Android, independently of the phone's floating menu layout.
        val toolbar = object : TextToolbar {
            var copy: (() -> Unit)? = null
            var selectAll: (() -> Unit)? = null
            override var status = TextToolbarStatus.Hidden
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                status = TextToolbarStatus.Shown
                copy = onCopyRequested
                selectAll = onSelectAllRequested
            }
            override fun hide() {
                status = TextToolbarStatus.Hidden
                copy = null
                selectAll = null
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                MikaTheme {
                    Column {
                        PersonalReply("Previous reply", false)
                        PersonalReply("one **two** six ten", false)
                    }
                }
            }
        }
        val words = "one two six ten"
        val reply = compose.onNodeWithText(words)
        val layouts = mutableListOf<TextLayoutResult>()
        reply.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val start = layouts.single().getBoundingBox(words.indexOf('n')).center
        val end = layouts.single().getBoundingBox(words.indexOf('x')).center
        reply.performTouchInput { longClick(start) }
        compose.runOnIdle { requireNotNull(toolbar.copy).invoke() }
        assertClipboard("one")

        reply.performTouchInput {
            down(start)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(end)
            up()
        }
        compose.runOnIdle { requireNotNull(toolbar.copy).invoke() }
        assertClipboard("one two six")

        reply.performTouchInput { longClick(start) }
        compose.runOnIdle { requireNotNull(toolbar.selectAll).invoke() }
        compose.runOnIdle { requireNotNull(toolbar.copy).invoke() }
        assertClipboard("one two six ten")
    }
}
