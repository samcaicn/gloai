package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderKeySitesTest {

    @Test
    fun `已知服务商按主机名给出官方 Key 申请页`() {
        assertEquals("https://platform.deepseek.com/api_keys", ProviderKeySites.forBaseUrl("https://api.deepseek.com"))
        assertEquals("https://platform.openai.com/api-keys", ProviderKeySites.forBaseUrl("https://api.openai.com/v1"))
        assertEquals("https://aistudio.google.com/app/apikey", ProviderKeySites.forBaseUrl("https://generativelanguage.googleapis.com"))
        assertEquals("https://console.anthropic.com/settings/keys", ProviderKeySites.forBaseUrl("https://api.anthropic.com"))
        assertEquals("https://console.groq.com/keys", ProviderKeySites.forBaseUrl("https://api.groq.com/openai/v1"))
    }

    @Test
    fun `同一主机路径不同也认`() {
        // 自定义表单里手填了官方地址、路径和预设不一样,也按主机认出来
        assertEquals("https://platform.deepseek.com/api_keys", ProviderKeySites.forBaseUrl("https://api.deepseek.com/v1"))
    }

    @Test
    fun `认不出的地址不给链接`() {
        assertNull(ProviderKeySites.forBaseUrl("https://llm.example.com/v1"))
        assertNull(ProviderKeySites.forBaseUrl("http://127.0.0.1:11434/v1"))
        assertNull(ProviderKeySites.forBaseUrl(""))
        assertNull(ProviderKeySites.forBaseUrl("not a url"))
    }

    @Test
    fun `每个云服务商预设都能认出申请页`() {
        // 官方 Key 申请页暂未确认的预设:显式豁免,确认了就把映射补上、从这里去掉
        val unmapped = setOf("OpenDesign Go", "OpenCode Go")
        // 自建(本机地址)和自定义(空地址)不在内;映射表跟着预设清单走,漏了就失败
        val missing = ProviderPresets.all
            .filter { !it.selfHosted && it.baseUrl.isNotEmpty() }
            .filter { it.label !in unmapped }
            .filter { ProviderKeySites.forBaseUrl(it.baseUrl) == null }
            .map { it.label }
        assertEquals("以下预设缺官方 Key 申请页映射: $missing", emptyList<String>(), missing)
    }
}
