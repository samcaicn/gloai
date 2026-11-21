package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.agent.TokenEstimate

/**
 * 人格提示词层:界面选项对应的固定句子([PersonaStyle])加用户写的一段正文,拼在身份、准则、通用规则之后,各能力说明之前。
 * 固定头尾在 `prompts/persona/layer.md`:它说明人格能改什么、不能改什么,并且明确人格不是用户的消息、
 * 不能授权任何动作。正文里的标签样子的文字会被转义,控制字符和 bidi 控制符会被去掉,长度按 token 有上限。
 * 零宽连接符留着(波斯语的写法和组合表情要用),所以认标签时允许字母之间夹着这类看不见的字符。
 *
 * 不选人格、或人格里选项全是默认值且正文为空时 [layer] 返回 null,系统提示词与没有这个功能时逐字节一致。
 */
object PersonaPrompt {
    const val MAX_NAME = 40
    /** 助理默认的自称。 */
    const val DEFAULT_NICKNAME = "Mika"
    /** 称呼最多这么多个字(按码点)。 */
    const val MAX_NICKNAME = 16
    const val MAX_PROMPT_CHARS = 4_000
    /** 正文拼进提示词的硬上限;存储层按字符再限一次,这里防绕过。 */
    const val MAX_PROMPT_TOKENS = 3_000

    private val NEWLINES = Regex("[\\r\\n]+")
    /** `persona` 七个字母,相邻两个之间允许夹格式字符(零宽空格、零宽连接符、软连字符……)。 */
    private const val SPLIT_WORD = """p\p{Cf}*e\p{Cf}*r\p{Cf}*s\p{Cf}*o\p{Cf}*n\p{Cf}*a"""
    /** 标签样子的文字:半角或全角尖括号,斜杠前后可以有空白,单词可以被看不见的字符拆开。 */
    private val TAG_LIKE = Regex("""(?i)[<\uFF1C]\s*/?[\s\p{Cf}]*$SPLIT_WORD(?![\p{L}\p{N}_])[^>\uFF1E]*[>\uFF1E]""")
    /** 改变显示方向的控制符:ALM、LRM、RLM、嵌入/覆盖(U+202A–202E)、隔离(U+2066–2069)。 */
    private val BIDI = Regex("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** 选项的固定句子:键 → 原文(`prompts/persona/style.md`,格式同 feedback 文件)。 */
    private val styleText: Map<String, String> by lazy { Feedback.parse(Prompts.read("persona/style.md")).toMap() }

    /**
     * 拼好的那一层。选项的句子在前(每条一行,前面加「- 」),用户写的正文在后,中间空一行;
     * 只有正文时和加选项之前逐字节一样。选项全默认且正文为空时没有可拼的,返回 null。
     */
    fun layer(
        name: String, body: String, style: PersonaStyle = PersonaStyle.DEFAULT, assistant: String = DEFAULT_NICKNAME,
    ): String? {
        val n = escapeName(name)
        val own = content(body, style)
        if (own.isBlank()) return null
        return Prompts.fill(Prompts.read("persona/layer.md"), mapOf("name" to n, "body" to own, "assistant" to nickname(assistant)))
    }

    /**
     * 人格块里属于这一套人格的那一段:选项的句子加用户写的正文(清洗过),和 [layer] 里放进去的一字不差。
     * 界面预览时用它把这一段和固定头尾分开上色。两样都没有时是空串。
     */
    fun content(body: String, style: PersonaStyle = PersonaStyle.DEFAULT): String =
        listOf(styleLines(style), cleanBody(body)).filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * 称呼:助理的自称,进固定的身份句(在人格块的围栏之外),所以只留名字里该有的字符:
     * 各种文字的字母、数字、组合记号、空格,以及连字符、间隔号和波斯语要用的零宽不连字符;别的一律去掉。
     * 连续空白并成一个空格,最多 [MAX_NICKNAME] 个字;清洗后是空的就用 [DEFAULT_NICKNAME]。
     */
    fun nickname(raw: String?): String {
        val kept = StringBuilder()
        var i = 0
        val text = raw.orEmpty()
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isLetterOrDigit(cp) || cp in NICKNAME_MARKS -> kept.appendCodePoint(cp)
                Character.getType(cp).toByte() in COMBINING -> kept.appendCodePoint(cp)
                Character.isWhitespace(cp) -> kept.append(' ')
            }
        }
        val collapsed = kept.toString().replace(Regex(" +"), " ").trim()
        val clipped = if (collapsed.codePointCount(0, collapsed.length) <= MAX_NICKNAME) collapsed
        else collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_NICKNAME)).trim()
        return clipped.ifEmpty { DEFAULT_NICKNAME }
    }

    /** 用户输入的称呼能不能原样用:留空可以(就是默认),否则清洗前后必须一样(没有被去掉或截掉的字)。界面据此提示。 */
    fun nicknameAccepted(raw: String): Boolean = raw.isBlank() || nickname(raw) == raw.trim().replace(Regex("\\s+"), " ")

    private val NICKNAME_MARKS = setOf('-'.code, '·'.code, '・'.code, 0x200C)
    private val COMBINING = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)

    /** 选项对应的那几行;全默认时为空串。句子是代码自己的,不经过清洗。 */
    internal fun styleLines(style: PersonaStyle): String =
        style.keys().joinToString("\n") { key -> "- " + checkNotNull(styleText[key]) { "missing persona style $key" } }

    /** style.md 里的全部键(给测试核对:每个选项取值都有一句,没有多余的)。 */
    internal val styleKeys: Set<String> get() = styleText.keys

    /** 名称进 `name="…"` 属性:先去换行、控制字符和 bidi 控制符,限长,再转义,免得截断的实体留在属性里。 */
    internal fun escapeName(name: String): String = name.trim()
        .replace(NEWLINES, " ")
        .filterNot { it.isISOControl() }
        .replace(BIDI, "")
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
            .replace(TAG_LIKE) { m -> m.value.replace(Regex("[<\uFF1C]"), "&lt;").replace(Regex("[>\uFF1E]"), "&gt;") }
        return TokenEstimate.clip(text, MAX_PROMPT_TOKENS)
    }
}
