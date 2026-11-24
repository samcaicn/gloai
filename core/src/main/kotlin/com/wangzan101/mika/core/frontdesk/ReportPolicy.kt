package com.wangzan101.mika.core.frontdesk

/**
 * 哪些任务事件前台要主动说:开始、排队、要用户回答或动手、收尾一律说;用户自己取消的不说;
 * 进度在同一任务上次汇报后 [progressQuietMs] 内不说,免得一直念叨,用户问起([noteUserAsked])后的下一条照说。
 * [shouldReport] 返回 true 即视为这次已汇报,从这一刻重新计时。
 */
class ReportPolicy(private val clock: () -> Long, private val progressQuietMs: Long = 90_000) {
    private val lastReportAt = HashMap<String, Long>()
    private val askedTasks = HashSet<String>()

    @Synchronized
    fun shouldReport(event: DeskEvent): Boolean {
        val report = when (event) {
            is DeskEvent.Cancelled -> false
            is DeskEvent.Progress -> askedTasks.remove(event.taskId) || quietPassed(event.taskId)
            else -> true
        }
        when (event) {
            is DeskEvent.Succeeded, is DeskEvent.Failed, is DeskEvent.Cancelled -> forget(event.taskId)
            else -> if (report) lastReportAt[event.taskId] = clock()
        }
        return report
    }

    /** 用户问起这件任务的进展:下一条进度不受静默期限制。 */
    @Synchronized
    fun noteUserAsked(taskId: String) {
        askedTasks += taskId
    }

    private fun quietPassed(taskId: String): Boolean {
        val last = lastReportAt[taskId] ?: return true
        return clock() - last >= progressQuietMs
    }

    /** 收尾了的任务不会再有进度,记录清掉。 */
    private fun forget(taskId: String) {
        lastReportAt -= taskId
        askedTasks -= taskId
    }

    companion object {
        /**
         * 没有入口接着(没人在看这条会话)、汇报只能走兜底时,这一条要不要发成通知:要用户回答或动手、收尾了的发;
         * 开始、排队、进度只写回会话,不打扰用户——前台是它派出去的任务的唯一声音,不该一直念叨。
         */
        fun notifies(event: DeskEvent): Boolean = when (event) {
            is DeskEvent.Started, is DeskEvent.Queued, is DeskEvent.Progress -> false
            is DeskEvent.NeedsAnswer, is DeskEvent.NeedsAction -> true
            is DeskEvent.Succeeded, is DeskEvent.Failed, is DeskEvent.Cancelled -> true
        }
    }
}
