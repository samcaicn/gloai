package com.jev.probe

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jev.probe.core.CloudSync
import com.jev.probe.core.CrashLog
import com.jev.probe.core.DeviceId
import com.jev.probe.core.Integrity
import com.jev.probe.core.Metrics
import com.jev.probe.core.Prefs

/**
 * App-wide entry point. Exists mainly so the cloud backup runs regardless of
 * which component the OS wakes first — the accessibility service and the
 * notification listener both start this app without ever opening
 * [MainActivity], and an unattended device may not show our UI for weeks.
 *
 * Work done here is deliberately cheap and off the main thread: one restore
 * attempt plus one upload, both of which no-op silently when the Worker endpoint
 * is not deployed yet or the network is unreachable.
 *
 * It also installs the crash / ANR recorders (P0-5). Both write to a local file
 * only — nothing is ever uploaded — and both are best-effort: if the recorder
 * itself fails, the app must still start.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        APP_CONTEXT = applicationContext
        val prefs = Prefs(this)
        // Touch lazily so the id/anchor are established before anything else asks.
        DeviceId.get(this)
        Metrics.init(this, prefs.metricsEnabled)
        installCrashHandler()
        installAnrWatchdog()
        Thread {
            try {
                // 运行时完整性：重签/马甲包/注入框架/调试器 → 置 tampered 标记，
                // 核心功能据此在客户端降级；真正的不可绕过在 L3 服务端按官方签名指纹放行。
                Integrity.verify(this@App)
            } catch (e: Exception) {
                Log.w(TAG, "integrity verify failed: ${e.message}")
            }
            try {
                // Restore first: whatever comes back decides what preferences exist,
                // and the upload that follows should then describe the merged state.
                CloudSync.restoreOnce(this@App, prefs)
                CloudSync.uploadNow(this@App, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "background sync failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /** Uncaught exception on any thread → local file. The default handler still
     *  runs afterwards, so the OS behaviour (crash dialog) is unchanged. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { CrashLog.record(this, "crash", thread.name, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * ANR watchdog: post a no-op to the main looper every 4s; if it has not run
     * 5s later, the main thread was blocked. We only sample the stack — we never
     * kill or restart anything, and we never upload it.
     */
    private fun installAnrWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val answered = java.util.concurrent.atomic.AtomicBoolean(true)
        Thread {
            while (true) {
                try {
                    answered.set(false)
                    main.post { answered.set(true) }
                    Thread.sleep(ANR_WAIT_MS)
                    if (!answered.get()) {
                        val stack = Looper.getMainLooper().thread.stackTrace
                            .take(14).joinToString("\n") { "  at $it" }
                        CrashLog.recordRaw(this, "anr",
                            "main thread blocked >${ANR_WAIT_MS}ms\n$stack")
                    }
                    Thread.sleep(ANR_POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {
                    // Never let the watchdog itself take the app down.
                }
            }
        }.apply { isDaemon = true; name = "anr-watchdog" }.start()
    }

    companion object {
        const val TAG = "JEVASSIST"
        const val ANR_POLL_MS = 4_000L
        const val ANR_WAIT_MS = 5_000L

        /** 应用级 Context 静态句柄，供 HttpJson / SyncClient 等无 Context 的调用点取用（仅拼请求头）。 */
        lateinit var APP_CONTEXT: Context
            private set
    }
}
