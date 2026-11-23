package com.wangzan101.mika.ui

import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.settings.GroundingStatus
import com.wangzan101.mika.settings.WebSearchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.wangzan101.mika.settings.ui.WebSearchCopy

class WebSearchCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun googleSearchCopyReflectsGroundingAvailabilityAndPricing() {
        val prev = UiText.source
        UiText.source = ResFileTexts.english
        try {
            assertEquals("Google Search", WebSearchCopy.name(WebSearchProvider.Google))
            val note = WebSearchCopy.note(WebSearchProvider.Google)
            assertTrue(note.contains("5,000 free grounded prompts/month"))
            assertTrue(note.contains("\$14/1,000 queries"))

            val withProvider = GroundingStatus(available = false, providerName = "LM Studio")
            assertTrue(
                WebSearchCopy.problem(
                    WebSearchProvider.Google,
                    WebSearchProvider.Problem.UnsupportedProvider,
                    withProvider,
                ).contains("LM Studio"),
            )

            val noModel = GroundingStatus(available = false, providerName = null)
            assertTrue(
                WebSearchCopy.problem(
                    WebSearchProvider.Google,
                    WebSearchProvider.Problem.UnsupportedProvider,
                    noModel,
                ).contains("Settings"),
            )

            assertEquals(
                "Unavailable",
                WebSearchCopy.value(
                    WebSearchConfig(
                        enabled = true,
                        provider = WebSearchProvider.Google,
                        grounding = withProvider,
                    ),
                ),
            )
            assertEquals(
                "Google Search",
                WebSearchCopy.value(
                    WebSearchConfig(
                        enabled = true,
                        provider = WebSearchProvider.Google,
                        grounding = GroundingStatus(available = true, providerName = "Gemini"),
                    ),
                ),
            )
        } finally {
            UiText.source = prev
        }
    }
}
