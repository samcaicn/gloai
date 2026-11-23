package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError

/**
 * 主动能力(后台自己发的便宜调用,如给通知分拣)的每日 Token 预算:各服务商合计,按本地日历日算,
 * 和服务商的每日预算互不相干——主动能力的用量不算进服务商的,服务商的也不挡主动能力。
 * 没有「不限制」:没设、设得不对都按 [DEFAULT_DAILY_LIMIT]。用完后调用方应安静停下,不打扰用户。
 *
 * 另有一道按请求次数算的上限([callLimit],跟着 Token 上限走):有的服务商不报用量,失败的请求也没有用量,
 * 那时 Token 数一直是 0,只按 Token 算等于没有上限;后台调用没人盯着,不能没有上限。
 *
 * 判定(给通知分拣)只用每项上限的 [TRIAGE_SHARE_PERCENT]%,自己先停下([triageExhausted]),余下的留给主动能力里
 * 别的低频用途;各用途都不能越过的硬上限是 [check](整份)。
 */
object ProactiveBudget {
    // 真机上(不带思考的本地模型)一批判定连提示词在内实测 470–700 Token;带思考的模型会多出几倍。
    // 2 万的话只够三四十批。
    const val DEFAULT_DAILY_LIMIT = 200_000L

    /** 每天请求数上限的下限:Token 上限设得再小也至少这么多次。 */
    const val CALL_LIMIT_FLOOR = 200L

    /**
     * 每天请求数上限 = Token 上限 / 这个数。实测一次判定约 500 Token,按 250 算是两倍的余量:
     * 报用量的服务商总是先到 Token 上限;这道上限只兜住不报用量(Token 数一直是 0)的情况。
     */
    const val TOKENS_PER_CALL_ALLOWANCE = 250L

    /** 判定能用的份额(百分比),Token 和请求次数各按这个比例算;余下的留给主动能力的其他用途。 */
    const val TRIAGE_SHARE_PERCENT = 80L

    /** 用完的是哪一项上限。 */
    enum class Limit { Tokens, Calls }

    /** 每天最多实际发出的请求数(含重试),各服务商合计:跟着 Token 上限 [tokenLimit] 走,不低于 [CALL_LIMIT_FLOOR]。 */
    fun callLimit(tokenLimit: Long): Long = maxOf(CALL_LIMIT_FLOOR, tokenLimit / TOKENS_PER_CALL_ALLOWANCE)

    /** 某项上限 [limit] 里判定能用的那份。 */
    fun triageShare(limit: Long): Long = limit * TRIAGE_SHARE_PERCENT / 100

    /**
     * 今天已用 [tokensToday] Token、发了 [callsToday] 次时,按 Token 上限 [tokenLimit] 的整份算,哪一项用完了;都没用完为 null。
     * 别的用途按整份用这个;判定用 [triageExhausted]。
     */
    fun exhausted(tokensToday: Long, callsToday: Long, tokenLimit: Long): Limit? = when {
        !allowed(tokensToday, tokenLimit) -> Limit.Tokens
        !allowed(callsToday, callLimit(tokenLimit)) -> Limit.Calls
        else -> null
    }

    /** 同 [exhausted],但只算判定那份([TRIAGE_SHARE_PERCENT]%):判定到这里就自己停下。 */
    fun triageExhausted(tokensToday: Long, callsToday: Long, tokenLimit: Long): Limit? = when {
        !allowed(tokensToday, triageShare(tokenLimit)) -> Limit.Tokens
        !allowed(callsToday, triageShare(callLimit(tokenLimit))) -> Limit.Calls
        else -> null
    }

    /** 设置里存的值;不在 [TokenBudgetLimits] 范围内(含没设)的按默认值。 */
    fun limitOf(stored: Long?): Long = stored?.takeIf(TokenBudgetLimits::valid) ?: DEFAULT_DAILY_LIMIT

    /** 今天已用 [usedToday] 时还能不能再发一次;用到上限即停,和服务商的每日预算同一个口径。 */
    fun allowed(usedToday: Long, limit: Long): Boolean = usedToday < limit

    /**
     * 不能再发时抛 [ModelError.ProactiveBudgetExceeded];每次实际请求前调——一次调用开头调一次,适配器在同一次调用里
     * 再发请求(兼容重试、刷新令牌后重发)之前也调(见 [metered] 的 beforeRetry)。
     * [callsToday] 是今天已经发出的请求数(连同这次调用里已经发出、还没记进账本的),到 [callLimit] 也停(这时错误里的 [ModelError.ProactiveBudgetExceeded.byCalls] 为真)。
     * 这是整份的硬上限,各用途共用。
     */
    fun check(usedToday: Long, limit: Long, callsToday: Long = 0) {
        if (!allowed(usedToday, limit)) throw ModelError.ProactiveBudgetExceeded(limit, usedToday)
        val calls = callLimit(limit)
        if (!allowed(callsToday, calls)) throw ModelError.ProactiveBudgetExceeded(calls, callsToday, byCalls = true)
    }
}
