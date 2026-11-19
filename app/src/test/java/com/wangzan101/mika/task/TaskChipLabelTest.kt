package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskStage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TaskChipLabelTest {
    @get:Rule val texts = ChineseTexts()
    private val running = TaskState.Running(3, "private note", reason = "private reason")
    private val stage = TaskStage(1, 3, "查票", "铁路12306 · 查票")
    private fun label(state: TaskState) = taskChip(RunOrigin.Ui, state, stage)

    @Test fun onlyActiveUserStartedTasksRequestAChip() {
        for (origin in RunOrigin.entries - RunOrigin.Ui) assertNull(taskChip(origin, running))
        for (state in listOf(TaskState.Idle, TaskState.Held(GateReason.queued("next")), TaskState.Cancelled, TaskState.Succeeded("done"))) {
            assertNull(label(state))
        }
    }

    @Test fun currentStageSurvivesToolsThinkingAndUnrelatedQuantities() {
        for (tool in listOf(null, "phone_observe", "phone_tap", "web_navigate")) {
            assertEquals("1/3 查票", label(running.copy(tool = tool, progress = RunProgress.Batch(20))))
        }
        assertEquals("1/3 查票", label(running.copy(progress = RunProgress.Task("private", 5, 8, null))))
        assertEquals("1/3 · 铁路12306 · 查票", chipDetail(running, stage))
        assertEquals("正在准备", taskChip(RunOrigin.Ui, running.copy(step = 0)))
        assertEquals("正在执行", taskChip(RunOrigin.Ui, running))
        assertEquals("正在思考", chipDetail(running))
    }

    @Test fun personalDetailsNeverBecomeWaitingLabels() {
        val request = ConfirmationRequest(IrrevAction.order, "private recipient", "private content", null, "app")
        assertEquals("等你同意", label(TaskState.WaitingConfirmation(request)))
        assertEquals("等你回答", label(TaskState.WaitingInput("private question")))
        assertEquals("等你接手", label(TaskState.WaitingTakeover("payment", "private", amount = "999")))
        assertEquals("等待解锁", label(TaskState.Held(TaskGate.LOCKED)))
    }
}
