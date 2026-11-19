package com.wangzan101.mika.settings

import com.wangzan101.mika.core.search.DeepSeekSearch
import com.wangzan101.mika.core.search.ExaSearch
import com.wangzan101.mika.core.search.JinaSearch
import com.wangzan101.mika.core.search.SearxngSearch
import com.wangzan101.mika.core.search.TinyFishSearch
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.core.search.YouSearch
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSettingsTest {
    private val identityCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    private fun settings(prefs: MemoryPrefs = MemoryPrefs()) = WebSearchSettings(prefs, identityCipher)

    /** 升级前只存了 enabled、endpoint、api_key_encrypted。 */
    private fun legacy(prefs: MemoryPrefs, endpoint: String, key: String) {
        prefs.edit().putBoolean("enabled", true).putString("endpoint", endpoint)
            .putString("api_key_encrypted", Base64.getEncoder().encodeToString(key.toByteArray())).commit()
    }

    @Test fun freshInstallIsOffAndPicksYou() {
        val config = settings().get()
        assertFalse(config.enabled)
        assertEquals(WebSearchProvider.You, config.provider)
        assertNull(settings().service())
    }

    @Test fun youWorksWithoutAKey() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebSearchConfig(true, WebSearchProvider.You))
        assertTrue(settings(prefs).service() is YouSearch)
    }

    @Test fun eachProviderKeepsItsOwnKey() {
        val prefs = MemoryPrefs()
        settings(prefs).set(
            WebSearchConfig(
                true, WebSearchProvider.Exa,
                mapOf(
                    WebSearchProvider.You to WebSearchEntry(apiKey = "you-key"),
                    WebSearchProvider.Exa to WebSearchEntry(apiKey = "exa-key"),
                    WebSearchProvider.Jina to WebSearchEntry(apiKey = "jina-key"),
                ),
            ),
        )
        val back = settings(prefs).get()
        assertEquals("you-key", back.entries[WebSearchProvider.You]!!.apiKey)
        assertEquals("exa-key", back.current.apiKey)
        assertEquals("jina-key", back.entries[WebSearchProvider.Jina]!!.apiKey)
        assertFalse(prefs.getString("api_key_encrypted_exa", null)!!.contains("exa-key"))
        assertTrue(settings(prefs).service() is ExaSearch)
    }

    @Test fun anOldExaUrlBecomesExaWithItsKey() {
        val prefs = MemoryPrefs()
        legacy(prefs, "https://api.exa.ai/search", "exa-key")
        val config = settings(prefs).get()
        assertTrue(config.enabled)
        assertEquals(WebSearchProvider.Exa, config.provider)
        assertEquals("exa-key", config.current.apiKey)
        assertTrue(settings(prefs).service() is ExaSearch)
        // 保存后写成新的样子,旧的两个键删掉
        settings(prefs).set(config)
        assertNull(prefs.getString("endpoint", null))
        assertNull(prefs.getString("api_key_encrypted", null))
        assertEquals("exa-key", settings(prefs).get().entries[WebSearchProvider.Exa]!!.apiKey)
    }

    @Test fun anOldJinaUrlBecomesJina() {
        val prefs = MemoryPrefs()
        legacy(prefs, "https://s.jina.ai/", "jina-key")
        assertEquals(WebSearchProvider.Jina, settings(prefs).get().provider)
        assertTrue(settings(prefs).service() is JinaSearch)
    }

    @Test fun anyOtherOldUrlBecomesYouButOffAndDropsTheKey() {
        val prefs = MemoryPrefs()
        legacy(prefs, "https://example.com/search", "someone-elses-key")
        val config = settings(prefs).get()
        assertFalse(config.enabled)
        assertEquals(WebSearchProvider.You, config.provider)
        assertEquals("", config.current.apiKey)
        assertNull(settings(prefs).service())
    }

    @Test(expected = IllegalArgumentException::class)
    fun jinaCannotBeSavedWithoutAKey() {
        settings().set(WebSearchConfig(true, WebSearchProvider.Jina))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tinyFishCannotBeSavedWithoutAKey() {
        settings().set(WebSearchConfig(true, WebSearchProvider.TinyFish))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exaCannotBeSavedWithoutAKeyEvenWhenOff() {
        settings().set(WebSearchConfig(false, WebSearchProvider.Exa, mapOf(WebSearchProvider.Exa to WebSearchEntry(apiKey = " "))))
    }

    @Test fun tinyFishUsesOnlyItsKey() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebSearchConfig(true, WebSearchProvider.TinyFish, mapOf(WebSearchProvider.TinyFish to WebSearchEntry(apiKey = "tinyfish-key"))))
        assertTrue(settings(prefs).service() is TinyFishSearch)
    }

    @Test fun deepSeekUsesOnlyItsKey() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebSearchConfig(true, WebSearchProvider.DeepSeek, mapOf(WebSearchProvider.DeepSeek to WebSearchEntry(apiKey = "deepseek-key"))))
        assertTrue(settings(prefs).service() is DeepSeekSearch)
        assertEquals("deepseek-key", settings(prefs).get().entries[WebSearchProvider.DeepSeek]!!.apiKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deepSeekCannotBeSavedWithoutAKey() {
        settings().set(WebSearchConfig(true, WebSearchProvider.DeepSeek))
    }

    @Test fun searxngUsesTheInstanceAndNoKey() {
        val prefs = MemoryPrefs()
        settings(prefs).set(WebSearchConfig(true, WebSearchProvider.SearXNG, mapOf(WebSearchProvider.SearXNG to WebSearchEntry("https://searx.example.org"))))
        assertTrue(settings(prefs).service() is SearxngSearch)
        assertEquals("https://searx.example.org", settings(prefs).get().current.endpoint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun enabledSearxngNeedsAnAddress() {
        settings().set(WebSearchConfig(true, WebSearchProvider.SearXNG))
    }

    @Test fun anUnknownStoredProviderFallsBackSafely() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("enabled", true).putString("provider", "gone").commit()
        assertEquals(WebSearchProvider.You, settings(prefs).get().provider)
    }
}
