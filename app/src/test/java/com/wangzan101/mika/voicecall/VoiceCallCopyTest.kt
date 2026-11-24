package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.frontdesk.DESK_RESULT_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 通话页的错误/结束/状态文案映射:8+1 类错误、5 种结束原因各占一条;RESUMED 超时落的 Ended(Background) 也有独立说法。 */
class VoiceCallCopyTest {

    @get:Rule val texts = ChineseTexts()

    @Test
    fun `每类错误各占一条文案`() {
        assertEquals("实时语音凭据没过，去配置里检查", voiceCallErrorText(VoiceCallError.Auth))
        assertEquals("连接超时，再试一次", voiceCallErrorText(VoiceCallError.ConnectTimeout))
        assertEquals("连接断开了", voiceCallErrorText(VoiceCallError.Disconnected))
        assertEquals("请求太密，稍后再试", voiceCallErrorText(VoiceCallError.RateLimited))
        assertEquals("实时语音服务出错了", voiceCallErrorText(VoiceCallError.Provider))
        assertEquals("麦克风收不到声音", voiceCallErrorText(VoiceCallError.MicSilent))
        assertEquals("声音通道正被别的应用占用", voiceCallErrorText(VoiceCallError.AudioFocus))
        assertEquals("播放出错了", voiceCallErrorText(VoiceCallError.Playback))
        assertEquals("正在打电话，结束后再试", voiceCallErrorText(VoiceCallError.Busy))
        assertEquals(VoiceCallError.entries.size, VoiceCallError.entries.map(::voiceCallErrorText).distinct().size)
    }

    @Test
    fun `每种结束原因各占一条文案`() {
        assertEquals("通话已结束", voiceCallEndText(EndReason.Hangup))
        assertEquals("已从通知挂断", voiceCallEndText(EndReason.Notification))
        assertEquals("有电话进来，通话结束", voiceCallEndText(EndReason.IncomingCall))
        assertEquals("5 分钟没有动静，通话已结束", voiceCallEndText(EndReason.Idle, idleMinutes = 5))
        assertEquals("5 分钟没有动静，通话已结束", voiceCallStatusText(VoiceCallState.Ended(EndReason.Idle, idleMinutes = 5)))
        assertEquals("请回到 Mika 再继续", voiceCallEndText(EndReason.Background))
        assertEquals(EndReason.entries.size, EndReason.entries.map { voiceCallEndText(it, 5) }.distinct().size)
    }

    @Test
    fun `进行中的状态词`() {
        assertEquals("正在连接…", voiceCallStatusText(VoiceCallState.Connecting))
        assertEquals("正在检查麦克风…", voiceCallStatusText(VoiceCallState.MicCheck))
        assertEquals("已连接", voiceCallStatusText(VoiceCallState.Active(1_000L)))
        // 终态的状态词就是结束/错误文案
        assertEquals("通话已结束", voiceCallStatusText(VoiceCallState.Ended(EndReason.Hangup)))
        assertEquals("连接超时，再试一次", voiceCallStatusText(VoiceCallState.Failed(VoiceCallError.ConnectTimeout, 1)))
    }

    @Test
    fun `前台汇报说成一句话,正文截到上限`() {
        assertEquals("订日料那件，办好了：七点两位", deskReportSpeech(DeskReport(DeskEvent.Succeeded("t1", "七点两位"), "订日料")))
        assertEquals("订日料那件，在问你：几点？", deskReportSpeech(DeskReport(DeskEvent.NeedsAnswer("t1", "几点？", null), "订日料")))
        val long = deskReportSpeech(DeskReport(DeskEvent.Succeeded("t1", "字".repeat(DESK_RESULT_MAX_CHARS)), "订日料"))
        assertTrue(long.length <= "订日料那件，".length + DESK_RESULT_MAX_CHARS)
    }

    @Test
    fun `打字派单的结果回到本地`() {
        fun result(status: String) = buildJsonObject { put("status", status) }
        assertEquals("正在处理", typedDeskResult(result("started")))
        assertEquals("正在处理", typedDeskResult(result("queued")))
        assertEquals("正在处理", typedDeskResult(result("already_dispatched")))
        assertEquals("没办成", typedDeskResult(result("failed")))
        assertEquals("没办成", typedDeskResult(result("rejected")))
    }
}
