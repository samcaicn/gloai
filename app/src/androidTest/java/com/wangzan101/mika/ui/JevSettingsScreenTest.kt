package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.decision.DecisionConfig
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class JevSettingsScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = DecisionSettings(tmp.root, AesGcmKeyCipher { aes })

    @Test fun enabledSettingsRequireAKeyAndChangesOnlyApplyAfterSaving() {
        val s = settings()
        compose.setContent { MikaTheme { JevSettingsScreen(s, onBack = {}) } }
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("jev-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("jev-save").performScrollTo().assertIsNotEnabled()
        assertFalse(s.state.value.enabled)
        compose.onNodeWithTag("jev-key").performScrollTo().performTextReplacement("test-key")
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("jev-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { s.state.value.enabled }
        assertEquals("test-key", settings().apiKey())
        compose.onNodeWithTag("jev-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("jev-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { !s.state.value.enabled }
        assertFalse(settings().state.value.enabled)
        assertNull(settings().createModel())
    }

    @Test fun changingProviderOrEndpointClearsTheOldSecretBeforeTesting() {
        val s = settings().also {
            it.save(
                DecisionConfig(
                    enabled = true,
                    endpoint = DecisionConfig.TYPE_SAFE_ENDPOINT,
                    model = DecisionConfig.TYPE_SAFE_MODEL,
                ),
                "typesafe-key",
            )
        }
        compose.setContent { MikaTheme { JevSettingsScreen(s, onBack = {}) } }
        compose.onNodeWithTag("jev-preset-OpenRouter").performScrollTo().performClick()
        compose.onNodeWithTag("jev-endpoint").assertTextEquals(DecisionConfig.OPEN_ROUTER_ENDPOINT)
        compose.onNodeWithTag("jev-model").assertTextEquals(DecisionConfig.OPEN_ROUTER_MODEL)
        compose.onNodeWithTag("jev-key").assertTextEquals("")
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("jev-key").performScrollTo().performTextReplacement("openrouter-key")
        compose.onNodeWithTag("jev-endpoint").performScrollTo().performTextReplacement("https://example.com/v1/systemone")
        compose.onNodeWithTag("jev-key").assertTextEquals("")
        assertEquals("typesafe-key", s.apiKey())
        assertEquals(DecisionConfig.TYPE_SAFE_ENDPOINT, s.state.value.endpoint)
    }

    @Test fun googlePresetPrefillsStoredGeminiKeyAndSwitchingAwayClearsIt() {
        val s = DecisionSettings(tmp.root, AesGcmKeyCipher { aes }) { "AIza-stored-gemini-key" }
        compose.setContent { MikaTheme { JevSettingsScreen(s, onBack = {}) } }
        compose.onNodeWithTag("jev-preset-Google").performScrollTo()
        compose.onNodeWithTag("jev-endpoint").assertTextEquals(DecisionConfig.GOOGLE_ENDPOINT)
        compose.onNodeWithTag("jev-model").assertTextEquals(DecisionConfig.GOOGLE_MODEL)
        compose.onNodeWithTag("jev-key").assertTextEquals("AIza-stored-gemini-key")
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsEnabled()

        compose.onNodeWithTag("jev-preset-TypeSafe").performScrollTo().performClick()
        compose.onNodeWithTag("jev-endpoint").assertTextEquals(DecisionConfig.TYPE_SAFE_ENDPOINT)
        compose.onNodeWithTag("jev-model").assertTextEquals(DecisionConfig.TYPE_SAFE_MODEL)
        compose.onNodeWithTag("jev-key").assertTextEquals("")
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("jev-preset-Google").performScrollTo().performClick()
        compose.onNodeWithTag("jev-endpoint").assertTextEquals(DecisionConfig.GOOGLE_ENDPOINT)
        compose.onNodeWithTag("jev-model").assertTextEquals(DecisionConfig.GOOGLE_MODEL)
        compose.onNodeWithTag("jev-key").assertTextEquals("AIza-stored-gemini-key")
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsEnabled()

        compose.onNodeWithTag("jev-endpoint").performScrollTo()
            .performTextReplacement("https://us-central1-aiplatform.googleapis.com/v1/decisions")
        compose.onNodeWithTag("jev-key").assertTextEquals("")
        compose.onNodeWithTag("jev-test").performScrollTo().assertIsNotEnabled()
    }
}
