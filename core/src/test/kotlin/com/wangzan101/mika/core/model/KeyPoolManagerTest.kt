package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolManagerTest {

    @Test
    fun parseKeysHandlesMultipleFormats() {
        val manager = KeyPoolManager()

        // Comma separated
        val comma = manager.parseKeys("key1, key2,  key3 ")
        assertEquals(listOf("key1", "key2", "key3"), comma)

        // Newline separated
        val newline = manager.parseKeys("keyA\nkeyB\r\nkeyC")
        assertEquals(listOf("keyA", "keyB", "keyC"), newline)

        // Semicolon separated
        val semicolon = manager.parseKeys("keyX; keyY; keyZ")
        assertEquals(listOf("keyX", "keyY", "keyZ"), semicolon)

        // JSON bracket format with quotes
        val bracket = manager.parseKeys("[\"key_1\", \"key_2\"]")
        assertEquals(listOf("key_1", "key_2"), bracket)

        // Deduplication and empty string filtering
        val dedup = manager.parseKeys("k1, k1, , k2, \n, k1")
        assertEquals(listOf("k1", "k2"), dedup)

        // Null or blank
        assertTrue(manager.parseKeys(null).isEmpty())
        assertTrue(manager.parseKeys("   ").isEmpty())
    }

    @Test
    fun getActiveKeyRotatesRoundRobin() {
        var fakeTime = 1000L
        val manager = KeyPoolManager(clock = { fakeTime })

        manager.register("provider1", "keyA, keyB")

        assertEquals("keyA", manager.getActiveKey("provider1"))
        assertEquals("keyB", manager.getActiveKey("provider1"))
        assertEquals("keyA", manager.getActiveKey("provider1"))
    }

    @Test
    fun rateLimitedKeyBypassedAndAutoRecovers() {
        var fakeTime = 1000L
        val manager = KeyPoolManager(clock = { fakeTime }, defaultCooldownMs = 10_000L)

        manager.register("provider1", "key1, key2")

        // First is key1
        assertEquals("key1", manager.getActiveKey("provider1"))

        // Key1 hits 429
        manager.markRateLimited("provider1", "key1", retryAfterMs = 5_000L)

        // Immediate next call MUST be key2
        assertEquals("key2", manager.getActiveKey("provider1"))
        assertEquals("key2", manager.getActiveKey("provider1"))

        // Now key2 also hits 429
        manager.markRateLimited("provider1", "key2", retryAfterMs = 10_000L)

        // Both are in cooldown, pool exhausted
        assertNull(manager.getActiveKey("provider1"))
        assertFalse(manager.hasAvailableKey("provider1"))

        // Advance time past key1's reset (5,000ms)
        fakeTime += 6_000L
        assertTrue(manager.hasAvailableKey("provider1"))
        assertEquals("key1", manager.getActiveKey("provider1"))

        // Advance time past key2's reset (10,000ms)
        fakeTime += 5_000L
        assertEquals(2, manager.activeKeyCount("provider1"))
    }

    @Test
    fun exhaustedKeyNeverRecovers() {
        var fakeTime = 1000L
        val manager = KeyPoolManager(clock = { fakeTime })

        manager.register("provider1", "badKey, goodKey")
        manager.markExhausted("provider1", "badKey")

        assertEquals("goodKey", manager.getActiveKey("provider1"))

        // Advance time far into future
        fakeTime += 100_000_000L
        assertEquals("goodKey", manager.getActiveKey("provider1"))
    }

    @Test
    fun circuitBreakerTripsAndProbes() {
        var fakeTime = 1000L
        val breaker = CircuitBreaker(
            failureThreshold = 3,
            resetTimeoutMs = 15_000L,
            clock = { fakeTime }
        )

        val target = "gemini:gemini-3.5-flash"

        // Initially healthy
        assertTrue(breaker.allowRequest(target))
        assertEquals(CircuitBreaker.State.Closed, breaker.getState(target))

        // 2 failures: still closed
        val serverErr = ModelError.Server(503, "Internal Server Error")
        breaker.recordFailure(target, serverErr)
        breaker.recordFailure(target, serverErr)
        assertTrue(breaker.allowRequest(target))
        assertEquals(CircuitBreaker.State.Closed, breaker.getState(target))

        // 3rd failure: trips to Open!
        breaker.recordFailure(target, serverErr)
        assertEquals(CircuitBreaker.State.Open, breaker.getState(target))
        assertFalse(breaker.allowRequest(target))

        // Advance time before cooldown: still Open
        fakeTime += 10_000L
        assertFalse(breaker.allowRequest(target))

        // Advance time past cooldown: enters HalfOpen and allows 1 trial probe
        fakeTime += 6_000L
        assertEquals(CircuitBreaker.State.HalfOpen, breaker.getState(target))
        assertTrue(breaker.allowRequest(target)) // Trial probe allowed

        // Secondary concurrent request blocked while probe in-flight
        assertFalse(breaker.allowRequest(target))

        // Probe succeeds: circuit returns to Closed!
        breaker.recordSuccess(target)
        assertEquals(CircuitBreaker.State.Closed, breaker.getState(target))
        assertTrue(breaker.allowRequest(target))
    }
}
