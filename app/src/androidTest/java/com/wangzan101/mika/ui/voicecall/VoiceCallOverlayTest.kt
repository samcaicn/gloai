package com.wangzan101.mika.ui.voicecall

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.wangzan101.mika.voicecall.VoiceCallRoute
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTaskLauncher
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
class VoiceCallOverlayTest {

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

    /** 一通假通话:真 session 接假设备;[connect] 决定连接成败(可多次,配合重试)。 */
    private inner class Call(
        val route: VoiceCallRoute,
        connect: suspend (Int) -> CallRealtimeSession,
        timeLimitMs: Long = 600_000L,
    ) {
        val audio = FakeAudio()
        val rt = FakeRealtime()
        val session = VoiceCallSession(
            scope = scope,
            audio = audio,
            foregroundResumed = MutableStateFlow(true),
            connect = connect,
            greeting = "你好",
            onVoiceRequest = { callId, text -> scope.launch { route.execute(callId, text) } },
            onBargeIn = route::onBargeIn,
            timeLimitMs = timeLimitMs,
        )
    }

    private fun fakeRoute(
        flows: Map<String, MutableStateFlow<TaskState>> = emptyMap(),
        cancelled: MutableList<String> = mutableListOf(),
    ) = VoiceCallRoute(
        launcher = VoiceCallTaskLauncher { "conv-1" },
        runState = { flows[it] },
        sendResult = { _, _ -> },
        cancelTask = { id ->
            cancelled += id
            flows[id]?.value = TaskState.Cancelled
        },
    )

    private fun show(call: Call, onCollapse: () -> Unit = {}) {
        val audioRoute = VoiceCallAudioRoute(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MikaTheme { VoiceCallOverlay(call.session, call.route, audioRoute, onCollapse) }
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
        val call = Call(fakeRoute(), connect = { gate.await() })
        show(call)
        // 连接中:状态词 + Web 形态球;计时还没开始
        compose.onNodeWithText("正在连接…").assertIsDisplayed()
        awaitBall("Web")

        // 服务接上了:检查麦克风 → 接通
        gate.complete(call.rt)
        reachActive(call)
        compose.onNodeWithText("已连接").assertIsDisplayed()
        compose.onNodeWithTag("voicecall-clock").assertTextEquals("0:00")
        awaitBall("Globe")
        call.session.end(EndReason.Hangup)
    }

    @Test fun micLevelSwitchesBallToWave() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // 用户开口:响亮采集块进来,球换成 Wave
        repeat(3) { call.audio.offerChunk(amplitude = 4_000) }
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun cloudAudioDrivesSpeakerLevelAndWave() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // 助手播音:云端音频进播放队列,电平起来,球换成 Wave
        call.rt.emit(VolcRealtimeEvent.Audio(pcm(4_000, 4_800)))
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun workingTaskShowsOrbitsAndStatusLines() {
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "正在打开设置"))
        val route = fakeRoute(mapOf("conv-1" to flow))
        lateinit var call: Call
        call = Call(route, connect = { call.rt })
        show(call)
        reachActive(call)
        // 打字办事:路由认领,球换 Orbits,状态行上来
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        compose.onNodeWithText("正在打开设置").assertIsDisplayed()
        awaitBall("Orbits")
        // 办完:终态行高亮,球回 Globe
        flow.value = TaskState.Succeeded("开好了")
        compose.onNodeWithText("已完成").assertIsDisplayed()
        awaitBall("Globe")
        compose.onNodeWithTag("voicecall-input").assertTextEquals("")
        call.session.end(EndReason.Hangup)
    }

    @Test fun bargeInInterruptsPlaybackAndCancelsTheRunningTask() {
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "正在打开设置"))
        val cancelled = mutableListOf<String>()
        val route = fakeRoute(mapOf("conv-1" to flow), cancelled)
        lateinit var call: Call
        call = Call(route, connect = { call.rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitBall("Orbits")
        // 用户开口打断:播放立刻停,已启动未完成的任务取消
        call.rt.emit(VolcRealtimeEvent.UserSpeaking)
        awaitValue { cancelled == listOf("conv-1") }
        assertEquals(1, call.audio.interruptCount)
        compose.onNodeWithText("已取消").assertIsDisplayed()
        call.session.end(EndReason.Hangup)
    }

    @Test fun hangupShowsEndedCopy() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-hangup").performClick()
        compose.onNodeWithText("通话已结束").assertIsDisplayed()
        assertTrue(call.session.state.value is VoiceCallState.Ended)
        assertEquals(1, call.audio.focusAbandons)
    }

    @Test fun failureShowsErrorCopyAndRetryRerunsTheFlow() {
        val attempts = AtomicInteger(0)
        lateinit var call: Call
        call = Call(fakeRoute(), connect = {
            if (attempts.incrementAndGet() == 1) throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT) else call.rt
        })
        show(call)
        // 第一次失败:错误文案 + 就地重试
        compose.onNodeWithText("连接超时，再试一次").assertIsDisplayed()
        compose.onNodeWithTag("voicecall-retry").performClick()
        reachActive(call)
        compose.onNodeWithText("已连接").assertIsDisplayed()
        assertEquals(2, attempts.get())
        call.session.end(EndReason.Hangup)
    }

    @Test fun timeLimitEndsTheCallWithItsOwnCopy() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt }, timeLimitMs = 300L)
        show(call)
        reachActive(call)
        awaitState(call.session) { it == VoiceCallState.Ended(EndReason.TimeLimit) }
        compose.onNodeWithText("聊满 10 分钟，通话结束").assertIsDisplayed()
    }

    @Test fun collapseInvokesCallback() {
        val collapses = AtomicInteger(0)
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call, onCollapse = { collapses.incrementAndGet() })
        reachActive(call)
        compose.onNodeWithTag("voicecall-collapse").performClick()
        assertEquals(1, collapses.get())
        call.session.end(EndReason.Hangup)
    }

    // ---------- 权限 / 凭据的门 ----------

    @Test fun permissionGateRequestsThenPointsToSettings() {
        val requests = AtomicInteger(0)
        val settings = AtomicInteger(0)
        var stage by mutableStateOf(VoiceCallGateStage.NeedPermission)
        compose.setContent {
            MikaTheme {
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
            MikaTheme {
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
            MikaTheme {
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
