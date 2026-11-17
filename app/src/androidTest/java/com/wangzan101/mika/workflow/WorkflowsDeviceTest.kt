package com.wangzan101.mika.workflow

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.ui.WorkflowsScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class WorkflowsDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "workflows-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val file = File(directory, "store.bin")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private var fail = false
    private val store = WorkflowStore(file, object : KeyCipher {
        override fun encrypt(plain: ByteArray) = if (fail) error("disk") else cipher.encrypt(plain)
        override fun decrypt(data: ByteArray) = cipher.decrypt(data)
    })
    private val dispatched = CopyOnWriteArrayList<WorkflowDispatch>()
    private val runtime = WorkflowRuntime(store) { dispatched += it }
    private var reused = ""
    @After fun clean() { directory.deleteRecursively() }
    private fun show() {
        compose.setContent { WorkflowsScreen(runtime, onBack = {}, onOpenConversation = {}, onUseResult = { reused = it }) }
        compose.waitUntil(5_000) { store.loaded.value }
    }
    private fun seed(): WorkflowTemplate = store.saveTemplate("study", 0, "日语资料整理", listOf(
        WorkflowStep(title = "找学习资料", instruction = "搜索适合初学者的五十音学习资料"),
        WorkflowStep(title = "安排七天学习", instruction = "根据资料制订每天二十分钟的学习计划")))
    private fun snapshot(name: String) {
        compose.waitForIdle(); Thread.sleep(800)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "workflow-$name-${shot.width}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
    @Test fun createEditExecutePauseContinueAndReuse() {
        show()
        compose.onNodeWithTag("workflow-new").performClick()
        compose.onNodeWithTag("workflow-title").performTextReplacement("日语资料整理")
        compose.onNodeWithTag("workflow-step-title-0").performTextReplacement("找学习资料")
        compose.onNodeWithTag("workflow-step-instruction-0").performTextReplacement("搜索五十音入门资料")
        compose.onNodeWithTag("workflow-step-title-1").performScrollTo().performTextReplacement("安排七天学习")
        compose.onNodeWithTag("workflow-step-instruction-1").performScrollTo().performTextReplacement("根据资料制订每天二十分钟的学习计划")
        compose.onNodeWithTag("workflow-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.data.value.templates.size == 1 }
        compose.onNodeWithTag("workflow-start").performClick()
        compose.onNodeWithTag("workflow-dialog-input").performTextReplacement("从五十音开始，每天二十分钟")
        snapshot("start")
        compose.onNodeWithTag("workflow-dialog-apply").performClick()
        compose.waitUntil(5_000) { dispatched.size == 1 }
        val first = dispatched[0]; store.claim(first.attemptId)
        compose.onNodeWithText("此步完成后暂停").performClick()
        compose.waitUntil(5_000) { store.data.value.runs.single().phase == WorkflowPhase.Pausing }
        runtime.complete(first.attemptId, true, "先学平假名，每天记一行，并用单词复习。")
        compose.waitUntil(5_000) { store.data.value.runs.single().phase == WorkflowPhase.Paused }
        snapshot("paused")
        assertEquals(1, dispatched.size)
        compose.onNodeWithTag("workflow-continue").performClick()
        compose.waitUntil(5_000) { dispatched.size == 2 }
        val second = dispatched[1]
        assertTrue(second.previous.contains("平假名")); assertTrue(second.input.contains("二十分钟"))
        store.claim(second.attemptId)
        runtime.complete(second.attemptId, true, "第1天：あ行。第2天：か行。第3天：さ行。每天用单词复习。")
        compose.waitUntil(5_000) { store.data.value.runs.single().phase == WorkflowPhase.Completed }
        snapshot("completed")
        compose.onNodeWithTag("workflow-use-result").performClick()
        assertTrue(reused.contains("第1天"))
        assertEquals(store.data.value, WorkflowStore(file, cipher).also { it.load() }.data.value)
        compose.onNodeWithText("再执行一次").performScrollTo().performClick()
        compose.onNodeWithTag("workflow-dialog-input").performTextReplacement("这次学习片假名")
        compose.onNodeWithTag("workflow-dialog-apply").performClick()
        compose.waitUntil(5_000) { dispatched.size == 3 }
        assertEquals("这次学习片假名", dispatched.last().input)
        assertEquals("", dispatched.last().previous)
        assertEquals(2, store.data.value.runs.size)
    }
    @Test fun interruptedStepManualResultAndSaveFailureDoNotRepeatActions() {
        val template = seed(); val id = runtime.start(template, "五十音")
        val first = dispatched.single(); store.claim(first.attemptId)
        runtime.complete(first.attemptId, false, "找到入门资料，但没能核对链接。", "测试中断")
        show()
        compose.onAllNodesWithText("日语资料整理").onLast().performClick()
        compose.onNodeWithTag("workflow-resolve").performClick()
        compose.onNodeWithTag("workflow-dialog-input").performTextReplacement("资料已核对：先学平假名，每天一行。")
        fail = true
        compose.onNodeWithTag("workflow-dialog-apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("没能保存，请再试一次。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("workflow-dialog-input").assertTextContains("资料已核对：先学平假名，每天一行。")
        assertEquals(1, dispatched.size); assertEquals(WorkflowPhase.Review, store.data.value.runs.single().phase)
        fail = false
        compose.onNodeWithTag("workflow-dialog-apply").performClick()
        compose.waitUntil(5_000) { dispatched.size == 2 }
        assertTrue(dispatched.last().previous.contains("资料已核对"))
        val second = dispatched.last(); store.claim(second.attemptId)
        runtime.complete(second.attemptId, false, "未完成计划", "网络中断")
        compose.onNodeWithTag("workflow-retry").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(2, dispatched.size)
        snapshot("review")
        compose.onNodeWithTag("workflow-retry").performScrollTo().performClick()
        compose.onNodeWithTag("workflow-retry-confirm").performClick()
        compose.waitUntil(5_000) { dispatched.size == 3 }
        assertNotEquals(second.attemptId, dispatched.last().attemptId)
        assertEquals(2, store.data.value.runs.single().attempts.last().size)
        assertEquals(id, store.data.value.runs.single().id)
    }
}
