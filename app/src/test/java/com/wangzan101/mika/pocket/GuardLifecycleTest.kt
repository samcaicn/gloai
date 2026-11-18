package com.wangzan101.mika.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardLifecycleTest {
    @Test fun `monitor only runs while enabled and a task is active`() {
        val port = object : GuardLifecycle.Port {
            var starts = 0
            var stops = 0
            override fun start(): Boolean { starts++; return true }
            override fun stop() { stops++ }
        }
        val gate = GuardLifecycle(port)
        gate.update(false, true)
        gate.update(true, false)
        assertEquals(0, port.starts)
        gate.update(true, true)
        gate.update(true, true)
        assertEquals(1, port.starts)
        gate.update(true, false)
        assertEquals(1, port.stops)
        gate.update(true, true)
        gate.update(false, true)
        assertEquals(2, port.stops)
        gate.close()
        gate.update(true, true)
        assertEquals(2, port.starts)
    }

    @Test fun `failed monitor start can be retried while task is active`() {
        val port = object : GuardLifecycle.Port {
            var attempts = 0
            override fun start(): Boolean { attempts++; return attempts > 1 }
            override fun stop() = Unit
        }
        val gate = GuardLifecycle(port)
        gate.update(true, true)
        gate.update(true, true)
        assertEquals(2, port.attempts)
    }

    @Test fun `failed monitor does not restart after task ends or feature is off`() {
        val port = object : GuardLifecycle.Port {
            var attempts = 0
            override fun start(): Boolean { attempts++; return false }
            override fun stop() = Unit
        }
        val gate = GuardLifecycle(port)
        gate.update(true, true)
        gate.update(true, false)
        gate.update(false, true)
        assertEquals(1, port.attempts)
        gate.close()
        gate.update(true, true)
        assertEquals(1, port.attempts)
    }
}
