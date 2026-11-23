package com.wangzan101.mika.core.model.oauth

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class XaiAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    // 绑到 127.0.0.1 并用它拼地址:MockWebServer 默认按本机主机名起服务,有的机器(localhost 被 VPN 改写过)
    // 解析出的名字不是 localhost,OauthUrls 只放行 https 和本机地址,主机核对就会拒掉它自己的地址
    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private val origin get() = "http://127.0.0.1:${server.port}"
    private val host get() = "127.0.0.1"

    private fun json(code: Int = 200, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun devicePollWaitsThenReturnsTokensAndDropsErrorBodies() = runTest {
        val secret = "device-secret-SHOULD-NOT-LEAK"
        val token = "access-secret-SHOULD-NOT-LEAK"
        var now = 0L
        val client = DeviceCodeClient(http, "$origin/device", "$origin/token", "client", "openid", setOf(host))
        server.enqueue(json(body = """{"device_code":"$secret","user_code":"ABCD-1234","verification_uri":"$origin/verify","verification_uri_complete":"$origin/verify?user_code=ABCD-1234","expires_in":30,"interval":1}"""))
        val grant = client.start()
        assertEquals("ABCD-1234", grant.challenge.userCode)
        assertFalse(grant.challenge.toString(), grant.challenge.toString().contains(secret))
        server.enqueue(json(400, """{"error":"authorization_pending","error_description":"token=$token"}"""))
        server.enqueue(json(400, """{"error":"slow_down","error_description":"token=$token"}"""))
        server.enqueue(json(body = """{"access_token":"$token","refresh_token":"r","expires_in":10,"scope":"openid"}"""))
        val slept = mutableListOf<Long>()
        val got = awaitDeviceGrant(grant, now = { now }, sleep = { slept += it; now += it }) { client.poll(it) }
        assertEquals(token, got.accessToken)
        assertEquals(listOf(1_000L, 6_000L), slept)
        val denied = runCatching {
            server.enqueue(json(400, """{"error":"access_denied","error_description":"token=$token device=$secret"}"""))
            awaitDeviceGrant(grant, now = { 0 }, sleep = {}) { client.poll(it) }
        }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Denied, denied.reason)
        assertFalse(denied.message.orEmpty(), denied.message.orEmpty().contains(token))
        assertFalse(denied.message.orEmpty(), denied.message.orEmpty().contains(secret))
    }

    /** 用户在浏览器里时网络可能一时不通:连着几次连不上才算失败,中间成功一次就从头数。 */
    @Test fun devicePollToleratesShortNetworkTrouble() = runTest {
        val grant = DeviceGrant(DeviceChallenge("U", "https://x", null, 600, 1), "d")
        val answers = ArrayDeque(listOf<DevicePoll>(
            DevicePoll.Failed(ModelError.Network()), DevicePoll.Failed(ModelError.Network()), DevicePoll.Pending,
            DevicePoll.Failed(ModelError.Server(502)), DevicePoll.Tokens(OauthTokens("ok")),
        ))
        var now = 0L
        assertEquals("ok", awaitDeviceGrant(grant, now = { now }, sleep = { now += it }) { answers.removeFirst() }.accessToken)
        val down = ArrayDeque(List(3) { DevicePoll.Failed(ModelError.Network()) })
        val e = runCatching { awaitDeviceGrant(grant, now = { now }, sleep = { now += it }) { down.removeFirst() } }.exceptionOrNull()
        assertEquals(AccountSignInFailure.Network, (e as AccountSignInException).reason)
    }

    @Test fun badIssuerIsRefusedBeforeAnyRequest() {
        assertFalse(OauthUrls.allowed("http://evil.example/token", setOf("evil.example")))
        assertFalse(OauthUrls.allowed("https://user:pw@auth.x.ai/token", setOf("auth.x.ai")))
        assertFalse(OauthUrls.allowed("https://evil.example/token", setOf("auth.x.ai")))
        assertTrue(OauthUrls.allowed("https://auth.x.ai/oauth2/token", setOf("auth.x.ai")))
        val e = runCatching { OauthUrls.check("https://evil.example/x", setOf("auth.x.ai")) }.exceptionOrNull()
        assertTrue(e is AccountSignInException && (e as AccountSignInException).reason == AccountSignInFailure.BadIssuer)
    }

    @Test fun xaiDiscoveryRejectsForeignEndpoints() = runTest {
        val auth = xai()
        server.enqueue(json(body = """{"issuer":"$origin","device_authorization_endpoint":"https://evil.example/device","token_endpoint":"$origin/oauth2/token"}"""))
        val e = runCatching { auth.discover() }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.BadIssuer, e.reason)
        assertEquals(1, server.requestCount)
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("evil-token"))
    }

    @Test fun xaiSignInUsesDeviceCodeAndDoesNotLogTokens() = runTest {
        val access = "xai-access-SECRET"
        val refresh = "xai-refresh-SECRET"
        var now = 5_000L
        val auth = xai()
        val opened = mutableListOf<String>()
        server.enqueue(discovery())
        server.enqueue(json(body = """{"device_code":"dev-SECRET","user_code":"WXYZ-9999","verification_uri":"$origin/device","verification_uri_complete":"$origin/device?user_code=WXYZ-9999","expires_in":60,"interval":1}"""))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(body = """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"id_token":"${jwt()}","scope":"openid email offline_access grok-cli:access api:access"}"""))
        server.enqueue(json(body = """{"sub":"user-9","email":"a@b.test"}"""))
        val session = auth.signIn(
            openBrowser = { opened += it },
            onChallenge = { assertEquals("WXYZ-9999", it.userCode) },
            sleep = { now += it },
            clock = { now },
        )
        assertEquals(XaiAuth.PROVIDER, session.provider)
        assertEquals("user-9", session.accountId)
        assertEquals("a@b.test", session.label)
        assertEquals(access, session.accessToken)
        assertEquals(XaiAuth.API_BASE, session.apiBase)
        assertTrue(opened.single().contains("user_code=WXYZ-9999"))
        assertFalse(opened.single(), opened.single().contains("dev-SECRET"))
        assertFalse(opened.single(), opened.single().contains(access))
        assertEquals(5, server.requestCount)
    }

    @Test fun xaiRefreshKeepsTheNewRefreshToken() = runTest {
        val auth = xai()
        val session = AccountSession(
            provider = XaiAuth.PROVIDER, accountId = "u", accessToken = "old", refreshToken = "refresh-SECRET",
            expiresAtMs = 0, clientId = XaiAuth.CLIENT_ID, issuer = origin,
        )
        server.enqueue(json(body = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":10}"""))
        val ok = auth.refresh(session) as OauthTokenResult.Ok
        assertEquals("new-access", ok.tokens.accessToken)
        assertEquals("new-refresh", ok.tokens.refreshToken)
        val req = server.takeRequest()
        assertEquals("/oauth2/token", req.path)
        assertTrue(req.body.readUtf8().contains("refresh-SECRET"))
    }

    @Test fun accountTokensRefreshOnceAndClearOnTerminalRejection() = runTest {
        var now = 10_000L
        var stored = AccountSession(XaiAuth.PROVIDER, accessToken = "old", refreshToken = "r", expiresAtMs = 0)
        val tokens = AccountTokens(
            account = "xAI",
            refresh = {
                OauthTokenResult.Ok(OauthTokens("new", "r2", expiresInSec = 100))
            },
            load = { stored },
            save = { _, next -> stored = next },
            now = { now },
        )
        assertEquals("new", tokens.get(fresh = false))
        assertEquals("new", stored.accessToken)
        assertEquals("r2", stored.refreshToken)
        now = stored.expiresAtMs
        stored = stored.copy(accessToken = "keep")
        var calls = 0
        val again = AccountTokens(
            account = "xAI",
            refresh = { calls++; OauthTokenResult.Rejected("invalid_grant", terminal = true) },
            load = { stored },
            save = { _, next -> stored = next },
            now = { now },
        )
        val e = runCatching { again.get(fresh = false) }.exceptionOrNull()
        assertTrue(e is ModelError.SignInExpired)
        assertEquals(1, calls)
        assertFalse(stored.signedIn)
    }

    @Test fun idClaimsAreDisplayOnlyAndIgnoreGarbage() {
        assertEquals("user-1" to "a@b.test", XaiAuth.idClaims(jwt()))
        assertEquals("" to "", XaiAuth.idClaims("not-a-jwt"))
        assertNull(AccountSession.fromJson("not json"))
    }

    private fun xai() = XaiAuth(http, issuer = origin, issuerHosts = setOf(host), verifyHosts = setOf(host))

    private fun discovery() = json(
        body = """{"issuer":"$origin","device_authorization_endpoint":"$origin/oauth2/device/code","token_endpoint":"$origin/oauth2/token","userinfo_endpoint":"$origin/userinfo"}""",
    )

    private fun jwt(): String {
        fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        return part("""{"alg":"none"}""") + "." + part("""{"sub":"user-1","email":"a@b.test"}""") + ".sig"
    }
}
