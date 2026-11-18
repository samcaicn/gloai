package com.wangzan101.mika.pocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketDetectorTest {
    @Test fun `stable cover activates and brief cover does not`() {
        val detector = PocketDetector()
        assertFalse(detector.update(true, true, false, 100))
        assertFalse(detector.update(true, true, false, 2099))
        assertTrue(detector.update(true, true, false, 2100))
        assertFalse(detector.update(true, true, false, 3000))
    }

    @Test fun `dismissal stays suppressed until proximity clears`() {
        val detector = PocketDetector()
        detector.update(true, true, false, 0)
        assertTrue(detector.update(true, true, false, 2000))
        detector.dismiss()
        assertFalse(detector.update(false, true, false, 3000))
        assertFalse(detector.update(true, true, false, 6000))
        assertFalse(detector.update(false, true, true, 7000))
        assertFalse(detector.update(true, true, false, 8000))
        assertTrue(detector.update(true, true, false, 10000))
    }

    @Test fun `unavailable readings do not trigger and break debounce`() {
        val detector = PocketDetector()
        detector.update(true, true, false, 0)
        assertFalse(detector.update(false, false, false, 1900))
        assertFalse(detector.update(true, true, false, 2000))
        assertTrue(detector.update(true, true, false, 4000))
    }

    @Test fun `failed attachment retries only after a fresh debounce`() {
        val detector = PocketDetector()
        detector.update(true, true, false, 0)
        assertTrue(detector.update(true, true, false, 2000))
        detector.failed()
        assertFalse(detector.update(true, true, false, 2001))
        assertTrue(detector.update(true, true, false, 4001))
    }
}
