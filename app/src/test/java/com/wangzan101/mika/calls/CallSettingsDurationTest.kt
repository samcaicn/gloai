package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSettingsDurationTest {
    @Test fun unlimitedDurationSurvivesSaveAndReload() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(maxCallSeconds = 0))
        assertEquals(0, prefs.getInt("max_seconds", -1))
        assertEquals(0, CallSettings(prefs).state.value.maxCallSeconds)
    }

    @Test fun positiveDurationIsClampedToTheAllowedRange() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(maxCallSeconds = 5))
        assertEquals(15, CallSettings(prefs).state.value.maxCallSeconds)
        settings.save(settings.state.value.copy(maxCallSeconds = 9_999))
        assertEquals(CallPolicy.MAX_SECONDS, CallSettings(prefs).state.value.maxCallSeconds)
    }
}
