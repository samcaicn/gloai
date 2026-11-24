package com.wangzan101.mika.calls.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallModeIdleTest {
    @Test fun `ready-made minutes, a custom value and never each select their own row`() {
        assertEquals(IdleChoice.Preset(2), idleChoiceOf(2))
        assertEquals(IdleChoice.Preset(5), idleChoiceOf(5))
        assertEquals(IdleChoice.Preset(10), idleChoiceOf(10))
        assertEquals(IdleChoice.Custom(7), idleChoiceOf(7))
        assertEquals(IdleChoice.Custom(120), idleChoiceOf(120))
        assertEquals(IdleChoice.Never, idleChoiceOf(0))
    }

    @Test fun `a custom value is saved only as whole minutes from 1 to 120`() {
        assertEquals(1, customIdleMinutes("1"))
        assertEquals(45, customIdleMinutes(" 45 "))
        assertEquals(120, customIdleMinutes("120"))
        assertNull(customIdleMinutes("0"))
        assertNull(customIdleMinutes("121"))
        assertNull(customIdleMinutes(""))
    }
}
