package com.wangzan101.mika.calls

/** Read-only task context belongs to the observed carrier call, not the AI session's lifetime. */
internal class CallTaskBinding(private val callIdentity: Any) {
    private var snapshot: CallTaskBrief? = null
    private var ended = false

    /** Invoked only after the runtime has matched the outgoing request to this actual call. */
    fun bind(task: CallTaskBrief?) {
        if (!ended && snapshot == null) snapshot = task?.validatedSnapshot()
    }

    fun visibleFor(soleLiveCall: Any?, serviceTracksCall: Boolean, isCarrierCall: Boolean): CallTaskBrief? =
        snapshot.takeIf { !ended && soleLiveCall === callIdentity && serviceTracksCall && isCarrierCall }

    /** Removal also covers InCallService unbind/destruction; late callbacks cannot restore context. */
    fun end() {
        ended = true
        snapshot = null
    }
}
