package com.wangzan101.mika.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CallFollowUpDraftDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSaveKeepsEditsAndSuccessfulSaveCanBeCopiedAndReopened() {
        val app = ApplicationProvider.getApplicationContext<MikaApp>()
        val draft = "关于刚才的电话，已记下您需要了解床垫的需求。尺寸和数量还待确认，方便时请补充一下，便于整理报价。"
        val edited = "关于刚才的电话，请补充床垫尺寸和数量，收到后再整理报价。"
        val record = CallRecord("follow-up-fixture", "测试客户", CallDirection.OUTGOING,
            1794691200000, 1794691260000, "通话已结束", emptyList(),
            summary = CallSummary(purpose = "了解床垫采购需求", highlights = listOf("需要床垫报价"),
                uncertainties = listOf("尺寸、数量待确认"), followUpDraft = draft), summaryStatus = SummaryStatus.READY)
        var stored = listOf(record)
        var failWrite = true
        val storage = object : CallHistory.Storage {
            override fun read() = stored
            override fun write(records: List<CallRecord>): Boolean {
                if (failWrite) return false
                stored = records
                return true
            }
        }
        val history = CallHistory(storage)
        runBlocking { history.load() }
        compose.setContent {
            val records by history.state.collectAsState()
            MikaTheme {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    CallHistorySection(records, false, { _, _ -> CallActionResult(true, "") },
                        { CallActionResult(true, "") }, { CallActionResult(true, "") }, { CallActionResult(true, "") },
                        focusRecordId = record.id,
                        onSaveDraft = { id, text -> CallActionResult(history.saveFollowUpDraft(id, text) == CallHistory.Result.SAVED, "") })
                }
            }
        }
        compose.onNodeWithTag("call-draft-copy").performScrollTo()
        snapshot(app, "draft")
        compose.onNodeWithTag("call-draft-edit").performClick()
        compose.onNodeWithTag("call-draft-input").performTextReplacement(edited)
        compose.onNodeWithTag("call-draft-save").performClick()
        compose.onNodeWithText(app.getString(R.string.calls_draft_save_failed)).assertExists()
        compose.onNodeWithTag("call-draft-input").assertTextContains(edited)
        compose.runOnIdle { assertEquals(draft, stored.single().summary!!.followUpDraft); failWrite = false }
        snapshot(app, "edit")
        compose.onNodeWithTag("call-draft-save").performClick()
        compose.waitUntil {
            compose.onAllNodes(hasTestTag("call-draft-text") and hasText(edited)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("call-draft-input").assertDoesNotExist()
        compose.onNodeWithTag("call-draft-text").assertTextEquals(edited)
        compose.onNodeWithTag("call-draft-copy").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(edited, app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        runBlocking {
            val reopened = CallHistory(storage)
            reopened.load()
            assertEquals(edited, reopened.state.value.single().summary!!.followUpDraft)
        }
    }

    private fun snapshot(context: Context, name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "follow-up-$name-${bitmap.width}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
