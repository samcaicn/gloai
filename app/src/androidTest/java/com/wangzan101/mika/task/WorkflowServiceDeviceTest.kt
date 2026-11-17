package com.wangzan101.mika.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.verify.Verifier
import com.wangzan101.mika.workflow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real TaskService and phone tool; only model responses are deterministic. No external message is sent. */
@RunWith(AndroidJUnit4::class)
class WorkflowServiceDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @Test fun serviceOpensSettingsThenPassesResultToNextStep() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MikaApp
        val shell = DeviceSupport.shell()
        DeviceSupport.service()
        val launchCount = AtomicInteger()
        val judgeCount = AtomicInteger()
        var sawPrevious = false
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                fun reply(text: String) = ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)
                if (system == Prompts.role("outcome_judge")) { judgeCount.incrementAndGet(); return reply("""{"outcome":"done","reason":"verified fixture"}""") }
                if (system == Prompts.role("conversation_title")) return reply("多步骤真机测试")
                if (system == Prompts.role("intent_gate")) return reply("ACT")
                if (system == Verifier.PROMPT) return reply(ScriptedModel.PASS)
                if (tools.isEmpty()) return reply("[]")
                val text = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                if (text.contains("WORKFLOW_STEP_TWO")) {
                    sawPrevious = text.contains("SETTINGS_OPENED_724")
                    return reply("已根据上一步结果整理：系统设置已打开，未修改任何设置。")
                }
                if (launchCount.getAndIncrement() == 0) return ModelReply(listOf(Part.ToolCall("open-settings", "phone_launch_app", """{"package":"com.android.settings"}""")), StopReason.ToolUse, null)
                assertTrue(testTasks.agentDisplayId.value > 0)
                assertTrue(shell.topTaskId(testTasks.agentDisplayId.value) >= 0)
                return reply("SETTINGS_OPENED_724：已打开系统设置，没有修改设置。")
            }
        }
        val store = app.workflows.store
        store.load()
        assertFalse(app.taskState.busy.value)
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> model }
        testTasks.visionModelFactory = { _, _ -> null }
        val template = store.saveTemplate(UUID.randomUUID().toString(), 0, "多步骤真机验证", listOf(
            WorkflowStep(title = "打开设置", instruction = "WORKFLOW_STEP_ONE 打开系统设置，不修改任何设置。"),
            WorkflowStep(title = "整理结果", instruction = "WORKFLOW_STEP_TWO 根据上一步结果写一句总结，不操作手机。")))
        var id: String? = null
        try {
            id = app.workflows.start(template, "只测试打开和总结")
            val runId = id
            val end = pollUntil(90_000) { store.data.value.runs.find { it.id == runId }?.takeIf { it.phase in listOf(WorkflowPhase.Completed, WorkflowPhase.Review) } }
            assertEquals(end.toString(), WorkflowPhase.Completed, end?.phase)
            assertTrue(sawPrevious)
            assertEquals(2, judgeCount.get())
            assertEquals(2, end!!.completed)
            assertTrue(end.result.contains("系统设置已打开"))
            pollUntil(10_000) { (!app.taskState.busy.value).takeIf { it } }
        } finally {
            TaskService.cancel(context)
            pollUntil(10_000) { (!app.taskState.busy.value).takeIf { it } }
            testTasks.modelFactory = TaskEnvironment.defaultModelFactory
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            id?.let { runCatching { store.deleteRun(it) } }
            store.deleteTemplate(template.id, template.revision)
        }
    }
}
