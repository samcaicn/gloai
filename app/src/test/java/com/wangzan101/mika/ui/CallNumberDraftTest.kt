package com.wangzan101.mika.ui

import org.junit.Assert.*
import org.junit.Test

class CallNumberDraftTest {
    @Test fun bothTabsShareNumbersAndUseTheIncomingCallMatchingRules() {
        val draft = CallNumberDraft("138 0013 8000，+86 13800138000;400-123-4567")
        assertTrue(draft.valid)
        assertEquals(setOf("13800138000", "4001234567"), draft.selectedNumbers)
        assertEquals("400-123-4567", draft.toggle("008613800138000"))
        val next = CallNumberDraft(draft.toggle("5551234"))
        assertEquals(setOf("13800138000", "4001234567", "5551234"), next.selectedNumbers)
    }

    @Test fun togglingAContactPreservesUnfinishedManualInput() {
        val draft = CallNumberDraft("12\n5551234")
        val edited = CallNumberDraft(draft.toggle("5551234"))
        assertFalse(edited.valid)
        assertEquals("12\n6661234", edited.toggle("6661234"))
        assertTrue(CallNumberDraft("").valid)
    }

    @Test fun aFullListStillAllowsRemoval() {
        val full = (100..199).joinToString("\n")
        assertTrue(CallNumberDraft(full).valid)
        assertEquals(full, CallNumberDraft(full).toggle("999"))
        assertFalse(CallNumberDraft("$full\n999").valid)
        assertEquals(99, CallNumberDraft(CallNumberDraft(full).toggle("199")).selectedNumbers.size)
    }
}
