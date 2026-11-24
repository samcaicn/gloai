package com.wangzan101.mika.voicecall.ui

import androidx.compose.ui.test.assertCountEquals
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voicecall.EndReason
import com.wangzan101.mika.voicecall.VoiceCallAudio
import com.wangzan101.mika.voicecall.VoiceCallAudioRoute
import com.wangzan101.mika.voicecall.VoiceCallError
import com.wangzan101.mika.voicecall.VoiceCallFeed
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTranscript
import com.wangzan101.mika.voicecall.ECHO_WINDOW_CAPACITY_CHARS
import com.wangzan101.mika.voicecall.RingBuffer
import com.wangzan101.mika.voicecall.VoiceCallRequest
import com.wangzan101.mika.voicecall.VoiceDeskBridge
import com.wangzan101.mika.voicecall.typedDeskResult
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通话页冒烟:假音频 + 假实时会话注入(参照 VoiceHoldTest 的手写 fake 模式),不用真麦克风、真服务。
 * 链:连接 → 听(电波形) → 播音动画 → 打断 → 挂断 → 重试 → 上限;权限拒绝 → 去设置;缺凭据 → 去配置。
 */
@RunWith(AndroidJUnit4::class)
class VoiceCallPageTest {

    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    /** 假音频设备:全绿通道,采集块由测试喂,记下打断/起停次数。 */
    private class FakeAudio : VoiceCallAudio {
        override val micSilenced = MutableStateFlow(false)
        private val capture = Channel<ShortArray>(Channel.UNLIMITED)
        override val captureChunks: ReceiveChannel<ShortArray> get() = capture
        @Volatile override var onFocusLost: (() -> Unit)? = null
        @Volatile var interruptCount = 0
        @Volatile var focusAbandons = 0

        fun offerChunk(amplitude: Short = 500) {
            check(capture.trySend(ShortArray(320) { amplitude }).isSuccess)
        }

        override fun isTelephonyBusy() = false
        override fun requestCallAudio() = true
        override fun abandonCallAudio() { focusAbandons++ }
        override fun startCapture(inputSource: Int) = true
        override fun stopCapture() {}
        override fun startPlayback() = true
        override suspend fun writePlayback(pcm: ByteArray, byteCount: Int) {}
        override fun interruptPlayback() { interruptCount++ }
        override fun stopPlayback() {}
    }

    /** 假实时会话:事件由测试喂;记下工具结果。 */
    private class FakeRealtime : CallRealtimeSession {
        private val incoming = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
        override val events: Flow<VolcRealtimeEvent> = flow { for (event in incoming) emit(event) }
        val toolResults = mutableListOf<Pair<String, JsonObject>>()

        fun emit(event: VolcRealtimeEvent) { check(incoming.trySend(event).isSuccess) }
        override fun sendPcm16(bytes: ByteArray, byteCount: Int) = true
        override fun sendGreeting(text: String) = true
        override fun setMuted(muted: Boolean) = true
        override fun sendToolResult(callId: String, result: JsonObject): Boolean {
            toolResults += callId to result
            return true
        }
        override fun close() {}
    }

    /** 假后台:第 N 次派单开 tN,状态取 [flows] 里同名的流;记下取消。 */
    private class FakePort(private val flows: Map<String, MutableStateFlow<TaskState>>) : DeskTaskPort {
        private var count = 0
        val cancelled = mutableListOf<String>()
        @Synchronized
        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome =
            DispatchOutcome.Started("t${++count}")
        override fun state(taskId: String): StateFlow<TaskState>? = flows[taskId]
        override fun queuePosition(taskId: String): Int? = null
        override fun steer(taskId: String, words: String) = true
        override fun answer(taskId: String, words: String) = true
        override fun cancel(taskId: String): Boolean { cancelled += taskId; return true }
    }

