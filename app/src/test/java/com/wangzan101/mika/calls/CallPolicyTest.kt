package com.wangzan101.mika.calls

import org.junit.Assert.*
import org.junit.Test

class CallPolicyTest {
    @Test fun blocksControlCodesAndNeverStripsDialingSyntax() {
        listOf("112", "911", "*#06#", "**21*123456789#", "123456789,22", "123456789;22", "tel:123456789",
            "123456789p1", "%2a123456789", "+1+5551234567").forEach {
            assertNull(it, CallPolicy.outgoingNumber(it))
        }
        assertEquals("+15551234567", CallPolicy.outgoingNumber(" +1 (555) 123-4567 "))
    }

    @Test fun allowlistFailsClosedAndOnlyExplicitAllAllowsHiddenCallers() {
        val config = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true)
        assertFalse(CallPolicy.callerAllowed(config, "15551234567"))
        assertFalse(CallPolicy.callerAllowed(config, null))
        val restricted = config.copy(allowedIncomingNumbers = setOf("+15551234567"))
        assertTrue(CallPolicy.callerAllowed(restricted, "+1 (555) 123-4567"))
        assertFalse(CallPolicy.callerAllowed(restricted, "5551234567"))
        val all = config.copy(incomingScope = IncomingScope.ALL)
        assertTrue(CallPolicy.callerAllowed(all, null))
        assertFalse(CallPolicy.callerAllowed(all.copy(autoAnswer = false), null))
        assertFalse(CallPolicy.callerAllowed(all.copy(credentialsConfigured = false), null))
        assertFalse(CallPolicy.callerAllowed(all.copy(audioVerified = false), null))
    }

    @Test fun requiresExplicitSelectionOnMultiSimDevices() {
        val sim1 = CallSim("1", "SIM 1", true)
        val sim2 = CallSim("2", "SIM 2", false)
        assertEquals(sim1, CallPolicy.chooseSim(listOf(sim1), null))
        assertNull(CallPolicy.chooseSim(listOf(sim1, sim2), null))
        assertEquals(sim1, CallPolicy.chooseSim(listOf(sim1, sim2), "default"))
        assertEquals(sim2, CallPolicy.chooseSim(listOf(sim1, sim2), "2"))
        assertNull(CallPolicy.chooseSim(listOf(sim1.copy(isDefault = false), sim2), "default"))
        assertNull(CallPolicy.chooseSim(listOf(sim1), "missing"))
    }

    @Test fun mainlandMobileAllowlistAcceptsOnlyCompleteEquivalentNumbers() {
        val local = "13800138000"
        val enabled = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true)
        for (allowed in listOf(local, "+8613800138000", "008613800138000")) {
            val config = enabled.copy(allowedIncomingNumbers = setOf(allowed))
            assertTrue(CallPolicy.callerAllowed(config, local))
            assertTrue(CallPolicy.callerAllowed(config, "+86 138 0013 8000"))
            assertTrue(CallPolicy.callerAllowed(config, "0086-138-0013-8000"))
            assertFalse(CallPolicy.callerAllowed(config, "800138000"))
            assertFalse(CallPolicy.callerAllowed(config, "8613800138000"))
            assertFalse(CallPolicy.callerAllowed(config, "+113800138000"))
            assertFalse(CallPolicy.callerAllowed(config, "13800138001"))
        }
        val all = enabled.copy(incomingScope = IncomingScope.ALL)
        assertFalse(CallPolicy.callerAllowed(all, "*#06#"))
        assertFalse(CallPolicy.callerAllowed(all, "13800138000;22"))
    }

    @Test fun delayedStartupCannotAuthorizeAnExistingCallOrBroadenItsPolicy() {
        val number = "13800138000"
        val ready = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true,
            audioVerified = true, allowedIncomingNumbers = setOf("+8613800138000"))
        assertTrue(CallPolicy.canStartAutoAnswer(ready, ready, number, CallPolicy.AUTO_START_WINDOW_MS))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready, number, CallPolicy.AUTO_START_WINDOW_MS + 1))
        assertFalse(CallPolicy.canStartAutoAnswer(ready.copy(autoAnswer = false), ready, number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready.copy(allowedIncomingNumbers = emptySet()), ready, number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready.copy(allowedIncomingNumbers = emptySet()), number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready.copy(audioMode = CallAudioMode.REDIRECT), number, 100L))
    }

    @Test fun bypassWinsOverBothAllowAllAndExplicitAllowlist() {
        val ready = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true,
            allowedIncomingNumbers = setOf("13800138000", "028-8123-4567"),
            bypassIncomingNumbers = setOf("+86 138 0013 8000", "0086 28 8123 4567"))
        for (scope in listOf(IncomingScope.LIST, IncomingScope.ALL)) {
            val config = ready.copy(incomingScope = scope)
            for (number in listOf("13800138000", "008613800138000", "+8613800138000", "02881234567", "+862881234567")) {
                assertTrue(CallPolicy.isBypassed(config, number))
                assertFalse(CallPolicy.callerAllowed(config, number))
            }
        }
        for (number in listOf(null, "800138000", "81234567", "+113800138000", "13800138001")) {
            assertFalse(CallPolicy.isBypassed(ready, number))
        }
        assertTrue(CallPolicy.callerAllowed(ready.copy(incomingScope = IncomingScope.ALL), "13800138001"))
    }

    @Test fun pauseExpiresForNewCallsWithoutAnsweringCallsThatArrivedDuringIt() {
        val deadline = 100_000L
        val paused = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true,
            incomingScope = IncomingScope.ALL, autoAnswerPausedUntilMs = deadline)
        val resumed = paused.copy(autoAnswerPausedUntilMs = null)
        assertEquals(1L, CallPolicy.autoAnswerPauseRemainingMs(paused, deadline - 1L))
        assertEquals(0L, CallPolicy.autoAnswerPauseRemainingMs(paused, deadline))
        assertFalse(CallPolicy.callerAllowed(paused, null, deadline - 1L))
        assertTrue(CallPolicy.callerAllowed(paused, null, deadline))
        assertFalse(CallPolicy.canStartAutoAnswer(paused, resumed, null, 1L, nowMs = deadline))
        assertFalse(CallPolicy.canStartAutoAnswer(resumed, paused, null, 1L, nowMs = deadline - 1L))
        assertTrue(CallPolicy.canStartAutoAnswer(paused, paused, null, 1L, nowMs = deadline + 1L))
    }

    @Test fun ownerRulesArePassedAsAnExplicitSectionOfTheBoundedCallPrompt() {
        val ownerRules = "仅在来电者说包裹到了时，请对方留下取件地点。"
        val prompt = CallPolicy.prompt("确认对方称呼。", CallDirection.INCOMING, ownerRules)
        assertTrue(prompt.indexOf("不得索取或透露密码") in 0 until prompt.indexOf(ownerRules))
        assertTrue(prompt.contains("机主明确保存的通话规则"))
        assertTrue(prompt.contains("确认对方称呼。"))
        assertFalse(CallPolicy.prompt("", CallDirection.INCOMING).contains("机主明确保存的通话规则"))
    }

    @Test fun legacyGreetingMigratesOnlyTheExactOldDefault() {
        val legacyDefault = "您好，我是 Mika 的 AI 电话助手。请问有什么需要我转告？"
        val customWithOldName = "您好，我是 Mika 的 AI 电话助手。请留下可回拨的时间。"
        val currentDefault = "您好，我是 Mika AI 电话助手。请问有什么需要我转告？"
        assertEquals(currentDefault, CallConfig().greeting)
        assertEquals(currentDefault, CallPolicy.migrateDefaultGreeting(legacyDefault))
        assertEquals(currentDefault, CallPolicy.spokenGreeting(legacyDefault, hasTask = false))
        assertEquals(customWithOldName, CallPolicy.migrateDefaultGreeting(customWithOldName))
        assertEquals(customWithOldName, CallPolicy.spokenGreeting(customWithOldName, hasTask = false))
        assertEquals("您好，我是 Mika AI 电话助手。请问现在方便通话吗？",
            CallPolicy.spokenGreeting(currentDefault, hasTask = true))
    }

    @Test fun explicitGreetingIsUsedExactlyAndBlankMeansSilence() {
        for (hasTask in listOf(false, true)) {
            assertEquals("你好", CallPolicy.spokenGreeting("你好", hasTask))
            assertEquals("", CallPolicy.spokenGreeting("", hasTask))
            assertEquals("", CallPolicy.spokenGreeting(" \n\t", hasTask))
        }
    }

    @Test fun dialogueDoesNotDemandAnotherIntroductionAfterTheConfiguredGreeting() {
        for (direction in CallDirection.entries) {
            val prompt = CallPolicy.prompt("你好", direction)
            assertTrue(prompt.contains("你好"))
            assertFalse(prompt.contains("通话开始时明确说明自己是 AI"))
            assertFalse(prompt.contains("Flash phone"))
            assertTrue(prompt.contains("只有对方直接问接话者的身份"))
            assertTrue(prompt.contains("‘喂，能听到吗’，只答‘能听到，您说’"))
            assertFalse(prompt.contains("你是 AI 电话助手"))
        }
    }

    @Test fun explicitSimOverridesPreferenceAndMissingPreferenceNeverFallsBack() {
        val first = CallSim("sim-1", "SIM 1", true)
        val second = CallSim("sim-2", "SIM 2", false)
        val both = listOf(first, second)
        assertEquals(second, CallPolicy.chooseOutgoingSim(both, null, second.id))
        assertEquals(first, CallPolicy.chooseOutgoingSim(both, first.id, second.id))
        assertEquals(first, CallPolicy.chooseOutgoingSim(both, "default", second.id))
        assertEquals(first, CallPolicy.chooseOutgoingSim(both, "default", "sim-404"))
        assertNull(CallPolicy.chooseOutgoingSim(listOf(first), null, second.id))
        assertNull(CallPolicy.chooseOutgoingSim(both, "sim-404", second.id))
        assertNull(CallPolicy.chooseOutgoingSim(both, null, null))
    }

    @Test fun capturesAppOpBaselineWithoutGuessingOnUnknownOutput() {
        assertEquals("default", CallPolicy.previousAppOpMode("No operations.\n"))
        assertEquals("default", CallPolicy.previousAppOpMode("No operations.\nDefault mode: default\n"))
        assertNull(CallPolicy.previousAppOpMode("No operations.\nDefault mode: allow"))
        assertNull(CallPolicy.previousAppOpMode("No operations.\nPermission denial"))
        assertEquals("allow", CallPolicy.previousAppOpMode("MANAGE_ONGOING_CALLS: allow; time=1s"))
        assertEquals("ignore", CallPolicy.previousAppOpMode("Uid mode: MANAGE_ONGOING_CALLS: ignore"))
        assertNull(CallPolicy.previousAppOpMode("Permission denial"))
        assertNull(CallPolicy.previousAppOpMode("Uid mode: MANAGE_ONGOING_CALLS: deny\nMANAGE_ONGOING_CALLS: allow"))
    }

    @Test fun callSecondsClampKeepsZeroAsUnlimited() {
        assertEquals(0, CallPolicy.clampCallSeconds(0))
        assertEquals(0, CallPolicy.clampCallSeconds(-5))
        assertEquals(15, CallPolicy.clampCallSeconds(5))
        assertEquals(120, CallPolicy.clampCallSeconds(120))
        assertEquals(CallPolicy.MAX_SECONDS, CallPolicy.clampCallSeconds(9999))
    }

    @Test fun defaultCallSecondsIsTenMinutes() {
        assertEquals(600, CallPolicy.DEFAULT_SECONDS)
        assertEquals(600, CallConfig().maxCallSeconds)
    }

    @Test fun closingLineIsRecognizedAsAnAssistantClosing() {
        assertTrue(CallFarewellPolicy.isAssistantClosing(CallPolicy.CLOSING_LINE))
    }

    @Test fun closingDelayOnlyWhenItFitsBeforeTheCap() {
        assertNull(CallPolicy.closingDelayMs(null))
        assertNull(CallPolicy.closingDelayMs(10_000))
        assertEquals(5_000L, CallPolicy.closingDelayMs(CallPolicy.CLOSING_LEAD_MS + 5_000L))
    }
}
