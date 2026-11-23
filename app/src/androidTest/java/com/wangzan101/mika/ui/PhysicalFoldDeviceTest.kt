package com.wangzan101.mika.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import com.wangzan101.mika.chat.ui.Composer
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.settings.ui.models.edit
import com.wangzan101.mika.settings.ui.models.use

/** 人工配合的物理姿态验收；仅显式传入 -e physicalFold true 时执行，不模拟设备状态。 */
class PhysicalFoldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = listOf("onboarding" to "model_setup_done", "phone_setup" to "guide_seen", "app_language" to "language_prompted")
    private var saved = emptyList<Boolean?>()
    private val draft = "物理折叠回归测试：合屏、半折、展开后保留（未发送）"

    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("physicalFold") == "true")
            saved = prefs.map { (file, key) -> context.getSharedPreferences(file, Context.MODE_PRIVATE).let {
                if (it.contains(key)) it.getBoolean(key, false) else null
            } }
            prefs.forEach { (file, key) -> context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putBoolean(key, true).commit() }
        }
        override fun after() {
            if (saved.isEmpty()) return
            prefs.forEachIndexed { i, (file, key) ->
                context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().apply {
                    saved[i]?.let { putBoolean(key, it) } ?: remove(key)
                }.commit()
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitPhysical(name: String) {
        Log.i(TAG, "WAIT_$name")
        val until = SystemClock.uptimeMillis() + 180_000
        while (SystemClock.uptimeMillis() < until) {
            val dump = DeviceSupport.sh("dumpsys device_state")
            assertTrue("Physical test must not use a state override", dump.contains("mOverrideState=Optional.empty"))
            if (dump.lineSequence().any { "mBaseState=" in it && "name='$name'" in it }) {
                Log.i(TAG, dump)
                SystemClock.sleep(1_500)
                return
            }
            SystemClock.sleep(300)
        }
        throw AssertionError("Timed out waiting for physical $name")
    }

    private fun assertDraft() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("composer-field")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-field").assertIsDisplayed().assertTextEquals(draft)
    }

    private fun record(stage: String) {
        val config = compose.activity.resources.configuration
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(context.getExternalFilesDir(null), "fold-verification/$stage.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$stage PASS ${config.screenWidthDp}x${config.screenHeightDp}dp screenshot=${file.absolutePath}")
    }

    @Test fun physicalCloseHalfOpenAndReopenKeepDraftAndAvoidHinge() {
        waitPhysical("OPENED")
        compose.runOnUiThread { (context.applicationContext as MikaApp).chat.newConversation() }
        compose.onNodeWithTag("composer-field").performTextReplacement(draft)
        compose.onNodeWithTag("permanent-navigation").assertIsDisplayed()
        record("opened-before")

        waitPhysical("CLOSED")
        assertDraft()
        compose.onNodeWithTag("permanent-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_menu)).assertIsDisplayed()
        record("closed")

        waitPhysical("HALF_OPENED")
        assertDraft()
        val activity = compose.activity
        val info = runBlocking {
            withTimeout(15_000) {
                withContext(Dispatchers.Main) {
                    WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).first { info ->
                        info.displayFeatures.filterIsInstance<FoldingFeature>().any { it.state == FoldingFeature.State.HALF_OPENED }
                    }
                }
            }
        }
        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().first { it.state == FoldingFeature.State.HALF_OPENED }
        Log.i(TAG, "HALF_OPENED feature=$fold")
        assertTrue("Half-open hinge should separate content", fold.isSeparating)
        val composer = compose.onNodeWithTag("composer-field").fetchSemanticsNode().boundsInWindow
        val b = fold.bounds
        val vertical = fold.orientation == FoldingFeature.Orientation.VERTICAL
        fun avoidsHinge(r: androidx.compose.ui.geometry.Rect) = if (vertical) r.right <= b.left || r.left >= b.right
            else r.bottom <= b.top || r.top >= b.bottom
        assertTrue("Composer $composer crosses hinge $b", avoidsHinge(composer))
        val hasNavigation = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("permanent-navigation")).fetchSemanticsNodes().isNotEmpty()
        if (hasNavigation) {
            val side = compose.onNodeWithTag("permanent-navigation").fetchSemanticsNode().boundsInWindow
            val detail = compose.onNodeWithTag("navigation-detail").fetchSemanticsNode().boundsInWindow
            assertTrue("Sidebar $side crosses hinge $b", avoidsHinge(side))
            assertTrue("Chat $detail crosses hinge $b", avoidsHinge(detail))
        } else {
            val menu = compose.onNodeWithContentDescription(context.getString(R.string.chat_menu)).fetchSemanticsNode().boundsInWindow
            assertTrue("Chat header $menu crosses hinge $b", avoidsHinge(menu))
            assertTrue("Header and composer must stay in the same intact pane", if (vertical)
                (menu.right <= b.left) == (composer.right <= b.left) else (menu.bottom <= b.top) == (composer.bottom <= b.top))
        }
        if (!vertical) assertFalse("Horizontal half-fold should use one intact pane", hasNavigation)
        record("half-opened")

        waitPhysical("OPENED")
        assertDraft()
        compose.onNodeWithTag("permanent-navigation").assertIsDisplayed()
        record("opened-after")
    }

    companion object { private const val TAG = "PhysicalFoldCheck" }
}
