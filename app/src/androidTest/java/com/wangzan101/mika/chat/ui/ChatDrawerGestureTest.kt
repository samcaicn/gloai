package com.wangzan101.mika.chat.ui

import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.systemBarsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import com.wangzan101.mika.settings.ui.models.use

/** 通过真实触摸验证推开聊天页、半程取消、横向滚动优先、窄窗口/RTL 和返回层级。 */
@RunWith(AndroidJUnit4::class)
class ChatDrawerGestureTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "drawer-gesture-" + UUID.randomUUID())
    private val store = ConversationStore(dir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowWidth = mutableStateOf(360.dp)
    private val dark = mutableStateOf(false)
    private var chatX = 0f
    private var chatWidth = 0
    private var underlyingBacks = 0
    private var scrollPosition: () -> Int = { 0 }

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun show(
        rtl: Boolean = false, scrollable: Boolean = false, realChat: Boolean = false,
        readHistory: (() -> List<ConversationSummary>)? = null,
    ) {
        compose.runOnUiThread { compose.activity.systemBarsFor(dark = false) }
        store.append("sample", Turn("侧拉交互验收", "这是一条本地测试对话", "succeeded"))
        val vm = ChatViewModel(
            scope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
            io = Dispatchers.Main.immediate,
        )
        if (realChat) compose.runOnUiThread { vm.openConversation("sample") }
        compose.setContent {
            MikaTheme(dark = dark.value) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    BackHandler { underlyingBacks++ }
                    Box(Modifier.width(windowWidth.value).fillMaxHeight().testTag("drawer-host")) {
                        ChatWithDrawer(
                            vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                            conversations = readHistory ?: { store.list() }, conversationChanges = store.changes,
                            onSettings = {},
                        ) { open ->
                            Column(Modifier.fillMaxSize().onGloballyPositioned {
                                chatX = it.positionInRoot().x
                                chatWidth = it.size.width
                            }) {
                                if (realChat) {
                                    ChatScreen(
                                        vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false),
                                        reduceMotion = true, onOpenDrawer = open, openSettings = {},
                                    )
                                } else {
                                    Button(open, Modifier.testTag("open-drawer")) { Text("菜单") }
                                    Text("聊天内容保持原有宽度")
                                }
                                if (scrollable && !realChat) {
                                    val scroll = rememberScrollState(200)
                                    scrollPosition = { scroll.value }
                                    Box(Modifier.width(360.dp).height(180.dp).horizontalScroll(scroll).testTag("wide-code")) {
                                        Text("宽代码 ".repeat(100), Modifier.width(1500.dp).background(MikaTheme.colors.fill))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun cleanUp() { scope.cancel(); dir.deleteRecursively() }

    private fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertClosed() {
        compose.onNodeWithTag("drawer").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(0f, chatX, 1f) }
    }

    @Test fun menuPushesTheFullWidthChatAndExposedChatClosesIt() {
        show()
        val originalWidth = chatWidth
        compose.onNodeWithTag("open-drawer").performClick()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width
        compose.runOnIdle {
            assertEquals(drawerWidth.toFloat(), chatX, 1f)
            assertEquals(originalWidth, chatWidth)
            assertTrue("leave a 48dp dismissal target", originalWidth - drawerWidth >= 48 * context.resources.displayMetrics.density - 1)
        }
        compose.onNodeWithTag("drawer-host").performTouchInput { click(Offset(width - 12f, height / 2f)) }
        assertClosed()
    }

    @Test fun shortSlowDragMovesChatBeforeReleaseThenCancels() {
        show()
        val originalWidth = chatWidth
        compose.onNodeWithTag("drawer-host").performTouchInput {
            down(Offset(width * 0.18f, height * 0.55f))
            moveBy(Offset(width * 0.2f, 0f), delayMillis = 400)
        }
        compose.runOnIdle {
            assertTrue("chat should follow the held finger", chatX > 10f && chatX < originalWidth / 2f)
            assertEquals(originalWidth, chatWidth)
        }
        compose.onNodeWithTag("drawer-host").performTouchInput { advanceEventTime(300); up() }
        assertClosed()
    }

    @Test fun rightSwipeOpensAndLeftSwipeOnExposedChatCloses() {
        show()
        compose.onNodeWithTag("drawer-host").performTouchInput {
            swipe(Offset(width * 0.15f, height * 0.55f), Offset(width * 0.95f, height * 0.55f), 500)
        }
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.runOnIdle { assertTrue(chatX > chatWidth / 2f) }
        compose.onNodeWithTag("drawer-host").performTouchInput {
            swipe(Offset(width * 0.95f, height * 0.55f), Offset(width * 0.15f, height * 0.55f), 500)
        }
        assertClosed()
    }

    /** 停住后再松手以排除惯性,按实际拖出位置验证 40% 分界,并覆盖越过中点的情况。 */
    @Test fun slowReleaseOpensAtFortyPercentAndRemainsMonotonic() {
        show()
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        for (fraction in listOf(0.35f, 0.41f, 0.55f, 0.75f)) {
            compose.onNodeWithTag("drawer-host").performTouchInput {
                down(Offset(width * 0.1f, height * 0.55f))
                moveBy(Offset(drawerWidth * fraction + slop, 0f), delayMillis = 500)
            }
            compose.runOnIdle { assertEquals("held fraction $fraction", drawerWidth * fraction, chatX, 2f) }
            compose.onNodeWithTag("drawer-host").performTouchInput { advanceEventTime(350); up() }
            if (fraction < 0.4f) assertClosed() else {
                compose.runOnIdle { assertEquals("released fraction $fraction", drawerWidth.toFloat(), chatX, 1f) }
                compose.onNodeWithTag("drawer-host").performTouchInput { click(Offset(width - 12f, height / 2f)) }
                assertClosed()
            }
        }
    }

    @Test fun quickFlickStillOpensBeforeFortyPercent() {
        show()
        compose.onNodeWithTag("drawer-host").performTouchInput {
            swipe(Offset(width * 0.15f, height * 0.55f), Offset(width * 0.4f, height * 0.55f), 100)
        }
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width
        compose.runOnIdle { assertEquals(drawerWidth.toFloat(), chatX, 1f) }
    }

    @Test fun veryShortFastFlickDoesNotExpandTheDrawer() {
        show()
        compose.onNodeWithTag("drawer-host").performTouchInput {
            swipe(Offset(width * 0.15f, height * 0.55f), Offset(width * 0.23f, height * 0.55f), 50)
        }
        assertClosed()
    }

    @Test fun fastOpenAndCloseHaveVisibleSettlingFrames() {
        show()
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width.toFloat()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("drawer-host").performTouchInput {
                swipe(Offset(width * 0.15f, height * 0.55f), Offset(width * 0.4f, height * 0.55f), 100)
            }
            compose.mainClock.advanceTimeByFrame()
            val firstOpenFrame = chatX
            compose.runOnIdle { assertTrue("flick must not jump to open on its first frame", chatX > 0f && chatX < drawerWidth - 2f) }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { assertTrue("opening must still be easing after 80ms", chatX > firstOpenFrame && chatX < drawerWidth - 2f) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertEquals(drawerWidth, chatX, 1f) }

            compose.onNodeWithTag("drawer-host").performTouchInput {
                swipe(Offset(width * 0.95f, height * 0.55f), Offset(width * 0.7f, height * 0.55f), 100)
            }
            compose.mainClock.advanceTimeByFrame()
            val firstCloseFrame = chatX
            compose.runOnIdle { assertTrue("flick must not jump to closed on its first frame", chatX > 2f && chatX < drawerWidth) }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { assertTrue("closing must still be easing after 80ms", chatX < firstCloseFrame && chatX > 2f) }
            compose.mainClock.advanceTimeBy(300)
            assertClosed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun openingFlickCanBeCaughtAndDraggedWithoutAJump() {
        show()
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width.toFloat()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("drawer-host").performTouchInput {
                swipe(Offset(width * 0.15f, height * 0.55f), Offset(width * 0.4f, height * 0.55f), 100)
            }
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val beforeCatch = chatX
            compose.onNodeWithTag("drawer-host").performTouchInput {
                down(Offset(width * 0.95f, height * 0.55f))
                moveBy(Offset(-20f, 0f), delayMillis = 16)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(beforeCatch - 20f, chatX, 2f) }
            compose.onNodeWithTag("drawer-host").performTouchInput { advanceEventTime(350); up() }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertEquals(drawerWidth, chatX, 1f) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun rtlSlowReleaseAlsoOpensAtFortyPercent() {
        show(rtl = true)
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        compose.onNodeWithTag("drawer-host").performTouchInput {
            down(Offset(width * 0.9f, height * 0.55f))
            moveBy(Offset(-(drawerWidth * 0.41f + slop), 0f), delayMillis = 500)
            advanceEventTime(350)
            up()
        }
        compose.runOnIdle { assertEquals(-drawerWidth.toFloat(), chatX, 1f) }
    }

    @Test fun horizontalCodeScrollKeepsTheDrawerClosed() {
        show(scrollable = true)
        compose.onNodeWithTag("wide-code").performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.7f, height / 2f), 500)
        }
        compose.runOnIdle { assertTrue("code consumes the rightward scroll first", scrollPosition() < 200) }
        assertClosed()
    }

    @Test fun openDrawerAdaptsToNarrowerWindowAndMirrorsInRtl() {
        show(rtl = true)
        compose.onNodeWithTag("open-drawer").performClick()
        compose.runOnIdle { windowWidth.value = 240.dp }
        compose.waitForIdle()
        val drawerWidth = compose.onNodeWithTag("drawer").fetchSemanticsNode().size.width
        compose.runOnIdle {
            assertEquals(-drawerWidth.toFloat(), chatX, 1f)
            assertTrue("narrow window must keep a 48dp dismissal target", chatWidth - abs(chatX) >= 48 * context.resources.displayMetrics.density - 1)
        }
        back()
        assertClosed()
    }

    @Test fun backClosesDrawerAndClosedChatPassesBackToItsParent() {
        show()
        compose.onNodeWithTag("open-drawer").performClick()
        back()
        assertClosed()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
        back()
        assertClosed()
        compose.runOnIdle { assertEquals(1, underlyingBacks) }
    }

    @Test fun edgeBackFollowsProgressCancelsAndCanBeStartedAgain() {
        show()
        compose.onNodeWithTag("open-drawer").performClick()
        compose.waitForIdle()
        val expandedX = chatX
        val dispatcher = compose.activity.onBackPressedDispatcher
        fun event(progress: Float) = BackEventCompat(700f, 700f, progress, BackEventCompat.EDGE_RIGHT)
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(0.15f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expandedX * 0.85f, chatX, 1f) }
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(0.6f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expandedX * 0.4f, chatX, 1f) }
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expandedX, chatX, 1f) }
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(0.4f)) }
        compose.waitForIdle()
        back()
        assertClosed()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
    }

    @Test fun slowHistoryReadDoesNotBlockTheFirstDrawerMovement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            show(readHistory = {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                store.list()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("open-drawer").performClick()
            compose.onNodeWithTag("drawer").assertIsDisplayed()
            compose.runOnIdle { assertTrue(chatX > chatWidth / 2f) }
        } finally {
            release.countDown()
        }
    }

    @Test fun realChatRemainsUsableAfterOpeningAndClosingTheDrawer() {
        show(realChat = true)
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val shots = File(context.getExternalFilesDir(null), "drawer-review").also { it.mkdirs() }
            File(shots, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        capture("01-chat")
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        capture("02-sidebar")
        back()
        compose.onNodeWithTag("composer-field").assertIsDisplayed()
        capture("03-returned")
        compose.runOnUiThread { dark.value = true; compose.activity.systemBarsFor(dark = true) }
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        capture("04-dark-sidebar")
        back()
        compose.onNodeWithTag("composer-field").assertIsDisplayed()
    }
}
