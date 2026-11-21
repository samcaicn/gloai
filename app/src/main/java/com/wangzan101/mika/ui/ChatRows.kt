package com.wangzan101.mika.ui

import com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState

/**
 * 「去设置」那行跟在哪一轮后面:对得上运行编号的跟在那轮;对不上(编号还没同步进对话、或那次没开出
 * 运行记录)返回 -1,照旧挂在最后。不归当前对话、没有触发、或没有轮次可挂时返回 null,不显示。
 */
internal fun phoneSetupRow(need: PhoneSetupPrompt.Need?, conversationId: String?, exchanges: List<Exchange>): Int? {
    if (need == null || exchanges.isEmpty()) return null
    if (need.conversationId != null && need.conversationId != conversationId) return null
    val match = need.runId?.let { rid -> exchanges.indexOfFirst { it.runId == rid } } ?: -1
    return if (match >= 0) match else -1
}

/**
 * 当前 UI 状态会生成的行 key,按 LazyColumn 里的顺序。自动滚到末尾和“跳到某一轮的消息”都用这一份,
 * 不再各自数一遍。条件和 ChatScreen.exchange 及其附属行一一对应;末尾状态行即使暂时没有可见内容也占一个 LazyList item。
 */
internal fun chatItemKeys(
    exchanges: List<Exchange>,
    kept: KeptPage?,
    phoneSetupRow: Int?,
    hasBlocked: Boolean,
): List<String> = buildList {
    if (exchanges.isEmpty() && !hasBlocked) add("empty")
    exchanges.forEachIndexed { index, ex ->
        if (ex.goal.isNotEmpty()) add("u$index")
        val active = ex.status.isActive()
        val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
        val showSteps = active && !answering
        // 没在跑步骤(结束了、或在写总结)时,插话收成紧跟用户消息下面的小字
        if (!showSteps) {
            ex.steers.filterNot { it.state == SteerState.Withdrawn }.forEach { note -> add("m$index-${note.id}") }
        }
        if (showSteps) add("s$index")
        if (!ex.draft.isNullOrBlank()) add("d$index")
        add("r$index")
        if (KeptRow.of(ex, kept) != null) add("k$index")
        if (ex.files.isNotEmpty()) add("f$index")
        if (ex.runId != null && (ex.memoryNotes.isNotEmpty() || ex.skillNotes.isNotEmpty())) add("m$index")
        if (phoneSetupRow == index) add("ps$index")
    }
    if (phoneSetupRow == -1 && exchanges.isNotEmpty()) add("phone-setup")
    if (hasBlocked) add("blocked")
}

/**
 * 当前 UI 状态会在 LazyColumn 里生成多少行。自动滚到末尾必须用这份当前状态算目标,不能读上一帧的
 * LazyListLayoutInfo.totalItemsCount:发送后的副作用可能先于新行测量执行,旧行数会把新轮次留在视口外。
 */
internal fun chatItemCount(
    exchanges: List<Exchange>,
    kept: KeptPage?,
    phoneSetupRow: Int?,
    hasBlocked: Boolean,
): Int = chatItemKeys(exchanges, kept, phoneSetupRow, hasBlocked).size

/** 目标消息在 [chatItemKeys] 里的 key;轮次或消息已经不在了时返回 null。 */
internal fun chatTargetKey(exchanges: List<Exchange>, target: ChatTarget): String? {
    val turn = target.turnIndex
    val ex = exchanges.getOrNull(turn) ?: return null
    return when (target) {
        is ChatTarget.Message -> {
            val note = target.steerIndex?.let { visibleSteer(ex, it) }
            val showSteps = ex.status.isActive() && !(ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank())
            // 插话在工作时是步骤里的一步(无独立行),收成小字后(结束/写总结)才有独立行可跳
            if (target.steerIndex != null && note != null && !showSteps) "m$turn-${note.id}" else turnStartKey(turn, ex)
        }
        is ChatTarget.Reply -> turnStartKey(turn, ex)
        is ChatTarget.Files -> if (ex.files.isNotEmpty()) "f$turn" else turnStartKey(turn, ex)
        is ChatTarget.Status -> "r$turn"
    }
}

/** 撤回的插话不在聊天页里显示,按位置找时要跳过。 */
private fun visibleSteer(ex: Exchange, index: Int): SteerNote? =
    ex.steers.filterNot { it.state == SteerState.Withdrawn }.getOrNull(index)

/** 这一轮的开头:有用户气泡就跳到气泡,否则退回结果卡。 */
private fun turnStartKey(turnIndex: Int, ex: Exchange): String =
    if (ex.goal.isNotEmpty()) "u$turnIndex" else "r$turnIndex"

private val ChatTarget.turnIndex: Int
    get() = when (this) {
        is ChatTarget.Message -> this.turnIndex
        is ChatTarget.Reply -> this.turnIndex
        is ChatTarget.Files -> this.turnIndex
        is ChatTarget.Status -> this.turnIndex
    }
