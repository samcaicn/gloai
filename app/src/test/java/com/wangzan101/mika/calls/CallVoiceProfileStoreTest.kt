package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallVoiceProfileStoreTest {
    private class Backend(var value: String? = null) : CallVoiceProfileBackend {
        override fun read(): String? = value
        override fun write(value: String): Boolean { this.value = value; return true }
    }

    private fun store(backend: Backend) = CallVoiceProfileStore(backend, newId = { "profile-new" })
    private fun credentials(key: String) = VolcRealtimeCredentials(apiKey = key)

    @Test fun legacyConfigurationMigratesWithoutLosingCredentialsOrAudioVerification() {
        val backend = Backend()
        val config = CallConfig(enabled = true, audioVerified = true, audioMode = CallAudioMode.REDIRECT)
        store(backend).migrateLegacy(config) { credentials("legacy-secret") }

        val reopened = store(backend)
        val profile = reopened.get(CallVoiceProfile.DEFAULT_ID)
        assertNotNull(profile)
        assertTrue(profile!!.credentialsConfigured)
        assertTrue(profile.connectionTested)
        assertTrue(profile.audioVerifications.isNotEmpty())
        assertFalse(reopened.isAudioVerified(profile.id, CallAudioMode.DIRECT, "build-fingerprint"))
        assertEquals("legacy-secret", reopened.credentials(profile.id)?.apiKey)
        assertFalse(profile.toString().contains("legacy-secret"))
    }

    @Test fun temporaryLegacyReadFailureCanBeRetriedAfterReopen() {
        val backend = Backend()
        val config = CallConfig(enabled = true, audioVerified = true)
        val first = store(backend)
        assertTrue(runCatching {
            first.migrateLegacy(config) { throw java.security.KeyStoreException("temporarily unavailable") }
        }.isFailure)
        assertNull(backend.value)

        val reopened = store(backend)
        reopened.migrateLegacy(config) { credentials("legacy-secret") }
        assertEquals("legacy-secret", reopened.credentials(CallVoiceProfile.DEFAULT_ID)?.apiKey)
        assertTrue(reopened.get(CallVoiceProfile.DEFAULT_ID)!!.connectionTested)
    }

    @Test fun fillsABlankMigratedDefaultWithoutReplacingConfiguredCredentials() {
        val backend = Backend()
        val config = CallConfig()
        store(backend).migrateLegacy(config) { null }
        val reopened = store(backend)
        reopened.migrateLegacy(config) { credentials("restored-secret") }
        reopened.migrateLegacy(config) { error("should not reread configured credentials") }
        assertEquals("restored-secret", reopened.credentials(CallVoiceProfile.DEFAULT_ID)?.apiKey)
    }

    @Test fun profilesKeepIndependentCredentialsTestsAndAudioVerification() {
        val backend = Backend()
        val store = store(backend)
        val first = store.save(CallVoiceProfile(id = "first", name = "First"), credentials("first-secret"))
        val second = store.save(CallVoiceProfile(id = "second", name = "Second", model = "custom-model"), credentials("second-secret"))

        store.markConnectionTested(first.id, true)
        store.markConnectionTested(second.id, true)
        store.markAudioVerified(first.id, CallAudioMode.DIRECT, true, "device-build")
        store.markAudioVerified(second.id, CallAudioMode.REDIRECT, true, "device-build")

        assertTrue(store.isAudioVerified(first.id, CallAudioMode.DIRECT, "device-build"))
        assertFalse(store.isAudioVerified(second.id, CallAudioMode.DIRECT, "device-build"))
        assertTrue(store.isAudioVerified(second.id, CallAudioMode.REDIRECT, "device-build"))
        assertEquals("first-secret", store.credentials(first.id)?.apiKey)
        assertEquals("second-secret", store.credentials(second.id)?.apiKey)

        val renamed = store.save(second.copy(name = "Renamed"))
        assertTrue(renamed.connectionTested)
        assertEquals("second-secret", store.credentials(second.id)?.apiKey)

        val changed = store.save(second.copy(endpoint = "wss://voice.example.com/v2"))
        assertFalse(changed.connectionTested)
        assertTrue(store.isAudioVerified(second.id, CallAudioMode.REDIRECT, "device-build"))
        val reopened = store(backend)
        assertTrue(reopened.load())
        assertEquals(2, reopened.state.value.size)
    }

    @Test fun openAiCredentialsStayBoundToTheirProviderAndEndpoint() {
        val backend = Backend()
        val store = store(backend)
        val volc = store.save(CallVoiceProfile(id = "original", name = "Doubao"), credentials("volc-test"))
        val openAi = CallVoiceProfile(id = "openai", name = "OpenAI", protocol = CallVoiceProtocol.OPENAI_REALTIME,
            model = OpenAiCallProtocol.MODEL, endpoint = OpenAiCallProtocol.ENDPOINT, speaker = "")
        assertTrue(runCatching { store.save(openAi.copy(id = volc.id)) }.isFailure)
        assertTrue(runCatching { store.save(openAi, VolcRealtimeCredentials(appId = "app", accessKey = "legacy")) }.isFailure)
        val saved = store.save(openAi, credentials("openai-test"))
        assertTrue(runCatching { store.save(saved.copy(endpoint = "wss://voice.example.com")) }.isFailure)
        val reopened = store(backend)
        assertEquals(CallVoiceProtocol.VOLC_DUPLEX, reopened.get(volc.id)?.protocol)
        assertEquals(OpenAiCallProtocol.VOICE, reopened.get(saved.id)?.speaker)
        assertEquals("openai-test", reopened.credentials(saved.id)?.apiKey)
        assertEquals("volc-test", reopened.credentials(volc.id)?.apiKey)
    }

    @Test fun activeProfileCannotBeDeletedAndInvalidEndpointsAreRejected() {
        val store = store(Backend())
        store.save(CallVoiceProfile(id = "active", name = "Active"), credentials("secret"))
        store.save(CallVoiceProfile(id = "other", name = "Other"), credentials("other-secret"))

        assertTrue(runCatching { store.delete("active", "active") }.isFailure)
        store.delete("other", "active")
        assertNull(store.get("other"))
        assertNull(store.credentials("other"))
        assertTrue(runCatching {
            store.save(CallVoiceProfile(id = "bad", name = "Bad", endpoint = "https://voice.example.com"))
        }.isFailure)
        assertEquals(VolcRealtimeConfig.DEFAULT_SPEAKER,
            store.save(CallVoiceProfile(id = "blank-voice", name = "Blank voice", speaker = "")).speaker)
    }

    @Test fun realtimeProfileUsesUserSuppliedModelAndSpeakerIds() {
        val store = store(Backend())
        val saved = store.save(CallVoiceProfile(name = "Realtime", model = "fishaudio/drama-3-int4", speaker = "custom_tts_voice"))
        assertEquals("fishaudio/drama-3-int4", saved.model)
        assertEquals("custom_tts_voice", saved.speaker)
        assertEquals(saved, store.get(saved.id))
    }

    @Test fun unreadableStoreFallsBackWithoutOverwritingEncryptedData() {
        val backend = Backend("not-json")
        val store = store(backend)
        assertFalse(store.load())
        assertNull(store.get(CallVoiceProfile.DEFAULT_ID))
        assertNull(store.credentials(CallVoiceProfile.DEFAULT_ID))
        store.migrateLegacy(CallConfig()) { credentials("new-secret") }
        assertEquals("not-json", backend.value)
        assertTrue(runCatching {
            store.save(CallVoiceProfile(id = "new", name = "New"), credentials("new-secret"))
        }.isFailure)
        assertEquals("not-json", backend.value)
    }

    @Test fun encryptedStorageIsDecryptedWhenProfilesAreReopened() {
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain
            override fun decrypt(data: ByteArray): ByteArray = data
        }
        val backend = Backend()
        val store = CallVoiceProfileStore(backend, cipher, newId = { "profile-new" })
        store.save(CallVoiceProfile(id = "secure", name = "Secure"), credentials("secure-secret"))

        val reopened = CallVoiceProfileStore(backend, cipher, newId = { "profile-new" })
        assertTrue(reopened.load())
        assertEquals("secure-secret", reopened.credentials("secure")?.apiKey)
        assertFalse(backend.value!!.contains("secure-secret"))
    }

    @Test fun protocolUsesTheSelectedRealtimeModel() {
        val frame = Json.parseToJsonElement(VolcRealtimeProtocol.create("event", VolcRealtimeConfig(model = "custom-realtime"))).jsonObject
        assertEquals("custom-realtime", frame.getValue("session").jsonObject.getValue("model").jsonPrimitive.content)
    }
}
