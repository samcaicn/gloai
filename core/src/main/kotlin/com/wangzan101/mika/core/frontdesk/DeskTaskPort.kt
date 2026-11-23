package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.StateFlow

/** 前台能对后台任务做的几件事;任务编号就是这件任务的子会话编号。 */
interface DeskTaskPort {
    /** 派一件新任务:[words] 是用户原话,[note] 是前台补的说明(两者分开交给后台),挂在 [parentConversation] 下。 */
    fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome

    /** 这件任务的状态;不认识这个编号时为 null。 */
    fun state(taskId: String): StateFlow<TaskState>?

    /** 还在开始队列里时排第几(从 1 起);已经开始或不认识时为 null。 */
    fun queuePosition(taskId: String): Int?

    /** 给在做的任务补一句用户的话;没被接受时返回 false。 */
    fun steer(taskId: String, words: String): Boolean

    /** 回答任务正在问的问题;任务没在等回答时返回 false。 */
    fun answer(taskId: String, words: String): Boolean

    /** 取消任务;已经收尾或不认识时返回 false。 */
    fun cancel(taskId: String): Boolean
}

/** 派单的结果:开始了、排在后面([position] 从 1 起)、或者没派成。 */
sealed interface DispatchOutcome {
    data class Started(val taskId: String) : DispatchOutcome
    data class Queued(val taskId: String, val position: Int) : DispatchOutcome
    data class Failed(val reason: String) : DispatchOutcome
}
