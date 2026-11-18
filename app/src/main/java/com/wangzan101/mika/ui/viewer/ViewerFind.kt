package com.wangzan101.mika.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wangzan101.mika.ui.FIND_LIMIT
import com.wangzan101.mika.ui.MdBlock
import com.wangzan101.mika.ui.findRanges

/**
 * 查看页的查找状态:开没开、搜什么、命中多少处、现在第几处。
 * 命中内容由正在显示的视图算好,再用 [report] 回报;输入框和计数条只读这里。
 */
internal class FindState {
    var active by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var count by mutableStateOf(0)
        private set
    var index by mutableStateOf(0)
        private set

    /** 命中太多,只数到了前 [FIND_LIMIT] 处。 */
    var more by mutableStateOf(false)
        private set

    fun open() { active = true }

    fun close() {
        active = false
        query = ""
        count = 0
        index = 0
        more = false
    }

    /** 换了搜索词就从第一处看起;计数等视图算好再经 [report] 填进来。 */
    fun updateQuery(value: String) {
        if (value == query) return
        query = value
        index = 0
    }

    fun next() { if (count > 0) index = (index + 1) % count }

    fun prev() { if (count > 0) index = (index + count - 1) % count }

    /** 视图算完这一轮的命中后回报。 */
    fun report(hits: Int, more: Boolean) {
        count = hits
        this.more = more
        if (index >= count) index = 0
    }
}

/** 文本类文件里的一处命中:[chunk] 是第几段,range 是这一段里的位置。 */
internal data class TextHit(val chunk: Int, val range: IntRange)

/** 文本类文件的全部命中;也记着是不是因为太多被截断了。 */
internal class TextFinds(val hits: List<TextHit>, val more: Boolean) {
    /** 每一段里要标的位置,按段分好,列表只给看得见的几段。 */
    val byChunk: Map<Int, List<IntRange>> = hits.groupBy({ it.chunk }, { it.range })
}

/** 在分段显示的文本里找 [query];位置按段算,跳转时就能直接去那一段。 */
internal fun findInChunks(chunks: List<String>, query: String): TextFinds {
    if (query.isEmpty()) return TextFinds(emptyList(), more = false)
    val hits = ArrayList<TextHit>()
    chunks.forEachIndexed { index, chunk ->
        findRanges(chunk, query, FIND_LIMIT + 1 - hits.size).forEach { hits += TextHit(index, it) }
        if (hits.size > FIND_LIMIT) return TextFinds(hits.take(FIND_LIMIT), more = true)
    }
    return TextFinds(hits, more = false)
}

/**
 * Markdown 里的一处命中:[block] 是顶层第几块,[text] 是命中的那段文字本身,
 * [occurrence] 是它在这一段里第几次出现 —— 显示时靠这两个认出「当前这一处」。
 */
internal data class BlockHit(val block: Int, val text: CharSequence, val occurrence: Int)

/** Markdown 的全部命中;也记着是不是因为太多被截断了。 */
internal class BlockFinds(val hits: List<BlockHit>, val more: Boolean)

/** 在 Markdown 的各段文字里找 [query];命中记在顶层的块上,跳转时滚到那一块。 */
internal fun findInBlocks(blocks: List<MdBlock>, query: String): BlockFinds {
    if (query.isEmpty()) return BlockFinds(emptyList(), more = false)
    val hits = ArrayList<BlockHit>()
    val pieces = ArrayList<CharSequence>()
    blocks.forEachIndexed { index, block ->
        pieces.clear()
        block.textPieces(pieces)
        for (piece in pieces) {
            findRanges(piece, query, FIND_LIMIT + 1 - hits.size).forEachIndexed { occurrence, _ ->
                hits += BlockHit(index, piece, occurrence)
            }
            if (hits.size > FIND_LIMIT) return BlockFinds(hits.take(FIND_LIMIT), more = true)
        }
    }
    return BlockFinds(hits, more = false)
}

/** Markdown 按显示顺序排开的各段文字:段落、标题、代码、引用和列表里的块、表格的格。 */
internal fun MdBlock.textPieces(out: MutableList<CharSequence>) {
    when (this) {
        is MdBlock.Para -> out += text
        is MdBlock.Heading -> out += text
        is MdBlock.Code -> out += code
        is MdBlock.Quote -> blocks.forEach { it.textPieces(out) }
        is MdBlock.ListBlock -> items.forEach { item -> item.forEach { it.textPieces(out) } }
        is MdBlock.Table -> {
            out += header
            rows.forEach { row -> out += row }
        }
        MdBlock.Rule -> Unit
    }
}
