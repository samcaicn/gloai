package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError

/**
 * 主模型链的切换决策(项目 3):主模型这一步失败后,要不要换链上的下一个、换哪一个。纯函数,便于单测。
 * 原则:错误出在这个模型或账号身上(Key、额度、模型名、没开通、不支持工具、套餐资格)重试也没用,立刻换;
 * 一时的故障(限流、5xx、网络、流断、代理连不上)先由 [RetryPolicy] 在同一个模型上重试,
 * [ModelCaller.complete] 放弃之后到这里,同样换下一个。本任务预算用完换谁都没用,不换;
 * 服务商的每日预算只挡那一家:换不同家的才有用,同家的跳过。内容被拒、回复解析不了、思考强度被拒不换
 * (换个模型多半一样;上下文超长另有压缩处理),原样交出,失败说法和没有备援时一致。
 */
internal object Failover {
    /**
     * [e] 是链上第 [fromIndex] 个模型失败的错误；[providerIds] 与链平行，可为空表。
     * 服务商身份和每日预算使用同一个 ID。返回下一个要试的下标，不换为 null。
     */
    fun nextIndex(e: ModelError, fromIndex: Int, providerIds: List<String?>, chainSize: Int): Int? = when (e) {
        is ModelError.TaskBudgetExceeded -> null
        is ModelError.DailyBudgetExceeded -> e.providerId?.let { exhausted ->
            (fromIndex + 1 until chainSize).firstOrNull { index ->
                val candidate = providerIds.getOrNull(index)
                candidate != null && candidate != exhausted
            }
        }
        is ModelError.QuotaExceeded, is ModelError.Auth, is ModelError.IllegalHeader, is ModelError.ModelNotFound, is ModelError.ModelNotAvailable,
        is ModelError.ToolsUnsupported, is ModelError.SignInExpired, is ModelError.AccountPlanNotAllowed,
        is ModelError.ChatGptLimitReached, is ModelError.ChatGptNotEligible,
        is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut,
        // 组当前无人可用/用尽:组内已经没法换了,让链上进下一条
        is ModelError.GroupUnavailable, is ModelError.GroupExhausted,
        is ModelError.Server, is ModelError.Proxy ->
            (fromIndex + 1).takeIf { it < chainSize }
        else -> null
    }
}
