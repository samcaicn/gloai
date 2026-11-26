package com.tuptup.speech.inference

import android.content.Context
import org.json.JSONObject
import kotlin.math.max

/**
 * 端侧 ByteLevel BPE tokenizer —— 直接解析 HuggingFace tokenizer.json。
 * 对齐 Verdict(ModernBERT/gliclass) 的 tokenizer:
 *   model.type=BPE, pre_tokenizer=ByteLevel, normalizer=NFC, vocab=50280, merges=50009
 *
 * 实现：UTF-8 字节 → ByteLevel 可见字符映射 → BPE 合并（按 merge rank）→ vocab id。
 * 特殊 token 用 added_tokens 的 id；[PAD]/[CLS]/[SEP] 按 modernbert 约定取。
 */
class BpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRanks: Map<Pair<String, String>, Int>,
    private val byteToChar: Map<Byte, Char>,
    private val charToByte: Map<Char, Byte>,
    private val clsId: Long,
    private val sepId: Long,
    private val padId: Long,
    private val maxLen: Int
) {
    data class Encoded(val inputIds: LongArray, val attentionMask: LongArray)

    companion object {
        /** ByteLevel 的 256 字节 → 可打印字符映射（GPT-2 风格） */
        private fun byteLevelMaps(): Pair<Map<Byte, Char>, Map<Char, Byte>> {
            val bs = listOf('!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
                '0','1','2','3','4','5','6','7','8','9',':',';','<','=','>','?','@',
                'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','X','Y','Z',
                '[','\\',']','^','_','`','a','b','c','d','e','f','g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v','w','x','y','z',
                '{','|','}','~')
            val extra = listOf('¡','¢','£','¤','¥','¦','§','¨','©','ª','«','¬','­','®','¯','°','±','²','³','´','µ','¶','·','¸','¹','º','»','¼','½','¾','¿')
            val all = bs + extra
            val n = 256 - all.size
            val upper = (0 until n).map { ('À'.code + it).toChar() }
            val table = all + upper
            val b2c = mutableMapOf<Byte, Char>()
            val c2b = mutableMapOf<Char, Byte>()
            for (i in 0 until 256) {
                val b = if (i < 128) i.toByte() else (i - 256).toByte()
                b2c[b] = table[i]; c2b[table[i]] = b
            }
            return b2c to c2b
        }

        fun fromAssets(ctx: Context): BpeTokenizer {
            val json = JSONObject(ctx.assets.open("tokenizer/tokenizer.json").bufferedReader().use { it.readText() })
            val model = json.getJSONObject("model")
            val vocabObj = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>()
            val it = vocabObj.keys()
            while (it.hasNext()) { val k = it.next(); vocab[k] = vocabObj.getInt(k) }

            val mergesArr = model.getJSONArray("merges")
            val ranks = HashMap<Pair<String, String>, Int>()
            for (i in 0 until mergesArr.length()) {
                val m = mergesArr.get(i)
                val parts = if (m is String) m.split(" ") else {
                    val a = m as org.json.JSONArray
                    listOf(a.getString(0), a.getString(1))
                }
                if (parts.size == 2) ranks[(parts[0] to parts[1])] = i
            }
            val (b2c, c2b) = byteLevelMaps()

            // 特殊 token：从 added_tokens 找，回退到 modernbert 常用 id
            val added = json.optJSONArray("added_tokens")
            var cls = 1L; var sep = 2L; var pad = 0L
            added?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    when (t.optString("content")) {
                        "[CLS]", "<s>" -> cls = t.optLong("id", cls)
                        "[SEP]", "</s>" -> sep = t.optLong("id", sep)
                        "<|padding|>", "[PAD]", "<pad>" -> pad = t.optLong("id", pad)
                    }
                }
            }
            val maxLen = json.optJSONObject("truncation")?.optInt("max_length", 512) ?: 512
            return BpeTokenizer(vocab, ranks, b2c, c2b, cls, sep, pad, maxLen)
        }

        /** 无 tokenizer.json 的降级：字符哈希 */
        fun fallback(): BpeTokenizer {
            val vocab = (0..1000).associate { "c$it" to it }
            val (b2c, c2b) = byteLevelMaps()
            return BpeTokenizer(vocab, emptyMap(), b2c, c2b, 1L, 2L, 0L, 512)
        }
    }

    /** 编码：不做 CLS/SEP（Verdict 的 prompt 契约是纯文本 BPE，模型内部处理位置），动态长度 */
    fun encode(text: String): Encoded {
        // NFC 归一化（Kotlin String 已接近 NFC，中文无需额外处理）
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (b in bytes) sb.append(byteToChar[b] ?: ' ')
        // 按 ByteLevel 空格切词（ByteLevel pre-tokenizer 以空格分词，Ġ 表示空格）
        val words = sb.toString().split(Regex("(?<= )(?=[^ ])")).filter { it.isNotEmpty() }
        val ids = mutableListOf<Long>()
        for (w in words) {
            var parts = segmentChars(w).toMutableList()
            while (parts.size > 1) {
                var bestRank = Int.MAX_VALUE; var bestIdx = -1
                for (i in 0 until parts.size - 1) {
                    val r = mergeRanks[parts[i] to parts[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; bestIdx = i }
                }
                if (bestIdx < 0) break
                parts[bestIdx] = parts[bestIdx] + parts[bestIdx + 1]
                parts.removeAt(bestIdx + 1)
            }
            for (p in parts) {
                if (ids.size >= maxLen) break
                // vocab 里的 token 可能带 Ġ 前缀，直接查；查不到再查去掉空格的版本
                ids.add((vocab[p] ?: vocab[p.trim()] ?: 0).toLong())
            }
            if (ids.size >= maxLen) break
        }
        if (ids.isEmpty()) ids.add(padId)
        return Encoded(ids.toLongArray(), LongArray(ids.size) { 1L })
    }

    /** 逐字符，但把 Ġ（ByteLevel 空格）合并到其后字符，符合 ByteLevel 分词 */
    private fun segmentChars(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == 'Ġ' && i + 1 < s.length) {
                out.add("Ġ" + s[i + 1]); i += 2
            } else { out.add(c.toString()); i++ }
        }
        return out
    }
}