    /**
     * 一通假通话:真 session 接假设备;[connect] 决定连接成败(可多次,配合重试)。
     * 前台、桥、对话流按生产装配同样接(后台换成 [FakePort])。
     * 会话一造好就开始连,这时测试里的变量还没赋上:[connect] 以这通电话为接收者,连自己的 [rt] 不经外面的变量。
     */
    private inner class Call(
        flows: Map<String, MutableStateFlow<TaskState>> = emptyMap(),
        connect: suspend Call.(Int) -> CallRealtimeSession,
        idleMs: Long? = 600_000L,
    ) {
        val audio = FakeAudio()
        val rt = FakeRealtime()
        val feed = VoiceCallFeed()
        val port = FakePort(flows)
        val transcript = VoiceCallTranscript(
            conversationId = { null },
            appendTurn = { _, _ -> },
        )
        private val ledger = DeskLedger()
        val desk = FrontDesk("conv-1", scope, port, ledger, ReportPolicy(System::currentTimeMillis), object : DeskSink {
            override suspend fun record(conversationId: String, report: DeskReport) {}
        })
        private val echoWindow = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        private val gate = VoiceCallRequest(echoWindow)
        private val executor = DeskToolExecutor(port, ledger, gate, System::currentTimeMillis, "conv-1") {
            desk.track(it.taskId, it.title)
        }
        private val bridge = VoiceDeskBridge(desk, executor, { rt }, { it.title }, echoWindow::append)
        val busy = desk.active.map { views -> views.any { it.state == DeskTaskPhase.Running || it.state == DeskTaskPhase.Queued } }
            .stateIn(scope, SharingStarted.Eagerly, false)
        lateinit var session: VoiceCallSession

        init {
            session = VoiceCallSession(
                scope = scope,
                audio = audio,
                foregroundResumed = MutableStateFlow(true),
                connect = { attempt -> connect(attempt) },
                greeting = "你好",
                echoWindow = echoWindow,
                requestGate = gate,
                onDeskCall = { callId, name, args -> scope.launch { bridge.onFunctionCall(callId, name, args) } },
                onTypedText = { text ->
                    scope.launch {
                        typedDeskResult(bridge.dispatchTyped(text)).let {
                            transcript.onAssistant(it)
                            feed.onAssistant(it, isFinal = true)
                        }
                        session.onLocalReply()
                    }
                },
                onUserTranscript = { text, isFinal ->
                    feed.onUser(text, isFinal)
                    if (isFinal) transcript.onUser(text)
                },
                onAssistantTranscript = { text, isFinal ->
                    feed.onAssistant(text, isFinal)
                    if (isFinal) transcript.onAssistant(text)
                },
                onAssistantDraft = feed::onAssistantDraft,
                idleMs = idleMs,
            )
            bridge.attach()
            scope.launch { desk.active.collect(feed::onTasks) }
        }
    }

    /**
     * 关掉动效再画:点阵球开着动效时每一帧都要下一帧,界面测试永远等不到空闲(ComposeNotIdle);
     * 关掉后球按形态和电平静止地画,形态的 testTag 不变。
     */
    @androidx.compose.runtime.Composable
    private fun StillTheme(content: @androidx.compose.runtime.Composable () -> Unit) = MikaTheme(reduceMotion = true, content = content)

    private fun show(
        call: Call,
        onCollapse: () -> Unit = {},
        screens: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        frames: (String) -> StateFlow<android.graphics.Bitmap?>? = { null },
        onOpenTask: (String) -> Unit = {},
        calls: List<com.wangzan101.mika.chat.ConversationCall> = emptyList(),
        onOpenRecord: (String) -> Unit = {},
    ) {
        val audioRoute = VoiceCallAudioRoute(InstrumentationRegistry.getInstrumentation().targetContext)
        val holder = VoiceCallHolder(call.session, call.busy, audioRoute, call.transcript, call.feed, screens, frames)
        compose.setContent {
            StillTheme {
                VoiceCallPage(holder, onCollapse, { onOpenTask(it.taskId) }, calls, onOpenRecord)
            }
        }
    }

    private fun awaitState(session: VoiceCallSession, timeoutMs: Long = 5_000, pred: (VoiceCallState) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!pred(session.state.value) && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue("state is ${session.state.value}", pred(session.state.value))
    }

    private fun awaitValue(timeoutMs: Long = 5_000, pred: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!pred() && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue(pred())
    }

