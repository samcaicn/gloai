package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自绘/反自动化界面（微信、GitHub 客户端等）注入无效时的结局文案：要告诉用户「该应用不响应自动化」，
 * 而不是只说「读不到控件」这种症状。
 */
class StopRulesTest {
    @Test fun treeBlindNoProgressSaysAppUnresponsive() {
        val v = GuardVerdict("no_progress", Severity.Stop, buildJsonObject { put("treeBlind", true) })
        assertTrue(StopRules.guard(v).reason.contains("不响应"))
    }

    @Test fun plainNoProgressKeepsGenericMessage() {
        val v = GuardVerdict("no_progress", Severity.Stop, buildJsonObject { put("unchanged", 3) })
        assertTrue(StopRules.guard(v).reason.contains("画面一直没有变化"))
    }
}
