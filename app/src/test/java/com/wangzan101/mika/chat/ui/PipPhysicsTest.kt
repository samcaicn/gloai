package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ui.PipPhysics.Corner
import org.junit.Assert.assertEquals
import org.junit.Test

class PipPhysicsTest {
    // 1080×2400 的屏幕:顶栏占 300(含状态栏)、输入栏占 250(含导航栏),系统栏是 60 和 40
    private val collapsedTop = 300f
    private val collapsedBottom = 250f
    private val safeTop = 60f
    private val safeBottom = 40f

    @Test fun collapsedPipKeepsClearOfTheTopBarAndComposer() {
        assertEquals(collapsedTop to collapsedBottom, insets(1f))
    }

    @Test fun expandedPipOnlyAvoidsTheSystemBars() {
        assertEquals(safeTop to safeBottom, insets(0f))
    }

    @Test fun insetsSlideBetweenTheTwoStates() {
        val (top, bottom) = insets(0.5f)
        assertEquals(180f, top, 0.01f)
        assertEquals(145f, bottom, 0.01f)
        // 越出范围的比例夹住
        assertEquals(collapsedTop to collapsedBottom, insets(3f))
        assertEquals(safeTop to safeBottom, insets(-1f))
    }

    @Test fun expandedPipReachesTheScreenEdges() {
        val pipWidth = 220f
        val pipHeight = 470f
        val margin = 30f
        val collapsed = PipPhysics.Bounds(1080f, 2400f, pipWidth, pipHeight, margin, collapsedTop, collapsedBottom)
        val expanded = PipPhysics.Bounds(1080f, 2400f, pipWidth, pipHeight, margin, safeTop, safeBottom)
        // 展开时上边贴着状态栏、下边贴着导航栏:整屏都能停,不只在中间
        assertEquals(30f to 90f, expanded.position(Corner.TopStart))
        assertEquals(830f to 1860f, expanded.position(Corner.BottomEnd))
        // 收起时仍然在顶栏下面、输入栏上面
        assertEquals(30f to 330f, collapsed.position(Corner.TopStart))
        assertEquals(830f to 1650f, collapsed.position(Corner.BottomEnd))
    }

    private fun insets(share: Float): Pair<Float, Float> =
        PipPhysics.restingInsets(collapsedTop, collapsedBottom, safeTop, safeBottom, share)
}
