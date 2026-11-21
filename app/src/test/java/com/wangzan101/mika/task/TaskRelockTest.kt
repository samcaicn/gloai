package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务解锁了手机之后留下的回锁义务:系统确认已锁之前一直挂着。 */
class TaskRelockTest {
    @Test fun keepsObligationUntilSystemConfirmsLock() {
        val c = TaskRelock()
        c.unlocked(7)
        assertEquals(TaskRelock.Decision.WAIT, c.decide(runsRemain = true, locked = false, interactions = 7, canLock = true))
        assertEquals(TaskRelock.Decision.LOCK, c.decide(runsRemain = false, locked = false, interactions = 7, canLock = true))
        // 系统没确认前义务还在:回锁被拒或未生效都能重试
        assertTrue(c.hasPending())
        assertEquals(TaskRelock.Decision.LOCK, c.decide(runsRemain = false, locked = false, interactions = 7, canLock = true))
        c.confirmed()
        assertFalse(c.hasPending())
        assertEquals(TaskRelock.Decision.NONE, c.decide(runsRemain = false, locked = false, interactions = 7, canLock = true))
    }

    @Test fun onlyLocksAfterAnIdleGracePeriod() {
        val c = TaskRelock()
        c.unlocked(3)
        // 等待期间主屏有新交互:先等空闲,不按旧基线锁
        assertEquals(TaskRelock.Decision.WAIT_IDLE, c.decide(runsRemain = false, locked = false, interactions = 4, canLock = true))
        // 空闲期里交互又变了:不接受、义务还在
        assertFalse(c.acceptIdle(start = 4, current = 5))
        assertTrue(c.hasPending())
        // 空闲期结束交互没再变:接受此刻的计数为新基线
        assertTrue(c.acceptIdle(start = 5, current = 5))
        assertEquals(TaskRelock.Decision.LOCK, c.decide(runsRemain = false, locked = false, interactions = 5, canLock = true))
        assertEquals(TaskRelock.Decision.WAIT_IDLE, c.decide(runsRemain = false, locked = false, interactions = 6, canLock = true))
    }

    @Test fun unavailableLockPathWarnsWithoutDroppingObligation() {
        val c = TaskRelock()
        c.unlocked(5)
        assertEquals(TaskRelock.Decision.WARN, c.decide(runsRemain = false, locked = false, interactions = 5, canLock = false))
        assertTrue(c.hasPending())
        // 已经锁上了(比如用户自己锁的):义务了结
        assertEquals(TaskRelock.Decision.NONE, c.decide(runsRemain = false, locked = true, interactions = 5, canLock = false))
        assertFalse(c.hasPending())
    }

    @Test fun nothingToRelockWithoutAnUnlock() {
        val c = TaskRelock()
        assertFalse(c.hasPending())
        assertEquals(TaskRelock.Decision.NONE, c.decide(runsRemain = false, locked = false, interactions = 0, canLock = true))
    }
}
