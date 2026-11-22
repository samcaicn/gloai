package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ImageAspect
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageModelSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun freshDefaultsAreOffAndAskBeforeSave() {
        val state = ImageModelSettings(MemoryPrefs()).state.value
        assertFalse(state.enabled)
        assertNull(state.choice)
        assertEquals(ImageSettingsState.DEFAULT_MAX_PER_TASK, state.maxPerTask)
        assertFalse(state.saveEnabled)
        assertTrue(state.askBeforeSave)
        assertFalse(state.askBeforeGenerate)
        assertTrue(state.supportsEdit)
    }

    @Test fun choiceAndFlagsSurviveARestart() {
        val prefs = MemoryPrefs()
        val settings = ImageModelSettings(prefs)
        val choice = ImageChoice("p1", "gpt-image-1", ImageProtocol.OpenAiImages)
        settings.setChoice(choice)
        settings.setEnabled(true)
        settings.setSaveEnabled(true)
        settings.setAskBeforeSave(false)
        settings.setAskBeforeGenerate(true)
        settings.setMaxPerTask(3)
        settings.setSupportsEdit(false)

        val restarted = ImageModelSettings(prefs).state.value
        assertEquals(choice, restarted.choice)
        assertTrue(restarted.enabled)
        assertTrue(restarted.saveEnabled)
        assertFalse(restarted.askBeforeSave)
        assertTrue(restarted.askBeforeGenerate)
        assertEquals(3, restarted.maxPerTask)
        assertFalse(restarted.supportsEdit)
    }

    @Test fun badJsonFallsBackToDefaults() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("image_settings", "not json").commit()
        val state = ImageModelSettings(prefs).state.value
        assertFalse(state.enabled)
        assertNull(state.choice)
        assertEquals(ImageSettingsState.DEFAULT_MAX_PER_TASK, state.maxPerTask)
    }

    @Test fun incompleteChoiceIsDropped() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("image_settings", """{"providerId":"p1","model":"m","protocol":"unknown"}""").commit()
        assertNull(ImageModelSettings(prefs).state.value.choice)
    }

    @Test fun maxPerTaskIsClamped() {
        val prefs = MemoryPrefs()
        val settings = ImageModelSettings(prefs)
        settings.setMaxPerTask(0)
        assertEquals(1, settings.state.value.maxPerTask)
        settings.setMaxPerTask(999)
        assertEquals(ImageSettingsState.MAX_PER_TASK_LIMIT, settings.state.value.maxPerTask)
        prefs.edit().putString("image_settings", """{"maxPerTask":9999}""").commit()
        assertEquals(ImageSettingsState.MAX_PER_TASK_LIMIT, ImageModelSettings(prefs).state.value.maxPerTask)
    }

    @Test fun capabilitiesFollowProtocolAndEditSwitch() {
        val prefs = MemoryPrefs()
        val settings = ImageModelSettings(prefs)
        assertNull(settings.capabilities())

        settings.setChoice(ImageChoice("p", "gpt-image-1", ImageProtocol.OpenAiImages))
        val openAi = settings.capabilities()!!
        assertTrue(openAi.supportsEdit)
        assertEquals(4, openAi.maxReferences)
        assertTrue(ImageAspect.Portrait in openAi.aspects)
        assertFalse(ImageAspect.Tall in openAi.aspects)

        settings.setChoice(ImageChoice("p", "gemini-x-image", ImageProtocol.GeminiImage))
        val gemini = settings.capabilities()!!
        assertTrue(ImageAspect.Wide in gemini.aspects)

        settings.setSupportsEdit(false)
        val noEdit = settings.capabilities()!!
        assertFalse(noEdit.supportsEdit)
        assertEquals(0, noEdit.maxReferences)
    }

    @Test fun imageAdapterIgnoresTtsMarks() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No credentials in this fixture") })
        val provider = store.connect(
            "Mixed", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", null,
            listOf(ListedModel("image-model"), ListedModel("fishaudio/drama-3-int4")),
        )
        val choice = ImageChoice(provider.id, "image-model", ImageProtocol.OpenAiImages)
        val settings = ImageModelSettings(MemoryPrefs())
        settings.setChoice(choice)
        val caps = settings.capabilities()!!
        assertEquals("image-model", store.imageModel(choice, caps)?.name)
        assertEquals("fishaudio/drama-3-int4", store.imageModel(choice.copy(model = "fishaudio/drama-3-int4"), caps)?.name)
        // 手填的、不在列表里的模型不再因标记或名字被排除
        assertEquals("custom", store.imageModel(choice.copy(model = "custom"), caps)?.name)
    }
}
