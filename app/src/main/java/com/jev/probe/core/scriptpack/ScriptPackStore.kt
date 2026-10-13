package com.jev.probe.core.scriptpack

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jev.probe.core.DeviceId
import com.jev.probe.core.Prefs
import com.jev.probe.core.SecureStore
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 话术包的导入 / 列表 / 启用 / 删除 + 有效时间维护。
 *
 * 落盘纪律（对应 docs/scriptpack-spec.md §8）：
 *  - .wpk 文件始终是**密文原文**，原样存 app 私有目录 files/scriptpacks/；
 *  - 解密后的 [ScriptPack] 只活在内存，[effectivePack] 带 5 分钟 TTL 缓存；
 *  - 索引（哪个包启用、导入时间、网络时间偏移）经 [SecureStore] 加密存储；
 *  - 绝不写 logcat 话术内容、不进崩溃日志、不进 Prefs.exportAll 云备份。
 *
 * 时间锚：effectiveNow = 本地时间 + clock_offset_ms。offset 每次联网机会
 * （导入时 / 主页刷新时由调用方触发 [refreshClockOffset]）用 Worker 的 Date
 * 响应头校准，防改本地时钟续期。
 */
object ScriptPackStore {

    private const val TAG = "JEVASSIST"
    private const val DIR = "scriptpacks"
    private const val IDX_KEY = "scriptpack_index_v1"
    private const val K_ACTIVE = "active"
    private const val K_PACKS = "packs"
    private const val K_CLOCK_OFFSET = "clock_offset_ms"
    private const val K_CLOCK_AT = "clock_offset_at"
    private const val TTL_MS = 5 * 60 * 1000L
    private const val MAX_CLOCK_SKEW_MS = 7L * 24 * 3600 * 1000

    private class Cache(val pack: ScriptPack, val mtime: Long, val at: Long)

    @Volatile
    private var cache: Cache? = null

    // ------------------------------------------------------------ 文件与索引