    /** 等一个文本节点出现(对话流经前台→feed 异步更新,直接断言可能踩空)。 */
    private fun awaitText(text: String, timeoutMs: Long = 5_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 球的形态从 testTag 读(点阵球不再把英文枚举名读给 TalkBack)。 */
    private fun ballMode(): String? {
        for (mode in listOf("Web", "Globe", "Wave", "Orbits")) {
            if (compose.onAllNodesWithTag("voicecall-ball-" + mode).fetchSemanticsNodes().isNotEmpty()) return mode
        }
        return null
    }

    private fun awaitBall(mode: String) {
        compose.waitUntil(5_000) { ballMode() == mode }
        assertEquals(mode, ballMode())
    }

    /** 接通:喂一块安静的首块,等到 Active。 */
    private fun reachActive(call: Call) {
        call.audio.offerChunk(amplitude = 100)
        awaitState(call.session) { it is VoiceCallState.Active }
    }

    private fun pcm(sample: Short, bytes: Int): ByteArray {
        val out = ByteArray(bytes)
        for (i in 0 until bytes / 2) {
            out[2 * i] = (sample.toInt() and 0xFF).toByte()
            out[2 * i + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
        }
        return out
    }

    // ---------- 连接 → 听 → 播音动画 → 打断 → 挂断 → 重试 → 上限 ----------

    @Test fun connectingShowsStatusAndWebBallThenActiveShowsClockAndGlobe() {
        val gate = kotlinx.coroutines.CompletableDeferred<CallRealtimeSession>()
        val call = Call(connect = { gate.await() })
        show(call)
        // 连接中:状态词 + Web 形态球;计时还没开始
        compose.onNodeWithText("正在连接…").assertIsDisplayed()
        awaitBall("Web")

        // 服务接上了:检查麦克风 → 接通
        gate.complete(call.rt)
        reachActive(call)
        compose.onNodeWithText("在听你说").assertIsDisplayed()
        // 计时在挂断钮里,并进了按钮的语义,按未合并的树找
        compose.onNodeWithTag("voicecall-clock", useUnmergedTree = true).assertTextEquals("0:00")
        awaitBall("Globe")
        call.session.end(EndReason.Hangup)
    }

    @Test fun micLevelSwitchesBallToWave() {
        val call = Call(connect = { rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // 用户开口:响亮采集块进来,球换成 Wave
        repeat(3) { call.audio.offerChunk(amplitude = 4_000) }
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun cloudAudioDrivesSpeakerLevelAndWave() {
        val call = Call(connect = { rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // 助手播音:云端音频进播放队列,电平起来,球换成 Wave
        call.rt.emit(VolcRealtimeEvent.Audio(pcm(4_000, 4_800)))
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun workingTaskShowsTaskCardAndStatusLines() {
        // 任务卡上的进度取任务给人看的那句(narration),不取步骤备注
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "open_app", narration = "正在打开设置"))
        val call = Call(mapOf("t1" to flow), connect = { rt })
        show(call)
        reachActive(call)
        // 打字办事:先点键盘展开输入行,发出去后直接派单,任务卡上来
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // 办完:卡片收尾标「已完成」
        flow.value = TaskState.Succeeded("开好了")
        awaitText("已完成")
        compose.onNodeWithTag("voicecall-input").assertTextEquals("")
        call.session.end(EndReason.Hangup)
    }

    @Test fun tappingATaskCardOpensItAndViewScreenShowsTheScreenOnTheCallPage() {
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "open_app", narration = "正在打开设置"))
        val call = Call(mapOf("t1" to flow), connect = { rt })
        val screens = MutableStateFlow<Set<String>>(emptySet())
        val frame = MutableStateFlow<android.graphics.Bitmap?>(android.graphics.Bitmap.createBitmap(90, 160, android.graphics.Bitmap.Config.ARGB_8888))
        val opened = mutableListOf<String>()
        show(call, screens = screens, frames = { frame }, onOpenTask = { opened += it })
        reachActive(call)
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // 还没建屏:没有「看画面」;建了屏就出来
        compose.onAllNodesWithTag("voicecall-task-screen").assertCountEquals(0)
        val taskId = call.feed.items.value.filterIsInstance<com.wangzan101.mika.voicecall.VoiceCallFeedItem.Task>().single().taskId
        screens.value = setOf(taskId)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voicecall-task-screen").fetchSemanticsNodes().isNotEmpty() }
        // 点「看画面」:画面出现在通话页上,不离开通话;× 关掉
        compose.onNodeWithTag("voicecall-task-screen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voicecall-screen-panel").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(opened.isEmpty())
        compose.onNodeWithTag("voicecall-screen-close").performClick()
        compose.onAllNodesWithTag("voicecall-screen-panel").assertCountEquals(0)
        // 点卡片:交给宿主打开那件任务
        compose.onNodeWithTag("voicecall-task").performClick()
        assertEquals(listOf(taskId), opened)
        // 办完了就不再显示「看画面」
        flow.value = TaskState.Succeeded("开好了")
        awaitText("已完成")
        compose.onAllNodesWithTag("voicecall-task-screen").assertCountEquals(0)
        call.session.end(EndReason.Hangup)
    }

    @Test fun bargeInInterruptsPlaybackButNeverCancelsTheRunningTask() {
        // 任务卡上的进度取任务给人看的那句(narration),不取步骤备注
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "open_app", narration = "正在打开设置"))
        val call = Call(mapOf("t1" to flow), connect = { rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // 用户开口打断:只停播放,后台任务照常跑
        call.rt.emit(VolcRealtimeEvent.UserSpeaking)
        awaitValue { call.audio.interruptCount == 1 }
        assertTrue(call.port.cancelled.isEmpty())
        assertTrue(flow.value is TaskState.Running)
        compose.onAllNodesWithText("正在打开设置").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        call.session.end(EndReason.Hangup)
    }

    @Test fun hangupShowsEndedCopy() {
        val call = Call(connect = { rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-hangup").performClick()
        compose.onNodeWithText("通话已结束").assertIsDisplayed()
        assertTrue(call.session.state.value is VoiceCallState.Ended)
        assertEquals(1, call.audio.focusAbandons)
    }

    @Test fun failureShowsErrorCopyAndRetryRerunsTheFlow() {
        val attempts = AtomicInteger(0)
        val call = Call(connect = {
            if (attempts.incrementAndGet() == 1) throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT) else rt
        })
        show(call)
        // 第一次失败:错误文案(顶栏状态 + 错误卡各一条) + 就地重试
        awaitText("连接超时，再试一次")
        compose.onNodeWithTag("voicecall-retry").performClick()
        reachActive(call)
        compose.onNodeWithText("在听你说").assertIsDisplayed()
        assertEquals(2, attempts.get())
        call.session.end(EndReason.Hangup)
    }

    @Test fun aQuietCallEndsWithItsOwnCopy() {
        val call = Call(connect = { rt }, idleMs = 300L)
        show(call)
        reachActive(call)
        awaitState(call.session) { it == VoiceCallState.Ended(EndReason.Idle, idleMinutes = 1) }
        compose.onNodeWithText("1 分钟没有动静，通话已结束", substring = true).assertIsDisplayed()
    }

    @Test fun collapseInvokesCallback() {
        val collapses = AtomicInteger(0)
        val call = Call(connect = { rt })
        show(call, onCollapse = { collapses.incrementAndGet() })
        reachActive(call)
        compose.onNodeWithTag("voicecall-collapse").performClick()
        assertEquals(1, collapses.get())
        call.session.end(EndReason.Hangup)
    }

    @Test fun theLastCallLinkJumpsToItAndThePageHasNoMenu() {
        val picked = mutableListOf<String>()
        val earlier = com.wangzan101.mika.chat.ConversationCall("old", 1_000L, 61_000L, spoke = true)
        val call = Call(connect = { rt })
        show(call, calls = listOf(earlier), onOpenRecord = { picked += it })
        reachActive(call)
        compose.onNodeWithTag("voicecall-last-call").performClick()
        assertEquals(listOf("old"), picked)
        // 通话记录和通话设置从聊天页右上角的 ⋯ 进,通话页不再放 ⋯
        compose.onNodeWithTag("voicecall-more").assertDoesNotExist()
        call.session.end(EndReason.Hangup)
    }

    // ---------- 权限 / 凭据的门 ----------

    @Test fun permissionGateRequestsThenPointsToSettings() {
        val requests = AtomicInteger(0)
        val settings = AtomicInteger(0)
        var stage by mutableStateOf(VoiceCallGateStage.NeedPermission)
        compose.setContent {
            StillTheme {
                VoiceCallGate(
                    stage,
                    onRequestPermission = { requests.incrementAndGet() },
                    onOpenSettings = { settings.incrementAndGet() },
                    onGoConfigure = {},
                    onCollapse = {},
                )
            }
        }
        compose.onNodeWithText("语音通话要用麦克风").assertIsDisplayed()
        compose.onNodeWithText("允许麦克风").performClick()
        assertEquals(1, requests.get())
        // 用户拒绝过:改指到系统设置
        stage = VoiceCallGateStage.PermissionDenied
        compose.onNodeWithText("去设置").performClick()
        assertEquals(1, settings.get())
    }

    @Test fun credentialsGatePointsToSetup() {
        val setups = AtomicInteger(0)
        compose.setContent {
            StillTheme {
                VoiceCallGate(
                    VoiceCallGateStage.NeedCredentials,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onGoConfigure = { setups.incrementAndGet() },
                    onCollapse = {},
                )
            }
        }
        compose.onNodeWithText("先配置实时语音").assertIsDisplayed()
        compose.onNodeWithText("去配置").performClick()
        assertEquals(1, setups.get())
    }

    @Test fun unsupportedProtocolGateExplainsAndPointsToSetup() {
        val setups = AtomicInteger(0)
        compose.setContent {
            StillTheme {
                VoiceCallGate(
                    VoiceCallGateStage.UnsupportedProtocol,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onGoConfigure = { setups.incrementAndGet() },
                    onCollapse = {},
                )
            }
        }
        compose.onNodeWithText("语音通话需要兼容的配置").assertIsDisplayed()
        compose.onNodeWithText("去配置").performClick()
        assertEquals(1, setups.get())
    }
}
