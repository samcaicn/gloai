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

/**
 * AI直播助手 Android 壳。
 *
 * 只做一件事：全屏加载 assets/index.html（原型本体）。
 * 原型是纯前端单文件，所有业务逻辑都在 HTML 里；壳负责提供 Android 运行环境，
 * 后续接口对接（短视频 / 数字人直播）也只需热替换 assets/index.html，
 * 无需改动或重装壳 —— 这与 CI 的 artifact 热更新链路一致。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val hotFile = java.io.File(filesDir, "index.html")
        if (hotFile.exists()) {
            webView.loadUrl("file://${hotFile.absolutePath}")
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
