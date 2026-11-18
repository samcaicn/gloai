package com.wangzan101.mika.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TERMINAL_URL = "https://appassets.androidplatform.net/assets/terminal/index.html"

/** 终端 WebView 的句柄:给外面的按键行发焦点/复制请求。 */
internal class TerminalWebViewHandle internal constructor(
    val view: WebView,
    private val bridge: TerminalBridge,
) {
    fun focus() {
        view.requestFocus()
        view.evaluateJavascript("mikaFocus()", null)
    }

    fun copyAll() {
        view.evaluateJavascript("mikaCopyAll()", null)
    }

    internal fun close() {
        bridge.close()
        runCatching { view.destroy() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun createTerminalWebView(context: Context, session: TerminalSession): TerminalWebViewHandle {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    val view = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.textZoom = 100
        settings.loadsImagesAutomatically = false
        setBackgroundColor(android.graphics.Color.rgb(16, 18, 16))
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.requestFocus()
                post { evaluateJavascript("mikaFocus()", null) }
            }
            false
        }
    }
    val bridge = TerminalBridge(context, view, session)
    view.addJavascriptInterface(bridge, "Bridge")
    view.loadUrl(TERMINAL_URL)
    return TerminalWebViewHandle(view, bridge)
}

/**
 * JS 与 Kotlin 之间的桥:输入按字节进 pty,输出按块写回 xterm.js。
 * 输出只在主线程喂给 WebView;输入在一个单线程上按顺序写,避免阻塞 JS 线程。
 */
internal class TerminalBridge(    private val context: Context,
    private val view: WebView,
    private val session: TerminalSession,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-terminal-input") }

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    @JavascriptInterface
    fun ready() {
        main.post {
            if (closed || ready) return@post
            ready = true
            val snapshot = session.snapshot()
            var offset = 0
            while (offset < snapshot.size) {
                val n = minOf(64 * 1024, snapshot.size - offset)
                feed(snapshot.copyOfRange(offset, offset + n))
                offset += n
            }
            scope.launch {
                session.output.collect { chunk -> feed(chunk) }
            }
        }
    }

    @JavascriptInterface
    fun write(base64: String) {
        if (closed) return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        runCatching { writes.execute { if (!closed) runCatching { session.writeBytes(bytes) } } }
    }

    @JavascriptInterface
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        session.resize(cols, rows)
    }

    @JavascriptInterface
    fun copy(text: String) {
        main.post {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return@post
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(context, tr(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
        }
    }

    fun close() {
        closed = true
        ready = false
        scope.cancel()
        writes.shutdownNow()
    }

    private fun feed(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        view.evaluateJavascript("mikaWrite('$base64')", null)
    }
}
