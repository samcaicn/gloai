package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.TextField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 打字的节奏:前缀越来越长、最后是全文;字不拆开;中日韩文字一个词一个词上屏;长文分段且有总时长上限;分段写入不成就退回整段写入。 */
class TypingPacingTest {
    private val limits = TypingPacing.Limits()

    private fun checkShape(text: String, steps: List<TypingPacing.Step>) {
        assertEquals(text.length, steps.last().end)
        steps.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", a.end < b.end) }
        // 段的边界不落在一个字的中间
        val units = TypingPacing.units(text).toSet() + text.length
        steps.forEach { assertTrue("${it.end} in $text", it.end in units) }
        steps.forEach { assertTrue(it.delayMs >= 0) }
    }

    @Test fun instantWritesTheWholeTextAtOnce() {
        assertEquals(listOf(TypingPacing.Step(5, 0)), TypingPacing.Instant.plan("hello"))
        assertFalse(TypingPacing.Instant.paced)
    }

    @Test fun latinTextGoesOutOneCharacterAtATimeWithBoundedDelays() {
        repeat(30) { seed ->
            val text = "hello world"
            val steps = TypingPacing.human(Random(seed)).plan(text)
            checkShape(text, steps)
            assertEquals(text.length, steps.size)
            val most = limits.latinMs.last + limits.pauseMs.last
            steps.forEach { assertTrue("${it.delayMs}", it.delayMs in limits.latinMs.first..most) }
        }
    }

    @Test fun cjkTextGoesOutAWordAtATime() {
        repeat(30) { seed ->
            val text = "今天天气很好，我们去公园散步吧"
            val steps = TypingPacing.human(Random(seed)).plan(text)
            checkShape(text, steps)
            // 汉字一段 1–3 个,标点自成一段
            var start = 0
            for (s in steps) {
                val chunk = text.substring(start, s.end)
                if (chunk == "，") assertTrue(s.delayMs >= limits.latinMs.first)
                else {
                    assertTrue(chunk, chunk.length in limits.cjkGroup)
                    assertTrue("${s.delayMs}", s.delayMs >= chunk.length * limits.cjkMs.first)
                }
                start = s.end
            }
        }
    }

    @Test fun charactersAreNeverSplit() {
        // a、笑脸(代理对)、b、点赞加肤色、c、一家人(零宽连接)、e 加附加的重音、中
        val text = "a😀b👍🏽c👨‍👩‍👧é中"
        assertEquals(listOf(0, 1, 3, 4, 8, 9, 17, 19), TypingPacing.units(text))
        repeat(20) { seed -> checkShape(text, TypingPacing.human(Random(seed)).plan(text)) }
    }

    @Test fun longTextIsChunkedAndCappedInTotal() {
        val latin = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        val cjk = "我们明天上午十点在公司门口见面，记得带上资料。".repeat(20)
        for (text in listOf(latin, cjk)) {
            for (l in listOf(TypingPacing.Limits(), TypingPacing.Limits.CAREFUL)) {
                val steps = TypingPacing.human(Random(7), l).plan(text)
                checkShape(text, steps)
                assertTrue("${steps.size}", steps.size <= l.maxSteps)
                // 按比例缩短时取整,至多每段差 1 毫秒
                assertTrue("${steps.sumOf { it.delayMs }}", steps.sumOf { it.delayMs } <= l.maxTotalMs + steps.size)
            }
        }
        // 短的不缩
        val short = TypingPacing.human(Random(3)).plan("ok")
        assertTrue(short.sumOf { it.delayMs } >= 2 * limits.latinMs.first)
    }

    @Test fun carefulIsSlower() {
        val text = "我想问一下这个还在吗"
        val normal = (0 until 20).sumOf { TypingPacing.human(Random(it)).plan(text).sumOf { s -> s.delayMs } }
        val careful = (0 until 20).sumOf { TypingPacing.human(Random(it), TypingPacing.Limits.CAREFUL).plan(text).sumOf { s -> s.delayMs } }
        assertTrue("$normal < $careful", normal < careful)
    }

    /** 记下写入的输入框;[accepts] 为假时像不接受半截字的框(读回总是空的)。 */
    private class Field(private val accepts: Boolean = true, private val writable: Boolean = true) : TextField {
        val writes = mutableListOf<String>()
        private var text = ""
        override fun read(): String? = if (accepts) text else ""
        override fun write(text: String): Boolean {
            if (!writable) return false
            writes += text
            this.text = text
            return true
        }
    }

    @Test fun leadInWritesGrowingPrefixesAndLeavesTheWholeTextToTheCaller() = runTest {
        val text = "你好，world"
        // 同一个种子排出的段和写入时的一样
        val plan = TypingPacing.human(Random(1)).plan(text)
        val pacing = TypingPacing.human(Random(1))
        val field = Field()
        val slept = mutableListOf<Long>()
        assertTrue(pacing.leadIn(field, text) { slept += it })
        assertEquals(plan.dropLast(1).map { text.substring(0, it.end) }, field.writes)
        assertEquals(plan.map { it.delayMs }, slept)
    }

    @Test fun leadInStopsWhenTheFieldRejectsPartialText() = runTest {
        val rejecting = Field(accepts = false)
        assertFalse(TypingPacing.human(Random(1)).leadIn(rejecting, "hello") {})
        assertEquals(listOf("h"), rejecting.writes)
        val locked = Field(writable = false)
        assertFalse(TypingPacing.human(Random(1)).leadIn(locked, "hello") {})
        // 不分段时什么也不写,调用方照旧整段写入
        val instant = Field()
        assertFalse(TypingPacing.Instant.leadIn(instant, "hello") { error("no wait") })
        assertTrue(instant.writes.isEmpty())
    }

    @Test fun passwordAndPasteOnlyWebFieldsAreWrittenAtOnce() {
        val human = TypingPacing.human(Random(1))
        assertSame(human, human.forField(web = false, secret = false))
        assertSame(TypingPacing.Instant, human.forField(web = false, secret = true))
        assertSame(TypingPacing.Instant, human.forField(web = true, secret = false))
    }
}
