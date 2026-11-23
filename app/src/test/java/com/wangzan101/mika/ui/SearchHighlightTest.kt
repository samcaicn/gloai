package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.chat.ui.matchRanges

class SearchHighlightTest {
    @Test fun findsEveryOccurrenceIgnoringCase() {
        assertEquals(listOf(0..2, 4..6), matchRanges("Foo foo", listOf("foo")))
        assertEquals(listOf(0..1, 6..7), matchRanges("ab cd ab", listOf("AB")))
    }

    @Test fun mergesOverlappingAndAdjacentRanges() {
        assertEquals(listOf(0..3), matchRanges("aaaa", listOf("aa")))
        assertEquals(listOf(0..5), matchRanges("abcABC", listOf("abc")))
    }

    @Test fun emptyTextOrTermsHasNoRanges() {
        assertEquals(emptyList<IntRange>(), matchRanges("", listOf("a")))
        assertEquals(emptyList<IntRange>(), matchRanges("abc", emptyList()))
        assertEquals(emptyList<IntRange>(), matchRanges("abc", listOf("")))
    }
}
