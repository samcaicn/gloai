package com.jev.probe.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账户令牌（用于 WeAuto 云端鉴权 + token 计费的 bearer token）的本地加密存储。
 *
 * - 用 AndroidKeyStore 生成一把「永不导出」的 AES-256-GCM 密钥，明文只活在内存。
 * - 即使设备被 root 或 SharedPreferences 被备份导出，拿到的也只是密文。
 * - 主线程/后台线程都能用（不要求锁屏密码或生物识别）。
 *
 * 这是「加密」要求的核心落点：客户端绝不保存明文 token，也不在日志里打印 token 内容
 * （只记录长度，沿用既有约定）。
 */
object SecureStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "weauto_billing_aes"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    private var cached: SecretKey? = null

    @Synchronized
    private fun key(context: Context): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey.also { cached = it }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return kg.generateKey().also { cached = it }
    }

    /** 明文 -> Base64(IV || ciphertext)。空串原样返回空串。 */
    fun encrypt(context: Context, plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, key(context))
        }
        val iv = cipher.iv
        val enc = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(enc, 0, out, iv.size, enc.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Base64(IV || ciphertext) -> 明文。解密失败（如密钥轮换）返回空串，调用方按「未配置」处理。 */
    fun decrypt(context: Context, ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        return try {
            val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return ""
            val iv = raw.copyOfRange(0, IV_LEN)
            val enc = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(TAG_LEN_BITS, iv))
            }
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
