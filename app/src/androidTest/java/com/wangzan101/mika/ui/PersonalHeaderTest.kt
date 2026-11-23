package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.personal.PersonalPhonePreview
import com.wangzan101.mika.personal.PersonalReplayFrame
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.wangzan101.mika.personal.ui.PersonalHeader
import com.wangzan101.mika.personal.ui.PersonalPreviewPlayer
import com.wangzan101.mika.settings.ui.models.use

@RunWith(AndroidJUnit4::class)
class PersonalHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phonePreviewAndProgressOpenDifferentViewsForTheSameTask() {
        val first = PersonalTask("one", "first", "查网页", "查网页", "source", 0)
        val second = PersonalTask("two", "second", "查日程", "查日程", "source", 1, detail = "正在查看日程")
        val preview = MutableStateFlow<PersonalPhonePreview?>(null)
        val frame = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var openedTask: String? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val liveLabel = context.getString(R.string.personal_replay_live)
        val returnLiveLabel = context.getString(R.string.personal_replay_return_live)
        val cache = context.cacheDir
        val olderFrame = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val saved = File.createTempFile("personal-preview", ".jpg", cache)
        saved.outputStream().use { olderFrame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val recordings = listOf(PersonalReplayFrame(1_000, saved), PersonalReplayFrame(11_000, saved))
        compose.setContent {
            MikaTheme {
                val latest by preview.collectAsState()
                var openedPreview by remember { mutableStateOf<PersonalPhonePreview?>(null) }
                PersonalHeader(listOf(first, second), preview, onOpenDrawer = {}, onActivity = {}, onTask = {
                    openedTask = it.id
                }, onOpenPreview = { openedPreview = it })
                openedPreview?.let { opened ->
                    PersonalPreviewPlayer(recordings, latest?.takeIf { it.conversationId == opened.conversationId }?.frame,
                        isLive = latest != null, onDismiss = { openedPreview = null })
                }
            }
        }
        compose.onNodeWithTag("personal-phone-preview").assertDoesNotExist()
        val idleAvatar = compose.onNodeWithTag("personal-profile").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { preview.value = PersonalPhonePreview("unrelated", frame) }
        compose.onNodeWithTag("personal-phone-preview").assertDoesNotExist()
        compose.runOnIdle { preview.value = PersonalPhonePreview(second.conversationId, frame) }
        compose.onNodeWithText("正在查看日程").assertExists()
        val avatar = compose.onNodeWithTag("personal-profile").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Mika").fetchSemanticsNode().boundsInRoot
        val thumbnail = compose.onNodeWithTag("personal-phone-preview").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("personal-task-progress").fetchSemanticsNode().boundsInRoot
        assertEquals(idleAvatar.center.x, avatar.center.x, 1f)
        assertTrue(thumbnail.left > avatar.right && avatar.center.y in thumbnail.top..thumbnail.bottom)
        assertTrue(thumbnail.height > name.bottom - avatar.top)
        assertEquals(2f, thumbnail.height / thumbnail.width, 0.04f)
        assertTrue(thumbnail.bottom < progress.top)
        compose.onNodeWithTag("personal-task-progress").performClick()
        compose.runOnIdle { assertEquals(second.id, openedTask) }
        compose.onNodeWithTag("personal-phone-preview").performClick()
        val pane = compose.onNodeWithTag("personal-preview-image")
        val timeline = compose.onNodeWithTag("personal-preview-timeline")
        compose.onNodeWithText(liveLabel).assertExists()
        compose.onNodeWithText("从头看").assertDoesNotExist()
        assertTrue(pane.fetchSemanticsNode().boundsInRoot.bottom <= timeline.fetchSemanticsNode().boundsInRoot.top)
        val portrait = pane.captureToImage().toPixelMap()
        val paintedHeight = (0 until portrait.height).count { y ->
            val pixel = portrait[portrait.width / 2, y]
            pixel.red > 0.9f && pixel.green < 0.1f
        }
        assertTrue("portrait frame only covers $paintedHeight px of ${portrait.height}", paintedHeight > portrait.height * 0.8f)

        val landscapeFrame = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        compose.runOnIdle { preview.value = PersonalPhonePreview(second.conversationId, landscapeFrame) }
        val landscape = pane.captureToImage().toPixelMap()
        val paintedWidth = (0 until landscape.width).count { x ->
            val pixel = landscape[x, landscape.height / 2]
            pixel.red > 0.9f && pixel.green < 0.1f
        }
        assertTrue("landscape frame only covers $paintedWidth px of ${landscape.width}", paintedWidth > landscape.width * 0.9f)
        timeline.performTouchInput {
            swipe(Offset(width * 0.95f, center.y), Offset(width * 0.1f, center.y))
        }
        compose.onNodeWithText(returnLiveLabel).assertExists()
        compose.waitUntil(5_000) {
            val pixels = pane.captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].blue > 0.9f
        }
        compose.onNodeWithText(returnLiveLabel).performClick()
        compose.onNodeWithText(liveLabel).assertExists()
        val live = pane.captureToImage().toPixelMap()
        assertTrue(live[live.width / 2, live.height / 2].red > 0.9f)
        compose.runOnIdle { preview.value = null }
        timeline.assertExists()
        compose.onNodeWithTag("personal-phone-preview").assertDoesNotExist()
        saved.delete()
    }
}
