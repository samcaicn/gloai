package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 一次就读到空树时要再读一次(窗口刚切走、正在重绘、无障碍还没吐出节点都会让这一屏暂时是空的);
 * 两次都空才判不可用,免得瞬时读不到就把整屏判死、把模型赶去整页 OCR。
 */
class BlankReadRetryTest {
    /** [blankFirst] 为真:第一次读是空树,之后正常;[blankAlways] 为真:一直是空树。 */
    private class FlickerScreen(private val blankFirst: Boolean, private val blankAlways: Boolean = false) : ScreenPort by FakeScreen() {
        var snapshots = 0
        override suspend fun snapshot(): ScreenSnapshot {
            val n = ++snapshots
            val empty = blankAlways || (blankFirst && n == 1)
            val els = if (empty) emptyList() else (1..8).map { i ->
                UiElement("@e$i", "TextView", "第${i}行", 0, 0, listOf(50, 100 + i * 60, 950, 150 + i * 60), null, true, false, false)
            }
            return ScreenSnapshot("s$n", "com.example", els, 1080, 2400)
        }
        override suspend fun peek() = snapshot()
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun blankReadIsRetriedOnceAndThenTrusted() = runTest {
        val s = FlickerScreen(blankFirst = true)
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(2, s.snapshots)
        // 第二次读到的那一屏是按 ref 能办事的:不写 treeQuality,也不让模型改走 OCR
        assertNull(r.content.s("treeQuality"))
        assertNull(r.content.s("imageReason"))
    }

    @Test fun stillBlankAfterRetryIsUnusable() = runTest {
        val s = FlickerScreen(blankFirst = true, blankAlways = true)
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(2, s.snapshots)
        assertEquals("unusable", r.content.s("treeQuality"))
    }

    @Test fun readableFirstReadIsNotReadTwice() = runTest {
        val s = FlickerScreen(blankFirst = false)
        toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(1, s.snapshots)
    }
}
