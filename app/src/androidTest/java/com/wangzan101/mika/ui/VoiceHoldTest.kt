package com.wangzan101.mika.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import com.wangzan101.mika.voice.SpeechFailure
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 对话输入栏按住说话:长按输入框浮起「在听」卡片,松开后字接在输入框里;上滑取消时什么都不填。用假的语音来源,不用麦克风。 */
@RunWith(AndroidJUnit4::class)
class VoiceHoldTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 假会话:事件由测试喂;记下松开、取消的次数。 */
    private class FakeSession : SpeechSession {
        val out = Channel<SpeechEvent>(Channel.UNLIMITED)
        override val events: Flow<SpeechEvent> = out.receiveAsFlow()
        override val live = true
        @Volatile var released = 0
        @Volatile var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++; out.close() }
    }

    private class FakeVoice : VoiceSource {
        val sessions = mutableListOf<FakeSession>()
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.System(onDevice = true)
        override fun start(language: String?): SpeechSession = FakeSession().also { sessions += it }
    }

    /** 引擎不可用的假来源(系统识别没响应);不该有人来起会话。 */
    private class UnavailableVoice : VoiceSource {
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
        override fun start(language: String?): SpeechSession = error("不可用的引擎不该起会话")
    }

    private val voice = FakeVoice()

    @Before fun grantMic() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    /** 「松开 发送」发出的字;[composer] 带上 sendVoice = true 时才有这条路。 */
    private val voiceSent = mutableListOf<String>()

    private fun composer(text: String = "", sendVoice: Boolean = false, source: VoiceSource = voice) = compose.setContent {
        MikaTheme {
            var t by remember { mutableStateOf(text) }
            Box(Modifier.fillMaxSize()) {
                Composer(
                    Modifier, t, { t = it }, ComposerMode.Idle, hasHistory = false, onSend = {}, onStop = {},
                    voice = source,
                    onVoiceText = { t = com.wangzan101.mika.voice.HoldToTalk.join(t, it) },
                    onVoiceSend = if (sendVoice) ({ voiceSent += com.wangzan101.mika.voice.HoldToTalk.join(t, it); t = "" }) else null,
                )
            }
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitNoTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    /** 按在输入框中间的手指要往上移多少,才到「在听」卡片里 y = 卡片上沿 + [intoCard](负数是卡片上沿以上)。 */
    private fun liftTo(intoCard: Float): Float {
        val card = compose.onNodeWithTag("voice-listen").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("composer-field").fetchSemanticsNode().boundsInRoot
        return (card.top + intoCard) - field.center.y
    }

    @Test fun holdShowsTheListenCardAndReleaseFillsTheField() {
        composer("帮我")
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        // 按满半秒以上才不算误触
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("帮我订明天的票")
    }

    @Test fun focusedFieldKeepsLongPressForTheSystem() {
        composer("帮我订票")
        // 点一下:聚焦打字
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        // 聚焦后长按是系统的选字、粘贴,不开始听
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        Thread.sleep(800)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0, voice.sessions.size)
        assertEquals(0, compose.onAllNodesWithTag("voice-listen").fetchSemanticsNodes().size)
    }

    @Test fun focusedMicButtonFillsTheField() {
        composer("帮我")
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        waitTag("composer-mic")
        compose.onNodeWithTag("composer-mic").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        Thread.sleep(700)
        compose.onNodeWithTag("composer-mic").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("帮我订明天的票")
    }

    /** 已允许录音,识别服务仍报录音出错:弹说明(麦克风总开关、权限管理);别的麦克风起不来照旧只提示一句。 */
    @Test fun audioErrorExplainsWhereToCheckButClientErrorKeepsTheOldHint() {
        composer()
        fun failWith(why: SpeechFailure) {
            compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
            waitTag("voice-listen")
            voice.sessions.last().out.trySend(SpeechEvent.Failed(why))
            compose.onNodeWithTag("composer-field").performTouchInput { up() }
        }
        failWith(SpeechFailure.MicUnavailable)
        waitTag("voice-toast")
        compose.onNodeWithText("麦克风用不了，可能正被别的 App 占着").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("voice-sheet").fetchSemanticsNodes().size)
        failWith(SpeechFailure.AudioError)
        waitTag("voice-sheet")
        compose.onNodeWithText("麦克风还是用不了").assertExists()
    }

    @Test fun slidingUpCancels() {
        composer()
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        Thread.sleep(700)
        // 滑过卡片上沿才是取消(滑进卡片里是直接发送)
        val dy = liftTo(-40f)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy)) }
        // 输入栏和上面的小胶囊都写「松开 取消」
        compose.waitUntil(5_000) { compose.onAllNodesWithText("松开 取消", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.cancelled == 1 }
        assertEquals(0, session.released)
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("")
    }

    @Test fun slidingIntoTheCardSendsWithWhatWasTyped() {
        composer("帮我", sendVoice = true)
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        Thread.sleep(700)
        val card = compose.onNodeWithTag("voice-listen").fetchSemanticsNode().boundsInRoot
        val dy = liftTo(card.height / 2)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy)) }
        compose.onNodeWithText("松开 发送", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        compose.waitUntil(5_000) { voiceSent.isNotEmpty() }
        assertEquals(listOf("帮我订明天的票"), voiceSent)
        compose.onNodeWithTag("composer-field").assertTextEquals("")
    }

    @Test fun withoutASendPathTheCardOnlyFills() {
        composer()
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        Thread.sleep(700)
        val card = compose.onNodeWithTag("voice-listen").fetchSemanticsNode().boundsInRoot
        val dy = liftTo(card.height / 2)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy)) }
        compose.onNodeWithText("松开 填入", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("订明天的票")
    }

    /** 引擎不可用功能也不消失:按住照常弹「还不能语音输入」说明页(原因 + 去选引擎的路),按住了说明页留着。 */
    @Test fun unavailableEngineHoldShowsTheExplanationSheet() {
        composer(source = UnavailableVoice())
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-sheet")
        compose.onNodeWithText("还不能语音输入").assertExists()
        // 按满半秒以上是故意按住:说明页留着,不收
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("voice-sheet").assertExists()
    }

    /** 引擎不可用时短按仍是点按:说明页弹过也要收掉、输入框聚焦打字(方案A 对说明页同样成立)。 */
    @Test fun unavailableEngineShortHoldFallsBackToTap() {
        composer(source = UnavailableVoice())
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        // 350ms:过了起听的 300ms(说明页已弹)、不足半秒,松手必须回退点按
        Thread.sleep(350)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        waitNoTag("voice-sheet")
        compose.onNodeWithTag("composer-field").assertIsFocused()
    }

    /** 引擎不可用时点话筒:直接弹说明页,不弹「按住说话」那句误导提示。 */
    @Test fun unavailableEngineMicTapOpensTheSheet() {
        composer(source = UnavailableVoice())
        waitTag("composer-mic")
        compose.onNodeWithTag("composer-mic").performClick()
        waitTag("voice-sheet")
        compose.onNodeWithText("还不能语音输入").assertExists()
    }
}
