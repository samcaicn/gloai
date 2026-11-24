package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ConversationCall

/**
 * 聊天页里语音通话的那几行浅色小字:通话里说的话照常一轮轮排在对话里,前后各一行「语音通话 · 9:41」「通话结束 · 3:12」;
 * 一句话没说的通话只占一行「语音通话 · 0:05 · 没有说话」,按接通时刻排进对话。
 */
internal sealed interface CallMark {
    val call: ConversationCall
    /** 通话里说的第一轮前面那行;还在通话里、或没说上话但还没挂的,也是这一行。 */
    data class Start(override val call: ConversationCall) : CallMark
    /** 通话里说的最后一轮后面那行。 */
    data class End(override val call: ConversationCall) : CallMark
    /** 挂了也没说上话:只这一行。 */
    data class Silent(override val call: ConversationCall) : CallMark
}

/** 这一行在列表里的 key。开头那行(和没说话那行)用 [callStartKey],从通话记录跳过来就跳到它。 */
internal val CallMark.key: String
    get() = when (this) {
        is CallMark.End -> "call-end-${call.id}"
        else -> callStartKey(call.id)
    }

internal fun callStartKey(callId: String) = "call-$callId"

/** 每一轮前面要插的通话行([before] 按轮次下标),和排在所有轮次后面的([tail])。 */
internal class CallMarks(private val before: Map<Int, List<CallMark>>, val tail: List<CallMark>) {
    fun before(index: Int): List<CallMark> = before[index].orEmpty()
    fun isEmpty() = before.isEmpty() && tail.isEmpty()

    companion object {
        val None = CallMarks(emptyMap(), emptyList())
    }
}

/**
 * 按存下的轮次([Exchange.callId]、[Exchange.atMs])和通话记录排出通话行。
 * - 有轮次的一通:第一轮前一行开头,最后一轮后一行结束(还没挂的不画结束);通话记录丢了也照样画开头,时刻取第一轮的。
 * - 没轮次的一通:排在接通之后存下的第一轮前面;后面没有存下的轮次就排在最后。
 */
internal fun callMarks(exchanges: List<Exchange>, calls: List<ConversationCall>): CallMarks {
    if (calls.isEmpty() && exchanges.none { it.callId != null }) return CallMarks.None
    val byId = calls.associateBy { it.id }
    // (放在哪一轮前面, 排序时刻, 行);下标等于轮次数的是排在最后的
    val placed = mutableListOf<Triple<Int, Long, CallMark>>()
    val segments = exchanges.withIndex().filter { it.value.callId != null }.groupBy { it.value.callId!! }
    segments.forEach { (id, rows) ->
        val first = rows.first().index
        val last = rows.last().index
        val call = byId[id] ?: ConversationCall(id, rows.first().value.atMs ?: 0L)
        placed += Triple(first, call.startMs, CallMark.Start(call))
        call.endMs?.let { placed += Triple(last + 1, it, CallMark.End(call)) }
    }
    calls.filter { it.id !in segments }.forEach { call ->
        val at = exchanges.indexOfFirst { ex -> ex.atMs.let { it == null || it > call.startMs } }.let { if (it < 0) exchanges.size else it }
        placed += Triple(at, call.startMs, if (call.endMs == null) CallMark.Start(call) else CallMark.Silent(call))
    }
    val sorted = placed.sortedWith(compareBy({ it.first }, { it.second }))
    val before = sorted.filter { it.first < exchanges.size }.groupBy({ it.first }, { it.third })
    return CallMarks(before, sorted.filter { it.first >= exchanges.size }.map { it.third })
}
