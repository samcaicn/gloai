package com.jev.probe.core.scriptpack

import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 话术包密码学原语 + 混淆存放的 master key。
 *
 * 与生成端 tools/scriptpack/packcrypto.py **逐字节一致**（格式与参数见
 * docs/scriptpack-spec.md §5）。只用 javax.crypto / java.security，无第三方依赖：
 *
 *  - AES-256-GCM（12B nonce / 16B tag / AAD = 文件中密文之前的全部字节）
 *  - HKDF-SHA256（Android 无内置 HKDF，这里用 HmacSHA256 手写，与 Python 侧同构）
 *  - ECDSA P-256 / SHA-256 / DER（[ScriptPack] 验签用）
 *
 * master key 不放明文：XOR 0x5A 后逆序存放，运行时还原。
 * **这不是安全边界**（静态逆向可还原）——真正的防伪造靠签名私钥；master 泄密
 * 最多让攻击者解自己的包，伪造不了别人的。正式发行建议改构建期注入。
 */
internal object AppPackCrypto {

    private const val TAG = "JEVASSIST"
    private const val INFO_PREFIX = "weauto-scriptpack-v1|"

    /** 测试 master（32B）。正式发行替换；生成端同步改 --master-hex / PACK_MASTER_HEX。 */
    private const val MASTER_XOR = 0x5A
    private val MASTER_OBFUSCATED: ByteArray =
        "5045746716013025D4C75146701534D70661502574C7164152FCBF891B2671C5"
            .let { hex ->
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }

    fun masterKey(): ByteArray {
        val n = MASTER_OBFUSCATED.size
        return ByteArray(n) { i ->
            (MASTER_OBFUSCATED[n - 1 - i].toInt() xor MASTER_XOR).toByte()
        }
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** SHA-256(DeviceId 稳定值) 前 16B——与生成端 device_hash_hex() 同值，可互相粘贴。 */
    fun deviceHash(deviceId: String): ByteArray =
        sha256(deviceId.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)

    /**
     * HKDF-SHA256（RFC 5869）。salt 为空时等价于全零盐（本格式 salt 恒为 16B，
     * 空盐分支只为对齐 RFC 语义）。
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: String, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < len) {
            mac.reset()
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(len)
    }

    /**
     * 内容密钥 = HKDF-SHA256(master || deviceHash, salt=kdf_salt,
     * info="weauto-scriptpack-v1|<pack_id>", 32)。
     * 未绑设备时 deviceHash 传 16B 全零（与生成端一致）。
     */
    fun deriveKey(master: ByteArray, packId: String, salt: ByteArray, deviceHash: ByteArray): ByteArray {
        require(deviceHash.size == 16) { "deviceHash 必须为 16B" }
        return hkdfSha256(master + deviceHash, salt, INFO_PREFIX + packId, 32)
    }

    /**
     * AES-256-GCM 解密。ctTag 末 16B 为认证 tag；AAD 必须传文件中密文之前的
     * 全部字节。tag 不符（密钥错 / 包被改 / 设备不匹配）抛异常，调用方按拒收处理。
     */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ctTag: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == 12) { "nonce 必须为 12B" }
        require(ctTag.size > 16) { "密文长度非法" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ctTag)
    }

    /** 只记失败类型，绝不记录任何密钥/密文/话术内容。 */
    fun logReject(stage: String, code: String) {
        Log.w(TAG, "scriptpack reject at $stage: $code")
    }
}
