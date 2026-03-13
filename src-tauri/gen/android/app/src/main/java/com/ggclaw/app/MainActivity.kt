package com.ggclaw.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var goclawService: GoClawService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GoClawService.LocalBinder
            goclawService = binder.getService()
            serviceBound = true

            // 初始化 goclaw
            val config = """
                {
                    "api_url": "https://clawadmin.tuptup.top",
                    "platform": "android"
                }
            """.trimIndent()
            val result = goclawService?.initialize(config)
            android.util.Log.d("MainActivity", "GoClaw initialized: $result")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            goclawService = null
            serviceBound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 启动并绑定 GoClawService
        val intent = Intent(this, GoClawService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        webView = findViewById(R.id.webView)

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(GoClawInterface(), "GoClaw")

        // Load the app URL
        webView.loadUrl("https://clawadmin.tuptup.top")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // JavaScript 接口类
    inner class GoClawInterface {
        @JavascriptInterface
        fun sendMessage(message: String): String {
            return goclawService?.send(message) ?: "{\"error\": \"service not available\"}"
        }

        @JavascriptInterface
        fun getStatus(): String {
            return goclawService?.status() ?: "{\"error\": \"service not available\"}"
        }

        @JavascriptInterface
        fun startService(): String {
            return goclawService?.start() ?: "{\"error\": \"service not available\"}"
        }

        @JavascriptInterface
        fun stopService() {
            goclawService?.stop()
        }
    }
}
