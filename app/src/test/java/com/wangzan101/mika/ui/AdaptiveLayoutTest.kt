package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    private fun vertical(left: Float, right: Float = left) =
        FoldObstacle(PaneBounds(left, 0f, right, 1000f), vertical = true)

    @Test fun coverScreenAndSplitWindowUseSinglePane() {
        for (width in listOf(320f, 599f, 600f, 639f)) {
            val layout = adaptiveLayout(width, 900f, emptyList(), navigation = true)
            assertFalse(layout.hasNavigation)
            assertEquals(PaneBounds(0f, 0f, width, 900f), layout.content)
        }
    }

    @Test fun expandedWidthGetsNavigationButShortLandscapeDoesNot() {
        assertEquals(280f, adaptiveLayout(640f, 480f, emptyList(), true).navigationWidth)
        assertEquals(280f, adaptiveLayout(673f, 900f, emptyList(), true).navigationWidth)
        assertEquals(280f, adaptiveLayout(839f, 900f, emptyList(), true).navigationWidth)
        assertEquals(304f, adaptiveLayout(840f, 480f, emptyList(), true).navigationWidth)
        assertFalse(adaptiveLayout(1000f, 479f, emptyList(), true).hasNavigation)
        assertFalse(adaptiveLayout(1000f, 900f, emptyList(), false).hasNavigation)
    }

    @Test fun bookPostureUsesActualOffCentreHingeEvenBelowExpandedBreakpoint() {
        val layout = adaptiveLayout(740f, 900f, listOf(vertical(310f, 330f)), true)
        assertEquals(310f, layout.navigationWidth)
        assertEquals(20f, layout.hingeGap)
        assertEquals(410f, layout.content.width - layout.navigationWidth - layout.hingeGap)
    }

    @Test fun zeroWidthSeparatingFoldStillPartitionsContent() {
        val fold = listOf(vertical(400f))
        assertEquals(400f, adaptiveLayout(800f, 900f, fold, true).navigationWidth)
        assertEquals(PaneBounds(0f, 0f, 400f, 900f), adaptiveLayout(800f, 900f, fold, false).content)
    }

    @Test fun narrowPaneFallsBackToLargestUnobstructedArea() {
        val layout = adaptiveLayout(700f, 900f, listOf(vertical(260f, 280f)), true)
        assertFalse(layout.hasNavigation)
        assertEquals(PaneBounds(280f, 0f, 700f, 900f), layout.content)
        val narrowChat = adaptiveLayout(700f, 900f, listOf(vertical(400f, 410f)), true)
        assertEquals(PaneBounds(0f, 0f, 400f, 900f), narrowChat.content)
    }

    @Test fun tabletopDoesNotPlaceContentAcrossHorizontalHinge() {
        val fold = FoldObstacle(PaneBounds(-20f, 400f, 820f, 420f), vertical = false)
        val layout = adaptiveLayout(800f, 800f, listOf(fold), true)
        assertEquals(PaneBounds(0f, 0f, 800f, 400f), layout.content)
        assertFalse(layout.hasNavigation)
    }

    @Test fun foldOutsideCurrentWindowIsIgnored() {
        val folds = listOf(vertical(-30f, -10f), vertical(1100f),
            FoldObstacle(PaneBounds(400f, 1000f, 420f, 1200f), true))
        assertEquals(adaptiveLayout(1000f, 900f, emptyList(), true), adaptiveLayout(1000f, 900f, folds, true))
    }

    @Test fun multipleFoldsChooseAnIntactRegion() {
        val layout = adaptiveLayout(1200f, 900f, listOf(vertical(300f, 320f), vertical(800f, 820f)), true)
        assertEquals(PaneBounds(320f, 0f, 800f, 900f), layout.content)
        assertFalse(layout.hasNavigation)
    }

    @Test fun foldOverlappingEdgeDoesNotCreateNegativeDimensions() {
        val layout = adaptiveLayout(400f, 800f, listOf(vertical(-10f, 20f)), true)
        assertEquals(PaneBounds(20f, 0f, 400f, 800f), layout.content)
        assertTrue(layout.content.width > 0)
    }
    @Test fun draggableSplitClampsBothPanesAndNeverMovesAHinge() {
        val wide = adaptiveLayout(860f, 600f, emptyList(), true)
        assertEquals(280f, splitNavigationWidth(wide, 20f))
        assertEquals(524f, splitNavigationWidth(wide, 900f))
        assertEquals(400f, splitNavigationWidth(wide, 400f))
        val hinge = adaptiveLayout(860f, 600f, listOf(vertical(410f)), true)
        assertEquals(410f, splitNavigationWidth(hinge, 500f))
    }
}
