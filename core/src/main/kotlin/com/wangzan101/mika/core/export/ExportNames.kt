package com.wangzan101.mika.core.export

/** 文件名与包内路径的清洗;规则和现有分享/附件保持同一方向,但这里不依赖沙箱能力包。 */
object ExportNames {
    const val MAX_NAME = 120
    const val MAX_NAME_BYTES = 240

    fun safeFileName(name: String, fallback: String = "attachment"): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '/'.code || cp == '\\'.code) continue
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt()) continue
            sb.appendCodePoint(cp)
        }
        val cleaned = sb.toString().trim().trimStart('.').ifEmpty { fallback }
        return fit(cleaned)
    }

    /** 按字符数和 UTF-8 字节数截短,保留扩展名和字符边界。 */
    fun fit(name: String): String = fit(name, "")

    private fun fit(name: String, suffix: String): String {
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        val suffixChars = suffix.codePointCount(0, suffix.length)
        if (suffix.isEmpty() && name.codePointCount(0, name.length) <= MAX_NAME &&
            name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val ext = extension.takeIf {
            it.toByteArray(Charsets.UTF_8).size <= 32 &&
            it.codePointCount(0, it.length) + suffixChars < MAX_NAME &&
            it.toByteArray(Charsets.UTF_8).size + suffixBytes < MAX_NAME_BYTES }.orEmpty()
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        val roomChars = (MAX_NAME - suffixChars - ext.codePointCount(0, ext.length)).coerceAtLeast(0)
        val roomBytes = (MAX_NAME_BYTES - suffixBytes - ext.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        val prefix = basePrefix(base, roomChars, roomBytes)
        return prefix.trimEnd() + suffix + ext
    }

    /** 同名时加「 (2)」「 (3)」……,扩展名留在最后。 */
    fun unique(name: String, used: MutableSet<String>): String {
        val fitted = fit(name)
        if (used.add(fitted)) return fitted
        var n = 2
        while (true) {
            val candidate = fit(fitted, " ($n)")
            if (used.add(candidate)) return candidate
            n += 1
        }
    }

    private fun basePrefix(text: String, maxChars: Int, maxBytes: Int): String {
        val out = StringBuilder()
        var chars = 0
        var bytes = 0
        var i = 0
        while (i < text.length && chars < maxChars) {
            val cp = text.codePointAt(i)
            val size = when {
                cp <= 0x7f -> 1
                cp <= 0x7ff -> 2
                cp <= 0xffff -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            out.appendCodePoint(cp)
            chars++
            bytes += size
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** zip 条目只接受相对路径;拒绝绝对路径、反斜杠、`.`/`..` 段、盘符和空段。 */
    fun entryPath(path: String): String {
        require(path.isNotBlank()) { "empty entry path" }
        require(!path.startsWith("/") && !path.startsWith("\\")) { "absolute entry path: $path" }
        require('\\' !in path) { "backslash in entry path: $path" }
        require(':' !in path.substringBefore('/')) { "drive or scheme in entry path: $path" }
        for (part in path.split('/')) {
            require(part.isNotEmpty() && part != "." && part != "..") { "bad entry path: $path" }
        }
        return path
    }
}
