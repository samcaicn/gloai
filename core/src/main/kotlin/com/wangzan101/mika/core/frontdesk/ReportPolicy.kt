package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState

/**
 * 哪些任务事件前台要主动说:开始、排队、要用户回答或动手、收尾一律说;用户自己取消的不说;
 * 进度在同一任务上次汇报后 [progressQuietMs] 内不说,免得一直念叨;用户问起进展时前台照任务板答,不靠这里放行。
 * [shouldReport] 返回 true 即视为这次已汇报,从这一刻重新计时。
 */
class ReportPolicy(private val clock: () -> Long, private val progressQuietMs: Long = 90_000) {
    private val lastReportAt = HashMap<String, Long>()

    @Synchronized
    fun shouldReport(event: DeskEvent): Boolean {
        val report = when (event) {
            // 和 saysEnding 一致:用户自己取消的不说
            is DeskEvent.Cancelled -> false
            is DeskEvent.Progress -> quietPassed(event.taskId)
            else -> true
        }
        when (event) {
            is DeskEvent.Succeeded, is DeskEvent.Failed, is DeskEvent.Cancelled -> forget(event.taskId)
            else -> if (report) lastReportAt[event.taskId] = clock()
        }
        return report
    }

    private fun quietPassed(taskId: String): Boolean {
        val last = lastReportAt[taskId] ?: return true
        return clock() - last >= progressQuietMs
    }

    /** 收尾了的任务不会再有进度,记录清掉。 */
    private fun forget(taskId: String) {
        lastReportAt -= taskId
    }

    companion object {
        /**
         * 没有入口接着(没人在看这条会话)、汇报只能走兜底时,这一条要不要发成通知:收尾了的、前置条件不满足的发;
         * 开始、排队、进度不发,不打扰用户(会话里写哪些行由兜底自己定)。要用户回答、确认、接管的也只写回会话:任务自己会发那条带回复框和
         * 确认、接管按钮的「需要你处理」通知,前台再发就是同一件事两条;前置条件任务不另发提醒,所以由前台发。
         */
        fun notifies(event: DeskEvent): Boolean = when (event) {
            is DeskEvent.Started, is DeskEvent.Queued, is DeskEvent.Progress -> false
            is DeskEvent.NeedsAnswer -> false
            is DeskEvent.NeedsAction -> event.kind == ActionKind.Prerequisite
            is DeskEvent.Succeeded, is DeskEvent.Failed, is DeskEvent.Cancelled -> true
        }

        /** 前台跟着的任务以 [end] 收尾时,前台会不会把这个结局说出来:用户自己取消的不说,其余都说(和 [shouldReport] 一致)。 */
        fun saysEnding(end: TaskState.Terminal): Boolean = end != TaskState.Cancelled
    }
}
