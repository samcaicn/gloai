package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EachLoopDetectorTest {
    private fun EachLoopDetector.feedAll(verbs: List<Verb>, ok: Boolean = true): List<Boolean> =
        verbs.map { feed(it, ok) }

    @Test fun openActBackThreeTimesFiresOnceAtTheNinth() {
        val d = EachLoopDetector()
        val rounds = List(4) { listOf(Verb.Observe, Verb.Tap, Verb.Back) }.flatten()
        val fired = d.feedAll(rounds)
        assertEquals(fired.indices.filter { fired[it] }, listOf(8))
    }

    @Test fun tapWaitBackThreeTimesFiresOnceAtTheNinth() {
        val d = EachLoopDetector()
        // 开详情→等加载→返回 的逐条干活:wait 不断 streak(对齐 do_each.md 的含等待示例)
        val rounds = List(4) { listOf(Verb.Tap, Verb.Wait, Verb.Back) }.flatten()
        val fired = d.feedAll(rounds)
        assertEquals(listOf(8), fired.indices.filter { fired[it] })
    }

    @Test fun tapBackPairFiresAtTheSixth() {
        val d = EachLoopDetector()
        val fired = d.feedAll(List(4) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun feedScrollingNeverFires() {
        val d = EachLoopDetector()
        assertFalse(d.feedAll(List(6) { listOf(Verb.Observe, Verb.Scroll) }.flatten()).any { it })
    }

    @Test fun twoScreenCompareNeverFires() {
        val d = EachLoopDetector()
        // motif 里必须恰一个 Back 且至少一个 Tap:[Observe, Back] 不含 Tap,不触发
        assertFalse(d.feedAll(List(4) { listOf(Verb.Observe, Verb.Back) }.flatten()).any { it })
    }

    @Test fun failureBreaksTheStreak() {
        val d = EachLoopDetector()
        assertFalse(d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten()).any { it })
        assertFalse(d.feed(Verb.Tap, false))
        // 失败后重新攒满 3 次才触发(证明失败前的半截没算进去)
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun usedDoEachDisarms() {
        val d = EachLoopDetector()
        d.feed(Verb.DoEach, true)
        assertFalse(d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten()).any { it })
    }

    @Test fun nonScreenVerbBreaksTheStreak() {
        val d = EachLoopDetector()
        d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten())
        d.feed(Verb.WebSearch, true)
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun resetClearsHalfFormedStreak() {
        val d = EachLoopDetector()
        d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten())
        d.reset()
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }
}
