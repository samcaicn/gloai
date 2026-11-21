package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import com.wangzan101.mika.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme

/** 两个槽位在抽屉和双栏间移动，不重新创建聊天和列表的 remember/rememberSaveable 状态。 */
@Composable
internal fun AdaptiveChatNavigation(
    layout: AdaptiveLayout,
    drawerState: DrawerState? = null,
    modifier: Modifier = Modifier,
    drawerContent: @Composable () -> Unit,
    sideContent: (@Composable () -> Unit)? = null,
    drawerGesturesEnabled: Boolean = true,
    compactContent: (@Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var requestedWidth by rememberSaveable { mutableStateOf<Float?>(null) }
    val navigationWidth = splitNavigationWidth(layout, requestedWidth)
    val density = LocalDensity.current.density
    val resizeLabel = stringResource(R.string.screen_resize_panes)
    val drag = rememberDraggableState { delta -> requestedWidth = splitNavigationWidth(layout, splitNavigationWidth(layout, requestedWidth) + delta / density) }
    val latestDrawer = rememberUpdatedState(drawerContent)
    val latestContent = rememberUpdatedState(content)
    val navigationStates = rememberSaveableStateHolder()
    val navigation = remember { movableContentOf {
        navigationStates.SaveableStateProvider("navigation") { latestDrawer.value() }
    } }
    val chat = remember { movableContentOf { latestContent.value() } }
    Box(modifier.fillMaxSize()) {
        if (layout.hasNavigation) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(navigationWidth.dp).fillMaxHeight().clipToBounds().testTag("permanent-navigation")) {
                    if (sideContent != null) sideContent() else navigation()
                }
                if (layout.fixedNavigation) Spacer(Modifier.width(layout.hingeGap.dp))
                else Box(
                    Modifier.width(SplitHandleWidth.dp).fillMaxHeight().testTag("pane-divider")
                        .draggable(drag, Orientation.Horizontal)
                        .semantics {
                            contentDescription = resizeLabel
                            progressBarRangeInfo = ProgressBarRangeInfo(navigationWidth, 280f..(layout.content.width - 320f - SplitHandleWidth).coerceAtLeast(280f))
                            setProgress { requestedWidth = splitNavigationWidth(layout, it); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MikaTheme.colors.hair))
                    Box(Modifier.width(3.dp).height(48.dp).background(MikaTheme.colors.ink3, RoundedCornerShape(2.dp)))
                }
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().testTag("navigation-detail"), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { chat() }
                }
            }
        } else if (!drawerGesturesEnabled) {
            chat()
        } else if (compactContent != null) {
            compactContent(navigation, chat)
        } else {
            ModalNavigationDrawer(
                drawerContent = { navigation() },
                drawerState = requireNotNull(drawerState),
                scrimColor = MikaTheme.colors.floatShadow.copy(alpha = 0.28f),
            ) {
                chat()
            }
        }
    }
}
