package com.jev.probe.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client half of the WeAuto cloud backup (server side: weauto-license Worker at
 * [Prefs.WORKER_BASE], same place the license key exchange already lives).
 *
 * Address scheme: `bak:<deviceId>` inside the Worker's KV. The device id *is*
 * the address, which is why it has to stay reinstall-stable — see
 * [DeviceId] for how that is achieved. Because it doubles as a credential the id
 * is a plain random UUID (122 bits), so guessing another device's slot is not
 * feasible without another form of leak.
 *
 * Payload is **plain JSON by user choice** — readable and editable by hand. That
 * tradeoff is explicit: it is the user's own Worker and their own data, and being
 * able to inspect the backup was preferred over local confidentiality.
 *
 * Everything here degrades silently. The Worker endpoint may not be deployed yet,
 * the network may be down, Cloudflare may be having a bad day — none of that may
 * ever interfere with reading and replying to messages, so callers get a result
 * object and decide for themselves whether to surface anything.
 */
object SyncClient {

    private const val TAG = "JEVASSIST"
    private const val CONNECT_MS = 8000
    private const val READ_MS = 15000
    /** Free-tier KV caps a value at 25 MB; nothing we produce comes close. */
    private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

    sealed class Result {
        data class Ok(val snapshot: JSONObject, val updatedAt: Long) : Result()
        /** Backup slot exists on the server but holds nothing usable yet. */
        object Empty : Result()
        /**
         * Endpoint not deployed on this Worker yet (404). Expected state until
         * `/sync` ships — must NOT be treated as an error worth telling the user
         * about.
         */
        object NotDeployed : Result()
        data class Failure(val message: String) : Result()
    }

    /** Plain-JSON responses preferred; keep WAF happy with a UA. */
    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "jev-assistant-android")
        }

    /**
     * Fetch this device's snapshot.
     *
     * Must run off the main thread — does blocking network I/O.
     */
    fun download(context: Context, mid: String, base: String = Prefs.WORKER_BASE): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = open("${base.trimEnd('/')}/sync?mid=$mid", "GET")
            val code = conn.responseCode
            when (code) {
                404 -> Result.NotDeployed
                !in 200..299 -> Result.Failure("sync HTTP $code")
                else -> {
                    val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (body.isBlank()) Result.Empty
                    else Result.Ok(JSONObject(body), System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            // Offline, DNS blocked, TLS reset. All routine; stay quiet.
            Result.Failure(e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Write this device's snapshot. Returns true on success, false without a
     * message when the endpoint simply is not deployed yet.
     */
    fun upload(context: Context, mid: String, snapshot: JSONObject,
               base: String = Prefs.WORKER_BASE): Result {
        var conn: HttpURLConnection? = null
        return try {
            val body = snapshot.toString().toByteArray(Charsets.UTF_8)
            if (body.size > MAX_UPLOAD_BYTES) {
                return Result.Failure("快照过大（${body.size / 1024}KB）")
            }
            conn = open("${base.trimEnd('/')}/sync?mid=$mid", "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setChunkedStreamingMode(0)
            }
            conn.outputStream.use { it.write(body) }
            when (val code = conn.responseCode) {
                404 -> Result.NotDeployed
                !in 200..299 -> Result.Failure("sync HTTP $code")
                else -> Result.Ok(snapshot, System.currentTimeMillis()).also {
                    Log.i(TAG, "sync upload ok ${body.size}B")
                }
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }
}
