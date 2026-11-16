package com.wangzan101.mika.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RestartsTest {
    @Test
    fun normalRestartIsImmediate() {
        val r = Restarts()
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(0))
        // 隔得够久的空段(没开口超时)不算连着
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(6_000))
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(12_000))
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(18_000))
    }

    @Test
    fun backsOffAfterThreeQuickEmptyRestarts() {
        val r = Restarts()
        repeat(3) { assertEquals(Restarts.Decision.Restart(0), r.onEmpty(it * 10L)) }
        assertEquals(Restarts.Decision.Restart(Restarts.BACKOFF_MS), r.onEmpty(40))
    }

    @Test
    fun givesUpAfterTooManyQuickEmptyRestarts() {
        val r = Restarts()
        val got = (0 until Restarts.MAX_QUICK).map { r.onEmpty(it * 300L) }
        assertEquals(Restarts.Decision.Restart(Restarts.BACKOFF_MS), got.last())
        assertEquals(Restarts.Decision.GiveUp, r.onEmpty(Restarts.MAX_QUICK * 300L))
    }

    @Test
    fun realSpeechResetsTheCount() {
        val r = Restarts()
        repeat(5) { r.onEmpty(it * 10L) }
        r.onSpeech()
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(60))
    }

    @Test
    fun errorOutcomes() {
        val no = SpeechRecognizer.ERROR_NO_MATCH
        val client = SpeechRecognizer.ERROR_CLIENT
        assertEquals(SystemSpeech.Outcome.Retry, SystemSpeech.afterError(no, holding = true, heard = false))
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterError(no, holding = false, heard = false))
        // 松开后有的服务报 CLIENT:什么都没听到就是没听清,不是麦克风坏了
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterError(client, holding = false, heard = false))
        assertEquals(SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemFailed)), SystemSpeech.afterError(client, holding = true, heard = false))
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterError(SpeechRecognizer.ERROR_NETWORK, holding = true, heard = true))
        assertEquals(SystemSpeech.Outcome.Fail(SpeechFailure.Network), SystemSpeech.afterError(SpeechRecognizer.ERROR_NETWORK, holding = false, heard = false))
    }
}
