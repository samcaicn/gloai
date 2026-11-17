package com.wangzan101.mika.settings

import com.wangzan101.mika.core.fetch.TinyFishFetch
import com.wangzan101.mika.core.fetch.WebFetchProvider
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchSettingsTest {
    private val identityCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    private fun settings(prefs: MemoryPrefs = MemoryPrefs()) = WebFetchSettings(prefs, identityCipher)

    @Test fun freshInstallIsOffAndPicksTinyFish() {
        val config = settings().get()
        assertFalse(config.enabled)
        assertEquals(WebFetchProvider.TinyFish, config.provider)
        assertNull(settings().service())
    }

    @Test fun anEnabledKeyCreatesTheService() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebFetchConfig(true, entries = mapOf(WebFetchProvider.TinyFish to WebFetchEntry("tinyfish-key"))))
        assertTrue(settings(prefs).service() is TinyFishFetch)
        assertEquals("tinyfish-key", settings(prefs).get().current.apiKey)
    }

    @Test fun theKeyIsStoredEncryptedAndTrimmed() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebFetchConfig(true, entries = mapOf(WebFetchProvider.TinyFish to WebFetchEntry("  tinyfish-key  "))))
        val stored = prefs.getString("api_key_encrypted_tinyfish", null)!!
        assertFalse(stored.contains("tinyfish-key"))
        assertEquals("tinyfish-key", settings(prefs).get().current.apiKey)
        assertEquals("tinyfish-key", String(Base64.getDecoder().decode(stored)))
    }

    @Test fun aDisabledServiceIsNotCreated() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebFetchConfig(false, entries = mapOf(WebFetchProvider.TinyFish to WebFetchEntry("tinyfish-key"))))
        assertNull(settings(prefs).service())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aKeyIsRequiredEvenWhenOff() {
        settings().set(WebFetchConfig(false, entries = mapOf(WebFetchProvider.TinyFish to WebFetchEntry(" "))))
    }

    @Test fun anUnknownStoredProviderFallsBackSafely() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("enabled", true).putString("provider", "gone").commit()
        assertEquals(WebFetchProvider.TinyFish, settings(prefs).get().provider)
    }
}