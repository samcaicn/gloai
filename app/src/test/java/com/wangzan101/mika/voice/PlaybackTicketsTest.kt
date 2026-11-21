package com.wangzan101.mika.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTicketsTest {

    @Test
    fun `新请求使旧请求失效`() {
        val tickets = PlaybackTickets()
        val first = tickets.begin()
        assertTrue(tickets.owns(first))
        val second = tickets.begin()
        assertFalse(tickets.owns(first))
        assertTrue(tickets.owns(second))
    }

    @Test
    fun `写文件完成时旧请求的回调被丢弃`() {
        val tickets = PlaybackTickets()
        // 第一条请求开始写文件(挂起中),第二条请求紧跟着进来
        val staleWrite = tickets.begin()
        val freshWrite = tickets.begin()
        // 旧写文件先完成:它的代次已经作废,不允许落地成播放器
        assertFalse(tickets.owns(staleWrite))
        // 新写文件完成:只有它能继续建 MediaPlayer
        assertTrue(tickets.owns(freshWrite))
    }

    @Test
    fun `主动停下后旧播放器的回调被丢弃`() {
        val tickets = PlaybackTickets()
        val playing = tickets.begin()
        // 用户点了停止;prepare 还在路上的 MediaPlayer 之后才回调 onPrepared
        tickets.invalidate()
        assertFalse(tickets.owns(playing))
        // 之后的下一次播放拿到全新代次,不受这次作废影响
        val next = tickets.begin()
        assertTrue(tickets.owns(next))
    }

    @Test
    fun `同一代次内多次回调都算数`() {
        val tickets = PlaybackTickets()
        val ticket = tickets.begin()
        assertTrue(tickets.owns(ticket))
        assertTrue(tickets.owns(ticket))
    }
}
