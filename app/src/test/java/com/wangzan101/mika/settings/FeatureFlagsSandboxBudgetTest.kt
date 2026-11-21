package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 每任务沙箱命令累计时长:默认 30 分钟,只认那几档,别的值按默认算。 */
class FeatureFlagsSandboxBudgetTest {
    @Test fun defaultsToThirtyMinutesAndKeepsOnlyKnownTiers() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertEquals(30, flags.sandboxBudgetMinutes())
        flags.setSandboxBudgetMinutes(120)
        assertEquals(120, flags.sandboxBudgetMinutes())
        flags.setSandboxBudgetMinutes(30)
        assertEquals(30, flags.sandboxBudgetMinutes())
        // 不在几档里的(手改的、以后去掉的一档)按默认算
        flags.setSandboxBudgetMinutes(45)
        assertEquals(30, flags.sandboxBudgetMinutes())
        prefs.edit().putInt("sandbox_budget_limit_minutes", 7).commit()
        assertEquals(30, flags.sandboxBudgetMinutes())
    }
}
