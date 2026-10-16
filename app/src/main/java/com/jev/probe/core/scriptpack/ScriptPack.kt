package com.jev.probe.core.scriptpack

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** 拒收原因码。设置页按码取 [com.jev.probe.core.t] 双语文案，不把码透给用户。 */
enum class PackError {
    NOT_A_PACK, BAD_VERSION, TRUNCATED, BAD_HEADER,
    SIGNATURE, DEVICE, EXPIRED, DECRYPT, CHECKSUM, EXPIRES_MISMATCH, APP_TOO_OLD
}

class PackException(val code: PackError) : Exception("scriptpack: $code")

data class PackHeader(
    val packId: String,
    val name: String,
    val version: Int,
    val author: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val deviceHashes: List<String>,
    val minAppVersion: String,
    /**
     * v1.1（2026-10-16，对齐 PC 版）：该包携带的离线授权回复次数。
     * >0 = 导入即授权（同时是话术包），且**必须绑机**（device_hashes 含本机），
     * 否则任何人拿到文件都能在别的机器上用次数——次数是商品，不绑机等于白送。
     * =0 = 纯话术包（无授权语义，旧格式兼容）。
     */
    val replyQuota: Int = 0
) {
    val deviceBound: Boolean get() = deviceHashes.isNotEmpty()

    /** 携带授权次数的包（导入后要写 PackLedger）。 */
    val carriesLicense: Boolean get() = replyQuota > 0
}

data class PackStyle(
    val tone: String = "",
    val persona: String = "",
    val taboo: String = "",
    val lengthHint: String = ""
)

data class PackFewShot(val user: String, val assistant: String)

data class PackParams(
    val temperature: Double = 0.8,
    val maxTokens: Int = 200,
    val candidates: Int = 3
)

/** 已验签 + 已解密的话术包（只活在内存，不落盘、不进日志、不进云备份）。 */
data class ScriptPack(
    val header: PackHeader,
    val systemPrompt: String,
    val style: PackStyle,
    val fewShot: List<PackFewShot>,
    val params: PackParams,
    val relationshipHint: String
)

/**
 * .wpk 解析 / 验签 / 解密。与生成端 tools/scriptpack/packcrypto.py 同布局。
 *
 * 校验链顺序（任何一步失败即拒，[PackException.code] 指明原因）：
 *   1. magic / 版本 / 长度
 *   2. ECDSA P-256 验签（覆盖 header + 密文，防伪造、防改到期时间）
 *   3. 设备允许列表（绑机包）
 *   4. header.expires_at 到期判定
 *   5. App 版本门槛
 *   6. AES-256-GCM 解密（tag 失败 = 密钥错 / 设备不匹配 / 包被改）
 *   7. payload 摘要复核 + header/payload 双写 expires_at 一致性 + payload 到期判定
 *
 * 命名说明：内容数据类叫 [ScriptPack]，编解码器叫本 object——同一 package 内
 * 两个同名声明会 Redeclaration。
 */
object ScriptPackCodec {

    private const val TAG = "JEVASSIST"
    const val MAGIC = "WPK1"
    private const val FORMAT_VERSION = 1

