package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 「语音转文字」选择页的分组和设置卡片上的两行字。 */
class SpeechOptionsTest {
    @get:Rule val texts = ChineseTexts()

    private val openAi = Provider(
        "p1", "OpenAI", ProviderConfig.Kind.OpenAiCompat, "https://api.openai.com/v1",
        listOf(ListedModel("gpt-4o"), ListedModel("whisper-1"), ListedModel("gpt-4o-mini")),
    )
    private val anthropic = Provider("p2", "Claude", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", listOf(ListedModel("claude-x")))
    private val noList = Provider("p3", "中转站", ProviderConfig.Kind.OpenAiCompat, "https://relay.example/v1", emptyList())

    @Test fun anthropicIsNotOffered() {
        val groups = SpeechOptions.groups(listOf(openAi, anthropic, noList))
        assertEquals(listOf("p1", "p3"), groups.map { it.provider.id })
    }

    @Test fun allModelsListedWithoutNameFilter() {
        val g = SpeechOptions.groups(listOf(openAi)).single()
        assertEquals(listOf("gpt-4o", "whisper-1", "gpt-4o-mini"), g.models)
    }

    @Test fun providerWithoutListStillGetsAGroup() {
        val g = SpeechOptions.groups(listOf(noList)).single()
        assertTrue(g.models.isEmpty())
    }

    @Test fun ttsMarksDoNotFilterModelsOrHideSelection() {
        val mixed = openAi.copy(
            models = openAi.models.orEmpty() + ListedModel("speech-local") + ListedModel("speech-tts"),
            ttsModels = setOf("speech-local"),
        )
        val ttsOnly = openAi.copy(id = "speech-out", models = listOf(ListedModel("speech-local")), ttsModels = setOf("speech-local"))
        val groups = SpeechOptions.groups(listOf(mixed, ttsOnly))
        assertEquals(listOf("p1", "speech-out"), groups.map { it.provider.id })
        assertEquals(listOf("gpt-4o", "whisper-1", "gpt-4o-mini", "speech-local", "speech-tts"), groups.first().models)

        // 标了 ttsModels 的模型仍可按显式选择转写;summary 不再因此显示未设置
        val choice = SpeechChoice.Provider(ttsOnly.id, "speech-local", TranscribeMethod.AudioTranscriptions)
        assertEquals("speech-local", SpeechOptions.summary(choice, SystemCapability(false, false), listOf(ttsOnly)).title)
    }

    @Test fun autoWithSystemShowsPhoneAndOffline() {
        val s = SpeechOptions.summary(SpeechChoice.Auto, SystemCapability(available = true, onDevice = true), listOf(openAi))
        assertEquals("手机自带", s.title)
        assertEquals("可离线", s.sub)
        assertTrue(s.subOk)
    }

    @Test fun autoWithoutSystemIsUnset() {
        val s = SpeechOptions.summary(SpeechChoice.Auto, SystemCapability(false, false), listOf(openAi))
        assertEquals("未设置", s.title)
        assertNull(s.sub)
    }

    @Test fun silentSystemSaysNotResponding() {
        val silent = SystemCapability(available = true, onDevice = true, responsive = false)
        for (choice in listOf(SpeechChoice.Auto, SpeechChoice.System)) {
            val s = SpeechOptions.summary(choice, silent, listOf(openAi))
            assertEquals("手机自带", s.title)
            assertEquals("没有响应", s.sub)
            assertFalse(s.subOk)
        }
    }

    @Test fun missingProviderIsUnset() {
        val choice = SpeechChoice.Provider("gone", "whisper-1", TranscribeMethod.AudioTranscriptions)
        val s = SpeechOptions.summary(choice, SystemCapability(true, true), listOf(openAi))
        assertEquals("未设置", s.title)
    }

    @Test fun providerChoiceShowsModelAndProviderName() {
        val choice = SpeechChoice.Provider("p1", "whisper-1", TranscribeMethod.AudioTranscriptions)
        val s = SpeechOptions.summary(choice, SystemCapability(true, true), listOf(openAi))
        assertEquals("whisper-1", s.title)
        assertEquals("OpenAI", s.sub)
    }

    @Test fun offShowsNone() {
        assertEquals("不用", SpeechOptions.summary(SpeechChoice.Off, SystemCapability(true, true), emptyList()).title)
    }
}
