package com.wangzan101.mika.core.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 人格里由界面选项决定的那部分。每个不是默认值的选项对应 `prompts/persona/style.md` 里的一句固定文字,
 * 拼在人格块里、用户自己写的指令前面;全是默认值时一句都不加。
 *
 * 选项有意做得少:一种基础风格定调子,三个三档选项管最常被要求改的三件事(表情符号、回答长短、进度说明的详略),
 * 其余的写进指令。它们只落在固定头允许人格改的范围里:语气、回答长短、进度说明的写法、格式偏好。
 * 「少问我」「跳过核对」这类属于授权和证据规则,不做成选项。
 */
@Serializable
data class PersonaStyle(
    /** 基础风格和语气。 */
    val base: Base = Base.Default,
    /** 表情符号。 */
    val emoji: Level = Level.Default,
    /** 回答篇幅。 */
    val length: Level = Level.Default,
    /** 办事时进度说明的详略。 */
    val progress: Level = Level.Default,
) {
    /** [wire] 是存盘和配置文件里的写法,也是 style.md 里键的后半段。 */
    @Serializable
    enum class Base(val wire: String) {
        @SerialName("default") Default("default"),
        @SerialName("crisp") Crisp("crisp"),
        @SerialName("warm") Warm("warm"),
        @SerialName("playful") Playful("playful"),
    }

    @Serializable
    enum class Level(val wire: String) {
        @SerialName("less") Less("less"),
        @SerialName("default") Default("default"),
        @SerialName("more") More("more"),
    }

    val isDefault: Boolean get() = this == DEFAULT

    /** 选中的各项在 style.md 里的键,顺序固定(同一组选项拼出来的文字逐字节一样)。 */
    internal fun keys(): List<String> = buildList {
        if (base != Base.Default) add("base.${base.wire}")
        for ((name, level) in levels()) if (level != Level.Default) add("$name.${level.wire}")
    }

    /** 三档选项:名字 → 取值,按界面上的顺序。 */
    fun levels(): List<Pair<String, Level>> = listOf("emoji" to emoji, "length" to length, "progress" to progress)

    companion object {
        val DEFAULT = PersonaStyle()
    }
}
