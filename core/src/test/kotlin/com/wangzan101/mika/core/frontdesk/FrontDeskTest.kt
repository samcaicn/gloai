package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrontDeskTest {
    /** 只给状态和排队位置的假端口;派单、补充这些前台在这里用不到。 */
    private class FakePort : DeskTaskPort {
        val states = HashMap<String, MutableStateFlow<TaskState>>()
        val positions = HashMap<String, Int>()

        fun add(taskId: String, state: TaskState = TaskState.Idle) = MutableStateFlow(state).also { states[taskId] = it }

        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome = error("unused")
        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = positions[taskId]
        override fun steer(taskId: String, words: String) = false
        override fun answer(taskId: String, words: String) = false
        override fun cancel(taskId: String) = false
    }

    /** 记下收到的汇报;[online] 为 false 时表示不在线;[gate] 非空时每次投递都挂起等它放行(模拟正在说话)。 */
    private class FakeSurface(var online: Boolean = true) : DeskSurface {
        val delivered = mutableListOf<DeskReport>()
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var hold = false

        override suspend fun deliver(report: DeskReport): Boolean {
            if (hold) CompletableDeferred<Unit>().also { gates.addLast(it) }.await()
            if (online) delivered += report
            return online
        }
    }

    private class FakeSink : DeskSink {
        val recorded = mutableListOf<Pair<String, DeskReport>>()
        override suspend fun record(conversationId: String, report: DeskReport) {
            recorded += conversationId to report
        }
    }

    private val port = FakePort()
    private val ledger = DeskLedger()
    private val sink = FakeSink()
    private var now = 0L

    private fun TestScope.desk() = FrontDesk("c1", backgroundScope, port, ledger, ReportPolicy(clock = { now }), sink, clock = { now })

    private fun running(narration: String? = null) = TaskState.Running(step = 1, note = "", narration = narration)

    @Test
    fun trackSubscribesAndEmitsReportsToSurface() = runTest {
        val state = port.add("t1")
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "订会议室")
        runCurrent()

        state.value = TaskState.WaitingInput("订几点的?", listOf("十点", "十一点"))
        runCurrent()

        assertEquals(listOf(DeskReport(DeskEvent.NeedsAnswer("t1", "订几点的?", listOf("十点", "十一点")), "订会议室")), surface.delivered)
        assertTrue(sink.recorded.isEmpty())
        assertEquals(DeskTaskPhase.NeedsYou, desk.active.value.single().state)
    }

    @Test
    fun reportsFallBackToSinkWhenNoSurface() = runTest {
        val state = port.add("t1")
        val desk = desk()
        desk.track("t1", "订会议室")
        runCurrent()
        state.value = TaskState.WaitingInput("订几点的?")
        runCurrent()

        val offline = FakeSurface(online = false)
        desk.attach(offline)
        state.value = TaskState.Failed("会议室都满了", FailKind.GaveUp)
        runCurrent()

        assertEquals(
            listOf(
                "c1" to DeskReport(DeskEvent.NeedsAnswer("t1", "订几点的?", null), "订会议室"),
                "c1" to DeskReport(DeskEvent.Failed("t1", "会议室都满了"), "订会议室"),
            ),
            sink.recorded,
        )
        assertTrue(offline.delivered.isEmpty())
    }

    @Test
    fun reportWaitsForCurrentReplyAndIsDeliveredOnce() = runTest {
        val state = port.add("t1")
        val desk = desk()
        val first = FakeSurface().apply { hold = true }
        desk.attach(first)
        desk.track("t1", "订会议室")
        runCurrent()

        state.value = running()
        runCurrent()
        state.value = TaskState.WaitingInput("订几点的?")
        runCurrent()
        state.value = TaskState.WaitingConfirmation(confirmation())
        runCurrent()
        // 第一条正在说,后两条排着,一条都还没送达。
        assertEquals(1, first.gates.size)
        assertTrue(first.delivered.isEmpty())

        first.gates.removeFirst().complete(Unit)
        runCurrent()
        first.gates.removeFirst().complete(Unit)
        runCurrent()
        assertEquals(
            listOf(DeskEvent.Started("t1"), DeskEvent.NeedsAnswer("t1", "订几点的?", null)),
            first.delivered.map { it.event },
        )

        // 第三条说到一半被打断:换上来的入口重新收到它,原入口和 sink 都不会再收到。
        val second = FakeSurface()
        desk.attach(second)
        first.gates.removeFirst().completeExceptionally(CancellationException("用户开口了"))
        runCurrent()
        assertEquals(listOf(DeskEvent.NeedsAction("t1", ActionKind.Confirm)), second.delivered.map { it.event })
        assertEquals(2, first.delivered.size)
        assertTrue(sink.recorded.isEmpty())

        // 被打断时没有新入口接上,就交给 sink,同样只一次。
        second.hold = true
        state.value = TaskState.Succeeded("订好了十点的会议室")
        runCurrent()
        desk.attach(FakeSurface(online = false)).close()
        second.gates.removeFirst().completeExceptionally(CancellationException("用户开口了"))
        runCurrent()
        assertEquals(listOf(DeskEvent.Succeeded("t1", "订好了十点的会议室")), sink.recorded.map { it.second.event })
        assertEquals(1, second.delivered.size)
    }

    // 看得到此刻接着谁:换上的入口顶掉前一个;前一个再关自己的句柄不影响新的,新的一关就空出来。
    @Test
    fun attachedShowsWhoHoldsTheDesk() = runTest {
        val desk = desk()
        val first = FakeSurface()
        val second = FakeSurface()
        assertEquals(null, desk.attached.value)
        val a = desk.attach(first)
        assertTrue(desk.attached.value === first)
        val b = desk.attach(second)
        a.close()
        assertTrue(desk.attached.value === second)
        b.close()
        assertEquals(null, desk.attached.value)
    }

    @Test
    fun terminalStopsSubscriptionAndActiveDropsAfterDone() = runTest {
        val state = port.add("t1", running("打开日历"))
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "订会议室")
        runCurrent()
        assertEquals(DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, "打开日历", null, listOf("打开日历"), startedAt = 0L, steps = listOf(DeskStep("打开日历", 0L))), desk.active.value.single())

        state.value = TaskState.Succeeded("订好了")
        runCurrent()
        state.value = TaskState.Failed("不该再看到", FailKind.GaveUp)
        runCurrent()

        assertEquals(listOf(DeskEvent.Started("t1"), DeskEvent.Succeeded("t1", "订好了")), surface.delivered.map { it.event })
        assertEquals(DeskTaskPhase.Done, desk.active.value.single().state)
    }

    @Test
    fun activeKeepsLastThreeDistinctCleanedProgressLines() = runTest {
        val state = port.add("t1", running("打开「日历」 [10,20]"))
        val desk = desk()
        desk.track("t1", "订会议室")
        runCurrent()
        assertEquals(listOf("打开"), desk.active.value.single().recent)

        // 只差坐标、引号细节的不算新的一行;重复出现的挪到最后,不重复记。
        for (narration in listOf("打开「日程」 [1,2]", "找日程", "选时间", "找日程", "填标题")) {
            state.value = running(narration)
            runCurrent()
        }
        val view = desk.active.value.single()
        assertEquals(listOf("选时间", "找日程", "填标题"), view.recent)
        assertEquals("填标题", view.line)

        state.value = TaskState.WaitingInput("要订哪一间?", null)
        runCurrent()
        assertEquals(listOf("找日程", "填标题", "要订哪一间?"), desk.active.value.single().recent)
    }

    @Test
    fun activeKeepsEveryStepWithItsTimeAndWhatItWaitsOn() = runTest {
        ledger.add(DeskTaskEntry("t1", "订会议室", "帮我订明天十点的会议室", 0L))
        now = 1_000L
        val state = port.add("t1", running("打开「日历」 [10,20]"))
        val desk = desk()
        desk.track("t1", "订会议室")
        runCurrent()
        for ((at, narration) in listOf(3_000L to "打开「日程」 [1,2]", 5_000L to "找日程", 9_000L to "选时间", 12_000L to "找日程")) {
            now = at
            state.value = running(narration)
            runCurrent()
        }
        now = 15_000L
        state.value = TaskState.WaitingInput("要订「三楼」还是「五楼」那间?", listOf("三楼", "五楼"))
        runCurrent()

        val view = desk.active.value.single()
        assertEquals("帮我订明天十点的会议室", view.goal)
        assertEquals(1_000L, view.startedAt)
        // 只差细节的不算新的一步;隔开了又说回来的算新的一步
        assertEquals(
            listOf(DeskStep("打开", 1_000L), DeskStep("找日程", 5_000L), DeskStep("选时间", 9_000L), DeskStep("找日程", 12_000L),
                DeskStep("要订还是那间?", 15_000L)),
            view.steps,
        )
        assertEquals(DeskNeed("要订「三楼」还是「五楼」那间?", listOf("三楼", "五楼")), view.need)
        assertEquals(15_000L, view.updatedAt)

        now = 20_000L
        state.value = TaskState.WaitingTakeover("pay", "要你本人确认付款")
        runCurrent()
        assertEquals(DeskNeed("要你本人确认付款", action = ActionKind.Takeover), desk.active.value.single().need)

        state.value = TaskState.Succeeded("x".repeat(DESK_RESULT_MAX_CHARS + 5))
        runCurrent()
        val done = desk.active.value.single()
        assertEquals(null, done.need)
        assertEquals(6, done.steps.size)
    }

    @Test
    fun queuedTaskShowsPositionAndReportsStartWhenItsTurnComes() = runTest {
        val state = port.add("t2", TaskState.Held(GateReason.queued("前面还有 1 个")))
        port.positions["t2"] = 1
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t2", "查快递")
        runCurrent()
        assertEquals(DeskTaskView("t2", "查快递", DeskTaskPhase.Queued, null, 1), desk.active.value.single().copy(line = null))

        port.positions.remove("t2")
        state.value = running()
        runCurrent()
        assertEquals(listOf(DeskEvent.Started("t2")), surface.delivered.map { it.event })
        assertEquals(DeskTaskView("t2", "查快递", DeskTaskPhase.Running, null, null, startedAt = 0L), desk.active.value.single())
    }

    /**
     * 刚派出、任务服务还没接手(状态还是 Idle)、也不在开始队列里的一件:是正在开始,不是排队。
     * 记成排队时卡片会先闪一下「排队中」,前台看着任务板也会跟用户说「还在排队」。
     */
    @Test
    fun startingTaskIsNotShownAsQueued() = runTest {
        val state = port.add("t1")
        val desk = desk()
        desk.track("t1", "看设置首页")
        runCurrent()
        assertEquals(DeskTaskView("t1", "看设置首页", DeskTaskPhase.Running, null, null, startedAt = 0L), desk.active.value.single())

        state.value = running()
        runCurrent()
        assertEquals(DeskTaskPhase.Running, desk.active.value.single().state)
    }

    /** 跟进里的任务(派出或接回过)[FrontDesk.tracks] 为 true;端口不认识、从没交给它的为 false。 */
    @Test
    fun tracksOnlyTasksItFollows() = runTest {
        port.add("t1")
        val desk = desk()
        assertFalse(desk.tracks("t1"))
        desk.track("t1", "看设置首页")
        desk.track("nope", "不认识")
        assertTrue(desk.tracks("t1"))
        assertFalse(desk.tracks("nope"))
    }

    /** 刚派出就预判要排队的(端口给了排第几):Idle 时照样是排队,带位置。 */
    @Test
    fun idleTaskWithAQueuePositionIsQueued() = runTest {
        port.add("t2")
        port.positions["t2"] = 2
        val desk = desk()
        desk.track("t2", "查快递")
        runCurrent()
        assertEquals(DeskTaskView("t2", "查快递", DeskTaskPhase.Queued, null, 2), desk.active.value.single())
    }

    @Test
    fun restoreResubscribesRunningTasks() = runTest {
        val live = port.add("t1", running("打开日历"))
        port.add("t2", TaskState.Succeeded("查到了"))
        port.add("t3", TaskState.Failed("没网", FailKind.GaveUp))
        val entries = listOf(
            DeskTaskEntry("t1", "订会议室", "帮我订会议室", 1),
            DeskTaskEntry("t2", "查快递", "查一下快递", 2),
            DeskTaskEntry("t3", "看天气", "明天天气", 3),
        )
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.restore(entries)
        runCurrent()

        assertEquals(
            listOf(DeskTaskPhase.Running, DeskTaskPhase.Done, DeskTaskPhase.Failed),
            desk.active.value.map { it.state },
        )
        assertEquals(listOf("t1", "t2", "t3"), desk.active.value.map { it.taskId })
        assertTrue(surface.delivered.isEmpty())
        assertTrue(sink.recorded.isEmpty())
        assertEquals(entries, ledger.all())

        live.value = TaskState.WaitingInput("订几点的?")
        runCurrent()
        assertEquals(listOf(DeskReport(DeskEvent.NeedsAnswer("t1", "订几点的?", null), "订会议室")), surface.delivered)
    }

    @Test
    fun trackingATaskThatAlreadyEndedStillReportsItsResult() = runTest {
        port.add("t1", TaskState.Failed("没网", FailKind.GaveUp))
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "看天气")
        runCurrent()

        assertEquals(listOf(DeskReport(DeskEvent.Failed("t1", "没网"), "看天气")), surface.delivered)
        assertEquals(DeskTaskPhase.Failed, desk.active.value.single().state)
    }

    @Test
    fun trackingTheSameTaskTwiceSubscribesOnce() = runTest {
        val state = port.add("t1")
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "订会议室")
        desk.track("t1", "订会议室")
        runCurrent()
        state.value = TaskState.WaitingInput("订几点的?")
        runCurrent()

        assertEquals(1, surface.delivered.size)
        assertEquals(1, desk.active.value.size)
        assertTrue(ledger.all().isEmpty())
    }

    private fun confirmation() = ConfirmationRequest(IrrevAction.order, target = "会议室", content = "十点", amount = null, app = "calendar")
}
