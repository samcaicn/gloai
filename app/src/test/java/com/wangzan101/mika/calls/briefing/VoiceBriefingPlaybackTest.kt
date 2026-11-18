package com.wangzan101.mika.calls.briefing

import com.wangzan101.mika.calls.CallPlaybackQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceBriefingPlaybackTest {
    @Test fun `an ordinary reply arriving faster than playback does not terminate briefing`() = runTest {
        val queue = CallPlaybackQueue(VOICE_BRIEFING_MAX_PENDING_BYTES)
        val packet = ByteArray(18_672) { 7 } // 389 ms, same burst shape as the live provider.
        repeat(20) { assertNotNull("packet $it of a 7_78s response", queue.offer(packet)) }
        val output = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        var played = 0
        while (played < packet.size * 20) {
            val block = checkNotNull(queue.take(output))
            assertTrue(queue.isCurrent(block))
            assertTrue(output.take(block.byteCount).all { it == 7.toByte() })
            played += block.byteCount
            queue.release(block)
        }
        assertEquals(packet.size * 20, played)
        queue.close()
    }

    @Test fun `a full briefing buffer stays bounded and owner speech revokes in-flight audio`() = runTest {
        val queue = CallPlaybackQueue(VOICE_BRIEFING_MAX_PENDING_BYTES)
        assertNotNull(queue.offer(ByteArray(VOICE_BRIEFING_MAX_PENDING_BYTES) { 3 }))
        val output = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        val old = checkNotNull(queue.take(output))
        assertNull(queue.offer(byteArrayOf(0, 0))) // The writer still owns these bytes.
        queue.invalidate()
        assertFalse(queue.isCurrent(old))
        assertNotNull(queue.offer(byteArrayOf(9, 9)))
        queue.release(old) // Cannot release or replay the replacement response.
        val replacement = checkNotNull(queue.take(output))
        assertTrue(queue.isCurrent(replacement))
        assertEquals(2, replacement.byteCount)
        assertEquals(9.toByte(), output[0])
        queue.release(replacement)
        queue.close()
        assertNull(queue.take(output))
    }
}
