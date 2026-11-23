package com.wangzan101.mika.core.frontdesk

/** 前台的任务板:把这次对话派过的任务列成几行字,每轮放进前台的上下文,前台看着说,不用自己去查。 */
object DeskBoard {
    /** 每件任务一行:编号、标题、状态(排队时带排第几)、进展;没有任务时为 null。 */
    fun render(views: List<DeskTaskView>): String? = views.takeIf { it.isNotEmpty() }?.joinToString("\n") { view ->
        val state = when (view.state) {
            DeskTaskPhase.Queued -> view.position?.let { "queued (position $it)" } ?: "queued"
            else -> word(view.state)
        }
        listOfNotNull("- task_id ${view.taskId}", view.title, state, progress(view)).joinToString(" · ")
    }

    /** 在做、要你处理的列出最近几条进展(旧的在前);其余状态只列当前一句。 */
    private fun progress(view: DeskTaskView): String? = when (view.state) {
        DeskTaskPhase.Running, DeskTaskPhase.NeedsYou -> view.recent.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: view.line
        else -> view.line
    }

    private fun word(phase: DeskTaskPhase): String = when (phase) {
        DeskTaskPhase.Running -> "running"
        DeskTaskPhase.Queued -> "queued"
        DeskTaskPhase.NeedsYou -> "needs_you"
        DeskTaskPhase.Done -> "done"
        DeskTaskPhase.Failed -> "failed"
        DeskTaskPhase.Cancelled -> "cancelled"
    }
}
