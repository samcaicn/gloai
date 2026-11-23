package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 金额按人民币保留原始精度。没有可估的调用时，金额为空。 */
@Serializable
data class DeepSeekCost(val minCny: String? = null, val maxCny: String? = null, val partial: Boolean = false)

internal data class DeepSeekRates(val hit: BigDecimal, val miss: BigDecimal, val output: BigDecimal) {
    fun cost(hitTokens: Long, missTokens: Long, outputTokens: Long): BigDecimal =
        (hit * hitTokens.toBigDecimal() + miss * missTokens.toBigDecimal() + output * outputTokens.toBigDecimal()).movePointLeft(6)
}

class DeepSeekPriceTable private constructor(private val models: Map<String, Pair<DeepSeekRates, DeepSeekRates>>) {
    internal fun rates(model: String) = models[model]

    companion object {
        private val directUrls = setOf("https://api.deepseek.com", "https://api.deepseek.com/v1", "https://api.deepseek.com/anthropic")
        private val modelId = Regex("(deepseek-[a-z0-9-]+)(?:\\([0-9]+\\))?")
        private val yuan = Regex("([0-9]{1,7}(?:\\.[0-9]{1,8})?)元")
        private val peakPolicy = Regex("北京时间周一至周五[（(]不含中国法定节假日[）)]9:00[-–]12:00、14:00[-–]18:00为高峰时段")

        fun isOfficialEndpoint(baseUrl: String): Boolean = baseUrl.trim().toHttpUrlOrNull()?.toString()?.trimEnd('/') in directUrls

        internal fun parse(markdown: String): DeepSeekPriceTable? {
            val compact = markdown.filterNot(Char::isWhitespace)
            if (!peakPolicy.containsMatchIn(compact)) return null
            val rows = markdown.lineSequence().filter { it.trimStart().startsWith('|') }.map { row ->
                row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim().trim('`') }
            }.toList()
            val header = rows.firstOrNull { it.size >= 4 && it.take(3).all { cell -> cell == "模型" } } ?: return null
            val ids = header.drop(3).map { modelId.matchEntire(it)?.groupValues?.get(1) ?: return null }
            if (ids.distinct().size != ids.size) return null
            val prices = mutableMapOf<Pair<Boolean, Int>, List<BigDecimal>>()
            for (row in rows.filter { it.firstOrNull()?.startsWith("价格") == true }) {
                if (row.size != header.size) return null
                val category = row[1].filterNot(Char::isWhitespace)
                val meter = when {
                    category.startsWith("百万tokens输入") && "缓存未命中" in category -> 1
                    category.startsWith("百万tokens输入") && "缓存命中" in category -> 0
                    category == "百万tokens输出" -> 2
                    else -> return null
                }
                val peak = when (row[2]) {
                    "高峰时段" -> true
                    "空闲时段" -> false
                    else -> return null
                }
                val amounts = row.drop(3).map { yuan.matchEntire(it)?.groupValues?.get(1)?.toBigDecimalOrNull() ?: return null }
                if (prices.put(peak to meter, amounts) != null) return null
            }
            if (prices.size != 6) return null
            val rates = ids.mapIndexed { i, id ->
                fun at(peak: Boolean) = DeepSeekRates(
                    prices.getValue(peak to 0)[i], prices.getValue(peak to 1)[i], prices.getValue(peak to 2)[i],
                )
                id to (at(false) to at(true))
            }.toMap().toMutableMap()
            rates["deepseek-flash"]?.let { flash ->
                if ("按Flash价格计费" in compact) {
                    for (alias in listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp")) {
                        if ("`$alias`" in markdown) rates.putIfAbsent(alias, flash)
                    }
                }
            }
            return DeepSeekPriceTable(rates)
        }
    }
}

