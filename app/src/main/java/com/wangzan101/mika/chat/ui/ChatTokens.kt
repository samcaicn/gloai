package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.model.usage.DeepSeekCost
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import com.wangzan101.mika.settings.ui.UsageCopy

/** 对话页上的 token 用量:每轮结束那一行显示这一轮的用量。 */
object ChatTokensCopy {
    /** 「1.2 万 Token」,写法和 Token 用量页同一个。 */
    fun amount(n: Long): String = "${UsageCopy.tokens(n)} ${UsageCopy.UNIT}"

    fun cost(estimate: DeepSeekCost): String {
        val min = estimate.minCny?.toBigDecimalOrNull() ?: return tr(R.string.chat_task_cost_unavailable)
        val max = estimate.maxCny?.toBigDecimalOrNull() ?: return tr(R.string.chat_task_cost_unavailable)
        val formatter = NumberFormat.getCurrencyInstance(uiLocale()).apply {
            currency = Currency.getInstance("CNY")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }
        val lower = formatter.format(min)
        val upper = formatter.format(max)
        val amount = if (lower == upper) lower else "$lower–$upper"
        val result = tr(R.string.chat_task_cost_estimate, amount)
        return if (estimate.partial) tr(R.string.chat_task_cost_partial, result) else result
    }
}
