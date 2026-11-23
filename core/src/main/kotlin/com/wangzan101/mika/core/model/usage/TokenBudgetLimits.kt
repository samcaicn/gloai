package com.wangzan101.mika.core.model.usage

/** 用户可设的 Token 预算范围；null 在设置层表示不限制。 */
object TokenBudgetLimits {
    const val MIN = 10_000L
    const val MAX = 100_000_000L
    val PRESETS = listOf(500_000L, 1_000_000L, 5_000_000L, 10_000_000L)

    fun valid(value: Long): Boolean = value in MIN..MAX
}
