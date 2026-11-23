package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallHistoryClearDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val record = CallRecord(
        id = "one", number = "123456789", direction = CallDirection.INCOMING,
        startedAtMs = 1, endedAtMs = 2, result = "fixture result", transcript = emptyList(),
    )
    private val app get() = ApplicationProvider.getApplicationContext<MikaApp>()

    @Test fun cancelDoesNotClearHistory() {
        var calls = 0
        showHistory(listOf(record)) { calls++; CallActionResult(true, "ok") }
        compose.onNodeWithTag("call-history-clear").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-clear-confirm").assertExists()
        compose.onNodeWithText(app.getString(R.string.calls_cancel)).performClick()
        compose.onNodeWithTag("call-history-clear-confirm").assertDoesNotExist()
        compose.onNodeWithText("fixture result", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun confirmClearsHistoryAndShowsEmptyState() {
        var calls = 0
        var history by mutableStateOf(listOf(record))
        compose.setContent {
            MikaTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CallHistorySection(history, false, { _, _ -> CallActionResult(true, "ok") },
                        { CallActionResult(true, "ok") }, onRetrySummary = { CallActionResult(true, "ok") }, onClear = {
                            calls++
                            history = emptyList()
                            CallActionResult(true, "ok")
                        })
                }
            }
        }
        compose.onNodeWithTag("call-history-clear").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("call-history-clear-confirm").performClick()
        compose.onNodeWithText(app.getString(R.string.calls_history_empty)).assertExists()
        compose.onNodeWithText(app.getString(R.string.calls_history_cleared)).assertExists()
        compose.onNodeWithText("fixture result", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun failedClearShowsErrorAndKeepsHistory() {
        var calls = 0
        showHistory(listOf(record)) { calls++; CallActionResult(false, "clear failed fixture") }
        compose.onNodeWithTag("call-history-clear").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-clear-confirm").performClick()
        compose.onNodeWithText(app.getString(R.string.calls_history_clear_failed)).assertExists()
        compose.onNodeWithText("fixture result", substring = true).assertExists()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun clearRemainsAvailableWhenSearchHasNoMatches() {
        var calls = 0
        showHistory(listOf(record)) { calls++; CallActionResult(true, "ok") }
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextInput("no matching call")
        compose.onNodeWithText(app.getString(R.string.calls_history_no_match)).assertExists()
        compose.onNodeWithTag("call-history-clear").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-clear-confirm").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun unreadableEmptyHistoryStillOffersClear() {
        var calls = 0
        compose.setContent {
            MikaTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CallHistorySection(emptyList(), true, { _, _ -> CallActionResult(true, "ok") },
                        { CallActionResult(true, "ok") }, onRetrySummary = { CallActionResult(true, "ok") }, onClear = {
                            calls++
                            CallActionResult(true, "ok")
                        })
                }
            }
        }
        compose.onNodeWithTag("call-history-clear").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-clear-confirm").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    private fun showHistory(history: List<CallRecord>, onClear: suspend () -> CallActionResult) {
        compose.setContent {
            MikaTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CallHistorySection(history, false, { _, _ -> CallActionResult(true, "ok") },
                        { CallActionResult(true, "ok") }, onRetrySummary = { CallActionResult(true, "ok") }, onClear = onClear)
                }
            }
        }
    }
}
