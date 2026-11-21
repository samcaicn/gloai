package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.*
import org.junit.Test

class IncomingCallTypeTest {
    private val number = "13800138000"
    private val ready = CallConfig(enabled = true, autoAnswer = true, audioVerified = true, credentialsConfigured = true,
        incomingScope = IncomingScope.TYPES, allowedCallTypes = setOf(IncomingCallType.PARCEL), allowedIncomingNumbers = setOf(number))

    @Test fun onlyUnambiguousSelectedTypeAllowsAutomaticAnswer() {
        val parcel = IncomingCallType.fromLabel("该号码已被12位用户标记为“快递物流”")
        assertEquals(IncomingCallType.PARCEL, parcel)
        for (word in listOf("外卖配送", "快递送餐", "配送", "快递小王", "举报快递", "")) {
            assertFalse(CallPolicy.callerAllowed(ready, number, type = IncomingCallType.fromLabel(word)))
        }
        assertFalse(CallPolicy.callerAllowed(ready, null, type = parcel))
        assertTrue(CallPolicy.canStartAutoAnswer(ready, ready, number, 8000, type = parcel))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready, number, 8001, type = parcel))
        val removed = ready.copy(allowedCallTypes = emptySet())
        assertFalse(CallPolicy.canStartAutoAnswer(removed, ready, number, 200, type = parcel))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, removed, number, 200, type = parcel))
        assertFalse(CallPolicy.callerAllowed(ready.copy(bypassIncomingNumbers = setOf("+86$number")), number, type = parcel))
        assertNull(IncomingCallType.fromLabel("快递", requireMark = true))
        assertEquals(parcel, IncomingCallType.fromLabel("快递（24人标记）（腾讯）", requireMark = true))
    }

    @Test fun evidenceBelongsToOneNumberAndConflictingLabelsCancelEligibility() {
        val evidence = IncomingTypeEvidence()
        val key = CallPolicy.incomingMatchKey(number)!!
        val parcel = setOf(IncomingCallType.PARCEL)
        assertFalse(evidence.accept(number, setOf("8000"), parcel))
        assertFalse(evidence.accept(number, setOf(key, "12345678"), parcel))
        assertTrue(evidence.accept(number, setOf(key), parcel))
        assertEquals(IncomingCallType.PARCEL, evidence.typeFor("+86$number"))
        assertNull(evidence.typeFor("13800138001"))
        assertTrue(evidence.accept(number, setOf(key), setOf(IncomingCallType.TAKEOUT)))
        assertNull(evidence.typeFor(number))
    }

    @Test fun strangersRequireAConfirmedLookupAndRespectBothPolicies() {
        val config = ready.copy(incomingScope = IncomingScope.STRANGERS)
        assertTrue(config.hasIncomingSelection)
        assertTrue(CallPolicy.canStartAutoAnswer(config, config, number, 500, isStranger = true))
        assertFalse(CallPolicy.callerAllowed(config, number))
        assertFalse(CallPolicy.callerAllowed(config, null, isStranger = true))
        assertFalse(CallPolicy.callerAllowed(config, "Private", isStranger = true))
        val bypass = config.copy(bypassIncomingNumbers = setOf("+86$number"))
        assertFalse(CallPolicy.canStartAutoAnswer(bypass, config, number, 500, isStranger = true))
        assertFalse(CallPolicy.canStartAutoAnswer(config, bypass, number, 500, isStranger = true))
        assertFalse(CallPolicy.canStartAutoAnswer(config, config, number, 8001, isStranger = true))
        val prefs = MemoryPrefs()
        CallSettings(prefs).save(config)
        assertEquals(IncomingScope.STRANGERS, CallSettings(prefs).state.value.incomingScope)
    }

    @Test fun legacyScopesAndTypeChoicesSurviveReload() {
        val prefs = MemoryPrefs()
        assertEquals(IncomingScope.LIST, CallSettings(prefs).state.value.incomingScope)
        prefs.edit().putBoolean("allow_all", true).commit()
        val settings = CallSettings(prefs)
        assertEquals(IncomingScope.ALL, settings.state.value.incomingScope)
        settings.save(settings.state.value.copy(incomingScope = IncomingScope.TYPES, allowedCallTypes = ready.allowedCallTypes))
        val restored = CallSettings(prefs).state.value
        assertEquals(IncomingScope.TYPES, restored.incomingScope)
        assertEquals(ready.allowedCallTypes, restored.allowedCallTypes)
        assertFalse(prefs.getBoolean("allow_all", true))
    }
}
