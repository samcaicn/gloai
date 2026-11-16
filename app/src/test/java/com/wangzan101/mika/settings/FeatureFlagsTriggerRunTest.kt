package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 定时任务最长运行时间:默认不限制,只认那几档,别的值按不限制算。 */
class FeatureFlagsTriggerRunTest {
    @Test fun defaultsToNoLimitAndKeepsOnlyKnownChoices() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertNull(flags.triggerRunMinutes())
        flags.setTriggerRunMinutes(30)
        assertEquals(30, flags.triggerRunMinutes())
        flags.setTriggerRunMinutes(null)
        assertNull(flags.triggerRunMinutes())
        // 不在几档里的(手改的、以后去掉的一档)按不限制算
        flags.setTriggerRunMinutes(45)
        assertNull(flags.triggerRunMinutes())
        prefs.edit().putInt("trigger_run_limit_minutes", 7).commit()
        assertNull(flags.triggerRunMinutes())
    }
}
