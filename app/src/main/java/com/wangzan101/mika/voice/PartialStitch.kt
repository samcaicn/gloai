package com.wangzan101.mika.voice

import kotlin.math.abs
import kotlin.math.floor

/**
 * 一段话说得长、识别服务只解最近一截(解码窗口)时,把一份份中间结果接成到此刻的全文。纯逻辑。
 *
 * [carried] 是已经留住的字,[tail] 是还在变的那截,界面上两截直接相连。窗口往后挪了一份新结果进来时:
 * 1. 先找新结果的开头落在已显示的字里的哪个位置:只比字母和数字(标点、空格、大小写不算),
 *    拿新结果开头 [LOOK] 个字逐个位置对,对上最多的那个位置算数(允许个别字被改写),一样多时取离按时间估的位置最近的;
 *    对上的不到一半、或少于 3 个,当没找到,退回按时间比例估。
 * 2. 重叠的那截里,开头 [HOLD] 个字沿用已显示的:新结果的开头是窗口的最前面,前文被截掉了,最容易解错、解重;
 *    同一截字在上一份里前面还有上文,更可信。往后找到两份连着 [RUN] 个字一样的地方才换成新结果
 *    (两份可以错开 [SLACK] 个字;不在英文单词或数字中间换)。[REACH] 个字之内找不到一样的,就从窗口开头整份换成新结果。
 *
 * 只用于按着时的预览;最终文字不经过这里。
 */
internal class PartialStitch {
    /** 已经留住的字:平时只往后加;新结果和已显示的对不上、或重叠里找不到一样的地方时,退回到窗口开头为止。 */
    var carried = ""
        private set

    /** 还在变的那截。 */
    var tail = ""
        private set

    /** 上一份结果的窗口开头落在 [carried] + [tail] 里的位置。 */
    private var anchor = 0
    private var lastStart = 0L
    private var lastDuration = 0L
    private var seen = false

    /** 换了一段话(上一段已经整段定下):从头来,不和上一段对齐。 */
    fun reset() {
        carried = ""; tail = ""; anchor = 0; lastStart = 0; lastDuration = 0; seen = false
    }

    /** 一份中间结果 [partial],它的窗口从 [startMs] 到 [durationMs](缺了就沿用上一份的);空的不算。 */
    fun feed(partial: String, startMs: Long?, durationMs: Long?) {
        if (partial.isEmpty()) return
        val start = startMs ?: lastStart
        if (seen && (start > lastStart || carried.isNotEmpty())) merge(partial, start)
        else { tail = partial; anchor = carried.length }
        seen = true
        lastStart = start
        lastDuration = durationMs ?: lastDuration
    }

    private fun merge(next: String, start: Long) {
        val shown = carried + tail
        val old = letters(shown)
        val new = letters(next)
        val from = old.indexOfFirst { it.index >= anchor }.let { if (it < 0) old.size else it }
        val span = lastDuration - lastStart
        val estimate = if (span > 0) from + (old.size - from) * ((start - lastStart).toDouble() / span) else from.toDouble()

        var at = -1
        var atScore = 0
        var atDistance = 0.0
        val look = minOf(LOOK, new.size)
        for (k in from until old.size) {
            val n = minOf(look, old.size - k)
            if (n < 2) break
            var score = 0
            for (i in 0 until n) if (old[k + i].char == new[i].char) score++
            if (score < minOf(3, n) || 2 * score < n) continue
            val distance = abs(k - estimate)
            if (at < 0 || score > atScore || (score == atScore && distance < atDistance)) { at = k; atScore = score; atDistance = distance }
        }
        if (at < 0) {
            // 对不上:按时间比例估窗口开头
            val k = floor(estimate + 0.5).toInt().coerceIn(from, old.size)
            val cut = if (k < old.size) old[k].index else shown.length
            carried = shown.substring(0, cut); tail = next; anchor = cut
            return
        }
        val cut = old[at].index
        for (j in HOLD until HOLD + REACH) {
            for (shift in SHIFTS) {
                val i = at + j + shift
                if (i < at || i + RUN > old.size || j + RUN > new.size) continue
                if ((0 until RUN).any { old[i + it].char != new[j + it].char }) continue
                if (midWord(shown, old[i].index) || midWord(next, new[j].index)) continue
                carried = shown.substring(0, old[i].index); tail = next.substring(new[j].index); anchor = cut
                return
            }
        }
        carried = shown.substring(0, cut); tail = next; anchor = cut
    }

    private class Letter(val index: Int, val char: Char)

    private fun letters(text: String): List<Letter> =
        text.withIndex().filter { it.value.isLetterOrDigit() }.map { Letter(it.index, it.value.lowercaseChar()) }

    /** [index] 处是不是英文单词或数字的中间(前一个字符也是英文字母或数字)。 */
    private fun midWord(text: String, index: Int): Boolean = index > 0 && latin(text[index]) && latin(text[index - 1])

    private fun latin(c: Char) = c.code < 128 && c.isLetterOrDigit()

    companion object {
        /** 拿新结果开头这么多个字去对位置。 */
        const val LOOK = 12

        /** 重叠那截开头这么多个字沿用已显示的。 */
        const val HOLD = 8

        /** 之后这么多个字之内找两份一样的地方。 */
        const val REACH = 12

        /** 连着这么多个字一样才换。 */
        const val RUN = 2

        /** 两份最多错开这么多个字。 */
        const val SLACK = 2

        private val SHIFTS = (-SLACK..SLACK).sortedBy { abs(it) }
    }
}
