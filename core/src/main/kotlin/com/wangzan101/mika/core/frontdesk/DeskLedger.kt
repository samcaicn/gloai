package com.wangzan101.mika.core.frontdesk

/** 前台派出去的一件任务:[words] 是派单时的用户原话,[at] 是派单时刻。 */
data class DeskTaskEntry(val taskId: String, val title: String, val words: String, val at: Long)

/** 这次对话里前台派过的任务,按派单先后;防重复派单、核对任务编号都看它。线程安全。 */
class DeskLedger {
    private val entries = mutableListOf<DeskTaskEntry>()

    @Synchronized
    fun add(entry: DeskTaskEntry) {
        entries += entry
    }

    @Synchronized
    fun all(): List<DeskTaskEntry> = entries.toList()

    @Synchronized
    fun get(taskId: String): DeskTaskEntry? = entries.firstOrNull { it.taskId == taskId }

    /** [since] 及之后派过的、原话相同(忽略首尾空白)的最近一件;没有时为 null。 */
    @Synchronized
    fun find(words: String, since: Long): DeskTaskEntry? {
        val key = words.trim()
        return entries.lastOrNull { it.at >= since && it.words.trim() == key }
    }
}
