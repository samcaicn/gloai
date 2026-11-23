package com.wangzan101.mika.cap.phone.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.ShizukuShellUserService
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Pure official Shizuku API integration for starting and stopping [ShizukuShellUserService].
 * Never uses reflection into private Shizuku internals and never attempts to start Shizuku itself.
 */
class ShizukuController internal constructor(
    private val scope: CoroutineScope,
    private val bridge: Bridge,
    private val attach: (IBinder) -> IMikaShell?,
    private val detach: (IMikaShell?) -> Unit,
    private val onChanged: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        hub: HelperHub,
        scope: CoroutineScope,
        onChanged: () -> Unit,
    ) : this(
        scope = scope,
        bridge = AndroidBridge(context.applicationContext),
        attach = hub::attachShizuku,
        detach = hub::detachShizuku,
        onChanged = onChanged,
    )

    internal interface Bridge {
        fun isPackageInstalled(): Boolean
        fun pingBinder(): Boolean
        fun isPreV11(): Boolean
        fun checkSelfPermission(): Int
        fun shouldShowRequestPermissionRationale(): Boolean
        fun requestPermission(requestCode: Int)
        fun bindUserService(connection: ServiceConnection)
        fun unbindUserService(connection: ServiceConnection, remove: Boolean)
        fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener)
        fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener)
        fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)
        fun openShizukuApp(context: Context?): Boolean
    }

    private val lock = Any()
    private var started = false
    private var bindRequested = false
    private var bindGeneration = 0L
    private var boundShell: IMikaShell? = null

    private val _state = MutableStateFlow(ShizukuPlatformState.Unavailable)
    val state: StateFlow<ShizukuPlatformState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
        onChanged()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refresh()
        onChanged()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) {
            refresh()
            onChanged()
        }
    }

    internal val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                synchronized(lock) { bindRequested = false }
                onChanged()
                return
            }
            val activeGeneration = synchronized(lock) {
                if (bindRequested) bindGeneration else null
            }
            if (activeGeneration == null) {
                runCatching { bridge.unbindUserService(this, remove = true) }
                return
            }
            val attached = attach(binder)
            var rejectLate = false
            synchronized(lock) {
                if (!bindRequested || bindGeneration != activeGeneration) {
                    rejectLate = true
                } else if (attached != null) {
                    boundShell = attached
                } else {
                    bindRequested = false
                }
            }
            if (rejectLate) {
                if (attached != null) {
                    detach(attached)
                }
            } else if (attached == null) {
                runCatching { bridge.unbindUserService(this, remove = true) }
                onChanged()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val prev = synchronized(lock) {
                boundShell.also { boundShell = null }
            }
            if (prev != null) {
                detach(prev)
            }
            refresh()
            onChanged()
        }
    }

    @Synchronized
    fun start(refreshNow: Boolean = true) {
        if (started) return
        started = true
        if (refreshNow) refresh()
        runCatching {
            bridge.addBinderReceivedListenerSticky(binderReceivedListener)
            bridge.addBinderDeadListener(binderDeadListener)
            bridge.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure {
            runCatching { Log.w(TAG, "failed to register Shizuku listeners", it) }
        }
    }

    fun refresh(): ShizukuPlatformState {
        val running = runCatching { bridge.pingBinder() }.getOrDefault(false)
        val installed = running || runCatching { bridge.isPackageInstalled() }.getOrDefault(false)
        val permissionGranted = running && runCatching {
            !bridge.isPreV11() && bridge.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val next = ShizukuPlatformState(
            installed = installed,
            running = running,
            permissionGranted = permissionGranted,
        )
        _state.value = next
        return next
    }

    suspend fun launchHelper(): BuiltinShellFailure? {
        val current = refresh()
        if (!current.ready) return current.failure()
        val generation = synchronized(lock) {
            bindRequested = true
            ++bindGeneration
        }
        return try {
            bridge.bindUserService(connection)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Shizuku bindUserService failed", e) }
            synchronized(lock) {
                if (bindGeneration == generation) {
                    bindRequested = false
                }
            }
            val refreshed = refresh()
            when {
                !refreshed.running -> BuiltinShellFailure.ShizukuNotRunning
                !refreshed.permissionGranted -> BuiltinShellFailure.ShizukuPermissionNeeded
                else -> BuiltinShellFailure.ShizukuBindFailed
            }
        }
    }

    fun prepareStop(): Boolean {
        val (prev, shouldUnbind) = synchronized(lock) {
            val wasActive = bindRequested || boundShell != null
            bindRequested = false
            if (wasActive) {
                bindGeneration++
            }
            val current = boundShell
            boundShell = null
            current to wasActive
        }
        if (prev != null) {
            detach(prev)
        }
        return shouldUnbind
    }

    fun unbindService() {
        runCatching { bridge.unbindUserService(connection, remove = true) }
    }

    fun stopHelper() {
        if (prepareStop()) {
            unbindService()
        }
    }

    fun stopHelperAsync() {
        if (prepareStop()) {
            scope.launch(io) { unbindService() }
        }
    }

    fun openGrantPermissionOrApp(activityOrContext: Context): Boolean = openGrantOrApp(activityOrContext)

    internal fun openGrantOrApp(activityOrContext: Context?): Boolean {
        val current = refresh()
        if (current.running && !current.permissionGranted) {
            val rationale = runCatching { bridge.shouldShowRequestPermissionRationale() }.getOrDefault(false)
            if (!rationale) {
                val requested = runCatching { bridge.requestPermission(REQUEST_CODE) }.isSuccess
                if (requested) return true
            }
        }
        return bridge.openShizukuApp(activityOrContext)
    }

    fun openShizukuApp(context: Context): Boolean = bridge.openShizukuApp(context)

    private class AndroidBridge(private val app: Context) : Bridge {
        private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
            Shizuku.UserServiceArgs(
                ComponentName(app.packageName, ShizukuShellUserService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("shizuku")
                .version(BuildConfig.VERSION_CODE)
        }

        override fun isPackageInstalled(): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        }.getOrDefault(false)

        override fun pingBinder(): Boolean = Shizuku.pingBinder()

        override fun isPreV11(): Boolean = Shizuku.isPreV11()

        override fun checkSelfPermission(): Int = Shizuku.checkSelfPermission()

        override fun shouldShowRequestPermissionRationale(): Boolean =
            Shizuku.shouldShowRequestPermissionRationale()

        override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

        override fun bindUserService(connection: ServiceConnection) {
            Shizuku.bindUserService(userServiceArgs, connection)
        }

        override fun unbindUserService(connection: ServiceConnection, remove: Boolean) {
            Shizuku.unbindUserService(userServiceArgs, connection, remove)
        }

        override fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) {
            Shizuku.addBinderReceivedListenerSticky(listener)
        }

        override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
            Shizuku.addBinderDeadListener(listener)
        }

        override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            Shizuku.addRequestPermissionResultListener(listener)
        }

        override fun openShizukuApp(context: Context?): Boolean {
            val ctx = context ?: return false
            val intent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                ctx.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 1001
        private const val TAG = "MikaShizuku"
    }
}
