package com.wangzan101.mika.calls

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRealtimeProviderValidationTest {
    @Test fun oversizedInstructionsFailLocallyBeforeDialing() = runTest {
        var dials = 0
        val failure = runCatching {
            val provider = ProfileRealtimeProvider()
            provider.validate(CallVoiceProfile(), "x".repeat(16_385))
            startCallSession(CallDirection.OUTGOING, connect = { Unit }, awaitCarrier = { dials++ })
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, dials)
    }

    @Test fun invalidSpeakerAndModelFailWithoutOpeningAConnection() {
        val provider = ProfileRealtimeProvider()
        for (profile in listOf(CallVoiceProfile(speaker = ""), CallVoiceProfile(model = "bad model"))) {
            assertTrue(runCatching { provider.validate(profile, "hello") }.exceptionOrNull() is IllegalArgumentException)
        }
        provider.validate(CallVoiceProfile(), "hello")
    }
}
