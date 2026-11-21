package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHealthTest {
    @Test
    fun headStallNeedsFrozenHeadAndLiveAudio() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        // 没有播放头观测、没有数据:不算死
        assertFalse(d.headStalled(10_000))

        d.recordWrite(480, 1_000)
        d.recordPlaybackHead(0, 1_000)
        // 数据在走、头冻了 500ms:还没到阈值
        assertFalse(d.headStalled(1_500))
        // 头冻满 1s:死
        assertTrue(d.headStalled(2_100))

        // 头重新走了:活
        d.recordPlaybackHead(240, 2_200)
        assertFalse(d.headStalled(2_600))
    }

    @Test
    fun headStallCoversSilentDeathWithUnplayedAudio() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(480, 1_000) // 240 帧进了 track
        d.recordPlaybackHead(240, 1_100) // 全播完了
        d.recordWrite(960, 1_200) // 又进 480 帧
        // 数据早停了,但 track 里还压着 480 帧没播、头也不动:静默死
        assertTrue(d.headStalled(9_000))
    }

    @Test
    fun headStallClearsWhenTrackDrainsEverything() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(480, 1_000)
        d.recordPlaybackHead(240, 1_100)
        // 播完、无新数据:正常静默,不是死
        assertFalse(d.headStalled(9_000))
    }

    @Test
    fun headStallResetsAcrossFlushEpochs() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(480, 1_000)
        d.recordPlaybackHead(0, 1_000)
        assertTrue(d.headStalled(2_100))
        // 打断 flush:新纪元,旧观测不再算数
        d.recordTrackFlush()
        assertFalse(d.headStalled(3_000))
    }

    @Test
    fun trackQueueAccountsWrittenPrimeAndHead() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(960, 100) // 480 帧数据
        d.recordPrime(480) // 240 帧先导静音
        assertEquals(-1L, d.trackQueuedFrames()) // 头未知
        d.recordPlaybackHead(240, 200)
        assertEquals(480 + 240 - 240L, d.trackQueuedFrames())
    }

    @Test
    fun rebuildPolicyThrottlesAndEventuallyGivesUp() {
        val p = ShellCallAudio.PlaybackRebuildPolicy(maxInWindow = 3, windowMs = 15_000, minIntervalMs = 1_000)
        assertFalse(p.throttled(0))
        assertTrue(p.allow(0))
        assertTrue(p.throttled(500))
        assertFalse(p.throttled(1_000))
        assertTrue(p.allow(1_000))
        assertTrue(p.allow(2_000))
        // 窗口内第 4 次:放弃,交还系统
        assertFalse(p.allow(3_000))
        // 窗口滑过后又可以
        assertTrue(p.allow(16_500))
    }
}
