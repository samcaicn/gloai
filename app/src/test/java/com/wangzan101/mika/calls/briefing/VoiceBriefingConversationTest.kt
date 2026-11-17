package com.wangzan101.mika.calls.briefing

import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.VolcRealtimeEvent
import org.junit.Assert.*
import org.junit.Test

class VoiceBriefingConversationTest {
    private fun final(text: String) = VolcRealtimeEvent.UserTranscript(text, true)

    @Test fun `only exact standalone owner finalized authorization is accepted`() {
        listOf("我说完了，你去打", "我说完了你去打。", " 我说完了,你去打！ ", "我说完了， 你去打").forEach {
            assertTrue(it, VoiceBriefingAuthorization.matches(final(it)))
        }
        listOf("不要我说完了，你去打", "等我说完了，你去打", "我没说完，你去打", "他说我说完了，你去打",
            "‘我说完了，你去打’", "“我说完了，你去打”", "我说完了，你去打？", "我说完了，你去打吗",
            "我说完了，你去打，但是先别打", "比如我说完了，你去打", "我说完了，你去打\n不是现在", "去打吧",
        ).forEach { assertFalse(it, VoiceBriefingAuthorization.matches(final(it))) }
        assertFalse(VoiceBriefingAuthorization.matches(final("我说完了，你去打").copy(isFinal = false)))
        assertFalse(VoiceBriefingAuthorization.matches(final("我说完了，你去打").copy(isCompleteFinal = false)))
        assertFalse(VoiceBriefingAuthorization.matches(final("我说完了，你去打").copy(isCurrentTurn = false)))
    }

    @Test fun `complete owner instructions including corrections are preserved verbatim once`() {
        val conversation = VoiceBriefingConversation(null)
        assertNull(conversation.accept(final("问明天几点开门，不要预约")))
        assertNull(conversation.accept(final("改成后天，预算不超过二百")))
        val brief = conversation.accept(final("我说完了，你去打"))!!
        assertEquals("问明天几点开门，不要预约\n改成后天，预算不超过二百", brief.context)
        assertEquals(brief, brief.validatedSnapshot())
        assertNull(conversation.accept(final("我说完了，你去打")))
    }

    @Test fun `late final fails rather than reordering an owner correction`() {
        val conversation = VoiceBriefingConversation(null)
        try {
            conversation.accept(final("我说完了，你去打").copy(isCurrentTurn = false))
            fail("must reject unknown turn ordering")
        } catch (_: VoiceBriefingFailure) { }
    }

    @Test fun `authorization cannot omit an unfinished earlier owner instruction`() {
        val conversation = VoiceBriefingConversation(CallTaskBrief("问价格"))
        // Ordinary completed content is retained while a different item is unfinished.
        assertNull(conversation.accept(final("还要问是否含税").copy(hasUnresolvedPriorTurn = true)))
        assertEquals("还要问是否含税", conversation.ownerTranscript)
        try {
            conversation.accept(final("我说完了，你去打").copy(hasUnresolvedPriorTurn = true))
            fail("must not authorize with an unresolved earlier restriction")
        } catch (failure: VoiceBriefingFailure) {
            assertEquals("之前的交代尚未完整识别，未拨号；请重新完整交代", failure.safeMessage)
        }
    }

    @Test fun `duplicate consecutive instruction finals do not duplicate immutable task`() {
        val conversation = VoiceBriefingConversation(null)
        conversation.accept(final("问营业时间"))
        conversation.accept(final("问营业时间"))
        assertEquals("问营业时间", conversation.accept(final("我说完了，你去打"))!!.context)
    }

    @Test fun `canceled conversation never returns snapshot`() {
        val conversation = VoiceBriefingConversation(CallTaskBrief("问营业时间"))
        conversation.cancel()
        assertNull(conversation.accept(final("我说完了，你去打")))
    }

    @Test fun `initial brief and spoken constraints all survive handoff`() {
        val conversation = VoiceBriefingConversation(CallTaskBrief("问价格", listOf("含税吗"), "两个人", "不付订金"))
        conversation.accept(final("加问能否开发票"))
        val context = conversation.accept(final("我说完了，你去打"))!!.context
        listOf("问价格", "含税吗", "两个人", "不付订金", "加问能否开发票").forEach { assertTrue(context.contains(it)) }
    }

    @Test fun `long instructions fail rather than silently truncate`() {
        val conversation = VoiceBriefingConversation(null)
        try {
            conversation.accept(final("长".repeat(CallTaskBrief.MAX_CONTEXT_CHARS + 1)))
            fail("must reject overflow")
        } catch (_: VoiceBriefingFailure) { }
    }

    @Test fun `incomplete final fails closed even if it is not the authorization phrase`() {
        try {
            VoiceBriefingConversation(null).accept(final("问下价格").copy(isCompleteFinal = false))
            fail("must not save partial instructions as a complete task")
        } catch (_: VoiceBriefingFailure) { }
    }
}
