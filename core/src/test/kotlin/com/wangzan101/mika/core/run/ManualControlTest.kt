package com.wangzan101.mika.core.run

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualControlTest {
    @Test fun waitsForActionAndNestedScriptStepsBeforeGivingControl() = runTest {
        val control = ManualControl()
        val finish = CompletableDeferred<Unit>()
        var nested = false
        val action = launch { control.automatic { finish.await(); control.plan { control.automatic { nested = true } } } }
        runCurrent()
        val take = async { control.begin() }
        runCurrent()
        assertEquals(ManualControl.State.Requesting, control.state.value)
        assertFalse(take.isCompleted)
        finish.complete(Unit)
        action.join()
        assertTrue(take.await())
        assertTrue(nested)
        assertEquals(ManualControl.State.Manual, control.state.value)
        control.end()
    }

    @Test fun stalePlanCannotClickAfterUserHasChangedTheScreen() = runTest {
        val control = ManualControl()
        val proceed = CompletableDeferred<Unit>()
        var clicked = false
        val plan = async {
            runCatching { control.plan { proceed.await(); control.automatic { clicked = true } } }.exceptionOrNull()
        }
        runCurrent()
        assertTrue(control.begin())
        control.end()
        // 别处的读屏也不能使接管之前准备的动作重新有效。
        control.automatic(observation = true) {}
        proceed.complete(Unit)
        assertTrue(plan.await() is ManualControl.Interrupted)
        assertFalse(clicked)
    }

    @Test fun cannotResumeWritingUntilSuccessfulFreshObservation() = runTest {
        val control = ManualControl()
        control.begin(); control.end()
        assertTrue(runCatching { control.automatic {} }.exceptionOrNull() is ManualControl.Interrupted)
        control.automatic(observation = true, refreshed = { it: Boolean -> it }) { false }
        assertTrue(runCatching { control.automatic {} }.exceptionOrNull() is ManualControl.Interrupted)
        control.automatic(observation = true, refresh = false) {} // 子任务的读不能替主任务确认画面。
        assertTrue(runCatching { control.automatic {} }.exceptionOrNull() is ManualControl.Interrupted)
        control.automatic(observation = true) {}
        assertEquals("done", control.automatic { "done" })
    }

    @Test fun handingBackWaitsForInputAndRejectsLaterInput() = runTest {
        val control = ManualControl()
        assertTrue(control.begin())
        val finish = CompletableDeferred<Unit>()
        val input = async { control.userInput { finish.await(); true } }
        runCurrent()
        val giveBack = launch { control.end() }
        runCurrent()
        assertFalse(giveBack.isCompleted)
        assertEquals(ManualControl.State.Manual, control.state.value)
        finish.complete(Unit)
        assertEquals(true, input.await())
        giveBack.join()
        assertNull(control.userInput { error("must not inject") })
    }

    @Test fun timedOutRequestNeverGainsControlLater() = runTest {
        val control = ManualControl()
        val finish = CompletableDeferred<Unit>()
        val action = launch { control.automatic { finish.await() } }
        runCurrent()
        val take = async { control.begin(timeoutMs = 100) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertFalse(take.await())
        finish.complete(Unit); action.join()
        assertEquals(ManualControl.State.Automatic, control.state.value)
        assertNull(control.userInput { true })
    }

    @Test fun cancelledRequestAndClosedRunNeverAcceptInput() = runTest {
        val control = ManualControl()
        val finish = CompletableDeferred<Unit>()
        val action = launch { control.automatic { finish.await() } }
        runCurrent()
        val request = launch { control.begin() }
        runCurrent(); request.cancel(); request.join()
        assertEquals(ManualControl.State.Automatic, control.state.value)
        finish.complete(Unit); action.join()
        assertTrue(control.begin())
        control.close()
        assertNull(control.userInput { true })
        assertFalse(control.begin())
    }

    @Test fun controlIsScopedToOneRun() = runTest {
        val first = ManualControl()
        val second = ManualControl()
        assertTrue(first.begin())
        assertEquals(7, second.automatic { 7 })
        assertNull(second.userInput { true })
        first.close(); second.close()
    }

    @Test fun childPlansRefreshIndependentlyWithoutRevivingParentPlan() = runTest {
        val control = ManualControl()
        val parent = Any()
        val child = Any()
        val parentObservation = ManualControl.Observer()
        val childObservation = ManualControl.Observer()
        control.plan(parent) {
            assertTrue(control.begin())
            control.end()
            control.plan(child) {
                control.automatic(observation = true, observer = childObservation) {}
                assertEquals(7, control.automatic(observer = childObservation) { 7 })
            }
            assertTrue(runCatching { control.automatic(observer = parentObservation) {} }.exceptionOrNull() is ManualControl.Interrupted)
        }
        // 子任务的观察不能替父任务清除读屏要求；父任务的新计划也需自行读屏。
        control.plan(parent) {
            assertTrue(runCatching { control.automatic(observer = parentObservation) {} }.exceptionOrNull() is ManualControl.Interrupted)
            control.automatic(observation = true, observer = parentObservation) {}
            control.automatic(observer = parentObservation) {}
        }
    }
    @Test fun resizingWaitsForAutomaticActionAndInvalidatesItsObservation() = runTest {
        val control = ManualControl()
        val finish = CompletableDeferred<Unit>()
        val action = launch { control.automatic { finish.await() } }
        runCurrent()
        var resized = false
        val resize = async { control.reconfigure { resized = true; true } }
        runCurrent()
        assertFalse(resized)
        finish.complete(Unit); action.join()
        assertEquals(true, resize.await())
        assertEquals(ManualControl.State.Automatic, control.state.value)
        assertTrue(runCatching { control.automatic {} }.exceptionOrNull() is ManualControl.Interrupted)
        control.automatic(observation = true) {}
        control.automatic {}
    }

    @Test fun resizingDuringManualControlWaitsForInputAndHandbackWaitsForResize() = runTest {
        val control = ManualControl()
        control.begin()
        val finishInput = CompletableDeferred<Unit>()
        val finishResize = CompletableDeferred<Unit>()
        val input = launch { control.userInput { finishInput.await(); true } }
        runCurrent()
        var resizing = false
        val resize = async { control.reconfigure { resizing = true; finishResize.await(); true } }
        runCurrent()
        assertFalse(resizing)
        finishInput.complete(Unit); input.join(); runCurrent()
        assertTrue(resizing)
        assertEquals(ManualControl.State.Manual, control.state.value)
        val back = launch { control.end() }
        runCurrent(); assertFalse(back.isCompleted)
        finishResize.complete(Unit)
        assertEquals(true, resize.await()); back.join()
        assertEquals(ManualControl.State.Automatic, control.state.value)
        control.close()
        assertFalse(control.reconfigure { error("closed run must not resize") })
    }

    @Test fun takeoverRequestedDuringResizeDoesNotDeadlockOrLoseOwnership() = runTest {
        val control = ManualControl()
        val finish = CompletableDeferred<Unit>()
        val resize = async { control.reconfigure { finish.await(); true } }
        runCurrent()
        val take = async { control.begin() }
        runCurrent(); assertFalse(take.isCompleted)
        finish.complete(Unit); resize.await()
        assertTrue(take.await())
        assertEquals(ManualControl.State.Manual, control.state.value)
        control.end()
    }
    @Test fun ordinaryActionsCanOverlapButTakeoverWaitsForBoth() = runTest {
        val control = ManualControl()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var started = 0
        launch { control.automatic { started++; first.await() } }
        launch { control.automatic { started++; second.await() } }
        runCurrent()
        assertEquals(2, started)
        val take = async { control.begin() }
        first.complete(Unit); runCurrent()
        assertFalse(take.isCompleted)
        second.complete(Unit)
        assertTrue(take.await())
        control.end()
    }

    @Test fun rejectedTakeoverAndFailedResizePreserveControl() = runTest {
        val control = ManualControl()
        assertTrue(control.begin())
        val before = control.version
        control.end { false }
        assertFalse(control.reconfigure { false })
        assertEquals(before, control.version)
        assertEquals(ManualControl.State.Manual, control.state.value)
        control.end()
    }
}
