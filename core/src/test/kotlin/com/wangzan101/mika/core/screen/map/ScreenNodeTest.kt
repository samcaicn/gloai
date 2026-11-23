package com.wangzan101.mika.core.screen.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenNodeTest {
    @Test fun screenIdIsDeterministicAndStable() {
        val a = ScreenId.of("com.example.app", "关于本机|设置")
        val b = ScreenId.of("com.example.app", "关于本机|设置")
        assertEquals(a, b) // 同输入两次相同
        assertEquals(16, a.length) // 16 hex
        assertNotEquals(ScreenId.of("com.example.app", "关于本机|设置"), ScreenId.of("com.other.app", "关于本机|设置")) // 不同 app
        assertNotEquals(ScreenId.of("com.example.app", "关于本机|设置"), ScreenId.of("com.example.app", "关于本机|设置|更多")) // 不同签名
        assertEquals(a, ScreenId.of("com.example.app", "关于本机|设置")) // 稳定，不随时间变
    }

    @Test fun codecRoundTrips() {
        val n = ScreenNode(
            id = ScreenId.of("com.example.app", "关于本机|设置"),
            app = "com.example.app",
            name = "",
            anchors = mapOf("关于本机" to 3, "设置" to 1),
            levels = emptyMap(),
            transitions = mapOf("k" to Transition("click", "设置", TransitionEffect.NAVIGATED, "abc123", 2, 0)),
            visits = 5,
            status = "confirmed",
        )
        assertEquals(n, ScreenNodeCodec.decode(ScreenNodeCodec.encode(n)))
    }

    @Test fun transitionKeyUsesTupleNotCounters() {
        val a = Transition("click", "设置", TransitionEffect.NAVIGATED, "target", count = 1, offTrack = 0)
        val b = Transition("click", "设置", TransitionEffect.NAVIGATED, "target", count = 9, offTrack = 3)
        assertEquals(a.key(), b.key()) // 同一 (动作, 目标, 效果, 到哪屏) 是一条边，次数/跑偏另计
        val c = Transition("click", "返回", TransitionEffect.NAVIGATED, "target", count = 1)
        assertNotEquals(a.key(), c.key()) // 目标不同则不同边
    }
}
