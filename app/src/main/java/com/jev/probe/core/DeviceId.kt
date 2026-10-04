package com.jev.probe.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * A privacy-respecting, reinstall-stable device id.
 *
 * WHY NOT ANDROID_ID / IMEI / SERIAL:
 * Google's "Best practices for unique identifiers" says to prefer a **privately
 * stored GUID** and to avoid hardware identifiers entirely. ANDROID_ID (SSAID)
 * is also useless as a *stable* key here: it changes on reinstall, and several
 * vendors return garbage — MIUI >= 12.5 hands every app the constant
 * `9774d56d682e549c`, and some EMUI builds return `""` until system services
 * have settled. The previously used [Prefs.instanceId] therefore could not
 * survive an uninstall, nor reliably distinguish two devices.
 *
 * WHAT THIS IS INSTEAD:
 * A freshly generated random UUID (v4, 122 bits of entropy). Nothing about the
 * hardware or the OS goes into it, nobody else can derive it, and clearing the
 * app's own data gives a new one — exactly the properties we want.
 *
 * HOW IT SURVIVES UNINSTALL:
 * The value is mirrored into a tiny anchor file in the **public Download
 * directory** via MediaStore. Files there are owned by the *user*, not the app,
 * so uninstalling the app does not delete them. That anchor is the "key" used to
 * look this device's data back up from the WeAuto cloud; the bulk data itself
 * lives on Cloudflare, not here.
 *
 * IMPORTANT — must use the MediaStore API, never `File`:
 * On Android 11+ (this app is minSdk 30 / targetSdk 35) the system binds files
 * in public directories to the install instance that created them. After a
 * reinstall the new instance is a stranger: direct `File` access to files left
 * in Download returns `Permission denied` on write and `No such file or
 * directory` on read/stat. MediaStore queries do still see them, so every read
 * and write here goes through ContentResolver.
 *
 * The anchor contains **only the device id** — no keys, no notes, nothing else.
 */
object DeviceId {

    private const val TAG = "JEVASSIST"
    private const val SP_KEY = "device_id"
    private const val SP_SYNC = "jev_device"

    // Where the anchor lands: Download/WeAuto/weauto-device.json
    private const val REL_DIR = "Download/WeAuto"
    private const val FILENAME = "weauto-device.json"
    private const val MIME = "application/json"
    private const val KEY_ID = "device_id"

    /**
     * The stable id. Read order: in-process cache → app-private SharedPreferences
     * → MediaStore anchor. The last one is what makes a reinstall recover the
     * same value; anything else would mean the cloud backup could never be
     * addressed again.
     */
    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        val app = context.applicationContext

        val sp = app.getSharedPreferences(SP_SYNC, Context.MODE_PRIVATE)
        sp.getString(SP_KEY, null).orEmpty().takeIf { it.isNotBlank() }?.let {
            cached = it
            return it
        }

        // Nothing local — either a fresh install or a reinstall whose private
        // storage was wiped. Look for the anchor left behind by the old install.
        val restored = readAnchor(app)
        if (!restored.isNullOrBlank()) {
            Log.i(TAG, "device-id restored from anchor after reinstall")
            sp.edit().putString(SP_KEY, restored).apply()
            cached = restored
            return restored
        }

        val fresh = UUID.randomUUID().toString()
        sp.edit().putString(SP_KEY, fresh).apply()
        writeAnchor(app, fresh)
        cached = fresh
        Log.i(TAG, "device-id generated fresh")
        return fresh
    }

    /**
     * A short, non-reversible tag safe to write into logs and to use as a
     * request header: sending the raw id around would hand out a bearer token
     * to anyone holding a log file.
     */
    fun shortHash(context: Context): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(get(context).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
    } catch (_: Exception) {
        "unknown"
    }

    /** Re-mirror to the anchor; call after any successful cloud backup. */
    fun touchAnchor(context: Context) = writeAnchor(context.applicationContext, get(context))

    // --------------------------------------------------------------- anchor

    private fun readAnchor(context: Context): String? = try {
        val uri = findUri(context)
        if (uri == null) {
            Log.i(TAG, "device-id anchor: none found")
            null
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val text = input.bufferedCharReader().readText()
                org.json.JSONObject(text).optString(KEY_ID, "").ifBlank { null }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "device-id anchor read failed: ${e.message}")
        null
    }

    private fun writeAnchor(context: Context, id: String) {
        try {
            val payload = org.json.JSONObject().put(KEY_ID, id).toString()
            val uri = findUri(context)
            if (uri != null) {
                // Already there (reinstall case): overwrite in place. Inserting a
                // second row would leave MediaStore to dedupe it into
                // "weauto-device(1).json" and we would lose track of it.
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(payload.toByteArray())
                }
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILENAME)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, REL_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val inserted = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: run {
                Log.w(TAG, "device-id anchor: insert returned null")
                return
            }
            context.contentResolver.openOutputStream(inserted)?.use {
                it.write(payload.toByteArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(inserted, done, null, null)
            }
        } catch (e: Exception) {
            // Never fatal: this is a convenience key, and the app must run on
            // devices with no Downloads provider available.
            Log.w(TAG, "device-id anchor write failed: ${e.message}")
        }
    }

    /**
     * Locate the existing row by its exact display name. Filtering on
     * RELATIVE_PATH too makes this robust even if the user has their own
     * "WeAuto" folder somewhere else.
     */
    private fun findUri(context: Context): Uri? = try {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(FILENAME)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            selection, args, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val path = c.getString(pathCol) ?: ""
                if (path.equals(REL_DIR, true) || path.endsWith("WeAuto", true)) {
                    return Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString()
                    )
                }
            }
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun java.io.InputStream.bufferedCharReader() = this.reader(Charsets.UTF_8)
}
