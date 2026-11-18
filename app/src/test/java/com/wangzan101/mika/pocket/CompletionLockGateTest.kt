package com.wangzan101.mika.pocket

import org.junit.Assert.*
import org.junit.Test

class CompletionLockGateTest {
    @Test fun locksOnlyAfterContinuousCoveredHalfSecond() {
        val gate = CompletionLockGate()
        assertTrue(gate.begin(true, true, true, true, 1000))
        assertFalse(gate.observe(true, true, true, 1499))
        assertTrue(gate.observe(true, true, true, 1500))
        assertFalse(gate.observe(true, true, true, 1600))
    }

    @Test fun refusesDisabledIneligibleBusyAndUncovered() {
        for (values in listOf(
            listOf(false, true, true, true), listOf(true, false, true, true),
            listOf(true, true, false, true), listOf(true, true, true, false)
        )) {
            val gate = CompletionLockGate()
            assertFalse(gate.begin(values[0], values[1], values[2], values[3], 0))
            assertFalse(gate.observe(true, true, true, 1000))
        }
    }

    @Test fun interruptionOrNewTaskCancelsPendingLock() {
        for (condition in 0..2) {
            val gate = CompletionLockGate()
            assertTrue(gate.begin(true, true, true, true, 0))
            assertFalse(gate.observe(condition != 0, condition != 1, condition != 2, 250))
            assertFalse(gate.observe(true, true, true, 1000))
        }
    }
}
