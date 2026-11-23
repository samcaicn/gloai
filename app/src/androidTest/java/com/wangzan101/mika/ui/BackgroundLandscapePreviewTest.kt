package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.display.RotationTestActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.wangzan101.mika.chat.ui.AgentPip
import com.wangzan101.mika.chat.ui.PipUi
import com.wangzan101.mika.settings.ui.models.use

/** 真实后台 Activity → ImageReader → Task 预览帧 → 应用内画中画,检查整条链路。 */
@RunWith(AndroidJUnit4::class)
class BackgroundLandscapePreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeActivityFillsLandscapePip() {
        val agent = DeviceSupport.newAgent()
        try {
            compose.setContent {
                val frame by agent.display.frames.collectAsState()
                AgentPip(PipUi(visible = true, expanded = true), frame, webContent = false, reduceMotion = true,
                    topInset = 0f, bottomInset = 120f, onExpand = {}, onCollapse = {}, onAttention = {})
            }
            val pkg = InstrumentationRegistry.getInstrumentation().context.packageName
            DeviceSupport.sh("am start -W --display ${agent.display.displayId} -n $pkg/${RotationTestActivity::class.java.name} --ei orientation 0")
            // 转屏时宽高会先变,像素仍可能是旧帧;等后台 Activity 的四个象限真的画好。
            compose.waitUntil(10_000) { agent.display.frames.value?.let(::readyFrame) == true }
            compose.waitForIdle()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("pip-frame", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            val captured = compose.onNodeWithTag("pip-frame", useUnmergedTree = true).captureToImage()
            val pixels = captured.toPixelMap()
            assertTrue("portrait preview", pixels.width > pixels.height)
            val red = pixels[pixels.width / 4, pixels.height / 4]
            val green = pixels[pixels.width * 3 / 4, pixels.height / 4]
            val blue = pixels[pixels.width / 4, pixels.height * 3 / 4]
            val yellow = pixels[pixels.width * 3 / 4, pixels.height * 3 / 4]
            assertTrue("top-left missing", red.red > .9f && red.green < .1f)
            assertTrue("top-right missing", green.green > .9f && green.red < .1f)
            assertTrue("bottom-left missing", blue.blue > .9f && blue.red < .1f)
            assertTrue("bottom-right black or cropped", yellow.red > .9f && yellow.green > .9f && yellow.blue < .1f)
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "preview-tests").apply { mkdirs() }
            File(dir, "background-landscape-pip.png").outputStream().use {
                captured.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { agent.release() }
    }

    private fun readyFrame(frame: Bitmap): Boolean {
        if (frame.width <= frame.height) return false
        val red = frame.getPixel(frame.width / 4, frame.height / 4)
        val green = frame.getPixel(frame.width * 3 / 4, frame.height / 4)
        val blue = frame.getPixel(frame.width / 4, frame.height * 3 / 4)
        val yellow = frame.getPixel(frame.width * 3 / 4, frame.height * 3 / 4)
        return Color.red(red) > 240 && Color.green(red) < 15 &&
            Color.green(green) > 240 && Color.red(green) < 15 &&
            Color.blue(blue) > 240 && Color.red(blue) < 15 &&
            Color.red(yellow) > 240 && Color.green(yellow) > 240 && Color.blue(yellow) < 15
    }
}
