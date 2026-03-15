package com.ggclaw.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private var goClawService: GoClawService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")
        
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

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

        initGoClaw()
        
        webView.loadUrl("https://clawadmin.tuptup.top")
        Log.d(TAG, "WebView loading URL")
    }

    private fun initGoClaw() {
        try {
            Log.d(TAG, "Initializing GoClaw...")
            
            val config = """
                {
                    "enabled": true,
                    "auto_start": true,
                    "ws_url": "ws://127.0.0.1:28789/ws",
                    "http_url": "http://127.0.0.1:28788"
                }
            """.trimIndent()
            
            val serviceIntent = Intent(this, GoClawService::class.java)
            serviceIntent.putExtra("config", config)
            startService(serviceIntent)
            
            Log.d(TAG, "GoClaw service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GoClaw: ${e.message}", e)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val serviceIntent = Intent(this, GoClawService::class.java)
            stopService(serviceIntent)
            Log.d(TAG, "GoClaw service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GoClaw service: ${e.message}", e)
        }
    }
}
