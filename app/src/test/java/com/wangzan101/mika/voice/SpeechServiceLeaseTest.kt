package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechServiceLeaseTest {
    private class FakeBinder(val calls: MutableList<String> = mutableListOf()) : SpeechServiceLease.Binder {
        lateinit var connected: () -> Unit
        lateinit var unavailable: () -> Unit
        var result = true
        var duringBind: (() -> Unit)? = null
        var bindError: Exception? = null
        var unbindError: Exception? = null

        override fun bind(onConnected: () -> Unit, onUnavailable: () -> Unit): Boolean {
            calls += "bind"
            connected = onConnected
            unavailable = onUnavailable
            duringBind?.invoke()
            bindError?.let { throw it }
            calls += "bindReturned"
            return result
        }

        override fun unbind() {
            calls += "unbind"
            unbindError?.let { throw it }
        }
    }

    private fun lease(binder: FakeBinder) = SpeechServiceLease(binder,
        onReady = { binder.calls += "ready" }, onUnavailable = { binder.calls += "unavailable" })

    @Test fun cancelBeforeConnectionUnbindsAndLateCallbacksDoNothing() {
        val binder = FakeBinder()
        val lease = lease(binder)
        assertTrue(lease.start())
        lease.close()
        lease.close()
        binder.connected()
        binder.unavailable()
        assertEquals(listOf("bind", "bindReturned", "unbind"), binder.calls)
        assertFalse(lease.start())
    }

    @Test fun cancelAfterConnectionUnbindsOnlyOnce() {
        val binder = FakeBinder()
        val lease = lease(binder)
        assertTrue(lease.start())
        assertTrue(lease.start())
        binder.connected()
        binder.connected()
        lease.close()
        lease.close()
        binder.connected()
        binder.unavailable()
        assertEquals(listOf("bind", "bindReturned", "ready", "unbind"), binder.calls)
    }

    @Test fun closeBeforeStartNeverRegistersAConnection() {
        val binder = FakeBinder()
        val lease = lease(binder)
        lease.close()
        assertFalse(lease.start())
        assertTrue(binder.calls.isEmpty())
    }

    // 空绑定、绑定失效和服务断开在适配器里都汇入这一个失效回调。
    @Test fun unavailableUnbindsBeforeReportingAndIgnoresLaterConnect() {
        val binder = FakeBinder()
        val lease = lease(binder)
        assertTrue(lease.start())
        binder.unavailable()
        binder.connected()
        binder.unavailable()
        lease.close()
        assertEquals(listOf("bind", "bindReturned", "unbind", "unavailable"), binder.calls)
    }

    @Test fun disconnectionAfterReadyStillCleansUpAndReportsOnlyOnce() {
        val binder = FakeBinder()
        val lease = lease(binder)
        assertTrue(lease.start())
        binder.connected()
        binder.unavailable()
        binder.unavailable()
        binder.connected()
        assertEquals(listOf("bind", "bindReturned", "ready", "unbind", "unavailable"), binder.calls)
    }

    @Test fun unsuccessfulBindIsCleanedUpWithoutCompetingWithCallerFallback() {
        val binder = FakeBinder().apply { result = false }
        val lease = lease(binder)
        assertFalse(lease.start())
        lease.close()
        binder.connected()
        binder.unavailable()
        assertEquals(listOf("bind", "bindReturned", "unbind"), binder.calls)
    }

    @Test fun bindExceptionCleansUpRegistrationAndReturnsFalse() {
        val binder = FakeBinder().apply { bindError = SecurityException("not allowed") }
        val lease = lease(binder)
        assertFalse(lease.start())
        lease.close()
        binder.connected()
        assertEquals(listOf("bind", "unbind"), binder.calls)
    }

    @Test fun unbindExceptionDoesNotPreventUnavailableOrCauseAnotherUnbind() {
        val binder = FakeBinder().apply { unbindError = IllegalArgumentException("already gone") }
        val lease = lease(binder)
        assertTrue(lease.start())
        binder.unavailable()
        lease.close()
        binder.connected()
        binder.unavailable()
        assertEquals(listOf("bind", "bindReturned", "unbind", "unavailable"), binder.calls)
    }

    @Test fun ownerCanCloseInsideReadyCallback() {
        val binder = FakeBinder()
        lateinit var lease: SpeechServiceLease
        lease = SpeechServiceLease(binder, onReady = {
            binder.calls += "ready"
            lease.close()
        }, onUnavailable = { binder.calls += "unavailable" })
        assertTrue(lease.start())
        binder.connected()
        binder.connected()
        binder.unavailable()
        assertEquals(listOf("bind", "bindReturned", "ready", "unbind"), binder.calls)
    }

    @Test fun ownerCanCloseInsideUnavailableCallback() {
        val binder = FakeBinder()
        lateinit var lease: SpeechServiceLease
        lease = SpeechServiceLease(binder, onReady = { binder.calls += "ready" }, onUnavailable = {
            binder.calls += "unavailable"
            lease.close()
        })
        assertTrue(lease.start())
        binder.unavailable()
        assertEquals(listOf("bind", "bindReturned", "unbind", "unavailable"), binder.calls)
    }

    @Test fun synchronousConnectionWaitsForBindToSucceed() {
        val binder = FakeBinder().apply { duringBind = { connected() } }
        val lease = lease(binder)
        assertTrue(lease.start())
        assertEquals(listOf("bind", "bindReturned", "ready"), binder.calls)
        lease.close()
    }

    @Test fun synchronousConnectionDoesNotStartOwnerWhenBindFails() {
        val binder = FakeBinder().apply {
            duringBind = { connected() }
            result = false
        }
        val lease = lease(binder)
        assertFalse(lease.start())
        assertEquals(listOf("bind", "bindReturned", "unbind"), binder.calls)
    }

    @Test fun synchronousUnavailableWinsOverConnectionAndCleansUpAfterBind() {
        val binder = FakeBinder().apply {
            duringBind = {
                connected()
                unavailable()
                connected()
            }
        }
        val lease = lease(binder)
        assertTrue(lease.start())
        assertEquals(listOf("bind", "bindReturned", "unbind", "unavailable"), binder.calls)
    }

    @Test fun closeDuringBindDefersUnbindUntilRegistrationReturns() {
        val binder = FakeBinder()
        val lease = lease(binder)
        binder.duringBind = {
            lease.close()
            binder.connected()
        }
        assertTrue(lease.start())
        assertEquals(listOf("bind", "bindReturned", "unbind"), binder.calls)
        lease.close()
    }
}
