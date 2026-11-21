package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneViewportTest {
    @Test fun dockedViewportMatchesAvailablePixelsWithReadableNarrowLayout() {
        val narrow = phoneViewport(770, 1100, 440)
        assertEquals(PhoneViewport(770, 1100, 385), narrow)
        assertEquals(PhoneViewport(1100, 900, 440), phoneViewport(1100, 900, 440))
        assertEquals(PhoneViewport(1024, 2048, 220), phoneViewport(2048, 4096, 440))
    }
}
