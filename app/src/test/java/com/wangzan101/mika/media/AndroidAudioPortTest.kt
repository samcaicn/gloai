package com.wangzan101.mika.media

import android.content.ContextWrapper
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAudioPortTest {
    @Test fun generationAndSpeakingUseConfiguredVoiceAndSpeedUnlessOverridden() = runTest {
        val requests = mutableListOf<TtsRequest>()
        val response = TtsResponse(byteArrayOf(1, 2), "audio/wav")
        val model = object : TtsModel {
            override val name = "tts"
            override val capabilities = TtsCapabilities()
            override suspend fun synthesize(request: TtsRequest): TtsResponse {
                requests += request
                return response
            }
        }
        val played = mutableListOf<TtsResponse>()
        val port = AndroidAudioPort(ContextWrapper(null), "conversation", model, model.capabilities,
            onSpeak = { played += it }, defaultVoice = "alloy", defaultSpeed = 1.2f)

        port.synthesize(TtsRequest("默认生成"))
        port.speak(TtsRequest("默认朗读"))
        port.synthesize(TtsRequest("指定音色", voice = "nova"))
        port.speak(TtsRequest("指定音色和速度", voice = "nova", speed = 0.8f))

        assertEquals(listOf("alloy", "alloy", "nova", "nova"), requests.map { it.voice })
        assertEquals(listOf(1.2f, 1.2f, 1.2f, 0.8f), requests.map { it.speed })
        assertEquals(listOf(response, response), played)
    }
}
