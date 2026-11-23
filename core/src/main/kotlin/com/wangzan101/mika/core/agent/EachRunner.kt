package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject

/**
 * Q3+Q4 原型：`do_each` 的循环执行器（未接线）。把 [ScrollCursor] 的「定位/滚动/去重」包起来，
 * 加上「做一条」的失败语义（stop/skip）与紧凑摘要。`read`/`scroll`/`doItem` 都是回调——真实实现里
 * 是 suspend + 走 ActionExecutor，这里用纯回调把语义钉清楚。
 *
 * Q3 结论：就地循环（不打开条目，就地改状态/移除）与「打开→做→返回」对执行器**没有区别**——
 * 执行器只关心「这条做没做成」；就地移除时列表上移、但 contentKey 不变，游标照常去重。
 *
 * Q4 结论：失败语义 = 做不成就停（Stop，安全默认，留下可重试）还是跳过继续（Skip，投递类常用，
 * 失败也记 done 免得下一轮又撞上同一条）。
 */
internal class EachRunner(
    private val role: String?,
    private val text: String?,
    private val onError: OnError,
) {
    enum class OnError { Stop, Skip }

    data class Outcome(val contentKey: String, val ok: Boolean)

    /** 一次循环的紧凑摘要——正是 N 轮压成 1 轮时交回给模型的东西。 */
    data class Summary(
        val done: Int,          // 做成的条数
        val failed: Int,        // 做失败的条数
        val stoppedAt: String?, // Stop 模式下停在哪条（contentKey）；跑完为 null
        val perItem: List<Outcome>,
    )

    /** [doItem] 返回 true=做成，false=失败。Stop 模式失败即停（那条不记 done），Skip 模式失败也记 done 继续。 */
    suspend fun run(
        read: suspend () -> List<JsonObject>,
        scroll: suspend () -> Unit,
        doItem: suspend (EachCursor.Item) -> Boolean,
        maxItems: Int = MAX_ITEMS,
    ): Summary {
        val cursor = ScrollCursor(role, text)
        val outcomes = mutableListOf<Outcome>()
        var justScrolled = false
        while (outcomes.size < maxItems) {
            val elements = read()
            when (val s = cursor.advance(elements, justScrolled)) {
                is ScrollCursor.Step.DoNext -> {
                    val ok = doItem(s.item)
                    outcomes += Outcome(s.item.contentKey, ok)
                    if (ok || onError == OnError.Skip) {
                        cursor.finish(s.item)
                        justScrolled = false
                    } else {
                        return Summary(outcomes.count { it.ok }, outcomes.count { !it.ok }, s.item.contentKey, outcomes)
                    }
                }
                ScrollCursor.Step.Scroll -> { scroll(); justScrolled = true }
                ScrollCursor.Step.End -> return Summary(outcomes.count { it.ok }, outcomes.count { !it.ok }, null, outcomes)
            }
        }
        // 只按尝试处理的条目数限制上限；滚动不占额度，滚不动时由游标的 End 结束。
        return Summary(outcomes.count { it.ok }, outcomes.count { !it.ok }, "__guard__", outcomes)
    }

    companion object {
        private const val MAX_ITEMS = 100_000
    }
}
