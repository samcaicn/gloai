package com.jev.probe.core

import android.content.Context
import android.util.Log
import com.jev.probe.core.kb.KbStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Assembles and applies the cloud snapshot that makes an uninstall/reinstall a
 * non-event.
 *
 * What goes into it (per the user's choice, all plain JSON so it stays readable):
 *   - the reinstall-stable device id, and the **old purchase mid**;
 *   - every preference except the two KeyStore-sealed blobs (see [Prefs.exportAll]);
 *   - the knowledge base: notes + contacts.
 * Deliberately NOT included: chat history, per-conversation logs. Those are the
 * one thing you would rather not have sitting on someone else's storage, they are
 * cheap to reaccumulate, and most installs never write them anyway.
 *
 * Two invariants worth reading the code by:
 *
 *  1. A restore never overwrites what the user already has. Every stage only
 *     fills gaps. That keeps a restore harmless to run twice, and safe if it
 *     lands after the user has already started reconfiguring by hand.
 *  2. The encrypted licence blobs are skipped, not copied. They cannot decrypt
 *     under a new KeyStore, and importing them would produce the worst possible
 *     failure — looking activated while yielding empty credentials. With the old
 *     mid back in place the existing `/license?mid=` polling re-fetches the real
 *     key from the server, which is where it should come from anyway.
 */
object CloudSync {

    private const val TAG = "JEVASSIST"
    private const val VERSION = 1

    /** True while the one-shot post-reinstall import is running. */
    private val restoring = AtomicBoolean(false)

    // ----------------------------------------------------------- snapshot

    fun build(context: Context, prefs: Prefs): JSONObject {
        val app = context.applicationContext
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("ts", System.currentTimeMillis())
        o.put("device_id", DeviceId.get(app))
        o.put("license_mid", prefs.licenseMid)
        o.put("prefs", prefs.exportAll())
        try {
            val kb = KbStore.get(app)
            o.put("kb", JSONObject().apply {
                put("notes", notesJson(kb.notes()))
                put("contacts", contactsJson(kb.contacts()))
            })
        } catch (e: Exception) {
            // A broken knowledge base must never block the rest of the backup.
            Log.w(TAG, "snapshot: kb skipped: ${e.message}")
        }
        return o
    }

    private fun notesJson(list: List<com.jev.probe.core.kb.Note>) = JSONArray().apply {
        list.forEach { n ->
            put(JSONObject().apply {
                put("id", n.id); put("title", n.title); put("content", n.content)
                put("tags", JSONArray(n.tags))
                put("alwaysOn", n.alwaysOn); put("enabled", n.enabled)
                put("updatedAt", n.updatedAt)
            })
        }
    }

    private fun contactsJson(list: List<com.jev.probe.core.kb.Contact>) = JSONArray().apply {
        list.forEach { c ->
            put(JSONObject().apply {
                put("id", c.id); put("name", c.name)
                put("aliases", JSONArray(c.aliases)); put("apps", JSONArray(c.apps))
                put("relationship", c.relationship); put("notes", c.notes)
                put("autoSummary", c.autoSummary); put("updatedAt", c.updatedAt)
            })
        }
    }

    // ------------------------------------------------------------- upload

    /** Blocking — call from a worker thread. */
    fun uploadNow(context: Context, prefs: Prefs): SyncClient.Result {
        val app = context.applicationContext
        if (!prefs.syncEnabled) {
            Log.i(TAG, "sync disabled, skipped")
            return SyncClient.Result.Empty
        }
        val snap = build(app, prefs)
        val res = SyncClient.upload(app, DeviceId.get(app), snap)
        if (res is SyncClient.Result.Ok) {
            prefs.lastSyncAt = System.currentTimeMillis()
            // Keep the local key mirrored even if the upload succeeded but the
            // anchor was never written (e.g. first-run permission edge cases).
            DeviceId.touchAnchor(app)
        }
        return res
    }

    // ----------------------------------------------------------- download

    /** Blocking — call from a worker thread. */
    fun downloadNow(context: Context, prefs: Prefs): SyncClient.Result =
        SyncClient.download(context.applicationContext, DeviceId.get(context))

    /**
     * Post-reinstall restore. Fills gaps only; see the class doc for why.
     *
     * @return summary string suitable for a toast/log, or null when nothing
     *         needed doing (including "endpoint not deployed yet").
     */
    fun restoreOnce(context: Context, prefs: Prefs): String? {
        if (prefs.syncRestored) {
            Log.i(TAG, "restore skipped: already done once")
            return null
        }
        return pullAndApply(context, prefs)
    }

    /**
     * Same thing for the explicit "restore from cloud" button — ignores the
     * "already done" marker. Still gap-fill only, so pressing it repeatedly, or
     * after the user has hand-tuned their settings, cannot lose their work.
     */
    fun restoreNow(context: Context, prefs: Prefs): String? = pullAndApply(context, prefs)

    private fun pullAndApply(context: Context, prefs: Prefs): String? {
        if (!restoring.compareAndSet(false, true)) return null
        return try {
            val app = context.applicationContext
            val res = SyncClient.download(app, DeviceId.get(app))
            when (res) {
                SyncClient.Result.NotDeployed -> {
                    // Leave [syncRestored] false: once /sync ships, the next launch
                    // should still get to try.
                    Log.i(TAG, "restore skipped: /sync not deployed yet")
                    null
                }
                is SyncClient.Result.Failure -> {
                    // 5xx 是服务端（Worker 未部署 / Cloudflare 源站故障），用户改不了，
                    // 降为 debug：否则每次启动都刷一行「sync HTTP 525」吓人且没信息量。
                    if (res.message.contains("HTTP 5")) Log.d(TAG, "restore unavailable: ${res.message}")
                    else Log.w(TAG, "restore failed: ${res.message}")
                    null
                }
                SyncClient.Result.Empty -> {
                    prefs.syncRestored = true
                    null
                }
                is SyncClient.Result.Ok -> {
                    apply(app, prefs, res.snapshot)
                }
            }
        } finally {
            restoring.set(false)
        }
    }

    /** Applies a snapshot; only fills gaps. Safe to run repeatedly. */
    private fun apply(app: Context, prefs: Prefs, snap: JSONObject): String {
        // The purchase anchor first — losing it strands a paid licence on the server.
        prefs.adoptLicenseMid(snap.optString("license_mid", ""))

        val prefsObj = snap.optJSONObject("prefs")
        val nPrefs = prefsObj?.let { prefs.importAll(it) } ?: 0

        var nKb = 0
        snap.optJSONObject("kb")?.let { kb ->
            try {
                nKb += KbRestore.apply(app, kb)
            } catch (e: Exception) {
                Log.w(TAG, "kb restore failed: ${e.message}")
            }
        }

        prefs.syncRestored = true
        Log.i(TAG, "restore applied: prefs=$nPrefs kb=$nKb")
        return "已恢复 $nPrefs 项配置、$nKb 条笔记/联系人"
    }
}
