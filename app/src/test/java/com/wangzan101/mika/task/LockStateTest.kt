package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockStateTest {
    private var interactive = false
    private var keyguardLocked = true
    private var deviceLocked = true
    private var reads = 0

    private fun TestScope.lockState() = LockState({ reads++; LockState.Reading(interactive, keyguardLocked, deviceLocked) }, backgroundScope)

    private fun TestScope.tick() {
        advanceTimeBy(DeviceStateWatcher.POLL_MS)
        runCurrent()
    }

    // 解锁的广播来得比锁屏界面退下早:读到的还是锁着,之后没有广播,也要自己看到解锁
    @Test fun unlockSeenWithoutAnotherBroadcast() = runTest {
        val lock = lockState()
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        interactive = true
        lock.refresh() // SCREEN_ON 或 USER_PRESENT:亮了,锁屏界面还在
        tick()
        assertFalse(passed)
        deviceLocked = false // 设备真正解锁(锁屏界面退下),没有广播
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())

        // 读到解锁后不再读
        val unlocked = reads
        repeat(10) { tick() }
        assertEquals(unlocked, reads)
    }

    // #638:熄屏期间轮询不停止;亮屏、解锁的广播全都不来,也要靠轮询自动继续。
    // 旧守卫用熄屏时的快照(interactive=false)挡住重读,漏一条 SCREEN_ON 就永远停在「等你解锁手机」。
    @Test fun screenOnAndUnlockSeenWithoutAnyBroadcast() = runTest {
        val lock = lockState() // 熄屏、锁着
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        val dark = reads
        repeat(5) { tick() } // 熄屏期间也在读:亮屏后没有广播可依赖
        assertTrue(reads > dark)
        assertFalse(passed)

        interactive = true // 亮屏,SCREEN_ON 没来
        tick()
        assertFalse(passed) // 设备还锁着,不放行
        deviceLocked = false // 解锁,USER_PRESENT 也没来
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    // 部分 ROM 上锁屏界面先退下去(keyguardLocked 已 false)、设备仍未解锁(deviceLocked 仍 true):
    // 不能因为锁屏界面没了就停轮询,否则 deviceLocked 永远读不到 false,任务卡在「等你解锁手机」。
    @Test fun unlockSeenWhenKeyguardClearsBeforeDevice() = runTest {
        interactive = true
        keyguardLocked = true
        deviceLocked = true
        val lock = lockState()
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        keyguardLocked = false // 锁屏界面先退
        tick()
        assertFalse(passed) // 设备还锁着,不能放行
        deviceLocked = false // 设备真正解锁,没有广播
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    // 订阅刚开始就重读一次:漏掉的亮屏/解锁不用再等一个轮询周期
    @Test fun staleLockedHealsOnSubscribe() = runTest {
        val lock = lockState() // 建立时读到:熄屏、锁着
        interactive = true
        deviceLocked = false // 系统其实已亮屏解锁,但没有广播、之前没有订阅者在读

        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent() // 订阅开始,立即重读,不用等第一个 500ms
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    // 没人看这份状态时不读
    @Test fun noReadsWithoutSubscribers() = runTest {
        interactive = true
        val lock = lockState()
        runCurrent()
        val start = reads
        repeat(10) { tick() }
        assertEquals(start, reads)
        assertTrue(lock.locked.value)
    }
}
