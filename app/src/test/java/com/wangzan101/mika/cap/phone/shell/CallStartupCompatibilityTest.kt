package com.wangzan101.mika.cap.phone.shell

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

/** Protect the already-working startup paths while auditing the 1.1.5 change. */
class CallStartupCompatibilityTest {
    private val phone = AudioDeviceInfo.TYPE_TELEPHONY

    @Test fun establishedTelephonyStartsImmediatelyForBothBackends() {
        for (backend in listOf(ShellCallAudio.DIRECT, ShellCallAudio.REDIRECT)) {
            for (rx in listOf(-1, phone)) {
                val h = Harness { rx to phone }
                h.run(backend)
                assertEquals(0L, h.now)
                assertEquals(1, h.primeWrites)
            }
        }
    }

    @Test fun redirectedHiddenDevicesRetainImmediateStartup() {
        for (rx in listOf(-1, phone)) {
            val h = Harness { rx to -1 }
            h.run(ShellCallAudio.REDIRECT)
            assertEquals(0L, h.now)
        }
    }

    @Test fun previouslySupportedUnknownToTelephonyTransitionsKeepTheirTiming() {
        for (readyAt in listOf(20L, 100L, 680L)) {
            for (rx in listOf(-1, phone)) {
                val h = Harness { at -> rx to if (at < readyAt) -1 else phone }
                h.run(ShellCallAudio.DIRECT)
                assertEquals(readyAt, h.now)
            }
        }
    }

    @Test fun directUnknownOutputStillHasABoundedFailure() {
        val h = Harness { phone to -1 }
        val error = runCatching { h.run(ShellCallAudio.DIRECT) }.exceptionOrNull()
        assertEquals("Uplink telephony route was not confirmed.", error?.message)
        assertEquals(700L, h.now)
        assertEquals(1, h.primeWrites)
    }

    @Test fun captureChangingToTheWrongDeviceDuringStartupFailsImmediately() {
        val h = Harness { at ->
            (if (at < 20L) phone else AudioDeviceInfo.TYPE_BUILTIN_MIC) to -1
        }
        val error = runCatching { h.run(ShellCallAudio.DIRECT) }.exceptionOrNull()
        assertTrue(error?.message?.startsWith("Downlink unexpectedly routed") == true)
        assertEquals(20L, h.now)
    }

    @Test fun partialSilencePrimeRetainsRetryAccountingAndImmediateReadyPath() {
        val h = Harness { phone to phone }
        h.writes = ArrayDeque(listOf(0, 2, 0, 6))
        h.run(ShellCallAudio.DIRECT)
        assertEquals(10L, h.now)
        assertEquals(4, h.primeWrites)
    }

    private class Harness(val routes: (Long) -> Pair<Int, Int>) {
        var now = 0L
        var primeWrites = 0
        var writes: ArrayDeque<Int>? = null
        fun run(backend: Int) {
            ShellCallAudio.awaitStartupRoute(backend, { now }, { now += it }, {}, { routes(now) }) { guard ->
                ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, guard) { _, remaining ->
                    primeWrites++
                    writes?.removeFirst() ?: remaining
                }
            }
        }
    }
}
