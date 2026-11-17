package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.TriggerDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScheduleAdminTest {
    private val first = TriggerDefinition("plan-a", "thread-a", "天气提醒", 1000)
    private val second = TriggerDefinition("plan-b", "thread-b", "工作提醒", 2000)
    private val plans = mutableListOf(first, second)
    private val calls = mutableListOf<String>()
    private val refused = mutableSetOf<String>()
    private val admin = LocalScheduleAdmin(
        plans = { plans.toList() },
        changeEnabled = { id, enabled -> calls += "enabled:$id:$enabled"; id !in refused },
        delete = { id -> calls += "delete:$id" },
        changeTime = { id, atMs -> calls += "time:$id:$atMs" },
        executeNow = { id -> calls += "run:$id" },
        editPlan = { thread, id, change -> calls += "edit:$thread:$id";
            com.wangzan101.mika.core.trigger.EditedSchedule(change(plans.single { it.id == id }), com.wangzan101.mika.core.trigger.Scheduled(true)) },
    )

    @Test fun listsAllPlansRegardlessOfConversation() {
        assertEquals(listOf(first, second), admin.list("thread-a"))
        assertEquals(listOf(first, second), admin.list("thread-b"))
        assertEquals(listOf(first, second), admin.list("empty-thread"))
        assertTrue(calls.isEmpty())
    }

    @Test fun ownPlanOperationsReachTheRuntimeWithTheirArguments() {
        assertTrue(admin.setEnabled("thread-a", first.id, false))
        assertTrue(admin.setEnabled("thread-a", first.id, true))
        assertTrue(admin.reschedule("thread-a", first.id, 3000))
        assertTrue(admin.runNow("thread-a", first.id))
        assertTrue(admin.remove("thread-a", first.id))
        assertEquals(listOf(
            "enabled:plan-a:false", "enabled:plan-a:true", "time:plan-a:3000", "run:plan-a", "delete:plan-a",
        ), calls)
    }

    @Test fun runtimeRefusalIsPassedBack() {
        refused += first.id
        assertFalse(admin.setEnabled("thread-a", first.id, true))
        assertEquals(listOf("enabled:plan-a:true"), calls)
    }

    @Test fun foreignPlansAreManagedByAnyConversation() {
        // 计划是设备全局的,别的对话建的计划也能从任意对话管理(#478)
        assertTrue(admin.setEnabled("thread-a", second.id, false))
        assertTrue(admin.remove("thread-a", second.id))
        assertEquals(listOf("enabled:plan-b:false", "delete:plan-b"), calls)
    }

    @Test fun missingPlansNeverReachTheRuntime() {
        for (id in listOf("missing-plan")) {
            assertFalse(admin.setEnabled("thread-a", id, false))
            assertFalse(admin.setEnabled("thread-a", id, true))
            assertFalse(admin.remove("thread-a", id))
            assertFalse(admin.reschedule("thread-a", id, 3000))
            assertFalse(admin.runNow("thread-a", id))
        }
        assertTrue(calls.isEmpty())
        assertEquals(listOf(first, second), plans)
    }

    @Test fun editUsesThePlansOwnConversation() {
        // 编辑别的对话建的计划时,用计划自己所属的对话去匹配,而不是当前对话
        assertEquals(null, admin.edit("thread-a", "missing-plan") { it.copy(goal = "changed") })
        assertEquals(true, admin.edit("thread-a", second.id) { it.copy(goal = "changed") }?.scheduled?.onTime)
        assertEquals(listOf("edit:thread-b:plan-b"), calls)
    }
}
