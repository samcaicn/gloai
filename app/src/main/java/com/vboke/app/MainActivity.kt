package com.vboke.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * AI直播助手（V播客）Android 壳。
 *
 * 加载优先级：filesDir/index.html（热更新版）→ assets/index.html（内置版）。
 *
 * 静默热更新（无 Toast/通知/对话框，失败静默，不阻塞启动）：
 *   1) onCreate 先同步应用 staging：filesDir/index.html.next 存在 → 原子重命名
 *      覆盖 index.html（毫秒级文件操作），本次启动即用新版；
 *   2) 再起后台线程静默检查：GET /api/hot/manifest 比对 sha256 → 不同则下载
 *      /api/hot/index.html → sha256 校验通过 → 写 index.html.next +
 *      hot_meta.json（staging 元数据）→ 等待下次启动生效。
 *
 * 更新源：aizhibo.tuptup.top（自有域，CF Pages）+ aizhibo-update.pages.dev 回退，
 * 由 aizhibo-update Worker 从 GitHub Actions artifact `vboke-web` 解出：
 * push 到 vboke 分支触发 CI 后即自动可下载。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        // 热更新源（按序回退）：自有域优先，pages.dev 兜底。
        private val UPDATE_BASES = arrayOf(
            "https://aizhibo.tuptup.top",
            "https://aizhibo-update.pages.dev"
        )
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 20000
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 0) 先应用上一轮已下载好的热更新包（同步、毫秒级，WebView 加载的就是新版）。
        applyPendingUpdate()

        // 沉浸式状态栏：深色主题，配合原型暗色 UI。
        window.statusBarColor = Color.parseColor("#0b0e14")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        webView = WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                // 让原型里的音频/视频（TTS 播报、直播预览）可自动播放。
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = false
            }
            setBackgroundColor(Color.parseColor("#0b0e14"))
        }

        setContentView(webView)

        // 优先加载热更新目录（应用私有 files 下），不存在则回落 assets 内置版。
        val hotFile = File(filesDir, "index.html")
        if (hotFile.exists()) {
            webView.loadUrl("file://${hotFile.absolutePath}")
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }

        // 1) 后台静默检查 + 预下载热更新包（不阻塞 UI，无任何提示；下次启动生效）。
        Thread { silentCheckUpdate() }.start()
    }

    /** 应用 staging：index.html.next 原子替换 index.html，并同步 hot_meta.json → hot_installed.json。 */
    private fun applyPendingUpdate() {
        try {
            val next = File(filesDir, "index.html.next")
            if (next.exists() && next.length() > 0L) {
                val cur = File(filesDir, "index.html")
                if (cur.exists()) cur.delete()
                if (next.renameTo(cur)) {
                    val meta = File(filesDir, "hot_meta.json")
                    val installed = File(filesDir, "hot_installed.json")
                    if (meta.exists()) {
                        if (installed.exists()) installed.delete()
                        meta.renameTo(installed)
                    }
                }
            }
        } catch (_: Exception) {
            // 静默：staging 应用失败不影响本次启动，旧版本继续可用。
        }
    }

    /** 当前生效版本的指纹：热更安装记录优先，否则取 APK 内置 assets/index.html 的 sha256。 */
    private fun currentSha(): String {
        return try {
            val installed = File(filesDir, "hot_installed.json")
            if (installed.exists()) {
                val sha = JSONObject(installed.readText()).optString("sha256")
                if (sha.isNotEmpty()) return sha
            }
            sha256Hex(assets.open("index.html").use { it.readBytes() })
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 静默热更新主流程：
     *   manifest 比对 sha256 → 下载 index.html → 校验 → 写 staging → 下次启动生效。
     * 任何异常全部吞掉，绝不打扰用户。
     */
    fun silentCheckUpdate(): Boolean {
        return try {
            val local = currentSha()
            val assetsSha = try {
                sha256Hex(assets.open("index.html").use { it.readBytes() })
            } catch (_: Exception) { "" }

            // 逐源尝试，任一源完整成功即止。
            for (base in UPDATE_BASES) {
                try {
                    val manifest = httpGetText("$base/api/hot/manifest") ?: continue
                    val remote = JSONObject(manifest).optString("sha256")
                    if (remote.isEmpty()) continue
                    if (remote == local) return true // 已是最新，静默结束

                    // 远端与 APK 内置一致 → 清掉旧热更文件，回到内置版（防版本倒退）
                    if (assetsSha.isNotEmpty() && remote == assetsSha) {
                        File(filesDir, "index.html").delete()
                        File(filesDir, "hot_installed.json").delete()
                        File(filesDir, "hot_meta.json").delete()
                        return true
                    }

                    val html = httpGetBytes("$base/api/hot/index.html") ?: continue
                    val actual = sha256Hex(html)
                    if (actual != remote) continue // 校验失败 → 丢弃，不污染本地

                    // 写 staging（先内容后元数据；写入成功即视为"待下次启动生效"）
                    val next = File(filesDir, "index.html.next")
                    next.writeBytes(html)
                    val version = try {
                        JSONObject(manifest).optString("version")
                    } catch (_: Exception) { "" }
                    File(filesDir, "hot_meta.json").writeText(
                        JSONObject().put("sha256", remote)
                            .put("version", version)
                            .put("size", html.size)
                            .put("saved_at", System.currentTimeMillis()).toString()
                    )
                    return true
                } catch (_: Exception) {
                    // 该源失败，尝试下一源。
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 基础网络 / 哈希工具（Android 自带能力，不引第三方依赖）
    // ------------------------------------------------------------------

    private fun httpGetText(url: String): String? {
        val bytes = httpGetBytes(url) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun httpGetBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "vboke-app/1.3")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
