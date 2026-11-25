package com.wangzan101.mika.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDanmakuFilterTest {
    @Test
    fun `keeps a real comment and trims it`() {
        val filter = LiveDanmakuFilter(minIntervalMs = 0)
        assertEquals("主播今天讲得真好", filter.accept("  主播今天讲得真好  ", 1_000L))
    }

    @Test
    fun `drops buttons status lines and system events`() {
        listOf(
            "关注", "点赞", "说点什么...", "进入直播间", "送出了小心心", "感谢关注",
            "1", "666", "😂😂😂", "https://example.com/a", "",
        ).forEach { line ->
            assertFalse("should drop: $line", LiveDanmakuText.plausible(line))
        }
        assertTrue(LiveDanmakuText.plausible("这个价格在哪买"))
        assertTrue(LiveDanmakuText.plausible("6666 哈哈"))
    }

    @Test
    fun `drops douyin invisible markers and entry notices`() {
        val welcome = "\u200E欢迎来到直播间！抖音严禁未成年人直播或礼物消费"
        val entered = "\u200E* hncea 来了 · 主播也在成都"
        val entered2 = "\u200E* 菜江湖～傅鲜森 来了"
        assertFalse(LiveDanmakuText.plausible(welcome))
        assertFalse(LiveDanmakuText.plausible(entered))
        assertFalse(LiveDanmakuText.plausible(entered2))
        // 真评论里带「来了」但不带前置空白,不能被误杀
        assertTrue(LiveDanmakuText.plausible("又来了"))
    }

    @Test
    fun `comment range scales with screen height`() {
        assertEquals(1_447..2_170, LiveDanmakuLayout.commentRange(2_412))
        assertTrue(1_960 in LiveDanmakuLayout.commentRange(2_412))
        assertTrue(500 !in LiveDanmakuLayout.commentRange(2_412))
    }

    @Test
    fun `does not repeat the same comment in the window`() {
        val filter = LiveDanmakuFilter(minIntervalMs = 0)
        assertEquals("晚上好", filter.accept("晚上好", 1_000L))
        assertNull(filter.accept("晚上好", 2_000L))
        assertNull(filter.accept(" 晚上好 ", 3_000L))
    }

    @Test
    fun `spaces and case do not make a new comment`() {
        val filter = LiveDanmakuFilter(minIntervalMs = 0)
        assertEquals("主播 早上好", filter.accept("主播  早上好", 1_000L))
        assertNull(filter.accept("主播 早上好", 2_000L))
        assertEquals("OK 主播", filter.accept("OK 主播", 3_000L))
        assertNull(filter.accept("ok 主播", 4_000L))
    }

    @Test
    fun `respects the minimum gap between spoken lines`() {
        val filter = LiveDanmakuFilter(minIntervalMs = 1_500)
        assertEquals("第一条", filter.accept("第一条", 10_000L))
        assertNull(filter.accept("第二条", 11_000L))
        assertEquals("第二条", filter.accept("第二条", 11_500L))
    }

    @Test
    fun `forgets old lines after ttl`() {
        val filter = LiveDanmakuFilter(minIntervalMs = 0, ttlMs = 60_000)
        assertEquals("又来了", filter.accept("又来了", 1_000L))
        assertNull(filter.accept("又来了", 30_000L))
        assertNull(filter.accept("又来了", 60_999L))
        assertEquals("又来了", filter.accept("又来了", 61_000L))
    }

    @Test
    fun `clear resets both deduplication and the rate limit`() {
        val filter = LiveDanmakuFilter(minIntervalMs = 5_000)
        assertEquals("你好呀", filter.accept("你好呀", 1_000L))
        filter.clear()
        assertEquals("你好呀", filter.accept("你好呀", 2_000L))
    }
}
