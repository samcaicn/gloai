package com.wangzan101.mika.voice.cascade

/**
 * Turns streaming reply text into speakable pieces: cut at sentence marks, and at commas once a piece is long
 * enough, so synthesis can start before the whole reply is written. Markdown decoration is dropped.
 */
class SentenceSplitter(private val minClause: Int = 8, private val maxPiece: Int = 120) {
    private val buffer = StringBuilder()

    fun push(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = cutIndex() ?: break
            emit(buffer.substring(0, cut + 1))?.let(out::add)
            buffer.delete(0, cut + 1)
        }
        return out
    }

    /** What is left once the reply is complete. */
    fun flush(): String? {
        val rest = emit(buffer.toString())
        buffer.clear()
        return rest
    }

    private fun cutIndex(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            val strong = c in STRONG || (c == '.' && i + 1 < buffer.length && buffer[i + 1].isWhitespace())
            if (strong && clean(buffer.substring(0, i + 1)).isNotEmpty()) return i
            if (c in WEAK && clean(buffer.substring(0, i + 1)).length >= minClause) return i
        }
        if (buffer.length > maxPiece) {
            val space = buffer.lastIndexOf(" ", maxPiece).takeIf { it > maxPiece / 2 }
            return space ?: (maxPiece - 1)
        }
        return null
    }

    private fun emit(text: String): String? = clean(text).takeIf { it.isNotEmpty() }

    companion object {
        private const val STRONG = "。！？!?；;\n…"
        private const val WEAK = "，,、：:"
        private val DECORATION = Regex("""[*#`_~>|]+|\[(\d+)]""")
        private val SPACES = Regex("""\s+""")

        /** Removes markup a voice should not read out. */
        fun clean(text: String): String = text.replace(DECORATION, " ").replace(SPACES, " ").trim()
            .takeIf { it.any(Char::isLetterOrDigit) }.orEmpty()
    }
}
