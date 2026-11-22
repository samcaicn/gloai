package com.wangzan101.mika.ui

import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeChoicesTest {
    @Test fun `only models with realtime capability are offered for calls`() {
        val snapshot = VoiceCatalogSnapshot(
            models = listOf(
                VoiceModel("owned:a", "rt-1", "Realtime One", realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex)),
                VoiceModel("provider:p", "stt-1", "Transcribe", stt = SttBinding(SttProtocol.AudioTranscriptions)),
                VoiceModel("provider:p", "rt-2", "Realtime Two", realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime)),
            ),
        )
        assertEquals(
            listOf(
                VoiceModelRef("owned:a", "rt-1", VoiceCapability.REALTIME) to "Realtime One",
                VoiceModelRef("provider:p", "rt-2", VoiceCapability.REALTIME) to "Realtime Two",
            ),
            realtimeChoices(snapshot),
        )
    }
}
