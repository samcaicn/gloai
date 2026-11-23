package com.wangzan101.mika.calls.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Real Compose gestures with synthetic callbacks only; never invokes Telecom or a model. */
@RunWith(AndroidJUnit4::class)
class CallDtmfUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(ChineseUi()).around(compose)

    private val active = CallRuntimeState(
        callId = "synthetic-a", phase = CallPhase.ACTIVE, canSendDtmf = true,
        canHangUp = true, canTakeOver = true, number = "5550100",
    )

    private fun show(
        state: State<CallRuntimeState> = mutableStateOf(active),
        events: MutableList<String> = mutableListOf(),
        send: suspend (String, String) -> CallActionResult,
    ) {
        compose.setContent {
            MikaTheme(dark = false) {
                Column(Modifier.fillMaxSize()) {
                    CurrentCallCard(
                        state = state.value, feedback = null, canAnswer = false, canControl = true,
                        onAnswer = { events += "answer" }, onHangup = { events += "hangup" },
                        onTakeover = { events += "takeover" }, onCancelAuto = { events += "cancel-auto" },
                        onTranscript = { events += "transcript" }, onSendDtmf = send,
                    )
                }
            }
        }
    }

    @Test fun manualKeysBindExactCallAndKeepExistingControls() {
        val sent = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()
        show(
            state = mutableStateOf(active.copy(aiActive = true,
                liveTranscript = listOf(CallTranscriptLine("assistant", "synthetic transcript")))),
            events = events,
        ) { id, keys -> sent += id to keys; CallActionResult(true, "transport-only") }
        compose.onNodeWithTag("call-dtmf-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-dtmf-dialog").assertIsDisplayed()
        compose.waitForIdle()
        for (key in "0123456789*#") {
            compose.onNodeWithTag("call-dtmf-key-$key").performScrollTo().performClick()
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals("0123456789*#".map { "synthetic-a" to it.toString() }, sent) }
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_sent)).assertIsDisplayed()
        compose.onNodeWithTag("call-dtmf-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-current-transcript").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-takeover").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-hangup").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("transcript", "takeover", "hangup"), events) }
    }

    @Test fun pendingSendBlocksOverlapAndClosingCancelsWithoutHangup() {
        val pending = CompletableDeferred<CallActionResult>()
        val sent = mutableListOf<String>()
        val events = mutableListOf<String>()
        var cancelled = false
        show(events = events) { _, keys ->
            sent += keys
            try { pending.await() } finally { cancelled = true }
        }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.onNodeWithTag("call-dtmf-key-1").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_sending)).assertIsDisplayed()
        compose.onNodeWithTag("call-dtmf-key-2").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("call-dtmf-batch").assertIsNotEnabled()
        compose.onNodeWithTag("call-dtmf-close").performClick()
        compose.waitUntil(3_000) { cancelled }
        compose.runOnIdle { assertEquals(listOf("1"), sent); assertTrue(events.isEmpty()) }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.onNodeWithTag("call-dtmf-key-2").assertIsEnabled()
        compose.onNodeWithTag("call-dtmf-status").assertDoesNotExist()
    }

    @Test fun callReplacementAndEndingDismissKeypadDiscardDraftAndCancelSend() {
        val state = mutableStateOf(active)
        val pending = CompletableDeferred<CallActionResult>()
        val sent = mutableListOf<Pair<String, String>>()
        var cancelled = false
        show(state) { id, keys ->
            sent += id to keys
            if (id == "synthetic-a") try { pending.await() } finally { cancelled = true }
            else CallActionResult(true, "ok")
        }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.onNodeWithTag("call-dtmf-key-1").performScrollTo().performClick()
        compose.runOnIdle { state.value = active.copy(callId = "synthetic-b") }
        compose.onNodeWithTag("call-dtmf-dialog").assertDoesNotExist()
        compose.waitUntil(3_000) { cancelled }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.onNodeWithTag("call-dtmf-key-#").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-a" to "1", "synthetic-b" to "#"), sent) }
        compose.onNodeWithTag("call-dtmf-batch").performScrollTo().performClick()
        compose.onNodeWithTag("call-dtmf-input").performTextReplacement("123")
        compose.runOnIdle { state.value = active.copy(callId = "synthetic-b", phase = CallPhase.HOLDING, canSendDtmf = false) }
        compose.onNodeWithTag("call-dtmf-dialog").assertDoesNotExist()
        compose.onNodeWithTag("call-dtmf-open").assertDoesNotExist()
        compose.runOnIdle { state.value = active.copy(callId = "synthetic-b") }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.onNodeWithTag("call-dtmf-input").assertDoesNotExist()
    }

    @Test fun sequenceInputValidatesLimitRetainsFailureAndAllowsExplicitRetry() {
        val sent = mutableListOf<String>()
        show { _, keys ->
            sent += keys
            CallActionResult(sent.size > 1, "synthetic failure")
        }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.onNodeWithTag("call-dtmf-batch").performScrollTo().performClick()
        val sequence = "12*#".repeat(8)
        compose.onNodeWithTag("call-dtmf-input").performTextReplacement(sequence)
        compose.onNodeWithTag("call-dtmf-input").performTextReplacement(sequence + "1")
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_invalid)).assertIsDisplayed()
        compose.onNodeWithTag("call-dtmf-input").assertTextContains(sequence)
        compose.onNodeWithTag("call-dtmf-send").performScrollTo().performClick()
        compose.onNodeWithText("synthetic failure").assertIsDisplayed()
        compose.onNodeWithTag("call-dtmf-input").assertTextContains(sequence)
        compose.onNodeWithTag("call-dtmf-send").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_sent)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(sequence, sequence), sent) }
        compose.onNodeWithTag("call-dtmf-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-dtmf-input").performTextReplacement("12A")
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_invalid)).assertIsDisplayed()
        compose.onNodeWithTag("call-dtmf-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-dtmf-input").performTextReplacement("12")
        compose.onNodeWithTag("call-dtmf-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-dtmf-input").assertTextContains("1")
    }

    @Test fun unavailableOrConcurrentRuntimeControlDoesNotSend() {
        val state = mutableStateOf(active.copy(canSendDtmf = false))
        var sends = 0
        show(state) { _, _ -> sends++; CallActionResult(true, "ok") }
        compose.onNodeWithTag("call-dtmf-open").assertDoesNotExist()
        compose.runOnIdle { state.value = active }
        compose.onNodeWithTag("call-dtmf-open").performClick()
        compose.runOnIdle { state.value = active.copy(dtmfBusy = true) }
        compose.onNodeWithTag("call-dtmf-key-1").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_sending)).assertIsDisplayed()
        compose.onNodeWithTag("call-dtmf-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-dtmf-open").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun currentInstructionsFollowTheBoundCallRatherThanAnEditableDraft() {
        val state = mutableStateOf(active.copy(activeTask = CallTaskBrief("bound goal", context = "bound instructions")))
        show(state) { _, _ -> CallActionResult(true, "ok") }
        compose.onNodeWithTag("call-active-task").performClick()
        compose.onNodeWithText("bound instructions").assertIsDisplayed()
        compose.runOnIdle { state.value = active.copy(callId = "replacement", activeTask = null) }
        compose.onNodeWithTag("call-active-task-dialog").assertDoesNotExist()
        compose.onNodeWithTag("call-active-task").assertDoesNotExist()
    }

    @Test fun systemStatusIsNotAttributedToCallerSpeech() {
        compose.setContent {
            MikaTheme(dark = false) {
                CallTranscriptText(CallTranscriptLine("system", "synthetic submission status"))
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.calls_dtmf_status_label)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.calls_caller)).assertDoesNotExist()
    }
}
