package com.jev.probe.core.scriptpack

/**
 * 把话术包组装成回复链路的提示词配置。
 *
 * 分工原则（docs/scriptpack-spec.md §8）：话术包只决定「人设与风格」，
 * 输出契约（JSON 数组协议）和知识库一致性约束**留在 App 侧**——否则一个
 * 劣质话术包就能让候选回复解析器（[com.jev.probe.jev.ReplyClient.parseThree]）
 * 失效。话术包可以改语气/禁忌/示例/温度，不能改协议。
 */
object PackPrompts {

    private const val KNOWLEDGE_CLAUSE =
        "如果给了背景与知识库，回复必须与之一致，可以引用其中事实，不要编造知识库里没有的事实。"

    /** 默认系统提示词（无话术包时的内置话术），与历史行为一致。 */
    val DEFAULT_DRAFT_SYS: String =
        "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"

    /** 话术包版系统提示词：话术核心 + 风格块 + 关系阶段 + 一致性约束 + 输出契约。 */
    fun buildSystemPrompt(pack: ScriptPack): String {
        val n = pack.params.candidates.coerceIn(1, 5)
        val contract = "只输出一个 JSON 数组，含且仅含 $n 条候选回复文本，" +
            "每条策略要有区别。每条不超过 40 字，口语、自然，像真人在聊天软件里发消息。" +
            "不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val sb = StringBuilder()
        sb.append(pack.systemPrompt.trim()).append("\n\n")
        val style = styleBlock(pack.style)
        if (style.isNotBlank()) sb.append(style).append('\n')
        if (pack.relationshipHint.isNotBlank()) {
            sb.append("关系阶段：").append(pack.relationshipHint.trim()).append('\n')
        }
        sb.append(KNOWLEDGE_CLAUSE).append('\n')
        sb.append(contract)
        return sb.toString()
    }

    private fun styleBlock(style: PackStyle): String {
        val sb = StringBuilder()
        fun line(label: String, value: String) {
            if (value.isNotBlank()) sb.append(label).append("：").append(value.trim()).append('\n')
        }
        line("语气", style.tone)
        line("人设", style.persona)
        line("禁忌", style.taboo)
        line("篇幅", style.lengthHint)
        return sb.toString().trimEnd()
    }

    /** 话术包温度（clamp 到 0..2，异常值回退 0.8）。 */
    fun temperatureOf(pack: ScriptPack): Double {
        val t = pack.params.temperature
        return if (t.isNaN() || t < 0.0 || t > 2.0) 0.8 else t
    }
}
