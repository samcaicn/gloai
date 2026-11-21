package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.net.ProxyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 项目 3 的切换决策表:哪种错误换下一个、哪种不换,逐类钉住。 */
class FailoverTest {
    private val tags = listOf("OpenRouter", "APInex", "Groq")

    @Test fun quotaSwitchesImmediately() {
        assertEquals(1, Failover.nextIndex(ModelError.QuotaExceeded(), 0, tags, 3))
    }

    @Test fun modelSideFailuresSwitch() {
        assertEquals(1, Failover.nextIndex(ModelError.Auth(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.IllegalHeader("Authorization"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ModelNotFound("x"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ModelNotAvailable("x"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ToolsUnsupported("x"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.SignInExpired(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.AccountPlanNotAllowed("xAI"), 0, tags, 3))
        assertEquals(2, Failover.nextIndex(ModelError.ChatGptLimitReached(), 1, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ChatGptNotEligible(), 0, tags, 3))
    }

    @Test fun transientFailuresSwitchAfterRetryGivesUp() {
        assertEquals(1, Failover.nextIndex(ModelError.RateLimited(null), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Server(503), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Server(400), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Network(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.StreamCut(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Proxy(ProxyFailure.Unreachable), 0, tags, 3))
    }

    @Test fun groupUnavailableSwitchesToNextChainLink() {
        // 组内已经无人可用/用尽:链上还有健康模型时要能进下一条
        assertEquals(1, Failover.nextIndex(ModelError.GroupUnavailable("g"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.GroupExhausted("g"), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.GroupUnavailable("g"), 1, tags, 2))
    }

    @Test fun taskBudgetNeverSwitches() {
        assertNull(Failover.nextIndex(ModelError.TaskBudgetExceeded(100, 100), 0, tags, 3))
    }

    @Test fun dailyBudgetOnlySwitchesAcrossProviders() {
        val blown = ModelError.DailyBudgetExceeded("Gateway", 100, 100, providerId = "primary-id")
        // 下一个是同一家:不换
        assertNull(Failover.nextIndex(blown, 0, listOf("primary-id", "primary-id"), 2))
        // 同家的跳过,换到不同家
        assertEquals(2, Failover.nextIndex(blown, 0, listOf("primary-id", "primary-id", "backup-id"), 3))
        // 备援的身份未知时不换。
        assertNull(Failover.nextIndex(blown, 0, listOf("primary-id", null), 2))
    }

    @Test fun nonModelFailuresDoNotSwitch() {
        assertNull(Failover.nextIndex(ModelError.ContentRefused(), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.BadResponse("x"), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.ContextTooLong(), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.ReasoningEffortRejected("x"), 0, tags, 3))
    }

    @Test fun chainEndDoesNotSwitch() {
        assertNull(Failover.nextIndex(ModelError.QuotaExceeded(), 2, tags, 3))
        assertNull(Failover.nextIndex(ModelError.QuotaExceeded(), 0, tags, 1))
    }
}
