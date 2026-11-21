package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("已达到 $maxSteps 步上限,任务没有完成。", FailKind.StepLimit)

    /** 收尾请求本身失败或仍没交出结果时，按触发收尾的原因落结局，不谎报步数用完。 */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("任务进度已经完成,但模型没有给出结果。", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("连续 ${NewDataTracker.LIMIT} 次没有读到新数据,任务已停止。", FailKind.Guard)
    }

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("模型停止了操作,没有给出回答。", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("模型的工具调用没有被服务端识别,原样作为文字返回了,没能执行。", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("模型调用异常:${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "同一个操作反复执行仍没有效果"
            "no_progress" -> if (treeBlind) "读不到这个界面的控件,点击和滑动画面也没有反应,该应用可能不响应 Mika 的自动化操作"
                else "操作多次后画面一直没有变化"
            "read_loop" -> "页面没有变化,反复读也读不出要找的内容"
            "too_many_failures" -> "操作连续失败"
            "sandbox_loop" -> "同一条命令原样重复仍然失败"
            else -> "触发了操作保护"
        }
        return TaskState.Failed("已安全停止(${v.code}):$why。", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "模型服务拒绝了请求:API Key 无效或没有权限。请在设置里检查 API Key 是否填写正确、是否有权使用该模型。"
    is ModelError.IllegalHeader -> "API Key 含有无法使用的字符。请在设置 › 模型里只粘贴密钥本身，不要带说明文字、换行或表情。"
    is ModelError.QuotaExceeded -> "模型账户额度已用完,请检查服务商账户的余额或套餐。"
    is ModelError.TaskBudgetExceeded -> "已达到本任务 ${e.limit} Token 的预算上限,任务没有完成。"
    is ModelError.DailyBudgetExceeded ->
        "服务商「${e.providerName}」已达到今天 ${e.limit} Token 的预算上限,任务没有完成。本地日期变更后会自动恢复。"
    // 主动能力的调用不在任务里发,正常不会走到这里;万一走到,只如实说明,不提示用户去改什么
    is ModelError.ProactiveBudgetExceeded -> "主动能力已达到今天的预算上限。本地日期变更后会自动恢复。"
    is ModelError.ModelNotFound -> {
        // 404 不一定是模型名不对(地址不对,或网关、中转服务说的别的情况):有原话时照原话说,不断定模型不存在
        val check = if (e.model.isEmpty()) "请到设置 › 模型检查模型名和 Base URL 是否正确。"
            else "去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。"
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "调用模型「${e.model}」时") +
                    "服务端回复：${quoted(e.detail)}。可能是模型名不对，或者服务那边暂时不可用。$check"
            e.model.isEmpty() -> "找不到该模型,$check"
            else -> "模型「${e.model}」在这个服务商那里不存在。$check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "模型「${e.model.ifEmpty { "当前模型" }}」这个账号没开通或没权限用。去设置 › 模型换一个模型，或到服务商后台开通。"
    is ModelError.ToolsUnsupported ->
        "模型「${e.model.ifEmpty { "当前模型" }}」不支持调用工具,Mika 干活要靠工具。去设置 › 模型换一个会调用工具的模型。"
    is ModelError.ContextTooLong -> "对话内容超出了模型的上下文长度,请换用上下文更长的模型,或把任务拆小一些。"
    is ModelError.ContentRefused -> "模型拒绝处理该内容,请调整任务描述或换一个模型。"
    is ModelError.ReasoningEffortRejected ->
        "模型不接受所选的思考强度。服务端回复：${quoted(e.detail)}。请到设置 › 模型,把思考强度改为默认或服务端支持的档位。"
    is ModelError.ServiceTierRejected ->
        "OpenAI 不接受所选的响应速度。" +
            (if (e.detail.isBlank()) "" else "服务端回复：${quoted(e.detail)}。") +
            "请到设置 › 模型 › 服务商，打开该模型，将响应速度改为默认或其他可用档位。"
    is ModelError.RateLimited -> "模型服务限流,重试后仍然请求太频繁。请稍后再试,或检查账户的调用频率限制。"
    is ModelError.Network -> "连接模型服务失败,重试后仍然不通。请检查网络和 Base URL。"
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "连不上网络代理,或代理没有转发这个请求,重试后仍然不通。请检查代理是否开着、设置 › 网络代理 里的地址和端口是否正确;不需要代理时把它清空。"
        ProxyFailure.AuthFailed -> "网络代理拒绝了账号或密码(HTTP 407)。请在设置 › 网络代理 里检查代理地址中的用户名和密码。"
    }
    is ModelError.Server -> "模型服务出错(HTTP ${e.status}),请稍后再试。"
    is ModelError.StreamCut ->
        "模型的回复还没传完,连接就在上游被断开了,重试后仍然如此。常见原因是网络不稳,或中转服务、网关的空闲超时太短(模型长时间思考时容易碰上)。请稍后再试。"
    is ModelError.BadResponse -> "${e.message}。请检查 Base URL 和接口类型是否匹配。"
    is ModelError.ChatGptLimitReached ->
        "ChatGPT 额度用完了,稍后再试。用量可以在 ChatGPT 设置 › 用量(chatgpt.com/settings/usage)查看;Plus 每 5 小时的额度和其他用 ChatGPT 登录的 App 共用。也可以先换一个模型。"
    is ModelError.ChatGptNotEligible ->
        "这个账号不能在其他 App 里用 ChatGPT 额度(需要 Plus 或 Pro,并且所在地区和账号设置允许)。${if (e.detail.isEmpty()) "" else "服务端说明:${quoted(e.detail)}。"}可以换一个模型。"
    is ModelError.SignInExpired -> "${e.account} 登录已失效,请在设置 › 模型里重新用 ${e.account} 登录。"
    is ModelError.AccountPlanNotAllowed ->
        "你的 ${e.account} 订阅档位不能用这个接口(HTTP 403)。${if (e.detail.isEmpty()) "" else "服务端说明:${quoted(e.detail)}。"}可以改用 ${e.account} 的 API Key,或在设置 › 模型里换一个模型。"
    is ModelError.ChainFailed -> {
        // 主消息是主模型最初的错误(上面按它的类型生成),后面附整条链每个模型的状态码与一行摘要(MK-1 第 2 批)
        val trail = e.entries.joinToString("；") { (name, err) ->
            "「$name」${chainErrorTag(err)}：${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + "本任务把整条备援链都试过了，全部失败：$trail。"
    }
}

/** 链失败记录里每个错误的短标签:认得出的写 HTTP 状态码,其余写故障名。 */
private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
