package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.chat.ui.AgentPip
import com.wangzan101.mika.chat.ui.PipUi

/** 小窗按画面比例定大小:横向网页看得到左右两端,长屏手机的画面看得到最底下。 */
@RunWith(AndroidJUnit4::class)
class AgentPipWebFrameTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeWebFrameShowsBothEdges() = checkLandscape(web = true)

    @Test fun landscapePhoneFrameShowsBothEdges() = checkLandscape(web = false)

    private fun checkLandscape(web: Boolean) {
        compose.mainClock.autoAdvance = false
        val frame = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, 320f, 800f, Paint().apply { color = Color.RED })
            drawRect(960f, 0f, 1280f, 800f, Paint().apply { color = Color.BLUE })
        }
        compose.setContent {
            AgentPip(PipUi(visible = true), frame, webContent = web, reduceMotion = true,
                topInset = 0f, bottomInset = 120f, onExpand = {}, onCollapse = {}, onAttention = {})
        }
        compose.waitForIdle()
        val size = compose.onNodeWithTag("pip-window").fetchSemanticsNode().size
        assertTrue("landscape preview stayed portrait: $size", size.width > size.height)
        val pixels = capture().toPixelMap()
        val y = pixels.height / 2
        val left = pixels[pixels.width / 10, y]
        val right = pixels[pixels.width * 9 / 10, y]
        assertTrue("left side of web viewport was cropped: $left", left.red > .8f && left.blue < .2f)
        assertTrue("right side of web viewport was cropped: $right", right.blue > .8f && right.red < .2f)
        frame.recycle()
    }

    @Test fun tallPhoneFrameShowsItsBottom() {
        compose.mainClock.autoAdvance = false
        // 长屏手机的画面(高约为宽的 2.2 倍):最底下一截涂成绿色,小窗按画面比例定高,底部不能被裁掉
        val frame = Bitmap.createBitmap(360, 794, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 714f, 360f, 794f, Paint().apply { color = Color.GREEN })
        }
        compose.setContent {
            AgentPip(PipUi(visible = true), frame, webContent = false, reduceMotion = true,
                topInset = 0f, bottomInset = 120f, onExpand = {}, onCollapse = {}, onAttention = {})
        }
        compose.waitForIdle()
        val pixels = capture().toPixelMap()
        val bottom = pixels[pixels.width / 2, pixels.height - 3]
        assertTrue("bottom of phone frame was cropped: $bottom", bottom.green > .8f && bottom.red < .2f)
        frame.recycle()
    }

    @Test fun phoneFrameRotatesWhileCollapsedAndExpanded() {
        var frame by mutableStateOf(Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888))
        var expanded by mutableStateOf(false)
        compose.setContent {
            AgentPip(PipUi(visible = true, expanded = expanded), frame, webContent = false, reduceMotion = true,
                topInset = 0f, bottomInset = 120f, onExpand = {}, onCollapse = {}, onAttention = {})
        }
        fun check(landscape: Boolean) {
            compose.waitForIdle()
            val size = compose.onNodeWithTag("pip-window").fetchSemanticsNode().size
            assertTrue("unexpected preview shape: $size", (size.width > size.height) == landscape)
        }
        check(false)
        compose.runOnIdle { frame = Bitmap.createBitmap(800, 360, Bitmap.Config.ARGB_8888) }
        check(true)
        compose.runOnIdle { expanded = true }
        check(true)
        compose.runOnIdle { frame = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888) }
        check(false)
    }

    private fun capture(): ImageBitmap =
        run {
            compose.waitForIdle()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("pip-frame", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("pip-frame", useUnmergedTree = true).captureToImage()
        }
}
