package com.wangzan101.mika.core.device

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceAccessTest {
    private class Store : DeviceAccessStore {
        override var enabled = true
        override var localEnabled = false
        override val credentialConfigured get() = kind != null
        override var attemptBlocked = false
        var localBlocked = false
        var kind: CredentialKind? = CredentialKind.Pin
        var last: DeviceCredential? = null
        var beginHook: () -> Unit = {}
        var beginLocalHook: () -> Unit = {}
        override fun credential() = kind?.let { DeviceCredential(it, if (it == CredentialKind.Pin) "123456".toCharArray() else "Test pass!".toCharArray()).also { last = it } }
        override fun beginAttempt(credential: DeviceCredential): Boolean {
            if (attemptBlocked || !enabled) return false
            attemptBlocked = true
            beginHook()
            return true
        }
        override fun beginLocalAttempt(): Boolean {
            if (localBlocked || !localEnabled) return false
            localBlocked = true
            beginLocalHook()
            return true
        }
        override fun completeAttempt(credential: DeviceCredential) { attemptBlocked = false; localBlocked = false }
    }
    private class Port : DeviceAccessPort {
        var current = DeviceState(false, true, true, true, true, true)
        var wakes = 0
        var dismisses = 0
        var reveals = 0
        var fallbacks = 0
        var entries = 0
        var inputWorks = true
        var readyTarget = true
        var suspendInput = false
        var falselyReportsWake = false
        var wakeHook: () -> Unit = {}
        var targetHook: () -> Unit = {}
        var visibleKind: CredentialKind? = null
        var detect: (() -> CredentialKind?)? = null
        var diagnostics: List<String> = emptyList()
        override suspend fun detectedCredentialKind() = detect?.invoke() ?: visibleKind
        override suspend fun credentialDiagnostics() = diagnostics
        override suspend fun state() = current
        override suspend fun wake(): Boolean {
            wakes++
            if (!falselyReportsWake) current = current.copy(interactive = true)
            wakeHook()
            return true
        }
        override suspend fun dismiss(): Boolean {
            dismisses++
            if (!current.secure) current = current.copy(keyguardShowing = false, deviceLocked = false)
            return true
        }
        override suspend fun revealCredential(): Boolean { reveals++; return true }
        override suspend fun revealCredentialFallback(): Boolean { fallbacks++; return true }
        override suspend fun credentialReady(kind: CredentialKind): Boolean { targetHook(); return readyTarget && (visibleKind == null || visibleKind == kind) }
        override suspend fun enter(credential: DeviceCredential): Boolean {
            entries++
            if (suspendInput) awaitCancellation()
            delay(20)
            if (inputWorks) current = current.copy(keyguardShowing = false, deviceLocked = false)
            return true
        }
    }

    @Test fun disabledAndUnavailableNeverSendInput() = runTest {
        val p = Port(); val s = Store(); val d = DeviceAccess(p, s)
        s.enabled = false
        assertEquals("disabled", d.unlock().status)
        s.enabled = true; p.current = p.current.copy(helperAvailable = false)
        assertEquals("helper_unavailable", d.unlock().status)
        p.current = p.current.copy(helperAvailable = true, userUnlocked = false)
        assertEquals("first_unlock_required", d.unlock().status)
        assertEquals(0, p.wakes + p.dismisses + p.entries)
    }

    @Test fun sourceAuthorizationCannotGrantOrChangeTheDevicePermission() = runTest {
        val p = Port(); val s = Store(); val d = DeviceAccess(p, s)
        assertEquals("disabled", d.unlock { false }.status)
        assertTrue(s.enabled)
        s.enabled = false
        assertEquals("disabled", d.unlock { true }.status)
        assertEquals(0, p.wakes + p.dismisses + p.reveals + p.entries)
        assertNull(s.last)
        // 拒绝某个来源不会影响已有的定时任务/MCP 调用。
        s.enabled = true
        assertEquals("unlocked", d.unlock().status)
        assertEquals(1, p.entries)
    }

    @Test fun sourceRevokedWhileQueuedIsCheckedAfterAcquiringTheMutex() = runTest {
        var authorized = true
        val p = Port().apply { suspendInput = true }
        val s = Store(); val d = DeviceAccess(p, s)
        val first = launch { d.unlock() }
        runCurrent()
        val queued = async { d.unlock { authorized } }
        runCurrent()
        authorized = false
        first.cancel(); first.join()
        assertEquals("disabled", queued.await().status)
        assertEquals(1, p.wakes)
        assertEquals(1, p.dismisses)
        assertEquals(1, p.entries)
        assertTrue(s.attemptBlocked)
    }

    @Test fun sourceRevokedDuringWakeDoesNotDismissOrLoadTheCredential() = runTest {
        var authorized = true
        val p = Port().apply { wakeHook = { authorized = false } }
        val s = Store()
        assertEquals("disabled", DeviceAccess(p, s).unlock { authorized }.status)
        assertEquals(1, p.wakes)
        assertEquals(0, p.dismisses + p.reveals + p.entries)
        assertNull(s.last)
        assertFalse(s.attemptBlocked)
    }

    @Test fun sourceRevokedDuringTargetPollingDoesNotRevealOrEnter() = runTest {
        var authorized = true
        var polls = 0
        val p = Port().apply {
            readyTarget = false
            targetHook = { if (++polls == 2) authorized = false }
        }
        val s = Store()
        assertEquals("disabled", DeviceAccess(p, s, 10, 50).unlock { authorized }.status)
        assertEquals(0, p.reveals + p.entries)
        assertFalse(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    @Test fun sourceRevokedWhenTargetBecomesReadyDoesNotStartAnAttempt() = runTest {
        var authorized = true
        val p = Port().apply { targetHook = { authorized = false } }
        val s = Store()
        assertEquals("disabled", DeviceAccess(p, s).unlock { authorized }.status)
        assertEquals(0, p.entries)
        assertFalse(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    @Test fun sourceRevokedAfterAttemptReservationKeepsTheLatchWithoutTyping() = runTest {
        var authorized = true
        val p = Port()
        val s = Store().apply { beginHook = { authorized = false } }
        assertEquals("disabled", DeviceAccess(p, s).unlock { authorized }.status)
        assertEquals(0, p.entries)
        assertTrue(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    /** 本机开关与远程开关各自独立:local 分流只认 localEnabled,remote 分流只认 enabled。 */
    @Test fun localPermissionIsCheckedIndependentlyFromTheRemoteSwitch() = runTest {
        // 只开本机开关:remote 分流仍拒,local 分流放行
        val localOnlyPort = Port()
        val localOnlyStore = Store().apply { enabled = false; localEnabled = true }
        val d = DeviceAccess(localOnlyPort, localOnlyStore)
        assertEquals("disabled", d.unlock().status)
        assertEquals(0, localOnlyPort.wakes + localOnlyPort.dismisses + localOnlyPort.entries)
        assertEquals("unlocked", d.unlockLocal().status)
        assertEquals(1, localOnlyPort.entries)
        assertFalse(localOnlyStore.attemptBlocked)
        assertFalse(localOnlyStore.localBlocked)
        // 只开远程开关:local 分流拒绝,一次操作都不做
        val remoteOnlyPort = Port()
        val remoteOnlyStore = Store().apply { enabled = true; localEnabled = false }
        assertEquals("disabled", DeviceAccess(remoteOnlyPort, remoteOnlyStore).unlockLocal().status)
        assertEquals(0, remoteOnlyPort.wakes + remoteOnlyPort.dismisses + remoteOnlyPort.reveals + remoteOnlyPort.entries)
        assertNull(remoteOnlyStore.last)
    }

    /** 唤醒途中用户关掉本机开关:每次操作前重读,流程必须在凭据输入前中止。 */
    @Test fun localSwitchOffDuringWakeStopsBeforeLoadingTheCredential() = runTest {
        val s = Store().apply { enabled = false; localEnabled = true }
        val p = Port().apply { wakeHook = { s.localEnabled = false } }
        assertEquals("disabled", DeviceAccess(p, s).unlockLocal().status)
        assertEquals(1, p.wakes)
        assertEquals(0, p.dismisses + p.reveals + p.entries)
        assertNull(s.last)
        assertFalse(s.localBlocked)
    }

    /** 熄屏但本来没安全锁:本机流程唤醒即带到可用,算这次解的(woke || !wasSecure)。 */
    @Test fun localWakeOnAnUnsecuredScreenCountsAsOurUnlock() = runTest {
        val p = Port().apply { current = DeviceState(false, true, false, false, true, true) }
        val s = Store().apply { enabled = false; localEnabled = true; kind = null }
        val result = DeviceAccess(p, s).unlockLocal()
        assertEquals("unlocked", result.status)
        assertTrue(result.performedUnlock)
        assertEquals(0, p.entries)
        assertFalse(s.localBlocked)
    }

    @Test fun wakeIsIdempotentAndDoesNotDismissOrEnter() = runTest {
        val p = Port(); val d = DeviceAccess(p, Store())
        repeat(2) { assertEquals("awake", d.wake().status) }
        assertEquals(1, p.wakes)
        assertEquals(0, p.dismisses + p.entries)
    }

    @Test fun commandSuccessDoesNotMeanTheScreenWoke() = runTest {
        val p = Port().apply { falselyReportsWake = true }
        assertEquals("wake_failed", DeviceAccess(p, Store(), 10, 30).wake().status)
        assertEquals(0, p.entries)
    }

    @Test fun swipeLockNeedsNoCredential() = runTest {
        val p = Port().apply { current = current.copy(secure = false) }
        val s = Store().apply { kind = null }
        assertEquals("unlocked", DeviceAccess(p, s).unlock().status)
        assertEquals(0, p.entries)
    }

    /** 回锁义务的依据:只有这次流程真的把手机从熄屏/锁屏带到可用,才标记 performedUnlock。 */
    @Test fun performedUnlockOnlyWhenThisFlowUnlocked() = runTest {
        // 本来就可用:不是这次解的
        val already = Port().apply { current = DeviceState(true, false, false, true, true, true) }
        assertFalse(DeviceAccess(already, Store()).unlock().performedUnlock)
        assertEquals(0, already.wakes + already.dismisses + already.entries)
        // 熄屏唤醒 + 上滑(无安全锁):这次流程带到的
        val swipe = Port().apply { current = DeviceState(false, true, false, false, true, true) }
        assertTrue(DeviceAccess(swipe, Store().apply { kind = null }).unlock().performedUnlock)
        // 亮着但锁屏(滑动锁):这次流程上滑解开的
        val dismissed = Port().apply { current = DeviceState(true, true, false, false, true, true) }
        assertTrue(DeviceAccess(dismissed, Store().apply { kind = null }).unlock().performedUnlock)
        // 输入凭据解开:这次流程解的
        val typed = Port()
        assertTrue(DeviceAccess(typed, Store()).unlock().performedUnlock)
    }

    @Test fun manualUnlockDuringOurFlowIsNotOurUnlock() = runTest {
        // 等凭据页期间用户自己解了:我们没注入,不算这次解的
        val manual = Port().apply {
            readyTarget = false
            targetHook = { current = current.copy(keyguardShowing = false, deviceLocked = false) }
        }
        assertFalse(DeviceAccess(manual, Store(), 10, 30).unlock().performedUnlock)
        // enter 报失败但系统已可用(用户同时解的):也不算这次解的
        val raced = object : DeviceAccessPort {
            private var s = DeviceState(true, true, true, true, true, true)
            override suspend fun state() = s
            override suspend fun wake() = true
            override suspend fun dismiss() = true
            override suspend fun credentialReady(kind: CredentialKind) = true
            override suspend fun enter(credential: DeviceCredential): Boolean {
                s = s.copy(keyguardShowing = false, deviceLocked = false)
                return false
            }
        }
        assertFalse(DeviceAccess(raced, Store()).unlock().performedUnlock)
    }

    @Test fun pinAndPasswordAreWipedAfterVerifiedSuccess() = runTest {
        for (kind in CredentialKind.entries) {
            val p = Port(); val s = Store().apply { this.kind = kind }
            assertEquals("unlocked", DeviceAccess(p, s).unlock().status)
            assertEquals(1, p.entries)
            assertFalse(s.attemptBlocked)
            assertTrue(s.last!!.value.all { it == '\u0000' })
            assertFalse(s.last.toString().contains("123456"))
        }
    }

    @Test fun failedAuthenticationIsLatchedAndNeverRetried() = runTest {
        val p = Port().apply { inputWorks = false }; val s = Store(); val d = DeviceAccess(p, s, 10, 30)
        assertEquals("unlock_failed", d.unlock().status)
        assertEquals("attempt_blocked", d.unlock().status)
        assertEquals(1, p.entries)
        assertTrue(s.attemptBlocked)
    }

    @Test fun unknownTargetAndMissingCredentialNeverEnter() = runTest {
        val p = Port().apply { readyTarget = false }; val s = Store(); val d = DeviceAccess(p, s, 10, 30)
        assertEquals("unsupported_keyguard", d.unlock().status)
        assertFalse(s.attemptBlocked)
        s.kind = null
        assertEquals("credential_required", d.unlock().status)
        assertEquals(0, p.entries)
    }

    @Test fun unsupportedKeyguardReturnsDiagnosticsWithoutCredentials() = runTest {
        val p = Port().apply {
            readyTarget = false
            diagnostics = listOf("systemui_windows=1", "field[id=com.android.systemui:id/pinEntry,match=none]")
        }
        val s = Store()
        val result = DeviceAccess(p, s, 10, 30).unlock()
        assertEquals("unsupported_keyguard", result.status)
        assertEquals(p.diagnostics, result.keyguardDiagnostics)
        assertEquals(p.diagnostics, result.toJson()["keyguard_diagnostics"]?.let { json ->
            (json as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        })
        val wire = result.toJson().toString()
        assertFalse(wire.contains("123456"))
        assertFalse(s.attemptBlocked)
        assertEquals(0, p.entries)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    @Test fun wrongStoredKindIsReportedWithoutSubmittingOrBlockingTheCredential() = runTest {
        for (stored in CredentialKind.entries) {
            val visible = CredentialKind.entries.single { it != stored }
            val p = Port().apply { visibleKind = visible }
            val s = Store().apply { kind = stored }
            val result = DeviceAccess(p, s, 10, 30).unlock()
            assertEquals("credential_kind_mismatch", result.status)
            assertEquals(stored, result.storedKind)
            assertEquals(visible, result.detectedKind)
            val json = result.toJson()
            assertEquals(kotlinx.serialization.json.JsonPrimitive(stored.name.lowercase()), json["stored_kind"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(visible.name.lowercase()), json["detected_kind"])
            assertTrue(json["message"].toString().contains("Re-enter"))
            assertFalse(json.toString().contains("123456"))
            assertFalse(json.toString().contains("Test pass!"))
            assertEquals(0, p.entries)
            assertFalse(s.attemptBlocked)
            assertTrue(s.last!!.value.all { it == '\u0000' })
        }
    }

    @Test fun cancellingTypeDetectionWipesTheCredentialWithoutStartingAnAttempt() = runTest {
        val p = Port().apply {
            readyTarget = false
            detect = { throw kotlinx.coroutines.CancellationException("cancelled") }
        }
        val s = Store()
        try {
            DeviceAccess(p, s, 10, 30).unlock()
            fail("cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, p.entries)
        assertFalse(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    @Test fun typeDetailsAreAbsentFromOrdinaryStateAndWakeResponses() = runTest {
        val d = DeviceAccess(Port(), Store())
        for (r in listOf(d.state(), d.wake())) {
            assertNull(r.storedKind)
            assertNull(r.detectedKind)
            assertTrue(r.keyguardDiagnostics.isEmpty())
            assertFalse(r.toJson().containsKey("stored_kind"))
            assertFalse(r.toJson().containsKey("detected_kind"))
            assertFalse(r.toJson().containsKey("keyguard_diagnostics"))
            assertFalse(r.toJson().containsKey("message"))
        }
    }

    @Test fun recognizedButUnreadyMatchingFieldStillReportsUnsupported() = runTest {
        val p = Port().apply { readyTarget = false; visibleKind = CredentialKind.Pin }
        val s = Store()
        assertEquals("unsupported_keyguard", DeviceAccess(p, s, 10, 30).unlock().status)
        assertEquals(0, p.entries)
        assertFalse(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    @Test fun secureKeyguardGetsOneGenericRevealBeforeReportingUnsupported() = runTest {
        val p = Port().apply { readyTarget = false }
        val s = Store()
        val d = DeviceAccess(p, s, 10, 30)
        assertEquals("unsupported_keyguard", d.unlock().status)
        assertEquals(1, p.reveals)
        assertEquals(0, p.entries)
        // 下一次明确调用是新的一次解锁流程,可以重新导航;同一次绝不反复滑。
        assertEquals("unsupported_keyguard", d.unlock().status)
        assertEquals(2, p.reveals)
        assertFalse(s.attemptBlocked)
    }

    @Test fun revealCanExposeTheAlreadyStoredCredentialKind() = runTest {
        val p = Port().apply {
            readyTarget = false
            targetHook = { if (reveals > 0) readyTarget = true }
        }
        val s = Store()
        assertEquals("unlocked", DeviceAccess(p, s, 10, 50).unlock().status)
        assertEquals(1, p.reveals)
        assertEquals(1, p.entries)
        assertFalse(s.attemptBlocked)
    }

    @Test fun biometricFirstPageGetsOneSwipeThenOneFallbackTapBeforeUnsupported() = runTest {
        val p = Port().apply { readyTarget = false }
        val s = Store()
        assertEquals("unsupported_keyguard", DeviceAccess(p, s, 10, 30).unlock().status)
        assertEquals(1, p.reveals)
        assertEquals(1, p.fallbacks)
        assertEquals(0, p.entries)
        assertFalse(s.attemptBlocked)
    }

    @Test fun fallbackTapCanExposeTheCredentialKind() = runTest {
        val p = Port().apply {
            readyTarget = false
            targetHook = { if (fallbacks > 0) readyTarget = true }
        }
        val s = Store()
        assertEquals("unlocked", DeviceAccess(p, s, 10, 50).unlock().status)
        assertEquals(1, p.reveals)
        assertEquals(1, p.fallbacks)
        assertEquals(1, p.entries)
        assertFalse(s.attemptBlocked)
    }

    @Test fun readyKeyguardIsNotSwipedOrTapped() = runTest {
        val p = Port()
        assertEquals("unlocked", DeviceAccess(p, Store(), 10, 30).unlock().status)
        assertEquals(0, p.reveals)
        assertEquals(0, p.fallbacks)
        assertEquals(1, p.entries)
    }

    @Test fun readyOrManuallyUnlockedKeyguardIsNotNeedlesslySwiped() = runTest {
        val ready = Port()
        assertEquals("unlocked", DeviceAccess(ready, Store(), 10, 30).unlock().status)
        assertEquals(0, ready.reveals)
        val manual = Port().apply {
            readyTarget = false
            targetHook = { current = current.copy(keyguardShowing = false, deviceLocked = false) }
        }
        assertEquals("unlocked", DeviceAccess(manual, Store(), 10, 30).unlock().status)
        assertEquals(0, manual.reveals)
        assertEquals(0, manual.entries)
    }

    @Test fun concurrentRequestsSubmitTheCredentialOnlyOnce() = runTest {
        val p = Port(); val d = DeviceAccess(p, Store())
        val a = async { d.unlock() }; val b = async { d.unlock() }
        assertEquals("unlocked", a.await().status)
        assertEquals("unlocked", b.await().status)
        assertEquals(1, p.entries)
    }

    @Test fun manualUnlockWhileWaitingForTheCredentialPageDoesNotType() = runTest {
        val p = Port().apply {
            readyTarget = false
            targetHook = { current = current.copy(keyguardShowing = false, deviceLocked = false) }
        }
        val s = Store()
        assertEquals("unlocked", DeviceAccess(p, s, 10, 30).unlock().status)
        assertEquals(0, p.entries)
        assertFalse(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }

    @Test fun disablingWhileWaitingForTheCredentialPageStopsBeforeInput() = runTest {
        val s = Store(); val p = Port().apply { readyTarget = false; targetHook = { s.enabled = false } }
        assertEquals("disabled", DeviceAccess(p, s, 10, 30).unlock().status)
        assertEquals(0, p.entries)
        assertFalse(s.attemptBlocked)
    }

    @Test fun cancellationAfterStartingInputKeepsTheLatchAndWipesTheCredential() = runTest {
        val p = Port().apply { suspendInput = true }; val s = Store(); val d = DeviceAccess(p, s)
        val job = launch { d.unlock() }
        runCurrent()
        assertEquals(1, p.entries)
        job.cancel(); job.join()
        assertTrue(s.attemptBlocked)
        assertTrue(s.last!!.value.all { it == '\u0000' })
    }
}
