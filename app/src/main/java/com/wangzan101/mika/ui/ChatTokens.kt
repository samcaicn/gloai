package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.usage.DeepSeekCost
import com.wangzan101.mika.record.RunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency

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

/**
 * 读这些轮的运行小结(运行记录关上时由写线程记,一轮刚结束时可能还没写好:没读全就隔一会儿再读缺的那几轮)。
 * 按运行编号取,不把整份小结都解析一遍;读盘在 IO 线程。
 * 换了轮次时先留着上次读到的,读完再换,每轮结束那一行不闪。
 */
@Composable
fun rememberRunMetrics(runIds: List<String>): Map<String, RunMetrics> {
    val context = LocalContext.current
    val store = remember { RunStore(context).metrics }
    var found by remember { mutableStateOf(emptyMap<String, RunMetrics>()) }
    LaunchedEffect(runIds) {
        val wanted = runIds.toSet()
        if (wanted.isEmpty()) {
            found = emptyMap()
            return@LaunchedEffect
        }
        // 上次读到的里面还要的先留着,只去读缺的
        var read = found.filterKeys { it in wanted }
        for (wait in RETRY_MS) {
            val missing = wanted - read.keys
            if (missing.isEmpty()) break
            read = read + withContext(Dispatchers.IO) { store.byIds(missing) }
            found = read
            if (read.keys.containsAll(wanted)) break
            delay(wait)
        }
        found = read
    }
    return found
}

/** 没读全时再读的间隔;读完这几次还缺的(旧到被清掉了)就按缺着算。 */
private val RETRY_MS = listOf(300L, 1_000L, 2_000L, 4_000L, 0L)
