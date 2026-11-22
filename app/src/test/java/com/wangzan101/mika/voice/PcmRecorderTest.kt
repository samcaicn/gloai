package com.wangzan101.mika.voice

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcmRecorderTest {
    @Test
    fun defaultsMatchLegacyBehavior() {
        val r = PcmRecorder()
        assertEquals(MediaRecorder.AudioSource.MIC, r.inputSource)
        assertEquals(800, r.chunkSamples) // 50ms × 16kHz,与旧的 Wav.RATE / 20 一致
        assertFalse(r.silenced.value)
    }

    @Test
    fun chunkMsDrivesChunkSize() {
        assertEquals(320, PcmRecorder(chunkMs = 20).chunkSamples)
        assertEquals(800, PcmRecorder(chunkMs = 50).chunkSamples)
    }
}
