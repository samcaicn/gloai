package com.wangzan101.mika.core.learn.memory

/** 跨会话任务摘要的检索:按关键词、App 包名、时间范围筛,给回最相关的几条(最新的在前)。 */
class TaskMemoryIndex(private val items: List<TaskMemory>) {

    /** 一层索引里的一条:标题、时间、用到的 App/网站、一句话结论和步数,不给步骤原文。 */
    data class Hit(
        val id: String,
        val goal: String,
        val conclusion: String,
        val apps: List<String>,
        val sites: List<String>,
        val createdAt: Long,
        val steps: Int,
    )

    /** [query] 在目标、结论、步骤标签里找(空 = 不过滤);[app] 按包名过滤;[since] 只要这之后(含)的。 */
    fun search(query: String, app: String?, since: Long?, limit: Int): List<TaskMemory> {
        val q = query.trim()
        val appKey = app?.trim()?.takeIf { it.isNotEmpty() }
        return items.asSequence()
            .filter { since == null || it.createdAt >= since }
            .filter { appKey == null || it.apps.contains(appKey) }
            .filter {
                q.isEmpty() || it.goal.contains(q, ignoreCase = true) ||
                    it.conclusion.contains(q, ignoreCase = true) ||
                    it.steps.any { s -> s.label.contains(q, ignoreCase = true) }
            }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    companion object {
        fun hit(m: TaskMemory): Hit =
            Hit(m.id, m.goal, m.conclusion, m.apps, m.sites, m.createdAt, m.steps.size)
    }
}
