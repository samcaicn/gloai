package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.connect
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 对着本地服务核对目录请求与用户单独确认的模型试调用。 */
class ModelSetupNetworkTest {
    @get:Rule val texts = ChineseTexts()
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun modelList(vararg ids: String): MockResponse {
        val models = ids.joinToString(",") { """{"id":"$it"}""" }
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"object":"list","data":[$models]}""")
    }

    private fun setUp(kind: ProviderConfig.Kind = ProviderConfig.Kind.OpenAiCompat): Pair<ProviderStore, ModelSetupViewModel> {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
        val vm = ModelSetupViewModel(store, scope, io = Dispatchers.IO)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setKind(kind)
        vm.setBaseUrl(server.url("/").toString().trimEnd('/'))
        vm.setApiKey("sk")
        return store to vm
    }

    private fun ModelSetupViewModel.testAndWait() = runBlocking {
        testAndSave()
        withTimeout(15_000) { state.first { it.form.test != ListState.Running } }
    }

    @Test fun missingCatalogNeverFallsBackToAChatRequest() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val (store, vm) = setUp()
        vm.testAndWait()
        assertTrue(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        vm.setModel("manual-model")
        vm.testAndWait()
        assertTrue(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())

        runBlocking {
            vm.saveWithoutTest()
            // 第一家服务商一出现，表单就可能隐藏；保存手填模型并重置表单后才回到 Idle。
            withTimeout(15_000) { vm.state.first { !it.showForm && it.form.test == ListState.Idle } }
        }
        val saved = store.providers.value.single()
        assertEquals(setOf("manual-model"), saved.typedModels)
        assertTrue(saved.verifiedModels.isEmpty())
        assertNull(store.roles.value.main)
        assertEquals(listOf("/models", "/models"), List(server.requestCount) { server.takeRequest().path })
    }

    private fun toolCall(): MockResponse = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
    )

    private fun rejected(): MockResponse = MockResponse().setResponseCode(400)
        .setHeader("Content-Type", "application/json").setBody("""{"error":{"message":"bad request"}}""")

    private fun ModelSetupViewModel.pickAndWait(role: ModelRole, providerId: String, model: String) = runBlocking {
        pick(role, providerId, model)
        withTimeout(15_000) { state.first { it.manualPick[providerId] != ListState.Running } }
    }

    /**
     * 已经有主模型时保存服务商只发一次 GET;之后每次换主模型只对那一个模型发一次请求,没通过原来的主模型不变;
     * 核实过的模型不再发请求。
     */
    @Test fun savingWithAMainSendsOnlyTheCatalogRequestAndEachMainChoiceProbesOnce() {
        server.enqueue(modelList("gpt-x", "gpt-y", "tts-z"))
        server.enqueue(toolCall())
        server.enqueue(rejected())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        val other = store.connect("Other", ProviderConfig.Kind.OpenAiCompat, "https://other.test/v1", "k", emptyList())
        assertTrue(store.use(ModelRole.Main, other.id, "old-main", ProviderPresets.custom.capabilities))
        vm.testAndWait()
        val saved = store.providers.value.first { it.id != other.id }
        assertEquals(listOf("gpt-x", "gpt-y", "tts-z"), saved.models.orEmpty().map { it.id })
        assertEquals("old-main", store.configFor(ModelRole.Main)?.model)
        assertEquals(1, server.requestCount)

        vm.pickAndWait(ModelRole.Main, saved.id, "gpt-y")
        assertEquals("gpt-y", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("gpt-y"), store.provider(saved.id)?.verifiedModels)
        vm.pickAndWait(ModelRole.Main, saved.id, "gpt-x")
        assertTrue(vm.state.value.manualPick[saved.id] is ListState.Failed)
        assertEquals("没通过不换", "gpt-y", store.configFor(ModelRole.Main)?.model)

        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("GET /models", "POST /responses", "POST /responses"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
        assertTrue(requests[1].body.readUtf8().contains("\"model\":\"gpt-y\""))
        assertTrue(requests[2].body.readUtf8().contains("\"model\":\"gpt-x\""))
    }

    /** 手填的轻量模型对那一个模型发一次试调用;没通过,原来的轻量不动。 */
    @Test fun typedLightModelIsProbedOnceAndRejectedOnFailure() {
        server.enqueue(modelList("gpt-x"))
        server.enqueue(rejected())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        val other = store.connect("Other", ProviderConfig.Kind.OpenAiCompat, "https://other.test/v1", "k", emptyList())
        assertTrue(store.use(ModelRole.Main, other.id, "old-main", ProviderPresets.custom.capabilities))
        assertTrue(store.use(ModelRole.Light, other.id, "old-light", ProviderPresets.custom.capabilities))
        vm.testAndWait()
        val saved = store.providers.value.first { it.id != other.id }
        runBlocking {
            vm.pickManual(ModelRole.Light, saved.id, "gpt-typo")
            withTimeout(15_000) { vm.state.first { it.manualPick[saved.id] is ListState.Failed } }
        }
        assertEquals("old-light", store.configFor(ModelRole.Light)?.model)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("GET /models", "POST /responses"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.last().body.readUtf8().contains("\"model\":\"gpt-typo\""))
    }

    /** 第一家、还没有主模型:推荐的模型没通过时服务商照样存下、不定主模型,只发了一次试调用。 */
    @Test fun failedFirstProbeKeepsTheProviderWithoutAMain() {
        server.enqueue(modelList("gpt-x", "gpt-y"))
        server.enqueue(rejected())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val saved = store.providers.value.single()
        assertNull(store.roles.value.main)
        assertTrue(saved.verifiedModels.isEmpty())
        assertEquals(saved.id, vm.state.value.pickToProbe)
        assertTrue(vm.state.value.probeNote.orEmpty().isNotEmpty())
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("GET /models", "POST /responses"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.last().body.readUtf8().contains("\"model\":\"gpt-x\""))
    }

    @Test fun badKeyIsAKeyErrorAndNothingIsSaved() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}"""))
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertFalse(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        assertEquals(listOf("/models"), List(server.requestCount) { server.takeRequest().path })
    }

    /** 第一家、还没有主模型:只对推荐的那一个模型发一次试调用(走它自己的接口),通了当主模型;再测试只测确认的那个。 */
    @Test fun firstProviderProbesOnlyTheRecommendedResponsesModel() {
        server.enqueue(modelList("gpt-x", "gpt-y"))
        server.enqueue(toolCall())
        server.enqueue(toolCall())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val saved = store.providers.value.single()
        assertEquals("gpt-x", store.configFor(ModelRole.Main)?.model)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, store.configFor(ModelRole.Main)?.kind)
        assertEquals(setOf("gpt-x"), saved.verifiedModels)
        assertEquals(2, server.requestCount)
        runBlocking {
            vm.retestModel(saved.id, "gpt-x")
            withTimeout(15_000) { vm.state.first { it.retest.isEmpty() && server.requestCount == 3 } }
        }

        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("/models", "/responses", "/responses"), requests.map { it.path })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
        val bodies = requests.drop(1).map { it.body.readUtf8() }
        assertTrue(bodies.all { it.contains("\"model\":\"gpt-x\"") && it.contains("\"name\":\"get_time\"") })
    }
}
