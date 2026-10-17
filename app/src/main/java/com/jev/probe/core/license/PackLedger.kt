package com.jev.probe.core.license

import android.content.Context
import android.util.Log
import com.jev.probe.core.SecureStore
import com.jev.probe.core.scriptpack.ScriptPackStore
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 授权账本（对应 PC 版 `weauto_license/ledger.py`，2026-10-16 落地）。
 *
 * 两层额度，一个账本：
 *  1. **离线授权**（v1.1 话术包）：导入带 `reply_quota` 的 .wpk 后，回复走
 *     **授权总次数**（跨天累计、用一次扣一次、用完需再购）；
 *  2. **每日免费**：无授权设备按自然日计数（随导入话术包条数 / 自定义大模型
 *     100 条 / 基线 30 条，见 [dailyFreeLimit]，隔日重置）。
 *
 * 四道防线（与 PC 版逐条对应）：
 *  1. **HMAC 防篡改**——账本 JSON 带签名，密钥材料在 AndroidKeyStore 里
 *     （SecureStore AES-GCM 加密的 32B 盐）。手工改 JSON → 验签失败 →
 *     视为 `__tampered__` → **不默认放行**（按超限处理）；
 *  2. **max_used 单调递增防回滚**——攻击者备份旧账本（used 小）恢复，
 *     读取时把 used 抬回历史高水位；
 *  3. **可信时钟**——按 `ScriptPackStore.effectiveNow()`（本地+网络偏移）判
 *     「今天」，改系统日期刷不了每日额度；
 *  4. **云端口径校准**——Worker 响应头 `X-Quota-Remaining / X-Quota-Limit`
 *     覆盖本地计数（本地只是体验优化，云端才是真额度）。
 *
 * 说明：云端 BILLING_KV 才是硬墙；本地账本是第二道，避免把滥用流量打给云端。
 */
object PackLedger {

    private const val TAG = "PackLedger"

    /**
     * 每日免费条数基线（与 PC 版 FREE_QUOTA_DEFAULT 一致）。
     * 实际生效值见 [dailyFreeLimit]：随导入话术包条数变化 / 自定义大模型放宽到 100。
     */
    const val FREE_QUOTA_DEFAULT = 30

    /** 用户自定义大模型（自带接口直连）时的每日免费上限。 */
    const val FREE_QUOTA_CUSTOM_MODEL = 100

    private const val SP_NAME = "pack_ledger"
    private const val K_BLOB = "blob"
    private const val K_SALT = "ledger_salt"

    /** 授权摘要（UI 展示 + 判定用）。null = 当前无有效授权。 */
    data class LicenseInfo(
        val packId: String,
        val name: String,
        val total: Int,
        val used: Int,
        val expiresAt: Long
    ) {
        val remaining: Int get() = (total - used).coerceAtLeast(0)
    }

    /** 额度判定结果。[limit] 为计费口径上限（授权=总次数；免费=每日条数）。 */
    data class QuotaState(
        val allowed: Boolean,
        val remaining: Int,
        val limit: Int,
        val licensed: Boolean,
        val tampered: Boolean
    )

    // ------------------------------------------------------------ 签名材料

    /** HMAC 密钥：32B 随机盐，经 SecureStore（AndroidKeyStore AES-GCM）加密后落盘。 */
    private fun signSalt(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val enc = prefs.getString(K_SALT, "") ?: ""
        if (enc.isNotBlank()) {
            val dec = SecureStore.decrypt(context, enc)
            if (dec.isNotBlank()) {
                val raw = Base64.getDecoder().decode(dec)
                if (raw.size == 32) return raw
            }
        }
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val enc2 = SecureStore.encrypt(context, Base64.getEncoder().encodeToString(fresh))
        prefs.edit().putString(K_SALT, enc2).apply()
        return fresh
    }

