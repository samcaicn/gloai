package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.VolcRealtimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceVoicesTest {
    @Test fun `realtime defaults are in their protocol lists`() {
        assertTrue(VolcRealtimeConfig.DEFAULT_SPEAKER in VoiceVoices.realtime(RealtimeProtocol.VolcDuplex))
        assertTrue(OpenAiCallProtocol.VOICE in VoiceVoices.realtime(RealtimeProtocol.OpenAiRealtime))
    }

    @Test fun `a saved custom voice is kept in front`() {
        val known = VoiceVoices.realtime(RealtimeProtocol.VolcDuplex)
        assertEquals(listOf("S_clone1") + known, VoiceVoices.withCurrent(known, "S_clone1"))
        assertEquals(known, VoiceVoices.withCurrent(known, known[1]))
        assertEquals(known, VoiceVoices.withCurrent(known, " "))
    }

    @Test fun `volc ids get a short label and others stay as they are`() {
        assertEquals("Vv", VoiceVoices.label("zh_female_vv_jupiter_bigtts"))
        assertEquals("Yunzhou", VoiceVoices.label("zh_male_yunzhou_jupiter_bigtts"))
        assertEquals("S_clone1", VoiceVoices.label("S_clone1"))
        assertEquals("marin", VoiceVoices.label("marin"))
    }
}
