package com.wangzan101.mika.about

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

/** 更新包没通过校验时为什么拒绝;界面按这个给出不同的说明。 */
class ApkRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** 大小、SHA-256 对不上,或者根本读不出安装包。 */
        Corrupt,
        /** 不是 Mika,或者版本不比已装的新。 */
        NotNewer,
        /** 签名证书和已装的 Mika 不同。 */
        Signer,
    }
}

/**
 * 下载地址只接受官方仓库的 HTTPS 发布附件。之后的跳转(到 GitHub 的附件存储)由系统下载服务处理、我们管不到,
 * 所以下载完的文件一律再验:大小与 SHA-256(发布元数据给了才验)、包名、版本号、签名证书。
 */
internal object ApkIntegrity {
    private val official = "${MikaLinks.RELEASES}/download/".toHttpUrl()

    fun requireOfficialUrl(url: String) {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host == official.host && parsed.port == 443)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        require(parsed.encodedPath.startsWith(official.encodedPath))
        require(parsed.encodedPath.endsWith(".apk", ignoreCase = true))
    }

    /**
     * 下载地址只能是官方发布附件,或「内置/用户添加的镜像前缀 + 官方附件地址」。
     * 镜像本身仍由用户信任;下载完照样校验大小、摘要和签名,地址放宽不会让别的包装进来。
     */
    fun requireTrustedDownloadUrl(url: String, officialUrl: String, mirrors: List<UpdateMirror>) {
        if (url == officialUrl) {
            requireOfficialUrl(url)
            return
        }
        require(mirrors.any { url == it.prefix + officialUrl }) { "not an official or configured mirror URL" }
        val parsed = url.toHttpUrl()
        require(parsed.isHttps)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        require(parsed.encodedPath.endsWith(".apk", ignoreCase = true))
    }

    /** 给了摘要就必须是 sha256 且对得上;别的写法也算对不上,不放过。 */
    fun verify(file: File, size: Long?, digest: String?) {
        corruptUnless(file.isFile && file.length() > 0)
        if (size != null) corruptUnless(file.length() == size)
        if (digest != null) {
            corruptUnless(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            corruptUnless(sha256(file).equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }

    fun requireReleaseVersion(archiveVersion: String?, releaseVersion: String) {
        corruptUnless(archiveVersion != null && AppVersion.compare(archiveVersion, releaseVersion) == 0)
    }

    /**
     * 新包的签名证书(各证书 SHA-256)要和已装的一致;新包轮换过签名时,已装的证书要在它的签名历史里。
     * 系统安装器也会拦,这里提前拦下是为了给出说得清的提示。
     */
    fun requireSameSigner(installed: Set<String>, archive: Set<String>, archiveHistory: Set<String>) {
        if (installed.isEmpty() || archive.isEmpty()) throw ApkRejected(ApkRejected.Reason.Signer)
        if (archive != installed && !archiveHistory.containsAll(installed)) throw ApkRejected(ApkRejected.Reason.Signer)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun corruptUnless(ok: Boolean) {
        if (!ok) throw ApkRejected(ApkRejected.Reason.Corrupt)
    }
}
