package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Q1+Q2 原型：`do_each` 循环里「定位条目 + 找下一个、跳过已做」的稳定键，与「滚动找更多」。
 *
 * 结论（Q1 已验证、Q2 继续）：
 * - `ref` 每次读屏重排，不能跨快照认「同一条」；
 * - `resourceId` 在真实 a11y 树里存在，但列表条目同构（同一 widget 类型、resourceId 一模一样），不能区分第 1 条和第 2 条；
 * - 跨快照、跨滚动稳定的身份是 `contentKey = role|name`（内容）；
 * - 同名条目靠 `ordinal`（本快照里同名出现的序号）在**同一屏内**区分，但 ordinal 是位置依赖的，滚动后失效，不能跨屏用。
 *
 * 这是未接线的原型，只为验证稳定键与滚动方案；`do_each` 正式实现时再收口。
 */
internal class EachCursor {

    /** 一条匹配到的条目：模型可见的元素 + 定位信息。 */
    data class Item(
        val contentKey: String, // role|name —— 跨快照、跨滚动稳定的身份
        val ordinal: Int,       // 本快照里同名条目的序号(0,1,…)，只在本快照内区分同名，不跨屏用
        val role: String,
        val name: String,
        val ref: String,        // 本轮点按用的 ref(可点祖先的 actionRef,没有才用自身 ref;本次快照内有效)
    )

    /** 按 pattern（role? / text?）匹配 [elements]，给每条算出 contentKey 与 ordinal。 */
    fun match(elements: List<JsonObject>, role: String?, text: String?): List<Item> {
        val count = mutableMapOf<String, Int>()
        val out = mutableListOf<Item>()
        for (e in elements) {
            val r = (e["role"] as? JsonPrimitive)?.contentOrNull ?: ""
            val n = (e["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            // 点按要落在可点祖先上:find 的结果里 actionRef 是可点祖先、ref 是命中节点自身。
            val ref = (e["actionRef"] as? JsonPrimitive)?.contentOrNull
                ?: (e["ref"] as? JsonPrimitive)?.contentOrNull ?: ""
            val roleOk = role == null || r == role
            val textOk = text == null || n.contains(text)
            if (!roleOk || !textOk) continue
            val ck = "$r|$n"
            val o = count.getOrDefault(ck, 0)
            count[ck] = o + 1
            out += Item(ck, o, r, n, ref)
        }
        return out
    }
}

/**
 * Q2 原型：滚动游标——跨多次读屏、多次滚动，把每条 contentKey 唯一地做一遍，滚到底就停。
 *
 * 用法（调用方驱动滚动，游标只做决定）：
 * ```
 * var justScrolled = false
 * while (true) {
 *     val elements = readScreen()
 *     when (val s = cursor.advance(elements, justScrolled)) {
 *         is DoNext -> { doItem(s.item); cursor.finish(s.item); justScrolled = false }
 *         Scroll    -> { scrollDown(); justScrolled = true }
 *         End       -> break
 *     }
 * }
 * ```
 *
 * 停止条件：刚滚过一次、且这次读屏没出现任何新 contentKey（到底了）。
 * 已知边界：同名条目（contentKey 相同）会收敛成「做一次」——元素本身信息不足以跨滚动区分两条同名条目，
 * 根治靠更细的 item 匹配（让 contentKey 唯一），不是靠键。
 */
internal class ScrollCursor(
    private val role: String?,
    private val text: String?,
) {
    private val each = EachCursor()
    val seen = mutableSetOf<String>()   // 见过的 contentKey（跨滚动）
    val done = mutableSetOf<String>()   // 已处理的 contentKey

    sealed class Step {
        data class DoNext(val item: EachCursor.Item) : Step()
        object Scroll : Step()
        object End : Step()
    }

    /** 给定当前屏读到的 [elements] 与是否刚滚过 [justScrolled]，返回下一步。 */
    fun advance(elements: List<JsonObject>, justScrolled: Boolean): Step {
        val items = each.match(elements, role, text)
        val newContent = items.filter { it.contentKey !in seen }.map { it.contentKey }
        seen += items.map { it.contentKey }
        val next = items.firstOrNull { it.contentKey !in done }
        if (next != null) return Step.DoNext(next)
        return if (justScrolled && newContent.isEmpty()) Step.End else Step.Scroll
    }

    /** 处理完 [item] 后标记其 contentKey 为已做。 */
    fun finish(item: EachCursor.Item) {
        done += item.contentKey
    }
}
