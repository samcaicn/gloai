package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PointEditorFocusTest {
    @Test fun pointTypeRejectsAnotherFocusedEditorWhenTargetSupportsLongClick() = rejects { screen, target ->
        screen.typeAt(target.centerX, target.centerY, "New draft")
    }

    @Test fun pointClearRejectsAnotherFocusedEditorWhenTargetSupportsLongClick() = rejects { screen, target ->
        screen.clearAt(target.centerX, target.centerY)
    }

    private fun rejects(action: suspend (AndroidScreen, UiElement) -> ActionResult) = runBlocking {
        val agent = DeviceSupport.newAgent()
        try {
            DeviceSupport.startTestActivity(agent.display.displayId, PointFocusEditorActivity::class.java)
            val a11y = DeviceSupport.service()
            withTimeout(8_000) {
                while (true) {
                    val focus = a11y.windowsOn(agent.display.displayId).firstNotNullOfOrNull {
                        it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    }
                    if (focus?.text?.toString() == "Focused original") break
                    delay(100)
                }
            }
            val screen = agent.screen
            val before = snapshotWithEditors(screen)
            val target = before.elements.single { it.name == "Target original" }
            val result = action(screen, target)
            assertEquals(result.toString(), "input_not_sent", (result as? ActionResult.Failed)?.reason)
            val after = snapshotWithEditors(screen)
            assertTrue(after.elements.any { it.name == "Target original" })
            assertTrue(after.elements.any { it.name == "Focused original" })
        } finally {
            agent.release()
        }
    }

    private suspend fun snapshotWithEditors(screen: AndroidScreen): ScreenSnapshot = withTimeout(8_000) {
        var snapshot = screen.snapshot()
        while (listOf("Target original", "Focused original").any { expected ->
                snapshot.elements.none { it.name == expected }
            }
        ) {
            delay(100)
            snapshot = screen.snapshot()
        }
        snapshot
    }
}
