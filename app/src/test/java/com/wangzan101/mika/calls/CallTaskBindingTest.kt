package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallTaskBindingTest {
    @Test fun `handoff keeps the confirmed call objective until the carrier call ends`() {
        val carrierCall = Any()
        val binding = CallTaskBinding(carrierCall)
        val questions = mutableListOf("确认今天几点关门")
        val brief = CallTaskBrief("询问营业时间", questions, "今晚到店", "不做预订")
        binding.bind(brief)

        // The AI session may release its work and the owner may edit the next draft.
        // Neither event ends or changes the already bound carrier call's objective.
        questions[0] = "下一通电话的问题"
        val afterHandoff = binding.visibleFor(carrierCall, true, true)
        assertEquals("询问营业时间", afterHandoff?.goal)
        assertEquals(listOf("确认今天几点关门"), afterHandoff?.questions)
        assertEquals("今晚到店", afterHandoff?.context)
        assertEquals("不做预订", afterHandoff?.constraints)

        binding.end()
        assertNull(binding.visibleFor(carrierCall, true, true))
        // An obsolete outgoing callback after disconnected/removed must not resurrect it.
        binding.bind(brief)
        assertNull(binding.visibleFor(carrierCall, true, true))
    }

    @Test fun `unbound or failed outgoing attempts never appear on another carrier call`() {
        val attemptedCall = Any()
        val unrelatedCall = Any()
        val failedAttempt = CallTaskBinding(attemptedCall)
        assertNull(failedAttempt.visibleFor(attemptedCall, true, true))
        assertNull(failedAttempt.visibleFor(unrelatedCall, true, true))

        failedAttempt.end()
        val next = CallTaskBinding(unrelatedCall)
        assertNull(next.visibleFor(unrelatedCall, true, true))
        next.bind(CallTaskBrief("新的独立目标"))
        assertEquals("新的独立目标", next.visibleFor(unrelatedCall, true, true)?.goal)
        assertNull(failedAttempt.visibleFor(unrelatedCall, true, true))
    }

    @Test fun `multiple calls hide context and removing one cannot move its task to the survivor`() {
        val firstCall = Any()
        val secondCall = Any()
        val first = CallTaskBinding(firstCall)
        val second = CallTaskBinding(secondCall)
        first.bind(CallTaskBrief("第一通目标"))
        val calls = mutableListOf(firstCall)
        assertEquals("第一通目标", first.visibleFor(calls.singleOrNull(), true, true)?.goal)

        calls.add(secondCall)
        assertNull(first.visibleFor(calls.singleOrNull(), true, true))
        assertNull(second.visibleFor(calls.singleOrNull(), true, true))
        first.end()
        calls.remove(firstCall)
        assertNull(first.visibleFor(calls.singleOrNull(), true, true))
        assertNull(second.visibleFor(calls.singleOrNull(), true, true))
    }

    @Test fun `service loss and non carrier details hide context and service destruction clears it`() {
        val call = Any()
        val binding = CallTaskBinding(call)
        binding.bind(CallTaskBrief("本次目标"))
        assertNull(binding.visibleFor(call, false, true))
        assertNull(binding.visibleFor(call, true, false))
        assertNull(binding.visibleFor(null, true, true))
        binding.end() // Runtime onCallRemoved, also delivered by service onUnbind/onDestroy.
        assertNull(binding.visibleFor(call, true, true))
    }

    @Test fun `confirmed snapshot is not replaced by a later work or another call identity`() {
        val call = Any()
        val binding = CallTaskBinding(call)
        binding.bind(CallTaskBrief("本次已确认目标"))
        binding.bind(CallTaskBrief("后来编辑的目标"))
        assertEquals("本次已确认目标", binding.visibleFor(call, true, true)?.goal)
        assertNull(binding.visibleFor(Any(), true, true))
    }
}
