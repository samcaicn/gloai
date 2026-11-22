package com.wangzan101.mika.voicecall

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallToneTest {
    @Test
    fun toneIsTwoSegmentsOfSixtyFiveMillisecondsAtTheOutputRate() {
        val pcm = VoiceCallTone.connectTonePcm(24_000)
        val expectedSamples = 2 * 24_000 * VoiceCallTone.TONE_MS / 1000
        assertEquals(expectedSamples * 2, pcm.size)
        assertEquals(2 * 24_000 * 65 / 1000, expectedSamples)
    }

    @Test
    fun sampleRateIsHonored() {
        val pcm = VoiceCallTone.connectTonePcm(16_000)
        assertEquals(2 * 16_000 * VoiceCallTone.TONE_MS / 1000 * 2, pcm.size)
    }

    @Test
    fun firstSegmentIs720HzAndSecondIs960Hz() {
        val samples = toSamples(VoiceCallTone.connectTonePcm(24_000))
        val perSegment = samples.size / 2
        val first = zeroCrossings(samples, 0, perSegment)
        val second = zeroCrossings(samples, perSegment, perSegment)
        // 720Hz × 65ms ≈ 46.8 周期 ≈ 94 次过零;960Hz ≈ 62.4 周期 ≈ 125 次过零(包络不影响计数)
        assertTrue("first segment crossings=$first", first in 82..106)
        assertTrue("second segment crossings=$second", second in 110..140)
    }

    @Test
    fun bothSegmentsFadeInFromAndOutToSilence() {
        val samples = toSamples(VoiceCallTone.connectTonePcm(24_000))
        val perSegment = samples.size / 2
        for (offset in listOf(0, perSegment)) {
            val headPeak = peak(samples, offset, 24)
            val tailPeak = peak(samples, offset + perSegment - 24, 24)
            val bodyPeak = peak(samples, offset + perSegment / 2 - 12, 24)
            assertTrue(headPeak < bodyPeak / 3)
            assertTrue(tailPeak < bodyPeak / 3)
            assertTrue(bodyPeak > Short.MAX_VALUE / 4)
        }
    }

    private fun toSamples(pcm: ByteArray): ShortArray = ShortArray(pcm.size / 2) {
        ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort()
    }

    private fun zeroCrossings(samples: ShortArray, offset: Int, count: Int): Int {
        var crossings = 0
        for (i in offset + 1 until offset + count) {
            if (samples[i - 1] < 0 && samples[i] >= 0 || samples[i - 1] >= 0 && samples[i] < 0) crossings++
        }
        return crossings
    }

    private fun peak(samples: ShortArray, offset: Int, count: Int): Int {
        var peak = 0
        for (i in offset until offset + count) peak = maxOf(peak, abs(samples[i].toInt()))
        return peak
    }
}
