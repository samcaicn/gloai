package com.wangzan101.mika.core.model.decision

import kotlinx.serialization.json.JsonElement

/** A bounded judgment over options supplied by the caller; it never generates or executes actions. */
fun interface DecisionModel {
    suspend fun choose(
        state: JsonElement,
        instructions: String,
        options: Map<String, String>,
        applicability: String?,
    ): DecisionChoice
}

/** [probability] is the chosen option's probability, not the distribution's confidence statistic. */
data class DecisionChoice(val choice: String, val probability: Double, val applicable: Double? = null)

object DecisionPolicy {
    const val MIN_PROBABILITY = 0.85
    const val MIN_APPLICABLE = 0.85
}

/** Fixed messages only: provider replies and transport exceptions can contain request content or keys. */
sealed class DecisionError(message: String) : Exception(message) {
    class InvalidRequest : DecisionError("决策请求配置无效")
    class InvalidResponse : DecisionError("决策模型返回了无效的选择或概率")
    class Network : DecisionError("决策模型网络连接失败或请求超时")
    class Http(val status: Int) : DecisionError("决策模型请求失败(HTTP $status)")
}
