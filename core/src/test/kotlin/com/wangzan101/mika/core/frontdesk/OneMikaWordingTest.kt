package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.model.Part
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户眼里只有 Mika:模型读得到的字(回话、通话、电话的提示词,每轮的背景标记,工具说明,给任务的之前几轮)
 * 不能带内部分层的说法,否则模型会照着说出「交给后台了」。唯一的例外是提示词里明说「别这么说」的那一条。
 */
class OneMikaWordingTest {
    private val inside = listOf("front desk", "frontdesk", "backend", "back end", "background task", "前台", "后台")

    private fun assertNoInsideWords(where: String, text: String) {
        val lower = text.lowercase()
        for (word in inside) assertFalse("$where 里出现了「$word」", word in lower)
    }

    /** 去掉「别提内部怎么做」那一条(它得点名这些词),其余部分照查。 */
    private fun withoutTheDontSayRule(prompt: String): String {
        val start = prompt.indexOf("- Never mention how it works inside")
        assertTrue("提示词里缺「别提内部怎么做」那一条", start >= 0)
        val end = prompt.indexOf("\n- ", start + 1).takeIf { it > 0 } ?: prompt.length
        return prompt.removeRange(start, end)
    }

    @Test fun replyPromptsSpeakAsOneMika() {
        for (spoken in listOf(false, true)) {
            val prompt = DeskPrompt.user(spoken)
            assertTrue(prompt.startsWith(if (spoken) LlmBrain.SPOKEN_STYLE else "You are Mika"))
            assertNoInsideWords("回话提示词(spoken=$spoken)", withoutTheDontSayRule(prompt))
        }
    }

    @Test fun phonePromptSpeaksAsMika() {
        assertNoInsideWords("电话提示词", DeskPrompt.phone("goal: x", "policy"))
    }

    @Test fun markersAndToolDescriptionsCarryNoInsideWords() {
        assertNoInsideWords("背景标记", LlmBrain.BACKGROUND_PREFIX)
        for (set in DeskToolSet.entries) assertNoInsideWords("工具说明 $set", DeskTools.specs(set).joinToString("\n"))
    }

    @Test fun earlierTurnsShowOnlyMika() {
        val turns = listOf(
            Turn("帮我订明天的日料", "好,我去订。", DeskTurn.STATUS, deskTasks = listOf("c1")),
            Turn("顺便查汇率", "", DeskTurn.STATUS, deskTasks = listOf("c2")),
            Turn("", "日料订好了", DeskTurn.REPORT_STATUS, deskTasks = listOf("c1")),
        )
        val text = (Conversation.context(turns)!!.parts.single() as Part.Text).text
        assertNoInsideWords("之前几轮", text)
    }
}
