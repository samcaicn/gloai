package com.wangzan101.mika.calls.briefing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceBriefingAudioGateTest {
    @Test fun `worker observing stop before job cancellation cannot poison authorization cleanup`() = runTest {
        var afterCleanup = false
        coroutineScope {
            val reading = CompletableDeferred<Unit>()
            val stopPublished = CompletableDeferred<Unit>()
            val worker = launch(start = CoroutineStart.UNDISPATCHED) {
                reading.complete(Unit)
                stopPublished.await()
                requireVoiceBriefingAudioActive(stopped = true) { error("gate must not run after stop") }
                fail("stopped worker cannot send a frame")
            }
            reading.await()
            stopPublished.complete(Unit)
            worker.join()
            assertTrue(worker.isCancelled)
        }
        afterCleanup = true
        assertTrue(afterCleanup)
    }

    @Test fun `a live worker with revoked eligibility still fails the parent and blocks submission`() = runTest {
        var submitted = false
        var reason: String? = null
        try {
            coroutineScope {
                launch { requireVoiceBriefingAudioActive(stopped = false) { false } }
            }
            submitted = true
        } catch (failure: VoiceBriefingFailure) {
            reason = failure.safeMessage
        }
        assertFalse(submitted)
        assertEquals("语音交代已停止，未拨号", reason)
    }
}
