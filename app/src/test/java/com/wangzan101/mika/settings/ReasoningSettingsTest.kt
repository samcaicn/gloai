package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ReasoningSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 100_000, reasoning = true)
    private val setups = mutableListOf<ClientSetup>()
    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, setup ->
        setups += setup
        object : ChatModel {
            override val name = cfg.model
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
        }
    })
    private fun connected(s: ProviderStore) = s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, "https://example.test/v1", "k",
        listOf(ListedModel("m", reasoningEfforts = listOf("low", "medium", "high")), ListedModel("other", reasoningEfforts = emptyList())))

    @Test fun sameModelCanHaveDifferentEffortPerRoleAndSurvivesReload() {
        val s = store()
        val p = connected(s)
        ModelRole.entries.forEach { assertTrue(s.use(it, p.id, "m", caps)) }
        assertEquals(1, s.list().size)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(s.setReasoningEffort(ModelRole.Vision, "medium"))
        assertTrue(s.setReasoningEffort(ModelRole.Light, "low"))
        val reloaded = store()
        ModelRole.entries.forEach { reloaded.modelFor(it) }
        assertEquals(listOf("high", "medium", "low"), setups.map { it.reasoningEffort })
        assertEquals(listOf("low", "medium", "high"), reloaded.provider(p.id)!!.models!!.first().reasoningEfforts)
        assertEquals(emptyList<String>(), reloaded.provider(p.id)!!.models!![1].reasoningEfforts)
        assertTrue(reloaded.setReasoningEffort(ModelRole.Light, null))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Light))
        assertEquals("high", store().roles.value.reasoningEffort(ModelRole.Main))
    }

    @Test fun followingMainInheritsEffortAndChangingModelClearsOnlyItsOwnChoice() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        assertFalse(s.setReasoningEffort(ModelRole.Light, "low"))
        s.modelFor(ModelRole.Light)
        assertEquals("high", setups.last().reasoningEffort)
        s.use(ModelRole.Light, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Light, "low")
        s.use(ModelRole.Main, p.id, "other", caps)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Main))
        assertEquals("low", s.roles.value.reasoningEffort(ModelRole.Light))
        s.choose(ModelRole.Light, RoleChoice.SameAsMain)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Light))
        assertFalse(s.roles.value.reasoningEfforts.containsKey(ModelRole.Light))
    }

    @Test fun unsupportedLevelsAreRejectedAndOldSettingsKeepServerDefault() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "max"))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Main))
        val saved = Json.parseToJsonElement(tmp.root.resolve("providers.json").readText()).jsonObject
        assertFalse(saved["uses"]!!.jsonObject["Main"]!!.jsonObject.containsKey("reasoningEffort"))
        store().modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
        s.use(ModelRole.Main, p.id, "other", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "low"))
        assertTrue(s.setReasoningEffort(ModelRole.Main, null))
    }

    @Test fun providerReportedLevelsWorkForEveryProtocol() {
        val cfg = ProviderConfig("c", ProviderConfig.Kind.OpenAiCompat, "https://example.test", "m", caps)
        assertFalse(ReasoningOptions.of(cfg, null)!!.fromProvider)
        val chat = ListedModel(
            "m", endpoint = ModelEndpoint(ModelProtocol.ChatCompletions, "https://example.test"),
            reasoningEfforts = listOf("low", "high"),
        )
        listOf(ModelProtocol.ChatCompletions, ModelProtocol.Messages, ModelProtocol.GenerateContent, ModelProtocol.Responses)
            .forEach { protocol ->
                val listed = chat.copy(endpoint = ModelEndpoint(protocol, "https://example.test"))
                assertEquals(listOf("low", "high"), ReasoningOptions.of(cfg, listed)!!.levels)
                assertTrue(ReasoningOptions.of(cfg, listed)!!.fromProvider)
            }
    }

    @Test fun manualLevelsFollowTheProtocolAndNeedReasoningSupport() {
        val direct = ProviderConfig("c", ProviderConfig.Kind.OpenAiResponses, "https://example.test", "m", caps)
        assertFalse(ReasoningOptions.of(direct, null)!!.fromProvider)
        assertEquals(emptyList<String>(), ReasoningOptions.of(direct, ListedModel("m", reasoning = false))!!.levels)
        val xai = ProviderConfig("x", ProviderConfig.Kind.Xai, "https://example.test", "m", caps)
        assertFalse(ReasoningOptions.of(xai, null)!!.fromProvider)
        // 订阅登录不跟列表里写的别的协议走,档位也按它实际走的 Responses 给(和建模型同一个判断)
        val viaMessages = ListedModel("m", endpoint = ModelEndpoint(ModelProtocol.Messages, "https://example.test"))
        assertFalse(ReasoningOptions.of(xai, viaMessages)!!.fromProvider)
        assertEquals(ProviderConfig.Kind.Xai, ProviderStore.adapterFor(xai, viaMessages.endpoint).first)
        // 列表还没加载、或只说了能思考没给档位:OpenAI 兼容接口给通用档位并标来源不是服务商,
        // Anthropic / Gemini 仍不猜(各模型写法不一样,猜错了每次都被拒)
        val thinking = ListedModel("m", reasoning = true)
        val generic = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        for (kind in listOf(ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini)) {
            assertNull(ReasoningOptions.of(direct.copy(kind = kind), null))
            assertNull(ReasoningOptions.of(direct.copy(kind = kind), thinking))
        }
        val compat = direct.copy(kind = ProviderConfig.Kind.OpenAiCompat)
        assertEquals(generic, ReasoningOptions.of(compat, null)!!.levels)
        assertFalse(ReasoningOptions.of(compat, null)!!.fromProvider)
        assertEquals(generic, ReasoningOptions.of(compat, thinking)!!.levels)
        assertFalse(ReasoningOptions.of(compat, thinking)!!.fromProvider)
    }

    // 预设里官方文档写明档位的兼容服务商(DeepSeek)按预设给;列表写明过的仍以列表为准,自定义地址照旧不猜
    @Test fun presetLevelsFillInWhenTheListingDeclaresNone() {
        val s = store()
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "k", listOf(ListedModel("deepseek-flash")))
        s.use(ModelRole.Main, p.id, "deepseek-flash", caps)
        val options = ReasoningOptions.of(s.configFor(ModelRole.Main)!!, p.models!!.first())!!
        assertEquals(listOf("none", "low", "high", "max"), options.levels)
        assertFalse(options.fromProvider)
        // 档位进得了 setReasoningEffort 的校验,也到得了请求
        assertTrue(s.setReasoningEffort(ModelRole.Main, "none"))
        s.modelFor(ModelRole.Main)
        assertEquals("none", setups.last().reasoningEffort)
        // 列表写明档位的以列表为准
        val declared = ProviderConfig("c2", ProviderConfig.Kind.OpenAiCompat, "https://agg.example/v1", "m2", caps)
        val listed = ReasoningOptions.of(declared, ListedModel("m2", reasoningEfforts = listOf("low")))!!
        assertEquals(listOf("low"), listed.levels)
        assertTrue(listed.fromProvider)
        // 对不上预设的地址(第三方网关):给通用档位,入口不再整行消失
        val custom = ProviderConfig("c3", ProviderConfig.Kind.OpenAiCompat, "https://custom.example/v1", "m3", caps)
        assertFalse(ReasoningOptions.of(custom, ListedModel("m3"))!!.fromProvider)
    }

    // DeepSeek 列表只写开着思考的强度(low / high / max),官方文档另写 none 关掉思考:读到列表后照样关得掉(#563)
    @Test fun listedLevelsKeepTheDocumentedOffSwitch() {
        val deepSeek = ListedModel("deepseek-flash", reasoningEfforts = listOf("low", "high", "max"))
        val official = ProviderConfig("d1", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "deepseek-flash", caps)
        val options = ReasoningOptions.of(official, deepSeek)!!
        assertEquals(listOf("none", "low", "high", "max"), options.levels)
        assertTrue(options.fromProvider)
        // 地址写成 /v1、对不上预设时,以前整行没有,现在照列表给
        val v1 = official.copy(baseUrl = "https://api.deepseek.com/v1")
        assertEquals(listOf("low", "high", "max"), ReasoningOptions.of(v1, deepSeek)!!.levels)
        // 列表已有 none、或这家文档没写 none、或列表说没有档位:都不补
        assertEquals(listOf("none", "high"), ReasoningOptions.of(official, ListedModel("m", reasoningEfforts = listOf("none", "high")))!!.levels)
        val agg = ProviderConfig("d2", ProviderConfig.Kind.OpenAiCompat, "https://agg.example/v1", "m", caps)
        assertEquals(listOf("low"), ReasoningOptions.of(agg, ListedModel("m", reasoningEfforts = listOf("low")))!!.levels)
        assertEquals(emptyList<String>(), ReasoningOptions.of(official, ListedModel("m", reasoningEfforts = emptyList()))!!.levels)
        // 列表让这个模型走别的协议(这里是 Messages)时,OpenAI 兼容文档里的 none 不适用
        val viaMessages = deepSeek.copy(endpoint = ModelEndpoint(ModelProtocol.Messages, "https://api.deepseek.com/anthropic", null))
        assertEquals(listOf("low", "high", "max"), ReasoningOptions.of(official, viaMessages)!!.levels)
    }

    // Anthropic 列表写明的档位照给;只接受按预算思考的模型能思考但没有可选档位,也不接受档位
    @Test fun anthropicOffersExactlyTheCatalogLevels() {
        val cfg = ProviderConfig("c", ProviderConfig.Kind.Anthropic, "https://api.anthropic.example", "m", caps)
        val adaptive = ListedModel("m", reasoning = true, reasoningEfforts = listOf("low", "medium", "high"),
            thinkingTypes = listOf("adaptive", "enabled"))
        assertEquals(listOf("low", "medium", "high"), ReasoningOptions.of(cfg, adaptive)!!.levels)
        // 不接受 disabled 的,列表就没有 none
        assertFalse("none" in ReasoningOptions.of(cfg, adaptive)!!.levels)
        val budgetOnly = ListedModel("m", reasoning = true, reasoningEfforts = emptyList(), thinkingTypes = listOf("enabled", "disabled"))
        assertEquals(emptyList<String>(), ReasoningOptions.of(cfg, budgetOnly)!!.levels)

        val s = store()
        val p = s.connect("A", ProviderConfig.Kind.Anthropic, "https://api.anthropic.example", "k", listOf(budgetOnly))
        s.use(ModelRole.Main, p.id, "m", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "high"))
        assertFalse(s.setReasoningEffort(ModelRole.Main, "none"))
    }

    // 列表写明的思考方式随列表存下,建模型时交给适配器;旧版本存的列表没有这一项,不给档位、不出错
    @Test fun thinkingTypesSurviveReloadAndOldListsGetNoOptions() {
        val s = store()
        val listed = ListedModel("m", reasoning = true, reasoningEfforts = listOf("none", "high"), thinkingTypes = listOf("adaptive", "disabled"))
        val p = s.connect("A", ProviderConfig.Kind.Anthropic, "https://api.anthropic.example", "k", listOf(listed))
        s.use(ModelRole.Main, p.id, "m", caps)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val reloaded = store()
        assertEquals(listed, reloaded.provider(p.id)!!.models!!.single())
        reloaded.modelFor(ModelRole.Main)
        assertEquals(listOf("adaptive", "disabled"), setups.last().thinkingTypes)
        assertEquals("high", setups.last().reasoningEffort)

        // 去掉新字段,模拟旧版本存下的列表
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace(Regex(""","reasoningEfforts":\[[^]]*]"""), "")
            .replace(Regex(""","thinkingTypes":\[[^]]*]"""), ""))
        val old = store()
        val oldListed = old.provider(p.id)!!.models!!.single()
        assertNull(oldListed.reasoningEfforts)
        assertNull(oldListed.thinkingTypes)
        assertNull(ReasoningOptions.of(old.configFor(ModelRole.Main)!!, oldListed))
    }

    @Test fun metadataRefreshDropsAnUnavailableChoiceAndUsesServerDefault() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoning = false)))
        val reloaded = store()
        assertNull(reloaded.roles.value.reasoningEffort(ModelRole.Main))
        assertFalse(reloaded.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(reloaded.setReasoningEffort(ModelRole.Main, null))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun chatGptCatalogCannotIntroduceUltraAndAnOldUltraChoiceFallsBackToDefault() {
        val s = store()
        val p = s.connect("ChatGPT", ProviderConfig.Kind.ChatGpt, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoningEfforts = listOf("low", "ultra", "max"))))
        s.use(ModelRole.Main, p.id, "m", caps)
        val listed = s.provider(p.id)!!.models!!.single()
        assertEquals(listOf("low", "max"), ReasoningOptions.of(s.configFor(ModelRole.Main)!!, listed)!!.levels)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "ultra"))
        assertTrue(s.setReasoningEffort(ModelRole.Main, "low"))
        // 模拟旧版本已经落盘的 ultra:读入时清掉,不能继续发出去让每次调用都 HTTP 400。
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace("\"reasoningEffort\":\"low\"", "\"reasoningEffort\":\"ultra\""))
        val reloaded = store()
        assertNull(reloaded.roles.value.reasoningEffort(ModelRole.Main))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun savedRoleEffortReachesRealResponsesAdapter() = runTest {
        MockWebServer().use { server ->
            server.start()
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, server.url("/v1").toString(), "k",
                listOf(ListedModel("m", reasoningEfforts = listOf("high"))))
            s.use(ModelRole.Main, p.id, "m", caps)
            s.setReasoningEffort(ModelRole.Main, "high")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"))
            s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test fun savedRoleEffortReachesEveryAdapter() = runTest {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            for (protocol in listOf(ModelProtocol.ChatCompletions, ModelProtocol.Messages, ModelProtocol.GenerateContent)) {
                val s = ProviderStore(tmp.newFolder("p-${protocol.wire}"), AesGcmKeyCipher { key })
                val p = s.connect("P", ProviderConfig.Kind.of(protocol), base, "k",
                    listOf(ListedModel("m", endpoint = ModelEndpoint(protocol, base), reasoningEfforts = listOf("high"))))
                s.use(ModelRole.Main, p.id, "m", caps)
                assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
                val reply = when (protocol) {
                    ModelProtocol.ChatCompletions -> """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
                    ModelProtocol.Messages -> """{"content":[],"stop_reason":"end_turn"}"""
                    else -> """{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"""
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(reply))
                s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val sent = when (protocol) {
                    ModelProtocol.ChatCompletions -> body["reasoning_effort"]
                    ModelProtocol.Messages -> body["output_config"]!!.jsonObject["effort"]
                    else -> body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]
                }
                assertEquals("high", sent!!.jsonPrimitive.content.lowercase())
            }
        }
    }
}
