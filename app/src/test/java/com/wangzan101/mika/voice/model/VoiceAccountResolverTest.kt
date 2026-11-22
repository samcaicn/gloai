package com.wangzan101.mika.voice.model

import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAccountResolverTest {
    private class FakeProviders : VoiceProviderAccountSource {
        val accounts = mutableMapOf<String, Provider>()
        val keys = mutableMapOf<String, String>()
        val revisions = mutableMapOf<String, Long>()
        var activeKeyReads = 0

        override fun provider(id: String): Provider? = accounts[id]
        override fun activeApiKey(id: String): String? {
            activeKeyReads++
            return keys[id]
        }
        override fun hasKey(id: String): Boolean = id in keys
        override fun keyRevision(id: String): Long = revisions[id] ?: 0L
        override fun headers(provider: Provider): Map<String, String> =
            mapOf("User-Agent" to "Mika/test", "X-Session" to "session-${provider.id}")

        fun add(provider: Provider, key: String? = null) {
            accounts[provider.id] = provider
            if (key != null) keys[provider.id] = key
        }

        fun changeBaseUrl(id: String, value: String) {
            accounts[id] = accounts.getValue(id).copy(baseUrl = value)
        }
    }

    private class FakeOwned : VoiceOwnedAccountSource {
        val credentials = mutableMapOf<String, VoiceCredentials>()
        val tokens = mutableMapOf<String, String>()
        override fun read(secretId: String): VoiceCredentials? = credentials[secretId]
        override fun credentialsConfigured(secretId: String): Boolean = read(secretId) != null
        override fun configurationToken(secretId: String): String = tokens[secretId].orEmpty()
    }

    private class Backend : VoiceCredentialBackend {
        val values = mutableMapOf<String, String>()
        override fun read(secretId: String): String? = values[secretId]
        override fun write(secretId: String, value: String): Boolean {
            values[secretId] = value
            return true
        }
        override fun delete(secretId: String) {
            values.remove(secretId)
        }
    }

    private val providers = FakeProviders()
    private val owned = FakeOwned()
    private val resolver = VoiceAccountResolver(providers, owned)
    private val provider = Provider(
        id = "p1",
        name = "Provider",
        kind = ProviderConfig.Kind.OpenAiCompat,
        baseUrl = "https://old.example/v1",
        sessionHeader = "X-Session",
    )
    private val service = VoiceService("s1", "Speech", VoiceAccount.ExistingProvider("p1"))
    private val model = VoiceModel(
        serviceId = "s1",
        id = "whisper-1",
        displayName = "Whisper",
        stt = SttBinding(SttProtocol.AudioTranscriptions),
    )

    init {
        providers.add(provider, "active-key")
    }

    @Test fun `existing provider uses active key and request headers`() {
        val resolved = resolver.resolve(service)

        val account = resolved as ResolvedAccount.ExistingProvider
        assertEquals("active-key", account.credentials.apiKey)
        assertEquals("Mika/test", account.headers["User-Agent"])
        assertEquals("session-p1", account.headers["X-Session"])
        assertEquals(1, providers.activeKeyReads)
        assertTrue(account.credentialsConfigured)
    }

    @Test fun `account endpoint change invalidates resolved fingerprint`() {
        val first = resolver.resolve(service)
        providers.changeBaseUrl("p1", "https://new.example/v1")
        val second = resolver.resolve(service)

        assertNotEquals(first.configurationFingerprint, second.configurationFingerprint)
        val firstModel = resolver.resolve(service, model)
        providers.changeBaseUrl("p1", "https://third.example/v1")
        assertNotEquals(firstModel.configurationFingerprint, resolver.resolve(service, model).configurationFingerprint)
    }

    @Test fun `key revision and model binding invalidate full fingerprint without exposing secrets`() {
        val first = resolver.resolve(service, model)
        providers.revisions["p1"] = 2L
        val keyChanged = resolver.resolve(service, model)
        val bindingChanged = resolver.resolve(
            service,
            model.copy(stt = model.stt!!.copy(endpoint = "https://binding.example/v1")),
        )

        assertNotEquals(first.configurationFingerprint, keyChanged.configurationFingerprint)
        assertNotEquals(keyChanged.configurationFingerprint, bindingChanged.configurationFingerprint)
        assertFalse(first.configurationFingerprint.contains("active-key"))
        assertFalse(first.toString().contains("active-key"))
    }

    @Test fun `missing existing account fails during resolution`() {
        providers.accounts.remove("p1")

        val error = assertThrows(VoiceModelError.MissingAccount::class.java) { resolver.resolve(service) }
        assertEquals(VoiceErrorCategory.MISSING_CREDENTIALS, error.category)
        assertFalse(error.toString().contains("active-key"))
    }

    @Test fun `voice owned credentials are resolved only while their encrypted account exists`() {
        val voiceService = VoiceService("owned", "Owned", VoiceAccount.VoiceOwned("secret-1"))
        owned.credentials["secret-1"] = VoiceCredentials(appId = "app", accessKey = "access")

        val account = resolver.resolve(voiceService) as ResolvedAccount.VoiceOwned
        assertEquals("app", account.credentials.appId)
        assertEquals("access", account.credentials.accessKey)
        assertTrue(account.credentialsConfigured)

        owned.credentials.remove("secret-1")
        val error = assertThrows(VoiceModelError.MissingCredentials::class.java) { resolver.resolve(voiceService) }
        assertEquals(VoiceErrorCategory.MISSING_CREDENTIALS, error.category)
    }

    @Test fun `voice owned credential rotation invalidates the fingerprint without exposing either key`() {
        val voiceService = VoiceService("owned", "Owned", VoiceAccount.VoiceOwned("secret-1"))
        val voiceModel = model.copy(serviceId = voiceService.id)
        owned.credentials["secret-1"] = VoiceCredentials(apiKey = "first-secret")
        owned.tokens["secret-1"] = "ciphertext-version-1"
        val first = resolver.resolve(voiceService, voiceModel)

        owned.credentials["secret-1"] = VoiceCredentials(apiKey = "second-secret")
        owned.tokens["secret-1"] = "ciphertext-version-2"
        val second = resolver.resolve(voiceService, voiceModel)

        assertNotEquals(first.configurationFingerprint, second.configurationFingerprint)
        assertFalse(first.configurationFingerprint.contains("first-secret"))
        assertFalse(second.configurationFingerprint.contains("second-secret"))
    }

    @Test fun `none account resolves without credentials`() {
        val resolved = resolver.resolve(VoiceService("local", "Local", VoiceAccount.None))

        assertSame(ResolvedAccount.None, resolved)
        assertTrue(resolved.credentialsConfigured)
        assertEquals("", resolved.credentials.apiKey)
    }

    @Test fun `voice credential store encrypts all fields and never publishes plaintext`() {
        val backend = Backend()
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
            override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
        }
        val store = VoiceCredentialStore(backend, cipher)

        store.save("secret-1", VoiceCredentials("api-secret", "app-secret", "access-secret"))
        val firstToken = store.configurationToken("secret-1")
        val stored = backend.values.getValue("secret-1")
        assertFalse(stored.contains("api-secret"))
        assertFalse(stored.contains("app-secret"))
        assertFalse(stored.contains("access-secret"))
        assertTrue(store.credentialsConfigured("secret-1"))

        val restored = store.read("secret-1")!!
        assertEquals("api-secret", restored.apiKey)
        assertEquals("app-secret", restored.appId)
        assertEquals("access-secret", restored.accessKey)
        assertFalse(restored.toString().contains("api-secret"))
        assertFalse(restored.toString().contains("app-secret"))
        assertFalse(restored.toString().contains("access-secret"))

        store.save("secret-1", VoiceCredentials("rotated-secret"))
        assertNotEquals(firstToken, store.configurationToken("secret-1"))
        assertFalse(store.configurationToken("secret-1").contains("rotated-secret"))

        store.delete("secret-1")
        assertNull(store.read("secret-1"))
        assertFalse(store.credentialsConfigured("secret-1"))
    }
}
