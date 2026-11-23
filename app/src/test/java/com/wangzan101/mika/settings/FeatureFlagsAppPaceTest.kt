package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按应用操作节奏:首次读取把内置 careful App 预置为「像人」,之后只看存下的列表;删光也不再重新预置。 */
class FeatureFlagsAppPaceTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun seedsBuiltInCarefulAppsOnFirstRead() {
        val f = flags()
        val overrides = f.appPaceOverrides()
        // 内置 App 知识标 careful 的全部包名,预置为「像人」
        val careful = GuideCatalog.loadBundled().carefulApps()
        assertTrue(careful.isNotEmpty())
        assertEquals(careful.associateWith { OperatingPace.HUMAN }, overrides)
        // 已落盘:第二次读同一份,不再重新预置
        assertEquals(overrides, f.appPaceOverrides())
    }

    @Test fun keepsEditsAndEmptyMeansEmpty() {
        val f = flags()
        f.appPaceOverrides()
        f.setAppPaceOverrides(mapOf("com.xing" to OperatingPace.FAST))
        assertEquals(mapOf("com.xing" to OperatingPace.FAST), f.appPaceOverrides())
        // 删光 = 明确不再按应用区分,不重新预置
        f.setAppPaceOverrides(emptyMap())
        assertEquals(emptyMap<String, OperatingPace>(), f.appPaceOverrides())
    }

    @Test fun survivesOddPackageNamesAndUnknownPaces() {
        val f = flags()
        f.setAppPaceOverrides(mapOf("com.\"we\\ird\"" to OperatingPace.HUMAN))
        assertEquals(mapOf("com.\"we\\ird\"" to OperatingPace.HUMAN), f.appPaceOverrides())
        // 手写进来的坏值:认不出的档名跳过(向前兼容,旧版存的档位新版改名不至于整表作废)
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putString("app_pace_overrides", """{"com.a":"FAST","com.b":"quick"}""").commit()
        assertEquals(mapOf("com.a" to OperatingPace.FAST), FeatureFlags(prefs).appPaceOverrides())
    }

    @Test fun corruptStoredJsonFallsBackToSeeding() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putString("app_pace_overrides", "{oops").commit()
        val overrides = FeatureFlags(prefs).appPaceOverrides()
        assertEquals(GuideCatalog.loadBundled().carefulApps().associateWith { OperatingPace.HUMAN }, overrides)
    }
}
