package com.wangzan101.mika.core.frontdesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskPromptTest {
    @Test fun spokenUserPromptCarriesTheSpokenStyle() {
        assertTrue(DeskPrompt.user(spoken = true).contains(LlmBrain.SPOKEN_STYLE))
        assertFalse(DeskPrompt.user(spoken = false).contains(LlmBrain.SPOKEN_STYLE))
    }

    @Test fun userPromptNamesOnlyTheUserTools() {
        val prompt = DeskPrompt.user(spoken = false)
        for (name in listOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL)) {
            assertTrue("missing $name", prompt.contains(name))
        }
        assertFalse(prompt.contains(DeskTools.ASK_OWNER))
    }

    @Test fun phonePromptCarriesTheBriefAndPolicyVerbatim() {
        val brief = "goal: 问一下周六晚上七点还有没有四人桌\nquestions: 能不能带狗"
        val policy = "只代表主人问,不替主人答应任何付款。{{not_a_placeholder}}"
        val prompt = DeskPrompt.phone(brief, policy)
        assertTrue(prompt.contains("问一下周六晚上七点还有没有四人桌"))
        assertTrue(prompt.contains(brief))
        assertTrue(prompt.contains(policy))
    }

    @Test fun phonePromptNamesOnlyAskOwner() {
        val prompt = DeskPrompt.phone("goal: x", "policy")
        assertTrue(prompt.contains(DeskTools.ASK_OWNER))
        for (name in listOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL)) {
            assertFalse("unexpected $name", prompt.contains(name))
        }
    }

    @Test fun phonePromptIsSpoken() {
        assertTrue(DeskPrompt.phone("goal: x", "policy").contains(LlmBrain.SPOKEN_STYLE))
    }

    /** 背景附注、进展通知、迟到结果的标记在提示词里要和代码一致,否则模型认不出它们。 */
    @Test fun userPromptExplainsTheMarkersTheBrainUses() {
        val prompt = DeskPrompt.user(spoken = false)
        assertTrue(prompt.contains(LlmBrain.BACKGROUND_PREFIX.substringBefore('.').trimStart('[')))
        assertTrue(prompt.contains("[update]"))
        assertTrue(prompt.contains("result arrived]"))
    }

    @Test fun userPromptIsStableAcrossCalls() {
        assertEquals(DeskPrompt.user(false), DeskPrompt.user(false))
    }
}
