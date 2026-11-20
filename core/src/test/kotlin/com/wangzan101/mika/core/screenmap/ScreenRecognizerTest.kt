package com.wangzan101.mika.core.screenmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecognizerTest {
    private fun node(id: String, vararg anchors: String) = ScreenNode(id = id, app = "com.example.app", anchors = anchors.associateWith { 1 })

    @Test fun fullMatchRecognizesScreen() {
        val n = node("a", "关于本机", "设置", "更多")
        val r = ScreenRecognizer.identify(setOf("关于本机", "设置", "更多"), listOf(n))
        assertNotNull(r)
        assertEquals("a", r!!.id)
        assertEquals(setOf("关于本机", "设置", "更多"), r.matched)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test fun missingOneAnchorStillRecognizes() {
        // 一页两种结构的「缺 banner」：少一个骨架锚点仍认对（覆盖率 < 1.0 容忍）
        val n = node("a", "关于本机", "设置", "更多")
        val r = ScreenRecognizer.identify(setOf("关于本机", "设置"), listOf(n))
        assertNotNull(r)
        assertEquals("a", r!!.id)
        assertEquals(setOf("关于本机", "设置"), r.matched)
        assertTrue(r.confidence >= 0.5)
    }

    @Test fun commonWordAcrossScreensDoesNotMergeThem() {
        // 「确定」在两屏都出现：只看到这个通用字，稀有度把置信度压到阈值下，不认成任何一屏
        val a = node("a", "保存", "确定")
        val b = node("b", "取消", "确定")
        assertNull(ScreenRecognizer.identify(setOf("确定"), listOf(a, b)))
    }

    @Test fun rarityDisambiguatesWhenUniqueAnchorAlsoPresent() {
        // 通用字 + 只属于 A 的「保存」→ 认 A，不认 B
        val a = node("a", "保存", "确定")
        val b = node("b", "取消", "确定")
        val r = ScreenRecognizer.identify(setOf("确定", "保存"), listOf(a, b))
        assertNotNull(r)
        assertEquals("a", r!!.id)
    }

    @Test fun screenNotInGraphReturnsNull() {
        val n = node("a", "关于本机", "设置")
        assertNull(ScreenRecognizer.identify(setOf("某个全新界面的标题"), listOf(n)))
    }

    @Test fun onlyFuzzyAnchorsReturnsNull() {
        // 两屏共享「确定」「返回」，只看到这些模糊字 → 低于阈值 → 不知道
        val a = node("a", "确定", "返回", "关于本机")
        val b = node("b", "确定", "返回", "设置")
        assertNull(ScreenRecognizer.identify(setOf("确定", "返回"), listOf(a, b)))
    }
}
