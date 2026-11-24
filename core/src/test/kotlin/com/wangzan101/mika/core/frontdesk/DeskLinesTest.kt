package com.wangzan101.mika.core.frontdesk

import org.junit.Assert.assertEquals
import org.junit.Test

class DeskLinesTest {
    // 单词里的撇号不是引号:「don't」「it's」之间那段不能被当成引号段剥掉
    @Test
    fun apostrophesInsideWordsAreNotQuotes() {
        assertEquals("I don't think it's ready", stripDetails("I don't think it's ready"))
    }

    @Test
    fun singleQuotedDetailsAreStillStripped() {
        assertEquals("Opened the event", stripDetails("Opened the event 'Dinner with Sam'"))
    }

    @Test
    fun quotedTitlesAndCoordinatesAreStripped() {
        assertEquals("打开了 点了", stripDetails("打开了「周五晚饭」 点了 [120, 340]"))
    }
}
