package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.wangzan101.mika.core.run.ManualControl
import com.wangzan101.mika.task.ScreenControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InteractiveOperationScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Control : ScreenControl {
        override val key = "test-run"
        override val manualControl = ManualControl()
        val gestures = mutableListOf<List<Int>>()
        override suspend fun beginScreenControl() = manualControl.begin()
        override suspend fun continueAfterScreenControl() = manualControl.end()
        override suspend fun resizeScreen(width: Int, height: Int) = true
        override suspend fun touchScreen(width: Int, height: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, swipe: Boolean): Boolean {
            gestures += listOf(width, height, x1, y1, x2, y2, if (swipe) 1 else 0)
            return true
        }
        override suspend fun screenBack(width: Int, height: Int) = true
        override suspend fun screenText(width: Int, height: Int, text: String) = true
    }

    @Test fun firstTouchAcquiresThenTapAndSwipeMapToImageAndMissingFrameCanResume() {
        val run = Control()
        var frame by mutableStateOf<Bitmap?>(Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888))
        compose.setContent { InteractiveOperationScreen(run, frame, PipMode.Running, {}, {}) }
        fun ready() { compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("screen-control-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
        ready()
        val screen = compose.onNodeWithTag("operation-screen-frame")
        screen.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(ManualControl.State.Manual, run.manualControl.state.value)
            assertTrue(run.gestures.isEmpty())
        }
        ready()
        screen.performTouchInput { click(center) }
        compose.runOnIdle {
            val tap = run.gestures.single()
            assertEquals(listOf(240, 480), tap.take(2))
            assertTrue(tap[2] in 119..120 && tap[3] in 239..240)
            assertEquals(0, tap.last())
        }
        screen.performTouchInput { swipe(center + Offset(0f, height * .15f), center - Offset(0f, height * .15f), 300) }
        compose.runOnIdle {
            assertEquals(2, run.gestures.size)
            assertEquals(1, run.gestures.last().last())
            assertTrue(run.gestures.last()[3] > run.gestures.last()[5])
            frame = null
        }
        ready()
        compose.onNodeWithTag("screen-control-toggle").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ManualControl.State.Automatic, run.manualControl.state.value) }
    }
}
