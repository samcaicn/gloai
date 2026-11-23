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
    /** 记下问过什么、撤过几次;[reply] 模拟主人在通知里回了一句。 */
    private class FakeAsk(var available: Boolean = true) : OwnerAsk {
        val questions = mutableListOf<String>()
        var cancels = 0
        private var onReply: ((String) -> Unit)? = null

        override fun ask(question: String, onReply: (String) -> Unit): Boolean {
            if (!available) return false
            questions += question
            this.onReply = onReply
            return true
        }

        override fun cancel() {
            cancels++
        }

        /** 不管有没有撤,迟到的回复照样送进来:忽略它是 PhoneDesk 的事。 */
        fun reply(text: String) = onReply!!.invoke(text)
    }

    /** 收下的通知;[ended] 留着由测试决定说没说完。 */
    private class FakeNotices(var accept: Boolean = true) {
        val sent = mutableListOf<String>()
        val ended = mutableListOf<(Boolean) -> Unit>()
        fun send(text: String, end: (Boolean) -> Unit): Boolean {
            if (!accept) return false
            sent += text
            ended += end
            return true
        }
    }

    private fun args(question: String) = buildJsonObject { put("question", question) }
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content

    @Test fun phoneHasOnlyAskOwner() = runBlocking {
        assertEquals(setOf(DeskTools.ASK_OWNER), DeskTools.specs(DeskToolSet.Phone).map { it.getValue("name").jsonPrimitive.content }.toSet())
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask)
        for (name in listOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL, "web_search", "check_tasks", "send_dtmf")) {
            assertEquals(name, "unsupported", desk.execute(name, buildJsonObject { put("words", "帮我订个位"); put("title", "x") }).status())
        }
        assertTrue(ask.questions.isEmpty())
    }

    @Test fun askOwnerReturnsImmediatelyAndRelaysReply() = runBlocking {
        val ask = FakeAsk()
        val notices = FakeNotices()
        val desk = PhoneDesk({ null }, ask, notices::send)
        val result = withTimeout(1_000) { desk.execute(DeskTools.ASK_OWNER, args("对方问能不能改到周日")) }
        assertEquals("asked", result.status())
        assertEquals("Tell the caller you are checking and will reply shortly.", result.getValue("next").jsonPrimitive.content)
        assertEquals(listOf("对方问能不能改到周日"), ask.questions)
        assertTrue(notices.sent.isEmpty())
        ask.reply("周日也行，下午两点以后")
        assertEquals(listOf("主人回复：周日也行，下午两点以后"), notices.sent)
    }

    @Test fun secondAskWhilePendingIsBusy() = runBlocking {
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, FakeNotices()::send)
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
        val notices = FakeNotices()
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, notices::send) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("要不要加一个人"))
        desk.close()
        assertEquals(1, ask.cancels)
        ask.reply("加")
        assertTrue(notices.sent.isEmpty())
        assertTrue(unrelayed.isEmpty())
        assertEquals("unavailable", desk.execute(DeskTools.ASK_OWNER, args("还在吗")).status())
        assertEquals(listOf("要不要加一个人"), ask.questions)
    }

    @Test fun askWithoutAReachableOwnerSaysSoAndFreesTheSlot() = runBlocking {
        val ask = FakeAsk(available = false)
        val desk = PhoneDesk({ null }, ask)
        assertEquals("unavailable", desk.execute(DeskTools.ASK_OWNER, args("能不能付定金")).status())
        ask.available = true
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("能不能付定金")).status())
        assertEquals("invalid_arguments", PhoneDesk({ null }, FakeAsk()).execute(DeskTools.ASK_OWNER, args("  ")).status())
    }

    @Test fun cutReplyIsResentOnceThenKeptInTheTranscript() = runBlocking {
        val ask = FakeAsk()
        val notices = FakeNotices()
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, notices::send) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("几点到"))
        ask.reply("七点")
        notices.ended.single().invoke(false)
        assertEquals(listOf("主人回复：七点", "主人回复：七点"), notices.sent)
        notices.ended[1].invoke(false)
        assertEquals(2, notices.sent.size)
        assertEquals(listOf("七点"), unrelayed)

        // 说出去了就不记
        desk.execute(DeskTools.ASK_OWNER, args("几个人"))
        ask.reply("四个")
        notices.ended.last().invoke(true)
        assertEquals(listOf("七点"), unrelayed)

        // 一次也收不下:重试一次,再记下来
        notices.accept = false
        desk.execute(DeskTools.ASK_OWNER, args("要包间吗"))
        ask.reply("不要")
        assertEquals(listOf("七点", "不要"), unrelayed)
    }

    @Test fun ownerTaskStateIsRenderedForTheDeskContext() {
        val state = MutableStateFlow<TaskState>(TaskState.Running(step = 3, note = "phone_call_dial"))
        val desk = PhoneDesk({ state }, FakeAsk())
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "进行中", desk.ownerTaskLine())
        state.value = TaskState.WaitingInput("哪天?")
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "等主人处理", desk.ownerTaskLine())
        state.value = TaskState.Cancelled
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "已取消", desk.ownerTaskLine())
        assertNull(PhoneDesk({ null }, FakeAsk()).ownerTaskLine())
    }

    @Test fun realtimePushesOnlyOwnerTaskChanges() = runBlocking {
        val state = MutableStateFlow<TaskState>(TaskState.Running(step = 1, note = "a"))
        val notices = FakeNotices()
        val desk = PhoneDesk({ state }, FakeAsk(), notices::send)
        val pushing = async { desk.pushOwnerTaskChanges() }
        yield()
        // 开场的状态已经在提示词里;同一种状态里的步数变化不推
        state.value = TaskState.Running(step = 2, note = "b")
        yield()
        assertTrue(notices.sent.isEmpty())
        state.value = TaskState.Succeeded("已经订好")
        withTimeout(1_000) { while (notices.sent.isEmpty()) yield() }
        assertEquals(listOf(PhoneDesk.OWNER_TASK_PREFIX + "已完成"), notices.sent)
        desk.close()
        withTimeout(1_000) { pushing.await() }
        assertFalse(pushing.isCancelled)
    }
}
