package com.wangzan101.mika.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewCoordinatesTest {
    @Test fun portraitImageInWidePaneExcludesSideLetterboxes() {
        assertEquals(500 to 1000, previewPoint(400f, 400f, 800, 800, 1000, 2000))
        assertNull(previewPoint(199f, 400f, 800, 800, 1000, 2000))
        assertNull(previewPoint(600f, 400f, 800, 800, 1000, 2000))
        assertEquals(0 to 0, previewPoint(200f, 0f, 800, 800, 1000, 2000))
    }

    @Test fun landscapeWebPageExcludesTopAndBottomLetterboxes() {
        assertEquals(960 to 540, previewPoint(150f, 300f, 300, 600, 1920, 1080))
        assertNull(previewPoint(150f, 100f, 300, 600, 1920, 1080))
        assertNull(previewPoint(150f, 500f, 300, 600, 1920, 1080))
    }

    @Test fun invalidOrOffscreenCoordinatesAreNeverClampedIntoAButton() {
        assertNull(previewPoint(-1f, 20f, 100, 100, 100, 100))
        assertNull(previewPoint(100f, 20f, 100, 100, 100, 100))
        assertNull(previewPoint(Float.NaN, 0f, 100, 100, 100, 100))
        assertNull(previewPoint(0f, 0f, 0, 100, 100, 100))
        assertNull(previewPoint(0f, 0f, 100, 100, 0, 100))
    }
}
