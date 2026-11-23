package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台给子任务补话走本机插话([com.wangzan101.mika.task.TaskRun.steer]),不走远程追加:
 * 远程追加要求任务带 MCP 外部编号,前台派出的子任务没有,走那条永远补不进去。
 * 真的 TaskRun 要跑着的任务服务才能建,JVM 测试建不了,这里按 TaskRun.steer 记下的去向判断收没收下。
 */
class DeskSteerTest {
    private class Steers(private val outcome: SteerState) {
        val notes = mutableListOf<SteerNote>()
        fun steer(text: String, id: String) { notes += SteerNote(id, text, outcome) }
    }

    @Test fun receivedByInboxIsAccepted() {
        val run = Steers(SteerState.Received)
        assertTrue(steerLocally("顺便看明天", run::steer) { run.notes })
        assertEquals("顺便看明天", run.notes.single().text)
    }

    @Test fun deferredToNextIsAccepted() {
        val run = Steers(SteerState.Next)
        assertTrue(steerLocally("顺便看明天", run::steer) { run.notes })
    }

    @Test fun cancelledBecauseStoppedIsRefused() {
        val run = Steers(SteerState.Cancelled)
        assertFalse(steerLocally("顺便看明天", run::steer) { run.notes })
    }

    @Test fun missingNoteIsRefused() {
        assertFalse(steerLocally("顺便看明天", { _, _ -> }) { emptyList() })
    }
}
