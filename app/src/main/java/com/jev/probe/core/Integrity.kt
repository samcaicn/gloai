package com.jev.probe.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 运行时完整性（L2 客户端绊线 + L3 服务端绑定所需的本地指纹）。
 *
 * 拆分职责：
 *  - native 层算出签名证书 SHA-256，并和**混淆存储的官方指纹**比对 → [isSignatureOfficial]。
 *    重打包一定换证书，所以这一项对"MT 管理器改两下就发"的面甲包流水线直接判 false。
 *  - native 层扫 /proc 识别 frida/Xposed/LSPatch/APatch/Substrate/Magisk 与调试器 → [injectionMask]。
 *    这是免重打包注入（LSPatch/NPatch/APatch 那套"不拆包只 hook"）的检测面。
 *  - [signatureFingerprint] 把官方指纹（运行时算的）作为 [HEADER_FP] 发往服务端，
 *    Worker 端 [EXPECTED_SIG_FP] 比对，马甲包证书≠官方 → 403 → 不能激活、不能用云 AI（L3 之墙）。
 *
 * 红色真相：**纯客户端校验能被内核级 root 绕过**。本类是"绊线"——它让克隆版
 * 在本地就跑不起来核心功能（[App] 据此降级），而真正的"墙"是服务端按官方指纹放行。
 */
object Integrity {

    private const val TAG = "JEVASSIST"

    // 注入位定义（与 native 一致）
    const val INJ_FRIDA     = 1 shl 0
    const val INJ_XPOSED    = 1 shl 1
    const val INJ_LSPATCH   = 1 shl 2
    const val INJ_APATCH    = 1 shl 3
    const val INJ_SUBSTRATE = 1 shl 4
    const val INJ_MAGISK    = 1 shl 5
    const val INJ_DEBUGGER  = 1 shl 6

    // L3 请求头名（Worker 端同名校验）
    const val HEADER_FP      = "X-Sig-Fp"
    const val HEADER_PKG     = "X-Pkg"
    const val HEADER_INSTANCE = "X-Instance"
    const val HEADER_TS      = "X-Ts"
    const val HEADER_HMAC    = "X-Hmac"

    /** 允许的包名（与 build.gradle applicationId 一致）。 */
    const val EXPECTED_PKG = "com.jev.probe"

    /** 时间戳窗口：±5 分钟，防重放。 */
    private const val TS_WINDOW_MS = 5 * 60 * 1000L

    // ---------------------------------------------------------- native 桥

    private external fun nativeCertFingerprint(cert: ByteArray): String
    private external fun nativeSignatureOfficial(cert: ByteArray): Boolean
    private external fun nativeInjectionMask(): Int
    private external fun nativeHmacKey(): ByteArray

    /** .so 是否成功加载。未加载时所有判定降级为「干净」，避免把正版用户误杀；
     *  真正的不可绕过仍在 L3 服务端（按官方签名指纹放行）。 */
    private var soLoaded = false

    init {
        try {
            System.loadLibrary("integrity")
            soLoaded = true
        } catch (e: Throwable) {
            Log.e(TAG, "integrity .so 加载失败（降级为干净，依赖服务端 L3）：${e.message}")
        }
    }

