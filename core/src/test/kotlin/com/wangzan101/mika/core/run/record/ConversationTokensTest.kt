package com.wangzan101.mika.core.run.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTokensTest {
    private fun m(id: String, input: Long, output: Long, noUsage: Int = 0) =
        RunMetrics(runId = id, inputTokens = input, outputTokens = output, callsWithoutUsage = noUsage)

    @Test fun sumsTurnsLikeTheTimelineLine() {
        val all = listOf(m("a", 1_000, 200), m("b", 30_000, 1_500)).associateBy { it.runId }
        val t = ConversationTokens.of(listOf("a", "b"), all)!!
        assertEquals(32_700, t.tokens)
        assertEquals(2, t.turns)
        assertFalse(t.partial)
        // 和「查看过程」顶上那一行同一个数
        assertEquals(all.getValue("b").tokens, ConversationTokens.turn(all["b"]))
    }

    @Test fun sameRunCountsOnce() {
        val all = mapOf("a" to m("a", 100, 50))
        assertEquals(150, ConversationTokens.of(listOf("a", "a"), all)!!.tokens)
    }

    @Test fun missingOrUnreportedTurnsMakeItPartial() {
        val all = listOf(m("a", 1_000, 200), m("c", 500, 0, noUsage = 2)).associateBy { it.runId }
        val t = ConversationTokens.of(listOf("a", "b", "c"), all)!!
        assertEquals(1_700, t.tokens)
        assertEquals(2, t.missing)
        assertTrue(t.partial)
    }

    @Test fun nothingKnownShowsNothing() {
        assertNull(ConversationTokens.of(emptyList(), emptyMap()))
        assertNull(ConversationTokens.of(listOf("a"), emptyMap()))
        assertNull(ConversationTokens.of(listOf("a"), mapOf("a" to m("a", 0, 0, noUsage = 1))))
        assertNull(ConversationTokens.turn(null))
        assertNull(ConversationTokens.turn(m("a", 0, 0)))
    }
}
