package com.wangzan101.mika.core.decision

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionConfigTest {
    @Test fun defaultsAreOptInAndContainNoCredentials() {
        val config = DecisionConfig()
        assertFalse(config.enabled)
        assertEquals(DecisionProtocol.Google, config.protocol)
        assertEquals(DecisionConfig.GOOGLE_ENDPOINT, config.endpoint)
        assertEquals(DecisionConfig.GOOGLE_MODEL, config.model)
        assertTrue(DecisionConfig.isValidEndpoint(config.endpoint))
        assertTrue(DecisionConfig.isValidEndpoint(DecisionConfig.TYPE_SAFE_ENDPOINT))
        assertTrue(DecisionConfig.isValidEndpoint(DecisionConfig.OPEN_ROUTER_ENDPOINT))
        assertFalse(Json.encodeToString(config).contains("apiKey", ignoreCase = true))
    }

    @Test fun endpointsAllowHttpsAndLocalServicesButNotEmbeddedCredentials() {
        listOf(
            "https://decision.example/v1/systemone",
            "http://localhost:8080/v1/systemone",
            "http://127.0.0.1:8080/v1/systemone",
            "http://[::1]:8080/v1/systemone",
            "http://192.168.1.5:8080/decide",
        ).forEach { assertTrue(it, DecisionConfig.isValidEndpoint(it)) }
        listOf(
            "", "not a url", "file:///tmp/model", "http://api.typesafe.ai/v1/systemone",
            "https://user:secret@decision.example/v1/systemone",
            "https://decision.example/v1/systemone?key=secret",
            "https://decision.example/v1/systemone#secret",
            "https://decision.example/v1/\nsystemone",
        ).forEach { assertFalse(it, DecisionConfig.isValidEndpoint(it)) }
    }

    @Test fun protocolInferenceDistinguishesGoogleAndSystemOne() {
        assertEquals(DecisionProtocol.Google, DecisionConfig().protocol)
        assertEquals(
            DecisionProtocol.SystemOne,
            DecisionConfig(endpoint = DecisionConfig.TYPE_SAFE_ENDPOINT, model = DecisionConfig.TYPE_SAFE_MODEL).protocol,
        )
        assertEquals(
            DecisionProtocol.SystemOne,
            DecisionConfig(endpoint = DecisionConfig.OPEN_ROUTER_ENDPOINT, model = DecisionConfig.OPEN_ROUTER_MODEL).protocol,
        )
        assertEquals(
            DecisionProtocol.Google,
            DecisionConfig.inferredProtocol("http://127.0.0.1:8080/v1beta/decisions", "custom-model"),
        )
        assertEquals(
            DecisionProtocol.Google,
            DecisionConfig.inferredProtocol("https://generativelanguage.googleapis.com/v1beta/custom", "other-model"),
        )
        assertEquals(
            DecisionProtocol.Google,
            DecisionConfig.inferredProtocol("https://proxy.example/decide", DecisionConfig.GOOGLE_MODEL),
        )
        val decodedLegacy = Json.decodeFromString<DecisionConfig>(
            """{"enabled":true,"endpoint":"https://api.typesafe.ai/v1/systemone","model":"jev-1.14.0"}""",
        )
        assertEquals(DecisionProtocol.SystemOne, decodedLegacy.protocol)
        assertEquals(DecisionProtocol.Google, Json.decodeFromString<DecisionConfig>("{}").protocol)
    }

    @Test fun aiPlatformEndpointsAreNotAllowedForGoogleDecisionsApi() {
        assertTrue(DecisionConfig.isGoogleEndpoint(DecisionConfig.GOOGLE_ENDPOINT))
        assertFalse(DecisionConfig.isAiPlatformEndpoint(DecisionConfig.GOOGLE_ENDPOINT))
        listOf(
            "https://aiplatform.googleapis.com/v1/decisions",
            "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/us-central1/publishers/google/models/gemini-decision-1:decide",
            "https://europe-west4-aiplatform.googleapis.com/v1beta/decisions",
        ).forEach { endpoint ->
            assertTrue(endpoint, DecisionConfig.isAiPlatformEndpoint(endpoint))
            assertFalse(endpoint, DecisionConfig.isGoogleEndpoint(endpoint))
        }
    }
}
