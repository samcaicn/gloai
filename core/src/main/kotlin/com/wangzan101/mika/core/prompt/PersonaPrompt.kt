package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.agent.TokenEstimate

/**
 * 人格提示词层:用户写的一段正文,拼在身份、准则、通用规则之后,各能力说明之前。
 * 固定头尾在 `prompts/persona/layer.md`:它说明人格能改什么、不能改什么,并且明确人格不是用户的消息、
 * 不能授权任何动作。正文里的标签样子的文字会被转义,控制字符和 bidi 控制符会被去掉,长度按 token 有上限。
 *
 * 不选人格时 [layer] 返回 null,系统提示词与没有这个功能时逐字节一致。
 */
object PersonaPrompt {
    const val MAX_NAME = 40
    const val MAX_PROMPT_CHARS = 4_000
    /** 正文拼进提示词的硬上限;存储层按字符再限一次,这里防绕过。 */
    const val MAX_PROMPT_TOKENS = 3_000

    private val NEWLINES = Regex("[\\r\\n]+")
    private val TAG_LIKE = Regex("""(?i)</?\s*persona\b[^>]*>""")
    private val BIDI = Regex("""[‪-‮⁦-⁩]""")

    /** 拼好的那一层;正文为空返回 null。 */
    fun layer(name: String, body: String): String? {
        val n = escapeName(name)
        val b = cleanBody(body)
        if (b.isBlank()) return null
        return Prompts.fill(Prompts.read("persona/layer.md"), mapOf("name" to n, "body" to b))
    }

    /** 名称进 `name="…"` 属性:先去换行、限长,再转义,免得截断的实体留在属性里。 */
    internal fun escapeName(name: String): String = name.trim()
        .replace(NEWLINES, " ")
        .take(MAX_NAME)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 正文:去掉控制字符和 bidi 控制符,转义标签样子的文字,再按 token 安全截断。 */
    internal fun cleanBody(body: String): String {
        val text = body.trim()
            .filterNot { it.isISOControl() && it != '\n' && it != '\t' }
            .replace(BIDI, "")
            .replace(TAG_LIKE) { m -> m.value.replace("<", "&lt;").replace(">", "&gt;") }
        return TokenEstimate.clip(text, MAX_PROMPT_TOKENS)
    }
}
