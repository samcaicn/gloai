package com.wangzan101.mika.ui

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.FieldsTestActivity
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.run.ManualControl
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.testTasks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import com.wangzan101.mika.settings.ui.models.edit

/** 真机完整链路：固定模型回复，真实 TaskRun、虚拟屏、系统设置与触控输入。 */
class InlineScreenControlDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val model = ScriptedModel().apply { intent = "ACT" }
    private val prefs = listOf("onboarding" to "model_setup_done", "phone_setup" to "guide_seen", "app_language" to "language_prompted")
    private var testConversation: String? = null
    private var saved = emptyList<Boolean?>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            assumeTrue(ctx.resources.configuration.let { it.screenWidthDp >= 640 && it.screenHeightDp >= 480 })
            assumeTrue(DeviceTaskState.runs.value.isEmpty())
            saved = prefs.map { (file, key) -> ctx.getSharedPreferences(file, Context.MODE_PRIVATE).let { if (it.contains(key)) it.getBoolean(key, false) else null } }
            prefs.forEach { (file, key) -> ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putBoolean(key, true).commit() }
            testTasks.modelFactory = { _, _ -> model }
            testTasks.lightModelFactory = { _, _ -> null }
            testTasks.visionModelFactory = { _, _ -> null }
            DeviceSupport.shell()
            DeviceSupport.service()
        }
        override fun after() {
            testConversation?.let { id ->
                DeviceTaskState.run(id)?.cancel()
                DeviceSupport.pollUntil(20_000) { true.takeIf { DeviceTaskState.run(id) == null } }
            }
            testTasks.modelFactory = TaskEnvironment.defaultModelFactory
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            prefs.forEachIndexed { i, (file, key) ->
                ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit().apply {
                    saved.getOrNull(i)?.let { putBoolean(key, it) } ?: remove(key)
                }.commit()
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun fit(run: com.wangzan101.mika.task.TaskRun) {
        var lastState = ""
        try { compose.waitUntil(15_000) {
            val area = compose.onNodeWithTag("operation-screen-viewport").fetchSemanticsNode().boundsInRoot
            val display = ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(run.displayId.value)
            val size = android.graphics.Point().also { display?.getRealSize(it) }
            val frame = run.frames.value
            lastState = "viewport=${area.width}x${area.height} display=${size.x}x${size.y} rotation=${display?.rotation} frame=${frame?.width}x${frame?.height} control=${run.manualControl.state.value}"
            frame != null && size.x > 0 && size.y > 0 &&
                kotlin.math.abs(size.x.toFloat() / size.y - area.width / area.height) < .015f &&
                kotlin.math.abs(frame.width.toFloat() / frame.height - area.width / area.height) < .015f &&
                compose.onAllNodes(hasTestTag("screen-control-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        } } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Virtual display did not fit: $lastState", e)
        }
    }

    @Test fun actualTaskCanBeControlledAndResumeWithFreshObservation() {
        val vm = (ctx.applicationContext as MikaApp).chat
        compose.runOnUiThread {
            vm.newConversation()
            assertTrue(vm.send("打开设置，供我测试手动接管，不修改设置", Prerequisites.Check(emptyList())))
            testConversation = vm.ui.value.conversationId
        }
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"com.android.settings"}""")
        model.awaitCall()
        model.feed("phone_observe")
        model.awaitCall()
        val run = DeviceTaskState.run(vm.ui.value.conversationId!!)!!
        assertNotNull(DeviceSupport.pollUntil(10_000) { run.frames.value })
        // 用真实输入框验证坐标，避免依赖某一版系统设置的语言或导航状态。
        DeviceSupport.startTestActivity(run.displayId.value, FieldsTestActivity::class.java)
        compose.onNodeWithTag("operation-screen-pane").assertExists()
        fit(run)
        val originalDisplay = run.displayId.value
        compose.onNodeWithTag("screen-control-toggle").performClick()
        assertNotNull(DeviceSupport.pollUntil(10_000) { true.takeIf { run.manualControl.state.value == ManualControl.State.Manual } })
        fit(run)
        val oldFrame = run.frames.value!!
        val beforeWidth = compose.onNodeWithTag("permanent-navigation").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("pane-divider").performTouchInput { swipe(center, center + Offset(180f, 0f), 400) }
        fit(run)
        assertTrue(compose.onNodeWithTag("permanent-navigation").fetchSemanticsNode().boundsInRoot.width > beforeWidth + 100f)
        assertEquals(originalDisplay, run.displayId.value)
        assertEquals(ManualControl.State.Manual, run.manualControl.state.value)
        assertFalse("old frame touch must be rejected after resizing", runBlocking {
            run.touchScreen(oldFrame.width, oldFrame.height, 10, 10, 10, 10, 80, false)
        })
        val frame = run.frames.value!!
        val service = DeviceSupport.service()
        val search = DeviceSupport.pollUntil(5_000) {
            service.windowsOn(run.displayId.value).mapNotNull { it.root }.flatMap {
                it.findAccessibilityNodeInfosByText("假搜索框")
            }.firstOrNull { it.isVisibleToUser }?.let { node -> Rect().also(node::getBoundsInScreen) }
        }
        assertNotNull("test search field not found on virtual display", search)
        val size = android.graphics.Point().also {
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(run.displayId.value).getRealSize(it)
        }
        val displayMetrics = android.util.DisplayMetrics().also {
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(run.displayId.value).getRealMetrics(it)
        }
        assertTrue("phone app must render a compact layout", size.x / displayMetrics.density < 600f)
        val x = search!!.centerX() * frame.width / size.x
        val y = search.centerY() * frame.height / size.y
        assertTrue("tap preview ${frame.width}x${frame.height} -> display $size, search $search", runBlocking {
            run.touchScreen(frame.width, frame.height, x, y, x, y, 80, false)
        })
        fun searchField() = service.windowsOn(originalDisplay).firstNotNullOfOrNull {
            it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { node -> node.isEditable }
        }
        assertNotNull("search field must receive focus", DeviceSupport.pollUntil(5_000) { searchField() })
        assertTrue(runBlocking { run.screenText(frame.width, frame.height, "Mika") })
        assertNotNull("typed text must appear in the focused search field", DeviceSupport.pollUntil(5_000) {
            searchField()?.takeIf { it.text?.toString() == "Mika" }
        })
        assertTrue(runBlocking { run.screenBack(frame.width, frame.height) })
        compose.onNodeWithTag("screen-control-toggle").performClick()
        assertNotNull(DeviceSupport.pollUntil(5_000) { true.takeIf { run.manualControl.state.value == ManualControl.State.Automatic } })
        // 接管前已在等待的模型回复必须丢弃，而非执行这次返回动作。
        model.feed("phone_back")
        val fresh = model.awaitCall()
        assertTrue(fresh.messages.toString().contains("The screen changed"))
        fit(run)
        model.feed("phone_observe")
        model.awaitCall()
        run.cancel()
        assertNotNull(DeviceSupport.pollUntil(15_000) { true.takeIf { DeviceTaskState.run(run.conversationId) == null } })
    }
}
