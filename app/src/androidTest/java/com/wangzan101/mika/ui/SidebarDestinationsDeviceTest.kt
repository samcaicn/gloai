package com.wangzan101.mika.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** 在展开的设备上验证真实侧栏入口、页面布局、系统返回和聊天草稿。 */
class SidebarDestinationsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = listOf("onboarding" to "model_setup_done", "phone_setup" to "guide_seen", "app_language" to "language_prompted")
    private var saved = emptyList<Boolean?>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            assumeTrue(context.resources.configuration.let { it.screenWidthDp >= 640 && it.screenHeightDp >= 480 })
            saved = preferences.map { (file, key) ->
                context.getSharedPreferences(file, Context.MODE_PRIVATE).let { if (it.contains(key)) it.getBoolean(key, false) else null }
            }
            preferences.forEach { (file, key) -> context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putBoolean(key, true).commit() }
        }
        override fun after() {
            preferences.forEachIndexed { index, (file, key) ->
                context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().apply {
                    saved[index]?.let { putBoolean(key, it) } ?: remove(key)
                }.commit()
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun open(label: Int) {
        compose.onNode((hasText(context.getString(label)) or hasContentDescription(context.getString(label))) and hasAnyAncestor(hasTestTag("drawer"))).performClick()
        compose.onNodeWithTag("permanent-navigation").assertIsDisplayed()
        val navigation = compose.onNodeWithTag("permanent-navigation").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("navigation-detail").fetchSemanticsNode().boundsInRoot
        assertTrue("detail must stay beside the sidebar", detail.left >= navigation.right && detail.width > 0)
    }

    @Test fun sidebarPagesStayBesideNavigationAndBackRestoresChatDraft() {
        compose.runOnUiThread { (context.applicationContext as MikaApp).chat.newConversation() }
        compose.onNodeWithTag("composer-field").performTextReplacement("保留这段折叠屏草稿")
        open(R.string.drawer_personal)
        compose.onNodeWithTag("personal-screen").assertIsDisplayed()
        open(R.string.drawer_plans)
        compose.onNodeWithTag("composer-field").assertDoesNotExist()
        compose.onNodeWithTag("plans-segment-running").assertIsDisplayed()
        open(R.string.memory_and_skills)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("segment-Skills")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("segment-Skills").assertIsDisplayed().performClick()
        open(R.string.drawer_browser)
        compose.onNodeWithTag("browser-search").assertIsDisplayed()
        // 旋转到窄窗口后只显示功能页，回到宽窗口时恢复侧栏且仍停留在浏览器。
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("permanent-navigation")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("browser-search").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("permanent-navigation")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browser-search").assertIsDisplayed()
        // 首帧出现不代表新 Activity 已恢复；等系统返回回调恢复后再发送真实返回键。
        compose.waitUntil(10_000) { compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("composer-field").assertTextEquals("保留这段折叠屏草稿")
        compose.onNodeWithTag("permanent-navigation").assertIsDisplayed()
        // 在功能页点新对话也必须回到右侧聊天，而不是只改隐藏的 ViewModel。
        open(R.string.drawer_plans)
        open(R.string.chat_new)
        compose.onNodeWithTag("composer-field").assertIsDisplayed()
    }
}
