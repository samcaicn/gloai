package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.decision.DecisionChoice
import com.wangzan101.mika.core.model.decision.DecisionConfig
import com.wangzan101.mika.core.model.decision.DecisionModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import com.wangzan101.mika.settings.ui.JevPreset
import com.wangzan101.mika.settings.ui.testDecisionConnection

class JevSettingsTest {
    @Test fun connectionProbeOnlySendsTheFixedSyntheticInputAndChecksItsAnswer() = runTest {
        var called = false
        val model = DecisionModel { state, instructions, options, applicability ->
            called = true
            assertEquals(JsonPrimitive("Hello!"), state)
            assertEquals("Classify this synthetic test message.", instructions)
            assertEquals(mapOf("greeting" to "A greeting", "other" to "Anything else"), options)
            assertNull(applicability)
            DecisionChoice("greeting", 0.9)
        }
        assertTrue(testDecisionConnection(model))
        assertTrue(called)
        assertFalse(testDecisionConnection(DecisionModel { _, _, _, _ -> DecisionChoice("other", 0.9) }))
    }

    @Test fun presetsKeepExactProviderModelIdentifiersAndRecognizeCustomConfiguration() {
        assertEquals(JevPreset.Google, JevPreset.entries.first())
        assertEquals(JevPreset.Google, JevPreset.of(DecisionConfig()))
        assertEquals(JevPreset.TypeSafe, JevPreset.of(DecisionConfig(endpoint = DecisionConfig.TYPE_SAFE_ENDPOINT,
            model = DecisionConfig.TYPE_SAFE_MODEL)))
        assertEquals(JevPreset.OpenRouter, JevPreset.of(DecisionConfig(endpoint = DecisionConfig.OPEN_ROUTER_ENDPOINT,
            model = DecisionConfig.OPEN_ROUTER_MODEL)))
        assertEquals(JevPreset.Custom, JevPreset.of(DecisionConfig(endpoint = "https://example.com/v1/systemone")))
        assertEquals(JevPreset.Custom, JevPreset.of(DecisionConfig(model = "custom-model")))
    }
}