    /**
     * 验签公钥（EC prime256v1 SPKI，标准 base64）。
     * **当前是测试值**（配对私钥在 tools/scriptpack/testdata/test_sig.pem，已 gitignore，
     * 仅本机实机测试用）：正式发行时用 `make_pack.py genkey` 打印的 SPKI 行整行替换，
     * 对应私钥由商户线下保管，永不进仓库、不进 CI。
     */
    const val PACK_VERIFY_PUBKEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEkECcoqq0+zhzMcMryblpbhJyb/BkZTR/7pe++yWB95hDQRiqBOgPTXbys6Fy7ZThscz08s8yXDvNCLXcWaFtBw=="

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toInt() and 0xFF).toLong() or
            ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[off + 3].toInt() and 0xFF).toLong() shl 24)

    private fun b64url(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** 只解明文头（导入列表 / 到期展示用），不验签不解密。 */
    fun readHeader(data: ByteArray): PackHeader {
        if (data.size < 8 || String(data, 0, 4, Charsets.US_ASCII) != MAGIC)
            throw PackException(PackError.NOT_A_PACK)
        val ver = data[4].toInt() and 0xFF
        if (ver != FORMAT_VERSION) throw PackException(PackError.BAD_VERSION)
        val hlen = u16le(data, 6)
        if (data.size < 8 + hlen) throw PackException(PackError.TRUNCATED)
        val json = try {
            JSONObject(String(data, 8, hlen, Charsets.UTF_8))
        } catch (_: Exception) {
            throw PackException(PackError.BAD_HEADER)
        }
        return headerOf(json)
    }

    private fun headerOf(json: JSONObject): PackHeader {
        val devices = ArrayList<String>()
        val arr: JSONArray? = json.optJSONArray("device_hashes")
        if (arr != null) for (i in 0 until arr.length()) devices.add(arr.optString(i))
        return PackHeader(
            packId = json.optString("pack_id"),
            name = json.optString("name").ifBlank { json.optString("pack_id") },
            version = json.optInt("version", 1),
            author = json.optString("author"),
            issuedAt = json.optLong("issued_at", 0),
            expiresAt = json.optLong("expires_at", 0),
            deviceHashes = devices,
            minAppVersion = json.optString("min_app_version").ifBlank { "0.0.0" },
            replyQuota = json.optInt("reply_quota", 0).coerceAtLeast(0)
        )
    }

    /** 完整校验链，见类注释。nowMs 传**有效时间**（含网络时间偏移校正）。 */
    fun parseAndDecrypt(
        data: ByteArray,
        deviceHash: ByteArray,
        nowMs: Long,
        appVersion: String
    ): ScriptPack {
        if (data.size < 8 || String(data, 0, 4, Charsets.US_ASCII) != MAGIC)
            throw PackException(PackError.NOT_A_PACK)
        if (data[4].toInt() and 0xFF != FORMAT_VERSION) throw PackException(PackError.BAD_VERSION)
        val hlen = u16le(data, 6)
        if (data.size < 8 + hlen) throw PackException(PackError.TRUNCATED)
        val headerJson = try {
            JSONObject(String(data, 8, hlen, Charsets.UTF_8))
        } catch (_: Exception) {
            throw PackException(PackError.BAD_HEADER)
        }
        val header = headerOf(headerJson)
        var off = 8 + hlen
        if (data.size < off + 4) throw PackException(PackError.TRUNCATED)
        val payloadLen = u32le(data, off)
        off += 4
        if (payloadLen < 16 || data.size < off + payloadLen + 2)
            throw PackException(PackError.TRUNCATED)
        val ctTag = data.copyOfRange(off, off + payloadLen.toInt())
        off += payloadLen.toInt()
        val sigLen = u16le(data, off)
        off += 2
        if (data.size < off + sigLen) throw PackException(PackError.TRUNCATED)
        val sig = data.copyOfRange(off, off + sigLen)
        // AAD = 文件中密文之前的全部字节（magic+ver+flags+hlen+header+payload_len）
        val aad = data.copyOfRange(0, 8 + hlen + 4)

        // 2. 验签
        if (!verifySignature(aad + ctTag, sig)) {
            AppPackCrypto.logReject("verify", PackError.SIGNATURE.name)
            throw PackException(PackError.SIGNATURE)
        }

        // 3. 设备允许列表
        val dh = if (header.deviceBound) deviceHash else ByteArray(16)
        if (header.deviceBound && !header.deviceHashes.contains(hex(dh))) {
            AppPackCrypto.logReject("device", PackError.DEVICE.name)
            throw PackException(PackError.DEVICE)
        }

        // 3b. v1.1：携带授权次数的包必须绑机（次数=商品，不绑机=白送）。
        //     与 PC 版 scriptpack.py import_offline_pack 同口径。
        if (header.carriesLicense && !header.deviceBound) {
            AppPackCrypto.logReject("quota-unbound", PackError.DEVICE.name)
            throw PackException(PackError.DEVICE)
        }

        // 4. 到期（header 被签名覆盖，改不了）
        if (nowMs >= header.expiresAt * 1000) throw PackException(PackError.EXPIRED)

        // 5. App 版本门槛
        if (compareVersion(appVersion, header.minAppVersion) < 0)
            throw PackException(PackError.APP_TOO_OLD)

        // 6. 解密
        val salt = b64url(headerJson.optString("kdf_salt"))
        val nonce = b64url(headerJson.optString("nonce"))
        val key = AppPackCrypto.deriveKey(AppPackCrypto.masterKey(), header.packId, salt, dh)
        val pt = try {
            AppPackCrypto.aesGcmDecrypt(key, nonce, ctTag, aad)
        } catch (e: Exception) {
            AppPackCrypto.logReject("decrypt", e.javaClass.simpleName)
            throw PackException(PackError.DECRYPT)
        }

        // 7. 摘要 + 双写到期
        val payload = try {
            JSONObject(String(pt, Charsets.UTF_8))
        } catch (_: Exception) {
            throw PackException(PackError.DECRYPT)
        }
        val expect = try {
            Base64.decode(
                headerJson.optString("payload_sha256"),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        } catch (_: Exception) {
            throw PackException(PackError.BAD_HEADER)
        }
        if (!expect.contentEquals(AppPackCrypto.sha256(pt)))
            throw PackException(PackError.CHECKSUM)
        val payloadExpires = payload.optLong("expires_at", 0)
        if (payloadExpires != header.expiresAt)
            throw PackException(PackError.EXPIRES_MISMATCH)
        if (nowMs >= payloadExpires * 1000) throw PackException(PackError.EXPIRED)

        return ScriptPack(
            header = header,
            systemPrompt = payload.optString("system_prompt"),
            style = styleOf(payload.optJSONObject("style")),
            fewShot = fewShotOf(payload.optJSONArray("few_shot")),
            params = paramsOf(payload.optJSONObject("params")),
            relationshipHint = payload.optString("relationship_hint")
        )
    }

    private fun styleOf(json: JSONObject?): PackStyle {
        json ?: return PackStyle()
        return PackStyle(
            tone = json.optString("tone"),
            persona = json.optString("persona"),
            taboo = json.optString("taboo"),
            lengthHint = json.optString("length_hint")
        )
    }

    private fun fewShotOf(arr: JSONArray?): List<PackFewShot> {
        arr ?: return emptyList()
        val out = ArrayList<PackFewShot>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(PackFewShot(o.optString("user"), o.optString("assistant")))
        }
        return out
    }

    private fun paramsOf(json: JSONObject?): PackParams {
        json ?: return PackParams()
        return PackParams(
            temperature = json.optDouble("temperature", 0.8),
            maxTokens = json.optInt("max_tokens", 200),
            candidates = json.optInt("candidates", 3).coerceIn(1, 5)
        )
    }

    private fun verifySignature(data: ByteArray, sig: ByteArray): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(PACK_VERIFY_PUBKEY_B64, Base64.NO_WRAP))
        )
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(data)
            verify(sig)
        }
    } catch (e: Exception) {
        Log.w(TAG, "verifySignature error: ${e.javaClass.simpleName}")
        false
    }

    /** "1.5.0" 式版本比较：a < b 返回负数。非数字段按 0。 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }
}
