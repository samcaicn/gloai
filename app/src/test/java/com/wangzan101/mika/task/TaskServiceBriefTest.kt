package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 前台派活时附的补充说明随开始任务的请求交给服务;不附就不带这一项。 */
class TaskServiceBriefTest {
    @Test fun startCarriesBrief() {
        val extras = TaskService.startExtras("订明早的票", conversationId = "c1", messageId = null, brief = "x").toMap()
        assertEquals("x", extras[TaskService.EXTRA_BRIEF])
        assertEquals("订明早的票", extras[TaskService.EXTRA_GOAL])
        assertEquals("c1", extras[TaskService.EXTRA_CONVERSATION])
        assertFalse(TaskService.EXTRA_MESSAGE_ID in extras)
        val plain = TaskService.startExtras("订票", conversationId = null, messageId = null, brief = null).toMap()
        assertFalse(TaskService.EXTRA_BRIEF in plain)
        assertFalse(TaskService.EXTRA_CONVERSATION in plain)
    }
}
