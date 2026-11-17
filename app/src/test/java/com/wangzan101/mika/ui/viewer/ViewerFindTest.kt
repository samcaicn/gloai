package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.ui.MarkdownModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFindTest {
    @Test fun textHitsKnowWhichChunkTheyAreIn() {
        val finds = findInChunks(listOf("a ok b", "ok ok"), "ok")
        assertEquals(listOf(TextHit(0, 2..3), TextHit(1, 0..1), TextHit(1, 3..4)), finds.hits)
        assertEquals(mapOf(0 to listOf(2..3), 1 to listOf(0..1, 3..4)), finds.byChunk)
        assertFalse(finds.more)
    }

    @Test fun textHitsAreCappedAndSaySo() {
        val finds = findInChunks(listOf("a".repeat(1_005)), "a")
        assertEquals(1_000, finds.hits.size)
        assertTrue(finds.more)
    }

    @Test fun emptyQueryHasNoHits() {
        assertEquals(emptyList<TextHit>(), findInChunks(listOf("anything"), "").hits)
        assertEquals(emptyList<BlockHit>(), findInBlocks(MarkdownModel.parse("# 标题"), "").hits)
    }

    @Test fun markdownHitsPointAtTheBlockAndThePiece() {
        val blocks = MarkdownModel.parse("# 标题\n\n正文里的 foo 和 foo\n\n```\nfoo()\n```")
        val finds = findInBlocks(blocks, "foo")
        assertEquals(listOf(1, 1, 2), finds.hits.map { it.block })
        val paragraph = finds.hits.first().text
        assertTrue(paragraph.toString().contains("正文里的"))
        assertEquals(listOf(0, 1, 0), finds.hits.map { it.occurrence })
        // 同一段文字里两处命中认的是同一段;代码块是另一段
        assertEquals(finds.hits[0].text, finds.hits[1].text)
        assertTrue(finds.hits[2].text !== finds.hits[0].text)
    }
}
