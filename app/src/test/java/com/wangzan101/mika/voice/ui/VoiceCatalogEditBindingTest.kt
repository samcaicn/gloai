package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/** 编辑表单只露出协议和地址;表单之外的协议参数(计费方案、地域、语言、音色)编辑后必须原样留下。 */
class VoiceCatalogEditBindingTest {
    @Test fun `editing keeps stt parameters the form does not show`() {
        val saved = SttBinding(
            SttProtocol.DoubaoStreaming, endpoint = "wss://old", region = "cn", resourceId = "plan-concurrent",
            businessSpace = "space-1", language = "zh",
        )
        val edited = editedSttBinding(saved, SttProtocol.DoubaoStreaming, "wss://new")
        assertEquals(saved.copy(endpoint = "wss://new"), edited)
    }

    @Test fun `switching stt protocol drops the old protocol's parameters`() {
        val saved = SttBinding(SttProtocol.BailianStreaming, region = "singapore", businessSpace = "llm-1", language = "en")
        assertEquals(
            SttBinding(SttProtocol.AudioTranscriptions, endpoint = "https://x"),
            editedSttBinding(saved, SttProtocol.AudioTranscriptions, "https://x"),
        )
    }

    @Test fun `new stt binding has only protocol and endpoint`() {
        assertEquals(SttBinding(SttProtocol.FunAsrStreaming, endpoint = "ws://h"), editedSttBinding(null, SttProtocol.FunAsrStreaming, "ws://h"))
    }

    @Test fun `editing keeps the realtime speaker`() {
        val saved = RealtimeBinding(RealtimeProtocol.VolcDuplex, endpoint = "wss://a", speaker = "warm-voice")
        assertEquals(saved.copy(endpoint = "wss://b"), editedRealtimeBinding(saved, RealtimeProtocol.VolcDuplex, "wss://b"))
        assertEquals(
            RealtimeBinding(RealtimeProtocol.OpenAiRealtime, endpoint = "wss://c"),
            editedRealtimeBinding(saved, RealtimeProtocol.OpenAiRealtime, "wss://c"),
        )
    }

    @Test fun `tts binding follows the same rule`() {
        val saved = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "https://a")
        assertEquals(TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "https://b"), editedTtsBinding(saved, TtsProtocol.OpenAiSpeech, "https://b"))
        assertEquals(TtsBinding(TtsProtocol.GeminiTts, endpoint = ""), editedTtsBinding(saved, TtsProtocol.GeminiTts, ""))
    }
}
