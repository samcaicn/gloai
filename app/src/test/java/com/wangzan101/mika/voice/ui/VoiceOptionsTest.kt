package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceVerification
import com.wangzan101.mika.voice.model.VoiceVerificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOptionsTest {
    private val openai = Provider("p1", "My OpenAI", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1")
    private val snapshot = VoiceCatalogSnapshot(
        services = listOf(
            SttSelectionController.SYSTEM_STT_SERVICE,
            VoiceService("provider:p1", "p1", VoiceAccount.ExistingProvider("p1")),
            VoiceService("stt-doubao", "Doubao", VoiceAccount.VoiceOwned("s")),
            VoiceService("realtime:a", "Volc call", VoiceAccount.VoiceOwned("r")),
        ),
        models = listOf(
            SttSelectionController.SYSTEM_STT_MODEL,
            VoiceModel("stt-doubao", "doubao", "Doubao Streaming", stt = SttBinding(SttProtocol.DoubaoStreaming)),
            VoiceModel("provider:p1", "gpt-4o-transcribe", "gpt-4o-transcribe", stt = SttBinding(SttProtocol.AudioTranscriptions)),
            VoiceModel("realtime:a", "m", "Volc call", realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex)),
        ),
        verifications = listOf(
            VoiceVerificationRecord(VoiceModelRef("stt-doubao", "doubao", VoiceCapability.STT), VoiceVerification.Verified),
        ),
    )

    @Test fun `stt lists the device first, names chat accounts by their provider name, and adds no duplicate offer`() {
        val choices = voiceOptions(VoiceCapability.STT, snapshot, listOf(openai))
        assertEquals(VoiceSource.Device, choices.first().source)
        val fromAccount = choices.single { it.ref.serviceId == "provider:p1" }
        assertEquals(VoiceSource.ChatAccount("My OpenAI"), fromAccount.source)
        assertNull(fromAccount.offer)
        assertEquals(listOf(VoiceTrait.AfterSpeech), fromAccount.traits)
        val doubao = choices.single { it.ref.serviceId == "stt-doubao" }
        assertTrue(doubao.verified)
        assertEquals(listOf(VoiceTrait.Live), doubao.traits)
    }

    @Test fun `realtime adds the chat account offer and marks it phone-only`() {
        val choices = voiceOptions(VoiceCapability.REALTIME, snapshot, listOf(openai))
        assertEquals(2, choices.size)
        val offer = choices.single { it.ref.serviceId == "provider:p1" }
        assertNotNull(offer.offer)
        assertEquals(openai, offer.provider)
        assertTrue(VoiceTrait.PhoneOnly in offer.traits)
        assertEquals(listOf(VoiceTrait.Interruptible), choices.single { it.ref.serviceId == "realtime:a" }.traits)
    }
}
