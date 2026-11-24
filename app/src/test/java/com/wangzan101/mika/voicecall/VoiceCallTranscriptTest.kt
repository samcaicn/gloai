package com.wangzan101.mika.voicecall

import com.wangzan101.mika.chat.ConversationCall
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallTranscriptTest {

    private class Harness(conversationId: String? = "conv-9") {
        var now = 1_000L
        val appended = mutableListOf<Pair<String, ChatTurn>>()
        val recorded = mutableListOf<Pair<String, ConversationCall>>()
        val transcript = VoiceCallTranscript(
            conversationId = { conversationId },
            appendTurn = { id, turn -> appended += id to turn },
            nowMillis = { now },
            callId = "call-1",
            recordCall = { id, call -> recorded += id to call },
        )
    }

    @Test
    fun `聚合问、答、时间`() {
        val h = Harness()
        h.transcript.onUser("明天天气怎么样")
        h.now = 2_000L
        h.transcript.onAssistant("明天晴天")
        h.transcript.onAssistant("最高 25 度")
        assertEquals(
            listOf(VoiceCallTranscript.Turn("明天天气怎么样", "明天晴天\n最高 25 度", 1_000L)),
            h.transcript.turns(),
        )
    }

    @Test
    fun `新一轮用户话语闭合上一轮`() {
        val h = Harness()
        h.transcript.onUser("第一句")
        h.transcript.onAssistant("答一")
        h.now = 5_000L
        h.transcript.onUser("第二句")
        h.transcript.onAssistant("答二")
        assertEquals(
            listOf(
                VoiceCallTranscript.Turn("第一句", "答一", 1_000L),
                VoiceCallTranscript.Turn("第二句", "答二", 5_000L),
            ),
            h.transcript.turns(),
        )
    }

    @Test
    fun `助手在用户开口前说的话聚成空问轮`() {
        val h = Harness()
        h.transcript.onAssistant("你好，我在听")
        assertEquals(listOf(VoiceCallTranscript.Turn("", "你好，我在听", 1_000L)), h.transcript.turns())
    }

    @Test
    fun `空白文本不进门`() {
        val h = Harness()
        h.transcript.onUser("")
        h.transcript.onAssistant("   ")
        assertTrue(h.transcript.turns().isEmpty())
    }

    @Test
    fun `结束写回发起会话——与聊天相同的存储入口`() = runTest {
        val h = Harness()
        h.transcript.onUser("带猫去打疫苗")
        h.transcript.onAssistant("好的，记下了")
        h.now = 3_000L
        h.transcript.onUser("顺便买猫粮")
        h.transcript.onAssistant("已加入清单")
        h.transcript.close()
        assertEquals(2, h.appended.size)
        val (id1, t1) = h.appended[0]
        assertEquals("conv-9", id1)
        assertEquals("带猫去打疫苗", t1.userText)
        assertEquals("好的，记下了", t1.outcome)
        // 通话里是前台在回话:写成前台的一轮,只派了单没回话的那轮也不会画成空的「完成」
        assertEquals(com.wangzan101.mika.core.agent.DeskTurn.STATUS, t1.status)
        assertEquals(1_000L, t1.atMs)
        val (id2, t2) = h.appended[1]
        assertEquals("conv-9", id2)
        assertEquals("顺便买猫粮", t2.userText)
        assertEquals(3_000L, t2.atMs)
    }

    @Test
    fun `末轮有问无答不写成空 outcome`() = runTest {
        val h = Harness()
        h.transcript.onUser("带猫去打疫苗")
        h.transcript.onAssistant("好的，记下了")
        h.now = 3_000L
        h.transcript.onUser("顺便买猫粮") // 通话到这就挂了,这句没等来回答
        h.transcript.close()
        assertEquals(1, h.appended.size)
        val (id, turn) = h.appended[0]
        assertEquals("conv-9", id)
        assertEquals("带猫去打疫苗", turn.userText)
        assertEquals("好的，记下了", turn.outcome)
    }

    @Test
    fun `全程有问无答一个字也不写`() = runTest {
        val h = Harness()
        h.transcript.onUser("喂喂，听得到吗")
        h.transcript.close()
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `空问轮不写回`() = runTest {
        val h = Harness()
        h.transcript.onAssistant("你好，我在听")
        h.transcript.close()
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `重复结束不重复写`() = runTest {
        val h = Harness()
        h.transcript.onUser("第一句")
        h.transcript.onAssistant("答")
        h.transcript.close()
        h.transcript.close()
        assertEquals(1, h.appended.size)
    }

    @Test
    fun `没有发起会话时不写`() = runTest {
        val h = Harness(conversationId = null)
        h.transcript.onUser("第一句")
        h.transcript.onAssistant("答")
        h.transcript.close()
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `调用方协程被取消时仍批量写完`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val appended = mutableListOf<String>()
        val transcript = VoiceCallTranscript(
            conversationId = { "conv-9" },
            appendTurn = { _, turn ->
                appended += turn.userText
                if (appended.size == 1) scope.cancel() // 写到一半整个通话协程域被撤
                delay(1)
            },
            nowMillis = { 7L },
        )
        transcript.onUser("第一句")
        transcript.onAssistant("甲")
        transcript.onUser("第二句")
        transcript.onAssistant("乙")
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { transcript.close() }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("第一句", "第二句"), appended)
    }

    @Test
    fun `派了任务的轮次带上任务编号写回,没等来回答也写`() = runTest {
        val h = Harness()
        h.transcript.onTask("t0") // 还没有当轮:忽略
        h.transcript.onUser("帮我订明晚的日料")
        h.transcript.onTask("t1")
        h.transcript.onTask("t1")
        h.transcript.onUser("再查一下天气")
        h.transcript.onTask("t2") // 派出去就挂了,这句没等来回答
        h.transcript.close()
        assertEquals(listOf(listOf("t1"), listOf("t2")), h.appended.map { it.second.deskTasks })
        assertEquals(listOf("帮我订明晚的日料", "再查一下天气"), h.appended.map { it.second.userText })
    }

    @Test
    fun `每轮说完就写,不等挂断,挂断只补最后一轮`() = runTest {
        val appended = mutableListOf<String>()
        val transcript = VoiceCallTranscript(
            conversationId = { "conv-9" },
            appendTurn = { _, turn -> appended += turn.userText + "=" + turn.outcome },
            nowMillis = { 7L },
            writeScope = this,
        )
        transcript.onUser("第一句")
        transcript.onAssistant("甲")
        advanceUntilIdle()
        assertTrue("还在答的这一轮先不写", appended.isEmpty())
        transcript.onUser("第二句") // 用户开口说下一句:上一轮说完了,马上写
        advanceUntilIdle()
        assertEquals(listOf("第一句=甲"), appended)
        transcript.onUser("第三句") // 第二句没等来回答也没派任务:不写
        transcript.onAssistant("丙")
        advanceUntilIdle()
        assertEquals(listOf("第一句=甲"), appended)
        transcript.close()
        assertEquals("挂断补上最后一轮,已写过的不重写", listOf("第一句=甲", "第三句=丙"), appended)
        transcript.close()
        assertEquals(2, appended.size)
    }

    @Test
    fun `写进会话的每一轮都带上这一通的编号`() = runTest {
        val h = Harness()
        h.transcript.onUser("几点了")
        h.transcript.onAssistant("九点")
        h.transcript.close()
        assertEquals(listOf("call-1"), h.appended.map { it.second.callId })
    }

    @Test
    fun `接通记一条没结束的通话,挂断补上结束、原因和说没说话`() = runTest {
        val recorded = mutableListOf<ConversationCall>()
        var now = 5_000L
        val transcript = VoiceCallTranscript(
            conversationId = { "conv-9" },
            appendTurn = { _, _ -> },
            nowMillis = { now },
            writeScope = this,
            callId = "call-1",
            recordCall = { _, call -> recorded += call },
        )
        transcript.onConnected(4_000L)
        transcript.onConnected(4_500L) // 重连再接通:不改接通时刻,不再记
        advanceUntilIdle()
        assertEquals(listOf(ConversationCall("call-1", 4_000L)), recorded)
        transcript.onUser("明天天气")
        transcript.onAssistant("晴")
        now = 9_000L
        transcript.close(ConversationCall.END_IDLE)
        assertEquals(ConversationCall("call-1", 4_000L, 9_000L, ConversationCall.END_IDLE, spoke = true), recorded.last())
        assertEquals(2, recorded.size)
    }

    @Test
    fun `一句话没说的通话也记,标成没说话`() = runTest {
        val h = Harness()
        h.transcript.onConnected(500L)
        h.transcript.onUser("喂") // 没等来回答:不算说上话
        h.transcript.close()
        val call = h.recorded.single().second
        assertFalse(call.spoke)
        assertEquals(500L, call.startMs)
        assertEquals(ConversationCall.END_HANGUP, call.endReason)
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `没接通的不记通话`() = runTest {
        val h = Harness()
        h.transcript.close()
        assertTrue(h.recorded.isEmpty())
    }
}
