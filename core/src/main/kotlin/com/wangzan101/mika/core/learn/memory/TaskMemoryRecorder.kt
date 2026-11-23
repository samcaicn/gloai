package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE

/**
 * 把一次运行的事件压成跨会话摘要。纯函数、不请模型:[label] 把工具名换成界面的说法,[appLabel] 把包名换成 App 名。
 * 只读几次屏、没做任何动作的碎片任务不记([record] 返回 null)。App 存包名、网站存主机名,是稳定的事实,按它过滤与追溯。
 */
object TaskMemoryRecorder {

    fun record(
        goal: String,
        events: List<RunEvent>,
        now: Long,
        id: String,
        label: (String) -> String = { it },
        appLabel: (String) -> String = { it },
    ): TaskMemory? {
        val acts = events.filterIsInstance<RunEvent.Activity>()
        // 没做过任何动作(控制类动作已在记录时排除),或只是草草看了一眼:不记
        if (acts.none { it.kind != "observe" } && acts.size < 2) return null

        val reads = events.filterIsInstance<RunEvent.Observed>().filter { it.kind == ObsKind.Read.wire && it.text.isNotBlank() }
        val readByStep: Map<Int, String> = reads.groupBy { it.step }.mapValues { (_, xs) -> xs.joinToString("\n") { it.text } }

        val steps = mutableListOf<TaskMemoryStep>()
        var lastObserve: String? = null
        for (a in acts.sortedBy { it.step }) {
            val what = listOfNotNull(label(a.label), where(a.target, appLabel), a.title?.takeIf(String::isNotBlank)).joinToString(" · ")
            val tags = listOfNotNull("对外发出".takeIf { a.outward && !a.failed }, "没成功".takeIf { a.failed })
            val line = "第 ${a.step} 步 $what" + if (tags.isEmpty()) "" else "(${tags.joinToString("、")})"
            // 连着读同一处只记一行
            if (a.kind == "observe" && line == lastObserve) continue
            lastObserve = if (a.kind == "observe") line else null
            steps += TaskMemoryStep(a.atMs, line.clip(LABEL_MAX), readByStep[a.step].orEmpty().clip(DETAIL_MAX))
        }
        if (steps.isEmpty()) return null

        return TaskMemory(
            id = id,
            goal = goal.clip(GOAL_MAX),
            conclusion = conclusion(events).clip(CONCLUSION_MAX),
            apps = apps(events, acts),
            sites = sites(events, acts),
            createdAt = now,
            steps = steps.take(STEP_MAX),
        )
    }

    /**
     * 只要结局那个词(「办成」「完成」「失败」…),不带回答或理由的文字。回答里多半转述了别人写的东西的任务用它
     * (读过通知的,见 [com.wangzan101.mika.core.proactive.NoticeRuns]):摘要会被以后的任务搜到,那段字不留。
     */
    fun outcome(events: List<RunEvent>): String = conclusion(events, text = false)

    /** 一句话结论:先看交出回答后判的结局,没有再退回任务本身的结局。[text] 为 false 时只留结局那个词。 */
    private fun conclusion(events: List<RunEvent>, text: Boolean = true): String {
        events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.let { j ->
            val word = when (j.outcome) {
                "done" -> "办成"
                "partial" -> "只办成一部分"
                "not_possible" -> "没办成"
                else -> ""
            }
            return listOf(word, j.reason.takeIf { text }.orEmpty()).filter { it.isNotBlank() }.joinToString("：")
        }
        events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.let { e ->
            val word = when (e.outcome) {
                "succeeded" -> "完成"
                "unverified" -> "完成(未核实)"
                "failed" -> "失败"
                "cancelled" -> "已取消"
                "error" -> "出错"
                else -> ""
            }
            return listOf(word, e.answerOrReason.takeIf { text }.orEmpty()).filter { it.isNotBlank() }.joinToString("：")
        }
        return ""
    }

    private fun apps(events: List<RunEvent>, acts: List<RunEvent.Activity>): List<String> =
        (acts.mapNotNull { it.target }.filter { PACKAGE_NAME_RE.matches(it) } +
            events.filterIsInstance<RunEvent.Observed>().mapNotNull { it.place?.takeIf { p -> p.kind == PlaceKind.App }?.name })
            .distinct().take(APP_MAX)

    private fun sites(events: List<RunEvent>, acts: List<RunEvent.Activity>): List<String> =
        (acts.mapNotNull { it.target }.filter { it.startsWith(SITE_PREFIX) }.map { it.removePrefix(SITE_PREFIX).trim() } +
            events.filterIsInstance<RunEvent.Observed>().mapNotNull { it.place?.takeIf { p -> p.kind == PlaceKind.Site }?.name })
            .distinct().take(SITE_MAX)

    /** 活动对象给人看的说法:「网站 域名」只留域名,包名换成 App 名,别的(网址、查询词)原样;空的为 null。 */
    private fun where(target: String?, appLabel: (String) -> String): String? = target?.let {
        when {
            it.isBlank() -> null
            it.startsWith(SITE_PREFIX) -> it.removePrefix(SITE_PREFIX).trim()
            PACKAGE_NAME_RE.matches(it) -> appLabel(it)
            else -> it
        }
    }

    /** 运行记录里网页那一屏的对象写成「网站 <域名>」。 */
    private const val SITE_PREFIX = "网站 "

    private const val GOAL_MAX = 120
    private const val CONCLUSION_MAX = 200
    private const val LABEL_MAX = 200
    private const val DETAIL_MAX = 800
    private const val STEP_MAX = 60
    private const val APP_MAX = 8
    private const val SITE_MAX = 8

    private fun String.clip(max: Int): String = if (length <= max) this else take(max) + "…"
}
