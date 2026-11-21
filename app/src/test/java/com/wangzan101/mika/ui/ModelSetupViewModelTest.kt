package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ClientSetup
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** 按地址给出列模型的结果;没写的地址列出空表。 */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    /** 下一次列模型抛这个;用来确认异常收成失败,不打掉测试协程。 */
    private var listBoom: Throwable? = null
    private var ids = 0

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Mika/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, _ ->
            listBoom?.let { throw it }
            listCalls += Triple(kind, url, apiKey)
            listing[url] ?: ModelListResult.Ok(emptyList())
        },
    )

    /** 建过的模型(模型名、请求头等);「测试并保存」的试调用回什么由 [reply] 定,默认调用试用的工具。 */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler))

    private val deepseekUrl = "https://api.deepseek.com"
    private val qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun testAndSaveStoresProviderAndPicksFirstModelAsMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        runCurrent()
        assertTrue("还没有服务商时表单展开", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        // 自动选第一个模型为主模型(只存这一条配置)
        assertEquals(listOf("deepseek-chat"), store.list().map { it.model })
        assertEquals(store.list().single().id, store.roles.value.main)
        val s = vm.state.value
        assertEquals(listOf(p), s.providers)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    /** 列出模型后让会当主模型的那个调用一次工具:带一个无参数的工具、回复上限够一次调用、带客户端标识;成功才存。 */
    @Test fun testAndSaveSendsOneTinyRequestToTheMainCandidate() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val (model, setup) = built.first()
        assertEquals("deepseek-chat", model)
        assertEquals(1024, setup.maxOutputTokens)
        assertEquals("Mika/1.2.3", setup.headers["User-Agent"])
        assertEquals("DeepSeek 没声明会话头", setOf("User-Agent"), setup.headers.keys)
        val (_, messages, tools) = probes.single()
        assertEquals(1, messages.size)
        assertEquals(listOf("get_time"), tools.map { it.name })
        assertEquals(1, store.providers.value.size)
        assertNull(vm.state.value.probeNote)
    }

    /** 接口通、模型只回了话:接着试下一个可当主模型的(明说不会调用工具的不试);都只回话就存下服务商、不定主模型,请用户选;选好后提醒收起。 */
    @Test fun modelThatOnlyAnswersTextIsSavedButNotMadeMain() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { ModelReply(listOf(Part.Text("It is noon.")), StopReason.EndTurn, null) }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(3, p.models?.size)
        assertNull("不自动选主模型", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        val s = vm.state.value
        assertFalse("存下了,表单收起", s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), built.map { it.first })
        assertEquals(
            "服务商已连上，但「deepseek-chat」没测试通过：它不会调用工具，Mika 干活需要会调用工具的模型。从列表里选一个你开通了的模型。\n" +
                "试了 deepseek-chat、deepseek-reasoner，都没测试通过。",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(s.probeBeforePick(ModelRole.Vision, p.id))
        assertFalse(s.canNext)
        vm.pick(ModelRole.Main, p.id, "deepseek-reasoner"); runCurrent()
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
    }

    /** 通义千问的 Key 下先列出别家的模型:先试自家的(qwen);第一个没开通就接着试下一个,通过的当主模型,说清先试的哪个没通过。 */
    @Test fun notActivatedModelFallsBackToTheNextCandidate() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("text-embedding-v4"),
            ListedModel("qwen3-max-preview"), ListedModel("qwen-plus"),
        ))
        reply = {
            if (built.last().first != "qwen-plus") throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.")
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("自家的先试、向量模型不试", listOf("qwen3-max-preview", "qwen-plus"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("qwen-plus", store.configFor(ModelRole.Main)?.model)
        assertTrue("qwen-plus" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "先试的 qwen3-max-preview 没测试通过：这个账号没开通或没权限用这个模型（服务商说：The product is not activated.）。主模型用了测试通过的「qwen-plus」。",
            s.probeNote,
        )
    }

    /**
     * 聚合服务商(一个列表里很多家的模型):排在最前面的那个模型上游挂了(502),后面的能用。
     * 5xx 不该当成整家不可用,按那个模型没通过算,照常存下这家、拿通过的那个当主模型。
     */
    @Test fun serverErrorOnTheFirstCandidateFallsBackToTheNext() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("meituan/longcat-flash-lite"), ListedModel("deepseek/deepseek-v4.1-flash"),
        ))
        reply = {
            if (built.last().first == "meituan/longcat-flash-lite") throw ModelError.Server(502)
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("meituan/longcat-flash-lite", "deepseek/deepseek-v4.1-flash"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("deepseek/deepseek-v4.1-flash", store.configFor(ModelRole.Main)?.model)
        assertTrue("deepseek/deepseek-v4.1-flash" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "先试的 meituan/longcat-flash-lite 没测试通过：服务端出错（HTTP 502）。" +
                "主模型用了测试通过的「deepseek/deepseek-v4.1-flash」。",
            s.probeNote,
        )
    }

    /**
     * 列表写明了某个模型不走当前接口类型(如聚合服务把只支持 /messages 的模型排在前面):不试它,直接试下一个。
     * 试了也必然失败,而每次失败要等满 PROBE_TIMEOUT_MS。
     */
    @Test fun modelOnAnotherProtocolIsSkippedWithoutProbing() = runTest {
        val url = "https://example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("deepseek/deepseek-v4-flash", protocols = listOf(ModelProtocol.ChatCompletions, ModelProtocol.Responses)),
        ))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("deepseek/deepseek-v4-flash"), built.map { it.first })
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.probeNote)
        assertEquals("deepseek/deepseek-v4-flash", store.configFor(ModelRole.Main)?.model)
    }

    /**
     * 列表给某个模型单独指定了接口(见 [ListedModel.endpoint]):调用按它自己说的协议走,与这里选的接口类型无关,
     * 不能因为 `supported_endpoints` 里没有当前接口类型就筛掉。
     */
    @Test fun modelWithItsOwnEndpointIsProbedWhateverTheKindIs() = runTest {
        val url = "https://example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel(
                "claude-sonnet-5-5",
                endpoint = ModelEndpoint(ModelProtocol.Messages, "https://example.com/provider/v1"),
                protocols = listOf(ModelProtocol.Messages),
            ),
        ))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("claude-sonnet-5-5"), built.map { it.first })
        assertEquals("claude-sonnet-5-5", store.configFor(ModelRole.Main)?.model)
    }

    /** 列表里的模型全都写明不走当前接口类型:一个都不试,照旧存下服务商,并说清为什么。 */
    @Test fun allModelsOnAnotherProtocolSaveWithAReason() = runTest {
        val url = "https://example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("claude-sonnet-5", protocols = listOf(ModelProtocol.Messages)),
        ))
        reply = { throw AssertionError("不该试调用:这些模型不走 OpenAI 兼容") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertTrue("不该试调用", built.isEmpty())
        val p = store.providers.value.single()
        val s = vm.state.value
        assertFalse(s.showForm)
        // 一个都没通过:服务商照旧存下、说清原因,并请用户自己从列表里选。
        // 这两个模型一次都没试,所以不能出现「试了 X、Y」那一行。
        // 原因是接口类型到不了它们,末尾那句不能让人去「开通」(那不是这里的问题),该换接口类型或换这个接口类型到得了的模型。
        assertEquals(p.id, s.pickToProbe)
        assertEquals(
            "服务商已连上，但「claude-sonnet-5-5」没测试通过：它不走你选的接口类型，请求到不了它。" +
                "换一个这个接口类型到得了的模型，或者换接口类型。",
            s.probeNote,
        )
    }

    /** 同上,只有一个模型、只走另一套接口:照样一句「换接口类型」,不写「试了」,也不让人去开通。 */
    @Test fun oneModelOnAnotherProtocolSaysToChangeTheInterfaceType() = runTest {
        val url = "https://example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages))))
        reply = { throw AssertionError("不该试调用:这个模型不走 OpenAI 兼容") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertTrue("不该试调用", built.isEmpty())
        assertEquals(
            "服务商已连上，但「claude-sonnet-5-5」没测试通过：它不走你选的接口类型，请求到不了它。" +
                "换一个这个接口类型到得了的模型，或者换接口类型。",
            vm.state.value.probeNote,
        )
    }

    /**
     * 列表没写 `supported_endpoints`、排头的模型只走另一套接口:服务商回 400 unsupported_model。经适配器同一套错误映射,
     * 这算那个模型的问题,接着试下一个,通过的当主模型,提示里带上服务商的原话。
     */
    @Test fun endpointMismatchOnTheFirstCandidateFallsBackToTheNext() = runTest {
        val url = "https://relay.example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("vendor/shape-a"), ListedModel("vendor/shape-b")))
        val said = "Model 'vendor/shape-a' must be called via /provider/v1/messages."
        reply = {
            val m = built.last().first
            if (m == "vendor/shape-a") {
                throw ModelHttp.openAiError(400, null, """{"error":{"message":"$said","param":"model","code":"unsupported_model"}}""", m)
            }
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("vendor/shape-a", "vendor/shape-b"), built.map { it.first })
        assertEquals("vendor/shape-b", store.configFor(ModelRole.Main)?.model)
        assertEquals(
            "先试的 vendor/shape-a 没测试通过：这个账号没开通或没权限用这个模型（服务商说：Model 'vendor/shape-a' must be called via /provider/v1/messages.）。" +
                "主模型用了测试通过的「vendor/shape-b」。",
            vm.state.value.probeNote,
        )
    }

    /** 两样一起:写明不走当前接口类型的先筛掉、不试;剩下的第一个回接口不对的 400,接着试下一个。 */
    @Test fun listedProtocolsFilterFirstThenEndpointMismatchFallsThrough() = runTest {
        val url = "https://relay.example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("vendor/messages-only", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("vendor/unknown"),
            ListedModel("vendor/chat", protocols = listOf(ModelProtocol.ChatCompletions)),
        ))
        reply = {
            val m = built.last().first
            if (m == "vendor/unknown") throw ModelHttp.openAiError(400, null, """{"error":{"code":"unsupported_model","param":"model"}}""", m)
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("写明只走 /messages 的不试", listOf("vendor/unknown", "vendor/chat"), built.map { it.first })
        assertEquals("vendor/chat", store.configFor(ModelRole.Main)?.model)
    }

    /**
     * Key 的问题不能被当成「这个模型走不了这个接口」:401,或 403 说的是 Key 不能用这个接口,
     * 第一个候选就挡住整次保存,给出真实原因,什么都不存。
     */
    @Test fun keyErrorsThatMentionTheEndpointStillBlockTheSave() = runTest {
        val url = "https://relay.example.com/provider/v1"
        for ((status, body) in listOf(
            401 to """{"error":{"message":"Invalid API key","code":"unsupported_model"}}""",
            403 to """{"error":{"message":"Your API key is not supported for this endpoint"}}""",
        )) {
            listing[url] = ModelListResult.Ok(listOf(ListedModel("vendor/a"), ListedModel("vendor/b")))
            built.clear()
            reply = { throw ModelHttp.openAiError(status, null, body, built.last().first) }
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
            assertEquals("HTTP $status:只试第一个", listOf("vendor/a"), built.map { it.first })
            val failed = vm.state.value.form.test as ListState.Failed
            assertTrue(failed.message, failed.message.contains("API Key 无效"))
            assertTrue(store.providers.value.isEmpty())
            assertNull(vm.state.value.pickToProbe)
        }
    }

    /** 试的几个都是 5xx:多半整家不可用(地址对、但服务没起来),照旧不存。 */
    @Test fun serverErrorOnEveryCandidateSavesNothing() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("a-model"), ListedModel("b-model"), ListedModel("c-model")))
        reply = { throw ModelError.Server(502) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("至多试 3 个", listOf("a-model", "b-model", "c-model"), built.map { it.first })
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("HTTP 502"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertNull(store.roles.value.main)
    }

    /** 先试的是 5xx,接着试的却是 Key 不对:前面那个 5xx 没证明 Key 能用,照旧不存。 */
    @Test fun serverErrorThenAuthErrorSavesNothing() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("a-model"), ListedModel("b-model")))
        reply = {
            if (built.last().first == "a-model") throw ModelError.Server(502)
            throw ModelError.Auth()
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("a-model", "b-model"), built.map { it.first })
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("API Key 无效"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    /** 试的几个都没开通:Key 是对的,照样存下服务商,不定主模型,说清试了哪些,请用户从列表里选;选的时候先试调用,不通不选。 */
    @Test fun allCandidatesNotActivatedSavesProviderAndAsksToPick() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("qwen-plus"), ListedModel("qwen-max"), ListedModel("qwen-turbo"),
        ))
        reply = { throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("至多试 3 个", listOf("qwen-plus", "qwen-max", "qwen-turbo"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals("sk", store.providerKey(p.id))
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(
            "服务商已连上，但「qwen-plus」没测试通过：这个账号没开通或没权限用这个模型（服务商说：The product is not activated.）。从列表里选一个你开通了的模型。\n" +
                "试了 qwen-plus、qwen-max、qwen-turbo，都没开通。",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        // 用户选了一个:先试调用,没开通就说原因、不选
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "antdigital/Ling-3.1-flash") { picked++ }; runCurrent()
        assertEquals(
            ListState.Failed("这个账号没开通「antdigital/Ling-3.1-flash」或没权限用（服务商说：The product is not activated.）。换一个模型，或到服务商后台开通。"),
            vm.state.value.manualPick[p.id],
        )
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, "qwen-turbo") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertEquals("qwen-turbo", store.configFor(ModelRole.Main)?.model)
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
    }

    /** 第一个就是 Key 不对(或别的不是模型的问题):不试别的,不存。 */
    @Test fun authErrorOnTheFirstCandidateSavesNothing() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-plus"), ListedModel("qwen-max")))
        reply = { throw ModelError.Auth() }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("qwen-plus"), built.map { it.first })
        assertTrue(vm.state.value.form.test is ListState.Failed)
        assertTrue(store.providers.value.isEmpty())
        assertNull(vm.state.value.pickToProbe)
    }

    /** 试调用失败(例如服务商要的请求头没带):服务端的报错原样给人看,什么都不存。 */
    @Test fun failedTinyRequestShowsTheServerErrorAndSavesNothing() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { throw ModelError.BadResponse("HTTP 400 {\"error\":{\"type\":\"MissingSessionID\"}}") }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("MissingSessionID"))
        assertTrue(failed.message.contains("deepseek-chat"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    @Test fun tinyRequestThatNeverAnswersTimesOut() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { awaitCancellation() }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Running, vm.state.value.form.test)
        advanceTimeBy(ModelSetupViewModel.PROBE_TIMEOUT_MS + 1); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("20"))
        assertTrue(store.providers.value.isEmpty())
    }

    /** 预设声明了会话头的(OpenCode Go):试调用就带上,存下的服务商也记着它。 */
    @Test fun presetSessionHeaderIsUsedForTheTestAndRemembered() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("glm-x", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        val setup = built.first().second
        assertTrue(setup.headers[go.sessionHeader!!].orEmpty().isNotEmpty())
        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
    }

    /** 没列出模型:先不存、不试,要一个模型名;填了再测试就用它试调用,通了存成不提供列表的服务商,它当主模型、记为核实过。 */
    @Test fun noListAsksForAModelAndProbesIt() = runTest {
        val url = "http://192.168.1.5:8000/v1"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.testAndSave(); runCurrent()
        assertTrue(probes.isEmpty())
        assertTrue("没存", store.providers.value.isEmpty())
        val f = vm.state.value.form
        assertTrue(f.noList)
        assertEquals(ListState.Idle, f.test)
        assertFalse("没填模型名不能测试", f.canTest)
        vm.setApiKey("k")
        assertTrue("换 Key 不收起模型名", vm.state.value.form.noList)
        vm.setModel(" qwen3 ")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("qwen3", built.single().first)
        val p = store.providers.value.single()
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(setOf("qwen3"), p.verifiedModels)
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        assertEquals(emptyList<String>(), vm.state.value.unverified(p))
    }

    /** 没列出模型、手填的模型试调用也说找不到:不存,说清是哪个模型、地址或接口类型可能不对,例子按地址对得上的那家。 */
    @Test fun noListAndModelNotFoundSavesNothing() = runTest {
        val url = "https://generativelanguage.googleapis.com"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("gemini") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        vm.testAndSave(); runCurrent()
        vm.setModel("gemini"); vm.testAndSave(); runCurrent()
        assertEquals(
            ListState.Failed("列不出模型，用「gemini」试调用也说找不到：地址或接口类型可能填错了，也可能没有这个模型（模型名例如 gemini-flash-latest）。"),
            vm.state.value.form.test,
        )
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        // 换了地址:要重新列,模型名先收起
        vm.setBaseUrl("$url/")
        assertFalse(vm.state.value.form.noList)
    }

    /** 预设(列表接口照理有)没列出模型、手填的找不到:只说模型不存在。 */
    @Test fun presetWithoutListReportsTheMissingModel() = runTest {
        val ark = ProviderPresets.all.first { it.label == "火山方舟" }
        listing[ark.baseUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("doubao") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ark.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        assertTrue(vm.state.value.form.noList)
        vm.setModel("doubao"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed("模型「doubao」不存在。要填服务商文档里完整的模型 ID（例如 deepseek-chat）。"), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** Cloudflare Workers AI 预设从选择、校验占位符、填写实际地址与模型到测试保存的完整流程。 */
    @Test fun cloudflarePresetFullFlowFromAccountIdToVerifiedModel() = runTest {
        val cf = ProviderPresets.all.first { it.label == "Cloudflare Workers AI" }
        val store = store()
        val vm = vm(store)

        vm.selectPreset(cf.label)
        var form = vm.state.value.form
        assertTrue(form.isCloudflare)
        assertFalse("Cloudflare 单独展示 Account ID 输入项", form.urlEditable)
        assertFalse(form.canTest)
        assertEquals(tr(com.wangzan101.mika.R.string.model_url_account_id), form.urlProblem)

        // 用户输入 Account ID 与 API Key
        val actualUrl = "https://api.cloudflare.com/client/v4/accounts/my_acc_123/ai/v1"
        vm.setAccountId("my_acc_123")
        vm.setApiKey("cf_token_xyz")
        form = vm.state.value.form
        assertEquals("my_acc_123", form.accountId)
        assertEquals(actualUrl, form.baseUrl)
        assertNull(form.urlProblem)
        assertTrue(form.canTest)

        // Cloudflare 不提供列模型接口 (GET /models 返回 405/NotSupported)
        listing[actualUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()

        form = vm.state.value.form
        assertTrue(form.noList)
        assertFalse("Requires model name to probe", form.canTest)

        // 用户输入模型名 @cf/openai/gpt-oss-20b
        vm.setModel("@cf/openai/gpt-oss-20b")
        assertTrue(vm.state.value.form.canTest)

        vm.testAndSave(); runCurrent()
        assertFalse("测试通过后关闭表单", vm.state.value.showForm)
        val saved = store.providers.value.single()
        assertEquals("Cloudflare Workers AI (my_a…_123)", saved.name)
        assertEquals(actualUrl, saved.baseUrl)
        assertEquals(setOf("@cf/openai/gpt-oss-20b"), saved.verifiedModels)
        assertEquals("@cf/openai/gpt-oss-20b", store.configFor(ModelRole.Main)?.model)
    }

    /** 自定义填了某家的官方地址:建议改用那家的预设;地址就是预设的、接口类型不同时一并说;改用时 Key 留着。 */
    @Test fun officialHostSuggestsThePreset() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.suggestedPreset)
        vm.setBaseUrl("https://generativelanguage.googleapis.com")
        vm.setApiKey("gk")
        val f = vm.state.value.form
        assertEquals("Gemini", f.suggestedPreset?.label)
        assertTrue(f.presetKindMismatch)
        assertEquals(
            "这是「Gemini」的官方地址，用它的预设更省事：地址和接口类型都已填好。接口类型也对不上：「Gemini」用的是 Gemini 接口，这里选的是 OpenAI 兼容。",
            ModelSetupCopy.presetSuggestion(f),
        )
        assertEquals(ProviderConfig.Kind.Gemini, f.exampleKind)
        // 同一主机的别的路径(如它的兼容接口):照样建议,但不说接口类型不对
        vm.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals("Gemini", vm.state.value.form.suggestedPreset?.label)
        assertFalse(vm.state.value.form.presetKindMismatch)
        vm.setKind(ProviderConfig.Kind.Gemini)
        vm.setBaseUrl("https://api.anthropic.com")
        assertEquals("Anthropic", vm.state.value.form.suggestedPreset?.label)
        assertTrue(vm.state.value.form.presetKindMismatch)
        vm.selectPreset("Anthropic")
        val after = vm.state.value.form
        assertEquals(ProviderConfig.Kind.Anthropic, after.kind)
        assertEquals("gk", after.apiKey)
        assertNull("选了预设就不再建议", after.suggestedPreset)
    }

    /** 选择页里手填模型名:先试调用,不通不选、说原因;通了才选上、记为核实过。 */
    @Test fun manualPickProbesFirst() = runTest {
        val store = store()
        val p = store.connect("192.168.1.5", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        val vm = vm(store)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("gemini") }
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "gemini") { picked++ }
        assertEquals(ListState.Running, vm.state.value.manualPick[p.id])
        runCurrent()
        assertEquals(ListState.Failed("模型「gemini」不存在。要填服务商文档里完整的模型 ID（例如 deepseek-chat）。"), vm.state.value.manualPick[p.id])
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        // 只回话不调用工具的,当不了主模型
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.pickManual(ModelRole.Main, p.id, "chatty") { picked++ }; runCurrent()
        assertEquals(ListState.Failed("chatty 没有调用工具，Mika 干活需要会调用工具的模型，换一个当主模型。"), vm.state.value.manualPick[p.id])
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, " qwen3 ") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertNull(vm.state.value.manualPick[p.id])
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("qwen3"), store.provider(p.id)?.verifiedModels)
    }

    /** 旧版本存下的不提供列表的服务商(手填的模型从没试过):提醒,「重新测试」通了提醒消失、记下;不通说原因。 */
    @Test fun unverifiedManualModelsWarnUntilRetested() = runTest {
        val store = store()
        val p = store.connect("generativelanguage.googleapis.com", ProviderConfig.Kind.OpenAiCompat, "https://generativelanguage.googleapis.com", "k", emptyList())
        store.use(ModelRole.Main, p.id, "gemini", ProviderPresets.custom.capabilities)
        val vm = vm(store)
        runCurrent()
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { throw ModelError.ModelNotFound("gemini") }
        vm.retest(p.id); runCurrent()
        assertEquals(
            ListState.Failed("模型「gemini」不存在。要填服务商文档里完整的模型 ID（例如 gemini-flash-latest）。"),
            vm.state.value.retest[p.id],
        )
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.retest(p.id); runCurrent()
        assertNull(vm.state.value.retest[p.id])
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.single()))
        // 有列表的服务商不提醒
        listing[deepseekUrl] = deepseek
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val ds = vm.state.value.providers.first { it.name == "DeepSeek" }
        vm.pick(ModelRole.Light, ds.id, "deepseek-coder"); runCurrent()
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.first { it.name == "DeepSeek" }))
    }

    /** 明文 http:本机、局域网的不提示;别的地址提醒一句,照样能测试。 */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("这个地址不加密，API Key 和对话内容可能被同一网络里的人看到。", vm.state.value.form.unencryptedWarning)
        vm.setApiKey("k")
        assertTrue(vm.state.value.form.canTest)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-vl", vision = true), ListedModel("qwen-max")))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("通义千问")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "通义千问"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("第二家不改主模型", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-max"), ListedModel("qwen-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.openForm(); vm.selectPreset("通义千问"); vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        val qwen = vm.state.value.providers.first { it.name == "通义千问" }
        // 选择页列出所有服务商的全部模型,不按能力过滤
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, qwen.id, "qwen-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("qwen-plain", vision.model)
        assertEquals(qwen.id, vision.providerId)
        assertTrue("选了看图就记看图", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(qwen))
        // 用主模型:看图这一路用主模型,不再有单独的看图配置
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(qwen))
        // 不用
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("没人用的配置删掉", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset("自定义")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("自定义的 OpenAI 兼容接口可以不填 Key", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // 服务不提供列表:先要一个模型名,没存
        assertTrue(store.providers.value.isEmpty())
        assertFalse(vm.state.value.canNext)
        // 模型只回话、不调用工具:存下服务商,但不当主模型
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.setModel("chatty"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        // 手填过的 chatty 没当上主模型也记着,选的时候还在
        assertEquals(listOf("qwen3", "chatty"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("已有的配置保留它的能力", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("失败时保留原来的列表", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.wangzan101.mika.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.wangzan101.mika.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("工具调用 · 未确认" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("工具调用" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("不支持工具调用" to false), ModelSetupCopy.tags(o[2]))
    }

    /** 自定义服务商可以起名字(去掉首尾空白);不填用地址的主机名。同一个地址起不同的名字是两家。 */
    @Test fun customProviderUsesTheGivenNameOrFallsBackToTheHost() = runTest {
        val url = "https://relay.example.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("m1")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k1")
        assertEquals("relay.example.com", vm.state.value.form.defaultName)
        vm.setName("  我的中转  ")
        vm.testAndSave(); runCurrent()
        assertEquals(listOf("我的中转"), store.providers.value.map { it.name })
        assertEquals("", vm.state.value.form.name)

        vm.openForm()
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k2"); vm.setName("   ")
        vm.testAndSave(); runCurrent()
        val names = store.providers.value.map { it.name }
        assertEquals(listOf("我的中转", "relay.example.com"), names)
        assertEquals(listOf("k1", "k2"), store.providers.value.map { store.providerKey(it.id) })
    }

    /** 名字有长度上限;换成预设时清掉;地址是某家预设的,默认名就是预设名;预设的名字不受表单里的名字影响。 */
    @Test fun customNameIsCappedAndOnlyAppliesToCustom() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setName("名".repeat(ModelSetupViewModel.MAX_NAME + 8))
        assertEquals(ModelSetupViewModel.MAX_NAME, vm.state.value.form.name.length)
        // 按字符截:emoji 不被截成半个
        vm.setName("😀".repeat(ModelSetupViewModel.MAX_NAME + 3))
        val capped = vm.state.value.form.name
        assertEquals(ModelSetupViewModel.MAX_NAME, capped.codePointCount(0, capped.length))
        assertEquals("😀".repeat(ModelSetupViewModel.MAX_NAME), capped)
        vm.setBaseUrl(deepseekUrl)
        assertEquals("DeepSeek", vm.state.value.form.defaultName)
        vm.selectPreset("DeepSeek")
        assertEquals("", vm.state.value.form.name)
        assertEquals("DeepSeek", vm.state.value.form.copy(name = "别的名字").providerName)
    }

    /**
     * 手填模型名不看列表的结果:列得出(没有它)、列出空表、没有列表接口、Key 不对、服务端出错、回的不是 JSON,
     * 都只用手填的那个试调用;试调用通了才存,记为手填。
     */
    private fun TestScope.typedModelIsProbedWhatever(listed: ModelListResult) {
        val url = "https://relay.example.com/v1"
        listing[url] = listed
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        assertFalse("没点之前不显示模型名", vm.state.value.form.showModel)
        vm.setManual(true)
        assertTrue(vm.state.value.form.showModel)
        vm.setModel(" plan-model ")
        vm.testAndSave(); runCurrent()
        assertEquals("只试手填的那个", listOf("plan-model"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(setOf("plan-model"), p.verifiedModels)
        assertEquals(setOf("plan-model"), p.typedModels)
        assertEquals("plan-model", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.options(p).single { it.id == "plan-model" }.typed)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun typedModelWithAListThatLacksIt() = runTest { typedModelIsProbedWhatever(deepseek) }
    @Test fun typedModelWithAnEmptyList() = runTest { typedModelIsProbedWhatever(ModelListResult.Ok(emptyList())) }
    @Test fun typedModelWithoutAListEndpoint() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.NotSupported)) }
    @Test fun typedModelWhenListingSaysTheKeyIsWrong() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.Auth)) }
    @Test fun typedModelWhenListingHitsAServerError() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.Server, "HTTP 500")) }
    @Test fun typedModelWhenListingIsNotJson() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.BadResponse)) }

    /** 列模型出错、没手填:照实说原因(Key 不对就说 Key 不对),展开模型名一栏,填了再测还是要试调用通过。 */
    @Test fun listErrorShowsTheReasonAndOpensTheModelField() = runTest {
        for ((i, failure) in listOf(ListFailure.Auth, ListFailure.Server, ListFailure.BadResponse).withIndex()) {
            val url = "https://relay$i.example.com/v1"
            listing[url] = ModelListResult.Failed(failure)
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
            vm.testAndSave(); runCurrent()
            val f = vm.state.value.form
            assertEquals(ListState.Failed(failure.text), f.test)
            assertTrue("出错后可以手填", f.showModel)
            assertTrue(store.providers.value.isEmpty())
            // 试调用也说 Key 不对:不存
            reply = { throw ModelError.Auth() }
            vm.setModel("plan-model"); vm.testAndSave(); runCurrent()
            assertTrue(vm.state.value.form.test is ListState.Failed)
            assertTrue(store.providers.value.isEmpty())
            reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
            vm.closeForm()
        }
    }

    /** 列表里就有手填的那个:只试它,不标手填。 */
    @Test fun typedModelThatIsListedIsNotMarkedTyped() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-reasoner"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("deepseek-reasoner"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(emptySet<String>(), p.typedModels)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
        // 收起「手动填写」就不用填的那个
        vm.openForm(); vm.setApiKey("sk"); vm.setManual(true); vm.setModel("x"); vm.setManual(false)
        assertEquals("", vm.state.value.form.typedModel)
    }

    /** 选择页里有列表的那家也能手填;通了记为手填,刷新列表后还在,标「手填」。 */
    @Test fun pickerAllowsTypingAModelForAListedProvider() = runTest {
        val store = store()
        val p = store.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk", deepseek.models)
        val vm = vm(store)
        runCurrent()
        vm.pickManual(ModelRole.Light, p.id, "deepseek-v9"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
        assertEquals("存进文件", setOf("deepseek-v9"), store().provider(p.id)?.typedModels)
        store.rememberModels(p.id, deepseek.models)
        vm.pick(ModelRole.Light, p.id, "deepseek-chat"); runCurrent()
        val options = vm.state.value.options(store.provider(p.id)!!)
        assertTrue(options.single { it.id == "deepseek-v9" }.typed)
        assertFalse(options.single { it.id == "deepseek-chat" }.typed)
        // 选列表里的不记为手填
        vm.pickManual(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
    }

    // ---------- 不测试,直接保存 ----------

    /** 一次网络都不打:不列模型、不试调用,只存下地址、Key 和「没测过」这件事。 */
    @Test fun saveWithoutTestStoresProviderWithoutListingOrProbe() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.saveWithoutTest(); runCurrent()
        assertTrue("一次列模型都没调", listCalls.isEmpty())
        assertTrue("一次试调用都没有", built.isEmpty())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals("sk-1", store.providerKey(p.id))
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue("标成没测过", p.savedUntested)
        // 存进文件也在
        assertTrue(store().provider(p.id)!!.savedUntested)
        // 没有主模型:留给用户去选(选的时候先试调用)
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertEquals(p.id, s.pickToProbe)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue("存好后说清它没测过", s.probeNote?.contains(p.name) == true)
    }

    /** 手填了模型名:只记为手填,不自动当主模型;选它当主模型时先试调用,通了才用。 */
    @Test fun saveWithoutTestWithTypedModelDoesNotBecomeMainUntilProbed() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-v9")
        vm.saveWithoutTest(); runCurrent()
        assertTrue(built.isEmpty())
        assertTrue(listCalls.isEmpty())
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-v9"), p.typedModels)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue(p.savedUntested)
        assertNull("没测过的模型不自动当主模型", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        assertEquals(p.id, vm.state.value.pickToProbe)
        // 重开设置(界面状态没了)也一样:跳过测试存下的那家选主模型先试调用
        val again = vm(store)
        runCurrent()
        assertNull(again.state.value.pickToProbe)
        assertTrue(again.state.value.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(again.state.value.probeBeforePick(ModelRole.Light, p.id))
        again.pickManual(ModelRole.Main, p.id, "deepseek-v9"); runCurrent()
        assertEquals(listOf("deepseek-v9"), built.map { it.first })
        assertEquals("deepseek-v9", store.configFor(ModelRole.Main)?.model)
        assertFalse("试通过了就不算没测过", store.provider(p.id)!!.savedUntested)
        assertFalse(again.state.value.probeBeforePick(ModelRole.Main, p.id))
    }

    /** 已经有主模型时跳过测试存下另一家:主模型不变。 */
    @Test fun saveWithoutTestKeepsTheCurrentMainModel() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val main = store.configFor(ModelRole.Main)
        assertEquals("deepseek-chat", main?.model)
        vm.openForm(); vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://127.0.0.1:11434/v1"); vm.setManual(true); vm.setModel("local-model")
        vm.saveWithoutTest(); runCurrent()
        assertEquals(main, store.configFor(ModelRole.Main))
        assertNull(vm.state.value.pickToProbe)
    }

    /** 地址格式不对、或该填 Key 的没填,什么都不存。 */
    @Test fun saveWithoutTestRefusesAnInvalidForm() = runTest {
        val store = store()
        val vm = vm(store)
        assertFalse("还没填 Key", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("api.deepseek.com")
        vm.setApiKey("sk")
        assertFalse("地址没写协议", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        // 自己跑的服务不要 Key,可以存
        vm.setBaseUrl("http://127.0.0.1:11434/v1")
        vm.setApiKey("")
        assertTrue(vm.state.value.form.canSaveUntested)
    }

    /** 已经测过的那家再跳过测试存一次:服务商不重复,Key 换成新的,已核实过的模型留着。 */
    @Test fun saveWithoutTestKeepsExistingProviderAndItsVerifiedModels() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-chat"), p.verifiedModels)
        assertFalse(p.savedUntested)
        vm.openForm(); vm.setApiKey("sk-2"); vm.saveWithoutTest(); runCurrent()
        assertEquals("不重复添加", 1, store.providers.value.size)
        val after = store.provider(p.id)!!
        assertEquals("sk-2", store.providerKey(p.id))
        assertEquals("列过的列表留着", listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder"), after.models!!.map { it.id })
        assertEquals(setOf("deepseek-chat"), after.verifiedModels)
        assertFalse("核实过的模型让这家不算没测过", after.savedUntested)
    }

    // ---------- 粘贴进来的 Key 里有换行、零宽或非 ASCII ----------

    /** 中间的换行原样留下,立刻标红,两个保存动作都不发请求、也不存。 */
    @Test fun newlineInTheKeyIsRejectedAndNotSaved() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-ab\ncd")
        val form = vm.state.value.form
        assertEquals("sk-ab\ncd", form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, form.keyNotice)
        assertFalse(form.canTest)
        assertFalse(form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(built.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    /** `\r` 和 `\r\n` 同样留下,不改写,也不崩溃。 */
    @Test fun crlfInTheKeyIsRejectedAndDoesNotCrash() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-ab\r\ncd")
        assertEquals("sk-ab\r\ncd", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        vm.setApiKey("sk-\rcd")
        assertEquals("sk-\rcd", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canSaveUntested)
    }

    /** 中文前缀留下,立刻标红,测试和直接保存都不发请求、也不存。改掉之后提示跟着消失。 */
    @Test fun chinesePrefixIsRejectedAndNotSaved() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("密钥：sk-abc123")
        val form = vm.state.value.form
        assertEquals("密钥：sk-abc123", form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, form.keyNotice)
        assertFalse(form.canTest)
        assertFalse(form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(built.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("sk-abc123")
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
    }

    /** 清空等于把整段删掉:红字和「去掉不可见字符」都收起,空着时两个保存动作仍不可用。 */
    @Test fun clearingTheKeyDropsHintsAndLeavesSaveDisabled() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("密钥：sk-\nabc")
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        vm.setApiKey("")
        assertEquals("", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(store.providers.value.isEmpty())

        vm.setApiKey("sk-\u200Babc")
        assertEquals("sk-abc", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.setApiKey("")
        assertEquals("", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
    }

    /** 零宽空格去掉后按普通 Key 用,不标成非法字符。 */
    @Test fun zeroWidthSpaceIsStrippedAndTreatedAsANormalKey() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-\u200Babc")
        assertEquals("sk-abc", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("sk-abc", listCalls.single().third)
        assertEquals("sk-abc", store.providerKey(store.providers.value.single().id))
    }

    /** 中间的普通空格不是非法字符:照旧拿去问服务商,失败走原来的「连接失败」。 */
    @Test fun spaceInsideTheKeyStillHitsTheProvider() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1 2")
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("sk-1 2", listCalls.single().third)
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** 换 Key 走同一套:换行和中文前缀都不保存、也不重新列模型。零宽去掉后可以换。 */
    @Test fun changeKeyRefusesNewlinesAndIllegalCharacters() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val id = store.providers.value.single().id
        val listed = listCalls.size
        vm.changeKey(id, "sk-\n2"); runCurrent()
        assertEquals("换行的 Key 不存", "sk", store.providerKey(id))
        assertEquals("换行的 Key 不重新列模型", listed, listCalls.size)
        vm.changeKey(id, "密钥：sk-3"); runCurrent()
        assertEquals("没存非法的 Key", "sk", store.providerKey(id))
        assertEquals("非法的 Key 不重新列模型", listed, listCalls.size)
        vm.changeKey(id, "sk-\u200B2"); runCurrent()
        assertEquals("sk-2", store.providerKey(id))
        assertEquals(listed + 1, listCalls.size)
        assertEquals("sk-2", listCalls.last().third)
    }

    /** 列模型时头值仍抛异常:收成失败,不打掉协程,也不把异常原文(可能含 Key)露出去。 */
    @Test fun listingExceptionDoesNotCrashAndHidesTheHeaderValue() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val id = store.providers.value.single().id
        listBoom = IllegalArgumentException("Unexpected char 0x0a in Authorization value: Bearer sk-\nabc")
        vm.refresh(id); runCurrent()
        val failed = vm.state.value.listing[id] as ListState.Failed
        assertEquals("Key 里有无法使用的字符，请只粘贴密钥本身。", failed.message)
        assertFalse(failed.message.contains("Bearer"))
        assertEquals("sk", store.providerKey(id))
    }
}
