package com.wangzan101.mika.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.decision.DecisionChoice
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentGateTest {
    @Test fun confidentDecisionSkipsGenerativeJudgeAndKeepsCurrentRequestSeparate() = runTest {
        val judge = FakeModel()
        val decision = DecisionModel { state, instructions, options, applicability ->
            assertEquals("现在查一下订单", state.jsonObject.getValue("currentMessage").jsonPrimitive.content)
            assertTrue(state.jsonObject.getValue("recentContext").jsonPrimitive.content.contains("昨天的订单"))
            assertEquals(setOf("DISCUSS", "READ", "ACT"), options.keys)
            assertTrue(instructions.contains("hypothetical"))
            assertNull(applicability)
            DecisionChoice("READ", 0.96)
        }
        val traces = mutableListOf<String>()
        assertEquals(IntentGate.Intent.Read, IntentGate(judge, decision = decision, onDecision = traces::add)
            .assess("现在查一下订单", listOf(Turn("昨天的订单", "看过了", "succeeded"))))
        assertTrue(judge.requests.isEmpty())
        assertEquals(listOf("selected:Read"), traces)
    }

    @Test fun uncertainInvalidAndFailedDecisionsUseExistingJudge() = runTest {
        val decisions = listOf(
            DecisionModel { _, _, _, _ -> DecisionChoice("ACT", 0.6) },
            DecisionModel { _, _, _, _ -> DecisionChoice("UNKNOWN", 1.0) },
            DecisionModel { _, _, _, _ -> DecisionChoice("ACT", Double.NaN) },
            DecisionModel { _, _, _, _ -> throw IllegalStateException("unavailable") },
        )
        for (decision in decisions) {
            val judge = FakeModel(com.wangzan101.mika.core.testing.answerReply("DISCUSS"))
            assertEquals(IntentGate.Intent.Discuss, IntentGate(judge, decision = decision).assess("以后可以做吗", emptyList()))
            assertEquals(1, judge.requests.size)
        }
    }

    @Test fun decisionTimeoutFallsBackButCallerCancellationPropagates() = runTest {
        val judge = FakeModel(com.wangzan101.mika.core.testing.answerReply("READ"))
        assertEquals(IntentGate.Intent.Read,
            IntentGate(judge, decision = DecisionModel { _, _, _, _ -> awaitCancellation() }).assess("查一下", emptyList()))
        val untouched = FakeModel()
        try {
            IntentGate(untouched, decision = DecisionModel { _, _, _, _ -> throw CancellationException("stopped") })
                .assess("查一下", emptyList())
            org.junit.Assert.fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(untouched.requests.isEmpty())
        }
    }

    @Test fun classifierSeesCurrentMessageAndRecentContextWithoutTools() = runTest {
        val judge = FakeModel(com.wangzan101.mika.core.testing.answerReply("DISCUSS"))
        val decision = IntentGate(judge).assess("以后可以帮我同步发布，对吧？",
            listOf(Turn("打开两个 App 看看", "看过了", "succeeded")))
        assertEquals(IntentGate.Intent.Discuss, decision)
        assertTrue(judge.offeredTools.single().isEmpty())
        val input = judge.requests.single().single().parts.single() as com.wangzan101.mika.core.model.Part.Text
        assertTrue(input.text.contains("The user's current message:\n以后可以帮我同步发布，对吧？"))
        // 上一轮的对话在当前的这句话之前
        assertTrue(input.text, "打开两个 App 看看" in input.text)
        assertTrue(input.text, input.text.indexOf("打开两个 App 看看") < input.text.indexOf("The user's current message:"))
    }

    @Test fun onlyKnownIntentLabelsAreAccepted() {
        assertEquals(IntentGate.Intent.Read, IntentGate.parse(" READ "))
        assertEquals(IntentGate.Intent.Act, IntentGate.parse("ACT"))
        assertNull(IntentGate.parse("maybe"))
    }

    @Test fun fallsBackToMainModelWhenLightJudgeFails() = runTest {
        val light = FakeModel(IllegalStateException("unavailable"))
        val main = FakeModel(com.wangzan101.mika.core.testing.answerReply("READ"))
        assertEquals(IntentGate.Intent.Read, IntentGate(light, main).assess("查一下订单", emptyList()))
        assertTrue(light.offeredTools.single().isEmpty())
        assertTrue(main.offeredTools.single().isEmpty())
    }
}
