package com.wangzan101.mika.cap.phone.shell

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallAudioTest {
    @Test
    fun startupAllowsSpeakerToTelephonyTransitionBeforeExposingThePipes() {
        val startup = StartupRoute { at ->
            AudioDeviceInfo.TYPE_TELEPHONY to
                if (at < 20L) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_TELEPHONY
        }
        startup.run()
        assertEquals(20L, startup.now)
        assertEquals(1, startup.silenceWrites)
        assertTrue(startup.ready)
    }

    @Test
    fun startupRejectsKnownWrongCaptureBeforeEvenWritingPrimeSilence() {
        val startup = StartupRoute { at ->
            (if (at < 20L) AudioDeviceInfo.TYPE_BUILTIN_MIC else AudioDeviceInfo.TYPE_TELEPHONY) to
                AudioDeviceInfo.TYPE_TELEPHONY
        }
        val failure = runCatching { startup.run() }.exceptionOrNull()
        assertTrue(failure?.message?.startsWith("Downlink unexpectedly routed") == true)
        assertEquals(0L, startup.now)
        assertEquals(0, startup.silenceWrites)
        assertFalse(startup.ready)
    }

    @Test
    fun startupWrongRouteTimesOutWithoutExposingThePipes() {
        val startup = StartupRoute { AudioDeviceInfo.TYPE_TELEPHONY to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val failure = runCatching { startup.run() }.exceptionOrNull()
        assertEquals("Uplink telephony route was not confirmed.", failure?.message)
        assertEquals(700L, startup.now)
        assertEquals(1, startup.silenceWrites)
        assertFalse(startup.ready)
    }

    @Test
    fun startupIdentityLossOrUnmuteStopsBeforeTheNextRouteSample() {
        listOf("owner", "mode", "mute").forEach { ended ->
            val startup = StartupRoute { -1 to -1 }
            startup.guard = { at -> check(at < 20L) { ended } }
            val failure = runCatching { startup.run() }.exceptionOrNull()
            assertEquals(ended, failure?.message)
            assertEquals(20L, startup.now)
            assertTrue(startup.sampleTimes.all { it < 20L })
            assertFalse(startup.ready)
        }
    }

    @Test
    fun startupIdentityAndMuteGuardAlsoProtectEverySilencePrimeRetry() {
        listOf("owner", "mode", "mute").forEach { ended ->
            var now = 0L
            var samples = 0
            var writes = 0
            val failure = runCatching {
                ShellCallAudio.awaitStartupRoute(ShellCallAudio.DIRECT, { now }, { now += it },
                    ensureActive = { check(now < 5L) { ended } },
                    sampleRoutes = { samples++; AudioDeviceInfo.TYPE_TELEPHONY to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
                    primeSilence = { startupGuard ->
                        ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, startupGuard) { _, _ ->
                            writes++
                            0
                        }
                    },
                )
            }.exceptionOrNull()
            assertEquals(ended, failure?.message)
            assertEquals(5L, now)
            assertEquals(1, samples)
            assertEquals(1, writes)
        }
    }

    @Test
    fun redirectedStartupAllowsUnknownDevicesButNeverKnownWrongCaptureOrPlayback() {
        val unknown = StartupRoute { -1 to -1 }
        unknown.run(ShellCallAudio.REDIRECT)
        assertTrue(unknown.ready)
        val wrongCapture = StartupRoute { AudioDeviceInfo.TYPE_BUILTIN_MIC to -1 }
        assertTrue(runCatching { wrongCapture.run(ShellCallAudio.REDIRECT) }.exceptionOrNull()
            ?.message?.startsWith("Downlink unexpectedly routed") == true)
        assertEquals(0, wrongCapture.silenceWrites)
        assertFalse(wrongCapture.ready)
        val wrongPlayback = StartupRoute { -1 to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val failure = runCatching { wrongPlayback.run(ShellCallAudio.REDIRECT) }.exceptionOrNull()
        assertEquals("Uplink telephony route was not confirmed.", failure?.message)
        assertEquals(700L, wrongPlayback.now)
        assertFalse(wrongPlayback.ready)
    }

    @Test
    fun runningRouteGuardStillRejectsKnownWrongDevicesImmediately() {
        for ((rx, tx) in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC to AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_TELEPHONY to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )) {
            assertTrue(runCatching { ShellCallAudio.requireTelephonyRoutes(rx, tx) }.isFailure)
        }
        ShellCallAudio.requireTelephonyRoutes(-1, -1)
        ShellCallAudio.requireTelephonyRoutes(AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_TELEPHONY)
    }

    private class StartupRoute(private val routes: (Long) -> Pair<Int, Int>) {
        var now = 0L
        var silenceWrites = 0
        var ready = false
        var guard: (Long) -> Unit = {}
        val sampleTimes = ArrayList<Long>()

        fun run(backend: Int = ShellCallAudio.DIRECT) {
            ShellCallAudio.awaitStartupRoute(backend, { now }, { now += it },
                ensureActive = { guard(now) },
                sampleRoutes = { sampleTimes += now; routes(now) },
                primeSilence = { startupGuard ->
                    ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, startupGuard) { _, size ->
                        silenceWrites++
                        size
                    }
                },
            )
            ready = true // The production session transfers its pipes only after this gate returns.
        }
    }

    @Test
    fun nonblockingPrimeRetriesTemporaryZeroAndFillsTheWholeStartThreshold() {
        var now = 0L
        var guards = 0
        val writes = ArrayList<Pair<Int, Int>>()
        val results = ArrayDeque(listOf(0, 2, 0, 6))
        val accepted = ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, { guards++ }) { offset, size ->
            writes += offset to size
            results.removeFirst()
        }
        assertEquals(8, accepted)
        assertEquals(listOf(0 to 8, 0 to 8, 2 to 6, 2 to 6), writes)
        assertEquals(10L, now)
        assertEquals(4, guards)
    }

    @Test
    fun nonblockingPrimeStaysBoundedAndRejectsHardErrorsImmediately() {
        var now = 0L
        var writes = 0
        val deadline = runCatching {
            ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, {}) { _, _ -> writes++; 0 }
        }.exceptionOrNull()
        assertTrue(deadline is IllegalStateException)
        assertEquals(100L, now)
        assertEquals(21, writes)

        now = 0L
        writes = 0
        val hardError = runCatching {
            ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, {}) { _, _ -> writes++; -6 }
        }.exceptionOrNull()
        assertTrue(hardError?.message?.contains("-6") == true)
        assertEquals(0L, now)
        assertEquals(1, writes)
    }

    @Test
    fun primingRechecksCallMuteAndRouteBeforeEveryAttempt() {
        listOf("Cellular call or its owner ended.", "Handset microphone was unmuted.", "Uplink unexpectedly routed").forEach { reason ->
            var now = 0L
            var guards = 0
            var writes = 0
            val failure = runCatching {
                ShellCallAudio.writePlaybackPrime(8, { now }, { now += it }, {
                    if (++guards == 2) error(reason)
                }) { _, _ -> writes++; 0 }
            }.exceptionOrNull()
            assertEquals(reason, failure?.message)
            assertEquals(1, writes)
            assertEquals(5L, now)
        }
    }

    @Test
    fun explicitlySelectedBackendNeverFallsBackToAnotherRoute() {
        assertTrue(ShellCallAudio.candidates(ShellCallAudio.DIRECT, false, true).isEmpty())
        assertTrue(ShellCallAudio.candidates(ShellCallAudio.REDIRECT, true, false).isEmpty())
        assertEquals(listOf(ShellCallAudio.DIRECT, ShellCallAudio.REDIRECT),
            ShellCallAudio.candidates(ShellCallAudio.AUTO, true, true))
    }

    @Test
    fun capturePeakDetectsQuietAndSignedLittleEndianSamples() {
        assertEquals(0, ShellCallAudio.pcmPeak(ByteArray(640), 640))
        assertEquals(32768, ShellCallAudio.pcmPeak(byteArrayOf(0, 0x80.toByte(), 10, 0), 4))
        assertEquals(256, ShellCallAudio.pcmPeak(byteArrayOf(0, 1, 0x7f), 3))
    }

    @Test
    fun continuousFreshFrameworkSilenceTripsAtTwoSeconds() {
        val capture = ShellCallAudio.CaptureSilenceDiagnostics()
        for (atMs in 0L until 2_000L step 100L) assertFalse(capture.observe(true, atMs))
        assertFalse(capture.observe(true, 1_999L))
        assertEquals(1_999L, capture.maxMs)
        assertTrue(capture.observe(true, 2_000L))
        assertEquals(2_000L, capture.maxMs)
    }

    @Test
    fun normalAndUnknownObservationsBreakSilencingWithoutExtendingTheReportedMaximum() {
        val capture = ShellCallAudio.CaptureSilenceDiagnostics()
        for (atMs in 0L..1_800L step 100L) assertFalse(capture.observe(true, atMs))
        assertFalse(capture.observe(false, 1_900L))
        for (atMs in 2_000L..3_700L step 100L) assertFalse(capture.observe(true, atMs))
        assertFalse(capture.observe(null, 3_800L))
        for (atMs in 3_900L..5_700L step 100L) assertFalse(capture.observe(true, atMs))
        for (atMs in 5_800L..10_000L step 100L) assertFalse(capture.observe(false, atMs))
        assertFalse(capture.observe(null, 20_000L))
        assertEquals(1_800L, capture.maxMs)
    }

    @Test
    fun missingPollsCannotTurnOldSilencingIntoAContinuousInterval() {
        val capture = ShellCallAudio.CaptureSilenceDiagnostics()
        for (atMs in 0L..1_900L step 100L) assertFalse(capture.observe(true, atMs))
        for (atMs in 2_301L..4_201L step 100L) assertFalse(capture.observe(true, atMs))
        assertEquals(1_900L, capture.maxMs)
        assertTrue(capture.observe(true, 4_301L))
        assertEquals(2_000L, capture.maxMs)
    }

    @Test
    fun playbackDiagnosticsTracksPendingZeroWritesAndFreezesAtStop() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertEquals(-1L, stats.lastWriteAgeMs(0L))
        stats.recordWrite(0, 0L)
        stats.recordWrite(0, 6L)
        assertEquals(2L, stats.writeZeroCount)
        assertEquals(12L, stats.zeroMaxMs(12L))
        stats.recordWrite(480, 15L)
        assertEquals(15L, stats.zeroMaxMs(45L))
        assertEquals(30L, stats.lastWriteAgeMs(45L))

        stats.recordWrite(0, 50L)
        stats.stop(71L)
        assertEquals(3L, stats.writeZeroCount)
        assertEquals(21L, stats.zeroMaxMs(10_000L))
        assertEquals(56L, stats.lastWriteAgeMs(10_000L))
    }

    @Test
    fun flushingOrLosingTheRouteDoesNotExtendTheZeroWriteStreak() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        stats.recordWrite(0, 100L)
        stats.endZeroStreak(114L)
        stats.recordWrite(0, 800L)
        stats.recordWrite(960, 811L)
        assertEquals(2L, stats.writeZeroCount)
        assertEquals(14L, stats.zeroMaxMs(900L))
        stats.recordFlush(21L)
        stats.recordFlush(12L)
        assertEquals(21L, stats.flushMaxMs)
    }

    @Test
    fun pendingUplinkStallRequiresContinuousZeroWritesRatherThanAQuietCall() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertFalse(stats.pendingWriteStalled(10_000L))
        stats.recordWrite(960, 10_000L)
        assertFalse(stats.pendingWriteStalled(30_000L))

        stats.recordWrite(0, 30_000L)
        stats.recordWrite(0, 31_499L)
        assertFalse(stats.pendingWriteStalled(31_499L))
        stats.recordWrite(0, 31_500L)
        assertTrue(stats.pendingWriteStalled(31_500L))
    }

    @Test
    fun actualProgressAndDiscardedPlaybackCannotCarryAStallIntoTheNextReply() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        stats.recordWrite(0, 500L)
        stats.recordWrite(2, 1_999L)
        stats.recordWrite(0, 2_000L)
        assertFalse(stats.pendingWriteStalled(3_499L))

        stats.endZeroStreak(3_499L) // Flush or loss of the confirmed route.
        assertFalse(stats.pendingWriteStalled(9_000L))
        stats.recordWrite(0, 9_000L)
        assertFalse(stats.pendingWriteStalled(9_001L))
        stats.stop(10_500L)
        assertFalse(stats.pendingWriteStalled(20_000L))
        assertEquals(1_500L, stats.zeroMaxMs(20_000L))
    }

    @Test
    fun playbackHeadIsAnUnsignedPositionAndCanBeInvalidatedAfterFlush() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertEquals(-1L, stats.playbackHeadFrames)
        stats.recordPlaybackHead(Int.MIN_VALUE, 0)
        assertEquals(0x8000_0000L, stats.playbackHeadFrames)
        stats.resetPlaybackHead()
        assertEquals(-1L, stats.playbackHeadFrames)
        stats.recordPlaybackHead(0, 0)
        assertEquals(0L, stats.playbackHeadFrames)
    }
}