    private fun hmacHex(salt: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 账本读写

    private data class Ledger(
        val tampered: Boolean,
        val date: String,
        val count: Int,
        val license: JSONObject?
    )

    private fun read(context: Context): Ledger {
        val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(K_BLOB, "") ?: ""
        if (raw.isBlank()) return Ledger(false, "", 0, null)
        return try {
            val d = JSONObject(raw)
            val sig = d.optString("sig", "")
            d.remove("sig")
            val want = hmacHex(signSalt(context), d.toString())
            if (sig.length != want.length || !constantTimeEquals(sig, want)) {
                Log.w(TAG, "ledger signature mismatch -> treat as tampered")
                return Ledger(true, "", 0, null)
            }
            val lic = d.optJSONObject("offline")
            Ledger(false, d.optString("date", ""), d.optInt("count", 0), lic)
        } catch (e: Exception) {
            Log.w(TAG, "ledger read failed: ${e.javaClass.simpleName}")
            Ledger(true, "", 0, null)
        }
    }

    private fun write(context: Context, date: String, count: Int, license: JSONObject?) {
        try {
            val d = JSONObject()
            d.put("date", date)
            d.put("count", count)
            if (license != null) d.put("offline", license)
            d.put("sig", hmacHex(signSalt(context), d.toString()))
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putString(K_BLOB, d.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "ledger write failed: ${e.javaClass.simpleName}")
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    /** 可信「今天」：防改系统日期刷每日额度。 */
    private fun today(context: Context): String {
        val now = ScriptPackStore.effectiveNow(context)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return fmt.format(java.util.Date(now))
    }

    // ------------------------------------------------------------ 离线授权

    /**
     * 导入授权后写入账本（对应 PC 版 set_offline_license）。
     * total<=0 或 packId 空 → 拒绝；被篡改的账本 → 拒绝。
     */
    fun setOfflineLicense(context: Context, packId: String, name: String, total: Int, expiresAt: Long): Boolean {
        if (packId.isBlank() || total <= 0) return false
        val cur = read(context)
        if (cur.tampered) return false
        val lic = JSONObject()
        lic.put("pack_id", packId)
        lic.put("name", name.ifBlank { packId })
        lic.put("total", total)
        lic.put("used", 0)
        lic.put("max_used", 0)
        lic.put("expires_at", expiresAt)
        lic.put("imported_at", System.currentTimeMillis() / 1000)
        write(context, cur.date, cur.count, lic)
        Log.i(TAG, "offline license set: pack=$packId total=$total")
        return true
    }

    fun clearOfflineLicense(context: Context) {
        val cur = read(context)
        if (cur.tampered) return
        write(context, cur.date, cur.count, null)
    }

    /** 当前生效的授权摘要；无授权/已过期/已用完 → null。 */
    fun offlineLicense(context: Context): LicenseInfo? {
        val cur = read(context)
        if (cur.tampered) return null
        val o = cur.license ?: return null
        val packId = o.optString("pack_id", "")
        if (packId.isBlank()) return null
        val total = o.optInt("total", 0)
        if (total <= 0) return null
        val expires = o.optLong("expires_at", 0)
        if (expires > 0 && expires * 1000 <= ScriptPackStore.effectiveNow(context)) return null
        return LicenseInfo(
            packId = packId,
            name = o.optString("name", packId),
            total = total,
            used = o.optInt("used", 0),
            expiresAt = expires
        )
    }

    // ------------------------------------------------------------ 额度判定

    /**
     * 当前生效的每日免费上限（无授权时用）：
     * - 用户自定义大模型（自带接口直连，不走云端）→ 固定 [FREE_QUOTA_CUSTOM_MODEL] 条/天；
     * - 导入了生效话术包 → 随包内话术条数（fewShot 数量）；
     * - 都没有 → 基线 [FREE_QUOTA_DEFAULT] 条/天。
     * 注意：云端 KV 的免费硬墙口径需与服务端同步调整，本地值只是第二道闸。
     */
    fun dailyFreeLimit(context: Context): Int {
        if (com.jev.probe.core.Prefs.isTextCustom()) return FREE_QUOTA_CUSTOM_MODEL
        val entries = try {
            ScriptPackStore.effectivePack(context)?.fewShot?.size ?: 0
        } catch (_: Exception) {
            0
        }
        return if (entries > 0) entries else FREE_QUOTA_DEFAULT
    }

    /**
     * 是否还能用（对应 PC 版 check_quota）：
     * - 有有效授权 → 按授权总次数判定（跨天累计，不受每日限制）；
     * - 无授权 → 每日免费条数（[dailyFreeLimit]，隔日重置）；
     * - 账本被篡改 → (false, 0, limit)（不默认放行）。
     */
    fun checkQuota(context: Context): QuotaState {
        val cur = read(context)
        if (cur.tampered) {
            return QuotaState(false, 0, FREE_QUOTA_DEFAULT, licensed = false, tampered = true)
        }
        val lic = offlineLicense(context)
        if (lic != null) {
            return QuotaState(
                allowed = lic.used < lic.total,
                remaining = lic.remaining,
                limit = lic.total,
                licensed = true,
                tampered = false
            )
        }
        val limit = dailyFreeLimit(context)
        val day = today(context)
        val used = if (cur.date == day) cur.count else 0
        return QuotaState(
            allowed = used < limit,
            remaining = (limit - used).coerceAtLeast(0),
            limit = limit,
            licensed = false,
            tampered = false
        )
    }

    /** 记一次使用（对应 PC 版 record_usage）：有授权扣授权次数，否则记每日免费。 */
    fun recordUsage(context: Context) {
        val cur = read(context)
        if (cur.tampered) return
        val o = cur.license
        if (o != null && o.optString("pack_id", "").isNotBlank() && o.optInt("total", 0) > 0) {
            val used = o.optInt("used", 0) + 1
            o.put("used", used)
            o.put("max_used", maxOf(o.optInt("max_used", 0), used))
            write(context, cur.date, cur.count, o)
            return
        }
        val day = today(context)
        if (cur.date != day) write(context, day, 1, null)
        else write(context, day, cur.count + 1, null)
    }

    /**
     * 重置当日免费计数（设置页「重置今日免费计数」按钮 / adb 广播调试通道）：
     * - 只清本地当日 count。**云端 KV（free:<utcday>:<inst>）仍是 30 条/天/设备的
     *   硬墙**——重置本地只是让客户端不再提前拦截，换不来超额云端额度；
     * - 话术包授权账本（offline license）原样保留，授权次数不受影响；
     * - 账本被篡改（tampered）时借此修复为一份全新的合法账本（授权信息无法
     *   从不可信 blob 恢复，需重新导入话术包）。
     */
    fun resetDaily(context: Context): Boolean {
        val cur = read(context)
        val keep = if (cur.tampered) null else cur.license
        write(context, today(context), 0, keep)
        Log.i(
            TAG,
            "daily free count reset (tampered=${cur.tampered}, licenseKept=${keep != null})"
        )
        return true
    }

    /**
     * 用云端响应头校准本地计数（对应 PC 版 sync_from_headers）：
     * - 有授权 → 按云端口径覆盖 used（limit = 授权总次数）；
     * - 无授权 → 覆盖当日 count。
     * 云端口径优先，避免本地漂移误杀/放纵。
     */
    fun syncFromHeaders(context: Context, remaining: Int?, limit: Int?) {
        if (remaining == null || limit == null || limit <= 0) return
        val cur = read(context)
        if (cur.tampered) return
        val used = (limit - remaining).coerceAtLeast(0)
        val o = cur.license
        if (o != null && o.optString("pack_id", "").isNotBlank() && o.optInt("total", 0) > 0) {
            o.put("used", used)
            o.put("max_used", maxOf(o.optInt("max_used", 0), used))
            write(context, cur.date, cur.count, o)
        } else {
            write(context, today(context), used, null)
        }
    }
}
