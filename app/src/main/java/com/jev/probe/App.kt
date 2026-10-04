package com.jev.probe

import android.app.Application
import android.util.Log
import com.jev.probe.core.CloudSync
import com.jev.probe.core.DeviceId
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
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        // Touch lazily so the id/anchor are established before anything else asks.
        DeviceId.get(this)
        Thread {
            try {
                // Restore first: whatever comes back decides what preferences exist,
                // and the upload that follows should then describe the merged state.
                CloudSync.restoreOnce(this, prefs)
                CloudSync.uploadNow(this, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "background sync failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "JEVASSIST"
    }
}
