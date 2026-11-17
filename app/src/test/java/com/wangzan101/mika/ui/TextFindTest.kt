package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFindTest {
    @Test fun findsEveryOccurrenceIgnoringCase() {
        assertEquals(listOf(0..2, 4..6, 8..10), findRanges("Foo foo FOO", "foo"))
        assertEquals(listOf(1..2), findRanges("在文件里查找", "文件"))
    }

    @Test fun adjacentOccurrencesStaySeparate() {
        assertEquals(listOf(0..1, 2..3), findRanges("aaaa", "aa"))
    }

    @Test fun stopsAtTheLimit() {
        assertEquals(listOf(0..0, 1..1, 2..2), findRanges("aaaa", "a", limit = 3))
        assertEquals(emptyList<IntRange>(), findRanges("aaaa", "a", limit = 0))
    }

    @Test fun emptyQueryFindsNothing() {
        assertEquals(emptyList<IntRange>(), findRanges("abc", ""))
        assertEquals(emptyList<IntRange>(), findRanges("", "a"))
    }
}
