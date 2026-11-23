package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.screen.uistate.Hant
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiStates
import kotlin.math.abs

/** Recognizes editing keys on a complete, spatially coherent decimal keypad. */
internal object NumericKeypadEditing {
    private val keyNames = (0..9).map(Int::toString) + "."
    private val backspaceNames = setOf("删除", "退格", "backspace", "delete")
    private val confirmNames = setOf("确定", "完成", "ok", "confirm")
    private val priceFieldNames = listOf("价格设置", "原价设置", "库存设置")

    fun edits(target: UiElement, screen: ScreenSnapshot): Boolean {
        if (screen.changedDuringRead || screen.protected || !screen.hasWindow) return false
        val targetName = name(target)
        if (targetName !in backspaceNames && targetName !in confirmNames) return false
        if (!validButton(target)) return false
        if (target.resourceId?.let(IrreversibleDetector::matchResource) != null) return false
        val keys = screen.elements.filter { validButton(it) && name(it) in keyNames }
            .groupBy(::name)
        if (keyNames.any { keys[it]?.size != 1 }) return false
        val digits = keyNames.associateWith { keys.getValue(it).single() }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        val columns = rows.first().map { x(digits.getValue(it)) }
        val rowY = rows.map { row -> row.map { y(digits.getValue(it)) }.average().toInt() }
        if (columns.zipWithNext().any { (left, right) -> right - left < 60 }) return false
        if (rowY.zipWithNext().any { (top, bottom) -> bottom - top !in 30..200 }) return false
        for ((rowIndex, row) in rows.withIndex()) {
            for ((column, key) in row.withIndex()) {
                val element = digits.getValue(key)
                if (abs(x(element) - columns[column]) > 30 || abs(y(element) - rowY[rowIndex]) > 30) return false
            }
        }
        val zero = digits.getValue("0")
        val dot = digits.getValue(".")
        if (abs(x(zero) - columns[1]) > 30 || abs(x(dot) - columns[0]) > 30) return false
        if (abs(y(zero) - y(dot)) > 30 || y(zero) - rowY.last() !in 30..200) return false
        val firstKeyTop = digits.values.minOf { it.bounds[1] }
        if (firstKeyTop < 500 || digits.values.maxOf { it.bounds[3] } - firstKeyTop > 500) return false

        val rightEdge = digits.values.maxOf { it.bounds[2] }
        val maxWidth = digits.values.maxOf { it.bounds[2] - it.bounds[0] }
        val maxHeight = digits.values.maxOf { it.bounds[3] - it.bounds[1] }
        if (target.bounds[0] < rightEdge || target.bounds[2] - target.bounds[0] > maxWidth * 2) return false
        if (target.bounds[3] - target.bounds[1] > maxHeight * 3) return false

        if (targetName in backspaceNames) return y(target) in (rowY.first() - 30)..(rowY[1] + 30)
        if (y(target) !in (rowY.last() - 30)..(y(zero) + 30)) return false
        if (priceFieldNames.any { field -> screen.elements.none {
                name(it).startsWith(field) && it.bounds.size == 4 && it.bounds[3] < firstKeyTop
            } }) return false
        if (IrreversibleDetector.paymentPage(screen) != null) return false
        // Payment/order/object-delete controls keep confirmation conservative.
        return screen.elements.none {
            it !== target && !(name(it) in backspaceNames && validButton(it) &&
                it.resourceId?.let(IrreversibleDetector::matchResource) == null &&
                it.bounds[0] >= rightEdge && y(it) in (rowY.first() - 30)..(rowY[1] + 30)) &&
                (IrreversibleDetector.matchName(it.name) != null ||
                    it.resourceId?.let(IrreversibleDetector::matchResource) != null)
        }
    }

    private fun validButton(element: UiElement): Boolean = element.clickable && !element.editable &&
        UiStates.DISABLED !in element.states &&
        element.bounds.size == 4 && element.bounds[0] in 0..998 && element.bounds[1] in 0..998 &&
        element.bounds[2] in (element.bounds[0] + 1)..999 &&
        element.bounds[3] in (element.bounds[1] + 1)..999

    private fun name(element: UiElement): String = Hant.fold(element.name).filterNot(Char::isWhitespace).lowercase()
    private fun x(element: UiElement): Int = (element.bounds[0] + element.bounds[2]) / 2
    private fun y(element: UiElement): Int = (element.bounds[1] + element.bounds[3]) / 2
}
