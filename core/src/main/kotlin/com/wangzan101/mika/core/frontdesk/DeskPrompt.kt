package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.Prompts

/**
 * 前台用的人格,和后台任务同一套:[assistant] 是助理的称呼(默认 Mika),[layer] 是拼好的人格层
 * ([PersonaPrompt.layer],没选人格或全默认时为 null)。
 */
data class DeskPersona(val assistant: String = PersonaPrompt.DEFAULT_NICKNAME, val layer: String? = null)

/**
 * 前台的系统提示词:和用户本人说话一份,在电话里替主人说话一份。
 * 口语场景(语音通话、电话)在最前面加 [LlmBrain.SPOKEN_STYLE];
 * 电话版把本次任务简报和通话规则原样填进去,里面即使带 {{…}} 也按原文保留。
 */
object DeskPrompt {
    /**
     * 和用户本人说话的前台提示词;[spoken] 为真时要求按朗读的方式写回复。分层和后台一样:
     * 角色说明(称呼进第一句)→ 改了称呼时的说明(`persona/renamed.md`)→ 人格层。
     * 同一套人格逐字节一样;默认人格时没有后两层。
     */
    fun user(spoken: Boolean, persona: DeskPersona = DeskPersona()): String {
        // 称呼落在人格围栏之外,这里自己再清洗一遍,不指望调用方洗过
        val name = PersonaPrompt.nickname(persona.assistant)
        val body = listOfNotNull(
            Prompts.fill(Prompts.role("front_desk"), mapOf("assistant" to name)),
            if (name == PersonaPrompt.DEFAULT_NICKNAME) null else Prompts.fill(Prompts.read("persona/renamed.md"), mapOf("assistant" to name)),
            persona.layer,
        ).joinToString("\n\n")
        return if (spoken) LlmBrain.SPOKEN_STYLE + "\n\n" + body else body
    }

    /** 电话里的前台提示词:[brief] 是这通电话的任务简报,[policy] 是通话规则,都逐字填入。 */
    fun phone(brief: String, policy: String): String =
        LlmBrain.SPOKEN_STYLE + "\n\n" +
            Prompts.fill(Prompts.role("front_desk_phone"), mapOf("brief" to brief, "policy" to policy))
}
