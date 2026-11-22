package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.PromptCaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsTest {
    private val base = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Explicit)

    @Test fun unknownCapabilitiesKeepPresetValues() {
        val o = ModelOptions.merge(listOf(ListedModel("m")), base).single()
        assertEquals(base, o.capabilities)
        assertFalse(o.toolCallsKnown)
        assertFalse(o.visionKnown)
        assertFalse(o.contextKnown)
    }

    @Test fun knownCapabilitiesOverridePresetButKeepCaching() {
        val o = ModelOptions.merge(listOf(ListedModel("m", toolCalls = false, vision = true, contextTokens = 1_000_000, reasoning = true)), base).single()
        assertEquals(Capabilities(false, true, 1_000_000, true, promptCaching = PromptCaching.Explicit), o.capabilities)
        assertTrue(o.toolCallsKnown && o.visionKnown && o.contextKnown)
        assertFalse(o.canBeMain)
    }

    @Test fun userSaidVisionWinsOverListingAndPreset() {
        assertTrue("没写 + 用户说能看", ModelOptions.seesImages(stored = false, userSaid = true, usedForVision = false))
        assertTrue("看图也用它", ModelOptions.seesImages(stored = false, userSaid = null, usedForVision = true))
        assertFalse("用户说不能看", ModelOptions.seesImages(stored = true, userSaid = false, usedForVision = true))
        assertFalse("列表说不能看,用户没改", ModelOptions.seesImages(stored = false, userSaid = null, usedForVision = false))
        val p = Provider("p", "P", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1",
            models = listOf(ListedModel("a"), ListedModel("b", vision = false)), visionOverrides = mapOf("a" to true))
        val (a, b) = ModelOptions.ofProvider(p, emptyList())
        assertTrue(a.canSee)
        assertEquals(true, a.visionByUser)
        assertFalse(b.canSee)
        assertEquals(null, b.visionByUser)
    }

    @Test fun toolCapableFirstAndDefaultMainPrefersConfirmed() {
        val listed = listOf(ListedModel("embed", toolCalls = false), ListedModel("guess"), ListedModel("sure", toolCalls = true))
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("guess", "sure", "embed"), options.map { it.id })
        assertEquals("sure", ModelOptions.defaultMain(options))
        assertEquals("guess", ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("guess")), base)))
        assertEquals(null, ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("embed", toolCalls = false)), base)))
    }

    /** 先自家的(预设的模型词),再接口明说能调用工具的,其余照列表顺序;明说不会调用工具的、像向量 / 语音 / 画图的不试。 */
    @Test fun candidatesPreferTheProvidersOwnFamily() {
        val listed = listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true),
            ListedModel("text-embedding-v4"),
            ListedModel("gte-rerank-v2"),
            ListedModel("qwen3-tts-flash"),
            ListedModel("wanx-image", chat = false),
            ListedModel("qwen-max"),
            ListedModel("qwen-plus", toolCalls = true),
            ListedModel("Other/Chat", displayName = "Qwen inside"),
            ListedModel("qwen-coder", toolCalls = false),
            ListedModel("moonshot-v1"),
        )
        val options = ModelOptions.merge(listed, base)
        val qwen = ProviderPresets.all.single { it.label == "通义千问" }.familyTokens
        assertEquals(listOf("qwen"), qwen)
        assertEquals(
            listOf("qwen-plus", "qwen-max", "Other/Chat", "antdigital/Ling-3.1-flash", "moonshot-v1"),
            ModelOptions.candidates(options, qwen),
        )
        assertEquals("qwen-plus", ModelOptions.defaultMain(options, qwen))
        // 认不出是哪家:接口明说能调用工具的在前,其余照列表顺序
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus", "qwen-max", "Other/Chat", "moonshot-v1"), ModelOptions.candidates(options))
        assertEquals(qwen, ModelOptions.familyOf("https://dashscope.aliyuncs.com/compatible-mode/v1/"))
        assertEquals(emptyList<String>(), ModelOptions.familyOf("https://openrouter.ai/api/v1"))
    }

    private fun ids(listed: List<ListedModel>, tokens: List<String>) = ModelOptions.candidates(ModelOptions.merge(listed, base), tokens)

    /** 自家的模型按版本新的在前,不管接口给的顺序;同版本的照列表顺序,不评 flash、pro、lite 哪个好。 */
    @Test fun ownModelsNewestVersionFirst() {
        assertEquals(
            listOf("x4", "x-3.1-pro", "x-3-flash", "x-3-pro", "x-2.5-flash", "x-2.0-flash-001"),
            ids(listOf("x-2.5-flash", "x-3-flash", "x-2.0-flash-001", "x-3.1-pro", "x4", "x-3-pro").map { ListedModel(it) }, listOf("x")),
        )
        // 横线分开的小版本、带字母的版本号;b / k 结尾的是参数量、上下文长度,不算版本
        assertEquals(
            listOf("claude-haiku-4-5-20251001", "claude-opus-4-1-20250805", "claude-3-5-haiku-20241022"),
            ids(listOf("claude-3-5-haiku-20241022", "claude-opus-4-1-20250805", "claude-haiku-4-5-20251001").map { ListedModel(it) }, listOf("claude")),
        )
        assertEquals(listOf("qwen3-max", "qwen2.5-72b-instruct"), ids(listOf(ListedModel("qwen2.5-72b-instruct"), ListedModel("qwen3-max")), listOf("qwen")))
    }

    /** 同一个版本:正式版在前,再是 latest 别名,预览、试验的在后;同一档里没写日期的在前,再按日期新的在前。 */
    @Test fun stableBeforePreviewAtTheSameVersion() {
        assertEquals(
            listOf("x-2.5-flash", "x-2.5-flash-2025-08-07", "x-2.5-flash-latest", "x-2.5-flash-preview-09-2025", "x-2.5-flash-exp-0827", "x-2.5-flash-preview-05-20"),
            ids(
                listOf("x-2.5-flash-exp-0827", "x-2.5-flash-preview-05-20", "x-2.5-flash-latest", "x-2.5-flash-preview-09-2025", "x-2.5-flash-2025-08-07", "x-2.5-flash")
                    .map { ListedModel(it) },
                listOf("x"),
            ),
        )
        // 更新的版本哪怕只是预览,也排在老版本的正式版前面
        assertEquals(listOf("x-3-pro-preview", "x-2.5-pro"), ids(listOf(ListedModel("x-2.5-pro"), ListedModel("x-3-pro-preview")), listOf("x")))
    }

    /** 读不出版本的(别名之类)在读得出的后面,彼此照列表顺序。 */
    @Test fun unparsableVersionsAfterParsedOnes() {
        assertEquals(
            listOf("x-2-mini", "x-1.5", "x-flash-latest", "x-chat", "x-pro"),
            ids(listOf("x-flash-latest", "x-chat", "x-1.5", "x-pro", "x-2-mini").map { ListedModel(it) }, listOf("x")),
        )
    }

    /** 外层先后不变:自家的在前,再是接口明说能调用工具的;版本只在自家同一档里比,别家的照列表顺序。 */
    @Test fun versionOrderStaysInsideTheOuterOrder() {
        val listed = listOf(
            ListedModel("other-9"),
            ListedModel("x-1", toolCalls = true),
            ListedModel("x-3"),
            ListedModel("x-2", toolCalls = true),
            ListedModel("other-1", toolCalls = true),
            ListedModel("other-5"),
        )
        assertEquals(listOf("x-2", "x-1", "x-3", "other-1", "other-9", "other-5"), ids(listed, listOf("x")))
        // 没有自家的词:照旧,不按版本排
        assertEquals(listOf("x-1", "x-2", "other-1", "other-9", "x-3", "other-5"), ids(listed, emptyList()))
    }

    /** 接口列出来老的 2.5 在前:先试的是最新的,向量模型、别家的 gemma 不先试。 */
    @Test fun realisticGeminiListTriesTheNewestFirst() {
        val listed = listOf(
            ListedModel("gemini-2.5-flash", displayName = "Gemini 2.5 Flash"),
            ListedModel("gemini-2.5-pro", displayName = "Gemini 2.5 Pro"),
            ListedModel("gemini-2.0-flash", displayName = "Gemini 2.0 Flash"),
            ListedModel("gemini-2.0-flash-001", displayName = "Gemini 2.0 Flash 001"),
            ListedModel("gemini-2.0-flash-lite", displayName = "Gemini 2.0 Flash-Lite"),
            ListedModel("gemini-2.5-flash-preview-09-2025", displayName = "Gemini 2.5 Flash Preview Sep 2025"),
            ListedModel("gemini-flash-latest", displayName = "Gemini Flash Latest"),
            ListedModel("gemini-3-pro-preview", displayName = "Gemini 3 Pro Preview"),
            ListedModel("gemma-3-27b-it", displayName = "Gemma 3 27B"),
            ListedModel("gemini-3.1-pro", displayName = "Gemini 3.1 Pro"),
            ListedModel("gemini-3-flash", displayName = "Gemini 3 Flash"),
            ListedModel("gemini-embedding-001", displayName = "Gemini Embedding 001"),
        )
        val gemini = ProviderPresets.all.single { it.label == "Gemini" }.familyTokens
        assertEquals(
            listOf(
                "gemini-3.1-pro", "gemini-3-flash", "gemini-3-pro-preview", "gemini-2.5-flash", "gemini-2.5-pro",
                "gemini-2.5-flash-preview-09-2025", "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.0-flash-001",
                "gemini-flash-latest", "gemma-3-27b-it",
            ),
            ids(listed, gemini),
        )
        assertEquals("gemini-3.1-pro", ModelOptions.defaultMain(ModelOptions.merge(listed, base), gemini))
    }

    @Test fun nonChatModelsAreRecognised() {
        fun opt(id: String, chat: Boolean? = null) = ModelOptions.merge(listOf(ListedModel(id, chat = chat)), base).single()
        for (id in listOf("text-embedding-3-small", "bge-reranker", "whisper-1", "gpt-4o-mini-tts", "gpt-4o-transcribe", "dall-e-3",
            "gpt-image-1", "qwen-vl-ocr", "paraformer-realtime-v2", "omni-moderation-latest", "qwen3-asr-flash")) {
            assertTrue(id, ModelOptions.looksNonChat(opt(id)))
        }
        for (id in listOf("qwen-plus", "deepseek-chat", "gpt-5-mini", "claude-sonnet-4-5", "qwen-vl-max", "step-2-16k")) {
            assertFalse(id, ModelOptions.looksNonChat(opt(id)))
        }
        assertFalse("接口说是对话模型就照接口", ModelOptions.looksNonChat(opt("my-embed-chat", chat = true)))
        assertTrue(ModelOptions.looksNonChat(opt("x", chat = false)))
        // 全都像别的用途:没有候选,默认主模型退回第一个可当主模型的
        val only = ModelOptions.merge(listOf(ListedModel("text-embedding-v4")), base)
        assertEquals(emptyList<String>(), ModelOptions.candidates(only))
        assertEquals("text-embedding-v4", ModelOptions.defaultMain(only))
    }

    @Test fun modelsOnlyReachableByUnsupportedProtocolsAreNotOffered() {
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/openai/v1")
        val listed = listOf(
            ListedModel("realtime-only", toolCalls = true, unsupportedApis = listOf("realtime")),
            ListedModel("chat", toolCalls = true, endpoint = chat),
            ListedModel("responses", toolCalls = true, endpoint = ModelEndpoint(ModelProtocol.Responses, "https://gw.test/openai/v1")),
            ListedModel("plain"),
        )
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("chat", "responses", "plain"), options.map { it.id })
        assertEquals("chat", ModelOptions.defaultMain(options))
    }

    @Test fun openCodeGoPresetListsFromItsCatalogAddress() {
        val p = ProviderPresets.all.single { it.label == "OpenCode Go" }
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals("https://opencode.ai/zen/go/v1", p.baseUrl)
        assertEquals(p, ProviderPresets.forBaseUrl("https://opencode.ai/zen/go/v1/"))
    }

    @Test fun contextLabels() {
        assertEquals("64K", ModelOptions.contextLabel(64_000))
        assertEquals("128K", ModelOptions.contextLabel(131_072))
        assertEquals("200K", ModelOptions.contextLabel(200_000))
        assertEquals("1M", ModelOptions.contextLabel(1_048_576))
        assertEquals("2M", ModelOptions.contextLabel(2_000_000))
    }

    @Test fun sameProviderKeyIgnoresTrailingSlash() {
        assertEquals(
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "DeepSeek"),
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "DeepSeek "),
        )
        val local = ProviderConfig("3", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "local", base)
        assertEquals("192.168.1.5", ProviderConnection.displayName(local))
    }

    @Test fun providerOptionsAreRememberedListPlusSavedConfigs() {
        val p = Provider("p", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", listOf(ListedModel("a"), ListedModel("b")))
        val savedCaps = base.copy(vision = true)
        val configs = listOf(
            ProviderConfig("1", p.kind, p.baseUrl, "b", savedCaps, p.name, "p"),
            ProviderConfig("2", p.kind, p.baseUrl, "typed", base, p.name, "p"),
            ProviderConfig("3", p.kind, p.baseUrl, "other", base, p.name, "q"),
        )
        val options = ModelOptions.ofProvider(p, configs)
        assertEquals(listOf("a", "b", "typed"), options.map { it.id })
        assertTrue("已有配置的用配置里的能力", options.first { it.id == "b" }.capabilities.vision)
        assertEquals(emptyList<String>(), ModelOptions.ofProvider(p.copy(models = null), emptyList()).map { it.id })
    }

    @Test fun ttsMarksDoNotFilterThePicker() {
        val ids = listOf("fishaudio/drama-3-int4", "renamed-model", "vendor/chat-tts", "speech-endpoint")
        val catalog = ids.map { ListedModel(it, chat = false) }
        val p = Provider("p", "Local", ProviderConfig.Kind.OpenAiCompat, "https://local.test/v1", models = catalog)
        assertEquals(ids.toSet(), ModelOptions.ofProvider(p, emptyList()).map { it.id }.toSet())
        for (id in ids) {
            val marked = p.copy(ttsModels = setOf(id))
            assertEquals(ids.toSet(), ModelOptions.ofProvider(marked, emptyList()).map { it.id }.toSet())
            assertEquals(ids.toSet(), ModelOptions.ofProvider(marked, emptyList(), includeTts = true).map { it.id }.toSet())
        }
    }

    /** 只写了 Mika 不会说的协议的模型:对话用途的选择器不列(调用必然失败),服务商详情和声音模型照样列出。 */
    @Test fun unusableModelsAreHiddenFromRolePickersButShownInTheFullCatalog() {
        val p = Provider("p", "Local", ProviderConfig.Kind.OpenAiCompat, "https://local.test/v1", models = listOf(
            ListedModel("chat-model"),
            ListedModel("speech-only", unsupportedApis = listOf("/v1/audio/speech")),
            ListedModel("routed", unsupportedApis = listOf("/v1/audio/speech"), endpoint = ModelEndpoint(ModelProtocol.ChatCompletions, "https://local.test/v1")),
        ))
        assertEquals(listOf("chat-model", "routed"), ModelOptions.ofProvider(p, emptyList()).map { it.id })
        assertEquals(setOf("chat-model", "speech-only", "routed"), ModelOptions.ofProvider(p, emptyList(), includeTts = true).map { it.id }.toSet())
    }
}
