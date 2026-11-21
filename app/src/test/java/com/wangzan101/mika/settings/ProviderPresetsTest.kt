package com.wangzan101.mika.settings

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.AddForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    /** 地址、名字不重复(按地址认预设);云端的都走 https,明文 http 只给自己机器上跑的。 */
    @Test fun urlsAreUniqueAndOnlySelfHostedUsePlainHttp() {
        val withUrl = ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }
        assertEquals(withUrl.size, withUrl.map { it.baseUrl }.distinct().size)
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.label }.distinct().size)
        withUrl.forEach { p ->
            if (p.selfHosted) assertTrue(p.label, p.baseUrl.startsWith("http://127.0.0.1:"))
            else assertTrue(p.label, p.baseUrl.startsWith("https://"))
            assertEquals(p, ProviderPresets.forBaseUrl(p.baseUrl))
        }
    }

    /** 自己跑的服务:地址可改、Key 可不填,名字仍用预设名;云端预设地址不给改、Key 必填。 */
    @Test fun selfHostedPresetsHaveEditableUrlAndOptionalKey() {
        val ollama = AddForm.of(preset("Ollama")).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(ollama.urlEditable)
        assertFalse(ollama.keyRequired)
        assertTrue("不填 Key 也能测试", ollama.canTest)
        assertEquals("Ollama", ollama.providerName)
        val openAi = AddForm.of(preset("OpenAI"))
        assertFalse(openAi.urlEditable)
        assertTrue(openAi.keyRequired)
        assertFalse(openAi.canTest)
        assertTrue(openAi.copy(apiKey = "sk").canTest)
    }

    /** 主机名对得上就认出是哪家(路径不论);自己跑的服务、认不出的地址不算。 */
    @Test fun presetByHost() {
        assertEquals("Gemini", ProviderPresets.forHost("https://generativelanguage.googleapis.com")?.label)
        assertEquals("Gemini", ProviderPresets.forHost(" https://generativelanguage.googleapis.com/v1beta/openai/ ")?.label)
        assertEquals("Anthropic", ProviderPresets.forHost("https://api.anthropic.com/v1")?.label)
        assertEquals("OpenAI", ProviderPresets.forHost("https://api.openai.com")?.label)
        assertEquals("DeepSeek", ProviderPresets.forHost("https://api.deepseek.com/v1")?.label)
        assertEquals("智谱", ProviderPresets.forHost("https://open.bigmodel.cn/api/paas/v4")?.label)
        assertEquals("智谱 Coding Plan", ProviderPresets.forHost("https://open.bigmodel.cn/api/coding/paas/v4")?.label)
        assertNull(ProviderPresets.forHost("https://models.example.com/v1"))
        assertNull(ProviderPresets.forHost("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.forHost("not a url"))
        // 每个有地址、不是自己跑的预设都认得出自己
        ProviderPresets.all.filter { it.baseUrl.isNotEmpty() && !it.selfHosted }.forEach {
            assertEquals(it.label, ProviderPresets.forHost(it.baseUrl)?.label)
        }
    }

    /** DeepSeek 的 V4 两个模型上下文都是 1M:窗口按旧默认 64k 时压缩频繁改写前缀,前缀缓存整段失效。 */
    @Test fun deepSeekPresetUsesTheFullWindow() {
        val deepseek = preset("DeepSeek")
        assertEquals(1_000_000, deepseek.capabilities.contextTokens)
        assertFalse(deepseek.capabilities.vision)
    }

    /** 预置的思考档位只来自官方文档:DeepSeek 的 reasoning_effort 可能值是 none/low/high/max(none 关思考),别名不预置。 */
    @Test fun reasoningLevelsAreCuratedAndOfficialOnly() {
        assertEquals(listOf("none", "low", "high", "max"), preset("DeepSeek").reasoningLevels)
        // 没核实的店家一律不预置,界面上照旧不出档位(见 ReasoningOptions)
        ProviderPresets.all.filter { it.label != "DeepSeek" }.forEach { p ->
            assertEquals(p.label, emptyList<String>(), p.reasoningLevels)
        }
    }

    @Test fun openDesignGoUsesTheCompatibleEndpointAndRequiresTheUsersKey() {
        val go = preset("OpenDesign Go")
        assertEquals(ProviderConfig.Kind.OpenAiCompat, go.kind)
        assertEquals("https://amr-link.open-design.ai/v1", go.baseUrl)
        assertTrue(go.familyTokens.isEmpty())
        assertEquals(go, ProviderPresets.forHost("https://amr-link.open-design.ai/v1/models"))
        val form = AddForm.of(go)
        assertTrue(form.keyRequired)
        assertFalse(form.canTest)
        assertTrue(form.copy(apiKey = "test-key").canTest)
    }

    /** Coding Plan 的 Key 和标准 API 不通用;地址要落在官方分开的 OpenAI 兼容端点上。 */
    @Test fun codingPlanUsesItsOwnDocumentedEndpoint() {
        val standard = preset("智谱")
        val coding = preset("智谱 Coding Plan")
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", coding.baseUrl)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, coding.kind)
        assertNotEquals(standard.baseUrl, coding.baseUrl)
    }

    /** Cloudflare Workers AI 预设要求用户替换 {account_id}，地址可编辑，且能按模板匹配实际端点。 */
    @Test fun cloudflarePresetRequiresAccountIdAndHasEditableUrl() {
        val cf = preset("Cloudflare Workers AI")
        assertEquals(ProviderConfig.Kind.OpenAiCompat, cf.kind)
        assertTrue(cf.capabilities.vision)
        assertTrue(cf.familyTokens.contains("llama-3.3"))
        assertTrue(cf.urlEditable)

        val form = AddForm.of(cf)
        assertTrue(cf.urlEditable)
        assertTrue(form.isCloudflare)
        assertFalse("Cloudflare 单独展示 Account ID 输入项，隐藏原始 URL 输入框", form.urlEditable)
        assertTrue(form.keyRequired)
        assertEquals(tr(com.wangzan101.mika.R.string.model_url_account_id), form.urlProblem)
        assertFalse(form.canTest)

        val filledForm = form.copy(
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/test_account_id_123/ai/v1",
            apiKey = "test_key",
        )
        assertNull(filledForm.urlProblem)
        assertTrue(filledForm.canTest)

        assertEquals(cf, ProviderPresets.forBaseUrl("https://api.cloudflare.com/client/v4/accounts/test_account_id_123/ai/v1"))
        assertEquals(cf, ProviderPresets.forHost("https://api.cloudflare.com/client/v4/accounts/test_account_id_123/ai/v1"))
    }
}
