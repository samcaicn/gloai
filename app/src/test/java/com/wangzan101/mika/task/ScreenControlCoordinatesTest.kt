package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenControlCoordinatesTest {
    @Test fun phonePreviewMapsToFullFoldableDisplay() {
        assertEquals(1182 to 836, controlPoint(180, 127, 360, 254, 2364, 1672))
        assertEquals(2357 to 1665, controlPoint(359, 253, 360, 254, 2364, 1672))
    }

    @Test fun fullResolutionWebFrameUsesUnchangedCoordinates() {
        assertEquals(200 to 400, controlPoint(200, 400, 1080, 1920, 1080, 1920))
    }

    @Test(expected = IllegalArgumentException::class) fun outsidePreviewIsRejected() {
        controlPoint(360, 100, 360, 254, 2364, 1672)
    }
}
