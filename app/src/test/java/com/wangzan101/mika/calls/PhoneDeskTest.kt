package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskToolSet
import com.wangzan101.mika.core.frontdesk.DeskTools
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDeskTest {
    /** 记下问过什么、撤过几次;[reply] 模拟主人回了一句(通知里或会话里)。 */
    private class FakeAsk(var reach: OwnerReach = OwnerReach.Notified) : OwnerAsk {
        val questions = mutableListOf<String>()
        var cancels = 0
        private var onReply: ((String) -> Unit)? = null

        override fun ask(question: String, onReply: (String) -> Unit): OwnerReach {
            if (reach == OwnerReach.Unreachable) return reach
            questions += question
            this.onReply = onReply
            return reach
        }

        override fun cancel() {
            cancels++
        }

        /** 不管有没有撤,迟到的回复照样送进来:忽略它是 PhoneDesk 的事。 */
        fun reply(text: String) = onReply!!.invoke(text)
    }

    /** 交给模型的提示;[ended] 留着由测试决定说没说完。 */
    private class FakeCues(var accept: Boolean = true) {
        val sent = mutableListOf<String>()
        val ended = mutableListOf<(Boolean) -> Unit>()
        val context = mutableListOf<String>()
        fun cue(text: String, end: (Boolean) -> Unit): Boolean {
            if (!accept) return false
            sent += text
            ended += end
            return true
        }
        fun context(text: String): Boolean {
            if (!accept) return false
            context += text
            return true
        }
    }

    private fun args(question: String) = buildJsonObject { put("question", question) }
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun phoneHasOnlyAskOwner() = runBlocking {
        assertEquals(setOf(DeskTools.ASK_OWNER), DeskTools.specs(DeskToolSet.Phone).map { it.getValue("name").jsonPrimitive.content }.toSet())
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask)
        for (name in listOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL, "web_search", "check_tasks", "send_dtmf")) {
            assertEquals(name, "unsupported", desk.execute(name, buildJsonObject { put("words", "帮我订个位"); put("title", "x") }).status())
        }
        assertTrue(ask.questions.isEmpty())
    }

    @Test fun askOwnerReturnsImmediatelyAndRelaysReplyAsACueNotVerbatim() = runBlocking {
        val ask = FakeAsk()
        val cues = FakeCues()
        val desk = PhoneDesk({ null }, ask, cues::cue)
        val result = withTimeout(1_000) { desk.execute(DeskTools.ASK_OWNER, args("对方问能不能改到周日")) }
        assertEquals("asked", result.status())
        assertTrue(result.text("next").startsWith("Tell the caller you are checking"))
        assertEquals(listOf("对方问能不能改到周日"), ask.questions)
        assertTrue(cues.sent.isEmpty())
        ask.reply("周日也行，下午两点以后")
        // 交给模型的是一条提示:带着主人原话,标明不是对方说的、不要照念,由模型转述
        val cue = cues.sent.single()
        assertTrue(cue.contains("周日也行，下午两点以后"))
        assertTrue(cue.startsWith(PhoneDesk.CUE_MARK))
    }

    @Test fun secondAskWhilePendingIsBusy() = runBlocking {
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, FakeCues()::cue)
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("第一个问题")).status())
        assertEquals("busy", desk.execute(DeskTools.ASK_OWNER, args("第二个问题")).status())
        assertEquals(listOf("第一个问题"), ask.questions)
        ask.reply("好")
        // 回了以后可以再问
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("第三个问题")).status())
    }

    @Test fun dtmfStillHandledByBridge() {
        assertEquals(CallToolRoute.Dtmf, callToolRoute(VolcRealtimeProtocol.DTMF_FUNCTION))
        assertEquals(CallToolRoute.Desk, callToolRoute(DeskTools.ASK_OWNER))
        // 用户那套前台工具名、编造的名字都进 PhoneDesk,由它回 unsupported,绝不走用户前台的执行器
        assertEquals(CallToolRoute.Desk, callToolRoute(DeskTools.DISPATCH))
        assertEquals(CallToolRoute.Desk, callToolRoute("dial_number"))
    }

    @Test fun callEndCancelsOwnerAsk() = runBlocking {
        val ask = FakeAsk()
        val cues = FakeCues()
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, cues::cue) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("要不要加一个人"))
        desk.close()
        assertEquals(1, ask.cancels)
        ask.reply("加")
        assertTrue(cues.sent.isEmpty())
        assertTrue(unrelayed.isEmpty())
        assertEquals("unavailable", desk.execute(DeskTools.ASK_OWNER, args("还在吗")).status())
        assertEquals(listOf("要不要加一个人"), ask.questions)
    }

    @Test fun askWithoutAReachableOwnerSaysSoAndFreesTheSlot() = runBlocking {
        val ask = FakeAsk(OwnerReach.Unreachable)
        val desk = PhoneDesk({ null }, ask)
        assertEquals("unavailable", desk.execute(DeskTools.ASK_OWNER, args("能不能付定金")).status())
        ask.reach = OwnerReach.Notified
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("能不能付定金")).status())
        assertEquals("invalid_arguments", PhoneDesk({ null }, FakeAsk()).execute(DeskTools.ASK_OWNER, args("  ")).status())
    }

    @Test fun aQuestionThatOnlyReachedTheConversationTellsTheCallerToExpectAFollowUp() = runBlocking {
        // 通知发不出去(请示频道被关了)时问题照样进会话:还是 asked,但让模型跟对方说回头再答复
        val desk = PhoneDesk({ null }, FakeAsk(OwnerReach.InConversation))
        val result = desk.execute(DeskTools.ASK_OWNER, args("几点到"))
        assertEquals("asked", result.status())
        assertTrue(result.text("next").contains("get back to them"))
    }

    @Test fun cutReplyIsResentOnceThenHeldForTheNextAskAndLoggedIfNeverRelayed() = runBlocking {
        val ask = FakeAsk()
        val cues = FakeCues()
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, cues::cue) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("几点到"))
        ask.reply("七点")
        cues.ended.single().invoke(false)
        assertEquals(2, cues.sent.size)
        cues.ended[1].invoke(false)
        assertEquals(2, cues.sent.size)
        // 两次都没说出去:留着,模型下次请示时作为结果拿到,由它转述
        val held = desk.execute(DeskTools.ASK_OWNER, args("主人回了吗"))
        assertEquals("owner_replied", held.status())
        assertEquals("七点", held.text("reply"))
        assertEquals(listOf("几点到"), ask.questions)
        assertTrue(unrelayed.isEmpty())

        // 说出去了就不留
        desk.execute(DeskTools.ASK_OWNER, args("几个人"))
        ask.reply("四个")
        cues.ended.last().invoke(true)
        desk.close()
        assertTrue(unrelayed.isEmpty())
    }

    @Test fun withoutACueInputTheReplyWaitsForTheNextAskOrGoesToTheTranscript() = runBlocking {
        // 协议没有能让模型接着回话的文字输入(cue 返回 false):不重试,留给下一次请示;到挂断还没取走就记进通话记录
        val ask = FakeAsk()
        val cues = FakeCues(accept = false)
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, cues::cue) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("要包间吗"))
        ask.reply("不要")
        assertTrue(cues.sent.isEmpty())
        assertTrue(unrelayed.isEmpty())
        desk.close()
        assertEquals(listOf("不要"), unrelayed)
    }

    @Test fun withoutCuesTheModelIsToldToCollectTheReplyAndReAskingCollectsIt() = runBlocking {
        // 收不到提示的协议:回复不会自己到,请示结果和提示词都让模型再请示一次去取
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, canCue = false)
        val asked = desk.execute(DeskTools.ASK_OWNER, args("能不能改到周日"))
        assertEquals("asked", asked.status())
        assertTrue(asked.text("next").contains("will NOT arrive by itself"))
        assertTrue(asked.text("next").contains("call ask_owner again"))
        // 还没回:再请示不算忙,告诉它还在等,也不再发新问题
        val waiting = desk.execute(DeskTools.ASK_OWNER, args("能不能改到周日"))
        assertEquals("still_waiting", waiting.status())
        assertEquals(listOf("能不能改到周日"), ask.questions)
        // 回了:再请示就拿到原话,由模型转述
        ask.reply("周日也行")
        val replied = desk.execute(DeskTools.ASK_OWNER, args("能不能改到周日"))
        assertEquals("owner_replied", replied.status())
        assertEquals("周日也行", replied.text("reply"))
        // 取走以后再问就是新问题
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("几个人")).status())
        assertEquals(listOf("能不能改到周日", "几个人"), ask.questions)
    }

    @Test fun withoutCuesAQuietQuestionAlsoSaysToCollectTheReply() = runBlocking {
        val next = PhoneDesk({ null }, FakeAsk(OwnerReach.InConversation), canCue = false)
            .execute(DeskTools.ASK_OWNER, args("几点到")).text("next")
        assertTrue(next.contains("get back to them"))
        assertTrue(next.contains("call ask_owner again"))
    }

    @Test fun cueCapableCallsKeepTheNoteWordingAndBusy() = runBlocking {
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, FakeCues()::cue)
        val next = desk.execute(DeskTools.ASK_OWNER, args("几点到")).text("next")
        assertTrue(next.contains("will reach you as a note"))
        assertFalse(next.contains("call ask_owner again"))
        assertEquals("busy", desk.execute(DeskTools.ASK_OWNER, args("几点到")).status())
    }

    @Test fun thePromptsRelayRuleFollowsWhetherTheProtocolCanCue() {
        val cue = PhoneDesk.systemPrompt("电话规则", null, null, canCue = true)
        val noCue = PhoneDesk.systemPrompt("电话规则", null, null, canCue = false)
        assertTrue(cue.contains(PhoneDesk.RELAY_BY_CUE))
        assertFalse(cue.contains(PhoneDesk.RELAY_BY_ASKING))
        assertTrue(noCue.contains(PhoneDesk.RELAY_BY_ASKING))
        assertFalse(noCue.contains(PhoneDesk.RELAY_BY_CUE))
        // 哪些协议能收提示:标准通话、能加 system 文字项的实时协议能;双工对话协议只有照念的文字输入,不能
        assertTrue(PhoneDesk.canCue(standard = true, protocol = CallVoiceProtocol.VOLC_DUPLEX))
        assertTrue(PhoneDesk.canCue(standard = false, protocol = CallVoiceProtocol.OPENAI_REALTIME))
        assertFalse(PhoneDesk.canCue(standard = false, protocol = CallVoiceProtocol.VOLC_DUPLEX))
    }

    @Test fun ownerTaskStateIsRenderedForTheDeskContext() = runBlocking {
        val state = MutableStateFlow<TaskState>(TaskState.Running(step = 3, note = "phone_call_dial"))
        val desk = PhoneDesk({ state }, FakeAsk())
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "进行中", desk.ownerTaskLine())
        state.value = TaskState.WaitingInput("哪天?")
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "等主人处理", desk.ownerTaskLine())
        // 请示的结果里也带着,协议推不了状态时模型照样知道
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "等主人处理", desk.execute(DeskTools.ASK_OWNER, args("x")).text("owner_task"))
        state.value = TaskState.Cancelled
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "已取消", desk.ownerTaskLine())
        assertNull(PhoneDesk({ null }, FakeAsk()).ownerTaskLine())
    }

    @Test fun realtimePushesOnlyOwnerTaskChangesAsSilentContext() = runBlocking {
        val state = MutableStateFlow<TaskState>(TaskState.Running(step = 1, note = "a"))
        val cues = FakeCues()
        val desk = PhoneDesk({ state }, FakeAsk(), cues::cue, context = cues::context)
        val pushing = async { desk.pushOwnerTaskChanges() }
        yield()
        // 开场的状态已经在提示词里;同一种状态里的步数变化不推
        state.value = TaskState.Running(step = 2, note = "b")
        yield()
        assertTrue(cues.context.isEmpty())
        state.value = TaskState.Succeeded("已经订好")
        withTimeout(1_000) { while (cues.context.isEmpty()) yield() }
        val line = cues.context.single()
        assertTrue(line.startsWith(PhoneDesk.CUE_MARK))
        assertTrue(line.contains(PhoneDesk.OWNER_TASK_PREFIX + "已完成"))
        // 状态只当背景,不触发模型开口
        assertTrue(cues.sent.isEmpty())
        desk.close()
        withTimeout(1_000) { pushing.await() }
        assertFalse(pushing.isCancelled)
    }
}
