package com.wangzan101.mika.ui

/** 全部使用窗口内容区域内的 dp；不依赖设备型号、屏幕方向或 Android Rect，方便验证边界。 */
internal data class PaneBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

internal data class FoldObstacle(val bounds: PaneBounds, val vertical: Boolean)

internal data class AdaptiveLayout(
    val content: PaneBounds,
    val navigationWidth: Float = 0f,
    val hingeGap: Float = 0f,
    val fixedNavigation: Boolean = false,
) {
    val hasNavigation get() = navigationWidth > 0f
}

/**
 * 640dp 起常驻 280dp 侧栏，840dp 起侧栏加宽到 304dp。竖折屏按真实铰链分栏。
 * 至少给列表 280dp、聊天 360dp，兼顾展开后仍不足 840dp 的折叠屏。
 * 横向折痕、多个铰链或任意一栏太窄时，选择最大的完整区域；同面积优先左侧/上侧。
 * 非分隔、无遮挡的平展折痕由调用方过滤，不应浪费可用空间。
 */
internal fun adaptiveLayout(width: Float, height: Float, folds: List<FoldObstacle>, navigation: Boolean): AdaptiveLayout {
    val full = PaneBounds(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    val relevant = folds.filter { fold ->
        val b = fold.bounds
        if (fold.vertical) b.left < width && b.right > 0 && b.bottom > 0 && b.top < height
        else b.top < height && b.bottom > 0 && b.right > 0 && b.left < width
    }
    if (navigation && relevant.size == 1) {
        val fold = relevant.single()
        val b = fold.bounds
        if (fold.vertical && b.left >= 280f && width - b.right >= 360f && height >= 480f) {
            return AdaptiveLayout(full, b.left, (b.right - b.left).coerceAtLeast(0f), fixedNavigation = true)
        }
    }
    var panes = listOf(full)
    for (fold in relevant) {
        val b = fold.bounds
        panes = panes.flatMap { p ->
            if (b.left >= p.right || b.right <= p.left || b.top >= p.bottom || b.bottom <= p.top) listOf(p)
            else if (fold.vertical) listOf(
                p.copy(right = b.left.coerceIn(p.left, p.right)),
                p.copy(left = b.right.coerceIn(p.left, p.right)),
            ) else listOf(
                p.copy(bottom = b.top.coerceIn(p.top, p.bottom)),
                p.copy(top = b.bottom.coerceIn(p.top, p.bottom)),
            )
        }
    }
    val pane = panes.maxByOrNull { it.width * it.height } ?: full
    val navWidth = when {
        !navigation || pane.height < 480f || pane.width < 640f -> 0f
        pane.width < 840f -> 280f
        else -> 304f
    }
    return AdaptiveLayout(pane, navWidth)
}

internal const val SplitHandleWidth = 16f
internal fun splitNavigationWidth(layout: AdaptiveLayout, requested: Float?): Float =
    if (!layout.hasNavigation || layout.fixedNavigation) layout.navigationWidth
    else (requested ?: layout.navigationWidth).coerceIn(280f, (layout.content.width - 320f - SplitHandleWidth).coerceAtLeast(280f))
