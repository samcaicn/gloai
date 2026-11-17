package com.wangzan101.mika.pocket

import com.wangzan101.mika.core.agent.TaskState

/** Only a stopped, waiting, or finished task needs a lock check. */
internal object LockTriggerPolicy {
    fun isWaiting(state: TaskState): Boolean = when (state) {
        // When already waiting for an unlock, locking again could prevent the task from resuming.
        is TaskState.Held -> !state.reason.queued && state.reason.code != "waiting_unlock"
        is TaskState.WaitingTakeover, is TaskState.WaitingInput, is TaskState.WaitingConfirmation -> true
        else -> false
    }
    fun shouldCheck(state: TaskState): Boolean = state is TaskState.Terminal || isWaiting(state)
}
