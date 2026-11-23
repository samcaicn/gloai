package com.wangzan101.mika.settings

import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.search.GoogleGroundingSearch
import com.wangzan101.mika.core.search.WebSearchResult
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.model.usage.UsageKey
import com.wangzan101.mika.core.model.usage.UsageLedger
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class ProviderStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val built = mutableListOf<Pair<ProviderConfig, String?>>()
    private val endpoints = mutableListOf<ModelEndpoint?>()

    private val setups = mutableListOf<ClientSetup>()

    private fun store(dailyUsage: (String) -> Long = { 0L }) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3", dailyUsage = dailyUsage,
    ) { cfg, apiKey, endpoint, setup ->
        built += cfg to apiKey
        setups += setup
        endpoints += endpoint
        FakeModel(cfg.capabilities)
    }

    private fun cfg(id: String, tools: Boolean = true, vision: Boolean = false) = ProviderConfig(
        id = id, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://example.test/v1", model = "m-$id",
        capabilities = Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false),
        name = "P $id",
    )

    private val none = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false)

    @Test fun openAiServiceTiersSurviveReloadAndApplyPerProviderAndModel() {
        val s = store()
        val base = "https://api.openai.com/v1"
        val api = s.connect("OpenAI", ProviderConfig.Kind.OpenAiResponses, base, "api-key",
            listOf(ListedModel("gpt-x"), ListedModel("gpt-y")))
        val plan = s.connect("ChatGPT", ProviderConfig.Kind.ChatGpt, base, null, listOf(ListedModel("gpt-x")))
        assertTrue(s.use(ModelRole.Main, api.id, "gpt-x", none))
        assertTrue(s.use(ModelRole.Light, plan.id, "gpt-x", none))
        assertTrue(s.use(ModelRole.Vision, api.id, "gpt-y", none))
        assertTrue(s.setModelServiceTier(api.id, "gpt-x", OpenAiServiceTier.Fast))
        assertTrue(s.setModelServiceTier(plan.id, "gpt-x", OpenAiServiceTier.Ultrafast))

        val reloaded = store()
        reloaded.modelFor(ModelRole.Main)
        reloaded.modelForRoleConfig(ModelRole.Light, reloaded.configFor(ModelRole.Light)!!.id)
        reloaded.modelFor(ModelRole.Vision)
        assertEquals(listOf(OpenAiServiceTier.Fast, OpenAiServiceTier.Ultrafast, null), setups.map { it.serviceTier })

        assertTrue(reloaded.setModelServiceTier(api.id, "gpt-x", null))
        store().modelFor(ModelRole.Main)
        assertNull(setups.last().serviceTier)
    }

    @Test fun openAiServiceTierDoesNotApplyToThirdPartyEndpoints() {
        val s = store()
        val base = "https://api.openai.com/v1"
        val thirdPartyEndpoint = ModelEndpoint(ModelProtocol.Responses, "https://other.test/v1")
        val custom = s.connect("Custom", ProviderConfig.Kind.OpenAiResponses, thirdPartyEndpoint.baseUrl, null, emptyList())
        assertFalse(s.setModelServiceTier(custom.id, "gpt-x", OpenAiServiceTier.Fast))

        val direct = s.connect("OpenAI", ProviderConfig.Kind.OpenAiResponses, base, "api-key", emptyList())
        assertTrue(s.use(ModelRole.Main, direct.id, "gpt-x", none))
        assertTrue(s.setModelServiceTier(direct.id, "gpt-x", OpenAiServiceTier.Fast))
        s.rememberModels(direct.id, listOf(ListedModel("gpt-x", endpoint = thirdPartyEndpoint)))
        assertFalse(s.provider(direct.id)!!.supportsServiceTier("gpt-x"))
        s.modelFor(ModelRole.Main)
        assertNull(setups.last().serviceTier)
    }

    /** 存一条配置并让它当主模型(没人用的配置会被清掉)。 */
    private fun ProviderStore.saveAsMain(c: ProviderConfig, apiKey: String?) {
        save(c, apiKey)
        assertTrue(choose(ModelRole.Main, RoleChoice.Model(c.id)))
    }

    // ---------- 主模型备援链(项目 3) ----------

    @Test fun mainFallbackGapSlotAppendsAtEnd() {
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(1, "a"))
        assertEquals(listOf("a"), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackAppendReplaceAndClear() {
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        assertEquals(listOf("a", "b"), s.roles.value.mainFallbacks)
        s.save(cfg("c"), "sk-c")
        assertTrue(s.setMainFallback(0, "c"))
        assertEquals(listOf("c", "b"), s.roles.value.mainFallbacks)
        assertTrue(s.setMainFallback(0, null))
        assertEquals(listOf("b"), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackMoveDedupes() {
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        assertTrue(s.setMainFallback(0, "b"))
        assertEquals(listOf("b", "a"), s.roles.value.mainFallbacks)
        assertTrue(s.setMainFallback(1, "b"))
        assertEquals(listOf("a", "b"), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackRejectsUnknownConfig() {
        val s = store()
        assertFalse(s.setMainFallback(0, "nope"))
        assertEquals(emptyList<String>(), s.roles.value.mainFallbacks)
    }

    // ---------- 密钥池接线:401/403 标耗尽、429 冷却 ----------

    /** 真接线(不换 buildModel):401 的坏 key 在池里被标耗尽,不再被轮询选中;好 key 接手完成请求。 */
    @Test fun authFailureMarksKeyExhaustedThroughWiredModel() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val c = cfg("auth-x").copy(baseUrl = server.url("/v1").toString().trimEnd('/'))
            s.saveAsMain(c, "sk-bad, sk-good")
            val pid = s.list().first { it.id == c.id }.providerId
            try {
                val m = s.modelForConfig(c.id)!!
                val reply = m.complete("s", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)), emptyList())
                assertEquals("hi", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                // 轮询位置跨注册保留(#757):先后两棒各用一把,不假设哪把先跑
                val first = server.takeRequest().getHeader("Authorization")
                val second = server.takeRequest().getHeader("Authorization")
                assertNotEquals(first, second)
                assertEquals(setOf("Bearer sk-bad", "Bearer sk-good"), setOf(first, second))
                // 401 的那把已标耗尽:池里只剩接手的那把可用,轮询不会再选回去
                assertEquals(2, KeyPoolManager.shared.keyCount(pid))
                assertEquals(1, KeyPoolManager.shared.activeKeyCount(pid))
                assertEquals(second?.removePrefix("Bearer "), KeyPoolManager.shared.getActiveKey(pid))
            } finally {
                KeyPoolManager.shared.reset(pid)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun mainFallbackRejectsSlotAtMax() {
        // 位子只有 0..MAX-1(UI 也只显示这些):slot == MAX 要拒(MK-1 B-D3)。
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertFalse(s.setMainFallback(MAX_MAIN_FALLBACKS, "a"))
        assertEquals(emptyList<String>(), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackChainSkipsMainItself() {
        // 旧设定若把主模型自己排进备援,组链时略过(MK-1 B-D2)。
        // 顺序要注意:setMainFallback 会 prune 掉还没被引用的配置,所以先把 a 排上再存 b。
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        val (models, _) = s.mainFallbackChain()
        assertEquals(1, models.size)
    }

    @Test fun fallbackChainIdentifiesProvidersWithTheSameDisplayNameSeparately() {
        val s = store()
        val primary = connected(s, name = "Gateway")
        val backup = s.connect("Gateway", ProviderConfig.Kind.OpenAiCompat,
            "https://backup.test/v1", "backup-key", listOf(ListedModel("m1")))
        s.use(ModelRole.Main, primary.id, "m1", none)
        s.use(ModelRole.Light, backup.id, "m1", none)
        assertTrue(s.setMainFallback(0, s.configFor(ModelRole.Light)!!.id))

        assertEquals(listOf(backup.id), s.mainFallbackChain().second)
    }

    @Test fun isMainModelMatchesCurrentMain() {
        // 手填备援时挡主模型自己,靠这个判断(MK-1 D1)。
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        assertTrue(s.isMainModel(p.id, "m1"))
        assertFalse(s.isMainModel(p.id, "m2"))
        assertFalse(s.isMainModel("nope", "m1"))
    }

    @Test fun choosingFallbackAsMainClearsItFromFallbacks() {
        // 把备援改选为主模型:它不能同时留在备援链里(MK-1 D2)。
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val fbId = s.ensureConfigId(p.id, "m2", none)!!
        assertTrue(s.setMainFallback(0, fbId))
        assertTrue(s.use(ModelRole.Main, p.id, "m2", none))
        assertEquals(emptyList<String>(), s.roles.value.mainFallbacks)
    }

    @Test fun storedMainDuplicateClearedOnReload() {
        // 旧文件里主模型自己排在备援链里:重新载入时清掉,界面与实际链一致(MK-1 D2)。
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        val reopened = store()
        assertEquals(listOf("b"), reopened.roles.value.mainFallbacks)
    }

    @Test fun settingMainAsFallbackIsDroppedImmediately() {
        // 任何路径把主模型写进备援位,存的当下就清掉,不等下次载入(审 T-1112-74a8f2 D2)。
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        assertTrue(s.roles.value.mainFallbacks.isEmpty())
    }

    @Test fun fallbackInheritsMainReasoningEffortWhenSupported() {
        // 主模型设了思考强度、备援写明支持同一档:建备援时带上(MK-1 B-D1)。
        val s = store()
        val mainP = s.connect("MainP", ProviderConfig.Kind.OpenAiCompat, "https://mainp.test/v1", "k",
            listOf(ListedModel("m1", reasoningEfforts = listOf("low", "high"))))
        s.use(ModelRole.Main, mainP.id, "m1", none)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val fbP = s.connect("FbP", ProviderConfig.Kind.OpenAiCompat, "https://fbp.test/v1", "k",
            listOf(ListedModel("m9", reasoningEfforts = listOf("low", "high"))))
        val fbId = s.ensureConfigId(fbP.id, "m9", none)!!
        assertTrue(s.setMainFallback(0, fbId))
        setups.clear()
        s.modelForConfig(fbId)
        assertEquals("high", setups.last().reasoningEffort)
    }

    @Test fun fallbackOmitsMainReasoningEffortWhenUnsupported() {
        // 备援没写明支持主模型那个档位:不带,用它自己的默认,不能让请求因参数被拒(MK-1 B-D1)。
        val s = store()
        val mainP = s.connect("MainP", ProviderConfig.Kind.OpenAiCompat, "https://mainp.test/v1", "k",
            listOf(ListedModel("m1", reasoningEfforts = listOf("low", "high"))))
        s.use(ModelRole.Main, mainP.id, "m1", none)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val fbP = s.connect("FbP", ProviderConfig.Kind.OpenAiCompat, "https://fbp.test/v1", "k",
            listOf(ListedModel("m9", reasoningEfforts = listOf("low"))))
        val fbId = s.ensureConfigId(fbP.id, "m9", none)!!
        assertTrue(s.setMainFallback(0, fbId))
        setups.clear()
        s.modelForConfig(fbId)
        assertNull(setups.last().reasoningEffort)
    }

    private fun connected(s: ProviderStore, name: String = "P", key: String = "k") =
        s.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", key, listOf(ListedModel("m1"), ListedModel("m2")))

    @Test fun overlappingLegacyRolesSurviveRestartAndRefreshUntilTheUserChooses() {
        val s = store()
        val provider = connected(s)
        assertTrue(s.use(ModelRole.Main, provider.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, provider.id, "m2", none))
        val existing = File(tmp.root, "providers.json")
        val stored = existing.readText()
        val entryStart = "\"id\":\"${provider.id}\",\"name\""
        assertTrue(entryStart in stored)
        existing.writeText(stored.replace(entryStart, "\"ttsModels\":[\"m1\"],$entryStart"))

        val reopened = store()
        // 旧 ttsModels 字段仍作为遗留数据可读;不再按它过滤运行时的用途
        assertEquals(setOf("m1"), reopened.provider(provider.id)?.ttsModels)
        reopened.rememberModels(provider.id, listOf(ListedModel("m1"), ListedModel("m2")))
        assertEquals("m1", reopened.configFor(ModelRole.Main)?.model)
        assertEquals("m2", reopened.configFor(ModelRole.Vision)?.model)
        assertEquals(reopened.roles.value, store().roles.value)
    }

    @Test fun ttsAdapterBuildsForAnyNonBlankModel() {
        val s = store()
        val p = connected(s)
        val choice = VoiceChoice(p.id, " manual-tts ", TtsProtocol.OpenAiSpeech)
        val capabilities = TtsCapabilities()
        // 不再要求模型标为 TTS;模型名去空格后直接建
        assertEquals("manual-tts", s.ttsModel(choice, capabilities)?.name)
        assertEquals("m1", s.ttsModel(choice.copy(model = "m1"), capabilities)?.name)
    }

    /** 地址读回来不合法(手改过的文件):不建朗读模型、不抛,试听和朗读按没选处理。 */
    @Test fun ttsAdapterIsNullForAnInvalidBaseUrl() {
        val s = store()
        val p = connected(s)
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("https://p.test/v1", "not a url"))
        val reopened = store()
        assertEquals("not a url", reopened.provider(p.id)?.baseUrl)
        assertNull(reopened.ttsModel(VoiceChoice(p.id, "tts-1", TtsProtocol.OpenAiSpeech), TtsCapabilities()))
        assertNull(reopened.ttsModel(VoiceChoice(p.id, "tts-1", TtsProtocol.GeminiTts), TtsCapabilities()))
    }

    /** 文件里已有的空手填名读入时丢掉。 */
    @Test fun blankTypedModelsAreDroppedOnRead() {
        val s = store()
        val p = connected(s)
        s.markTyped(p.id, "manual-tts")
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("\"typedModels\":[\"manual-tts\"]", "\"typedModels\":[\"\",\" \",\"manual-tts\"]"))
        assertEquals(setOf("manual-tts"), store().provider(p.id)?.typedModels)
    }

    @Test fun keyChangeInvalidatesAllowanceForOnlyThatProvider() {
        val store = store()
        val first = connected(store)
        val other = connected(store, "Other")
        val revisions = store.providerKeyRevisions.value
        val providers = store.providers.value

        store.updateProviderKey(first.id, "new-key")

        assertEquals(providers, store.providers.value)
        assertEquals(revisions.getValue(first.id) + 1, store.providerKeyRevisions.value[first.id])
        assertEquals(revisions[other.id], store.providerKeyRevisions.value[other.id])
        assertNull(store.allowanceKey(first, revisions.getValue(first.id)))
        assertEquals("new-key", store.allowanceKey(first, store.providerKeyRevisions.value.getValue(first.id)))
        store.removeProvider(first.id)
        assertNull(store.providerKeyRevisions.value[first.id])
    }

    // ---------- 用途的选择 ----------

    @Test fun modelContextSurvivesPruningAndIsSharedOnlyWithinTheSameProviderAndModel() {
        val s = store()
        val first = connected(s)
        val second = connected(s, "Other")
        s.use(ModelRole.Main, first.id, "m1", none)
        s.use(ModelRole.Vision, second.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(1_000_000, 600_000)
        assertTrue(s.setModelContext(cfg.id, saved))
        for (role in listOf(ModelRole.Main, ModelRole.Light)) {
            val caps = s.modelFor(role)!!.capabilities
            assertEquals(1_000_000, caps.contextTokens)
            assertEquals(600_000, caps.compactionTriggerTokens)
        }
        assertEquals(64_000, s.modelFor(ModelRole.Vision)!!.capabilities.contextTokens)
        assertNull(s.modelFor(ModelRole.Vision)!!.capabilities.compactionTriggerTokens)
        s.use(ModelRole.Main, first.id, "m2", none)
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings()))
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        assertEquals(600_000, reloaded.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(reloaded.setModelContext(reloaded.configFor(ModelRole.Main)!!.id, ModelContextSettings()))
        assertNull(store().provider(first.id)!!.contextSettings["m1"])
    }

    @Test fun perModelReadTimeoutFlowsIntoTheClientSetupAndPersists() {
        // #295:慢速本地推理服务(例如经隧道访问)一次生成可以超过全局默认的 120 秒读超时;
        // 每模型配置的读超时随 ModelContextSettings 存取,并进到建模型用的 ClientSetup 里。
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(readTimeoutSeconds = 600)
        assertTrue(s.setModelContext(cfg.id, saved))
        s.modelFor(ModelRole.Main)
        assertEquals(600, setups.last().readTimeoutSeconds)
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        reloaded.modelFor(ModelRole.Main)
        assertEquals(600, setups.last().readTimeoutSeconds)
    }

    @Test fun configuredReadTimeoutAppliesToTheRealHttpClient() = runTest {
        MockWebServer().use { server ->
            server.start()
            // 响应头故意晚于单模型的一秒读超时;若配置没进入 OkHttp,这里不会抛 Network。
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Local", ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "k", listOf(ListedModel("m")))
            s.use(ModelRole.Main, p.id, "m", none)
            val cfg = s.configFor(ModelRole.Main)!!
            assertTrue(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 1)))
            try {
                s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
                fail("Expected the configured read timeout")
            } catch (_: ModelError.Network) {
                // 真实适配器在读取响应头时超时,而不只是 ClientSetup 里保存了一个数字。
            }
        }
    }

    @Test fun readTimeoutMustFitOkHttpRange() {
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 0)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = -5)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 2_147_484)))
        assertTrue(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 2_147_483)))
    }

    @Test fun previouslySavedOversizeReadTimeoutFallsBackWithoutLosingContextLimits() {
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        assertTrue(s.setModelContext(cfg.id, ModelContextSettings(1_000_000, 600_000, 600)))
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace("\"readTimeoutSeconds\":600", "\"readTimeoutSeconds\":2147484"))

        val reloaded = store()
        assertEquals(ModelContextSettings(1_000_000, 600_000), reloaded.provider(first.id)!!.contextSettings["m1"])
        assertEquals(1_000_000, reloaded.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
        assertNull(setups.last().readTimeoutSeconds)
    }

    /** 预设改了默认上下文后,按旧默认存下的配置读入时跟上去;用户改过的和写了每模型窗口的不动(见 repairStaleContextDefaults)。 */
    @Test fun stalePresetWindowIsRepairedOnLoadButUserValuesAreNot() {
        val s = store()
        val deepseek = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "k", listOf(ListedModel("deepseek-flash")))
        val other = connected(s)
        s.use(ModelRole.Main, deepseek.id, "deepseek-flash", none)
        s.use(ModelRole.Light, other.id, "m1", none)
        // 用户给另一条配置单独写过窗口:不动
        assertTrue(s.setModelContext(s.configFor(ModelRole.Light)!!.id, ModelContextSettings(windowTokens = 32_000)))
        val reloaded = store()
        assertEquals(1_000_000, reloaded.configFor(ModelRole.Main)!!.capabilities.contextTokens)
        // 预设默认没变的(示例地址不对应任何预设)和用户写过的都不动
        assertEquals(64_000, reloaded.configFor(ModelRole.Light)!!.capabilities.contextTokens)
    }

    @Test fun manualTriggerUsesCoreBoundsAndASmallerReselectedWindowKeepsTheStoredValue() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 1_000_000))
        val id = s.configFor(ModelRole.Main)!!.id
        for (value in listOf(500_000, 980_000, 950_000)) {
            assertTrue(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        for (value in listOf(0, 499_999, 980_001)) {
            assertFalse(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        assertFalse(s.setModelContext(id, ModelContextSettings(windowTokens = -1)))
        s.use(ModelRole.Main, p.id, "m2", none)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 32_000))
        val saved = store().provider(p.id)!!.contextSettings.getValue("m1")
        assertEquals(950_000, saved.triggerTokens)
        assertEquals(24_000, saved.limits(s.modelFor(ModelRole.Main)!!.capabilities.contextTokens).trigger)
    }

    @Test fun missingContextFieldsUseAutoAndMalformedEntriesDoNotDiscardOtherModels() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val file = File(tmp.root, "providers.json")
        val original = file.readText()
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        val entries = """{"m1":{"windowTokens":1000000},"m2":{"triggerTokens":600000},"bad":{"triggerTokens":{}}}"""
        file.writeText(original.replace("\"services\":[{", "\"services\":[{\"contextSettings\":$entries,"))
        assertEquals(mapOf("m1" to ModelContextSettings(1_000_000), "m2" to ModelContextSettings(triggerTokens = 600_000)),
            store().provider(p.id)!!.contextSettings)
    }

    @Test fun failedContextSaveLeavesBothTheStoredAndActiveModelAloneAndCanBeRetried() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val id = s.configFor(ModelRole.Main)!!.id
        val saved = ModelContextSettings(triggerTokens = 40_000)
        val blocked = File(tmp.root, "providers.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(s.setModelContext(id, saved))
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(blocked.delete())
        assertNull(store().provider(p.id)!!.contextSettings["m1"])
        assertTrue(s.setModelContext(id, saved))
        assertEquals(saved, store().provider(p.id)!!.contextSettings["m1"])
    }

    @Test
    fun freshInstallHasNoMainVisionOffAndLightSameAsMain() {
        val s = store()
        assertEquals(RoleChoices(main = null, vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.modelFor(ModelRole.Main))
        assertNull(s.modelFor(ModelRole.Vision))
        assertNull(s.modelFor(ModelRole.Light))
    }

    @Test
    fun lightSameAsMainResolvesToMainAndVisionStaysOffEvenIfMainCanSee() {
        val s = store()
        s.saveAsMain(cfg("a", vision = true), "sk-a")
        assertEquals("a", s.configFor(ModelRole.Light)?.id)
        // 不再有「主模型能看图就兼任看图」的隐含规则
        assertNull(s.configFor(ModelRole.Vision))
        val m = s.modelFor(ModelRole.Main)
        assertEquals("套上计量后能力照旧(临时:每 16 轮折一次)", cfg("a", vision = true).capabilities.copy(compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS), m?.capabilities)
        assertEquals("a" to "sk-a", built.single().first.id to built.single().second)
    }

    @Test
    fun visionSameAsMainUsesMainAndMainItselfSeesUntilSwitchedAway() {
        val s = store()
        s.saveAsMain(cfg("a", vision = false), "sk-a")
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        val v = s.configFor(ModelRole.Vision)!!
        assertEquals("a", v.id)
        assertTrue("用户说主模型能看图,看图这一路照发截图", v.capabilities.vision)
        s.modelFor(ModelRole.Vision)
        assertTrue(built.last().first.capabilities.vision)
        assertEquals("sk-a", built.last().second)
        assertTrue("用户说了主模型能看图,主模型自己办事也直接看截图", s.configFor(ModelRole.Main)!!.capabilities.vision)
        // 重开仍在
        assertEquals(RoleChoice.SameAsMain, store().roles.value.vision)
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Off))
        assertFalse("换走了就回到存着的标记", s.configFor(ModelRole.Main)!!.capabilities.vision)
    }

    @Test
    fun pickingTheMainModelAsVisionModelMakesMainSeeAndSwitchingAwayUndoesIt() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m1", none))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        s.modelFor(ModelRole.Main)
        assertTrue("建出来的主模型附图", built.last().first.capabilities.vision)
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        assertFalse("看图换成别的模型,主模型回到列表和初值说的", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertFalse("没改存着的配置", store().list().first { it.model == "m1" }.capabilities.vision)
    }

    @Test
    fun userSaysModelCanSeeOverridesUnknownAndPersists() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertFalse("列表没写,按初值", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val reopened = store()
        assertEquals(mapOf("m1" to true), reopened.provider(p.id)!!.visionOverrides)
        assertTrue("重开仍在", reopened.configFor(ModelRole.Main)!!.capabilities.vision)
        // 用户也能关掉:看图用主模型时主模型也按用户说的不附图,看图这一路照旧
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertTrue(s.setModelVision(p.id, "m1", false))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.vision)
        assertFalse(s.setModelVision("missing", "m1", true))
    }

    @Test
    fun listingSaysNoVisionStaysNoUnlessTheUserOverrides() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(ListedModel("m1", toolCalls = true, vision = false)))
        val o = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertFalse(o.canSee)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", o.capabilities))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val after = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertTrue(after.canSee)
        assertEquals(true, after.visionByUser)
    }

    @Test
    fun visionSameAsMainWithoutMainHasNoVisionModel() {
        val s = store()
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertNull(s.configFor(ModelRole.Vision))
    }

    @Test
    fun choicesTheRoleDoesNotTakeAreRejected() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertFalse(s.choose(ModelRole.Main, RoleChoice.SameAsMain))
        assertFalse(s.choose(ModelRole.Main, RoleChoice.Off))
        assertFalse(s.choose(ModelRole.Light, RoleChoice.Off))
        assertFalse("不存在的配置", s.choose(ModelRole.Main, RoleChoice.Model("zzz")))
        assertEquals(RoleChoices(main = "a"), s.roles.value)
    }

    @Test
    fun explicitChoiceIsNotBlockedByCapabilityFlagsAndMarksThem() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        val main = s.configFor(ModelRole.Main)!!
        val vision = s.configFor(ModelRole.Vision)!!
        assertEquals("m1" to "m2", main.model to vision.model)
        assertTrue("选作主模型就记工具调用", main.capabilities.toolCalls)
        assertTrue("选作看图就算能看图", vision.capabilities.vision)
        // 轻量不要求任何能力
        assertTrue(s.use(ModelRole.Light, p.id, "m2", none))
        assertEquals(vision.id, s.configFor(ModelRole.Light)?.id)
        assertFalse(s.use(ModelRole.Main, "missing", "m1", none))
    }

    @Test
    fun usingTheSameModelReusesItsConfig() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        assertEquals(1, s.list().size)
        assertEquals(s.roles.value.main, (s.roles.value.light as RoleChoice.Model).configId)
        assertEquals("k", s.apiKey(s.roles.value.main!!))
    }

    @Test
    fun lightUsersGetTheLightModelAndNotTheMainOne() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        built.clear()
        s.modelFor(ModelRole.Light)
        assertEquals("m2" to "k", built.single().first.model to built.single().second)
        built.clear()
        s.modelFor(ModelRole.Main)
        assertEquals("m1", built.single().first.model)
    }

    // ---------- 没人用的配置删掉 ----------

    @Test
    fun configsNoRoleUsesAreDeletedButTheProviderKeyStays() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertEquals(listOf("m1", "m2"), s.list().map { it.model })
        // 轻量改回用主模型:m2 没人用了
        assertTrue(s.choose(ModelRole.Light, RoleChoice.SameAsMain))
        assertEquals(listOf("m1"), s.list().map { it.model })
        // 换主模型:m1 没人用了
        s.use(ModelRole.Main, p.id, "m2", none)
        assertEquals(listOf("m2"), s.list().map { it.model })
        assertEquals("k", s.providerKey(p.id))
        assertEquals("k", s.apiKey(s.list().single().id))
        assertEquals(listOf("m2"), store().list().map { it.model })
    }

    @Test
    fun visionSameAsMainKeepsTheMainConfigAndNothingElse() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertEquals(listOf("m1"), s.list().map { it.model })
        assertEquals(setOf(s.roles.value.main), s.roles.value.referenced)
    }

    @Test
    fun loadDropsUnusedConfigsAndChoicesPointingNowhere() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("b"), null)
        assertEquals(2, s.list().size)
        val reopened = store()
        assertEquals(listOf("a"), reopened.list().map { it.id })
        // 手改坏的文件:看图指向不存在的配置,退回不用
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace("\"Vision\":{\"use\":\"none\"}", "\"Vision\":{\"use\":\"model\",\"config\":\"gone\"}"))
        assertEquals(RoleChoice.Off, store().roles.value.vision)
    }

    // ---------- Key ----------

    @Test
    fun keyIsNotStoredInPlainPrefs() {
        val secret = "sk-very-secret-0123456789"
        store().saveAsMain(cfg("a"), secret)
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("plaintext key found in ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        assertEquals(secret, store().apiKey("a"))
    }

    @Test
    fun saveWithNullKeyKeepsExistingKey() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("a").copy(model = "other"), null)
        assertEquals("sk-a", s.apiKey("a"))
        assertEquals("other", s.list().single().model)
        assertTrue(s.hasKey(s.list().single().providerId))
    }

    @Test
    fun undecryptableKeyReadsAsMissing() {
        store().saveAsMain(cfg("a"), "sk-a")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(ProviderStore(tmp.root, AesGcmKeyCipher { otherKey }).apiKey("a"))
    }

    /** 试调用通过的模型名随服务商存下;再连一次同一家时合并;旧文件没有这一项时为空。 */
    @Test
    fun verifiedModelsPersist() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), verified = setOf("a"))
        assertEquals(setOf("a"), p.verifiedModels)
        s.markVerified(p.id, "b")
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", null, emptyList(), verified = setOf("c"))
        assertEquals(setOf("a", "b", "c"), store().provider(p.id)?.verifiedModels)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(Regex(",\"verifiedModels\":\\[[^\\]]*\\]"), ""))
        assertFalse(f.readText().contains("verifiedModels"))
        assertEquals(emptySet<String>(), store().provider(p.id)?.verifiedModels)
    }

    @Test
    fun connectedProviderWithoutModelsPersistsWithItsListAndKey() {
        val s = store()
        val listed = listOf(ListedModel("a", displayName = "A", toolCalls = true, contextTokens = 128_000), ListedModel("b", vision = false))
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "sk-d", listed)
        assertTrue("连上了还没选模型", s.list().isEmpty())
        assertNull(s.roles.value.main)
        val reopened = store()
        assertEquals(listOf(p), reopened.providers.value)
        assertEquals(listed, reopened.providers.value.single().models)
        assertEquals("sk-d", reopened.providerKey(p.id))
        // 同一家再连一次:更新 Key 和列表,不重复添加
        val again = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "sk-new", listOf(ListedModel("c")))
        assertEquals(p.id, again.id)
        assertEquals(1, s.providers.value.size)
        assertEquals("sk-new", s.providerKey(p.id))
        assertEquals(listOf("c"), store().providers.value.single().models!!.map { it.id })
        // 没列过的是 null,列过但没有的是空列表
        s.rememberModels(p.id, emptyList())
        assertEquals(emptyList<ListedModel>(), store().providers.value.single().models)
    }

    // ---------- 跳过测试直接存下 ----------

    /** 跳过测试存下的那家带标记;重新读文件也在(旧版本的文件没有这一项,按测过算)。 */
    @Test
    fun untestedProviderPersistsItsFlag() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        assertTrue(p.savedUntested)
        assertTrue("重新读入还在", store().provider(p.id)!!.savedUntested)
        // 不是跳过测试存下的那些没有这个标记
        val other = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", emptyList())
        assertFalse(other.savedUntested)
        assertFalse(store().provider(other.id)!!.savedUntested)
    }

    /** 列过一次模型、或试通过一个模型,「没测过」就不再成立。 */
    @Test
    fun listingOrVerifyingClearsTheUntestedFlag() {
        val s = store()
        val listed = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.rememberModels(listed.id, listOf(ListedModel("m1")))
        assertFalse("列出来了就算测过", store().provider(listed.id)!!.savedUntested)

        val verified = s.connect("Z", ProviderConfig.Kind.OpenAiCompat, "https://z.test/v1", "k", emptyList(), untested = true)
        assertTrue(s.provider(verified.id)!!.savedUntested)
        s.markVerified(verified.id, "m1")
        assertFalse("核实过就算测过", s.provider(verified.id)!!.savedUntested)
        assertEquals(setOf("m1"), s.provider(verified.id)!!.verifiedModels)
    }

    /** 主模型在它那家还没测试通过(没有列表、手填、没核实)时给出那一家;核实过、或这家有列表时不算。 */
    @Test
    fun untestedMainProviderFollowsTheMainModel() {
        val s = store()
        assertNull("没有主模型", s.untestedMainProvider())
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.markTyped(p.id, "m1")
        s.use(ModelRole.Main, p.id, "m1", none)
        assertEquals(p.id, s.untestedMainProvider()?.id)
        s.markVerified(p.id, "m1")
        assertNull("试通过了", s.untestedMainProvider())
        val listed = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", listOf(ListedModel("m2")))
        s.use(ModelRole.Main, listed.id, "m2", none)
        assertNull("从列表里选的不算", s.untestedMainProvider())
    }

    /** 跳过测试再存一次已经列过模型的那家:已有的列表留着,不要被空表盖掉,也仍然是「测过」。 */
    @Test
    fun savingUntestedAgainKeepsAnExistingModelList() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listOf(ListedModel("m1")))
        val again = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k2", emptyList(), untested = true)
        assertEquals(p.id, again.id)
        assertEquals(listOf("m1"), again.models!!.map { it.id })
        assertFalse("已经列过列表的不算没测过", again.savedUntested)
        assertEquals("k2", s.providerKey(p.id))
    }

    @Test
    fun modelsOfOneProviderShareOneKey() {
        val s = store()
        val p = connected(s, key = "k1")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.updateProviderKey(p.id, "k2")
        assertEquals(listOf("k2", "k2"), s.list().map { s.apiKey(it.id) })
        s.modelFor(ModelRole.Main)
        assertEquals("k2", built.last().second)
        // Key 文件里只有一份(按服务商存)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(p.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun localServiceWithoutKeyHasNoKey() {
        val s = store()
        val p = s.connect("local", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        assertFalse(s.hasKey(p.id))
    }

    @Test
    fun configsExposeCurrentList() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertEquals(s.list(), s.configs.value)
        assertEquals(1, s.configs.value.size)
    }

    // ---------- 删除服务商 ----------

    @Test
    fun removeProviderDropsItsModelsAndKeyAndResetsItsRoles() {
        val s = store()
        val p = connected(s, key = "k1")
        s.save(cfg("other"), "sk-o")
        s.choose(ModelRole.Main, RoleChoice.Model("other"))
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        s.removeProvider(p.id)
        assertEquals(listOf("other"), s.list().map { it.id })
        assertEquals(RoleChoices(main = "other", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.providerKey(p.id))
        assertEquals(listOf("P other"), s.providers.value.map { it.name })
        // 重开后数据一致
        val reopened = store()
        assertEquals(listOf("other"), reopened.list().map { it.id })
        assertEquals(s.roles.value, reopened.roles.value)
        assertNull(reopened.providerKey(p.id))
        assertEquals("sk-o", reopened.apiKey("other"))
    }

    @Test
    fun removingTheMainProviderLeavesMainUnsetAndKeepsOtherRoles() {
        val s = store()
        val a = connected(s, "A")
        val b = connected(s, "B")
        s.use(ModelRole.Main, a.id, "m1", none)
        s.use(ModelRole.Vision, b.id, "m1", none)
        s.removeProvider(a.id)
        assertNull(s.roles.value.main)
        assertEquals(b.id, s.configFor(ModelRole.Vision)?.providerId)
        // 重开时不会把剩下的那条当成主模型(那是旧版本的规则)
        assertNull(store().roles.value.main)
        assertEquals(b.id, store().configFor(ModelRole.Vision)?.providerId)
    }

    // ---------- 旧版本的文件 ----------

    private fun enc(k: String): String = java.util.Base64.getEncoder().encodeToString(AesGcmKeyCipher { key }.encrypt(k.toByteArray()))

    private fun legacy(id: String, name: String, url: String, model: String, vision: Boolean = false) =
        """{"id":"$id","name":"$name","kind":"OpenAiCompat","baseUrl":"$url","model":"$model","toolCalls":true,"vision":$vision,""" +
            """"contextTokens":64000,"reasoning":false,"groundingVerified":false,"promptCaching":"implicit"}"""

    /** 旧版本的文件:只有模型配置,Key 按配置 id 存,角色只记显式指定的。 */
    private fun writeLegacyFiles(roles: String = """{"Main":"c2","Vision":"c3"}""") {
        File(tmp.root, "providers.json").writeText(
            """{"providers":[""" +
                legacy("c1", "DeepSeek", "https://api.deepseek.com", "deepseek-chat") + "," +
                legacy("c2", "DeepSeek", "https://api.deepseek.com/", "deepseek-reasoner") + "," +
                legacy("c3", "", "http://192.168.1.5:8000/v1", "local", vision = true) +
                """],"roles":$roles}""",
        )
        File(tmp.root, "provider-keys.json").writeText("""{"c1":"${enc("sk-ds")}","c2":"${enc("sk-ds")}","c3":"${enc("sk-local")}"}""")
    }

    @Test
    fun legacyConfigsAreGroupedIntoProvidersOnLoad() {
        writeLegacyFiles()
        val s = store()
        assertEquals(listOf("DeepSeek", "192.168.1.5"), s.providers.value.map { it.name })
        val ds = s.providers.value[0]
        val local = s.providers.value[1]
        assertNull("还没列过模型", ds.models)
        // Key 归到服务商
        assertEquals("sk-ds", s.providerKey(ds.id))
        assertEquals("sk-local", s.providerKey(local.id))
        // 显式指定的照旧;没人用的 c1 删掉
        assertEquals(RoleChoices(main = "c2", vision = RoleChoice.Model("c3"), light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c2", "c3"), s.list().map { it.id })
        assertEquals(listOf(ds.id, local.id), s.list().map { it.providerId })
        assertEquals("deepseek-reasoner", s.configFor(ModelRole.Main)?.model)
        s.modelFor(ModelRole.Vision)
        assertEquals("c3" to "sk-local", built.last().first.id to built.last().second)
        // 按配置 id 存的旧 Key 清掉了
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(ds.id, local.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun legacyDefaultsAreWrittenOutAsTheyWorked() {
        // 没指定主模型:那时用第一个配置;它不能看图,所以看图不用;轻量跟随主模型
        writeLegacyFiles(roles = "{}")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c1"), s.list().map { it.id })
        assertTrue(File(tmp.root, "providers.json").readText().contains("\"uses\""))
    }

    @Test
    fun legacyMainThatCanSeeKeepsServingVision() {
        writeLegacyFiles(roles = """{"Main":"c3","Light":"c1"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c3", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("c1")), s.roles.value)
        assertEquals("c3", s.configFor(ModelRole.Vision)?.id)
        assertEquals(listOf("c1", "c3"), s.list().map { it.id })
    }

    @Test
    fun legacyRolesPointingToMissingConfigsFallBackToTheOldDefaults() {
        writeLegacyFiles(roles = """{"Main":"gone","Vision":"gone"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
    }

    @Test
    fun migrationRunsOnceAndRepeatsWithoutSideEffects() {
        writeLegacyFiles(roles = """{"Main":"c3"}""")
        val first = store()
        val providers = first.providers.value
        val configs = first.list()
        val roles = first.roles.value
        val configText = File(tmp.root, "providers.json").readText()
        val keyText = File(tmp.root, "provider-keys.json").readText()
        repeat(2) {
            val again = store()
            assertEquals(providers, again.providers.value)
            assertEquals(configs, again.list())
            assertEquals(roles, again.roles.value)
            assertEquals(configText, File(tmp.root, "providers.json").readText())
            assertEquals(keyText, File(tmp.root, "provider-keys.json").readText())
        }
    }

    @Test
    fun interruptedMigrationIsRedoneWithoutLosingKeys() {
        writeLegacyFiles()
        store()
        // 模拟中途断掉:配置文件回到旧格式,Key 文件里新旧两份都在
        val migratedKeys = File(tmp.root, "provider-keys.json").readText()
        writeLegacyFiles()
        val legacyKeys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
            as kotlinx.serialization.json.JsonObject
        val newKeys = kotlinx.serialization.json.Json.parseToJsonElement(migratedKeys) as kotlinx.serialization.json.JsonObject
        File(tmp.root, "provider-keys.json").writeText(kotlinx.serialization.json.JsonObject(legacyKeys + newKeys).toString())
        val s = store()
        assertEquals(2, s.providers.value.size)
        assertEquals(listOf("sk-ds", "sk-local"), listOf("c2", "c3").map { s.apiKey(it) })
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(s.providers.value.map { it.id }.toSet(), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    // ---------- 看图定位已验证 ----------

    @Test
    fun groundingVerifiedFollowsTheVisionModelAndPersists() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        // 没有看图模型时不动
        s.setVisionGroundingVerified(true)
        assertFalse(s.list().single().capabilities.groundingVerified)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
        assertTrue(store().configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        // 看图用主模型时改的是主模型那条
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertFalse(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
    }

    @Test
    fun roleChoicesResolveAndValidate() {
        val r = RoleChoices(main = "a", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("b"))
        assertEquals("a", r.resolve(ModelRole.Vision))
        assertEquals("b", r.resolve(ModelRole.Light))
        assertFalse(r.sameModel(ModelRole.Main, ModelRole.Light))
        assertTrue(r.sameModel(ModelRole.Main, ModelRole.Vision))
        assertTrue(RoleChoices(main = "a").sameModel(ModelRole.Main, ModelRole.Light))
        assertFalse(RoleChoices().sameModel(ModelRole.Main, ModelRole.Light))
        assertEquals(setOf("a", "b"), r.referenced)
        assertNull(RoleChoices().resolve(ModelRole.Light))
        assertNull(r.with(ModelRole.Light, RoleChoice.Off))
        assertNull(r.with(ModelRole.Main, RoleChoice.SameAsMain))
        assertEquals(RoleChoices(main = null, vision = RoleChoice.SameAsMain, light = RoleChoice.SameAsMain), r.keepOnly(emptySet()))
    }

    // ---------- 按模型指定的接口 ----------

    private val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://x.test/openai/v1", "reasoning_content")
    private val messages = ModelEndpoint(ModelProtocol.Messages, "https://x.test/anthropic/v1")
    private val generate = ModelEndpoint(ModelProtocol.GenerateContent, "https://x.test/google/v1beta")
    private val responses = ModelEndpoint(ModelProtocol.Responses, "https://x.test/openai/v1/")

    @Test
    fun perModelEndpointsPersistWithTheList() {
        val s = store()
        val listed = listOf(
            ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("c", endpoint = generate),
            ListedModel("r", endpoint = responses), ListedModel("u", unsupportedApis = listOf("realtime")), ListedModel("plain"),
        )
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listed)
        assertEquals(listed, store().providers.value.single().models)
    }

    @Test
    fun eachModelIsBuiltWithTheEndpointItsListingNamed() = runTest {
        val s = store()
        val p = s.connect(
            "X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k",
            listOf(ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("plain")),
        )
        s.use(ModelRole.Main, p.id, "b", none)
        s.use(ModelRole.Light, p.id, "plain", none)
        store().modelFor(ModelRole.Main)
        store().modelFor(ModelRole.Light)
        assertEquals(listOf(messages, null), endpoints)
        assertEquals(listOf("k", "k"), built.map { it.second })
        // 重新列出、地址换了,下次建模型用新的
        val moved = messages.copy(baseUrl = "https://y.test/anthropic/v1")
        s.rememberModels(p.id, listOf(ListedModel("b", endpoint = moved)))
        s.modelFor(ModelRole.Main)
        assertEquals(moved, endpoints.last())
    }

    @Test
    fun adapterAndBaseUrlFollowTheProtocolWithoutRepeatingTheVersionPath() {
        val c = cfg("1")
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://example.test/v1", ProviderStore.adapterFor(c, null))
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://x.test/openai/v1", ProviderStore.adapterFor(c, chat))
        assertEquals(ProviderConfig.Kind.Anthropic to "https://x.test/anthropic", ProviderStore.adapterFor(c, messages))
        assertEquals(ProviderConfig.Kind.Gemini to "https://x.test/google", ProviderStore.adapterFor(c, generate))
        assertEquals(ProviderConfig.Kind.OpenAiResponses to "https://x.test/openai/v1", ProviderStore.adapterFor(c, responses))
        val anthropicConfig = c.copy(kind = ProviderConfig.Kind.Anthropic, baseUrl = "https://api.anthropic.com")
        assertEquals(ProviderConfig.Kind.Anthropic to "https://api.anthropic.com", ProviderStore.adapterFor(anthropicConfig, null))
    }

    // ---------- 请求头 ----------

    /** 声明了会话头的服务商:同一段对话建的模型带同一个会话号,换一段对话就换;User-Agent 每个都带。 */
    @Test
    fun sessionHeaderIsStableWithinAConversationAndDiffersAcross() {
        val s = store()
        val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(ListedModel("m1")), sessionHeader = "x-test-session")
        s.use(ModelRole.Main, p.id, "m1", none)
        val a = ModelSession.of("conv-a")
        s.modelFor(ModelRole.Main, a); s.modelFor(ModelRole.Light, a); s.modelFor(ModelRole.Main, ModelSession.of("conv-b"))
        val sent = setups.map { it.headers["x-test-session"] }
        assertEquals(listOf(a.id, a.id, ModelSession.of("conv-b").id), sent)
        assertTrue(sent[0] != sent[2])
        assertTrue(setups.all { it.headers["User-Agent"] == "Mika/1.2.3" })
    }

    /** 列表把模型指到 Responses 的(同一服务商下只说这一种协议的模型):走真的 Responses 适配器,照样带客户端标识和会话号。 */
    @Test
    fun responsesRoutedModelCarriesTheSessionHeader() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val responsesBase = server.url("/v1").toString()
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, responsesBase))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed), sessionHeader = "x-test-session")
            s.use(ModelRole.Main, p.id, "r-only", none)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            val session = ModelSession.of("conv-a")
            s.modelFor(ModelRole.Main, session)!!.complete("s", emptyList(), emptyList())
            val req = server.takeRequest()
            assertEquals("/v1/responses", req.path)
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertEquals(session.id, req.getHeader("x-test-session"))
            assertEquals("Bearer k", req.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun noSessionHeaderForProvidersThatDoNotDeclareIt() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.modelFor(ModelRole.Main, ModelSession.of("conv-a"))
        assertEquals(mapOf("User-Agent" to "Mika/1.2.3"), setups.single().headers)
    }

    /** 会话头随服务商存下;旧版本存下的(没有这一项)按地址对得上的预设补上,预设的地址就会带。 */
    @Test
    fun sessionHeaderPersistsAndIsFilledFromThePresetForOldFiles() {
        val goUrl = ProviderPresets.all.first { it.sessionHeader != null }
        val s = store()
        val p = s.connect(goUrl.label, goUrl.kind, goUrl.baseUrl, "k", emptyList())
        assertEquals(goUrl.sessionHeader, p.sessionHeader)
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(",\"sessionHeader\":\"${goUrl.sessionHeader}\"", ""))
        assertFalse(f.readText().contains("sessionHeader"))
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        assertTrue("written back", f.readText().contains("sessionHeader"))
    }

    /** 试调用走真的适配器:带客户端标识和声明的会话头、带一个试用的工具;回了工具调用才算通过,服务端的报错原样带回。 */
    @Test
    fun probeGoesThroughTheRealAdapterWithHeaders() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val url = server.url("/v1").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", "x-test-session", "m", null, none))
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertTrue(req.getHeader("x-test-session").orEmpty().isNotEmpty())
            assertEquals("Bearer sk", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body, body.contains("\"tools\"") && body.contains("get_time"))
            assertFalse("Chat Completions 不给回复上限", body.contains("max_tokens"))

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("""{"error":{"type":"MissingSessionID"}}"""))
            val e = s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", null, "m", null, none) as ProbeResult.Failed
            assertTrue(e.error.message.orEmpty(), e.error.message.orEmpty().contains("MissingSessionID"))
            assertNull(server.takeRequest().getHeader("x-test-session"))

            // Responses 不给回复上限;回了函数调用就算通过
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiResponses, url, "sk", null, "m", null, none))
            val responses = server.takeRequest()
            assertEquals("/v1/responses", responses.path)
            assertEquals("Mika/1.2.3", responses.getHeader("User-Agent"))
            val responsesBody = responses.body.readUtf8()
            assertFalse(responsesBody.contains("max_output_tokens"))
            assertTrue(responsesBody.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    /** 接口通、模型只回了话不调用工具:不是失败,但要分得出来。 */
    @Test
    fun probeThatGetsOnlyTextReportsNoToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"It is noon."},"finish_reason":"stop"}]}"""))
            assertEquals(ProbeResult.NoToolCall, s.probe(ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "sk", null, "m", null, none))
        } finally {
            server.shutdown()
        }
    }

    /** 试调用给 Messages 的回复上限留得够一次工具调用(16 个 token 装不下)。 */
    @Test
    fun probeLeavesRoomForAToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"content":[{"type":"tool_use","id":"t1","name":"get_time","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.Anthropic, server.url("/").toString(), "sk", null, "m", null, none))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"max_tokens\":1024"))
            assertTrue(body.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    // ---------- 用量 ----------

    @Test
    fun everyModelHandedOutIsMeteredUnderTheRoleItWasAskedFor() = runTest {
        val seen = mutableListOf<Pair<UsageSource, Usage?>>()
        val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val p = connected(s, name = "甲")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Vision)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(
            listOf(
                UsageSource(p.id, "甲", "m1", UsagePurpose.Main),
                UsageSource(p.id, "甲", "m1", UsagePurpose.Vision),
                UsageSource(p.id, "甲", "m2", UsagePurpose.Light),
            ),
            seen.map { it.first },
        )
        assertEquals(Usage(3, 2), seen.first().second)
    }

    @Test fun dailyTokenBudgetPersistsClearsAndRejectsInvalidValues() {
        val s = store()
        val p = connected(s)
        assertNull(s.provider(p.id)!!.dailyTokenBudget)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        assertEquals(10_000L, store().provider(p.id)!!.dailyTokenBudget)
        for (invalid in listOf(0L, 9_999L, 100_000_001L)) {
            assertFalse(s.setDailyTokenBudget(p.id, invalid))
            assertEquals(10_000L, s.provider(p.id)!!.dailyTokenBudget)
        }
        assertTrue(s.setDailyTokenBudget(p.id, null))
        assertNull(store().provider(p.id)!!.dailyTokenBudget)
        assertFalse(s.setDailyTokenBudget("missing", 10_000))
    }

    @Test fun malformedSavedDailyBudgetLoadsAsUnlimited() {
        val s = store()
        val p = connected(s)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("\"dailyTokenBudget\":10000", "\"dailyTokenBudget\":1"))
        assertNull(store().provider(p.id)!!.dailyTokenBudget)
    }

    @Test fun failedDailyBudgetSaveLeavesTheActiveAndPersistedValuesAlone() {
        val s = store()
        val p = connected(s)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        val blocked = File(tmp.root, "providers.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(s.setDailyTokenBudget(p.id, 20_000))
        assertEquals(10_000L, s.provider(p.id)!!.dailyTokenBudget)
        assertTrue(blocked.delete())
        assertEquals(10_000L, store().provider(p.id)!!.dailyTokenBudget)
    }

    @Test fun dailyBudgetRejectsOnlyItsOwnProviderWithoutAffectingOtherCalls() = runTest {
        val used = mutableMapOf<String, Long>()
        val seen = mutableListOf<UsageSource>()
        val s = ProviderStore(
            tmp.root, AesGcmKeyCipher { key }, dailyUsage = { used[it] ?: 0 },
            usage = { source, _ -> seen += source },
        ) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val limited = connected(s, name = "Limited")
        val available = connected(s, name = "Available")
        s.use(ModelRole.Main, limited.id, "m1", none)
        s.use(ModelRole.Light, available.id, "m1", none)
        assertTrue(s.setDailyTokenBudget(limited.id, 10_000))
        assertTrue(s.setDailyTokenBudget(available.id, 10_000))
        used[limited.id] = 10_000
        used[available.id] = 9_999

        try {
            s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
            fail("达到每日预算的服务商应停止下一次请求")
        } catch (e: ModelError.DailyBudgetExceeded) {
            assertEquals("Limited", e.providerName)
            assertEquals(limited.id, e.providerId)
            assertEquals(10_000L, e.limit)
            assertEquals(10_000L, e.used)
        }
        assertTrue("拒绝时不记一笔不存在的调用", seen.isEmpty())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(listOf(available.id), seen.map { it.providerId })
    }

    /** 主动能力和服务商各有各的预算:用真的账本记账、也从它查当天用量。 */
    private class Budgets(dir: File) {
        var clock = java.time.Instant.parse("2026-11-10T04:00:00Z").toEpochMilli()
        val ledger = UsageLedger(File(dir, "usage.json"), now = { clock }, flushDelayMs = 60_000, zone = { java.time.ZoneOffset.UTC })
        var proactiveLimit = ProactiveBudget.DEFAULT_DAILY_LIMIT
    }

    private fun budgeted(b: Budgets) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key }, usage = b.ledger, dailyUsage = b.ledger::providerTokensToday,
        proactiveUsage = b.ledger::proactiveTokensToday, proactiveLimit = { b.proactiveLimit },
        proactiveCalls = b.ledger::proactiveCallsToday,
    ) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }

    @Test fun proactiveCallIgnoresTheProviderDailyBudgetAndDoesNotCountTowardsIt() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Main), Usage(10_000, 0))

        try {
            s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
            fail("服务商今天的预算已用完")
        } catch (_: ModelError.DailyBudgetExceeded) {}
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())

        assertEquals("主动能力的用量不动服务商的「今日已用」", 10_000L, b.ledger.providerTokensToday(p.id))
        assertEquals(5L, b.ledger.proactiveTokensToday())
        val proactive = b.ledger.snapshot().rows.single { it.key.purpose == UsagePurpose.Proactive }
        assertEquals("轻量用主模型时记在主模型那条配置名下", UsageKey(p.id, "m1", UsagePurpose.Proactive), proactive.key)
    }

    @Test fun exhaustedProactiveBudgetRefusesOnlyProactiveCalls() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        b.proactiveLimit = 10_000
        // 另一家记的主动能力用量也算在同一份预算里
        b.ledger.record(UsageSource("other", "O", "x", UsagePurpose.Proactive), Usage(10_000, 0))
        val before = b.ledger.snapshot()

        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("主动能力今天的预算已用完")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertEquals(10_000L, e.limit)
            assertEquals(10_000L, e.used)
        }
        assertEquals("拒绝时不记一笔不存在的调用", before, b.ledger.snapshot())

        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        assertEquals(5L, b.ledger.providerTokensToday(p.id))

        // 第二天恢复
        b.clock += 24 * UsageLedger.HOUR_MS
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())
        assertEquals("轻量模型选了 m2 就用 m2", "m2", b.ledger.snapshot().rows.last { it.key.purpose == UsagePurpose.Proactive && it.key.providerId == p.id }.key.model)
    }

    /** 服务商不报用量时 Token 数不涨,靠请求次数的上限停下(默认 20 万 Token 时是 800 次);主模型的调用不受影响。 */
    @Test fun proactiveCallsStopAtTheDailyCallLimitWhenNoUsageIsReported() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val cap = ProactiveBudget.callLimit(b.proactiveLimit)
        assertEquals(800L, cap)
        repeat(cap.toInt()) {
            b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Proactive), null)
        }
        assertEquals(0L, b.ledger.proactiveTokensToday())
        assertEquals(cap, b.ledger.proactiveCallsToday())

        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("主动能力今天的请求次数已到上限")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertTrue(e.byCalls)
        }
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
    }

    /**
     * 只剩最后一次请求额度时,适配器在同一次调用里要再发一个请求(这里是不认 include 参数的 Responses 服务:400 后去掉参数重发):
     * 不发,次数停在上限上;已经发出去的那一个照实记账。用的是真的适配器和账本。
     */
    @Test fun proactiveRetryInsideOneCallStopsAtTheCallLimit() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val b = Budgets(tmp.newFolder())
            val s = ProviderStore(
                tmp.root, AesGcmKeyCipher { key }, usage = b.ledger, dailyUsage = b.ledger::providerTokensToday,
                proactiveUsage = b.ledger::proactiveTokensToday, proactiveLimit = { b.proactiveLimit },
                proactiveCalls = b.ledger::proactiveCallsToday,
            )
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, server.url("/v1").toString()))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed))
            s.use(ModelRole.Main, p.id, "r-only", none)
            val cap = ProactiveBudget.callLimit(b.proactiveLimit)
            repeat(cap.toInt() - 1) { b.ledger.record(UsageSource(p.id, "Go", "r-only", UsagePurpose.Proactive), null) }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400)
                .setBody("""{"error":{"param":"include","message":"reasoning.encrypted_content unsupported"}}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            try {
                s.proactiveModel()!!.complete("s", emptyList(), emptyList())
                fail("只剩一次额度,重发的那个请求不该发出去")
            } catch (e: ModelError.ProactiveBudgetExceeded) {
                assertTrue(e.byCalls)
            }
            assertEquals(1, server.requestCount)
            assertEquals(cap, b.ledger.proactiveCallsToday())
        } finally {
            server.shutdown()
        }
    }

    /** 判定到八成就自己停(在 TriageRunner 里);直接的主动能力调用照样能用到整份。 */
    @Test fun directProactiveCallsMayUseTheWholeBudgetPastTheTriageShare() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val src = UsageSource(p.id, "P", "m1", UsagePurpose.Proactive)
        b.ledger.record(src, Usage(ProactiveBudget.triageShare(b.proactiveLimit).toInt(), 0))
        repeat(ProactiveBudget.triageShare(ProactiveBudget.callLimit(b.proactiveLimit)).toInt() - 1) { b.ledger.record(src, null) }
        assertEquals(ProactiveBudget.Limit.Tokens,
            ProactiveBudget.triageExhausted(b.ledger.proactiveTokensToday(), b.ledger.proactiveCallsToday(), b.proactiveLimit))
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())
    }

    /** 请求次数上限跟着 Token 上限走:调高 Token 上限后,不报用量的服务商不会停在 200 次。 */
    @Test fun raisingTheTokenLimitRaisesTheDerivedCallLimit() = runTest {
        val b = Budgets(tmp.newFolder())
        b.proactiveLimit = 50_000
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        repeat(200) { b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Proactive), null) }
        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("5 万 Token 时请求上限是下限 200 次")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertTrue(e.byCalls)
            assertEquals(200L, e.limit)
        }
        b.proactiveLimit = 2_000_000
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())
        repeat((8_000 - b.ledger.proactiveCallsToday()).toInt()) { b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Proactive), null) }
        assertEquals(8_000L, b.ledger.proactiveCallsToday())
        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("200 万 Token 时请求上限是 8000 次")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertEquals(8_000L, e.limit)
        }
    }

    @Test fun proactiveModelIsNullWithoutAnyModel() {
        assertNull(budgeted(Budgets(tmp.newFolder())).proactiveModel())
    }

    /** 主动能力的模型:回复限短;服务商写明了档位的取最低一档,没写明的照用这个用途选的档位;主动能力以外不受影响。 */
    @Test fun proactiveModelIsBuiltWithACapAndTheLowestDeclaredEffort() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(
            ListedModel("m1", reasoningEfforts = listOf("high", "low", "minimal", "weird")),
            ListedModel("m2"),
        ))
        s.use(ModelRole.Main, p.id, "m1", none)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        s.proactiveModel()
        assertEquals("minimal", setups.last().reasoningEffort)
        assertEquals(ProactiveModelSetup.CAP_THINKING, setups.last().maxOutputTokens)
        // 请求是哪一种,一路带到建模型的那份设置里:回复长的(当日归纳之类)不另设上限,档位照旧取最低的
        for (call in listOf(ProactiveCall.Notes, ProactiveCall.Day, ProactiveCall.Span)) {
            s.proactiveTargeted(call)
            assertNull(call.name, setups.last().maxOutputTokens)
            assertEquals("minimal", setups.last().reasoningEffort)
        }
        s.proactiveTargeted(ProactiveCall.Learn)
        assertEquals(ProactiveModelSetup.CAP_THINKING, setups.last().maxOutputTokens)
        s.modelFor(ModelRole.Main)
        assertEquals("用户任务照用选的档位", "high", setups.last().reasoningEffort)
        assertNull(setups.last().maxOutputTokens)
        assertEquals(TriageTargetOf(p.id, "P", "m1"), s.proactiveTarget())

        // 轻量模型单独选了 m2(服务商没写档位):不猜,不发档位
        s.use(ModelRole.Light, p.id, "m2", none)
        s.proactiveModel()
        assertNull(setups.last().reasoningEffort)
        assertEquals(TriageTargetOf(p.id, "P", "m2"), s.proactiveTarget())
    }

    @Test fun proactiveCapIsSmallOnlyWhenThinkingIsOff() {
        val short = ProactiveCall.Triage
        val listed = ReasoningOptions(listOf("none", "low", "high"), fromProvider = true)
        assertEquals("none" to ProactiveModelSetup.CAP, ProactiveModelSetup.of(ProviderConfig.Kind.Gemini, listed, "high", short))
        assertEquals(null to ProactiveModelSetup.CAP, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, null, short))
        assertEquals(null to ProactiveModelSetup.CAP_THINKING, ProactiveModelSetup.of(ProviderConfig.Kind.Gemini, null, null, short))
        val guessed = ReasoningOptions(listOf("none", "low"), fromProvider = false)
        assertEquals("手动档位不猜", "high" to ProactiveModelSetup.CAP_THINKING, ProactiveModelSetup.of(ProviderConfig.Kind.OpenAiResponses, guessed, "high", short))
        assertNull(ProactiveModelSetup.lowestDeclared(ReasoningOptions(listOf("ultra"), fromProvider = true)))
        assertEquals(ProactiveModelSetup.CAP, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, null, ProactiveCall.Learn).second)
    }

    // 分段、当日、周、月归纳的回复有十几句、每句上百字:不另设上限(不管开没开思考、走哪种协议),和普通任务一样用适配器的默认。
    // 短上限只给判定和归纳话题;它得放得下写满的一批判定(8 条、每条一句最长的理由)
    @Test fun longProactiveCallsAreNotCapped() {
        val listed = ReasoningOptions(listOf("none", "low", "high"), fromProvider = true)
        for (call in listOf(ProactiveCall.Notes, ProactiveCall.Day, ProactiveCall.Span)) {
            assertTrue(call.long)
            assertEquals("none" to null, ProactiveModelSetup.of(ProviderConfig.Kind.Gemini, listed, "high", call))
            assertEquals(null to null, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, null, call))
            assertEquals("high" to null, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, "high", call))
        }
        assertFalse(ProactiveCall.Triage.long || ProactiveCall.Learn.long)
        val fullBatch = com.wangzan101.mika.core.proactive.NoticeTriage.MAX_BATCH * (com.wangzan101.mika.core.proactive.NoticeTriage.REASON_MAX + 60)
        assertTrue("写满的一批判定(按一个字一个半 token 算)也放得下", ProactiveModelSetup.CAP >= fullBatch * 3 / 2)
    }

    @Test fun proactiveTargetIsNullWithoutAnyModel() {
        assertNull(store().proactiveTarget())
    }

    // 目标和模型是同一次读配置得来的:给出的目标就是建模型用的那条配置
    @Test fun proactiveTargetedModelIsBuiltFromTheTargetItNames() {
        val s = store()
        assertNull(s.proactiveTargeted(ProactiveCall.Triage))
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(ListedModel("m1"), ListedModel("m2")))
        s.use(ModelRole.Main, p.id, "m1", none)
        val main = s.proactiveTargeted(ProactiveCall.Triage)!!
        assertEquals(TriageTargetOf(p.id, "P", "m1"), main.target)
        assertEquals(main.target.providerId to main.target.model, built.last().first.providerId to built.last().first.model)
        s.use(ModelRole.Light, p.id, "m2", none)
        val light = s.proactiveTargeted(ProactiveCall.Triage)!!
        assertEquals(TriageTargetOf(p.id, "P", "m2"), light.target)
        assertEquals("m2", built.last().first.model)
        assertEquals(s.proactiveTarget(), light.target)
    }

    @Test fun geminiDeveloperApiKeyOnlyUsesValidGeminiProviderAndPrefersMain() {
        val s = store()
        assertNull(s.geminiDeveloperApiKey())

        val openAi = s.connect(
            "OpenAI", ProviderConfig.Kind.OpenAiCompat, "https://api.openai.com/v1",
            "sk-openai-key", listOf(ListedModel("gpt-5")),
        )
        val anthropic = s.connect(
            "Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com",
            "sk-ant-key", listOf(ListedModel("claude-sonnet-4-5")),
        )
        assertTrue(s.use(ModelRole.Main, openAi.id, "gpt-5", none))
        assertNull(s.geminiDeveloperApiKey())

        s.connect(
            "Gemini Invalid", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "bad\nkey", listOf(ListedModel("gemini-2.5-flash")),
        )
        s.connect(
            "Gemini Whitespace", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "AIza bad key", listOf(ListedModel("gemini-2.5-flash")),
        )
        s.connect(
            "Gemini Blank", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "   ", listOf(ListedModel("gemini-2.5-flash")),
        )
        assertNull(s.geminiDeveloperApiKey())

        val gemFirst = s.connect(
            "Gemini First", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "  AIza-first  ", listOf(ListedModel("gemini-2.5-flash")),
        )
        val gemMain = s.connect(
            "Gemini Main", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "AIza-main", listOf(ListedModel("gemini-2.5-pro")),
        )
        assertEquals("AIza-first", s.geminiDeveloperApiKey())

        assertTrue(s.use(ModelRole.Main, gemMain.id, "gemini-2.5-pro", none))
        assertEquals("AIza-main", s.geminiDeveloperApiKey())

        assertTrue(s.use(ModelRole.Main, anthropic.id, "claude-sonnet-4-5", none))
        assertEquals("AIza-first", s.geminiDeveloperApiKey())

        s.removeProvider(gemFirst.id)
        s.removeProvider(gemMain.id)
        assertNull(s.geminiDeveloperApiKey())
    }

    @Test fun googleGroundingAvailabilityFollowsMainModelProviderKind() {
        val s = store()
        assertEquals(GroundingStatus(available = false, providerName = null), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val gemini = s.connect(
            "Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "gem-key", listOf(ListedModel("gemini-2.5-flash")),
        )
        assertTrue(s.use(ModelRole.Main, gemini.id, "gemini-2.5-flash", none))
        assertEquals(GroundingStatus(available = true, providerName = "Gemini"), s.groundingStatus())
        assertTrue(s.googleGroundingSearch() is GoogleGroundingSearch)

        val gemNoKey = s.connect(
            "Gemini Empty", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "", listOf(ListedModel("gemini-2.5-flash")),
        )
        assertTrue(s.use(ModelRole.Main, gemNoKey.id, "gemini-2.5-flash", none))
        assertEquals(GroundingStatus(available = false, providerName = "Gemini Empty"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val local = s.connect(
            "LM Studio", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:1234/v1",
            "local-key", listOf(ListedModel("qwen-local")),
        )
        assertTrue(s.use(ModelRole.Main, local.id, "qwen-local", none))
        assertEquals(GroundingStatus(available = false, providerName = "LM Studio"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val anthropic = s.connect(
            "Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com",
            "sk-ant", listOf(ListedModel("claude-sonnet-4-6")),
        )
        assertTrue(s.use(ModelRole.Main, anthropic.id, "claude-sonnet-4-6", none))
        assertEquals(GroundingStatus(available = false, providerName = "Anthropic"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val responses = s.connect(
            "OpenAI Responses", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1",
            "sk-oai", listOf(ListedModel("gpt-5")),
        )
        assertTrue(s.use(ModelRole.Main, responses.id, "gpt-5", none))
        assertEquals(GroundingStatus(available = false, providerName = "OpenAI Responses"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())
    }

    @Test fun googleGroundingSearchExecutesGeminiRequestAndSessionHeadersAgainstMockServer() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            val dir = tmp.newFolder("grounding-gem")
            val s = ProviderStore(dir, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val base = server.url("/").toString().trimEnd('/')
            val p = s.connect(
                "Gemini", ProviderConfig.Kind.Gemini, base, "gem-secret",
                listOf(ListedModel("gemini-2.5-flash")), sessionHeader = "x-test-session",
            )
            assertTrue(s.use(ModelRole.Main, p.id, "gemini-2.5-flash", none))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"Sunny in Tokyo."}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/wx","title":"Tokyo Weather"}}],"groundingSupports":[{"segment":{"text":"Sunny in Tokyo."},"groundingChunkIndices":[0]}]}}]}""",
                ),
            )
            val result = s.googleGroundingSearch(ModelSession.of("conv-g"))!!.search("tokyo weather", 3)
            val success = result as WebSearchResult.Success
            assertEquals(1, success.hits.size)
            assertEquals("Tokyo Weather", success.hits[0].title)
            assertEquals("https://example.com/wx", success.hits[0].url)

            val req = server.takeRequest()
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", req.path)
            assertEquals("gem-secret", req.getHeader("x-goog-api-key"))
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertEquals(ModelSession.of("conv-g").id, req.getHeader("x-test-session"))
            assertTrue(req.body.readUtf8().contains("googleSearch"))
        } finally {
            server.shutdown()
        }
    }

    @Suppress("TestFunctionName")
    private fun TriageTargetOf(id: String, name: String, model: String) = com.wangzan101.mika.core.proactive.TriageTarget(id, name, model)

    private class FakeModel(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
