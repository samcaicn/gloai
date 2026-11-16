package com.wangzan101.mika.core.model

import okhttp3.Request

/**
 * 粘贴进来的 API Key 怎么收。
 *
 * OkHttp 4.12 的 `Headers.checkValue` 只接受制表符和 U+0020..U+007E。`\n`、`\r`、其它小于 0x20 的控制字符,
 * 以及任何大于 0x7E 的字符(中文、emoji、全角符号)都会在构造 Request 时抛 [IllegalArgumentException],
 * 而且发生在发出请求之前,调用方的网络 try/catch 罩不住。
 *
 * 零宽 / BOM 这类看不见的字符直接去掉,剩下的还能当普通 Key 用。换行(`\n`、`\r`)和其它放不进请求头的字符
 * 原样留下,由 [Accepted.notice] 标成 [Notice.IllegalCharacters],调用方不要拿去发请求或保存。
 */
object ApiKeyText {
    enum class Notice {
        /** 没有去掉什么,字符也都放得进请求头。 */
        None,
        /** 只去掉了零宽、BOM 这类看不见的字符;剩下的是可用的 Key。 */
        InvisibleRemoved,
        /** 还含有放不进请求头的字符(含换行)。这些字符没有被改掉。 */
        IllegalCharacters,
    }

    data class Accepted(val text: String, val notice: Notice)

    /**
     * 按一次输入(粘贴或逐字改)收 Key:去掉看不见的零宽 / BOM,不改其余字符。
     * `\n`、`\r` 留在原文里,和中文、emoji 一样算放不进请求头。
     * 首尾空白留着,由保存时的 `trim()` 处理;中间的普通空格也留着,照旧拿去问服务商。
     */
    fun accept(raw: String): Accepted {
        var removedInvisible = false
        val kept = StringBuilder(raw.length)
        for (c in raw) {
            if (isInvisible(c)) removedInvisible = true else kept.append(c)
        }
        val text = kept.toString()
        val notice = when {
            !isHeaderLegal(text) -> Notice.IllegalCharacters
            removedInvisible -> Notice.InvisibleRemoved
            else -> Notice.None
        }
        return Accepted(text, notice)
    }

    /**
     * [value] 整段都能放进 OkHttp 的头值。空串可以;制表符和 U+0020..U+007E 可以;
     * 其余控制字符和非 ASCII 不行。查的是将要放进头的整段(含 `Bearer ` 前缀)。
     */
    fun isHeaderLegal(value: String): Boolean = value.all { c -> c == '\t' || c.code in 0x20..0x7E }

    /** 零宽、BOM、以及改写文字方向的格式字符。这些粘贴时常被带进来,本身不是密钥的一部分。 */
    private fun isInvisible(c: Char): Boolean = when (c) {
        '\u061C', // ARABIC LETTER MARK
        '\u180E', // MONGOLIAN VOWEL SEPARATOR
        '\uFEFF', // BOM / ZERO WIDTH NO-BREAK SPACE
        -> true
        in '\u200B'..'\u200F', // ZWSP、ZWNJ、ZWJ、LRM、RLM
        in '\u202A'..'\u202E', // 双向嵌入 / 覆盖
        in '\u2060'..'\u2069', // 词连接符、不可见运算符、双向隔离
        -> true
        else -> false
    }
}

/**
 * 把 [value] 放进名为 [name] 的头。不合法时抛 [ModelError.IllegalHeader],不调用 OkHttp
 * (它抛的 [IllegalArgumentException] 消息里带着头的原文,会把 Key 打进日志)。
 */
fun Request.Builder.headerOrReject(name: String, value: String): Request.Builder {
    if (!ApiKeyText.isHeaderLegal(value)) throw ModelError.IllegalHeader(name)
    return header(name, value)
}
