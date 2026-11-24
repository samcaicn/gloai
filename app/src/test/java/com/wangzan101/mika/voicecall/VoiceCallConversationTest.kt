package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallConversationTest {
    private val parents = mapOf("task-1" to "main")

    @Test fun `聊天页开着一条普通会话,通话接着它`() {
        assertEquals("main", VoiceCallConversation.pick("main", parents::get) { error("不该新开") })
    }

    @Test fun `聊天页停在前台派出的任务会话里,通话接回它的前台会话,之前的通话还在`() {
        assertEquals("main", VoiceCallConversation.pick("task-1", parents::get) { error("不该新开") })
    }

    @Test fun `聊天页是空白新对话,占下一条,聊天页也停到这条上,下次打开还是它`() {
        var showing: String? = null
        val claim = { "fresh".also { showing = it } }
        val first = VoiceCallConversation.pick(showing, parents::get, claim)
        assertEquals("fresh", first)
        assertTrue(showing == "fresh")
        assertEquals(first, VoiceCallConversation.pick(showing, parents::get) { error("第二次不该再新开") })
    }
}