/** 价目表由官网发布。单次读取失败时，短时间内沿用已验证的价目表。 */
class DeepSeekPriceCache(http: OkHttpClient, private val now: () -> Instant = Instant::now) {
    private val http = http.newBuilder().followRedirects(false).callTimeout(1500, TimeUnit.MILLISECONDS).build()
    private val mutex = Mutex()
    private var attemptedAt: Instant? = null
    private var snapshot: Pair<Instant, DeepSeekPriceTable>? = null

    suspend fun prices(): DeepSeekPriceTable? = mutex.withLock {
        val time = now()
        fun recent(at: Instant, maxAge: Duration): Boolean = !time.isBefore(at) && time.isBefore(at.plus(maxAge))
        snapshot?.takeIf { recent(it.first, Duration.ofHours(1)) }?.let { return@withLock it.second }
        if (attemptedAt?.let { recent(it, Duration.ofMinutes(1)) } != true) {
            attemptedAt = time
            val response = try {
                HttpSupport.execute(http, Request.Builder().url(PRICE_URL).build())
            } catch (_: ModelError) {
                null
            }
            if (response?.status == 200) {
                DeepSeekPriceTable.parse(response.body)?.let { snapshot = time to it }
            }
        }
        snapshot?.takeIf { recent(it.first, Duration.ofHours(24)) }?.second
    }

    companion object {
        private const val PRICE_URL = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing.md"
    }
}

/** 只按模型和时段累计数字，避免为长任务保留逐次调用数据。 */
class DeepSeekRunCost {
    private data class Tokens(var hit: Long = 0, var miss: Long = 0, var output: Long = 0)
    private val calls = mutableMapOf<Pair<String, Boolean>, Tokens>()
    private var deepSeekUsed = false
    private var partial = false

    @Synchronized
    internal fun add(model: String?, time: Instant, usage: Usage?, httpAttempts: Int) {
        if (httpAttempts <= 0) return
        if (model == null) {
            partial = true
            return
        }
        deepSeekUsed = true
        if (httpAttempts > 1) partial = true
        if (usage == null || usage.outputTokens < 0 || usage.cachedInputTokens !in 0..usage.inputTokens) {
            partial = true
            return
        }
        val tokens = calls.getOrPut(model to potentiallyPeak(time)) { Tokens() }
        tokens.hit += usage.cachedInputTokens.toLong()
        tokens.miss += (usage.inputTokens - usage.cachedInputTokens).toLong()
        tokens.output += usage.outputTokens.toLong()
    }

    @Synchronized
    fun hasUsage(): Boolean = calls.isNotEmpty()

    @Synchronized
    fun estimate(table: DeepSeekPriceTable?): DeepSeekCost? {
        if (!deepSeekUsed) return null
        var low: BigDecimal? = null
        var high: BigDecimal? = null
        var incomplete = partial
        for ((key, tokens) in calls) {
            val rates = table?.rates(key.first)
            if (rates == null) {
                incomplete = true
                continue
            }
            val offPeak = rates.first.cost(tokens.hit, tokens.miss, tokens.output)
            val possiblePeak = if (key.second) rates.second.cost(tokens.hit, tokens.miss, tokens.output) else offPeak
            low = (low ?: BigDecimal.ZERO) + minOf(offPeak, possiblePeak)
            high = (high ?: BigDecimal.ZERO) + maxOf(offPeak, possiblePeak)
        }
        return DeepSeekCost(low?.stripTrailingZeros()?.toPlainString(), high?.stripTrailingZeros()?.toPlainString(), incomplete)
    }

    companion object {
        private val china = ZoneId.of("Asia/Shanghai")
        private val peakWindows = listOf(LocalTime.of(9, 0) to LocalTime.of(12, 0), LocalTime.of(14, 0) to LocalTime.of(18, 0))

        private fun potentiallyPeak(time: Instant): Boolean {
            val local = time.atZone(china)
            // 工作日名义高峰也可能是法定假日，按两种价分别计算区间。
            return local.dayOfWeek.value <= 5 && peakWindows.any { (from, to) -> local.toLocalTime() >= from && local.toLocalTime() < to }
        }
    }
}
