package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.ShizukuPlatformState
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.lang.reflect.Proxy

class ShizukuTransportTest {
    private class FakeBridge : ShizukuController.Bridge {
        var installed = false
        var binderAlive = false
        var preV11 = false
        var permission = PackageManager.PERMISSION_DENIED
        var rationale = false
        var requestThrows = false
        var bindThrows: RuntimeException? = null
        var openAppResult = true

        var bindCalls = 0
        var unbindCalls = 0
        var unbindRemoveFlags = mutableListOf<Boolean>()
        var requestedCodes = mutableListOf<Int>()
        var openAppCalls = 0

        var receivedListener: Shizuku.OnBinderReceivedListener? = null
        var deadListener: Shizuku.OnBinderDeadListener? = null
        var permListener: Shizuku.OnRequestPermissionResultListener? = null

        override fun isPackageInstalled(): Boolean = installed
        override fun pingBinder(): Boolean = binderAlive
        override fun isPreV11(): Boolean = preV11
        override fun checkSelfPermission(): Int = permission
        override fun shouldShowRequestPermissionRationale(): Boolean = rationale
        override fun requestPermission(requestCode: Int) {
            if (requestThrows) throw SecurityException("denied")
            requestedCodes += requestCode
        }

        override fun bindUserService(connection: ServiceConnection) {
            bindCalls++
            bindThrows?.let { throw it }
        }

        override fun unbindUserService(connection: ServiceConnection, remove: Boolean) {
            unbindCalls++
            unbindRemoveFlags += remove
        }

        override fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) {
            receivedListener = listener
        }

