package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.prompt.Prompts

/**
 * 前台的系统提示词:和用户本人说话一份,在电话里替主人说话一份。
 * 口语场景(语音通话、电话)在最前面加 [LlmBrain.SPOKEN_STYLE];
 * 电话版把本次任务简报和通话规则原样填进去,里面即使带 {{…}} 也按原文保留。
 */
object DeskPrompt {
    /** 和用户本人说话的前台提示词;[spoken] 为真时要求按朗读的方式写回复。 */
    fun user(spoken: Boolean): String {
        val body = Prompts.role("front_desk")
        return if (spoken) LlmBrain.SPOKEN_STYLE + "\n\n" + body else body
    }

    /** 电话里的前台提示词:[brief] 是这通电话的任务简报,[policy] 是通话规则,都逐字填入。 */
    fun phone(brief: String, policy: String): String =
        LlmBrain.SPOKEN_STYLE + "\n\n" +
            Prompts.fill(Prompts.role("front_desk_phone"), mapOf("brief" to brief, "policy" to policy))
}
