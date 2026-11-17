package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.EditedSchedule

class LocalScheduleAdmin internal constructor(
    private val plans: () -> List<TriggerDefinition>,
    private val changeEnabled: (String, Boolean) -> Boolean,
    private val delete: (String) -> Unit,
    private val changeTime: (String, Long) -> Unit,
    private val executeNow: (String) -> Unit,
    private val editPlan: (String, String, (TriggerDefinition) -> TriggerDefinition) -> EditedSchedule? = { _, _, _ -> null },
) : ScheduleAdmin {
    constructor(context: Context) : this(
        { TriggerRuntime.list(context) },
        { id, enabled -> TriggerRuntime.setEnabled(context, id, enabled) },
        { id -> TriggerRuntime.remove(context, id) },
        { id, atMs -> TriggerRuntime.reschedule(context, id, atMs) },
        { id -> TriggerRuntime.runNow(context, id) },
        { thread, id, change -> TriggerRuntime.edit(context, thread, id, change) },
    )

    // 计划按设备全局可见,不按对话过滤:抽屉里手动建的计划各挂在一个新建对话下,按对话过滤会让 AI 在别的对话里 list 不到(#478)
    override fun list(threadId: String): List<TriggerDefinition> = plans()

    private fun owned(threadId: String, id: String): Boolean = list(threadId).any { it.id == id }

    override fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean {
        if (!owned(threadId, id)) return false
        return changeEnabled(id, enabled)
    }

    override fun remove(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        delete(id)
        return true
    }

    override fun reschedule(threadId: String, id: String, atMs: Long): Boolean {
        if (!owned(threadId, id)) return false
        changeTime(id, atMs)
        return true
    }

    override fun runNow(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        executeNow(id)
        return true
    }

    override fun edit(threadId: String, id: String, change: (TriggerDefinition) -> TriggerDefinition): EditedSchedule? {
        // 编辑要用计划自己所属的对话(全局后不再拿当前对话的 threadId 去匹配)
        val own = plans().firstOrNull { it.id == id }?.threadId ?: return null
        return editPlan(own, id, change)
    }
}
