package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.run.record.ConversationTokens
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunMetrics
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeepSeekPricingTest {
    @Test fun calculatesCachePricesAndOnlyShowsARangeInPossiblePeakWindows() {
        val usage = Usage(1_000_000, 1_000_000, 500_000)
        val cost = DeepSeekRunCost()
        // 北京时间周一 09:00、12:00 和周日 09:00，只有第一笔在可能的高峰窗口内。
        cost.add("deepseek-flash", Instant.parse("2026-11-02T01:00:00Z"), usage, 1)
        cost.add("deepseek-flash", Instant.parse("2026-11-02T04:00:00Z"), usage, 1)
        cost.add("deepseek-v4-flash", Instant.parse("2026-11-01T01:00:00Z"), usage, 1)
        assertEquals(DeepSeekCost("12", "16"), cost.estimate(checkNotNull(DeepSeekPriceTable.parse(markdown))))
    }

    @Test fun marksUnpricedRequestsAndMissingUsageWithoutCountingThemAsFree() {
        val table = checkNotNull(DeepSeekPriceTable.parse(markdown))
        val cost = DeepSeekRunCost()
        cost.add(null, time, Usage(100, 100), 1)
        assertNull(cost.estimate(table))
        cost.add("deepseek-unknown", time, Usage(100, 100), 1)
        assertEquals(DeepSeekCost(partial = true), cost.estimate(table))
        cost.add("deepseek-flash", time, Usage(1_000_000, 1_000_000, 500_000), 2)
        cost.add("deepseek-flash", time, null, 1)
        assertEquals(DeepSeekCost("4", "4", partial = true), cost.estimate(table))
        assertEquals(DeepSeekCost(partial = true), cost.estimate(null))
    }

    @Test fun failsClosedOnChangedTableOrTariffPolicyAndRejectsRelays() {
        assertNull(DeepSeekPriceTable.parse(markdown.replace("14:00", "15:00")))
        assertNull(DeepSeekPriceTable.parse(markdown.replace("0.20元", "USD 0.20")))
        assertNull(DeepSeekPriceTable.parse(markdown.lineSequence().filterNot { "空闲时段 | 3元" in it }.joinToString("\n")))
        assertTrue(DeepSeekPriceTable.isOfficialEndpoint("https://api.deepseek.com/v1/"))
        assertFalse(DeepSeekPriceTable.isOfficialEndpoint("https://api.deepseek.com.example.test/v1"))
        assertFalse(DeepSeekPriceTable.isOfficialEndpoint("https://api.deepseek.com/v1?proxy=1"))
    }

    @Test fun cachesVerifiedPricesAndSendsNoAuthorization() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(markdown))
            server.enqueue(MockResponse().setBody("<html>temporarily unavailable</html>"))
            server.enqueue(MockResponse().setResponseCode(503))
            var now = time
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            val cache = DeepSeekPriceCache(http) { now }
            val first = checkNotNull(cache.prices())
            val request = server.takeRequest()
            assertEquals("/zh-cn/quick_start/pricing.md", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(first, cache.prices())
            now = time.plusSeconds(3_601)
            assertEquals(first, cache.prices())
            assertEquals(first, cache.prices())
            assertEquals(2, server.requestCount)
            now = time.plusSeconds(86_400)
            assertNull(cache.prices())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun preservesOptionalCostInRunRecordsAndAlignsTheDisplayedTokens() {
        val tally = RunUsageTally()
        tally.add(Usage(10, 5), 1)
        tally.add(Usage(4, 2), 1)
        val cost = DeepSeekCost("0.1", "0.2")
        val event = tally.event(10, cost)
        val decoded = RunEvent.decode(RunEvent.encode(event)) as RunEvent.ModelCalls
        assertEquals(cost, decoded.deepSeekCost)
        val metrics = checkNotNull(RunMetrics.of(listOf(
            RunEvent.TaskStarted(0, "查天气", "deepseek-flash"),
            RunEvent.ModelReply(5, 1, "", null, null, 10, 5, 0, 4),
            decoded,
        )))
        assertEquals(cost, metrics.deepSeekCost)
        assertEquals(15L, ConversationTokens.turn(metrics))
        assertEquals(21L, ConversationTokens.turn(metrics, includeSideCalls = true))
        assertNull((RunEvent.decode(RunEvent.encode(tally.event(10))) as RunEvent.ModelCalls).deepSeekCost)
    }

    private val time = Instant.parse("2026-11-01T01:00:00Z")
    private val markdown = """
        | 模型 | 模型 | 模型 | deepseek-flash(1) | deepseek-v4-pro |
        | --- | --- | --- | --- | --- |
        | 价格(2) | 百万tokens输入 / （缓存命中） | 空闲时段 | 0.20元 | 0.80元 |
        | 价格(2) | 百万tokens输入 / （缓存命中） | 高峰时段 | 0.40元 | 1.60元 |
        | 价格(2) | 百万tokens输入 / （缓存未命中） | 空闲时段 | 1.80元 | 3元 |
        | 价格(2) | 百万tokens输入 / （缓存未命中） | 高峰时段 | 3.60元 | 6元 |
        | 价格(2) | 百万tokens输出 | 空闲时段 | 3元 | 8元 |
        | 价格(2) | 百万tokens输出 | 高峰时段 | 6元 | 16元 |

        (1) 旧名 `deepseek-v4-flash` 按 Flash 价格计费。
        (2) 北京时间周一至周五（不含中国法定节假日）9:00 - 12:00、14:00 - 18:00 为高峰时段。
    """.trimIndent()
}
