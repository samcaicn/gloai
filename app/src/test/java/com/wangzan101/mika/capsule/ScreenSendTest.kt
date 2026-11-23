package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.chat.ui.ScreenCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双击还是单击、截屏预览发进哪个对话、带屏幕的话在列表里怎么称呼。 */
class ScreenSendTest {
    @get:Rule val texts = ChineseTexts()

    private fun taps() = DoubleTap(300, slopPx = 20f)

    @Test fun secondTapWithinTimeoutIsDouble() {
        val t = taps()
        assertEquals(DoubleTap.Tap.First, t.tap(1_000, 100f, 200f, source = 1))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_250, 110f, 210f, source = 1))
        // 双击之后到时也不再算单击
        assertFalse(t.settle(1_400))
    }

    @Test fun distantSecondTapStartsANewSingleTap() {
        val t = taps()
        t.tap(1_000, 100f, 200f, source = 1)
        assertEquals(DoubleTap.Tap.First, t.tap(1_250, 140f, 200f, source = 1))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_400, 135f, 205f, source = 1))
    }

    @Test fun secondTapFromAnotherSourceStartsANewSingleTap() {
        val t = taps()
        t.tap(1_000, 100f, 200f, source = 1)
        assertEquals(DoubleTap.Tap.First, t.tap(1_250, 100f, 200f, source = 2))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_400, 105f, 200f, source = 2))
    }

    @Test fun singleTapSettlesAfterTimeout() {
        val t = taps()
        assertEquals(DoubleTap.Tap.First, t.tap(1_000, 0f, 0f, source = 1))
        assertFalse(t.settle(1_200))
        assertTrue(t.settle(1_300))
        assertFalse("只算一次", t.settle(1_400))
    }

    @Test fun lateSecondTapStartsOver() {
        val t = taps()
        t.tap(1_000, 0f, 0f, source = 1)
        assertEquals(DoubleTap.Tap.First, t.tap(1_400, 0f, 0f, source = 1))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_600, 0f, 0f, source = 1))
    }

    @Test fun clockGoingBackStartsOver() {
        val t = taps()
        t.tap(1_000, 0f, 0f, source = 1)
        assertEquals(DoubleTap.Tap.First, t.tap(900, 0f, 0f, source = 1))
    }

    @Test fun resetForgetsTheFirstTap() {
        val t = taps()
        t.tap(1_000, 0f, 0f, source = 1)
        t.reset()
        assertFalse(t.settle(2_000))
        assertEquals(DoubleTap.Tap.First, t.tap(2_100, 0f, 0f, source = 1))
    }

    private fun run(key: String, conversation: String, state: TaskState, kind: CapsuleKind, onScreen: Boolean = false) =
        CardRun(CapsuleRun(key, conversation, state, goal = "g", onScreen = onScreen), kind, "")

    @Test fun screenGoesToTheConversationTheCardShows() {
        val running = run("a", "c1", TaskState.Running(1, ""), CapsuleKind.Running, onScreen = true)
        val done = run("b", "c2", TaskState.Succeeded("好"), CapsuleKind.Done)
        assertEquals("c1", CapsuleCards.conversationOf(CapsuleCards.of(listOf(running), null)!!))
        assertEquals("c2", CapsuleCards.conversationOf(CapsuleCards.of(listOf(done), null)!!))
        // 空闲卡片:接着最近的对话;选了新对话时由调用方新开
        assertEquals("c9", CapsuleCards.conversationOf(CardView.Chat("c9", title = "t")))
        assertNull(CapsuleCards.conversationOf(CardView.Chat("c9", fresh = true)))
        assertNull(CapsuleCards.conversationOf(CardView.Chat(null)))
        // 好几件事时卡片只说排在最前的那件,屏幕发给它所在的对话
        val several = CapsuleCards.of(listOf(running, done))!!
        assertEquals(CardInputKind.Steer, CapsuleCards.input(several)?.kind)
        assertEquals("c1", CapsuleCards.conversationOf(several))
    }

    @Test fun inputKindDecidesHowTheScreenIsSent() {
        val running = run("a", "c1", TaskState.Running(1, ""), CapsuleKind.Running, onScreen = true)
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(running), null)!!)!!.kind)
        val asking = run("b", "c2", TaskState.WaitingInput("记一笔吗?", listOf("记一笔", "不用")), CapsuleKind.NeedAnswer)
        assertEquals(CardInputKind.Answer, CapsuleCards.input(CapsuleCards.of(listOf(asking), null)!!)!!.kind)
        assertEquals(CardInputKind.Start, CapsuleCards.input(CardView.Chat(null))!!.kind)
    }

    @Test fun plainTitles() {
        assertEquals("第三条有没有坑?", ScreenCopy.plain("[[screen:s1]]\n第三条有没有坑?"))
        assertEquals(ScreenCopy.TAG, ScreenCopy.plain("[[screen:s1]]"))
        assertEquals("普通的一句话", ScreenCopy.plain("普通的一句话"))
    }
}
