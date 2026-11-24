package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallTaskLinkTest {
    private val saved = listOf(
        ChatTurn("开设置", "开好了", "succeeded", runId = "run-1"),
        ChatTurn("再看下蓝牙", "看了", "succeeded", runId = "run-2"),
        ChatTurn("谢谢", "", "desk"),
    )

    @Test fun `在办的打开正在跑的那次`() {
        assertEquals("run-3", VoiceCallTaskLink.runFor(DeskTaskPhase.Running, "run-3") { saved })
        assertEquals("run-3", VoiceCallTaskLink.runFor(DeskTaskPhase.NeedsYou, "run-3") { saved })
    }

    @Test fun `在办却还没起运行时不拿上一次的冒充`() {
        assertNull(VoiceCallTaskLink.runFor(DeskTaskPhase.Running, null) { saved })
    }

    @Test fun `排队的还没开跑,退回会话`() {
        assertNull(VoiceCallTaskLink.runFor(DeskTaskPhase.Queued, "run-3") { saved })
    }

    @Test fun `办完的打开会话里最近一次运行`() {
        assertEquals("run-2", VoiceCallTaskLink.runFor(DeskTaskPhase.Done, null) { saved })
        assertEquals("run-2", VoiceCallTaskLink.runFor(DeskTaskPhase.Failed, null) { saved })
        assertEquals("run-3", VoiceCallTaskLink.runFor(DeskTaskPhase.Cancelled, "run-3") { error("在跑的那次还在,不用读存储") })
        assertNull(VoiceCallTaskLink.runFor(DeskTaskPhase.Done, null) { emptyList() })
    }
}
