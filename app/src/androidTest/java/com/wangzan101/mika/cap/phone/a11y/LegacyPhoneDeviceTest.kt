package com.wangzan101.mika.cap.phone.a11y

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.Verification
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 走一遍真实的内置助手、虚拟屏和无障碍路径,不用模型,也不往外发消息。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31, maxSdkVersion = 32)
class LegacyPhoneDeviceTest {
    @Test
    fun usbHelperOpensReadsAndTypesOnLegacyDisplay() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phone = PhoneServices.of(context)
        val script = File(context.getExternalFilesDir(null), HelperHandoff.USB_SCRIPT)
        if (phone.hub.shell.value == null) {
            phone.shellManager.usbScript.refresh()
            assertNotNull("USB helper script", DeviceSupport.pollUntil(5_000) {
                script.takeIf { it.exists() && it.readText().contains(context.applicationInfo.sourceDir) }
            })
            DeviceSupport.sh("sh ${script.absolutePath}")
        }
        val shell = withTimeoutOrNull(15_000) { phone.hub.shell.filterNotNull().first() }
            ?: error("USB helper did not hand off: ${phone.shellControl.status.value}")
        assertEquals(2000, shell.identity()[0])

        val display = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val shown = context.getSystemService(DisplayManager::class.java).getDisplay(display.displayId)
            assertEquals("legacy display must be public", 0, shown.flags and Display.FLAG_PRIVATE)
            val testPackage = instrumentation.context.packageName
            assertEquals("launch test app", ShellService.START_OK, shell.startApp(display.displayId, testPackage))
            assertNotNull("rendered frame", DeviceSupport.pollUntil(5_000) { display.latestFramePng() })
            val service = withTimeoutOrNull(15_000) { ScreenAccessibility.instance.filterNotNull().first() }
                ?: error("Helper/display work but accessibility service is not connected")
            val screen = AndroidScreen(service, shell, DeviceSupport.ready(display))
            DeviceSupport.sh("am start --display ${display.displayId} -n $testPackage/${FieldsTestActivity::class.java.name}")
            val snapshot = DeviceSupport.pollUntil(5_000) {
                runBlocking { screen.snapshot() }.takeIf { s -> s.elements.any { it.editable } }
            }
            assertNotNull("test fields readable", snapshot)
            assertEquals(testPackage, snapshot!!.packageName)
            val reveal = snapshot.elements.first { it.name == "假搜索框" && it.clickable }
            val tapped = screen.tapRef(reveal.ref, snapshot.snapshotId)
            assertTrue("tap result: $tapped", tapped is ActionResult.Ok)
            val revealed = screen.snapshot()
            val field = revealed.elements.first { it.editable && it.name == "真正的输入框" }
            val typed = screen.typeText(field.ref, revealed.snapshotId, "Mika 兼容输入测试")
            assertTrue("type result: $typed", typed is ActionResult.Ok)
            assertEquals(Verification.MATCH, (typed as ActionResult.Ok).verification)
            val after = screen.snapshot()
            assertTrue(after.elements.any { it.editable && it.name == "Mika 兼容输入测试" })
        } finally {
            display.release()
        }
    }
}
