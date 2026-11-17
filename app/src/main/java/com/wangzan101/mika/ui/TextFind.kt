package com.wangzan101.mika.ui

/** 页内查找一次最多数这么多处,显示时也只标这么多处:再多也没法一个个看。 */
internal const val FIND_LIMIT = 1000

/**
 * 页内查找:在 [text] 里不区分大小写地找 [query] 的每一处,最多 [limit] 处。
 * 返回的位置按出现先后排列,紧挨着的两处不合并(例如在 "aaaa" 里找 "aa" 得到两处)。
 * 空词不找 —— 否则每个字前面都算一处,没有意义。
 */
internal fun findRanges(text: CharSequence, query: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val found = ArrayList<IntRange>()
    var from = 0
    while (found.size < limit) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        found += at until at + query.length
        from = at + query.length
    }
    return found
}
