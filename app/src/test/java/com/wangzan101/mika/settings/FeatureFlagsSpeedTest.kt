package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsSpeedTest {
    @Test fun freshInstallTurnsOnDefaultsIncludingReplay() {
        val f = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).speedFlags()
        assertTrue(f.inlineScreen && f.batchSteps && f.adaptiveVision && f.replay && f.lightSteps && f.tablesAtBirth)
    }

    @Test fun earlierUpgradeOnlyAddsReplayOnce() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        // 上一版升级过、用户又关掉了连续操作:这次只加上回放、大小模型分工和界面压表,不动别的
        prefs.edit().putString("speed_flags", "inlineScreen,adaptiveVision").putBoolean("speed_defaults_applied_v2", true).commit()
        val flags = FeatureFlags(prefs)
        assertEquals(SpeedFlags(inlineScreen = true, adaptiveVision = true, replay = true, lightSteps = true, tablesAtBirth = true), flags.speedFlags())
        // 升级之后用户手动关掉回放就保持关闭
        flags.setSpeed(flags.speedFlags().copy(replay = false))
        assertFalse(FeatureFlags(prefs).speedFlags().replay)
    }
}
