package com.wangzan101.mika.core.frontdesk

/** 前台派出去的一件任务:[words] 是派单时的用户原话,[at] 是派单时刻。 */
data class DeskTaskEntry(val taskId: String, val title: String, val words: String, val at: Long)

/** 这次对话里前台派过的任务,按派单先后;防重复派单、核对任务编号都看它。线程安全。 */
class DeskLedger {
    /** 每件和它的查重键(前台要的是哪件;默认就是原话)。 */
    private val entries = mutableListOf<Pair<DeskTaskEntry, String>>()

    @Synchronized
    fun add(entry: DeskTaskEntry, asked: String = entry.words) {
        entries += entry to asked.trim()
    }

    @Synchronized
    fun all(): List<DeskTaskEntry> = entries.map { it.first }

    @Synchronized
    fun get(taskId: String): DeskTaskEntry? = entries.firstOrNull { it.first.taskId == taskId }?.first

    /** [since] 及之后派过的、查重键相同(忽略首尾空白)的最近一件;没有时为 null。 */
    @Synchronized
    fun find(asked: String, since: Long): DeskTaskEntry? {
        val key = asked.trim()
        return entries.lastOrNull { (entry, k) -> entry.at >= since && k == key }?.first
    }
}
