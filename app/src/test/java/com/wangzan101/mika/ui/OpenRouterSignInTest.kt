package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * 用 OpenRouter 账号登录走一遍:浏览器(假的)打开授权页,带着 code 回到本机回调,换到 Key,
 * 按 OpenRouter 预设存成服务商、选上主模型、打开详情;退出只删本机的 Key。换 Key 的接口是本地假服务。
 */
class OpenRouterSignInTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val listCalls = mutableListOf<Pair<String, String?>>()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun store(
        models: List<ListedModel> = listOf(ListedModel("openai/gpt-x"), ListedModel("anthropic/claude-x")),
    ) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        openRouterAuth = OpenRouterAuth(http, keysUrl = server.url("/api/v1/auth/keys").toString()),
        modelLister = { _, url, apiKey, _ ->
            synchronized(listCalls) { listCalls += url to apiKey }
            ModelListResult.Ok(models)
        },
    )

    /** 假浏览器:打开授权页时照用户同意了那样,带着 code 和 state 请求本机回调;[pages] 记下回调后浏览器里显示的结果。 */
    private inner class Browser(private val approve: Boolean = true) : SignInBrowser {
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        override fun open(url: String) {
            opened += url
            val u = url.toHttpUrl()
            val callback = u.queryParameter("callback_url")!!.toHttpUrl().newBuilder().host("127.0.0.1")
            if (approve) callback.addQueryParameter("code", "the-code").addQueryParameter("state", u.queryParameter("state"))
            else callback.addQueryParameter("error", "access_denied")
            Thread { http.newCall(Request.Builder().url(callback.build()).build()).execute().close() }.start()
        }
        override fun returnToApp() {}
        override fun page(failure: String?): String { pages += failure; return "<p>ok</p>" }
    }

    private fun ModelSetupViewModel.signInAndWait(provider: AccountProvider) = runBlocking {
        signInWithAccount(provider)
        withTimeout(15_000) { state.first { !it.accountSignIn.running && (it.signedInProvider != null || it.accountSignIn.failure != null) } }
    }

    @Test fun signInStoresAnEncryptedKeyAndOpensTheProvider() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-SECRET"}"""))
        val s = store()
        val browser = Browser()
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        val p = s.openRouterAccount()!!
        assertEquals(p.id, ui.signedInProvider)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals(OpenRouterAuth.API_BASE, p.baseUrl)
        assertEquals("OpenRouter", p.name)
        assertEquals("sk-or-v1-SECRET", s.providerKey(p.id))
        assertEquals(listOf(OpenRouterAuth.API_BASE to "sk-or-v1-SECRET"), listCalls)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertNotNull("还没有主模型时选一个", ui.roles.main)
        assertEquals(listOf<String?>(null), browser.pages)
        assertTrue(browser.opened.single().startsWith("https://openrouter.ai/auth?"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("\"code\":\"the-code\""))
        // Key 只加密存,不进配置文件
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("SECRET")) }
        // 重新读入时还认得是授权来的
        assertEquals(p.id, store().openRouterAccount()?.id)

        vm.signedInShown()
        assertNull(vm.state.value.signedInProvider)
        runBlocking {
            vm.signOutAccount(p.id)
            withTimeout(5_000) { vm.state.first { it.openRouterAccount?.signedIn == false } }
        }
        assertNull(s.providerKey(p.id))
        assertNotNull("服务商和模型留着", s.provider(p.id)?.models)
        assertTrue(vm.state.value.signedOut(s.provider(p.id)!!))
    }

    @Test fun deniedAuthorizationSaysSoAndSavesNothing() {
        val s = store()
        val browser = Browser(approve = false)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)
        assertEquals(AccountProvider.OpenRouter, ui.accountSignIn.provider)
        assertEquals("已取消登录。", ui.accountSignIn.failure)
        assertEquals(listOf<String?>("已取消登录。"), browser.pages)
        assertTrue(s.providers.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun accountWithOnlyTtsModelsHasNoDefaultMainModel() {
        val models = listOf(
            ListedModel("fishaudio/drama-3-int4"),
            ListedModel("vendor/synth", unsupportedApis = listOf("/v1/audio/speech")),
            ListedModel("vendor/opaque"),
        )
        val s = store(models)
        val existing = s.connectOpenRouter("old-key", models)
        models.forEach { assertTrue(s.setTtsModel(existing.id, it.id, enabled = true)) }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-NEW"}"""))
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = Browser(), signIns = ChatGptSignIns(scope))

        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        assertEquals(existing.id, ui.signedInProvider)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertEquals(models, s.openRouterAccount()?.models)
        assertNull(ui.roles.main)
    }

    /**
     * 用户已经自己填了 OpenRouter 的 Key:授权登录另存一家,不顶掉那把 Key;退出登录只删授权来的那把。
     * 之后再自己填 Key「测试并保存」,也只更新自己填的那一家,不碰授权来的。
     */
    @Test fun signInKeepsAKeyTheUserTypedForOpenRouter() {
        val s = store()
        val typed = s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed", emptyList())
        val signed = s.connectOpenRouter("sk-or-v1-signed", emptyList())
        assertFalse(typed.id == signed.id)
        assertEquals("sk-typed", s.providerKey(typed.id))
        assertEquals("sk-or-v1-signed", s.providerKey(signed.id))
        assertNull(typed.signIn)
        assertEquals(signed.id, s.openRouterAccount()?.id)
        // 再登录一次更新授权来的那一家,不多出第三家
        assertEquals(signed.id, s.connectOpenRouter("sk-or-v1-again", emptyList()).id)
        assertEquals(2, s.providers.value.size)
        // 自己再填一次 Key:更新自己那一家
        assertEquals(typed.id, s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed-2", emptyList()).id)
        assertEquals("sk-or-v1-again", s.providerKey(signed.id))
        s.signOutOpenRouter(signed.id)
        assertNull(s.providerKey(signed.id))
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // 填 Key 的那一家不能被当成登录退出
        s.signOutOpenRouter(typed.id)
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // 重新读入后两家照样分开
        val reloaded = store()
        assertEquals(signed.id, reloaded.openRouterAccount()?.id)
        assertEquals("sk-typed-2", reloaded.providerKey(typed.id))
    }
}
