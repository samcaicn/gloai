package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** 删掉一个项目会连带什么:有多少条对话、多少个运行中或排队中的任务、以及绑在这些对话上的定时任务数(定时计划保留)。 */
data class ProjectDeleteImpact(val conversations: Int, val runningTasks: Int, val schedules: Int)

/**
 * 删掉一个项目:停掉项目内所有对话在跑或排队的任务并等它们收尾,删除项目内对话历史和项目定义,
 * 然后删除项目共享工作区(`p-<projectId>`)并清理对应导入记录。定时计划保留。
 */
class ProjectDeleter(
    private val projects: ProjectStore,
    private val conversations: ConversationStore,
    private val active: StateFlow<List<String>>,
    private val cancelTasks: (conversationId: String) -> Unit,
    private val schedulesOf: (conversationId: String) -> List<String>,
    private val deleteProjectWorkspace: suspend (projectId: String) -> Unit,
    private val stopWaitMs: Long = 15_000,
    private val io: CoroutineContext = Dispatchers.IO,
    private val forgetRemoteTasks: (String) -> Unit = {},
    private val onDeleted: suspend (projectId: String, conversationIds: List<String>) -> Unit = { _, _ -> },
) {
    fun impact(projectId: String): ProjectDeleteImpact {
        val memberIds = memberConversationIds(projectId)
        val memberSet = memberIds.toSet()
        val runningCount = active.value.count { it in memberSet }
        val scheduleCount = memberIds.sumOf { id -> runCatching { schedulesOf(id).size }.getOrDefault(0) }
        return ProjectDeleteImpact(memberIds.size, runningCount, scheduleCount)
    }

    suspend fun delete(projectId: String) {
        val memberIds = withContext(io) { memberConversationIds(projectId) }
        val memberSet = memberIds.toSet()
        val activeMembers = active.value.filter { it in memberSet }
        if (activeMembers.isNotEmpty()) {
            activeMembers.forEach(cancelTasks)
            withTimeoutOrNull(stopWaitMs) { active.first { current -> current.none { it in memberSet } } }
        }
        val deletedIds = withContext(io) {
            // 停完任务后重新收集一次成员(期间可能有新落盘的旁文件)，然后删记录与项目定义
            val allMembers = (memberIds + memberConversationIds(projectId)).distinct()
            for (id in allMembers) {
                forgetRemoteTasks(id)
                conversations.delete(id)
            }
            projects.delete(projectId)
            allMembers
        }
        try { deleteProjectWorkspace(projectId) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { onDeleted(projectId, deletedIds) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    /**
     * 项目里的对话:存下了首轮、旁文件指向这个项目的,加上任务在跑或排队、首轮还没存下的。
     * 新对话发出首句时先写 `.project` 旁文件,首轮等任务收尾才写进对话文件,[ConversationStore.list] 里还没有它;
     * 漏掉它,删项目时它的任务不停,收尾时又把对话写回来。
     */
    fun memberConversationIds(projectId: String): List<String> =
        (conversations.list(includeArchived = true).filter { it.projectId == projectId }.map { it.id } +
            active.value.filter { conversations.projectId(it) == projectId }).distinct()
}
