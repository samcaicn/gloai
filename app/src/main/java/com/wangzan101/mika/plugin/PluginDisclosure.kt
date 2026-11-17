package com.wangzan101.mika.plugin

/**
 * 装一个插件前给用户看的披露:会得到哪些工具(名字 → 风险等级)与会不会改写系统提示词。
 * 插件脚本跑在 App 进程内,UI 用这份披露诚实地告诉用户它能读到对话、记忆和模型密钥。
 */
data class PluginDisclosure(val tools: List<Pair<String, String>>, val rewritesPrompt: Boolean)

fun PluginManifest.disclosure(): PluginDisclosure = PluginDisclosure(
    tools = tools.map { it.name to (it.risk ?: "read") },
    rewritesPrompt = promptTransform != null,
)

/** 插件默认的提示词规则(persona.conf):全是注释,等于不改提示词;「还原默认」写回这份。 */
val DEFAULT_PERSONA_CONF: String = """
    # 提示词改写配置。改完下一次任务生效。
    # prefix / suffix:整段包在系统提示词外面(各一行)
    # replace:一行一条,格式  旧文字=>新文字  (字面匹配,不是正则)
    # 行首加 [模型名片段] 只对那个模型生效,例如 [claude]prefix=…
    #
    # 例:先给结论
    # prefix=先给结论,再给理由。不确定的事直接说不确定,不要绕。
    #
    # 例:去掉某句固定话术
    # replace=You are Mika, the user's personal assistant on their phone.=>You are Mika.
    #
    # 例:追加一条规矩
    # suffix=回答尽量控制在三句话内,除非用户要细节。
""".trimIndent()
