package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.chat.ui.AgentPip
import com.wangzan101.mika.chat.ui.PipMode
import com.wangzan101.mika.chat.ui.PipUi

@RunWith(AndroidJUnit4::class)
class AgentPipResizeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sizeControlsChangeTheExpandedWindowAndResetIt() {
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        var expanded by mutableStateOf(true)
        compose.setContent {
            AgentPip(PipUi(visible = true, expanded = expanded), frame, webContent = false, pipInput = null, operateEnabled = true,
                reduceMotion = false, topInset = 0f, bottomInset = 80f,
                onExpand = { expanded = true }, onCollapse = { expanded = false }, onAttention = {})
        }
        fun width(): Int { compose.waitForIdle(); return compose.onNodeWithTag("pip-window").fetchSemanticsNode().size.width }
        val initial = width()
        compose.onNodeWithTag("pip-size-smaller").performClick()
        val small = width()
        assertTrue(small < initial)
        compose.onNodeWithTag("pip-size-larger").performClick()
        assertTrue(width() > small)
        compose.onNodeWithTag("pip-size-reset").performClick()
        assertEquals(initial, width())
        compose.runOnIdle { expanded = false }
        compose.waitForIdle()
        compose.onNodeWithTag("pip-size-larger").assertDoesNotExist()
        frame.recycle()
    }

    @Test fun pinchResizesWithoutBecomingAnAttentionTap() {
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        var attention = 0
        compose.setContent {
            AgentPip(PipUi(visible = true, expanded = true, mode = PipMode.NeedsYou), frame, webContent = false, pipInput = null, operateEnabled = true,
                reduceMotion = false, topInset = 0f, bottomInset = 80f,
                onExpand = {}, onCollapse = {}, onAttention = { attention++ })
        }
        val window = compose.onNodeWithTag("pip-window")
        val before = window.fetchSemanticsNode().size.width
        window.performTouchInput {
            pinch(start0 = Offset(width * 0.4f, height * 0.5f), end0 = Offset(width * 0.2f, height * 0.5f),
                start1 = Offset(width * 0.6f, height * 0.5f), end1 = Offset(width * 0.8f, height * 0.5f))
        }
        compose.waitForIdle()
        assertTrue(window.fetchSemanticsNode().size.width > before)
        assertEquals(0, attention)
        frame.recycle()
    }
}
