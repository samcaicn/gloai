package com.wangzan101.mika.core.export

import com.wangzan101.mika.core.run.record.Redactor

/**
 * 导出前脱敏。密钥样式永远按运行记录那套规则处理([Redactor.text]);
 * 手机号、邮箱、私有路径只在用户选 [RedactionLevel.SecretsAndPii] 时处理,避免默认误伤正文。
 */
object ExportRedactor {
    private const val MASK = "***"

    private val EMAIL = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val PHONE_CN = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val PHONE_INTL = Regex("""\+\d[\d ()-]{7,}\d""")
    private val PRIVATE_PATH = Regex("""(?<![\w])(?:/data/user/\d+/|/storage/emulated/\d+/|/sdcard/)[^\s"'<>]+""")

    fun apply(text: String, level: RedactionLevel): String {
        var out = Redactor.text(text)
        if (level == RedactionLevel.SecretsAndPii) {
            out = EMAIL.replace(out, MASK)
            out = PHONE_CN.replace(out, MASK)
            out = PHONE_INTL.replace(out, MASK)
            out = PRIVATE_PATH.replace(out, MASK)
        }
        return out
    }
}
