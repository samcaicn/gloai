package com.wangzan101.mika.chat.ui

import org.junit.Assert.*
import org.junit.Test

class PipSizingTest {
    @Test fun scaleIsBoundedAndInvalidGestureValuesDoNotEscape() {
        assertEquals(0.65f, PipSizing.scale(0.1f), 0f)
        assertEquals(1.8f, PipSizing.scale(20f), 0f)
        assertEquals(1f, PipSizing.scale(Float.NaN), 0f)
        assertEquals(1f, PipSizing.scale(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun resizingPreservesBothLandscapeAndPortraitFramesWithinTheArea() {
        for (aspect in listOf(0.5625f, 2.2f)) for (scale in listOf(0.65f, 1f, 1.8f)) {
            val size = PipSizing.fit(320 * scale, aspect, 380f, 600f, 8f)
            assertTrue(size.width <= 380f)
            assertTrue(size.height <= 600f)
            assertEquals(aspect, (size.height - 8) / (size.width - 8), 0.0001f)
        }
    }

    @Test fun smallerAndLargerRequestsChangeTheWindowUntilItReachesTheBounds() {
        val small = PipSizing.fit(220f * 0.65f, 2.2f, 600f, 1000f, 8f)
        val normal = PipSizing.fit(220f, 2.2f, 600f, 1000f, 8f)
        val large = PipSizing.fit(220f * 1.8f, 2.2f, 600f, 1000f, 8f)
        assertTrue(small.width < normal.width)
        assertTrue(normal.width < large.width)
        val capped = PipSizing.fit(2000f, 2.2f, 200f, 300f, 8f)
        assertTrue(capped.width <= 200f)
        assertEquals(300f, capped.height, 0.001f)
    }

    @Test fun verySmallAreaStillFitsAndInvalidAspectFallsBack() {
        val tiny = PipSizing.fit(300f, 2.2f, 5f, 4f, 8f)
        assertTrue(tiny.width <= 5f)
        assertTrue(tiny.height <= 4f)
        val fallback = PipSizing.fit(220f, Float.NaN, 500f, 900f, 8f)
        assertEquals(2.2f, (fallback.height - 8) / (fallback.width - 8), 0.001f)
    }
}
