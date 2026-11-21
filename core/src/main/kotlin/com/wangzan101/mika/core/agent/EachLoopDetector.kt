package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb

/**
 * 逐条干活的识别:最近动词序列里「开详情→动作→返回」的 motif 完整重复 [REPEATS] 次时提醒一次 do_each。
 * 只看形状不看内容;失败、非屏动词都打断;模型自己用了 do_each 就解除武装。纯状态机,每任务一个实例。
 */
internal class EachLoopDetector {
    private val seq = ArrayDeque<Verb>()
    private var fired = false
    private var disarmed = false

    fun reset() = seq.clear()

    /** 喂一轮的动词([ok] 为这轮成功);该提醒返回 true(每任务最多一次)。 */
    fun feed(verb: Verb?, ok: Boolean): Boolean {
        if (verb == Verb.DoEach) disarmed = true
        if (verb == null || !ok || verb !in COUNTED) { reset(); return false }
        seq.addLast(verb)
        while (seq.size > WINDOW) seq.removeFirst()
        if (fired || disarmed) return false
        if (!repeating()) return false
        fired = true
        return true
    }

    /** 最近 [REPEATS]×l 个动词呈 l 周期,motif 恰含一个 Back 且至少一个 Tap(开详情→动作→返回的签名)。 */
    private fun repeating(): Boolean {
        for (l in 2..MAX_MOTIF) {
            if (seq.size < REPEATS * l) continue
            val window = seq.toList().takeLast(REPEATS * l)
            val motif = window.take(l)
            if (window.chunked(l).any { it != motif }) continue
            if (motif.count { it == Verb.Back } == 1 && Verb.Tap in motif) return true
        }
        return false
    }

    companion object {
        const val REPEATS = 3
        const val MAX_MOTIF = 5
        const val WINDOW = REPEATS * MAX_MOTIF
        private val COUNTED = setOf(
            Verb.Observe, Verb.ListElements, Verb.Tap, Verb.TypeText, Verb.ClearText,
            Verb.Scroll, Verb.Swipe, Verb.Back, Verb.PressKey, Verb.Wait, Verb.WaitFor,
        )
    }
}
