package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.headerOrReject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** 查询地址固定在官方服务上，配置里的 Base URL 只用于选择服务商。 */
enum class AllowanceSource(internal val url: String) {
    DeepSeek("https://api.deepseek.com/user/balance"),
    Kimi("https://api.moonshot.cn/v1/users/me/balance"),
    SiliconFlow("https://api.siliconflow.cn/v1/user/info"),
    OpenRouter("https://openrouter.ai/api/v1/key"),
}

data class AllowanceMoney(val amount: BigDecimal, val currency: String)

sealed interface ProviderAllowance {
    data class Balance(val amounts: List<AllowanceMoney>) : ProviderAllowance

    /** remaining 为 null 表示这把 Key 没有配置支出上限。 */
    data class KeyLimit(val remaining: AllowanceMoney?, val reset: ResetPeriod? = null) : ProviderAllowance
}

enum class ResetPeriod { Daily, Weekly, Monthly }

class ProviderAllowanceClient(http: OkHttpClient) {
    private val http = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun query(source: AllowanceSource, apiKey: String): ProviderAllowance {
        val request = Request.Builder().url(source.url).headerOrReject("Authorization", "Bearer $apiKey").build()
        val response = HttpSupport.execute(http, request)
        when (response.status) {
            401, 403 -> throw ModelError.Auth()
            429 -> throw ModelError.RateLimited(HttpSupport.retryAfterMs(response.retryAfter))
            !in 200..299 -> throw ModelError.Server(response.status)
        }
        val root = try {
            Json.parseToJsonElement(response.body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ModelError.BadResponse()
        return when (source) {
            AllowanceSource.DeepSeek -> deepSeek(root)
            AllowanceSource.Kimi -> {
                if (root.text("code") != "0") throw ModelError.BadResponse()
                ProviderAllowance.Balance(listOf(AllowanceMoney(root.data().amount("available_balance"), "CNY")))
            }
            AllowanceSource.SiliconFlow -> {
                if (root.text("status") != "true" || root.text("code") != "20000") throw ModelError.BadResponse()
                ProviderAllowance.Balance(listOf(AllowanceMoney(root.data().amount("totalBalance"), "CNY")))
            }
            AllowanceSource.OpenRouter -> openRouter(root.data())
        }
    }

    private fun deepSeek(root: JsonObject): ProviderAllowance.Balance {
        val balances = root["balance_infos"] as? JsonArray ?: throw ModelError.BadResponse()
        if (balances.isEmpty()) throw ModelError.BadResponse()
        return ProviderAllowance.Balance(balances.map {
            val item = it as? JsonObject ?: throw ModelError.BadResponse()
            val currency = item.text("currency")?.takeIf { code -> code.matches(Regex("[A-Z]{3}")) }
                ?: throw ModelError.BadResponse()
            AllowanceMoney(item.amount("total_balance"), currency)
        })
    }

    private fun openRouter(data: JsonObject): ProviderAllowance.KeyLimit {
        if (data["limit"] == JsonNull) return ProviderAllowance.KeyLimit(null)
        val remaining = AllowanceMoney(data.amount("limit_remaining"), "USD")
        val period = when (data.text("limit_reset")) {
            "daily" -> ResetPeriod.Daily
            "weekly" -> ResetPeriod.Weekly
            "monthly" -> ResetPeriod.Monthly
            else -> null
        }
        return ProviderAllowance.KeyLimit(remaining, period)
    }

    private fun JsonObject.data(): JsonObject = get("data") as? JsonObject ?: throw ModelError.BadResponse()

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.amount(name: String): BigDecimal = text(name)?.toBigDecimalOrNull()
        ?: throw ModelError.BadResponse()
}
