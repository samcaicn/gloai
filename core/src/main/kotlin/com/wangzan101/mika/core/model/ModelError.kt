package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("API Key 无效或没有权限")
    /**
     * 请求头的值含有 OkHttp 拒绝的字符(换行、其它控制字符、非 ASCII)。在调用 `header()` 之前抛出,
     * 避免构造 Request 时 [IllegalArgumentException] 把进程打掉。消息里不带头的原文,免得把 Key 打进日志。
     * [header] 只是头的名字。
     */
    class IllegalHeader(val header: String) : ModelError("请求头含有无法使用的字符")
    class QuotaExceeded : ModelError("额度已用完")
    /** 本任务的已报告用量达到预算；不再发起任何收尾或重试请求。 */
    class TaskBudgetExceeded(val limit: Long, val used: Long) : ModelError("本任务的 Token 预算已用完")
    /** 这家服务商今天在本地已报告的用量达到预算；[providerName] 只用于给用户说明是哪一家。 */
    class DailyBudgetExceeded(
        val providerName: String, val limit: Long, val used: Long, val providerId: String? = null,
    ) : ModelError("服务商今日的 Token 预算已用完")
    /**
     * 主动能力今天(本地日历日,各服务商合计)的已报告用量达到它自己的预算;不发请求。
     * [byCalls] 为真时到的是请求次数的上限,[limit]、[used] 是次数;否则是 Token。
     * 只会出现在主动能力的后台调用里,调用方应安静停下,不当作用户任务的失败去提示。见 [com.wangzan101.mika.core.model.usage.ProactiveBudget]。
     */
    class ProactiveBudgetExceeded(val limit: Long, val used: Long, val byCalls: Boolean = false) : ModelError("主动能力今日的预算已用完")
    class RateLimited(val retryAfterMs: Long?) : ModelError("请求太频繁,请稍后再试")
    class ContextTooLong : ModelError("对话内容超出模型上下文长度")
    /**
     * 服务商那里没有这个模型(或地址不对,请求的接口不存在);[model] 是请求的模型名,不知道时为空。
     * [detail] 是服务端 JSON 报错里的原话(截短),没有为空:网关、中转服务还会用 404 说别的情况(比如它的上游模型这会儿都不可用),
     * 看原话才分得出是哪一种。
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "找不到该模型" else "找不到模型「$model」")
    /**
     * 服务商有这个模型,这个账号却用不了(没开通、没权限、没买);[detail] 是服务端的原话(截短),没有为空。
     * 换一个模型或到服务商后台开通才行,重试没用。见 [ModelAccessErrors]。
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "这个账号用不了该模型(没开通或没权限)" else "这个账号用不了模型「$model」(没开通或没权限)")
    /** 服务端明说这个模型不支持调用工具(带工具的请求被拒)。Mika 干活靠工具,它当不了主模型。 */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "该模型不支持调用工具" else "模型「$model」不支持调用工具")
    class Network(cause: Throwable? = null) : ModelError("网络连接失败", cause)
    /**
     * 走网络代理时代理那一段出的错(连不上代理,或代理拒了账号密码),不是服务商的问题。见 [com.wangzan101.mika.core.net.NetProxy]。
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "连不上网络代理"
            ProxyFailure.AuthFailed -> "网络代理拒绝了账号或密码"
        },
        cause,
    )
    /**
     * 流式回复还没收尾(没有结束标记,也没有结束原因)连接就关了:多半是途中的网关、代理空闲超时或网络切换把连接掐断。
     * 回复不完整、不能用,重发同一个请求通常就好;和接口配置无关。
     */
    class StreamCut : ModelError("回复还没传完,连接就被断开了")
    /** [detail] 保住服务商的报错原话:分类归 Server 走切换,原话给连接探测等诊断场景看(MK-1 第 2 批)。 */
    class Server(val status: Int, val detail: String? = null) : ModelError(
        "模型服务出错(HTTP $status)" + (detail?.takeIf { it.isNotBlank() }?.let { ":" + it.take(200) } ?: ""),
    )
    class ContentRefused : ModelError("模型拒绝处理该内容")
    /** 服务端明确拒绝思考强度参数:保留服务端原话,引导改档位,不能误报成地址或接口错误。 */
    class ReasoningEffortRejected(val detail: String) : ModelError("模型不接受所选的思考强度")
    class ServiceTierRejected(val detail: String) : ModelError("OpenAI 不接受所选的响应速度")
    /** ChatGPT 套餐给其他 App 用的额度到了上限:不重试,等额度恢复或换模型。 */
    class ChatGptLimitReached : ModelError("ChatGPT 额度用完了")
    /** 这个 ChatGPT 账号不能在其他 App 里用套餐额度(套餐不符、地区或账号策略不允许):不重试。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "这个账号不能在其他 App 里用 ChatGPT 额度" else "这个账号不能在其他 App 里用 ChatGPT 额度: $detail")
    /** 登录已失效(换新令牌被拒,或用户在账号里断开了这个 App),要重新登录。[account] 是哪一家的登录(界面上的名字)。 */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("登录已失效,请重新登录")
    /**
     * 用账号登录的(如 xAI)接口回 HTTP 403:登录是成功的,这个订阅档位却不能调这个接口。不重试;
     * 改用这家的 API Key 才行。[account] 是哪一家,[detail] 是服务端的原话(截短),没有为空。
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("你的${account}订阅档位不能用这个接口")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "模型返回了无法解析的内容" else "模型返回了无法解析的内容: $detail", cause)
    /**
     * 主模型链整条都试过、全部失败(MK-1 第 2 批):[primary] 是主模型最初的错误,失败说法以它为主;
     * [entries] 按尝试顺序记每个模型(名字、它犯的错误),主模型自己是第一条。任务/每日预算错误不包这层,原样交出。
     */
    class ChainFailed(val primary: ModelError, val entries: List<Pair<String, ModelError>>) :
        ModelError(primary.message.orEmpty())

    /** 模型组里没有可用成员:全部停用、能力不符、冷却或限额。[detail] 说明是哪一类,不含内容。 */
    class GroupUnavailable(val group: String = "", val detail: String = "") :
        ModelError(if (detail.isEmpty()) "模型组「$group」没有可用的模型" else "模型组「$group」没有可用的模型: $detail")

    /** 模型组里成员都达到限额。 */
    class GroupExhausted(val group: String = "", val detail: String = "") :
        ModelError(if (detail.isEmpty()) "模型组「$group」的模型都达到限额" else "模型组「$group」的模型都达到限额: $detail")
}

/** 错误分类的共用判断:哪些错误是一时的,过一会儿再发同一个请求可能就好。 */
object ModelErrors {
    fun transient(e: Throwable): Boolean = when (e) {
        is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
        is ModelError.Server -> e.status in 500..599
        is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
        else -> false
    }
}