        override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
            deadListener = listener
        }

        override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            permListener = listener
        }

        override fun openShizukuApp(context: Context?): Boolean {
            openAppCalls++
            return openAppResult
        }
    }

    private fun fakeBinder(): IBinder = Proxy.newProxyInstance(
        IBinder::class.java.classLoader,
        arrayOf(IBinder::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "isBinderAlive", "pingBinder" -> true
            "toString" -> "FakeIBinder"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args?.firstOrNull() === proxy
            else -> null
        }
    } as IBinder

    private fun fakeShell(): IMikaShell = Proxy.newProxyInstance(
        IMikaShell::class.java.classLoader,
        arrayOf(IMikaShell::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "FakeIMikaShell"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args?.firstOrNull() === proxy
            else -> null
        }
    } as IMikaShell

    @Test
    fun refreshMapsInstalledRunningPermissionAndSuiBinderAlive() {
        val bridge = FakeBridge()
        val controller = ShizukuController(TestScope(), bridge, { fakeShell() }, {}, {})

        assertEquals(ShizukuPlatformState(false, false, false), controller.refresh())

        bridge.installed = true
        assertEquals(ShizukuPlatformState(true, false, false), controller.refresh())

        bridge.binderAlive = true
        assertEquals(ShizukuPlatformState(true, true, false), controller.refresh())

        bridge.permission = PackageManager.PERMISSION_GRANTED
        assertEquals(ShizukuPlatformState(true, true, true), controller.refresh())

        // Pre-v11 Shizuku never counts as granted.
        bridge.preV11 = true
        assertEquals(ShizukuPlatformState(true, true, false), controller.refresh())

        // Sui / binder-only provider: installed=true whenever pingBinder() is true.
        bridge.installed = false
        bridge.preV11 = false
        assertEquals(ShizukuPlatformState(true, true, true), controller.refresh())
    }

    @Test
    fun launchHelperValidatesStateAndMapsBindFailures() = runTest {
        val bridge = FakeBridge()
        val controller = ShizukuController(this, bridge, { fakeShell() }, {}, {})

        assertEquals(BuiltinShellFailure.ShizukuNotInstalled, controller.launchHelper())
        assertEquals(0, bridge.bindCalls)

        bridge.installed = true
        assertEquals(BuiltinShellFailure.ShizukuNotRunning, controller.launchHelper())
        assertEquals(0, bridge.bindCalls)

        bridge.binderAlive = true
        assertEquals(BuiltinShellFailure.ShizukuPermissionNeeded, controller.launchHelper())
        assertEquals(0, bridge.bindCalls)

        bridge.permission = PackageManager.PERMISSION_GRANTED
        assertNull(controller.launchHelper())
        assertEquals(1, bridge.bindCalls)

        bridge.bindThrows = IllegalStateException("dead").also { bridge.binderAlive = true }
        val transientController = ShizukuController(
            this,
            object : ShizukuController.Bridge by bridge {
                override fun bindUserService(connection: ServiceConnection) {
                    bridge.binderAlive = false
                    throw IllegalStateException("binder died mid-call")
                }
            },
            { fakeShell() },
            {},
            {},
        )
        assertEquals(BuiltinShellFailure.ShizukuNotRunning, transientController.launchHelper())

        bridge.binderAlive = true
        bridge.permission = PackageManager.PERMISSION_GRANTED
        bridge.bindThrows = SecurityException("bind failed")
        assertEquals(BuiltinShellFailure.ShizukuBindFailed, controller.launchHelper())
    }

    @Test
    fun serviceConnectionAttachDetachAndLateBindCleanup() = runTest {
        val bridge = FakeBridge().apply {
            installed = true
            binderAlive = true
            permission = PackageManager.PERMISSION_GRANTED
        }
        val attached = mutableListOf<IBinder>()
        val detached = mutableListOf<IMikaShell?>()
        var changed = 0
        val shell = fakeShell()
        val controller = ShizukuController(
            scope = this,
            bridge = bridge,
            attach = { b -> attached += b; shell },
            detach = { detached += it },
            onChanged = { changed++ },
        )

        // Calling stopHelper() before any launch does not issue an unbind.
        controller.stopHelper()
        assertEquals(0, bridge.unbindCalls)

        // Null binder clears active bind request and notifies observer.
        assertNull(controller.launchHelper())
        controller.connection.onServiceConnected(null, null)
        assertEquals(1, changed)

        // Late onServiceConnected after stopHelper() detaches and unbinds instead of keeping stale shell.
        var stopDuringAttach = false
        lateinit var raceController: ShizukuController
        raceController = ShizukuController(
            scope = this,
            bridge = bridge,
            attach = { b ->
                attached += b
                if (stopDuringAttach) raceController.stopHelper()
                shell
            },
            detach = { detached += it },
            onChanged = {},
        )
        assertNull(raceController.launchHelper())
        stopDuringAttach = true
        raceController.connection.onServiceConnected(null, fakeBinder())
        assertEquals(listOf<IMikaShell?>(shell), detached)
        assertEquals(listOf(true), bridge.unbindRemoveFlags)

        // Stopping and relaunching during attach() detaches the stale shell without clobbering the new bind or unbinding twice.
        detached.clear()
        bridge.bindCalls = 0
        bridge.unbindCalls = 0
        bridge.unbindRemoveFlags.clear()
        val staleShell = fakeShell()
        val freshShell = fakeShell()
        var nextShell = staleShell
        var stopAndRelaunchDuringAttach = false
        lateinit var relaunchController: ShizukuController
        relaunchController = ShizukuController(
            scope = this,
            bridge = bridge,
            attach = { b ->
                attached += b
                if (stopAndRelaunchDuringAttach) {
                    stopAndRelaunchDuringAttach = false
                    relaunchController.stopHelper()
                    assertNull(runBlocking { relaunchController.launchHelper() })
                }
                nextShell
            },
            detach = { detached += it },
            onChanged = {},
        )
        assertNull(relaunchController.launchHelper())
        stopAndRelaunchDuringAttach = true
        relaunchController.connection.onServiceConnected(null, fakeBinder())
        assertEquals(listOf<IMikaShell?>(staleShell), detached)
        assertEquals(1, bridge.unbindCalls)
        assertEquals(listOf(true), bridge.unbindRemoveFlags)
        assertEquals(2, bridge.bindCalls)

        nextShell = freshShell
        relaunchController.connection.onServiceConnected(null, fakeBinder())
        assertEquals(listOf<IMikaShell?>(staleShell), detached)
        relaunchController.stopHelper()
        assertEquals(listOf<IMikaShell?>(staleShell, freshShell), detached)
        assertEquals(2, bridge.unbindCalls)
        assertEquals(listOf(true, true), bridge.unbindRemoveFlags)

        // Happy path connect + stopHelper() detaches cleanly and removes bound service.
        detached.clear()
        bridge.unbindCalls = 0
        bridge.unbindRemoveFlags.clear()
        assertNull(controller.launchHelper())
        controller.connection.onServiceConnected(null, fakeBinder())
        controller.stopHelper()
        assertEquals(listOf<IMikaShell?>(shell), detached)
        assertEquals(1, bridge.unbindCalls)
        assertEquals(listOf(true), bridge.unbindRemoveFlags)

        // onServiceDisconnected detaches current shell, refreshes state, and fires onChanged.
        detached.clear()
        val beforeDisconnect = changed
        assertNull(controller.launchHelper())
        controller.connection.onServiceConnected(null, fakeBinder())
        bridge.binderAlive = false
        controller.connection.onServiceDisconnected(null)
        assertEquals(listOf<IMikaShell?>(shell), detached)
        assertFalse(controller.state.value.running)
        assertEquals(beforeDisconnect + 1, changed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun prepareStopAndStopHelperAsyncDetachSynchronouslyAndUnbindOnIo() = runTest {
        val bridge = FakeBridge().apply {
            installed = true
            binderAlive = true
            permission = PackageManager.PERMISSION_GRANTED
        }
        val detached = mutableListOf<IMikaShell?>()
        val shell = fakeShell()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ShizukuController(
            scope = this,
            bridge = bridge,
            attach = { shell },
            detach = { detached += it },
            onChanged = {},
            io = dispatcher,
        )

        // Calling prepareStop() / stopHelperAsync() when inactive does not unbind.
        assertFalse(controller.prepareStop())
        controller.stopHelperAsync()
        advanceUntilIdle()
        assertEquals(0, bridge.unbindCalls)
        assertTrue(detached.isEmpty())

        // After launch + bind, stopHelperAsync() synchronously detaches & clears active bind before IO unbind runs.
        assertNull(controller.launchHelper())
        controller.connection.onServiceConnected(null, fakeBinder())
        controller.stopHelperAsync()
        assertEquals(listOf<IMikaShell?>(shell), detached)
        assertEquals(0, bridge.unbindCalls)

        advanceUntilIdle()
        assertEquals(1, bridge.unbindCalls)
        assertEquals(listOf(true), bridge.unbindRemoveFlags)

        // Late onServiceConnected after prepareStop() (before IO unbind) rejects immediately without keeping boundShell.
        detached.clear()
        bridge.unbindCalls = 0
        bridge.unbindRemoveFlags.clear()
        assertNull(controller.launchHelper())
        assertTrue(controller.prepareStop())
        assertTrue(detached.isEmpty())
        controller.connection.onServiceConnected(null, fakeBinder())
        assertEquals(1, bridge.unbindCalls)
        assertEquals(listOf(true), bridge.unbindRemoveFlags)
        assertFalse(controller.prepareStop())
    }

    @Test
    fun listenersAndGrantPermissionOrAppBehavior() {
        val bridge = FakeBridge()
        var changed = 0
        val controller = ShizukuController(TestScope(), bridge, { fakeShell() }, {}, { changed++ })

        controller.start()
        assertEquals(ShizukuPlatformState(false, false, false), controller.state.value)

        bridge.installed = true
        bridge.binderAlive = true
        bridge.receivedListener!!.onBinderReceived()
        assertTrue(controller.state.value.running)
        assertEquals(1, changed)

        bridge.permission = PackageManager.PERMISSION_GRANTED
        bridge.permListener!!.onRequestPermissionResult(9999, PackageManager.PERMISSION_DENIED)
        assertEquals(1, changed)
        bridge.permListener!!.onRequestPermissionResult(ShizukuController.REQUEST_CODE, PackageManager.PERMISSION_GRANTED)
        assertTrue(controller.state.value.permissionGranted)
        assertEquals(2, changed)

        bridge.binderAlive = false
        bridge.deadListener!!.onBinderDead()
        assertFalse(controller.state.value.running)
        assertEquals(3, changed)

        // When running without permission and no rationale needed, requests Shizuku permission dialog.
        bridge.binderAlive = true
        bridge.permission = PackageManager.PERMISSION_DENIED
        assertTrue(controller.openGrantOrApp(null))
        assertEquals(listOf(ShizukuController.REQUEST_CODE), bridge.requestedCodes)
        assertEquals(0, bridge.openAppCalls)

        // Rationale shown or permission request failing falls back to opening Shizuku app.
        bridge.rationale = true
        assertTrue(controller.openGrantOrApp(null))
        assertEquals(1, bridge.openAppCalls)

        bridge.rationale = false
        bridge.requestThrows = true
        assertTrue(controller.openGrantOrApp(null))
        assertEquals(2, bridge.openAppCalls)
    }
}
