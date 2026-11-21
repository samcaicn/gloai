package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OperationScreenPaneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun receivesFramesShowsAttentionAndExposesNavigationAndHideControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var frame by mutableStateOf<Bitmap?>(null)
        var mode by mutableStateOf(PipMode.Running)
        var navigationRequests = 0
        var closeRequests = 0
        compose.setContent {
            OperationScreenPane(frame, mode, onConversations = { navigationRequests++ }, onClose = { closeRequests++ })
        }
        compose.onNodeWithText(context.getString(R.string.screens_waiting)).assertExists()
        compose.onNodeWithTag("operation-screen-frame").assertDoesNotExist()
        compose.runOnIdle {
            frame = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
            mode = PipMode.NeedsYou
        }
        compose.onNodeWithTag("operation-screen-frame").assertExists()
        compose.onNodeWithText(context.getString(R.string.screens_needs_you)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_menu)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.screens_hide_preview)).performClick()
        compose.runOnIdle {
            assertEquals(1, navigationRequests)
            assertEquals(1, closeRequests)
        }
    }
}
