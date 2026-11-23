package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class ProviderAllowanceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ProviderAllowanceClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ProviderAllowanceClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            chain.proceed(request.newBuilder().url(server.url(path)).build())
        }.build())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun deepSeekReturnsEveryCurrencyEvenWhenTheBalanceIsZero() = runTest {
        respond("""{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0.00"},{"currency":"USD","total_balance":"1.23"}]}""")
        val balance = client.query(AllowanceSource.DeepSeek, "test-key")
        assertEquals(ProviderAllowance.Balance(listOf(money("0.00", "CNY"), money("1.23", "USD"))), balance)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/user/balance", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
    }

    @Test fun kimiAndSiliconFlowUseTheirOwnBalanceFields() = runTest {
        respond("""{"code":0,"data":{"available_balance":49.58894,"cash_balance":3.0}}""")
        respond("""{"status":true,"code":20000,"data":{"totalBalance":"88.88","chargeBalance":"88.00"}}""")
        assertEquals(ProviderAllowance.Balance(listOf(money("49.58894", "CNY"))), client.query(AllowanceSource.Kimi, "test-key"))
        assertEquals(ProviderAllowance.Balance(listOf(money("88.88", "CNY"))), client.query(AllowanceSource.SiliconFlow, "test-key"))
        assertEquals("/v1/users/me/balance", server.takeRequest().path)
        assertEquals("/v1/user/info", server.takeRequest().path)
    }

    @Test fun openRouterDistinguishesZeroFromAnUnsetLimit() = runTest {
        respond("""{"data":{"limit":10,"limit_remaining":0,"limit_reset":"weekly"}}""")
        respond("""{"data":{"limit":null}}""")
        assertEquals(ProviderAllowance.KeyLimit(money("0", "USD"), ResetPeriod.Weekly), client.query(AllowanceSource.OpenRouter, "test-key"))
        assertEquals(ProviderAllowance.KeyLimit(null), client.query(AllowanceSource.OpenRouter, "test-key"))
        assertEquals("/api/v1/key", server.takeRequest().path)
    }

    @Test fun malformedDataAndProviderErrorsAreUnavailable() = runTest {
        respond("""{"code":2,"data":{"available_balance":0}}""")
        respond("""{"status":false,"code":50000,"data":{"totalBalance":"0"}}""")
        respond("""{"data":{"usage":0}}""")
        respond("""{"balance_infos":[{"currency":"USD","total_balance":"invalid"}]}""")
        respond("<html>login</html>")
        for (source in listOf(AllowanceSource.Kimi, AllowanceSource.SiliconFlow, AllowanceSource.OpenRouter,
            AllowanceSource.DeepSeek, AllowanceSource.DeepSeek)) {
            assertTrue(runCatching { client.query(source, "test-key") }.exceptionOrNull() is ModelError.BadResponse)
        }
    }

    @Test fun rejectsUnauthorizedAndDoesNotFollowRedirects() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertTrue(runCatching { client.query(AllowanceSource.DeepSeek, "test-key") }.exceptionOrNull() is ModelError.Auth)
        val redirect = runCatching { client.query(AllowanceSource.DeepSeek, "test-key") }.exceptionOrNull() as ModelError.Server
        assertEquals(302, redirect.status)
        assertEquals(2, server.requestCount)
    }

    private fun respond(body: String) { server.enqueue(MockResponse().setBody(body)) }

    private fun money(amount: String, currency: String) = AllowanceMoney(BigDecimal(amount), currency)
}
