package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 主聊天的问题卡按正在办的那件事此刻的状态画按钮:接管、要你先办好一件事画出来,排队、锁屏、在跑不画。 */
class DeskNeedStatusTest {
    @Test fun takeoverKeepsReasonMessageAndPaymentLines() {
        val status = ChatReduce.needStatus(TaskState.WaitingTakeover("payment", "确认付款", "电影票", "¥45", "万达影城"), { it })
        assertEquals(ExchangeStatus.NeedsTakeover("payment", "确认付款", "电影票", "¥45", "万达影城"), status)
    }

    @Test fun heldThatNeedsYouIsAPausedCard() {
        val status = ChatReduce.needStatus(TaskState.Held(GateReason("x", "先打开无障碍", resumable = true, needsYou = true)), { it })
        assertEquals(ExchangeStatus.Paused("先打开无障碍", true), status)
    }

    @Test fun queuedLockedAndRunningDrawNothing() {
        assertNull(ChatReduce.needStatus(TaskState.Held(GateReason(GateReason.QUEUED, "排第 2")), { it }))
        assertNull(ChatReduce.needStatus(TaskState.Held(TaskGate.LOCKED), { it }))
        assertNull(ChatReduce.needStatus(TaskState.Idle, { it }))
    }
}
