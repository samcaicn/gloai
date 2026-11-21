package com.wangzan101.mika.settings

import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.usage.AllowanceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceRouteTest {
    @Test fun everyBuiltInConnectionHasAnAllowanceRouteOrLocalExplanation() {
        val sources = mapOf(
            "DeepSeek" to AllowanceSource.DeepSeek,
            "Kimi" to AllowanceSource.Kimi,
            "硅基流动" to AllowanceSource.SiliconFlow,
            "OpenRouter" to AllowanceSource.OpenRouter,
        )
        for (preset in ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }) {
            val route = AllowanceRoute.forProvider(Provider("p", preset.label, preset.kind, preset.baseUrl))
            assertEquals(preset.label, sources[preset.label], route.source)
            if (preset.selfHosted) assertTrue(preset.label, route.selfHosted)
            else assertNotNull(preset.label, route.dashboard)
        }
    }

    @Test fun openDesignGoOpensItsDashboardWithoutAnAutomaticKeyQuery() {
        val go = route("https://amr-link.open-design.ai/v1")
        assertEquals("https://open-design.ai/cloud/dashboard", go.dashboard)
        assertNull(go.source)
        assertEquals(AllowanceRoute(), route("https://amr-link.open-design.ai.example.test/v1"))
    }

    @Test fun onlyRecognizedOfficialAddressesCanUseTheSavedKey() {
        assertEquals(AllowanceSource.DeepSeek, route("https://api.deepseek.com/v1/").source)
        assertEquals(AllowanceSource.Kimi, route("https://api.moonshot.cn/v1/").source)
        for (url in listOf(
            "https://api.deepseek.com.example.test/v1", "https://api.deepseek.com/proxy", "http://api.deepseek.com",
            "https://api.deepseek.com:8443", "https://proxy@api.deepseek.com", "https://api.deepseek.com?proxy=1",
            "https://api.deepseek.com#proxy", "https://openrouter.ai/api/v1/other", "http://127.0.0.1:8080/v1",
        )) {
            assertEquals(url, AllowanceRoute(), route(url))
        }
        assertTrue(route("http://192.168.1.10:11434/v1", name = "Ollama").selfHosted)
    }

    @Test fun accountLoginSelectsTheCorrectUsagePageAndSharesOnlyTheOpenRouterKeyQuery() {
        val chatGpt = route("https://api.openai.com/v1", kind = ProviderConfig.Kind.ChatGpt)
        assertEquals(ChatGptAuth.USAGE_URL, chatGpt.dashboard)
        assertNull(chatGpt.source)
        assertNotEquals(chatGpt.dashboard, route("https://api.openai.com/v1").dashboard)

        val grok = route("https://api.x.ai/v1", kind = ProviderConfig.Kind.Xai)
        assertNull(grok.source)
        assertNotEquals(grok.dashboard, route("https://api.x.ai/v1").dashboard)

        val openRouter = Provider("p", "OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1")
        assertEquals(AllowanceRoute.forProvider(openRouter),
            AllowanceRoute.forProvider(openRouter.copy(signIn = ProviderStore.OPENROUTER_SIGN_IN)))
    }

    private fun route(url: String, name: String = "DeepSeek", kind: ProviderConfig.Kind = ProviderConfig.Kind.OpenAiCompat) =
        AllowanceRoute.forProvider(Provider("p", name, kind, url))
}
