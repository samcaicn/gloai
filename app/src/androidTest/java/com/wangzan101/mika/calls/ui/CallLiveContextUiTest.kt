package com.wangzan101.mika.calls.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Synthetic state and gestures only: no Telecom, microphone, provider or carrier calls. */
@RunWith(AndroidJUnit4::class)
class CallLiveContextUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(ChineseUi()).around(compose)
    private val active = CallRuntimeState(callId = "synthetic-call", phase = CallPhase.ACTIVE,
        aiActive = true, canHangUp = true, canTakeOver = true)

    @Composable
    private fun Card(state: CallRuntimeState) {
        CurrentCallCard(state, null, canAnswer = false, canControl = true,
            onAnswer = {}, onHangup = {}, onTakeover = {}, onCancelAuto = {}, onTranscript = {})
    }

    @Test fun compactPreviewUsesLatestNonblankLineAndItsActualSpeaker() {
        val state = mutableStateOf(active.copy(liveTranscript = listOf(
            CallTranscriptLine("assistant", "older assistant line"),
            CallTranscriptLine("caller", "new caller line"),
            CallTranscriptLine("assistant", "   "),
        )))
        compose.setContent { MikaTheme(dark = false) { Column { Card(state.value) } } }
        compose.onNodeWithText("new caller line").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.calls_caller)).assertIsDisplayed()
        compose.onNodeWithText("older assistant line").assertDoesNotExist()
        compose.onNodeWithTag("call-takeover").assertIsDisplayed()
        compose.onNodeWithTag("call-hangup").assertIsDisplayed()
        compose.runOnIdle {
            state.value = active.copy(liveTranscript = listOf(CallTranscriptLine("system", "local device status")))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_status_label)).assertIsDisplayed()
        compose.onNodeWithText("new caller line").assertDoesNotExist()
        compose.runOnIdle { state.value = active.copy(liveTranscript = emptyList()) }
        compose.onNodeWithTag("call-current-latest").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.calls_ai_active)).assertIsDisplayed()
    }

    @Test fun confirmedTaskRemainsAfterAiHandoffAndEndsWithItsCarrierCall() {
        val identity = Any()
        val binding = CallTaskBinding(identity)
        binding.bind(CallTaskBrief("confirmed goal", context = "confirmed instructions"))
        val state = mutableStateOf(active.copy(activeTask = binding.visibleFor(identity, true, true)))
        compose.setContent { MikaTheme(dark = false) { Column { Card(state.value) } } }
        compose.runOnIdle {
            state.value = state.value.copy(aiActive = false, canTakeOver = false,
                activeTask = binding.visibleFor(identity, true, true))
        }
        compose.onNodeWithTag("call-active-task").performClick()
        compose.onNodeWithText("confirmed instructions").assertIsDisplayed()
        compose.runOnIdle {
            binding.end()
            state.value = active.copy(callId = "next-call", activeTask = binding.visibleFor(Any(), true, true))
        }
        compose.onNodeWithTag("call-active-task-dialog").assertDoesNotExist()
        compose.onNodeWithTag("call-active-task").assertDoesNotExist()
    }

    @Test fun existingReadingPositionSurvivesNewLinesTabReturnAndRestoration() {
        val lines = mutableStateOf(List(24) { CallTranscriptLine("caller", "archived line $it ".repeat(20)) })
        val visible = mutableStateOf(true)
        lateinit var reading: ScrollState
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MikaTheme(dark = false) {
                val holder = rememberSaveableStateHolder()
                if (visible.value) holder.SaveableStateProvider("receive") {
                    val scroll = rememberScrollState()
                    SideEffect { reading = scroll }
                    Column(Modifier.height(240.dp).fillMaxWidth().verticalScroll(scroll).testTag("reading-page")) {
                        LiveCallTranscript("synthetic-call", lines.value)
                    }
                } else Text("another page")
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.calls_expand)).performClick()
        compose.onNodeWithTag("reading-page").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
        ) { it(0f, 120f) }
        compose.waitForIdle()
        val before = reading.value
        assertTrue(before > 0)
        compose.runOnIdle { lines.value = lines.value + CallTranscriptLine("assistant", "new line at the end") }
        compose.waitForIdle()
        assertEquals(before, reading.value)
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(before, reading.value)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(before, reading.value)
    }
}