    /**
     * 取本应用签名证书 DER 字节（apkContentsSigners[0]）。拿不到返回 null。
     * minSdk=30（API 30 ≥ P），全程走 GET_SIGNING_CERTIFICATES 路径，
     * SigningInfo.apkContentsSigners 在单/多签名下都返回签名者数组。
     */
    private fun signingCert(context: Context): ByteArray? = try {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo
            ?.apkContentsSigners
            ?.firstOrNull()
            ?.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "读取签名证书失败：${e.message}")
        null
    }

    // ---------------------------------------------------------- 对外判定

    /** 运行时证书是否等于官方（native 混淆比对）。false=被重签/马甲包。 */
    fun isSignatureOfficial(context: Context): Boolean {
        if (!soLoaded) return true // .so 未加载：不本地拦截，交给 L3 服务端
        val cert = signingCert(context) ?: return false
        return try { nativeSignatureOfficial(cert) } catch (_: Throwable) { false }
    }

    /** 签名证书 SHA-256（小写十六进制），作为 L3 的 X-Sig-Fp。 */
    fun signatureFingerprint(context: Context): String {
        val cert = signingCert(context)
        if (cert != null) {
            try { return nativeCertFingerprint(cert) } catch (_: Throwable) {}
        }
        // 兜底（极少走到）：用 Java 侧 MessageDigest 也算同一值，保证 L3 头始终有内容。
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val raw = (pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()) ?: return ""
            MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    /** 注入/调试位掩码，0=干净。 */
    fun injectionMask(context: Context): Int {
        if (!soLoaded) return 0 // .so 未加载：不本地拦截，交给 L3 服务端
        return try {
            nativeInjectionMask()
        } catch (_: Throwable) {
            0
        }
    }

    fun describeMask(mask: Int): String {
        val names = mutableListOf<String>()
        if (mask and INJ_FRIDA != 0) names += "frida"
        if (mask and INJ_XPOSED != 0) names += "xposed"
        if (mask and INJ_LSPATCH != 0) names += "lspatch"
        if (mask and INJ_APATCH != 0) names += "apatch"
        if (mask and INJ_SUBSTRATE != 0) names += "substrate"
        if (mask and INJ_MAGISK != 0) names += "magisk"
        if (mask and INJ_DEBUGGER != 0) names += "debugger"
        return if (names.isEmpty()) "clean" else names.joinToString(",")
    }

    /**
     * 启动完整性校验。结果写入 [Prefs.tampered]（持久化），供 UI 与核心功能降级读取。
     * 设计：发现篡改不崩溃（避免触发系统异常监控/被当作 bug），而是置位标记、
     * 让 [App] 与核心流程在「装了也不能用」的状态下运行。
     */
    fun verify(context: Context): IntegrityResult {
        val official = isSignatureOfficial(context)
        val mask = injectionMask(context)
        val tampered = !official || mask != 0
        val result = IntegrityResult(
            officialSignature = official,
            injectionMask = mask,
            injectionText = describeMask(mask),
            tampered = tampered
        )
        // 持久化：一旦被标记篡改，即便攻击者清数据重装同证书包也不会"自愈"
        // （这里仅记录，真正的不可绕过在 L3 服务端按官方指纹放行）。
        Prefs(context).tampered = tampered
        if (tampered) {
            Log.w(TAG, "完整性校验失败：official=$official injection=${result.injectionText}")
        }
        return result
    }

    /** 生成 L3 完整性请求头（X-Sig-Fp / X-Pkg / X-Instance / X-Ts / X-Hmac）。 */
    fun integrityHeaders(context: Context, method: String, pathname: String): Map<String, String> {
        val fp = signatureFingerprint(context)
        val pkg = context.packageName
        val instance = Prefs(context).instanceId
        val ts = System.currentTimeMillis().toString()
        // 签名串（与服务端 verifyClientIntegrity 一致）：method\npathname\nts\nfp\npkg\ninstance
        // 不含 body：时间戳窗口（±5min）已防重放；body 绑定非必需且易引入不一致。
        val mac = "$method\n$pathname\n$ts\n$fp\n$pkg\n$instance"
        val hmac = hmacSha256(mac)
        return mapOf(
            HEADER_FP to fp,
            HEADER_PKG to pkg,
            HEADER_INSTANCE to instance,
            HEADER_TS to ts,
            HEADER_HMAC to hmac
        )
    }

    /**
     * HMAC-SHA256 十六进制。native 还原出原始 32 字节密钥后，取其十六进制串作为
     * HMAC 密钥（UTF-8 字节）——与服务端 `hmacHex(env.INTEGRITY_HMAC_KEY, ...)` 的
     * 处理方式一致（服务端把 hex 字符串当密钥源）。若用原始字节则当密钥，两边
     * 密钥字节不同会导致 HMAC 不匹配、被 403 误杀。
     */
    private fun hmacSha256(message: String): String {
        val raw = try { nativeHmacKey() } catch (_: Throwable) { ByteArray(0) }
        if (raw.isEmpty()) return ""
        val keyHex = raw.joinToString("") { "%02x".format(it) } // = env.INTEGRITY_HMAC_KEY 的明文
        raw.fill(0) // 原始字节用完即清零，减少内存驻留
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    data class IntegrityResult(
        val officialSignature: Boolean,
        val injectionMask: Int,
        val injectionText: String,
        val tampered: Boolean
    )
}
