package com.wangzan101.mika.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.editedTrigger
import com.wangzan101.mika.core.trigger.toPlanDraft
import com.wangzan101.mika.task.LocalScheduleAdmin
import com.wangzan101.mika.task.TriggerRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import com.wangzan101.mika.settings.ui.models.edit

/** 模型管理宿主 → 本机归属校验 → 持久化和重排期；工具解析另由 core 契约测试覆盖。 */
@RunWith(AndroidJUnit4::class)
class ScheduleEditDeviceTest {
    @Test fun adminEditsTheSamePersistedPlanAndCannotEditAnotherConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val owner = "schedule357-$id"
        val original = TriggerDefinition(id, owner, "old", System.currentTimeMillis() + 86_400_000,
            calendarTime = "08:00", calendarZone = "Asia/Shanghai", enabled = false)
        try {
            TriggerRuntime.schedule(context, original)
            val admin = LocalScheduleAdmin(context)
            val result = admin.edit(owner, id) { current ->
                editedTrigger(current.toPlanDraft().copy(content = "new", clock = "08:00,20:00"),
                    current, id, owner, System.currentTimeMillis())
            }
            assertNotNull(result)
            val saved = TriggerRuntime.list(context).single { it.id == id }
            assertEquals(owner, saved.threadId)
            assertEquals("new", saved.goal)
            assertFalse(saved.enabled)
            assertEquals(listOf("08:00", "20:00"), saved.calendarClocks())
            val reopened = com.wangzan101.mika.task.FileTriggerStore(java.io.File(context.filesDir, "triggers-v3.json"))
            assertEquals(saved, reopened.get(id))
            val foreign = admin.edit("other", id) { error("must not edit a foreign plan") }
            assertNull(foreign)
            assertNull(TriggerRuntime.edit(context, owner, id) { it.copy(threadId = "other") })
            assertEquals(saved, TriggerRuntime.list(context).single { it.id == id })
        } finally {
            TriggerRuntime.remove(context, id)
        }
    }
}
