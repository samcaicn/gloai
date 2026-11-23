package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.frontdesk.DeskNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前台兜底写回会话的那一行(没有入口在线时的汇报)是一句正常的回话,不是失败。 */
class ChatReduceDeskReportTest {
    @Test fun deskReportRowRendersAsAReply() {
        val exchange = ChatReduce.fromTurn(Turn(userText = "", outcome = "订票：办好了：明早 8 点", status = DeskNotifier.STATUS))
        val status = exchange.status
        assertTrue("desk_report 不能显示成失败: $status", status is ExchangeStatus.Done)
        assertEquals("订票：办好了：明早 8 点", (status as ExchangeStatus.Done).answer)
        assertEquals("", exchange.goal)
    }
}