    private fun packsDir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    private fun safeName(packId: String): String =
        packId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "pack" }

    private fun packFile(context: Context, safePackId: String): File =
        File(packsDir(context), "$safePackId.wpk")

    /**
     * 索引内容**没有任何秘密**：启用哪个包、导入时间、是否绑机、网络时钟偏移。
     * 加密它只是锦上添花（密文落在同样只有本 App 能读的私有目录），因此 keystore
     * 不可用时必须退化为明文，不能让话术包整体失效——实机在 ColorOS 上就撞到过
     * AndroidKeyStore 抛 IllegalStateException（账户 token 路径因为 `encrypt("")`
     * 直接短路返回，从没暴露过这个问题）。
     */
    private fun readIndex(context: Context): JSONObject = try {
        val enc = SecureStore.decrypt(context, indexRaw(context))
        val raw = if (enc.isNotBlank()) enc else plainFile(context).let {
            if (it.exists()) it.readText() else ""
        }
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun plainFile(context: Context): File =
        File(context.filesDir, "$IDX_KEY.json")

    private fun indexRaw(context: Context): String = try {
        val f = File(context.filesDir, "$IDX_KEY.enc")
        if (f.exists()) f.readText() else ""
    } catch (_: Exception) {
        ""
    }

    private fun saveIndex(context: Context, idx: JSONObject) {
        val text = idx.toString()
        try {
            val enc = SecureStore.encrypt(context, text)
            if (enc.isBlank()) throw IllegalStateException("SecureStore 返回空串")
            File(context.filesDir, "$IDX_KEY.enc").writeText(enc)
            plainFile(context).delete()
        } catch (e: Exception) {
            Log.w(TAG, "index secure save failed, fallback plain: " +
                "${e.javaClass.simpleName}: ${e.message}")
            try {
                plainFile(context).writeText(text)
            } catch (e2: Exception) {
                Log.w(TAG, "index plain save failed: ${e2.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------ 导入 / 管理

    /**
     * 导入：读 uri -> 完整校验链 -> 密文落盘 -> 设为启用 -> 校准网络时间。
     * 失败抛 [PackException]（调用方按 code 提示用户）。
     */
    fun importPack(context: Context, uri: Uri): PackHeader {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw PackException(PackError.TRUNCATED)
        val pack = ScriptPackCodec.parseAndDecrypt(
            bytes,
            AppPackCrypto.deviceHash(DeviceId.get(context)),
            effectiveNow(context),
            appVersion(context)
        )
        val safe = safeName(pack.header.packId)
        packFile(context, safe).writeBytes(bytes)

        val idx = readIndex(context)
        val packs = idx.optJSONObject(K_PACKS) ?: JSONObject()
        packs.put(safe, JSONObject()
            .put("imported_at", System.currentTimeMillis())
            .put("device_bound", pack.header.deviceBound))
        idx.put(K_PACKS, packs)
        idx.put(K_ACTIVE, safe)
        saveIndex(context, idx)
        cache = null
        refreshClockOffset(context)
        Log.i(TAG, "scriptpack imported: ${pack.header.packId} v${pack.header.version}")
        return pack.header
    }

    /** 已导入的话术包列表（只解明文头，含已过期的——设置页要能显示到期状态）。 */
    fun packs(context: Context): List<PackHeader> =
        packsDir(context).listFiles(java.io.FileFilter { f -> f.name.endsWith(".wpk") })
            ?.mapNotNull { f ->
                try {
                    ScriptPackCodec.readHeader(f.readBytes())
                } catch (e: PackException) {
                    AppPackCrypto.logReject("list", e.code.name)
                    null
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun activePackId(context: Context): String? =
        readIndex(context).optString(K_ACTIVE).ifBlank { null }

    fun setActive(context: Context, packId: String): Boolean {
        val safe = safeName(packId)
        if (!packFile(context, safe).exists()) return false
        val idx = readIndex(context)
        idx.put(K_ACTIVE, safe)
        saveIndex(context, idx)
        cache = null
        return true
    }

    fun remove(context: Context, packId: String) {
        val safe = safeName(packId)
        packFile(context, safe).delete()
        val idx = readIndex(context)
        idx.optJSONObject(K_PACKS)?.remove(safe)
        if (idx.optString(K_ACTIVE) == safe) idx.remove(K_ACTIVE)
        saveIndex(context, idx)
        cache = null
        Log.i(TAG, "scriptpack removed: $packId")
    }

    // ------------------------------------------------------------ 生效链路

    /**
     * 回复链路唯一入口：启用中且未过期的话术包；没有 / 已过期 / 任何异常都返回
     * null（调用方回退内置默认话术）——**话术包永远不允许把回复链路搞挂**。
     */
    fun effectivePack(context: Context): ScriptPack? {
        return try {
            val safe = activePackId(context) ?: return null
            val f = packFile(context, safe)
            if (!f.exists()) return null
            val mtime = f.lastModified()
            val now = effectiveNow(context)
            val c = cache
            if (c != null && c.mtime == mtime && now - c.at < TTL_MS) {
                if (now >= c.pack.header.expiresAt * 1000) null else c.pack
            } else {
                val pack = ScriptPackCodec.parseAndDecrypt(
                    f.readBytes(),
                    AppPackCrypto.deviceHash(DeviceId.get(context)),
                    now,
                    appVersion(context)
                )
                cache = Cache(pack, mtime, now)
                if (now >= pack.header.expiresAt * 1000) null else pack
            }
        } catch (e: PackException) {
            AppPackCrypto.logReject("effective", e.code.name)
            null
        } catch (e: Exception) {
            Log.w(TAG, "effectivePack io error: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 本机 device hash（16B hex）。设置页展示 + 绑机签发时照抄。 */
    fun deviceHashHex(context: Context): String =
        AppPackCrypto.deviceHash(DeviceId.get(context))
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ 时间锚

    /** 有效时间 = 本地时间 + 网络时间偏移（偏移 clamp 到 ±7 天，防异常 Date 头）。 */
    fun effectiveNow(context: Context): Long {
        val idx = readIndex(context)
        val offset = idx.optLong(K_CLOCK_OFFSET, 0).coerceIn(-MAX_CLOCK_SKEW_MS, MAX_CLOCK_SKEW_MS)
        return System.currentTimeMillis() + offset
    }

    /**
     * 用 Worker 健康端点的 Date 响应头校准时钟。任何失败静默保留原偏移
     * （首次无网络时退化为本地时间，spec §7 已声明该窗口）。
     */
    fun refreshClockOffset(context: Context) {
        try {
            val conn = (URL("${Prefs.WORKER_BASE}/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val date = conn.getHeaderField("Date")
            conn.disconnect()
            if (date.isNullOrBlank()) return
            val server = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(date)?.time ?: return
            val offset = (server - System.currentTimeMillis())
                .coerceIn(-MAX_CLOCK_SKEW_MS, MAX_CLOCK_SKEW_MS)
            val idx = readIndex(context)
            idx.put(K_CLOCK_OFFSET, offset)
            idx.put(K_CLOCK_AT, System.currentTimeMillis())
            saveIndex(context, idx)
        } catch (e: Exception) {
            Log.w(TAG, "clock offset refresh failed: ${e.javaClass.simpleName}")
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }
}
