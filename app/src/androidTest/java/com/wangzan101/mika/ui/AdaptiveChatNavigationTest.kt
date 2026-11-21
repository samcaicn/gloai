package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdaptiveChatNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingPanesKeepsDraftScrollAndCompositionThenRestoresAfterRecreation() = verifyStateContinuity(false)

    @Test fun customDrawerKeepsDraftScrollAndCompositionAcrossSplitAndRecreation() = verifyStateContinuity(true)

    private fun verifyStateContinuity(customDrawer: Boolean) {
        val compact = adaptiveLayout(400f, 900f, emptyList(), true)
        val book = adaptiveLayout(1000f, 900f,
            listOf(FoldObstacle(PaneBounds(420f, 0f, 440f, 900f), true)), true)
        var layout by mutableStateOf(compact)
        var showScreen by mutableStateOf(false)
        lateinit var list: LazyListState
        var mounts = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 900.dp))) {
                AdaptiveChatNavigation(layout, rememberDrawerState(DrawerValue.Closed),
                    drawerContent = { Box(Modifier.width(280.dp)) { Text("Conversations") } },
                    compactContent = if (customDrawer) ({ navigation, detail ->
                        ChatNavigationDrawer(
                            drawerState = rememberChatDrawerState(DrawerValue.Closed),
                            drawerContent = navigation,
                            gesturesEnabled = true,
                            contentOffset = { 0f },
                            onDismiss = {},
                        ) { detail() }
                    }) else null,
                    sideContent = if (showScreen) ({ Text("Live screen", Modifier.testTag("live-screen")) }) else null,
                ) {
                    var draft by rememberSaveable { mutableStateOf("") }
                    list = rememberLazyListState()
                    DisposableEffect(Unit) { mounts++; onDispose {} }
                    Column(Modifier.fillMaxSize()) {
                        BasicTextField(draft, { draft = it }, Modifier.testTag("draft"))
                        LazyColumn(Modifier.weight(1f).testTag("history"), state = list) {
                            items(100) { Text("Message $it") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("draft").performTextReplacement("Keep this unsent draft")
        compose.onNodeWithTag("history").performScrollToIndex(45)
        compose.runOnIdle { layout = book }
        compose.onNodeWithTag("permanent-navigation").assertWidthIsEqualTo(420.dp)
        compose.onNodeWithTag("draft").assertTextEquals("Keep this unsent draft")
        compose.runOnIdle {
            assertEquals(45, list.firstVisibleItemIndex)
            assertEquals(1, mounts)
            showScreen = true
        }
        compose.onNodeWithTag("live-screen").assertExists()
        compose.onNodeWithTag("draft").assertTextEquals("Keep this unsent draft")
        compose.runOnIdle {
            assertEquals(45, list.firstVisibleItemIndex)
            assertEquals(1, mounts)
            layout = compact
        }
        compose.onNodeWithTag("live-screen").assertDoesNotExist()
        compose.onNodeWithTag("permanent-navigation").assertDoesNotExist()
        compose.onNodeWithTag("draft").assertTextEquals("Keep this unsent draft")
        compose.runOnIdle { assertEquals(1, mounts); assertEquals(45, list.firstVisibleItemIndex) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("draft").assertTextEquals("Keep this unsent draft")
        compose.runOnIdle { assertEquals(45, list.firstVisibleItemIndex) }
    }
}
