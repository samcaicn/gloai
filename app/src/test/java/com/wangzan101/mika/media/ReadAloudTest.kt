package com.wangzan101.mika.media

import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTest {
    @Test fun disabledVoiceNeverResolvesAModelOrSynthesizes() = runTest {
        val prefs = MemoryPrefs()
        val settings = VoiceSettings(prefs)
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.OpenAiSpeech, "alloy"))
        settings.setAutoRead(true)
        settings.setEnabled(false)
        val restored = VoiceSettings(prefs)

        readAloud("answer", restored,
            model = { _, _ -> error("disabled voice must not resolve a provider") },
            play = { _, _, _ -> error("disabled voice must not play audio") },
        )
    }

    @Test fun enabledVoiceUsesSettingsAndDisablingBlocksTheNextCall() = runTest {
        val settings = VoiceSettings(MemoryPrefs())
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.OpenAiSpeech, "alloy", 1.2f, streaming = true))
        settings.setMaxChars(4)
        val requests = mutableListOf<TtsRequest>()
        var resolved = 0
        val streamingChoices = mutableListOf<Boolean>()
        val model = object : TtsModel {
            override val name = "test"
            override val capabilities = TtsCapabilities()
            override suspend fun synthesize(request: TtsRequest): TtsResponse {
                requests += request
                return TtsResponse(byteArrayOf(1, 2), "audio/wav")
            }
        }
        suspend fun speak() = readAloud("answer", settings,
            model = { _, _ -> resolved++; model },
            play = { selected, request, streaming ->
                selected.synthesize(request.single())
                streamingChoices += streaming
            },
        )

        speak()
        settings.setEnabled(false)
        speak()

        assertEquals(1, resolved)
        assertEquals(listOf(true), streamingChoices)
        assertEquals(listOf(TtsRequest("answ", "alloy", 1.2f, "wav")), requests)
    }

    @Test fun longStreamingReplyKeepsShortParagraphsAndCompleteListItemsTogether() = runTest {
        val intro = "句".repeat(24) + "。\n\n"
        val firstItems = "- ${"甲".repeat(30)}\n- ${"乙".repeat(16)}\n"
        val remainingItems = "- ${"丙".repeat(16)}\n- ${"丁".repeat(16)}\n\n"
        val closing = "${"尾".repeat(18)}？"

        val requests = streamingRequests(intro + firstItems + remainingItems + closing)

        assertEquals(listOf(intro + firstItems, remainingItems + closing).map { TtsRequest(it, "alloy", 1f, "wav") }, requests)
    }

    @Test fun splittingAndTheCharacterLimitPreserveWholeGraphemes() = runTest {
        val beforeEmoji = "甲".repeat(75) + "aa"
        val first = beforeEmoji + "👩‍💻"
        val text = first + "乙".repeat(77)

        val requests = streamingRequests(text)

        assertEquals(first, requests.first().text)
        assertEquals(text, requests.joinToString("") { it.text })
        assertEquals(beforeEmoji, streamingRequests(text, maxChars = first.length - 1).single().text)
    }

    @Test fun longLatinReplySplitsBetweenWords() = runTest {
        val text = "ordinary words ".repeat(28)
        val requests = streamingRequests(text)

        assertEquals(text, requests.joinToString("") { it.text })
        assertEquals(true, requests.dropLast(1).all { it.text.endsWith(" ") })
        assertEquals(true, requests.all { it.text.trim().length <= 228 })
    }

    @Test fun blankTextAndWhitespaceBetweenParagraphsNeverBecomeRequests() = runTest {
        val text = "首段。\n\n" + " ".repeat(400) + "末段。"

        assertEquals(emptyList<TtsRequest>(), streamingRequests(" \n\t "))
        assertEquals(listOf("首段。", "末段。"), streamingRequests(text).map { it.text.trim() })
    }

    private suspend fun streamingRequests(text: String, maxChars: Int = 2000): List<TtsRequest> {
        val settings = VoiceSettings(MemoryPrefs())
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.OpenAiSpeech, "alloy", streaming = true))
        settings.setMaxChars(maxChars)
        val streamingModel = object : StreamingTtsModel {
            override val name = "test"
            override val capabilities = TtsCapabilities()
            override suspend fun synthesize(request: TtsRequest) = error("playback is injected")
            override fun streamAudio(request: TtsRequest) = error("playback is injected")
        }
        val requests = mutableListOf<List<TtsRequest>>()
        readAloud(text, settings,
            model = { _, _ -> streamingModel },
            play = { _, batch, streaming ->
                assertEquals(true, streaming)
                requests += batch
            },
        )
        return requests.flatten()
    }
}
