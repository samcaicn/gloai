package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.wangzan101.mika.ui.theme.MikaTheme

internal val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayout(PaneBounds(0f, 0f, 0f, 0f)) }

/** 跟随窗口和姿态变化；按生命周期收集，后台时取消监听。系统栏和铰链都是窗口坐标。 */
@Composable
internal fun AdaptiveWindow(navigation: Boolean = false, content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var folds by remember(activity, lifecycle) { mutableStateOf<List<FoldingFeature>>(emptyList()) }
    LaunchedEffect(activity, lifecycle) {
        if (activity != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                folds = info.displayFeatures.filterIsInstance<FoldingFeature>().filter {
                    it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
                }
            }
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val left = bars.getLeft(density, direction)
        val top = bars.getTop(density)
        val width = (constraints.maxWidth - left - bars.getRight(density, direction)).coerceAtLeast(0)
        val height = (constraints.maxHeight - top - bars.getBottom(density)).coerceAtLeast(0)
        val scale = density.density
        val obstacles = folds.map {
            val b = it.bounds
            FoldObstacle(
                PaneBounds((b.left - origin.x - left) / scale, (b.top - origin.y - top) / scale,
                    (b.right - origin.x - left) / scale, (b.bottom - origin.y - top) / scale),
                it.orientation == FoldingFeature.Orientation.VERTICAL,
            )
        }
        val layout = adaptiveLayout(width / scale, height / scale, obstacles, navigation)
        val pane = layout.content
        val right = (width / scale - pane.right).coerceAtLeast(0f).dp
        val bottom = (height / scale - pane.bottom).coerceAtLeast(0f).dp
        // 单栏没有折痕时，继续由原页面自行处理系统栏与键盘。
        val protection = if (layout.hasNavigation || pane != PaneBounds(0f, 0f, width / scale, height / scale)) {
            Modifier.background(MikaTheme.colors.bg).windowInsetsPadding(bars)
                .absolutePadding(left = pane.left.dp, top = pane.top.dp, right = right, bottom = bottom)
                .consumeWindowInsets(PaddingValues(top = pane.top.dp, bottom = bottom)).clipToBounds()
        } else Modifier
        Box(Modifier.fillMaxSize().then(protection)) {
            CompositionLocalProvider(LocalAdaptiveLayout provides layout) { content() }
        }
    }
}
