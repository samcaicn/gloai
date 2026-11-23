package com.wangzan101.mika.core.learn.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSlotsTest {
    private fun route(template: String, slots: Int) = Route("r", template, slots, emptyList(), emptyMap(), emptyList(), lastUsedMs = 0)

    @Test fun bindsExactOriginalTextAndOutOfOrderSlotNumbers() {
        assertEquals(listOf("hello world", "张三丰"),
            RouteSlots.extract(route("给 {2} 发消息说 {1}", 2), "给 张三丰 发消息说 hello world"))
        assertEquals(emptyList<String>(), RouteSlots.extract(route("打开设置", 0), "打开 设置"))
    }

    @Test fun preservesLeadingAndTrailingWhitespaceInsideOriginalParameterSpans() {
        val content = "    indented\n"
        assertEquals(listOf(content), RouteSlots.extract(route("把以下内容写入备忘录：{1}", 1),
            "把以下内容写入备忘录：$content"))
        assertEquals(listOf("\n  hello world\n", "张三"), RouteSlots.extract(route("给{2}发送「{1}」", 2),
            "给张三发送「\n  hello world\n」"))
        assertEquals(listOf("  hello  "), RouteSlots.extract(route("写{1}然后保存", 1), "写  hello  然后保存"))
    }

    @Test fun changedWhitespaceInFixedSegmentsFallsBackInsteadOfGuessingBoundaries() {
        assertNull(RouteSlots.extract(route("在 备忘录里写{1}", 1), "在备忘录里写内容"))
        assertNull(RouteSlots.extract(route("写{1}然后 保存", 1), "写内容然后保存"))
        assertNull(RouteSlots.extract(route("写{1} {2}", 2), "写内容 其他"))
    }

    @Test fun ambiguousBoundariesAndRepeatedValuesAreNotGuessed() {
        assertNull(RouteSlots.extract(route("给{1}发消息说{2}", 2), "给张三发消息说李四发消息说你好"))
        assertNull(RouteSlots.extract(route("给{1}发消息说{2}", 2), "给好发消息说好"))
        assertNull(RouteSlots.extract(route("写{1}{2}", 2), "写甲乙丙"))
        assertNull(RouteSlots.extract(route("写{1}然后{1}", 1), "写甲然后甲"))
    }

    @Test fun rejectsMissingBoundariesExtraInstructionsAndExcessiveSearch() {
        assertNull(RouteSlots.extract(route("打开设置", 0), "打开设置并关闭蓝牙"))
        assertNull(RouteSlots.extract(route("写{1}然后保存", 1), "写内容然后保存并删除"))
        assertNull(RouteSlots.extract(route("写{1}", 1), "写"))
        assertNull(RouteSlots.extract(route("写{1}", 1), "写" + "a".repeat(8_001)))
        assertNull(RouteSlots.extract(route("写{1}然后{2}保存", 2), "写内容" + "然后".repeat(200)))
    }
}
