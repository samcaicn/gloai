package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceModelDisplayNameTest {
    @Test fun `built-in system models map to localized name resources`() {
        assertEquals(R.string.voice_catalog_system_stt_name, systemModelDisplayNameRes(SttSelectionController.SYSTEM_STT_MODEL))
        assertEquals(R.string.voice_catalog_system_tts_name, systemModelDisplayNameRes(TtsSelectionController.SYSTEM_TTS_MODEL))
    }

    @Test fun `user defined models keep their persisted display name`() {
        assertNull(systemModelDisplayNameRes(VoiceModel("provider:p1", "m1", "M1")))
        assertNull(systemModelDisplayNameRes(VoiceModel("android-system", "custom", "Custom")))
    }
}
