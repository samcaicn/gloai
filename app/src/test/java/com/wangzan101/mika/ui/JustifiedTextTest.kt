package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 中文两端对齐只在字与字之间摊间距,决定「哪里能摊」的是 [cjkGapRanges]。 */
class JustifiedTextTest {
    private fun ranges(text: String) = cjkGapRanges(text, 0, text.length)

    @Test
    fun gapsBetweenHanCharacters() {
        // 四个字之间有 3 个相邻字对
        assertEquals(listOf(0..0, 1..1, 2..2), ranges("中文字数"))
    }

    @Test
    fun noGapsAroundPunctuation() {
        // 「，」两侧不摊:中文标点自带全角空隙,再拉就散了
        assertEquals(listOf(0..0), ranges("中文，字"))
    }

    @Test
    fun noGapsAroundLatinAndDigits() {
        assertEquals(emptyList<IntRange>(), ranges("App123"))
        // 汉字与英文相邻处也不摊,英文单词内部更不能被拉开
        assertEquals(emptyList<IntRange>(), ranges("读PDF"))
        assertEquals(listOf(1..1), ranges("a中文b"))
    }

    @Test
    fun kanaCounted() {
        assertEquals(listOf(0..0, 1..1, 2..2), ranges("かなカナ"))
    }

    @Test
    fun surrogatePairsStayWhole() {
        // 扩展 B 区的字是代理对,区间不能劈开
        val text = "𠀀𠀁" // 𠀀𠀁
        assertEquals(listOf(0..1), ranges(text))
    }

    @Test
    fun clampedToLineWindow() {
        val text = "一二三四五"
        assertEquals(listOf(1..1, 2..2), cjkGapRanges(text, 1, 4))
    }
}
