package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGateTest {
    @get:Rule val texts = ChineseTexts()

    private val locked = MutableStateFlow(false)
    private val shellUp = MutableStateFlow(true)
    private var moveBackCalls = 0
    private var moveBackResult = true
    private val gate = TaskGate(locked, shellUp) { moveBackCalls++; moveBackResult }
    private val reported = mutableListOf<TaskState?>()

    @Test
    fun passesImmediatelyWhenNothingBlocks() = runTest {
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun nonInteractiveWaitsForUnlockThenResumesByItself() = runTest {
        locked.value = LockState.Reading(interactive = false, keyguardLocked = false, deviceLocked = false).locked
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        locked.value = LockState.Reading(interactive = true, keyguardLocked = true, deviceLocked = true).locked // 亮屏但还在锁屏界面
        runCurrent()
        assertFalse(passed)
        locked.value = LockState.Reading(interactive = true, keyguardLocked = false, deviceLocked = false).locked
        runCurrent()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    @Test
    fun gateRefreshesSystemLockStateBeforeAllowingWrite() = runTest {
        var systemLocked = true
        var unlocks = 0
        val refreshed = TaskGate(locked, shellUp,
            refreshLocked = { locked.value = systemLocked }, // 熄屏已发生，广播对应的状态流还没更新
            unattendedUnlock = {
                unlocks++
                systemLocked = false
                locked.value = false
                "unlocked"
            }) { true }

        refreshed.pass { reported += it }

        assertEquals(1, unlocks)
        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun unattendedTaskUnlocksThenContinues() = runTest {
        locked.value = true
        var unlocks = 0
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = {
            unlocks++
            locked.value = false
            "unlocked"
        }) { true }

        unattended.pass { reported += it }

        assertEquals(1, unlocks)
        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun unattendedTaskFailsWhenUnlockIsUnavailable() = runTest {
        locked.value = true
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = { "unsupported_keyguard" }) { true }

        val result = withTimeoutOrNull(1_000) { runCatching { unattended.pass { reported += it } } }

        val error = result?.exceptionOrNull()
        assertTrue(error is UnattendedLockException)
        assertEquals("unsupported_keyguard", (error as UnattendedLockException).status)
        assertTrue(error.message.orEmpty().endsWith("[unsupported_keyguard]"))
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
    }

    @Test
    fun channelUnlockFailureUsesItsOwnGuidanceAndDoesNotRetry() = runTest {
        locked.value = true
        var unlocks = 0
        val channel = TaskGate(locked, shellUp,
            unattendedUnlock = { unlocks++; "disabled" },
            unlockFailureMessage = { tr(R.string.wechat_unlock_failed) }) { true }

        val result = withTimeoutOrNull(1_000) { runCatching { channel.pass { reported += it } } }

        val error = result?.exceptionOrNull()
        assertTrue(error is UnattendedLockException)
        assertEquals("${tr(R.string.wechat_unlock_failed)} [disabled]", error?.message)
        assertEquals(1, unlocks)
        assertTrue(locked.value)
        assertEquals(listOf(TaskState.Held(TaskGate.LOCKED)), reported)
    }

    /** 已开始、本地授权的任务解锁失败:不再杀任务、不再重复注入凭据,停在锁屏等用户手动解锁。 */
    @Test
    fun midRunFailedUnlockWaitsForManualUnlockWithoutRetryingCredential() = runTest {
        var attempts = 0
        var failure: String? = null
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { attempts++; "attempt_blocked" },
            recoverUnlockFailure = { true },
            onRecoveryFailure = { failure = it }) { true }
        assertFalse(recovering.pass { reported += it })
        locked.value = true
        var resumed = false
        var needsFreshScreen: Boolean? = null
        launch { needsFreshScreen = recovering.pass { reported += it }; resumed = true }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals("attempt_blocked", failure)
        assertFalse(resumed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(resumed)
        assertEquals(true, needsFreshScreen)
        assertEquals(1, attempts)
    }

    /** 已开始、本地授权的任务自动解锁成功:关卡告诉调用方锁屏周期发生过,准备好的动作要重读屏再做。 */
    @Test
    fun midRunSuccessfulAutoUnlockAlsoRequiresFreshScreen() = runTest {
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { locked.value = false; "unlocked" },
            recoverUnlockFailure = { true }) { true }
        assertFalse(recovering.pass { reported += it })
        locked.value = true
        assertTrue(recovering.pass { reported += it })
        assertFalse(locked.value)
    }

    /** 解锁助手中途出错:按内部错误处理,转成等待用户解锁,不向外抛错丢进度。 */
    @Test
    fun midRunUnlockExceptionWaitsForUserInsteadOfLosingProgress() = runTest {
        var reportedStatus: String? = null
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { error("helper disconnected") }, recoverUnlockFailure = { true },
            onRecoveryFailure = { reportedStatus = it }) { true }
        assertFalse(recovering.pass { reported += it })
        locked.value = true
        var resumed = false
        launch { recovering.pass { reported += it }; resumed = true }
        runCurrent()
        assertEquals("internal_error", reportedStatus)
        assertFalse(resumed)
        locked.value = false
        runCurrent()
        assertTrue(resumed)
    }

    /** 还没开始过的任务解锁失败照旧失败收尾:没有进度可保。 */
    @Test
    fun initialFailureStillEndsUnattendedRun() = runTest {
        locked.value = true
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { "unsupported_keyguard" }, recoverUnlockFailure = { true }) { true }
        val outcome = runCatching { recovering.pass { reported += it } }
        assertTrue(outcome.exceptionOrNull() is UnattendedLockException)
    }

    /** 没有解锁授权的任务(用户在场)解锁助手出错:照旧失败收尾,不装作能恢复。 */
    @Test
    fun unauthorizedRunStillFailsWhenUnlockErrors() = runTest {
        locked.value = true
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { error("helper disconnected") }, recoverUnlockFailure = { false }) { true }
        val outcome = runCatching { recovering.pass { reported += it } }
        assertTrue(outcome.exceptionOrNull() is UnattendedLockException)
        assertEquals("internal_error", (outcome.exceptionOrNull() as UnattendedLockException).status)
    }

    /** 用户在场的任务中途锁屏、手动解锁回来:关卡同样报锁屏周期,准备好的动作先重读屏。 */
    @Test
    fun attendedRunResumedFromManualUnlockRequiresFreshScreen() = runTest {
        assertFalse(gate.pass { reported += it })
        locked.value = true
        var passed = false
        var needsFreshScreen: Boolean? = null
        launch { needsFreshScreen = gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(passed)
        assertEquals(true, needsFreshScreen)
    }

    @Test
    fun unattendedTaskTrustsRefreshedSystemStateOverAttemptOutcome() = runTest {
        locked.value = true
        // 流程因权限等原因返回时，用户可能刚好已经手动解锁；实际系统状态才是依据。
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = {
            locked.value = false
            "disabled"
        }) { true }

        unattended.pass { reported += it }

        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun movedAwayPausesUntilUserContinuesThenMovesBack() = runTest {
        gate.resume() // 暂停之前的「继续」不算数
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), reported.last())
        assertEquals(0, moveBackCalls)

        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)

        // 挪回来之后不再暂停
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    /** 用户在借出的页面上动手:不论哪种屏都停下,点「继续」当场放行;之前的「继续」不算,也不挪任何东西。 */
    @Test
    fun userOnPagePausesAnyStepUntilContinue() = runTest {
        gate.onUserOnPage()
        assertTrue(gate.userOnPagePending)
        var passed = false
        launch { gate.pass({ false }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE)), reported.last())
        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertFalse(gate.userOnPagePending)
        assertEquals(0, moveBackCalls)
        // 还没走到关卡就点了继续:不再停
        gate.onUserOnPage()
        gate.resume()
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun failedMoveBackStaysPaused() = runTest {
        moveBackResult = false
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        gate.resume()
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVE_BACK_FAILED)), reported.last())

        moveBackResult = true
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun helperLossPausesUntilBackAndUserContinues() = runTest {
        shellUp.value = false
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())

        gate.resume() // 助手还没恢复:继续无效
        runCurrent()
        assertFalse(passed)

        shellUp.value = true
        runCurrent()
        assertFalse(passed) // 恢复了也要等用户点继续
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun movedAwayMeansOnMainAndNotOnAgentDisplay() {
        assertTrue(DeviceStateWatcher.isMovedAway(agentTop = 5, mainTop = 7, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = 7, mainTop = 3, taskId = 7))
        // Agent 自己关掉了 App:不在任何屏最上面,不算被拿走
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = 3, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = -1, taskId = -1))
    }

    @Test
    fun continueTappedRightAsPauseIsShownIsNotLost() = runTest {
        gate.onTaskMovedAway()
        var passed = false
        // 通知一显示「已暂停」用户就点了继续:点击发生在 setWaiting 回调期间
        launch {
            gate.pass { reported += it; if (it is TaskState.Held && moveBackCalls == 0) gate.resume() }
            passed = true
        }
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)
    }

    @Test
    fun exposesPendingMoveForImmediateNotice() {
        assertFalse(gate.movedAwayPending)
        gate.onTaskMovedAway()
        assertTrue(gate.movedAwayPending)
    }

    @Test
    fun continueTappedAfterMoveDetectedButBeforeGateCounts() = runTest {
        // 发现被挪走后通知立即显示暂停;用户在 Agent 走到 gate 之前就点了继续
        gate.onTaskMovedAway()
        gate.resume()
        gate.pass { reported += it }
        assertEquals(1, moveBackCalls)
        assertEquals(null, reported.last())
    }

    /** 网页、沙箱上的步骤不需要内置助手,也不会被挪到主屏:只看锁屏。 */
    @Test
    fun shellAndMovedAwayOnlyCheckedForPhoneSteps() = runTest {
        shellUp.value = false
        gate.onTaskMovedAway()
        gate.pass({ false }) { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
        var passed = false
        launch { gate.pass({ true }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())
        // 锁屏照样挡网页的步骤
        reported.clear()
        locked.value = true
        var webPassed = false
        launch { gate.pass({ false }) { reported += it }; webPassed = true }
        runCurrent()
        assertFalse(webPassed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(webPassed)
        coroutineContext.cancelChildren()
    }
}
