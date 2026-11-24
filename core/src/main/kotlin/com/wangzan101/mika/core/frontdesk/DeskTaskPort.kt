package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.StateFlow

/** 前台能对后台任务做的几件事;任务编号就是这件任务的子会话编号。 */
interface DeskTaskPort {
    /** 派一件新任务:[words] 是用户原话,[note] 是前台补的说明(两者分开交给后台),挂在 [parentConversation] 下。 */
    fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome

    /**
     * 派一件要带文件的新任务(用户在输入框带着附件发来的一句):先开好子会话(挂在 [parentConversation] 下),
     * [prepare] 把文件放进子会话的工作区,给出交给后台的原话(用户的话后面接上文件行);它返回 null(文件没放进去)
     * 或出错时删掉子会话、不派。没有说明:这一句不经前台模型。不支持带文件的端口回 Failed。
     */
    suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome =
        DispatchOutcome.Failed("files_unsupported")

    /** 这件任务的状态;不认识这个编号时为 null。 */
    fun state(taskId: String): StateFlow<TaskState>?

    /**
     * 运行 [runKey] 是不是这个端口正跟着的那件任务的运行,是的话给出任务编号。一件任务的子会话里之后再开的运行
     * 不算;端口不分运行时为 null。
     */
    fun followedTaskOf(runKey: String): String? = null

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
