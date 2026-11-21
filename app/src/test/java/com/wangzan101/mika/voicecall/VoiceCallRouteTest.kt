package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallRouteTest {

    @get:Rule val texts = ChineseTexts()

    private class Harness {
        val launched = mutableListOf<String>()
        val results = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        val flows = mutableMapOf<String, MutableStateFlow<TaskState>>()
        var nextConversationId = "conv-1"
        var runStateLookups = 0
        var flowReady = true
        /** 取消任务时顺手把它的状态流置成已取消(真机上任务服务会这么做)。 */
        var flipFlowOnCancel = true

        val launcher = VoiceCallTaskLauncher { goal ->
            launched += goal
            nextConversationId
        }
        val runState: (String) -> StateFlow<TaskState>? = { id ->
            runStateLookups++
            if (flowReady) flows[id] else null
        }
        val route = VoiceCallRoute(
            launcher = launcher,
            runState = runState,
            sendResult = { callId, text -> results += callId to text },
            cancelTask = { id ->
                cancelled += id
                if (flipFlowOnCancel) flows[id]?.let { it.value = TaskState.Cancelled }
            },
        )

        fun running(step: Int, note: String) = MutableStateFlow<TaskState>(TaskState.Running(step, note))
    }

    @Test
    fun `任务成功——结果文本回给实时模型`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow(TaskState.Succeeded("明天晴天，最高 25 度"))
        h.route.execute("call-1", "明天天气怎么样")
        assertEquals(listOf("明天天气怎么样"), h.launched)
        assertEquals(listOf("call-1" to "明天晴天，最高 25 度"), h.results)
        assertEquals(listOf("已完成"), h.route.lines.value)
    }

    @Test
    fun `任务失败——失败原因回给实时模型`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow(TaskState.Failed("没配模型", FailKind.Environment))
        h.route.execute("call-1", "查一下快递")
        assertEquals(listOf("call-1" to "没配模型"), h.results)
        assertEquals(listOf("没办成"), h.route.lines.value)
    }

    @Test
    fun `先到先得——两个 callId 竞争只有一个执行`() = runTest {
        val h = Harness()
        h.flowReady = false // 任务还没登记,第一个请求在等 runState 出现
        val first = async { h.route.execute("call-1", "查天气") }
        runCurrent()
        assertEquals(listOf("查天气"), h.launched)

        val second = async { h.route.execute("call-2", "定闹钟") }
        runCurrent()
        assertEquals("后来的请求不重复发起任务", listOf("查天气"), h.launched)
        assertEquals(
            listOf("call-2" to "Another request is still running. Tell the user the previous request is still being handled; they can ask again when it finishes."),
            h.results,
        )

        h.flowReady = true
        h.flows["conv-1"] = MutableStateFlow(TaskState.Succeeded("晴天"))
        advanceUntilIdle()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(
            listOf(
                "call-2" to "Another request is still running. Tell the user the previous request is still being handled; they can ask again when it finishes.",
                "call-1" to "晴天",
            ),
            h.results,
        )
    }

    @Test
    fun `任务 Held 需要用户——状态行提示去任务页看并交还模型`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow<TaskState>(TaskState.Running(1, "正在打开设置"))
        val run = async { h.route.execute("call-1", "帮我开深色模式") }
        runCurrent()
        h.flows.getValue("conv-1").value = TaskState.Held(GateReason(code = "locked", why = "手机锁着", needsYou = true))
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        assertEquals(
            listOf("call-1" to "The task started but needs the user to act on the phone (approve, answer, or take over). Tell the user to open the task page to continue."),
            h.results,
        )
        assertEquals(listOf("正在打开设置", "需要你在任务页处理，去任务页看看"), h.route.lines.value)

        // 交还后路由空出来,下一个请求照跑
        h.nextConversationId = "conv-2"
        h.flows["conv-2"] = MutableStateFlow(TaskState.Succeeded("开好了"))
        h.route.execute("call-2", "再试一次")
        assertEquals("call-2" to "开好了", h.results.last())
    }

    @Test
    fun `任务 WaitingConfirmation——状态行提示去任务页看并交还模型`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow<TaskState>(
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "晚上一起吃饭吗", null, "com.example")),
        )
        h.route.execute("call-1", "给张三发消息")
        assertEquals(
            listOf("call-1" to "The task started but needs the user to act on the phone (approve, answer, or take over). Tell the user to open the task page to continue."),
            h.results,
        )
        assertEquals(listOf("需要你在任务页处理，去任务页看看"), h.route.lines.value)
    }

    @Test
    fun `任务排队等待——不算要用户处理,继续等到结果`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow<TaskState>(TaskState.Held(GateReason.queued("前面还有 1 个任务")))
        val run = async { h.route.execute("call-1", "查天气") }
        runCurrent()
        assertTrue(h.results.isEmpty())
        h.flows.getValue("conv-1").value = TaskState.Succeeded("晴天")
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        assertEquals(listOf("call-1" to "晴天"), h.results)
        assertEquals(listOf("任务暂时停住了", "已完成"), h.route.lines.value)
    }

    @Test
    fun `打断——取消已启动未完成的任务`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = h.running(1, "正在打开设置")
        val run = async { h.route.execute("call-1", "帮我开深色模式") }
        runCurrent()
        assertEquals(listOf("正在打开设置"), h.route.lines.value)

        h.route.onBargeIn()
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        assertEquals(listOf("conv-1"), h.cancelled)
        assertEquals(listOf("call-1" to "The task was cancelled because the user interrupted."), h.results)
        assertEquals(listOf("正在打开设置", "已取消"), h.route.lines.value)

        // 路由空出来,下一个请求照跑
        h.nextConversationId = "conv-2"
        h.flows["conv-2"] = MutableStateFlow(TaskState.Succeeded("好的"))
        h.route.execute("call-2", "换个话题")
        assertEquals("call-2" to "好的", h.results.last())
    }

    @Test
    fun `打断发生在任务登记完成之前——任务一登记就被取消且只取消一次`() = runTest {
        val h = Harness()
        h.flowReady = false
        h.flipFlowOnCancel = false // 登记时流还不存在,取消落不到流上
        val run = async { h.route.execute("call-1", "查天气") }
        runCurrent()
        assertEquals(listOf("查天气"), h.launched)

        h.route.onBargeIn()
        assertEquals(listOf("conv-1"), h.cancelled)

        h.flowReady = true
        h.flows["conv-1"] = MutableStateFlow(TaskState.Cancelled) // 服务侧已把它取消
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        assertEquals("同一个任务不重复取消", listOf("conv-1"), h.cancelled)
        assertEquals(listOf("call-1" to "The task was cancelled because the user interrupted."), h.results)
    }

    @Test
    fun `没有任务在跑时打断——不取消任何东西`() = runTest {
        val h = Harness()
        h.route.onBargeIn()
        assertTrue(h.cancelled.isEmpty())
    }

    @Test
    fun `状态行缓冲上限 12 行——最旧的滚掉`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = h.running(0, "")
        val run = async { h.route.execute("call-1", "干活") }
        runCurrent()
        val flow = h.flows.getValue("conv-1")
        for (i in 1..15) {
            flow.value = TaskState.Running(i, "步骤 $i 说明")
            runCurrent()
        }
        flow.value = TaskState.Succeeded("好了")
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        val lines = h.route.lines.value
        assertEquals(12, lines.size)
        assertEquals("已完成", lines.last())
        assertTrue(lines.contains("步骤 15 说明"))
        assertFalse(lines.contains("步骤 4 说明"))
    }

    @Test
    fun `状态行剥掉坐标和日程标题等细节`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = h.running(1, "点击 [123,456][789,012] 打开日程「年度体检复查」并提醒")
        val run = async { h.route.execute("call-1", "看看日程") }
        runCurrent()
        assertEquals(listOf("点击 打开日程并提醒"), h.route.lines.value)
        h.flows.getValue("conv-1").value = TaskState.Succeeded("明天下午三点")
        advanceUntilIdle()
        assertTrue(run.isCompleted)
    }

    @Test
    fun `状态行剥掉方头括号包裹的非数字细节`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = h.running(1, "点击【通讯录】找到【张三】并呼叫")
        val run = async { h.route.execute("call-1", "打电话") }
        runCurrent()
        // 剥掉后按既有语义直接收拢(中文词间本来没有空格,不补)
        assertEquals(listOf("点击找到并呼叫"), h.route.lines.value)
        h.flows.getValue("conv-1").value = TaskState.Succeeded("好了")
        advanceUntilIdle()
        assertTrue(run.isCompleted)
    }

    @Test
    fun `busy 流跟着路由有没有活走`() = runTest {
        val h = Harness()
        assertFalse(h.route.busy.value)
        h.flowReady = false
        val run = async { h.route.execute("call-1", "干活") }
        runCurrent()
        assertTrue(h.route.busy.value)
        h.flowReady = true
        h.flows["conv-1"] = MutableStateFlow(TaskState.Succeeded("好"))
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        assertFalse(h.route.busy.value)
    }

    @Test
    fun `状态行最长 40 字`() = runTest {
        val h = Harness()
        val long = "正在处理一件说起来特别特别长的事情".repeat(4) // 60 字
        h.flows["conv-1"] = h.running(1, long)
        val run = async { h.route.execute("call-1", "干活") }
        runCurrent()
        val line = h.route.lines.value.single()
        assertEquals(40, line.length)
        h.flows.getValue("conv-1").value = TaskState.Succeeded("好了")
        advanceUntilIdle()
        assertTrue(run.isCompleted)
    }

    @Test
    fun `连续重复的状态行不重复进缓冲`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = h.running(1, "同一句进度")
        val run = async { h.route.execute("call-1", "干活") }
        runCurrent()
        val flow = h.flows.getValue("conv-1")
        flow.value = TaskState.Running(2, "同一句进度")
        runCurrent()
        assertEquals(listOf("同一句进度"), h.route.lines.value)
        flow.value = TaskState.Succeeded("好了")
        advanceUntilIdle()
        assertTrue(run.isCompleted)
    }

    @Test
    fun `runState 迟到登记——轮询等到为止`() = runTest {
        val h = Harness()
        h.flowReady = false
        val run = async { h.route.execute("call-1", "查天气") }
        runCurrent()
        repeat(3) {
            advanceTimeBy(100)
            runCurrent()
        }
        h.flowReady = true
        h.flows["conv-1"] = MutableStateFlow(TaskState.Succeeded("晴天"))
        advanceUntilIdle()
        assertTrue(run.isCompleted)
        assertEquals(listOf("call-1" to "晴天"), h.results)
        assertTrue(h.runStateLookups >= 3)
    }

    @Test
    fun `runState 一直不出现——启动失败回给模型`() = runTest {
        val h = Harness()
        h.flowReady = false
        h.route.execute("call-1", "查天气")
        assertEquals(
            listOf("call-1" to "The task could not be started. Apologize briefly and suggest the user start it from the task page instead."),
            h.results,
        )
    }

    @Test
    fun `空答案用兜底文本`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow(TaskState.Succeeded(""))
        h.route.execute("call-1", "干活")
        assertEquals(listOf("call-1" to "The task finished."), h.results)
    }

    @Test
    fun `结果文本超长截断`() = runTest {
        val h = Harness()
        h.flows["conv-1"] = MutableStateFlow(TaskState.Succeeded("长".repeat(3000)))
        h.route.execute("call-1", "干活")
        assertEquals(1800, h.results.single().second.length)
    }

    @Test
    fun `toVoiceLine 逐状态映射`() {
        assertNull(TaskState.Idle.toVoiceLine())
        assertEquals("需要你在任务页处理，去任务页看看", TaskState.WaitingTakeover("pay", "请完成支付").toVoiceLine())
        assertEquals("需要你在任务页处理，去任务页看看", TaskState.WaitingInput("选哪个?").toVoiceLine())
        assertEquals(
            "需要你在任务页处理，去任务页看看",
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "内容", null, "com.example")).toVoiceLine(),
        )
        assertEquals("需要你在任务页处理，去任务页看看", TaskState.Held(GateReason(code = "x", needsYou = true)).toVoiceLine())
        assertEquals("任务暂时停住了", TaskState.Held(GateReason(code = "x")).toVoiceLine())
        assertEquals("正在处理", TaskState.Running(1, "").toVoiceLine())
        assertEquals("已完成", TaskState.Succeeded("好").toVoiceLine())
        assertEquals("没办成", TaskState.Failed("原因", FailKind.Model).toVoiceLine())
        assertEquals("已取消", TaskState.Cancelled.toVoiceLine())
    }
}
