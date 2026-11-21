package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsPersonaTest {
    /** 发行版和调试版一样默认开;关掉后记得住。 */
    @Test fun defaultsOnAndPersistsChanges() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertTrue(flags.persona())
        flags.setPersona(false)
        assertFalse(FeatureFlags(prefs).persona())
        flags.setPersona(true)
        assertTrue(FeatureFlags(prefs).persona())
    }
}
