package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.decision.DecisionConfig
import com.wangzan101.mika.core.model.decision.DecisionError
import com.wangzan101.mika.core.model.decision.DecisionProtocol
import com.wangzan101.mika.core.model.decision.GoogleDecisionModel
import com.wangzan101.mika.core.model.decision.SystemOneDecisionModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSink
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.settings.ui.testDecisionConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class DecisionSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private fun settings() = DecisionSettings(tmp.root, cipher)
    private val config = DecisionConfig(enabled = true)

    @Test fun freshInstallIsOffAndDoesNotCreateAClientOrCredentials() {
        val s = settings()
        assertEquals(DecisionConfig(), s.state.value)
        assertEquals("", s.apiKey())
        assertNull(s.createModel())
        assertTrue(tmp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun encryptedRecordRoundTripsAndDisablingKeepsTheConfiguredKey() {
        val s = settings()
        s.save(config, "test-secret-key-123")
        val bytes = tmp.root.resolve("decision-model.bin").readText(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("test-secret-key-123"))
        assertFalse(bytes.contains(config.endpoint))
        assertFalse(bytes.contains(config.model))
        val restored = settings()
        assertEquals(config, restored.state.value)
        assertEquals("test-secret-key-123", restored.apiKey())
        assertNotNull(restored.createModel())
        restored.save(config.copy(enabled = false), restored.apiKey())
        assertFalse(settings().state.value.enabled)
        assertEquals("test-secret-key-123", settings().apiKey())
        assertNull(settings().createModel())
    }

    @Test fun invalidEnabledConfigurationsAreRejectedWithoutChangingSavedSettings() {
        val s = settings()
        s.save(config, "valid-key")
        val invalid = listOf(
            config.copy(endpoint = "http://example.com/v1/systemone") to "key",
            config.copy(endpoint = "https://user:secret@example.com/v1/systemone") to "key",
            config.copy(endpoint = "https://example.com/v1/systemone?key=secret") to "key",
            config.copy(endpoint = "https://example.com/v1/systemone#part") to "key",
            config.copy(endpoint = "") to "key",
            config.copy(model = " ") to "key",
            config.copy(model = "bad\nmodel") to "key",
            config to "",
            config to "key with spaces",
            config to "key\r\nheader",
            config to "秘密",
        )
        invalid.forEach { (c, k) ->
            try { s.save(c, k); fail("Invalid configuration was accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(config, s.state.value)
            assertEquals("valid-key", settings().apiKey())
        }
    }

    @Test fun disabledDraftCanBeSavedWithoutAKeyAndWhitespaceIsTrimmed() {
        val s = settings()
        s.save(DecisionConfig(endpoint = " https://example.com/v1/systemone ", model = " jev-test "), "")
        assertEquals("https://example.com/v1/systemone", settings().state.value.endpoint)
        assertEquals("jev-test", settings().state.value.model)
        assertNull(settings().createModel())
        assertTrue(DecisionSettings.valid(config.copy(endpoint = "http://127.0.0.1:8000/v1/systemone"), "key"))
    }

    @Test fun corruptRecordLostKeyAndCredentialsChangedAfterLoadingFailClosed() {
        val s = settings()
        s.save(config, "key")
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val lost = DecisionSettings(tmp.root, AesGcmKeyCipher { other })
        assertFalse(lost.state.value.enabled)
        assertEquals("", lost.apiKey())
        assertNull(lost.createModel())
        tmp.root.resolve("decision-model.bin").writeText("broken")
        assertFalse(settings().state.value.enabled)
        assertNull(s.createModel())
    }

    @Test fun failedWritePreservesThePreviousEncryptedRecordAndState() {
        var failEncryption = false
        val failing = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                if (failEncryption) error("Encryption unavailable")
                return cipher.encrypt(plain)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val s = DecisionSettings(tmp.root, failing)
        s.save(config, "old-key")
        failEncryption = true
        try { s.save(config.copy(model = "another-model"), "new-key"); fail() } catch (_: IllegalStateException) { }
        assertEquals(config, s.state.value)
        assertEquals("old-key", settings().apiKey())
        assertFalse(tmp.root.resolve("decision-model.bin.tmp").exists())
    }

    @Test fun googleDefaultRoundTripsAndLegacyRecordsInferProtocol() {
        val s = settings()
        val google = DecisionConfig(enabled = true)
        assertEquals(DecisionProtocol.Google, google.protocol)
        s.save(google, "AIza-test-key")
        val restored = settings()
        assertEquals(google, restored.state.value)
        assertEquals(DecisionProtocol.Google, restored.state.value.protocol)
        assertTrue(restored.createModel() is GoogleDecisionModel)

        val legacyBytes = """{"enabled":true,"endpoint":"${DecisionConfig.TYPE_SAFE_ENDPOINT}","model":"${DecisionConfig.TYPE_SAFE_MODEL}","apiKey":"legacy-ts-key"}"""
            .toByteArray(Charsets.UTF_8)
        tmp.root.resolve("decision-model.bin").writeBytes(cipher.encrypt(legacyBytes))
        val legacy = settings()
        assertEquals(DecisionProtocol.SystemOne, legacy.state.value.protocol)
        assertEquals(DecisionConfig.TYPE_SAFE_ENDPOINT, legacy.state.value.endpoint)
        assertEquals("legacy-ts-key", legacy.apiKey())
        assertTrue(legacy.createModel() is SystemOneDecisionModel)
    }

    @Test fun storedGeminiKeyFallbackAppliesOnlyToGoogleAndNotSystemOne() {
        val s = DecisionSettings(tmp.root, cipher) { "  AIza-from-models  " }
        assertEquals("AIza-from-models", s.storedGeminiApiKey())

        val google = DecisionConfig(enabled = true)
        assertFalse(DecisionSettings.valid(google, ""))
        assertTrue(s.canSave(google, ""))
        assertTrue(s.canTest(google, ""))
        s.save(google, "")
        assertEquals("", s.apiKey())
        assertTrue(
            DecisionSettings(tmp.root, cipher) { "AIza-from-models" }.createModel() is GoogleDecisionModel,
        )

        val typeSafe = DecisionConfig(
            enabled = true,
            endpoint = DecisionConfig.TYPE_SAFE_ENDPOINT,
            model = DecisionConfig.TYPE_SAFE_MODEL,
            protocol = DecisionProtocol.SystemOne,
        )
        assertFalse(s.canSave(typeSafe, ""))
        assertFalse(s.canTest(typeSafe, ""))
        try { s.save(typeSafe, ""); fail("Expected SystemOne save without key to fail") } catch (_: IllegalArgumentException) { }
        try { s.testModel(typeSafe, ""); fail("Expected SystemOne testModel without key to fail") } catch (_: IllegalArgumentException) { }
    }

    @Test fun aiPlatformEndpointsAreRejectedForGoogleDecisionsProtocol() {
        val s = DecisionSettings(tmp.root, cipher) { "AIza-from-models" }
        val aiPlatformUrls = listOf(
            "https://aiplatform.googleapis.com/v1/decisions",
            "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/us-central1/publishers/google/models/gemini-decision-1:decide",
        )
        aiPlatformUrls.forEach { url ->
            val cfg = DecisionConfig(
                enabled = true,
                endpoint = url,
                model = DecisionConfig.GOOGLE_MODEL,
                protocol = DecisionProtocol.Google,
            )
            assertFalse(url, DecisionSettings.valid(cfg, "AIza-explicit"))
            assertFalse(url, s.canSave(cfg, ""))
            assertFalse(url, s.canTest(cfg, ""))
            try { s.save(cfg, "AIza-explicit"); fail("Expected aiplatform URL save to fail") } catch (_: IllegalArgumentException) { }
            try { s.testModel(cfg, "AIza-explicit"); fail("Expected aiplatform URL testModel to fail") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun testModelHitsGoogleDecisionsEndpointWithApiKeyHeaderAndNeverFallsBackOnError() = runBlocking {
        MockWebServer().use { server ->
            val recorded = mutableListOf<Pair<UsageSource, Usage?>>()
            val s = DecisionSettings(
                tmp.root,
                cipher,
                usage = UsageSink { source, usage -> recorded += source to usage },
                geminiKeyProvider = { "AIza-mock-key" },
            )
            val cfg = DecisionConfig(
                enabled = true,
                endpoint = server.url("/v1beta/decisions").toString(),
                model = DecisionConfig.GOOGLE_MODEL,
                protocol = DecisionProtocol.Google,
            )

            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                    """{"model":"gemini-decision-1","answers":{"choice":{"type":"choice","choice":"greeting","probabilities":{"greeting":0.95,"other":0.05}}},"usage":{"total_input_tokens":12,"total_output_tokens":0}}""",
                ),
            )
            val model = s.testModel(cfg, "")
            assertTrue(model is GoogleDecisionModel)
            assertTrue(testDecisionConnection(model))

            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1beta/decisions", request.path)
            assertEquals("AIza-mock-key", request.getHeader("x-goog-api-key"))
            assertNull(request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("gemini-decision-1", body["model"]!!.jsonPrimitive.content)
            assertEquals("Hello!", body["input"]!!.jsonPrimitive.content)
            assertTrue("questions" in body)
            assertFalse("state" in body)
            assertEquals(1, recorded.size)
            assertEquals(
                UsageSource("google-decision", "Google", DecisionConfig.GOOGLE_MODEL, UsagePurpose.Other),
                recorded.single().first,
            )
            assertEquals(Usage(12, 0), recorded.single().second)

            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}"""))
            try {
                s.testModel(cfg, "").choose(JsonPrimitive("Hello!"), "Classify", mapOf("greeting" to "g", "other" to "o"), null)
                fail("Expected 401 Auth error")
            } catch (_: ModelError.Auth) { }
            assertEquals(2, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":500}}"""))
            try {
                s.testModel(cfg, "").choose(JsonPrimitive("Hello!"), "Classify", mapOf("greeting" to "g", "other" to "o"), null)
                fail("Expected 500 Http error")
            } catch (e: DecisionError.Http) {
                assertEquals(500, e.status)
            }
            assertEquals(3, server.requestCount)
        }
    }
}
